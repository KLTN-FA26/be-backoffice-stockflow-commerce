package com.stockflow.assistant.internal.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The models of a route, each resolved to the vendor it runs on, the key it is called with and the endpoint it is sent
 * to (design §11). {@code ASSISTANT_MODELS} may mix vendors — a free local model first, a cloud one when it is needed —
 * and this is the one place that decides, per model, which credential goes where.
 *
 * <p><b>Naming a model.</b> {@code provider/model}, as in {@code openai/qwen3:8b} or {@code gemini/gemini-3.5-flash},
 * where the provider is one of {@link ModelProvider#ids()}; a name without such a prefix is a model of
 * {@code stockflow.assistant.provider}, as every name was before vendors could mix. Only the first {@code /} is read,
 * so a model id that holds one of its own keeps it: a gateway's {@code anthropic/claude-sonnet} reached through an
 * OpenAI-compatible server is written {@code openai/anthropic/claude-sonnet}, since alone it names Anthropic itself.
 * A name equal to {@code stockflow.assistant.model}, as written, is always the assistant's own model.</p>
 *
 * <p><b>A key goes to its own vendor only.</b> {@code ASSISTANT_API_KEY} belongs to the assistant's own provider and is
 * sent to that provider's models alone. A model of another vendor takes that vendor's own variable
 * ({@code GEMINI_API_KEY}, {@code ANTHROPIC_API_KEY}, {@code OPENAI_COMPATIBLE_API_KEY}) or nothing: reading
 * {@code ASSISTANT_API_KEY} for it would hand one company's credential to another the day a list mixed them. For the same
 * reason {@code base-url}, which may point at a proxy for the assistant's own provider, is used for that provider
 * only.</p>
 *
 * <p><b>A model that cannot be used is left out, not fatal</b> ({@link #refusal}): without its key, with a key shaped like
 * another vendor's, or at an endpoint the data policy forbids. The route goes on without it and the start-up log says
 * why, as the assistant does for its own model.</p>
 */
final class ModelCatalog {

    private ModelCatalog() {
    }

    /**
     * One model of the route, resolved.
     *
     * @param name     as configured, the router's name for it in logs and in its quota bookkeeping
     * @param provider the vendor it runs on
     * @param model    its id at that vendor, without the prefix
     * @param apiKey   the key it is called with, from its own vendor's variable (class javadoc); blank for none
     * @param endpoint where it is called
     */
    record ModelSpec(String name, ModelProvider provider, String model, String apiKey, String endpoint) {

        ModelSpec {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(apiKey, "apiKey");
            Objects.requireNonNull(endpoint, "endpoint");
        }

        /** Whether this is the assistant's own model, the one the {@code LanguageModel} bean answers with. */
        boolean isOwnModel(AssistantProperties properties) {
            return provider == properties.providerKind() && model.equals(properties.model());
        }

        /** Hides the key: specs are logged. */
        @Override
        public String toString() {
            return "ModelSpec[%s: %s %s at %s, key %s]".formatted(name, provider.id(), model,
                    AssistantProperties.loggable(endpoint),
                    apiKey.isEmpty() ? "<none>" : "<set>");
        }
    }

    /** Every model of the route, cheapest first, resolved; usable or not ({@link #refusal} says). */
    static List<ModelSpec> route(AssistantProperties properties, ModelRoutingProperties routing) {
        return routing.route(properties.model()).stream().map(name -> spec(properties, name)).toList();
    }

    /** {@code name} resolved (class javadoc). */
    static ModelSpec spec(AssistantProperties properties, String name) {
        ModelProvider own = properties.providerKind();
        ModelProvider provider = own;
        String model = name.strip();
        if (model.equals(properties.model())) {
            return new ModelSpec(name, own, model, properties.apiKey(), properties.endpoint());
        }
        int slash = model.indexOf('/');
        if (slash > 0) {
            ModelProvider prefixed = ModelProvider.named(model.substring(0, slash));
            if (prefixed != null) {
                provider = prefixed;
                model = model.substring(slash + 1).strip();
            }
        }
        if (model.isEmpty()) {
            throw new IllegalArgumentException("stockflow.assistant.routing.models: \"" + name + "\" names no model");
        }
        if (provider == own) {
            return new ModelSpec(name, provider, model, properties.apiKey(), properties.endpoint());
        }
        String key = AssistantProperties.ownKey(provider, properties.anthropicApiKey(), properties.geminiApiKey(),
                properties.openaiCompatible());
        return new ModelSpec(name, provider, model, key, defaultEndpoint(provider, properties));
    }

    /** Where a vendor other than the assistant's own is called: its public API, or the configured local server. */
    private static String defaultEndpoint(ModelProvider provider, AssistantProperties properties) {
        return switch (provider) {
            case OPENAI -> properties.openaiCompatible().baseUrl();
            case GEMINI -> GeminiLanguageModel.DEFAULT_BASE_URL;
            case ANTHROPIC -> AssistantProperties.ANTHROPIC_BASE_URL;
        };
    }

    /**
     * Why {@code spec} cannot be used, for the start-up log; empty when it can. Never the key itself.
     */
    static Optional<String> refusal(AssistantProperties properties, ModelSpec spec) {
        if (!properties.dataPolicy().allows(spec.provider(), spec.endpoint())) {
            return Optional.of("the data policy " + properties.dataPolicy() + " forbids " + spec.provider().id()
                    + " at " + AssistantProperties.loggable(spec.endpoint()));
        }
        if (spec.provider().needsKey() && spec.apiKey().isEmpty()) {
            return Optional.of("no key is set for " + spec.provider().id() + " (" + spec.provider().keyVariable()
                    + ")");
        }
        if (spec.provider().keyOfAnotherVendor(spec.apiKey())) {
            return Optional.of("its key is shaped like another vendor's, not " + spec.provider().id() + "'s");
        }
        return Optional.empty();
    }

    /**
     * The assistant's settings as {@code spec}'s adapter needs them: its vendor, model and key; every other setting
     * (effort, timeouts, output tokens, the data policy) shared. {@code base-url} travels with the assistant's own
     * provider only (class javadoc).
     */
    static AssistantProperties settingsFor(AssistantProperties properties, ModelSpec spec) {
        boolean own = spec.provider() == properties.providerKind();
        return new AssistantProperties(properties.enabled(), spec.apiKey(), spec.model(), properties.effort(),
                properties.maxToolRounds(), properties.maxOutputTokens(), properties.dailyMessageLimit(),
                properties.retentionDays(), properties.requestTimeout(), properties.answerLease(),
                properties.refusalFallback(), own ? properties.baseUrl() : "", spec.provider().id(),
                properties.anthropicApiKey(), properties.geminiApiKey(), properties.openaiCompatible(),
                properties.dataPolicy());
    }
}
