package com.nontracey.aiservice.common;

/**
 * 租户上下文:以 {@link ThreadLocal} 保存当前请求所属的租户 ID,供全链路读取。
 *
 * <p><b>架构位置</b>:common 层多租户基础设施。由 {@link TenantFilter} 在请求进入时 set、
 * 请求结束时 clear;业务代码(Loader / VectorStoreService / HybridRetriever)通过 {@link #get()}
 * 拿到当前租户,实现知识库、向量库、BM25 索引的租户隔离。
 *
 * <p><b>为什么用 ThreadLocal</b>:Spring MVC 每个请求由一个线程处理,ThreadLocal 天然按请求隔离,
 * 无需层层传参。注意:该方式仅适用于 MVC 同步线程;Agent 的 WebFlux 响应式线程会切换线程,
 * ThreadLocal 不可靠(这是 MVC + WebFlux 混用需要留意的点)。
 *
 * <p><b>缺省租户</b>:请求未带 {@code X-Tenant-Id} 头时,回退为 {@link #DEFAULT_TENANT}。
 *
 * <p><b>同构映射</b>:对应 B 项目用 contextvar 传递租户的做法。
 */
public final class TenantContext {

    /** 缺省租户 ID:请求头未指定 X-Tenant-Id 时使用。 */
    public static final String DEFAULT_TENANT = "default";

    /** 当前线程绑定的租户 ID;由 TenantFilter 写入。 */
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** 工具类,禁止实例化。 */
    private TenantContext() {}

    /**
     * 读取当前请求的租户 ID;未设置或为空白时回退缺省租户。
     *
     * @return 当前租户 ID(永不为 null)
     */
    public static String get() {
        String t = CURRENT.get();
        return t == null || t.isBlank() ? DEFAULT_TENANT : t;
    }

    /** 由 {@link TenantFilter} 在请求入口写入租户;包私有,避免业务代码随意改写。 */
    static void set(String tenant) {
        CURRENT.set(tenant);
    }

    /** 由 {@link TenantFilter} 在请求结束清理,防止线程复用导致的租户串号。 */
    static void clear() {
        CURRENT.remove();
    }

    /** Explicitly propagate a trusted tenant into an asynchronous worker and always clean it. */
    public static void runAs(String tenant, Runnable action) {
        String previous = CURRENT.get();
        CURRENT.set(tenant);
        try {
            action.run();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
