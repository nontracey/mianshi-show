package com.nontracey.aiservice.api;

import com.nontracey.aiservice.common.ApiResponse;
import com.nontracey.aiservice.common.TraceIdFilter;
import com.nontracey.aiservice.dto.Dtos;
import com.nontracey.aiservice.infra.Guardrails;
import com.nontracey.aiservice.infra.Metrics;
import com.nontracey.aiservice.interview.EvaluatorService;
import com.nontracey.aiservice.interview.QuestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 面试接口控制器:/api/interview/question(出题)与 /api/interview/evaluate(作答评估)。
 *
 * <p><b>架构位置</b>:api 层。组合 QuestionService(出题)、EvaluatorService(LLM-as-Judge 评估)、
 * Guardrails(评估输入护栏)与 Metrics(埋点)。
 *
 * <p><b>同构映射</b>:对应 B 项目 {@code app/api/interview.py}。
 */
@RestController
@RequestMapping("/api/interview")
public class InterviewController {

    private static final Logger log = LoggerFactory.getLogger(InterviewController.class);
    private final QuestionService questionService;
    private final EvaluatorService evaluatorService;
    private final Guardrails guardrails;
    private final Metrics metrics;

    public InterviewController(QuestionService questionService, EvaluatorService evaluatorService,
                               Guardrails guardrails, Metrics metrics) {
        this.questionService = questionService;
        this.evaluatorService = evaluatorService;
        this.guardrails = guardrails;
        this.metrics = metrics;
    }

    /**
     * 出题端点。
     *
     * <p><b>HTTP</b>:{@code POST /api/interview/question}。请求体 {@link Dtos.QuestionReq}
     * (topic/difficulty/count);响应 {@link Dtos.QuestionData}(题目列表)。
     *
     * @param req 出题请求;count 为 0 时按 1 处理
     * @return 题目列表;topic 不存在返回业务码 404
     */
    @PostMapping("/question")
    public ApiResponse<Dtos.QuestionData> question(@RequestBody Dtos.QuestionReq req) {
        long t0 = System.currentTimeMillis();
        try {
            var data = questionService.generate(req.topic(), req.difficulty(),
                    req.count() == 0 ? 1 : req.count());
            metrics.recordRequest(System.currentTimeMillis() - t0);
            return ApiResponse.ok(data, TraceIdFilter.current());
        } catch (IllegalArgumentException e) {
            // topic 不存在等参数错误:业务码 404
            metrics.recordRequest(System.currentTimeMillis() - t0);
            return ApiResponse.err(404, e.getMessage(), TraceIdFilter.current());
        }
    }

    /**
     * 作答评估端点(LLM-as-Judge)。
     *
     * <p><b>HTTP</b>:{@code POST /api/interview/evaluate}。请求体 {@link Dtos.EvaluateReq}
     * (questionId/userAnswer);响应 {@link Dtos.EvaluateData}(含评分/命中/遗漏/反馈)。
     *
     * <p>用户作答先过护栏(防注入),日志里对作答做 PII 脱敏并截断到 60 字。
     *
     * @param req 评估请求
     * @return 评估结果;被护栏拦截返回 400,题目不存在返回 404
     */
    @PostMapping("/evaluate")
    public ApiResponse<Dtos.EvaluateData> evaluate(@RequestBody Dtos.EvaluateReq req) {
        long t0 = System.currentTimeMillis();
        // 护栏:候选人作答可能夹带注入,先检测
        var g = guardrails.checkInjection(req.userAnswer());
        if (g.blocked()) {
            metrics.recordRequest(System.currentTimeMillis() - t0);
            return ApiResponse.err(400, "输入被拒:" + g.reason(), TraceIdFilter.current());
        }
        // 日志脱敏 + 截断,避免把敏感/超长内容写进日志
        log.info("evaluate | qid={} | answer={}", req.questionId(),
                guardrails.redactPii(req.userAnswer()).substring(0, Math.min(60, req.userAnswer().length())));
        try {
            var ev = evaluatorService.evaluate(req.questionId(), req.userAnswer());
            metrics.recordRequest(System.currentTimeMillis() - t0);
            return ApiResponse.ok(new Dtos.EvaluateData(ev), TraceIdFilter.current());
        } catch (IllegalArgumentException e) {
            // topic 不存在 / 缺少 rubric:业务码 404
            metrics.recordRequest(System.currentTimeMillis() - t0);
            return ApiResponse.err(404, e.getMessage(), TraceIdFilter.current());
        }
    }
}
