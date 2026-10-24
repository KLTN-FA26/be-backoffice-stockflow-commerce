package com.stockflow.assistant.internal.service;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the {@link LanguageModel}: the provider when the assistant is switched on and has a key, otherwise one that
 * refuses every call with {@code ASSISTANT_DISABLED}.
 *
 * <p>A missing key is a WARN, not a startup failure. A deployment that never set the key must keep serving every other
 * module (design §11).</p>
 */
@Configuration(proxyBeanMethods = false)
class AssistantModelConfig {

    private static final Logger log = LoggerFactory.getLogger(AssistantModelConfig.class);

    @Bean
    LanguageModel languageModel(AssistantProperties properties) {
        if (!properties.enabled()) {
            log.info("The assistant is switched off (stockflow.assistant.enabled=false)");
            return new Unavailable();
        }
        if (!properties.usable()) {
            log.warn("stockflow.assistant.enabled=true but no API key is set (ANTHROPIC_API_KEY): the assistant "
                    + "answers 503 ASSISTANT_DISABLED until one is configured");
            return new Unavailable();
        }
        AnthropicOkHttpClient.Builder client = AnthropicOkHttpClient.builder()
                .apiKey(properties.apiKey())
                .timeout(properties.requestTimeout())
                .maxRetries(AssistantProperties.MAX_RETRIES);
        if (!properties.baseUrl().isEmpty()) {
            client.baseUrl(properties.baseUrl());
        }
        log.info("The assistant is on: model {}, effort {}", properties.model(), properties.effort());
        return new ClaudeLanguageModel(client.build(), properties);
    }

    /** The assistant switched off: it says so instead of failing in some less obvious way. */
    static final class Unavailable implements LanguageModel {

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public Reply next(Request request) {
            throw new BusinessException(ErrorCode.ASSISTANT_DISABLED);
        }

        @Override
        public Turn.ModelTurn closingTurn(String text) {
            throw new BusinessException(ErrorCode.ASSISTANT_DISABLED);
        }
    }
}
