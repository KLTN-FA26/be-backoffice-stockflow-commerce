package com.stockflow.assistant.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
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
 * @param apiKey             from {@code ANTHROPIC_API_KEY}; never written in a YAML file
 * @param model              the provider's model id; a cost decision to make from the golden set (design §13 AQ-07)
 * @param effort             {@code low}, {@code medium}, {@code high}, {@code xhigh} or {@code max}
 * @param maxToolRounds      how many times one question may run tools before the assistant must answer
 * @param maxOutputTokens    room for thinking and the answer of one model call
 * @param dailyMessageLimit  questions one user may ask per business day
 * @param retentionDays      days an idle conversation is kept before the retention job deletes it
 * @param requestTimeout     one call to the provider, retries included by the SDK
 * @param answerLease        how long a claim on a conversation blocks a second question; must outlast an answer
 * @param refusalFallback    lets the provider retry a request its safeguards declined on a fallback model
 * @param baseUrl            the provider's endpoint; blank for its default (a proxy or a test double otherwise)
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
        @DefaultValue("") String baseUrl) {

    private static final Set<String> EFFORTS = Set.of("low", "medium", "high", "xhigh", "max");

    public AssistantProperties {
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
        // One answer is up to max-tool-rounds + 1 model calls; a lease shorter than that would free the
        // conversation while it is still being answered.
        if (answerLease.compareTo(requestTimeout.multipliedBy(maxToolRounds + 1L)) < 0) {
            throw new IllegalArgumentException("stockflow.assistant.answer-lease must be at least "
                    + "request-timeout x (max-tool-rounds + 1)");
        }
        apiKey = apiKey == null ? "" : apiKey.strip();
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
    }

    /** Whether the assistant can answer at all: switched on and given credentials. */
    public boolean usable() {
        return enabled && !apiKey.isEmpty();
    }

    /** Hides the key: these properties are logged by the actuator and by startup diagnostics. */
    @Override
    public String toString() {
        return "AssistantProperties[enabled=%s, apiKey=%s, model=%s, effort=%s, maxToolRounds=%d]"
                .formatted(enabled, apiKey.isEmpty() ? "<none>" : "<set>", model, effort, maxToolRounds);
    }
}
