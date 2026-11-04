package com.stockflow.assistant.internal.service;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.stockflow.assistant.internal.domain.DocumentReader;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.http.RestClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Chooses the {@link DocumentReader} by the chat's own settings ({@link AssistantProperties}): the same switch, the same
 * provider, the same key and model. One decision, read the same way: a reader exists exactly when the chat's model
 * would, so an operator turns the assistant on or off once, and a key shaped like the other vendor's is never sent
 * here either.
 *
 * <p>A configuration of its own, beside {@code AssistantModelConfig}, rather than one more bean there: the clients are
 * built the same way (the patient request factory, the bounded sleeper) but are separate instances, so neither port
 * reaches into the other's adapter. When the assistant cannot answer, {@code AssistantModelConfig} has already logged
 * why; nothing more is logged here.</p>
 */
@Configuration(proxyBeanMethods = false)
class DocumentReaderConfig {

    private static final Logger log = LoggerFactory.getLogger(DocumentReaderConfig.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    DocumentReader documentReader(AssistantProperties properties, RestClientFactory http) {
        if (!properties.usable()) {
            return new Unavailable();
        }
        if ("gemini".equals(properties.provider())) {
            // A model reading a photo may think for a minute; the application's read timeout suits quick services.
            JdkClientHttpRequestFactory patient = new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
            patient.setReadTimeout(properties.requestTimeout());
            String base = properties.baseUrl().isEmpty() ? GeminiLanguageModel.DEFAULT_BASE_URL : properties.baseUrl();
            log.info("Delivery notes are read by Gemini, model {}", properties.model());
            return new GeminiDocumentReader(http.forService("gemini", base).requestFactory(patient).build(),
                    properties);
        }
        AnthropicOkHttpClient.Builder client = AnthropicOkHttpClient.builder()
                .apiKey(properties.apiKey())
                .timeout(properties.requestTimeout())
                .maxRetries(AssistantProperties.MAX_RETRIES)
                // A spent quota fails at once instead of sleeping out a retry-after of hours (ClaudeLanguageModel).
                .sleeper(new ClaudeLanguageModel.BoundedSleeper());
        if (!properties.baseUrl().isEmpty()) {
            client.baseUrl(properties.baseUrl());
        }
        log.info("Delivery notes are read by model {}", properties.model());
        return new ClaudeDocumentReader(client.build(), properties);
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
