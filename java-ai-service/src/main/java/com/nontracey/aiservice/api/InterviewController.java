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

/** 面试接口:/api/interview/question 与 /api/interview/evaluate。 */
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

    @PostMapping("/question")
    public ApiResponse<Dtos.QuestionData> question(@RequestBody Dtos.QuestionReq req) {
        long t0 = System.currentTimeMillis();
        try {
            var data = questionService.generate(req.topic(), req.difficulty(),
                    req.count() == 0 ? 1 : req.count());
            metrics.recordRequest(System.currentTimeMillis() - t0);
            return ApiResponse.ok(data, TraceIdFilter.current());
        } catch (IllegalArgumentException e) {
            metrics.recordRequest(System.currentTimeMillis() - t0);
            return ApiResponse.err(404, e.getMessage(), TraceIdFilter.current());
        }
    }

    @PostMapping("/evaluate")
    public ApiResponse<Dtos.EvaluateData> evaluate(@RequestBody Dtos.EvaluateReq req) {
        long t0 = System.currentTimeMillis();
        var g = guardrails.checkInjection(req.userAnswer());
        if (g.blocked()) {
            metrics.recordRequest(System.currentTimeMillis() - t0);
            return ApiResponse.err(400, "输入被拒:" + g.reason(), TraceIdFilter.current());
        }
        log.info("evaluate | qid={} | answer={}", req.questionId(),
                guardrails.redactPii(req.userAnswer()).substring(0, Math.min(60, req.userAnswer().length())));
        try {
            var ev = evaluatorService.evaluate(req.questionId(), req.userAnswer());
            metrics.recordRequest(System.currentTimeMillis() - t0);
            return ApiResponse.ok(new Dtos.EvaluateData(ev), TraceIdFilter.current());
        } catch (IllegalArgumentException e) {
            metrics.recordRequest(System.currentTimeMillis() - t0);
            return ApiResponse.err(404, e.getMessage(), TraceIdFilter.current());
        }
    }
}
