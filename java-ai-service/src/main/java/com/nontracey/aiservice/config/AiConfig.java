package com.nontracey.aiservice.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import com.nontracey.aiservice.rag.VectorStoreService;
import java.util.List;

/**
 * Spring AI / HTTP 客户端配置类。
 *
 * <p><b>架构位置</b>:配置层,集中声明与 LLM、外部 HTTP 相关的可注入 Bean。
 *
 * <p><b>关键点</b>:
 * <ul>
 *   <li>{@link ChatModel} 与 {@link EmbeddingModel} 由 spring-ai-openai starter 依据
 *       application.yml 的 {@code spring.ai.openai.*} 自动装配(base-url / api-key / model / temperature),
 *       这里不需要手工 new。EmbeddingModel 直接被 {@link com.nontracey.aiservice.rag.VectorStoreService} 注入使用。</li>
 *   <li>{@link ChatClient} 是调用 LLM 的统一入口(prompt().system().user().call()),
 *       由本类基于自动装配的 ChatModel 构造成单例 Bean,供 Generator / EvaluatorService /
 *       AgentOrchestrator / HybridRetriever 等注入。</li>
 *   <li>{@link WebClient} 用于 {@link com.nontracey.aiservice.rag.Loader} 远程拉取知识库 manifest。</li>
 * </ul>
 *
 * <p><b>同构映射</b>:对应 B 项目 {@code app/infra/llm.py}(构造 LLM/embedding 客户端)。
 */
@Configuration
public class AiConfig {

    /**
     * 构造全局共享的 {@link ChatClient}。
     *
     * @param chatModel 由 Spring AI OpenAI starter 自动装配的底层模型客户端
     * @return 封装后的 ChatClient,业务侧统一通过它发起对话/Function Calling
     */
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.create(chatModel);
    }

    /**
     * 构造 {@link WebClient},供 Loader 从远程 URL 拉取知识库 manifest 与 topic JSON。
     *
     * @return 默认配置的 WebClient
     */
    @Bean
    public WebClient webClient() {
        return WebClient.builder().build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.vector-store", havingValue = "memory", matchIfMissing = true)
    public VectorStore memoryVectorStore(VectorStoreService service) {
        return new VectorStore() {
            @Override public void add(List<org.springframework.ai.document.Document> docs) { service.add(docs); }
            @Override public void delete(List<String> ids) { service.delete(ids); }
            @Override public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression expression) {
                throw new UnsupportedOperationException("memory vector store 不支持按表达式删除");
            }
            @Override public List<org.springframework.ai.document.Document> similaritySearch(
                    org.springframework.ai.vectorstore.SearchRequest request) {
                return service.similaritySearch(request);
            }
        };
    }
}
