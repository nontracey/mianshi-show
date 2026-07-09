package com.nontracey.aiservice.common;

/** 租户上下文:每请求由 TenantFilter 设置,全链路用 {@link #get()} 取当前租户。
 * <p>缺省租户 "default"(无 X-Tenant-Id 头时用)。 */
public final class TenantContext {

    public static final String DEFAULT_TENANT = "default";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static String get() {
        String t = CURRENT.get();
        return t == null || t.isBlank() ? DEFAULT_TENANT : t;
    }

    static void set(String tenant) {
        CURRENT.set(tenant);
    }

    static void clear() {
        CURRENT.remove();
    }
}
