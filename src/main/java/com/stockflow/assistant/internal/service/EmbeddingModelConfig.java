package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.EmbeddingModel;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.http.RestClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Chooses the {@link EmbeddingModel}: Gemini when embeddings are on and the assistant can answer on Gemini, otherwise
 * one that is not available, and the knowledge search stays keyword-only (design §6.4).
 *
 * <p>Nothing here stops the application starting. Each reason to stay keyword-only is logged once, and a key shaped
 * like Anthropic's is never sent to Google, whichever variable it came from.</p>
 *
 * <p>The client comes from {@code RestClientFactory} with its default timeouts: an embedding call returns in well under
 * a second, and the search a question waits on must give up long before the model call it belongs to would.</p>
 */
@Configuration(proxyBeanMethods = false)
class EmbeddingModelConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelConfig.class);

    @Bean
    EmbeddingModel embeddingModel(EmbeddingProperties embeddings, AssistantProperties assistant,
                                  RestClientFactory http) {
        EmbeddingModel keywordsOnly = new Unavailable(embeddings.model(), embeddings.dimensions());
        if (!embeddings.enabled()) {
            log.info("The assistant searches its knowledge by keyword only (stockflow.assistant.embeddings.enabled="
                    + "false)");
            return keywordsOnly;
        }
        if (!assistant.usable()) {
            // The assistant cannot answer, and AssistantModelConfig has said why: nothing would search.
            return keywordsOnly;
        }
        if (!"gemini".equals(assistant.provider())) {
            log.info("The assistant searches its knowledge by keyword only: provider {} has no embedding API",
                    assistant.provider());
            return keywordsOnly;
        }
        String key = embeddings.keyFor(assistant);
        if (key.startsWith("sk-ant-")) {
            log.warn("ASSISTANT_EMBEDDINGS_API_KEY is shaped like an Anthropic key: it is never sent to Google, and "
                    + "the assistant searches its knowledge by keyword only");
            return keywordsOnly;
        }
        String base = assistant.baseUrl().isEmpty() ? GeminiLanguageModel.DEFAULT_BASE_URL : assistant.baseUrl();
        log.info("The assistant searches its knowledge by keyword and by meaning: Gemini {}, {} dimensions",
                embeddings.model(), embeddings.dimensions());
        return new GeminiEmbeddingModel(http.forService("gemini-embeddings", base).build(), embeddings.model(),
                embeddings.dimensions(), key);
    }

    /** Keyword search only: embeddings switched off, no key, or a provider with no embedding API. */
    record Unavailable(String name, int dimensions) implements EmbeddingModel {

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public List<float[]> embed(List<String> texts, Purpose purpose) {
            throw new BusinessException(ErrorCode.ASSISTANT_DISABLED);
        }
    }
}
