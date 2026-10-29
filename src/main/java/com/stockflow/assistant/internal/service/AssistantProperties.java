package com.stockflow.assistant.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
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
 * @param model              the provider's model id; a cost decision to make from the golden set (design §13 AQ-07)
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
 * @param provider           {@code anthropic} (the default, ADR-0010) or {@code gemini}: which adapter sits behind the
 *                           {@code LanguageModel} port. The loop, the tools and the stored history do not depend on it
 * @param anthropicApiKey    {@code ANTHROPIC_API_KEY}: the key of {@code provider=anthropic} when {@code api-key} is
 *                           not set, and of no other provider
 * @param geminiApiKey       {@code GEMINI_API_KEY}: the same for {@code provider=gemini}
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
        @DefaultValue("") String geminiApiKey) {

    /** Retries of a failed provider call after the first attempt: by the SDK for Anthropic, the adapter for Gemini. */
    public static final int MAX_RETRIES = 2;

    private static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");

    private static final Set<String> PROVIDERS = Set.of("anthropic", "gemini");

    /** The constructor the binder uses: the record has a second one, for a key given directly. */
    @ConstructorBinding
    public AssistantProperties {
        provider = provider == null || provider.isBlank() ? "anthropic" : provider.strip().toLowerCase();
        if (!PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("stockflow.assistant.provider must be one of " + PROVIDERS);
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
        apiKey = apiKey == null || apiKey.isBlank()
                ? ("gemini".equals(provider) ? geminiApiKey : anthropicApiKey)
                : apiKey.strip();
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
    }

    /** The settings with the key given directly, as tests and tools build them: no variable to fall back to. */
    public AssistantProperties(boolean enabled, String apiKey, String model, String effort, int maxToolRounds,
                               int maxOutputTokens, int dailyMessageLimit, int retentionDays, Duration requestTimeout,
                               Duration answerLease, boolean refusalFallback, String baseUrl, String provider) {
        this(enabled, apiKey, model, effort, maxToolRounds, maxOutputTokens, dailyMessageLimit, retentionDays,
                requestTimeout, answerLease, refusalFallback, baseUrl, provider, "", "");
    }

    /** The longest one model call may take: every attempt timing out. */
    public Duration longestModelCall() {
        return requestTimeout.multipliedBy(MAX_RETRIES + 1L);
    }

    /** Whether the assistant can answer at all: switched on, and given a key that belongs to its provider. */
    public boolean usable() {
        return enabled && !apiKey.isEmpty() && !keyOfAnotherVendor();
    }

    /**
     * Whether the key is shaped like the other vendor's: Anthropic keys start {@code sk-ant-}, Google API keys
     * {@code AIza}. Such a key is never sent anywhere; the provider would only refuse it, after it had arrived.
     */
    public boolean keyOfAnotherVendor() {
        return "gemini".equals(provider) ? apiKey.startsWith("sk-ant-") : apiKey.startsWith("AIza");
    }

    /** The variable that holds this provider's own key, for the messages that tell an operator what to set. */
    public String keyVariable() {
        return "gemini".equals(provider) ? "GEMINI_API_KEY" : "ANTHROPIC_API_KEY";
    }

    /** Hides the keys: these properties are logged by the actuator and by startup diagnostics. */
    @Override
    public String toString() {
        return "AssistantProperties[enabled=%s, provider=%s, apiKey=%s, model=%s, effort=%s, maxToolRounds=%d]"
                .formatted(enabled, provider, apiKey.isEmpty() ? "<none>" : "<set>", model, effort, maxToolRounds);
    }
}
