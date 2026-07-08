package com.nontracey.aiservice.rag;

import com.nontracey.aiservice.dto.Dtos.Topic;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 文档切分:把 topic 的 learningCards 拆成 chunk。
 * 长文(explain/interviewAnswer)按 chunk_size 切;短卡(checklist/code/compareTable/diagram)整张入库。 */
@Service
public class Splitter {

    private static final Set<String> WHOLE_CARD_TYPES = Set.of("checklist", "compareTable", "code", "diagram");
    private static final Set<String> SPLIT_CARD_TYPES = Set.of("explain", "interviewAnswer");

    public static final int CHUNK_SIZE = 500;
    public static final int CHUNK_OVERLAP = 80;

    public List<Chunk> splitTopic(Topic topic) {
        List<Chunk> out = new ArrayList<>();
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

            Map<String, Object> meta = new HashMap<>(base);
            meta.put("card_type", ctype);
            meta.put("card_title", title);

            if (WHOLE_CARD_TYPES.contains(ctype)) {
                out.add(new Chunk(content, meta));
                if ("diagram".equals(ctype) && card.get("fallback") instanceof String fb && !fb.isBlank()) {
                    Map<String, Object> m2 = new HashMap<>(meta);
                    m2.put("card_title", title + "(文本版)");
                    out.add(new Chunk(fb, m2));
                }
            } else if (SPLIT_CARD_TYPES.contains(ctype)) {
                for (String piece : splitRecursive(content, CHUNK_SIZE, CHUNK_OVERLAP)) {
                    out.add(new Chunk(piece, meta));
                }
            } else {
                out.add(new Chunk(content, meta));
            }
        }
        if (topic.summary() != null && !topic.summary().isBlank()) {
            Map<String, Object> m = new HashMap<>(base);
            m.put("card_type", "summary");
            m.put("card_title", "摘要");
            out.add(new Chunk(topic.summary(), m));
        }
        return out;
    }

    public List<Chunk> splitAll(List<Topic> topics) {
        List<Chunk> out = new ArrayList<>();
        for (Topic t : topics) out.addAll(splitTopic(t));
        return out;
    }

    /** 最简递归切分(与 B 同策略)。 */
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

    public record Chunk(String text, Map<String, Object> metadata) {}
}
