package com.nontracey.aiservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用自定义配置,从 application.yml 的 {@code app.*} 前缀绑定。
 *
 * <p><b>架构位置</b>:属于配置层,被 {@link com.nontracey.aiservice.rag.Loader}(读知识库来源)
 * 与 {@link com.nontracey.aiservice.api.OpsController}(/health 展示)等消费。
 *
 * <p><b>设计点</b>:用 record + {@code @ConfigurationProperties} 做类型安全的配置绑定,
 * 配合主类上的 {@code @ConfigurationPropertiesScan} 自动注册,无需额外 {@code @EnableConfigurationProperties}。
 *
 * <p><b>同构映射</b>:对应 B 项目 {@code app/config.py}(pydantic Settings)与 D 项目的 options 类。
 *
 * @param kb                知识库(KB)来源配置,见 {@link Kb}
 * @param vectorStore       向量库实现选择:"memory"(默认,内存)或 "pgvector"(生产,见 application.yml 注释)
 * @param rateLimitPerMinute 每请求方每分钟限流阈值(预留配置,当前代码未强制启用)
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Kb kb,
        String vectorStore,
        int rateLimitPerMinute
) {
    /**
     * 知识库三层数据源配置,加载优先级:contentPath > contentUrl > samplePath(见 Loader)。
     *
     * @param contentUrl  远程 manifest 地址(第二优先级,HTTP 拉取,失败降级到 sample)
     * @param contentPath 本地知识库目录或 manifest.json 路径(第一优先级)
     * @param samplePath  内置样例知识库路径(兜底,默认指向仓库 data/ 下的 sample 文件)
     */
    public record Kb(String contentUrl, String contentPath, String samplePath) {}
}
