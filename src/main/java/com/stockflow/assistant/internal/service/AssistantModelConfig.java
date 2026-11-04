package com.stockflow.assistant.internal.service;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.http.RestClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Chooses the {@link LanguageModel}: the provider when the assistant is switched on and has a key of its own, otherwise
 * one that refuses every call with {@code ASSISTANT_DISABLED}. And the models questions are routed over
 * ({@link ModelRouter}).
 *
 * <p>A missing key is a WARN, not a startup failure. A deployment that never set the key must keep serving every other
 * module (design §11). So is a key shaped like the other vendor's: it is not sent, because the first question would
 * hand that vendor's credential to a company that has no business holding it.</p>
 *
 * <p><b>Every model of the route on the same footing.</b> A model of {@code stockflow.assistant.routing.models} other
 * than {@code stockflow.assistant.model} gets an adapter of its own, built as the {@code LanguageModel} bean is — the
 * same provider, key and settings — and only when that bean is a real adapter: a switched-off assistant, or a missing
 * or misplaced key, routes nowhere. The configured model's adapter is the bean itself, so a test that replaces the
 * bean replaces what every question is sent to.</p>
 */
@Configuration(proxyBeanMethods = false)
class AssistantModelConfig {

    private static final Logger log = LoggerFactory.getLogger(AssistantModelConfig.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    LanguageModel languageModel(AssistantProperties properties, RestClientFactory http) {
        if (!properties.enabled()) {
            log.info("The assistant is switched off (stockflow.assistant.enabled=false)");
            return new Unavailable();
        }
        if (properties.apiKey().isEmpty()) {
            log.warn("stockflow.assistant.enabled=true but no API key is set for provider {} (ASSISTANT_API_KEY or "
                    + "{}): the assistant answers 503 ASSISTANT_DISABLED until one is configured",
                    properties.provider(), properties.keyVariable());
            return new Unavailable();
        }
        if (properties.keyOfAnotherVendor()) {
            log.warn("The assistant's API key is shaped like another vendor's, not provider {}'s: it is never sent, "
                    + "and the assistant answers 503 ASSISTANT_DISABLED until ASSISTANT_API_KEY or {} holds a key "
                    + "of {}", properties.provider(), properties.keyVariable(), properties.provider());
            return new Unavailable();
        }
        if ("gemini".equals(properties.provider())) {
            log.info("The assistant is on: Gemini, model {}", properties.model());
        } else {
            log.info("The assistant is on: model {}, effort {}", properties.model(), properties.effort());
        }
        return adapter(properties, http);
    }

    /**
     * The models questions are routed over, cheapest first (class javadoc): with
     * {@code stockflow.assistant.routing.models} unset, the {@code LanguageModel} bean alone, as before routing.
     */
    @Bean
    ModelRouter modelRouter(LanguageModel languageModel, AssistantProperties properties,
                            ModelRoutingProperties routing, RestClientFactory http, Clock clock) {
        ZoneId quotaDay = ModelRouter.quotaDay(properties.provider());
        List<String> names = routing.route(properties.model());
        if (!properties.usable() || names.equals(List.of(properties.model()))) {
            return new ModelRouter(List.of(new ModelRouter.Candidate(properties.model(), languageModel)), routing,
                    quotaDay, clock);
        }
        if (!names.contains(properties.model())) {
            log.warn("stockflow.assistant.model {} is not one of stockflow.assistant.routing.models, so no question "
                    + "goes to it; questions start on {}", properties.model(), names.getFirst());
        }
        List<ModelRouter.Candidate> candidates = new ArrayList<>();
        List<AutoCloseable> owned = new ArrayList<>();
        for (String name : names) {
            LanguageModel model = languageModel;
            if (!name.equals(properties.model())) {
                model = adapter(withModel(properties, name), http);
                if (model instanceof AutoCloseable closeable) {
                    owned.add(closeable);
                }
            }
            candidates.add(new ModelRouter.Candidate(name, model));
        }
        log.info("The assistant routes questions over {}, cheapest first: a spent quota moves them on to the next "
                + "model{}", names, routing.escalateOnFailure()
                ? ", and so does, once, a question a model could not answer" : "");
        return new ModelRouter(candidates, routing, quotaDay, clock, owned);
    }

    /** The provider's adapter for {@code properties.model()}, once the checks of {@link #languageModel} passed. */
    private static LanguageModel adapter(AssistantProperties properties, RestClientFactory http) {
        if ("gemini".equals(properties.provider())) {
            // The application's read timeout suits quick services; a model call may think for a minute, so this
            // client waits request-timeout instead. Correlation id and call log come from the factory.
            JdkClientHttpRequestFactory patient = new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
            patient.setReadTimeout(properties.requestTimeout());
            String base = properties.baseUrl().isEmpty() ? GeminiLanguageModel.DEFAULT_BASE_URL : properties.baseUrl();
            return new GeminiLanguageModel(http.forService("gemini", base).requestFactory(patient).build(),
                    properties);
        }
        AnthropicOkHttpClient.Builder client = AnthropicOkHttpClient.builder()
                .apiKey(properties.apiKey())
                .timeout(properties.requestTimeout())
                .maxRetries(AssistantProperties.MAX_RETRIES)
                // The SDK would otherwise sleep any retry-after, however long; a spent quota fails at once instead.
                .sleeper(new ClaudeLanguageModel.BoundedSleeper());
        if (!properties.baseUrl().isEmpty()) {
            client.baseUrl(properties.baseUrl());
        }
        return new ClaudeLanguageModel(client.build(), properties);
    }

    /**
     * The same settings for another model of the route. The key passes as it was resolved — the provider's own, or
     * {@code ASSISTANT_API_KEY} — so every model is sent the one key the checks above let through.
     */
    private static AssistantProperties withModel(AssistantProperties properties, String model) {
        return new AssistantProperties(properties.enabled(), properties.apiKey(), model, properties.effort(),
                properties.maxToolRounds(), properties.maxOutputTokens(), properties.dailyMessageLimit(),
                properties.retentionDays(), properties.requestTimeout(), properties.answerLease(),
                properties.refusalFallback(), properties.baseUrl(), properties.provider(),
                properties.anthropicApiKey(), properties.geminiApiKey());
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
