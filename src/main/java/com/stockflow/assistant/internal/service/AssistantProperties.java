package com.stockflow.assistant.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The assistant's settings ({@code stockflow.assistant}, design §11).
 *
 * <p><b>Off unless switched on, and never a reason not to start.</b> With {@code enabled=false}, or with no
 * {@code api-key}, the module still starts and its endpoints answer 503 {@code ASSISTANT_DISABLED}. An optional
 * provider that refuses to boot the application can roll back a deploy that has nothing to do with it.</p>
 *
 * <p><b>A key goes to its own vendor only.</b> Without {@code ASSISTANT_API_KEY} the key is the chosen provider's own
 * variable, never the other vendor's: falling back to {@code ANTHROPIC_API_KEY} whatever the provider would send an
 * Anthropic credential to Google the day the Gemini key is missing. A key shaped like the other vendor's is not
 * {@link #usable()} either, so a mix-up costs a WARN at startup instead of a credential handed to a third party.</p>
 *
 * @param apiKey             the key, from the environment and never from a YAML file: {@code ASSISTANT_API_KEY}, or
 *                           when that is unset the chosen provider's own variable ({@code anthropicApiKey},
 *                           {@code geminiApiKey})
 * @param model              the provider's model id; a cost decision to make from the golden set (design §13 AQ-07).
 *                           With {@code stockflow.assistant.routing.models} set, that list decides which models answer
 *                           ({@link ModelRoutingProperties})
 * @param effort             {@code low}, {@code medium}, {@code high}, {@code xhigh} or {@code max}; Anthropic only
 * @param maxToolRounds      how many times one question may run tools before the assistant must answer
 * @param maxOutputTokens    room for thinking and the answer of one model call
 * @param dailyMessageLimit  questions one user may ask per business day
 * @param retentionDays      days an idle conversation is kept before the retention job deletes it
 * @param requestTimeout     one attempt of a call to the provider; the SDK retries a failed attempt
 *                           {@value #MAX_RETRIES} times, each with this timeout again
 * @param answerLease        how long a claim on a conversation blocks a second question. The loop starts no model
 *                           call that could outlast it, so it must hold at least two of the longest calls
 * @param refusalFallback    lets the provider retry a request its safeguards declined on a fallback model
 * @param baseUrl            the provider's endpoint; blank for its default (a proxy or a test double otherwise)
 * @param provider           {@code anthropic} (the default, ADR-0010), {@code gemini} or {@code openai} (any server
 *                           speaking OpenAI's chat-completions API, such as a local Ollama): which adapter sits behind
 *                           the {@code LanguageModel} port. The loop, the tools and the stored history do not depend
 *                           on it
 * @param anthropicApiKey    {@code ANTHROPIC_API_KEY}: the key of {@code provider=anthropic} when {@code api-key} is
 *                           not set, and of no other provider
 * @param geminiApiKey       {@code GEMINI_API_KEY}: the same for {@code provider=gemini}
 * @param openaiCompatible   where the {@code openai} provider is, and its optional key ({@link OpenAiCompatible})
 * @param dataPolicy         {@code CLOUD_ALLOWED}, or {@code LOCAL_ONLY} for no model outside the company's network
 *                           ({@link DataPolicy}, design §13 AQ-01)
 * @param allowedProviders   the providers the assistant's data may reach ({@code anthropic}, {@code gemini},
 *                           {@code openai}); empty for any ({@link DataBoundary})
 * @param allowedHosts       the hosts outside the company's network the data may reach; empty for any
 */
@ConfigurationProperties(prefix = "stockflow.assistant")
public record AssistantProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String apiKey,
        @DefaultValue("claude-opus-5-5") String model,
        @DefaultValue("low") String effort,
        @DefaultValue("6") int maxToolRounds,
        @DefaultValue("16000") int maxOutputTokens,
        @DefaultValue("50") int dailyMessageLimit,
        @DefaultValue("90") int retentionDays,
        @DefaultValue("PT90S") Duration requestTimeout,
        @DefaultValue("PT15M") Duration answerLease,
        @DefaultValue("true") boolean refusalFallback,
        @DefaultValue("") String baseUrl,
        @DefaultValue("anthropic") String provider,
        @DefaultValue("") String anthropicApiKey,
        @DefaultValue("") String geminiApiKey,
        @DefaultValue OpenAiCompatible openaiCompatible,
        @DefaultValue("CLOUD_ALLOWED") DataPolicy dataPolicy,
        @DefaultValue List<String> allowedProviders,
        @DefaultValue List<String> allowedHosts) {

    /** Retries of a failed provider call after the first attempt: by the SDK for Anthropic, the adapter otherwise. */
    public static final int MAX_RETRIES = 2;

    /** Anthropic's API, where {@code base-url} is blank. */
    static final String ANTHROPIC_BASE_URL = "https://api.anthropic.com";

    private static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");

    /**
     * A server speaking OpenAI's chat-completions and embeddings API ({@code stockflow.assistant.openai-compatible}):
     * Ollama, vLLM or LM Studio, usually on the company's own machine.
     *
     * @param baseUrl         the API's root, the path before {@code /chat/completions}; Ollama's on this machine by
     *                        default
     * @param apiKey          {@code OPENAI_COMPATIBLE_API_KEY}, sent as a bearer token when set; a local server needs
     *                        none
     * @param maxOutputTokens the most an answer may take on this server ({@code max_tokens} is the smaller of this and
     *                        {@code max-output-tokens}): a local model's context holds prompt and answer together, and
     *                        vLLM refuses a request whose prompt plus {@code max_tokens} exceeds it
     */
    public record OpenAiCompatible(
            @DefaultValue(OpenAiCompatible.OLLAMA) String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("4096") int maxOutputTokens) {

        /** Ollama's OpenAI-compatible API on this machine. */
        public static final String OLLAMA = "http://localhost:11434/v1";

        /** The constructor the binder uses: the record has a second one, without the answer's room. */
        @ConstructorBinding
        public OpenAiCompatible {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? OLLAMA : baseUrl.strip();
            while (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            apiKey = apiKey == null ? "" : apiKey.strip();
            if (maxOutputTokens < 256) {
                throw new IllegalArgumentException(
                        "stockflow.assistant.openai-compatible.max-output-tokens must be at least 256");
            }
        }

        public OpenAiCompatible(String baseUrl, String apiKey) {
            this(baseUrl, apiKey, 4096);
        }

        /** Hides the key, and any user and password the URL carries, as the enclosing record does. */
        @Override
        public String toString() {
            return "OpenAiCompatible[baseUrl=%s, apiKey=%s, maxOutputTokens=%d]".formatted(loggable(baseUrl),
                    apiKey.isEmpty() ? "<none>" : "<set>", maxOutputTokens);
        }
    }

    /**
     * {@code url} as a log may show it: without the {@code user:password@} it may carry, which a base URL of a proxy or
     * a hosted model server sometimes does.
     */
    static String loggable(String url) {
        if (url == null) {
            return "";
        }
        return url.replaceFirst("^([A-Za-z][A-Za-z0-9+.-]*://)[^/@?#]*@", "$1");
    }

    /** The constructor the binder uses: the record has others, for settings given directly. */
    @ConstructorBinding
    public AssistantProperties {
        provider = provider == null || provider.isBlank() ? "anthropic" : provider.strip().toLowerCase();
        ModelProvider kind = ModelProvider.named(provider);
        if (kind == null) {
            throw new IllegalArgumentException("stockflow.assistant.provider must be one of " + ModelProvider.ids());
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("stockflow.assistant.model must not be blank");
        }
        if (!EFFORTS.contains(effort)) {
            throw new IllegalArgumentException("stockflow.assistant.effort must be one of " + EFFORTS);
        }
        if (maxToolRounds < 1 || maxToolRounds > 20) {
            throw new IllegalArgumentException("stockflow.assistant.max-tool-rounds must be between 1 and 20");
        }
        if (maxOutputTokens < 1024 || maxOutputTokens > 64_000) {
            throw new IllegalArgumentException("stockflow.assistant.max-output-tokens must be between 1024 and 64000");
        }
        if (dailyMessageLimit < 1) {
            throw new IllegalArgumentException("stockflow.assistant.daily-message-limit must be at least 1");
        }
        if (retentionDays < 1) {
            throw new IllegalArgumentException("stockflow.assistant.retention-days must be at least 1");
        }
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("stockflow.assistant.request-timeout must be positive");
        }
        // The loop stops calling the model when the next call could outlast the lease (AssistantService), so the
        // lease must leave room for a first call and a closing one. Each call is up to MAX_RETRIES + 1 attempts.
        if (answerLease.compareTo(requestTimeout.multipliedBy(2L * (MAX_RETRIES + 1))) < 0) {
            throw new IllegalArgumentException("stockflow.assistant.answer-lease must be at least "
                    + "request-timeout x " + 2 * (MAX_RETRIES + 1));
        }
        anthropicApiKey = anthropicApiKey == null ? "" : anthropicApiKey.strip();
        geminiApiKey = geminiApiKey == null ? "" : geminiApiKey.strip();
        openaiCompatible = openaiCompatible == null ? new OpenAiCompatible(null, null) : openaiCompatible;
        dataPolicy = dataPolicy == null ? DataPolicy.CLOUD_ALLOWED : dataPolicy;
        allowedProviders = allowedProviders == null ? List.of() : List.copyOf(allowedProviders);
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        apiKey = apiKey == null || apiKey.isBlank() ? ownKey(kind, anthropicApiKey, geminiApiKey, openaiCompatible)
                : apiKey.strip();
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
    }

    /** The settings without the allow-lists: any provider and host the data policy allows. */
    public AssistantProperties(boolean enabled, String apiKey, String model, String effort, int maxToolRounds,
                               int maxOutputTokens, int dailyMessageLimit, int retentionDays, Duration requestTimeout,
                               Duration answerLease, boolean refusalFallback, String baseUrl, String provider,
                               String anthropicApiKey, String geminiApiKey, OpenAiCompatible openaiCompatible,
                               DataPolicy dataPolicy) {
        this(enabled, apiKey, model, effort, maxToolRounds, maxOutputTokens, dailyMessageLimit, retentionDays,
                requestTimeout, answerLease, refusalFallback, baseUrl, provider, anthropicApiKey, geminiApiKey,
                openaiCompatible, dataPolicy, List.of(), List.of());
    }

    /** The settings as they were before the OpenAI-compatible provider and the data policy: both at their defaults. */
    public AssistantProperties(boolean enabled, String apiKey, String model, String effort, int maxToolRounds,
                               int maxOutputTokens, int dailyMessageLimit, int retentionDays, Duration requestTimeout,
                               Duration answerLease, boolean refusalFallback, String baseUrl, String provider,
                               String anthropicApiKey, String geminiApiKey) {
        this(enabled, apiKey, model, effort, maxToolRounds, maxOutputTokens, dailyMessageLimit, retentionDays,
                requestTimeout, answerLease, refusalFallback, baseUrl, provider, anthropicApiKey, geminiApiKey,
                null, null);
    }

    /** The settings with the key given directly, as tests and tools build them: no variable to fall back to. */
    public AssistantProperties(boolean enabled, String apiKey, String model, String effort, int maxToolRounds,
                               int maxOutputTokens, int dailyMessageLimit, int retentionDays, Duration requestTimeout,
                               Duration answerLease, boolean refusalFallback, String baseUrl, String provider) {
        this(enabled, apiKey, model, effort, maxToolRounds, maxOutputTokens, dailyMessageLimit, retentionDays,
                requestTimeout, answerLease, refusalFallback, baseUrl, provider, "", "");
    }

    /** A provider's own key variable's value: never another vendor's (class javadoc). */
    static String ownKey(ModelProvider provider, String anthropicApiKey, String geminiApiKey,
                         OpenAiCompatible openaiCompatible) {
        return switch (provider) {
            case ANTHROPIC -> anthropicApiKey;
            case GEMINI -> geminiApiKey;
            case OPENAI -> openaiCompatible.apiKey();
        };
    }

    /** The provider behind the {@code LanguageModel} bean. */
    public ModelProvider providerKind() {
        return ModelProvider.named(provider);
    }

    /** Where that provider is called: {@code base-url} for the cloud vendors when set, the configured server for openai. */
    public String endpoint() {
        return switch (providerKind()) {
            case OPENAI -> openaiCompatible.baseUrl();
            case GEMINI -> baseUrl.isEmpty() ? GeminiLanguageModel.DEFAULT_BASE_URL : baseUrl;
            case ANTHROPIC -> baseUrl.isEmpty() ? ANTHROPIC_BASE_URL : baseUrl;
        };
    }

    /** The longest one model call may take: every attempt timing out. */
    public Duration longestModelCall() {
        return requestTimeout.multipliedBy(MAX_RETRIES + 1L);
    }

    /**
     * Whether the assistant can answer at all: switched on, given a key that belongs to its provider (or none, for a
     * local server that needs none), and at an endpoint the data policy allows.
     */
    public boolean usable() {
        return enabled && (!apiKey.isEmpty() || !providerKind().needsKey()) && !keyOfAnotherVendor()
                && allowedByPolicy();
    }

    /** Whether the data policy and the allow-lists let the provider's endpoint be used ({@link DataBoundary}). */
    public boolean allowedByPolicy() {
        return boundaryRefusal().isEmpty();
    }

    /** Why the provider's endpoint may not be used; empty when it may. */
    Optional<String> boundaryRefusal() {
        return boundary().refusal(providerKind(), endpoint());
    }

    /** Where the assistant's data may go: the policy and the allow-lists ({@link DataBoundary}). */
    DataBoundary boundary() {
        return DataBoundary.of(dataPolicy, allowedProviders, allowedHosts);
    }

    /**
     * Whether the key is shaped like another vendor's ({@link ModelProvider#keyOfAnotherVendor}). Such a key is never
     * sent anywhere; the provider would only refuse it, after it had arrived.
     */
    public boolean keyOfAnotherVendor() {
        return providerKind().keyOfAnotherVendor(apiKey);
    }

    /** The variable that holds this provider's own key, for the messages that tell an operator what to set. */
    public String keyVariable() {
        return providerKind().keyVariable();
    }

    /** Hides the keys: these properties are logged by the actuator and by startup diagnostics. */
    @Override
    public String toString() {
        return ("AssistantProperties[enabled=%s, provider=%s, apiKey=%s, model=%s, effort=%s, maxToolRounds=%d, "
                + "dataPolicy=%s, allowedProviders=%s, allowedHosts=%s]").formatted(enabled, provider,
                apiKey.isEmpty() ? "<none>" : "<set>", model, effort, maxToolRounds, dataPolicy, allowedProviders,
                allowedHosts);
    }
}
