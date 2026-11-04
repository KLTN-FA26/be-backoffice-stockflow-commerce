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
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Chooses the {@link LanguageModel}: the provider when the assistant is switched on, has a key of its own (or needs
 * none) and runs where the data policy allows; otherwise one that refuses every call with {@code ASSISTANT_DISABLED}.
 * And the models questions are routed over ({@link ModelRouter}).
 *
 * <p>A missing key is a WARN, not a startup failure. A deployment that never set the key must keep serving every other
 * module (design §11). So is a key shaped like another vendor's: it is not sent, because the first question would
 * hand that vendor's credential to a company that has no business holding it. So is a provider the data policy
 * forbids ({@link DataPolicy}): under {@code LOCAL_ONLY} a cloud model is never built.</p>
 *
 * <p><b>Every model of the route on the same footing.</b> A model of {@code stockflow.assistant.routing.models} other
 * than the assistant's own gets an adapter of its own, built as the {@code LanguageModel} bean is, for the vendor
 * {@link ModelCatalog} resolves it to, with that vendor's key — and only when the bean is a real adapter: a
 * switched-off assistant, or a missing or misplaced key, routes nowhere. A model that cannot be used is left out of
 * the route with a WARN. The assistant's own model's adapter is the bean itself, so a test that replaces the bean
 * replaces what every question is sent to.</p>
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
        if (!properties.allowedByPolicy()) {
            log.warn("The data policy {} forbids provider {} at {}: the assistant answers 503 ASSISTANT_DISABLED "
                    + "until it runs on a model inside the company's network (provider openai, a local "
                    + "openai-compatible.base-url)", properties.dataPolicy(), properties.provider(),
                    AssistantProperties.loggable(properties.endpoint()));
            return new Unavailable();
        }
        if (properties.apiKey().isEmpty() && properties.providerKind().needsKey()) {
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
        switch (properties.providerKind()) {
            case GEMINI -> log.info("The assistant is on: Gemini, model {}", properties.model());
            case OPENAI -> log.info("The assistant is on: model {} at {}", properties.model(),
                    AssistantProperties.loggable(properties.endpoint()));
            case ANTHROPIC -> log.info("The assistant is on: model {}, effort {}", properties.model(),
                    properties.effort());
        }
        return adapter(properties, http);
    }

    /**
     * The models questions are routed over, cheapest first (class javadoc): with
     * {@code stockflow.assistant.routing.models} unset, the {@code LanguageModel} bean alone, as before routing.
     */
    @Bean
    ModelRouter modelRouter(LanguageModel languageModel, AssistantProperties properties,
                            ModelRoutingProperties routing, RestClientFactory http, Clock clock,
                            AssistantTelemetry telemetry) {
        List<ModelCatalog.ModelSpec> specs = ModelCatalog.route(properties, routing);
        ModelRouter.Candidate own = new ModelRouter.Candidate(ModelCatalog.spec(properties, properties.model()),
                languageModel);
        ZoneId quotaDay = properties.providerKind().quotaDay();
        if (!properties.usable() || (specs.size() == 1 && specs.getFirst().isOwnModel(properties))) {
            return new ModelRouter(List.of(own), routing, quotaDay, clock, List.of(), telemetry);
        }
        if (specs.stream().noneMatch(spec -> spec.isOwnModel(properties))) {
            log.warn("stockflow.assistant.model {} is not one of stockflow.assistant.routing.models, so no question "
                    + "goes to it; questions start on {}", properties.model(), specs.getFirst().name());
        }
        List<ModelRouter.Candidate> candidates = new ArrayList<>();
        List<AutoCloseable> owned = new ArrayList<>();
        for (ModelCatalog.ModelSpec spec : specs) {
            if (spec.isOwnModel(properties)) {
                candidates.add(new ModelRouter.Candidate(spec, languageModel));
                continue;
            }
            Optional<String> refusal = ModelCatalog.refusal(properties, spec);
            if (refusal.isPresent()) {
                log.warn("Model {} is left out of the route: {}", spec.name(), refusal.get());
                continue;
            }
            LanguageModel model = adapter(ModelCatalog.settingsFor(properties, spec), http);
            if (model instanceof AutoCloseable closeable) {
                owned.add(closeable);
            }
            candidates.add(new ModelRouter.Candidate(spec, model));
        }
        if (candidates.isEmpty()) {
            log.warn("No model of stockflow.assistant.routing.models can be used: questions go to {} alone",
                    properties.model());
            return new ModelRouter(List.of(own), routing, quotaDay, clock, List.of(), telemetry);
        }
        log.info("The assistant routes questions over {}, cheapest first: a spent quota moves them on to the next "
                + "model{}", candidates.stream().map(ModelRouter.Candidate::name).toList(), routing.escalateOnFailure()
                ? ", and so does, once, a question a model could not answer" : "");
        return new ModelRouter(candidates, routing, quotaDay, clock, owned, telemetry);
    }

    /** {@link #modelRouter(LanguageModel, AssistantProperties, ModelRoutingProperties, RestClientFactory, Clock,
     * AssistantTelemetry)} without traces, as tests build it. */
    ModelRouter modelRouter(LanguageModel languageModel, AssistantProperties properties,
                            ModelRoutingProperties routing, RestClientFactory http, Clock clock) {
        return modelRouter(languageModel, properties, routing, http, clock, AssistantTelemetry.NONE);
    }

    /** The provider's adapter for {@code properties.model()}, once the checks of {@link #languageModel} passed. */
    private static LanguageModel adapter(AssistantProperties properties, RestClientFactory http) {
        return switch (properties.providerKind()) {
            // A model call may think for a minute; the application's read timeout suits quick services, so these
            // clients wait request-timeout instead. Correlation id and call log come from the factory.
            case GEMINI -> new GeminiLanguageModel(patient(http, "gemini", properties), properties);
            case OPENAI -> new OpenAiCompatibleLanguageModel(patient(http, "openai-compatible", properties),
                    properties);
            case ANTHROPIC -> claude(properties);
        };
    }

    /** A client of {@code service} at the provider's endpoint that waits {@code request-timeout} for an answer. */
    static RestClient patient(RestClientFactory http, String service, AssistantProperties properties) {
        JdkClientHttpRequestFactory patient = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        patient.setReadTimeout(properties.requestTimeout());
        return http.forService(service, properties.endpoint()).requestFactory(patient).build();
    }

    private static LanguageModel claude(AssistantProperties properties) {
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
