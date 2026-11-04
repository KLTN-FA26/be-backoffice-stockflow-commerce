package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.Reranker;
import com.stockflow.common.http.RestClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Chooses the {@link Reranker} ({@link KnowledgeProperties.Reranker}): text-embeddings-inference at a base URL inside
 * the company's network, otherwise none, and the search keeps its fused order. Never a reason not to start.
 */
@Configuration(proxyBeanMethods = false)
class RerankerConfig {

    private static final Logger log = LoggerFactory.getLogger(RerankerConfig.class);

    @Bean
    Reranker reranker(KnowledgeProperties knowledge, RestClientFactory http) {
        KnowledgeProperties.Reranker settings = knowledge.reranker();
        if (!settings.configured()) {
            return Reranker.NONE;
        }
        if (!DataPolicy.isLocal(settings.baseUrl())) {
            log.warn("The assistant does not rerank its knowledge search: the reranker at {} is outside the company's "
                    + "network, and the search terms carry what users ask", AssistantProperties.loggable(
                    settings.baseUrl()));
            return Reranker.NONE;
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(settings.timeout());
        log.info("The assistant reranks its knowledge search's best {} passages at {}", settings.depth(),
                AssistantProperties.loggable(settings.baseUrl()));
        return new TeiReranker(http.forService("tei-reranker", settings.baseUrl()).requestFactory(factory).build());
    }
}
