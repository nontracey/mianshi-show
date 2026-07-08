package com.nontracey.aiservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 应用配置(从 application.yml 的 app.* 读取)。 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Kb kb,
        String vectorStore,
        int rateLimitPerMinute
) {
    public record Kb(String contentUrl, String contentPath, String samplePath) {}
}
