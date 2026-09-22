package com.mianshi.mcp.governance;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import com.mianshi.mcp.tool.ToolContract;

/**
 * 治理执行器：超时 / 重试（仅可重试错误，幂等键保证安全）/ 审计，三件套统一入口（ADR-2）。
 * 已知短板：orTimeout 后底层线程仍可能继续运行（JDK Future 取消语义），见 README 状态节。
 */
public final class GovernanceExecutor {

    /** 可重试错误：网络 / 超时 / 5xx。业务校验失败不属于可重试。 */
    public static final class RetryableException extends RuntimeException {
        public RetryableException(String msg, Throwable cause) { super(msg, cause); }
    }

    private final AuditLog audit;
    private final ExecutorService executor;

    public GovernanceExecutor(AuditLog audit) {
        this.audit = audit;
        this.executor = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "governance-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public Object execute(ToolContract contract, String idempotencyKey, Callable<Object> action) {
        if (!contract.audit()) throw new IllegalStateException("contract missing audit: 治理三件套不可关闭（ADR-2）");
        if (contract.requiredScope() == null || contract.requiredScope().isBlank())
            throw new IllegalStateException("contract missing requiredScope: 无权限作用域的工具不允许注册");
        long start = System.nanoTime();
        int attempts = 0;
        Exception last = null;
        int maxAttempts = 1 + Math.max(0, contract.maxRetries());
        while (attempts < maxAttempts) {
            attempts++;
            try {
                Future<Object> f = executor.submit(action);
                Object result = f.get(contract.timeoutMs(), TimeUnit.MILLISECONDS);
                long ms = (System.nanoTime() - start) / 1_000_000;
                audit.record(contract.name(), "call", "success", ms,
                        Map.of("attempt", attempts, "idempotencyKey", idempotencyKey));
                return result;
            } catch (TimeoutException e) {
                last = new RetryableException("timeout after " + contract.timeoutMs() + "ms", e);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable c = e.getCause() != null ? e.getCause() : e;
                last = (c instanceof RuntimeException re) ? re : new RuntimeException(c);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted", e);
            }
            boolean retryable = last instanceof RetryableException;
            long ms = (System.nanoTime() - start) / 1_000_000;
            audit.record(contract.name(), "call", "failure", ms,
                    Map.of("attempt", attempts, "retryable", retryable,
                           "idempotencyKey", idempotencyKey,
                           "error", String.valueOf(last.getMessage())));
            if (!retryable) break;
        }
        throw last instanceof RuntimeException re ? re : new RuntimeException(last);
    }
}
