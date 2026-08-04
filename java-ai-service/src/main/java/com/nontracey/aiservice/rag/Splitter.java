package com.nontracey.aiservice.rag;

import com.nontracey.aiservice.dto.Dtos.Topic;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文档切分器(RAG 的切块环节):把 topic 的 learningCards 拆成可检索的 {@link Chunk}。
 *
 * <p><b>架构位置</b>:rag 模块,位于 Loader 之后、VectorStoreService 之前。产出的 chunk
 * 携带丰富 metadata(topic_id/title/card_type 等),既供检索来源标注,也供租户过滤。
 *
 * <p><b>切块策略(与 B 同策略)</b>:
 * <ul>
 *   <li>短卡片(checklist / compareTable / code / diagram)整张入库,避免破坏结构完整性。</li>
 *   <li>长文(explain / interviewAnswer)按 {@link #CHUNK_SIZE} 递归切分,带 {@link #CHUNK_OVERLAP} 重叠。</li>
 *   <li>topic.summary 作为独立 summary chunk 入库。</li>
 * </ul>
 *
 * <p><b>同构映射</b>:对应 B 项目 {@code app/rag/splitter.py}。
 */
@Service
public class Splitter {

    /** 整张入库、不切分的卡片类型(结构化内容,切开会破坏语义)。 */
    private static final Set<String> WHOLE_CARD_TYPES = Set.of("checklist", "compareTable", "code", "diagram");
    /** 需要按长度递归切分的卡片类型(长文本)。 */
    private static final Set<String> SPLIT_CARD_TYPES = Set.of("explain", "interviewAnswer");

    /** 单 chunk 目标最大字符数。 */
    public static final int CHUNK_SIZE = 500;
    /** 相邻 chunk 重叠字符数,避免语义在边界被截断。 */
    public static final int CHUNK_OVERLAP = 80;

    /**
     * 把单个 topic 的全部 learningCards(及 summary)切成 chunk 列表。
     *
     * @param topic 知识库条目
     * @return chunk 列表(每个都带 topic 级 + 卡片级 metadata)
     */
    public List<Chunk> splitTopic(Topic topic) {
        List<Chunk> out = new ArrayList<>();
        // topic 级公共 metadata,所有 chunk 共享,供检索来源标注与过滤
        Map<String, Object> base = new HashMap<>();
        base.put("topic_id", topic.id());
        base.put("domain", topic.domain());
        base.put("category", topic.category());
        base.put("title", topic.title());
        base.put("tags", topic.tags());
        base.put("difficulty", topic.difficulty());

        for (Map<String, Object> card : topic.learningCards()) {
            String ctype = (String) card.getOrDefault("type", "explain");
            String title = (String) card.getOrDefault("title", "");
            String content = (String) card.getOrDefault("content", "");
            if (content == null || content.isBlank()) continue;

            // 卡片级 metadata:在 topic 级基础上追加 card_type / card_title
            Map<String, Object> meta = new HashMap<>(base);
            meta.put("card_type", ctype);
            meta.put("card_title", title);

            if (WHOLE_CARD_TYPES.contains(ctype)) {
                // 结构化短卡整张入库,避免切碎破坏语义
                out.add(new Chunk(content, meta));
                // diagram 卡片若带纯文本 fallback,额外入一份文本版,便于纯文本检索
                if ("diagram".equals(ctype) && card.get("fallback") instanceof String fb && !fb.isBlank()) {
                    Map<String, Object> m2 = new HashMap<>(meta);
                    m2.put("card_title", title + "(文本版)");
                    out.add(new Chunk(fb, m2));
                }
            } else if (SPLIT_CARD_TYPES.contains(ctype)) {
                // 长文本递归切分,带 overlap
                for (String piece : splitRecursive(content, CHUNK_SIZE, CHUNK_OVERLAP)) {
                    out.add(new Chunk(piece, meta));
                }
            } else {
                // 未知类型兜底:整张入库
                out.add(new Chunk(content, meta));
            }
        }
        // topic 摘要单独成一个 chunk,便于概览类问题命中
        if (topic.summary() != null && !topic.summary().isBlank()) {
            Map<String, Object> m = new HashMap<>(base);
            m.put("card_type", "summary");
            m.put("card_title", "摘要");
            out.add(new Chunk(topic.summary(), m));
        }
        return out;
    }

    /**
     * 批量切分多个 topic(ingest 流程调用)。
     *
     * @param topics topic 列表
     * @return 全部 chunk(顺序与 topic 顺序一致)
     */
    public List<Chunk> splitAll(List<Topic> topics) {
        List<Chunk> out = new ArrayList<>();
        for (Topic t : topics) out.addAll(splitTopic(t));
        return out;
    }

    /**
     * 最简递归切分(与 B 同策略)。
     *
     * <p>三步走:
     * <ol>
     *   <li>按分隔符优先级({@code \n\n > \n > 句号/点 > 空格})逐级拆分,尽量在自然语义边界断开;
     *       一旦所有片段都不超过 size 就提前结束。</li>
     *   <li>对仍超长的片段硬切(每 size 一段)兜底,保证没有超大 chunk。</li>
     *   <li>把相邻小片段合并到接近 size 的块,并在块之间保留 overlap 个字符的重叠,
     *       避免关键语义正好被截断在边界。</li>
     * </ol>
     *
     * @param text    原始长文本
     * @param size    单块目标最大长度
     * @param overlap 相邻块重叠字符数
     * @return 切分后的文本片段列表
     */
    private List<String> splitRecursive(String text, int size, int overlap) {
        if (text.length() <= size) return List.of(text);
        String[] seps = {"\n\n", "\n", "。", ".", "!", "?", ";", " "};
        List<String> pieces = new ArrayList<>(List.of(text));
        for (String sep : seps) {
            List<String> next = new ArrayList<>();
            for (String p : pieces) {
                if (p.length() <= size) next.add(p);
                else {
                    for (String s : p.split(java.util.regex.Pattern.quote(sep))) {
                        if (!s.isEmpty()) next.add(s);
                    }
                }
            }
            pieces = next;
            if (pieces.stream().allMatch(p -> p.length() <= size)) break;
        }
        // 兜底硬切
        List<String> fin = new ArrayList<>();
        for (String p : pieces) {
            if (p.length() <= size) fin.add(p);
            else for (int i = 0; i < p.length(); i += size) fin.add(p.substring(i, Math.min(p.length(), i + size)));
        }
        // 合并带 overlap
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String p : fin) {
            if (buf.length() > 0 && buf.length() + p.length() + 1 > size) {
                out.add(buf.toString().trim());
                buf = new StringBuilder(buf.substring(Math.max(0, buf.length() - overlap))).append(p);
            } else {
                buf.append(p);
            }
        }
        if (!buf.toString().trim().isEmpty()) out.add(buf.toString().trim());
        return out;
    }

    /**
     * 切块后的最小检索/入库单元。
     *
     * @param text     chunk 文本(用于 embedding 与 BM25 索引)
     * @param metadata 元数据(topic_id/title/card_type/tags 等,供来源标注与租户过滤)
     */
    public record Chunk(String text, Map<String, Object> metadata) {}
}
