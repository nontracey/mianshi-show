package com.nontracey.aiservice.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nontracey.aiservice.common.TenantContext;
import com.nontracey.aiservice.config.AppProperties;
import com.nontracey.aiservice.dto.Dtos.Topic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库加载器(RAG 的 L / Loader 环节):manifest 驱动,三层数据源降级(与 B 同策略)。
 *
 * <p><b>架构位置</b>:rag 模块入口。把外部知识库 JSON 读成 {@link Topic} 领域对象,供
 * {@link Splitter} 切块、{@link VectorStoreService} 入库,以及出题/评估按 id 取 topic。
 *
 * <p><b>数据源优先级(三层降级)</b>:
 * <ol>
 *   <li>{@code KB_CONTENT_PATH}:本地目录/manifest(最高优先)。</li>
 *   <li>{@code KB_CONTENT_URL}:远程 manifest(HTTP 拉取,失败降级到样例)。</li>
 *   <li>{@code KB_SAMPLE_PATH}:内置样例知识库(兜底)。</li>
 * </ol>
 * 只入库 {@code status == "production"} 的 topic。
 *
 * <p><b>租户隔离</b>:{@code byTenant} 是 {@code Map<tenantId, Map<topicId, Topic>>},
 * 每个租户独立持有一份知识库集合;读写都用 {@link TenantContext#get()} 定位当前租户的桶。
 *
 * <p><b>同构映射</b>:对应 B 项目 {@code app/rag/loader.py}。
 */
@Service
public class Loader {

    private static final Logger log = LoggerFactory.getLogger(Loader.class);
    /** JSON 解析器(manifest / domain / topic 均为 JSON)。 */
    private final ObjectMapper mapper = new ObjectMapper();
    /** 应用配置,提供三层数据源路径/URL。 */
    private final AppProperties props;
    /** 用于远程拉取 manifest 与 topic 的 HTTP 客户端。 */
    private final WebClient webClient;

    /** 租户隔离的知识库:tenantId -> (topicId -> Topic)。 */
    private final Map<String, Map<String, Topic>> byTenant = new ConcurrentHashMap<>();
    /** 当前加载的知识库内容版本号(来自 manifest 的 contentVersion)。 */
    private String contentVersion = "";

    public Loader(AppProperties props, WebClient webClient) {
        this.props = props;
        this.webClient = webClient;
    }

    /**
     * 加载知识库到当前租户的桶,返回入库的 production topic 数量。
     *
     * <p>步骤:按三层优先级读取 topic 列表 -> 清空当前租户旧数据 -> 只保留 production 的写入。
     *
     * @param sourceOverride 可选的本地路径覆盖(对应 /api/ingest 的 source 字段);为空走配置降级
     * @return 成功入库的 production topic 数
     */
    public synchronized int load(String sourceOverride) {
        // 三层数据源选择:请求覆盖 > 本地 path > 远程 url(失败降级 sample)> sample
        List<Topic> topics;
        if (sourceOverride != null && !sourceOverride.isBlank()) {
            topics = loadFromLocal(new File(sourceOverride));
        } else if (!props.kb().contentPath().isBlank()) {
            topics = loadFromLocal(new File(props.kb().contentPath()));
        } else if (!props.kb().contentUrl().isBlank()) {
            try {
                topics = loadFromRemote(props.kb().contentUrl());
            } catch (Exception e) {
                // 远程不可用时不阻断,降级到内置样例,保证开发/离线可用
                log.warn("远程 manifest 拉取失败,降级到样例:{}", e.getMessage());
                topics = loadFromSample();
            }
        } else {
            topics = loadFromSample();
        }

        // 定位当前租户的桶,先清空再全量重建,避免新旧数据混杂
        String tenant = TenantContext.get();
        Map<String, Topic> byId = byTenant.computeIfAbsent(tenant, k -> new ConcurrentHashMap<>());
        byId.clear();
        int prod = 0;
        for (Topic t : topics) {
            // 只保留 production 状态的 topic,draft/废弃条目不入库
            if ("production".equals(t.status())) {
                byId.put(t.id(), t);
                prod++;
            }
        }
        log.info("知识库加载完成:tenant={}, version={}, production topics={}", tenant, contentVersion, prod);
        return prod;
    }

    /**
     * 从内置样例知识库(单个 JSON 文件)加载。
     *
     * <p>样例文件是自包含结构:{@code {contentVersion, topics:[...]}}。
     *
     * @return topic 列表
     * @throws RuntimeException 文件不存在或解析失败
     */
    @SuppressWarnings("unchecked")
    private List<Topic> loadFromSample() {
        try {
            File f = resolveSample();
            Map<String, Object> data = mapper.readValue(f, Map.class);
            contentVersion = (String) data.getOrDefault("contentVersion", "sample-unknown");
            List<Map<String, Object>> raw = (List<Map<String, Object>>) data.get("topics");
            return raw.stream().map(this::toTopic).toList();
        } catch (Exception e) {
            throw new RuntimeException("加载样例知识库失败:" + e.getMessage(), e);
        }
    }

    /**
     * 从本地目录加载(manifest 驱动的分层目录结构)。
     *
     * <p>目录约定:{@code root/manifest.json} 声明 domains,每个 domain 的 entry 指向一个 domain JSON,
     * domain JSON 内的 categories 又各自指向若干 topic JSON 文件。
     *
     * @param root 本地知识库目录(或直接是某个 manifest.json 文件)
     * @return topic 列表(root 不是目录时返回空列表)
     * @throws RuntimeException manifest 读取/解析失败
     */
    @SuppressWarnings("unchecked")
    private List<Topic> loadFromLocal(File root) {
        try {
            // root 既可以是目录(取其中 manifest.json),也可以直接是 manifest 文件
            File manifest = root.isDirectory() ? new File(root, "manifest.json") : root;
            Map<String, Object> m = mapper.readValue(manifest, Map.class);
            contentVersion = (String) m.getOrDefault("contentVersion", "local-unknown");
            List<Topic> out = new ArrayList<>();
            if (root.isDirectory()) {
                // manifest.domains -> 逐个 domain -> categories -> topics 逐层下钻
                List<Map<String, Object>> domains = (List<Map<String, Object>>) m.get("domains");
                for (Map<String, Object> d : domains) {
                    File df = new File(root, (String) d.get("entry"));
                    if (!df.exists()) continue;
                    Map<String, Object> dData = mapper.readValue(df, Map.class);
                    List<Map<String, Object>> cats = (List<Map<String, Object>>) dData.get("categories");
                    for (Map<String, Object> c : cats) {
                        for (String tp : (List<String>) c.get("topics")) {
                            File tf = new File(root, tp);
                            if (tf.exists()) {
                                out.add(toTopic(mapper.readValue(tf, Map.class)));
                            }
                        }
                    }
                }
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("本地知识库加载失败:" + e.getMessage(), e);
        }
    }

    /**
     * 从远程 URL 加载(HTTP 拉取 manifest,再逐级拉取 domain / topic)。
     *
     * <p>单个 domain 或 topic 拉取失败只记录 warning、跳过该条,不中断整体加载。
     *
     * @param url manifest 的完整 URL
     * @return topic 列表(manifest 为空时返回空列表)
     */
    @SuppressWarnings("unchecked")
    private List<Topic> loadFromRemote(String url) {
        // 先拉 manifest;.block() 把 Mono 转同步值(Loader 加载走同步流程)
        Map<String, Object> manifest = webClient.get().uri(url).retrieve().bodyToMono(Map.class).block();
        if (manifest == null) return List.of();
        contentVersion = (String) manifest.getOrDefault("contentVersion", "remote-unknown");
        // 以 manifest 所在"目录"为 base,拼接相对路径的 domain / topic
        String base = url.substring(0, url.lastIndexOf('/'));
        List<Topic> out = new ArrayList<>();
        List<Map<String, Object>> domains = (List<Map<String, Object>>) manifest.get("domains");
        for (Map<String, Object> d : domains) {
            String dUrl = base + "/" + d.get("entry");
            try {
                Map<String, Object> dData = webClient.get().uri(dUrl).retrieve().bodyToMono(Map.class).block();
                if (dData == null) continue;
                for (Map<String, Object> c : (List<Map<String, Object>>) dData.get("categories")) {
                    for (String tp : (List<String>) c.get("topics")) {
                        String tUrl = base + "/" + tp;
                        try {
                            Map<String, Object> t = webClient.get().uri(tUrl).retrieve().bodyToMono(Map.class).block();
                            if (t != null) out.add(toTopic(t));
                        } catch (Exception e) {
                            // 单条 topic 失败不中断整体
                            log.warn("拉取 topic 失败 {}:{}", tUrl, e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                // 单个 domain 失败不中断整体
                log.warn("拉取 domain 失败 {}:{}", dUrl, e.getMessage());
            }
        }
        return out;
    }

    /**
     * 把原始 JSON Map 映射成强类型 {@link Topic}。
     *
     * <p>所有字段都用 getOrDefault 兜底,缺失字段给安全默认值,避免 NPE。
     *
     * @param m 单个 topic 的 JSON Map
     * @return Topic 领域对象
     */
    @SuppressWarnings("unchecked")
    private Topic toTopic(Map<String, Object> m) {
        return new Topic(
                (String) m.get("id"),
                (String) m.getOrDefault("domain", ""),
                (String) m.getOrDefault("category", ""),
                (String) m.getOrDefault("title", ""),
                (String) m.getOrDefault("summary", ""),
                (List<String>) m.getOrDefault("tags", List.of()),
                ((Number) m.getOrDefault("difficulty", 3)).intValue(),
                (String) m.getOrDefault("status", ""),
                (String) m.getOrDefault("interviewFrequency", ""),
                (String) m.getOrDefault("interviewerFocus", ""),
                (List<Map<String, Object>>) m.getOrDefault("learningCards", List.of()),
                (List<Map<String, Object>>) m.getOrDefault("recallPrompts", List.of()),
                (Map<String, Object>) m.getOrDefault("rubric", Map.of())
        );
    }

    /**
     * 解析样例知识库文件路径。
     *
     * <p>绝对路径直接返回;相对路径则从当前工作目录逐级向上找 {@code data/<文件名>},
     * 兼容从仓库根或子项目目录启动,不依赖具体 CWD。找不到时兜底按相对 CWD 解析。
     *
     * @return 样例知识库 File
     */
    private File resolveSample() {
        Path p = Path.of(props.kb().samplePath());
        if (p.isAbsolute()) return p.toFile();
        // 相对路径：从工作目录逐级向上找 data/<文件名>，兼容从仓库根或子项目目录启动，
        // 不依赖具体 CWD（换电脑/换目录 clone 下来都能跑）。
        String tail = p.getFileName().toString();
        for (Path cur = Path.of(System.getProperty("user.dir")).toAbsolutePath(); cur != null; cur = cur.getParent()) {
            File candidate = cur.resolve("data").resolve(tail).toFile();
            if (candidate.isFile()) return candidate;
        }
        // 兜底：按相对 CWD 解析
        return Path.of(System.getProperty("user.dir")).resolve(p).normalize().toFile();
    }

    /**
     * 按 id 读取当前租户的 topic。
     *
     * @param id topic id
     * @return 对应 Topic;不存在时返回 null
     */
    public Topic get(String id) {
        return byTenant.getOrDefault(TenantContext.get(), Map.of()).get(id);
    }

    /**
     * 列出当前租户的全部 topic。
     *
     * @return topic 列表(拷贝,修改不影响内部存储)
     */
    public List<Topic> list() {
        return new ArrayList<>(byTenant.getOrDefault(TenantContext.get(), Map.of()).values());
    }

    /**
     * 当前租户已加载的 topic 数量。
     *
     * @return topic 数
     */
    public int count() {
        return byTenant.getOrDefault(TenantContext.get(), Map.of()).size();
    }

    /** 当前知识库内容版本号(来自 manifest 的 contentVersion)。 */
    public String contentVersion() { return contentVersion; }
}
