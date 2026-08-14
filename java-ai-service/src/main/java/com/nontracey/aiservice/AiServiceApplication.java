package com.nontracey.aiservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AI 面试陪练服务(Java 版,即"C 项目")的 Spring Boot 启动入口。
 *
 * <p><b>职责</b>:引导 Spring 容器启动,装配所有 Bean(Controller / Service / 配置类等)。
 *
 * <p><b>技术栈</b>:Spring Boot 3.5.16 + Spring AI 1.1.8(ChatClient / ToolCallback / Advisor)
 * + WebFlux(仅 Agent SSE 端点用)。应用主体是 Spring MVC,只有 /api/agent/session 切到响应式流。
 *
 * <p><b>关键注解</b>:
 * <ul>
 *   <li>{@code @SpringBootApplication}:组合了 {@code @Configuration} + {@code @ComponentScan}
 *       + {@code @EnableAutoConfiguration},自动扫描本包及子包下的组件。</li>
 *   <li>{@code @ConfigurationPropertiesScan}:开启对 {@code @ConfigurationProperties} 记录类的扫描,
 *       使 {@link com.nontracey.aiservice.config.AppProperties} 能被自动注册并从 application.yml 绑定。</li>
 * </ul>
 *
 * <p><b>同构映射</b>:对应 B 项目(Python)的 {@code app/main.py}(FastAPI 入口)与
 * D 项目(.NET)的 {@code Program.cs};三者对外 API 契约一致,仅生态实现不同。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AiServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
