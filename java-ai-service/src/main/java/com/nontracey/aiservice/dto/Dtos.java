package com.nontracey.aiservice.dto;

import java.util.List;
import java.util.Map;

/**
 * 请求/响应 DTO 集合,三语言(Java / Python / .NET)对外契约一致(见 docs/00 §4)。
 *
 * <p><b>架构位置</b>:dto 层,集中定义所有 HTTP 接口的入参/出参与内部领域对象(topic)。
 * 全部用 Java record 表达,天然不可变、自带 equals/hashCode/toString,Jackson 可直接序列化。
 *
 * <p><b>设计点</b>:
 * <ul>
 *   <li>按接口分组(见各分隔注释),字段名与 B/D 项目保持一致,保证跨语言客户端无感切换。</li>
 *   <li>{@link Topic} 是知识库条目的领域模型,由 Loader 从 JSON 反序列化而来,供 RAG / 出题 / 评估复用。</li>
 * </ul>
 *
 * <p><b>同构映射</b>:对应 B 项目 {@code app/schemas.py}(pydantic 模型)。
 * 私有构造器禁止实例化本工具类。
 */
public final class Dtos {

    private Dtos() {}

    // ---------- /health ----------
    /**
     * 健康检查响应数据。
     *
     * @param status           服务状态,固定 "ok"
     * @param version          服务版本号
     * @param llmModel         当前使用的 LLM 模型名
     * @param vectorStore      向量库实现("memory"/"pgvector")
     * @param kbSource         实际生效的知识库来源(路径或 URL)
     * @param llmReachable     LLM 是否可达(当前实现固定 true)
     * @param vectorStoreReady 向量库是否已加载数据(Loader 中 topic 数 > 0)
     */
    public record HealthData(String status, String version, String llmModel, String vectorStore,
                             String kbSource, boolean llmReachable, boolean vectorStoreReady) {}

    // ---------- /api/ingest ----------
    /**
     * 知识库入库请求。
     *
     * @param source 可选的数据源覆盖值(本地路径);为空则按配置的三层降级加载
     */
    public record IngestReq(String source) {}

    /**
     * 知识库入库响应。
     *
     * @param count          成功入库的 production topic 数量
     * @param chunks         切分后写入向量库的 chunk 总数
     * @param contentVersion 知识库内容版本号(来自 manifest)
     */
    public record IngestData(int count, int chunks, String contentVersion) {}

    // ---------- /api/ask ----------
    /**
     * RAG 提问请求。
     *
     * @param question 用户问题文本
     * @param topK     检索返回条数(可选,默认 4)
     * @param stream   是否流式(当前 /api/ask 未启用流式,预留字段)
     */
    public record AskReq(String question, Integer topK, boolean stream) {}

    /**
     * 答案来源引用(对应检索命中的一个 topic)。
     *
     * @param id       topic id
     * @param topic    topic 标题
     * @param score    相关度分数(混合检索融合分或向量余弦分)
     * @param cardType 命中 chunk 所属卡片类型(explain/checklist/...)
     */
    public record Source(String id, String topic, double score, String cardType) {}

    /**
     * RAG 提问响应。
     *
     * @param answer  LLM 生成的答案
     * @param sources 引用的来源列表
     * @param usage   附加信息(如语义缓存命中时带 cache_hit=true)
     */
    public record AskData(String answer, List<Source> sources, Map<String, Object> usage) {
        /** 构造仅含答案、无来源/无 usage 的响应(用于空检索等场景)。 */
        public static AskData empty(String answer) {
            return new AskData(answer, List.of(), Map.of());
        }
    }

    // ---------- /api/interview/question ----------
    /**
     * 出题请求。
     *
     * @param topic      topic id
     * @param difficulty 难度过滤(可选)
     * @param count      期望出题数量(0 时按 1 处理)
     */
    public record QuestionReq(String topic, Integer difficulty, int count) {}

    /**
     * 单个面试题。
     *
     * @param questionId 题目 id(通常为 topicId.recall.N)
     * @param prompt     题面文本
     * @param difficulty 难度等级
     */
    public record Question(String questionId, String prompt, int difficulty) {}

    /**
     * 出题响应。
     *
     * @param questions 生成的题目列表
     */
    public record QuestionData(List<Question> questions) {}

    // ---------- /api/interview/evaluate ----------
    /**
     * 答案评估请求。
     *
     * @param questionId 被评估题目的 id
     * @param userAnswer 候选人(用户)的作答文本
     * @param stream     是否流式(当前未启用,预留字段)
     */
    public record EvaluateReq(String questionId, String userAnswer, boolean stream) {}

    /**
     * 评估结果(LLM-as-Judge 的结构化输出)。
     *
     * @param score           总分(0-100)
     * @param dimensionScores 各维度分(coverage/accuracy/interviewExpression/depth)
     * @param hit             答中的要点
     * @param missed          遗漏的要点
     * @param mistakes        回答中的错误
     * @param feedback        综合文字反馈
     * @param degraded        是否降级结果(LLM 不可用/输出无法解析时为 true)
     */
    public record Evaluation(int score, Map<String, Integer> dimensionScores,
                             List<String> hit, List<String> missed, List<String> mistakes,
                             String feedback, boolean degraded) {}

    /**
     * 评估响应(对 {@link Evaluation} 的薄封装,保持与 B/D 字段结构一致)。
     *
     * @param evaluation 评估结果
     */
    public record EvaluateData(Evaluation evaluation) {}

    // ---------- /api/agent/session ----------
    /**
     * Agent 模拟面试会话请求(WebFlux SSE 端点入参)。
     *
     * @param topic  面试主题(topic id 或关键词)
     * @param rounds 期望的问答轮数(小于 1 时按 1 处理)
     */
    public record AgentSessionReq(String topic, int rounds) {}

    // ---------- /api/metrics ----------
    /**
     * 运维指标快照。
     *
     * @param requestsTotal 累计请求数
     * @param tokensTotal   累计 token 消耗
     * @param cacheHits     语义缓存命中次数
     * @param cacheMisses   语义缓存未命中次数
     * @param cacheHitRate  缓存命中率(0-1)
     * @param avgLatencyMs  平均请求耗时(毫秒)
     * @param llmCalls      LLM 调用次数
     */
    public record MetricsData(int requestsTotal, int tokensTotal, int cacheHits, int cacheMisses,
                              double cacheHitRate, double avgLatencyMs, int llmCalls) {}

    // ---------- topic(知识库条目)----------
    /**
     * 知识库中的一个主题条目,是 RAG / 出题 / 评估共用的领域模型。
     *
     * @param id                唯一 id(如 java.concurrency.thread-pool)
     * @param domain            所属领域(如 java)
     * @param category          所属分类(如 concurrency)
     * @param title             标题
     * @param summary           摘要(切块时作为 summary chunk 入库)
     * @param tags              标签列表
     * @param difficulty        难度等级
     * @param status            状态;仅 "production" 会被 Loader 入库
     * @param interviewFrequency 面试出现频率描述
     * @param interviewerFocus  面试官关注点描述
     * @param learningCards     学习卡片列表(explain/checklist/code/compareTable/diagram 等)
     * @param recallPrompts     人工撰写的召回题(出题直接复用)
     * @param rubric            评分标准(mustHave/goodToHave/commonMistakes/scoreWeights,评估用)
     */
    public record Topic(String id, String domain, String category, String title, String summary,
                       List<String> tags, int difficulty, String status, String interviewFrequency,
                       String interviewerFocus, List<Map<String, Object>> learningCards,
                       List<Map<String, Object>> recallPrompts, Map<String, Object> rubric) {}
}
