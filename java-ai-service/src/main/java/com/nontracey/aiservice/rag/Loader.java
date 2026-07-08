package com.nontracey.aiservice.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** 知识库加载器:manifest 驱动,三层数据源降级(与 B 同策略)。
 * 优先级:KB_CONTENT_PATH > KB_CONTENT_URL > KB_SAMPLE_PATH。只入 status=="production"。 */
@Service
public class Loader {

    private static final Logger log = LoggerFactory.getLogger(Loader.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AppProperties props;
    private final WebClient webClient;

    private final Map<String, Topic> byId = new ConcurrentHashMap<>();
    private String contentVersion = "";

    public Loader(AppProperties props, WebClient webClient) {
        this.props = props;
        this.webClient = webClient;
    }

    public synchronized int load(String sourceOverride) {
        List<Topic> topics;
        if (sourceOverride != null && !sourceOverride.isBlank()) {
            topics = loadFromLocal(new File(sourceOverride));
        } else if (!props.kb().contentPath().isBlank()) {
            topics = loadFromLocal(new File(props.kb().contentPath()));
        } else if (!props.kb().contentUrl().isBlank()) {
            try {
                topics = loadFromRemote(props.kb().contentUrl());
            } catch (Exception e) {
                log.warn("远程 manifest 拉取失败,降级到样例:{}", e.getMessage());
                topics = loadFromSample();
            }
        } else {
            topics = loadFromSample();
        }

        byId.clear();
        int prod = 0;
        for (Topic t : topics) {
            if ("production".equals(t.status())) {
                byId.put(t.id(), t);
                prod++;
            }
        }
        log.info("知识库加载完成:version={}, production topics={}", contentVersion, prod);
        return prod;
    }

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

    @SuppressWarnings("unchecked")
    private List<Topic> loadFromLocal(File root) {
        try {
            File manifest = root.isDirectory() ? new File(root, "manifest.json") : root;
            Map<String, Object> m = mapper.readValue(manifest, Map.class);
            contentVersion = (String) m.getOrDefault("contentVersion", "local-unknown");
            List<Topic> out = new ArrayList<>();
            if (root.isDirectory()) {
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

    @SuppressWarnings("unchecked")
    private List<Topic> loadFromRemote(String url) {
        Map<String, Object> manifest = webClient.get().uri(url).retrieve().bodyToMono(Map.class).block();
        if (manifest == null) return List.of();
        contentVersion = (String) manifest.getOrDefault("contentVersion", "remote-unknown");
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
                            log.warn("拉取 topic 失败 {}:{}", tUrl, e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("拉取 domain 失败 {}:{}", dUrl, e.getMessage());
            }
        }
        return out;
    }

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

    public Topic get(String id) { return byId.get(id); }
    public List<Topic> list() { return new ArrayList<>(byId.values()); }
    public int count() { return byId.size(); }
    public String contentVersion() { return contentVersion; }
}
