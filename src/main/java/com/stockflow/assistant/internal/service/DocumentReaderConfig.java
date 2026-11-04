package com.stockflow.assistant.internal.service;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.stockflow.assistant.internal.domain.DocumentReader;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.http.RestClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * Chooses the {@link DocumentReader} by the chat's own settings ({@link AssistantProperties}): the same switch, the same
 * provider, the same key and data policy. One decision, read the same way: a reader exists exactly when the chat's model
 * would, so an operator turns the assistant on or off once, and a key shaped like another vendor's is never sent here
 * either.
 *
 * <p><b>The model</b> is the chat's, unless {@code stockflow.assistant.receipt-drafts.model} names another, read as a
 * routed model is ({@link ModelCatalog}): {@code openai/qwen2.5vl:3b} reads the photos on the local server even for a
 * Gemini assistant, with that server's key, and a model the data policy or a missing key rules out reads nothing. A
 * local text model cannot see a photo, so with {@code provider=openai} it is usually a vision model of the same
 * server.</p>
 *
 * <p>A configuration of its own, beside {@code AssistantModelConfig}, rather than one more bean there: the clients are
 * built the same way (the patient request factory, the bounded sleeper) but are separate instances, so neither port
 * reaches into the other's adapter. When the assistant cannot answer, {@code AssistantModelConfig} has already logged
 * why; nothing more is logged here.</p>
 */
@Configuration(proxyBeanMethods = false)
class DocumentReaderConfig {

    private static final Logger log = LoggerFactory.getLogger(DocumentReaderConfig.class);

    @Bean
    DocumentReader documentReader(AssistantProperties assistant, RestClientFactory http,
                                  @Value("${stockflow.assistant.receipt-drafts.model:}") String readerModel) {
        if (!assistant.usable()) {
            return new Unavailable();
        }
        AssistantProperties properties = assistant;
        if (readerModel != null && !readerModel.isBlank()) {
            // Read as a routed model is (ModelCatalog): its vendor's own key only, and the data policy applies.
            ModelCatalog.ModelSpec spec = ModelCatalog.spec(assistant, readerModel);
            Optional<String> refusal = ModelCatalog.refusal(assistant, spec);
            if (refusal.isPresent()) {
                log.warn("Delivery notes are not read: stockflow.assistant.receipt-drafts.model {} cannot be used, {}",
                        spec.name(), refusal.get());
                return new Unavailable();
            }
            properties = ModelCatalog.settingsFor(assistant, spec);
        }
        return switch (properties.providerKind()) {
            case GEMINI -> {
                log.info("Delivery notes are read by Gemini, model {}", properties.model());
                yield new GeminiDocumentReader(AssistantModelConfig.patient(http, "gemini", properties), properties);
            }
            case OPENAI -> {
                log.info("Delivery notes are read by model {} at {}", properties.model(),
                        AssistantProperties.loggable(properties.endpoint()));
                yield new OpenAiCompatibleDocumentReader(
                        AssistantModelConfig.patient(http, "openai-compatible", properties), properties);
            }
            case ANTHROPIC -> {
                AnthropicOkHttpClient.Builder client = AnthropicOkHttpClient.builder()
                        .apiKey(properties.apiKey())
                        .timeout(properties.requestTimeout())
                        .maxRetries(AssistantProperties.MAX_RETRIES)
                        // A spent quota fails at once instead of sleeping out a retry-after of hours.
                        .sleeper(new ClaudeLanguageModel.BoundedSleeper());
                if (!properties.baseUrl().isEmpty()) {
                    client.baseUrl(properties.baseUrl());
                }
                log.info("Delivery notes are read by model {}", properties.model());
                yield new ClaudeDocumentReader(client.build(), properties);
            }
        };
    }

    /** The assistant switched off, or without a key of its provider's own: it says so. */
    static final class Unavailable implements DocumentReader {

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public Reading read(Request request) {
            throw new BusinessException(ErrorCode.ASSISTANT_DISABLED);
        }
    }
}
