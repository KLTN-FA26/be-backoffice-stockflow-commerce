package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.EmbeddingModel;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.http.RestClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/**
 * Chooses the {@link EmbeddingModel} ({@link EmbeddingProperties}): Gemini for a Gemini assistant, an
 * OpenAI-compatible server inside the company's network for any assistant, otherwise one that is not available, and
 * the knowledge search stays keyword-only (design §6.4).
 *
 * <p>Nothing here stops the application starting. Each reason to stay keyword-only is logged once, and a key shaped
 * like another vendor's is never sent, whichever variable it came from.</p>
 *
 * <p>Gemini's client comes from {@code RestClientFactory} with its default timeouts: an embedding call returns in well
 * under a second, and the search a question waits on must give up long before the model call it belongs to would. A
 * local server gets {@value #LOCAL_READ_SECONDS} seconds for the passages, since its first call after a while loads the
 * model into memory, and {@value #QUESTION_READ_SECONDS} for a question's embedding, for that same reason.</p>
 */
@Configuration(proxyBeanMethods = false)
class EmbeddingModelConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingModelConfig.class);

    static final int LOCAL_READ_SECONDS = 30;

    /** A question's embedding, which a search waits on: well under a model call, and the search falls back to words. */
    static final int QUESTION_READ_SECONDS = 5;

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
        return embeddings.providerKind() == ModelProvider.OPENAI
                ? openAiCompatible(embeddings, assistant, http, keywordsOnly)
                : gemini(embeddings, assistant, http, keywordsOnly);
    }

    private static EmbeddingModel gemini(EmbeddingProperties embeddings, AssistantProperties assistant,
                                         RestClientFactory http, EmbeddingModel keywordsOnly) {
        if (assistant.providerKind() != ModelProvider.GEMINI) {
            log.info("The assistant searches its knowledge by keyword only: Gemini embeddings serve a Gemini assistant "
                    + "only, and this one runs on {} (set stockflow.assistant.embeddings.provider=openai for a local "
                    + "embedding model)", assistant.provider());
            return keywordsOnly;
        }
        String key = embeddings.keyFor(assistant);
        if (ModelProvider.GEMINI.keyOfAnotherVendor(key)) {
            log.warn("ASSISTANT_EMBEDDINGS_API_KEY is shaped like an Anthropic key: it is never sent to Google, and "
                    + "the assistant searches its knowledge by keyword only");
            return keywordsOnly;
        }
        String base = assistant.endpoint();
        log.info("The assistant searches its knowledge by keyword and by meaning: Gemini {}, {} dimensions",
                embeddings.model(), embeddings.dimensions());
        return new GeminiEmbeddingModel(http.forService("gemini-embeddings", base).build(), embeddings.model(),
                embeddings.dimensions(), key);
    }

    private static EmbeddingModel openAiCompatible(EmbeddingProperties embeddings, AssistantProperties assistant,
                                                   RestClientFactory http, EmbeddingModel keywordsOnly) {
        String base = assistant.openaiCompatible().baseUrl();
        java.util.Optional<String> outside = assistant.boundary().refusal(ModelProvider.OPENAI, base);
        if (outside.isPresent()) {
            log.warn("The assistant searches its knowledge by keyword only: the embedding server at {} cannot be "
                    + "used, {}", AssistantProperties.loggable(base), outside.get());
            return keywordsOnly;
        }
        if (!DataPolicy.isLocal(base) && assistant.providerKind() != ModelProvider.OPENAI) {
            log.info("The assistant searches its knowledge by keyword only: the embedding server at {} is outside the "
                    + "company's network and is not the assistant's own provider, so the search terms would reach "
                    + "a second vendor", AssistantProperties.loggable(base));
            return keywordsOnly;
        }
        String key = embeddings.keyFor(assistant);
        if (ModelProvider.OPENAI.keyOfAnotherVendor(key)) {
            log.warn("OPENAI_COMPATIBLE_API_KEY is shaped like a Google or Anthropic key: it is never sent to the "
                    + "embedding server, and the assistant searches its knowledge by keyword only");
            return keywordsOnly;
        }
        log.info("The assistant searches its knowledge by keyword and by meaning: {} at {}, {} dimensions",
                embeddings.model(), AssistantProperties.loggable(base), embeddings.dimensions());
        return new OpenAiCompatibleEmbeddingModel(waiting(http, base, Duration.ofSeconds(LOCAL_READ_SECONDS)),
                waiting(http, base, Duration.ofSeconds(QUESTION_READ_SECONDS)), embeddings.model(),
                embeddings.dimensions(), key);
    }

    /** A client of the embedding server that waits {@code read} for an answer. */
    private static RestClient waiting(RestClientFactory http, String base, Duration read) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(read);
        return http.forService("openai-compatible-embeddings", base).requestFactory(factory).build();
    }

    /** Keyword search only: embeddings switched off, no key, or a provider that may not make them here. */
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
