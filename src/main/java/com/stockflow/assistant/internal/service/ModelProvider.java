package com.stockflow.assistant.internal.service;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

/**
 * The vendors a model of the assistant can run on (design §11): one place that knows each one's key variable, the shape
 * of its key, and when its quota day ends, instead of a "gemini or else" in every class that builds an adapter.
 *
 * <p><b>{@code openai} is a wire format, not a company.</b> It is any server speaking OpenAI's chat-completions API —
 * Ollama, vLLM or LM Studio on the company's own machine — at {@code stockflow.assistant.openai-compatible.base-url}.
 * Its key is optional, because a local server has none, and a cloud vendor's key is never sent to it: the endpoint is
 * whatever an operator typed, and a Google or Anthropic credential has no business arriving there.</p>
 */
enum ModelProvider {

    ANTHROPIC("anthropic", "ANTHROPIC_API_KEY") {
        @Override
        boolean keyOfAnotherVendor(String key) {
            return key.startsWith(GOOGLE_KEY);
        }
    },
    GEMINI("gemini", "GEMINI_API_KEY") {
        @Override
        boolean keyOfAnotherVendor(String key) {
            return key.startsWith(ANTHROPIC_KEY);
        }

        @Override
        ZoneId quotaDay() {
            return GEMINI_QUOTA_DAY;
        }
    },
    OPENAI("openai", "OPENAI_COMPATIBLE_API_KEY") {
        @Override
        boolean keyOfAnotherVendor(String key) {
            return key.startsWith(GOOGLE_KEY) || key.startsWith(ANTHROPIC_KEY);
        }

        @Override
        boolean needsKey() {
            return false;
        }
    };

    /** Where Gemini's daily quotas restart: midnight Pacific time (Google's rate-limit documentation, October 2026). */
    static final ZoneId GEMINI_QUOTA_DAY = ZoneId.of("America/Los_Angeles");

    private static final String GOOGLE_KEY = "AIza";
    private static final String ANTHROPIC_KEY = "sk-ant-";

    private final String id;
    private final String keyVariable;

    ModelProvider(String id, String keyVariable) {
        this.id = id;
        this.keyVariable = keyVariable;
    }

    /** The name in configuration: {@code stockflow.assistant.provider} and the prefix of a routed model. */
    String id() {
        return id;
    }

    /** The variable that holds this vendor's own key, for the messages that tell an operator what to set. */
    String keyVariable() {
        return keyVariable;
    }

    /**
     * Whether {@code key} is shaped like another vendor's — Anthropic keys start {@code sk-ant-}, Google API keys
     * {@code AIza} — and so must never be sent here: the call would only be refused, after the credential arrived.
     */
    abstract boolean keyOfAnotherVendor(String key);

    /** Whether a call needs a key at all: a cloud vendor does, a local server does not. */
    boolean needsKey() {
        return true;
    }

    /**
     * Where this vendor's quota day ends at midnight ({@link ModelRouter}): Pacific time for Gemini's free tier, UTC
     * for the others, which count no daily quota of their own.
     */
    ZoneId quotaDay() {
        return ZoneOffset.UTC;
    }

    /** The provider of a configured name, case-insensitive; null for a name that is none of them. */
    static ModelProvider named(String name) {
        if (name == null) {
            return null;
        }
        String wanted = name.strip().toLowerCase();
        return Arrays.stream(values()).filter(provider -> provider.id.equals(wanted)).findFirst().orElse(null);
    }

    /** Every configured name, for the message that refuses an unknown one. */
    static List<String> ids() {
        return Arrays.stream(values()).map(ModelProvider::id).toList();
    }
}
