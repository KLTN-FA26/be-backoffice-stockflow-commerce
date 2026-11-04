package com.stockflow.assistant.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Searching the rules by meaning ({@code stockflow.assistant.embeddings}, design §6.4 and §11): a vector for every
 * passage and every question from a multilingual embedding model, beside the keyword search.
 *
 * <p><b>Gemini only, and only for a Gemini assistant.</b> Anthropic has no embedding API, so under
 * {@code provider=anthropic} the search stays keyword-only, even with a key set here: embedding a Claude assistant's
 * search terms at Google would send what users ask to a second vendor, a data flow design §5.5 and AQ-01 have not
 * agreed to. Under {@code provider=gemini} the passages and the search terms go to the vendor that already receives
 * the whole conversation.</p>
 *
 * <p><b>The key</b> is {@code api-key} when set, otherwise the Gemini assistant's own — never an Anthropic key, for
 * the reason {@link AssistantProperties} gives. A key of its own only keeps the embedding quota apart.</p>
 *
 * <p>Like the assistant itself, never a reason not to start: switched off, or without a key, the search is
 * keyword-only. A value that cannot work (a blank model, a length Gemini does not make) fails the start, as a wrong
 * {@code stockflow.assistant} value does.</p>
 *
 * @param enabled    on unless switched off; it takes effect only where it can work (a Gemini assistant with a key).
 *                   Switching it off is also how the golden set's recall@5 is measured without vectors
 * @param apiKey     {@code ASSISTANT_EMBEDDINGS_API_KEY}; blank for the Gemini assistant's own key
 * @param model      the embedding model. Every vector is stored with its name, so a new model re-embeds every passage
 *                   on the next start, and searches by keyword alone until it has
 * @param dimensions the length of a vector (Gemini's {@code outputDimensionality}, {@value #MIN_DIMENSIONS} to
 *                   {@value #MAX_DIMENSIONS}). A new length re-embeds every passage too
 */
@ConfigurationProperties(prefix = "stockflow.assistant.embeddings")
public record EmbeddingProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("") String apiKey,
        @DefaultValue("gemini-embedding-001") String model,
        @DefaultValue("768") int dimensions) {

    /** The shortest vector Gemini's embedding models make. */
    public static final int MIN_DIMENSIONS = 128;

    /** The longest: {@code gemini-embedding-001} at full length. */
    public static final int MAX_DIMENSIONS = 3072;

    public EmbeddingProperties {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("stockflow.assistant.embeddings.model must not be blank");
        }
        if (dimensions < MIN_DIMENSIONS || dimensions > MAX_DIMENSIONS) {
            throw new IllegalArgumentException("stockflow.assistant.embeddings.dimensions must be between "
                    + MIN_DIMENSIONS + " and " + MAX_DIMENSIONS);
        }
        model = model.strip();
        apiKey = apiKey == null ? "" : apiKey.strip();
    }

    /**
     * The key vectors are made with for {@code assistant}: none unless it runs on Gemini, and then this key or else
     * the assistant's own.
     */
    public String keyFor(AssistantProperties assistant) {
        if (!"gemini".equals(assistant.provider())) {
            return "";
        }
        return apiKey.isEmpty() ? assistant.apiKey() : apiKey;
    }

    /** Hides the key: these properties are logged by the actuator and by startup diagnostics. */
    @Override
    public String toString() {
        return "EmbeddingProperties[enabled=%s, model=%s, dimensions=%d, apiKey=%s]"
                .formatted(enabled, model, dimensions, apiKey.isEmpty() ? "<none>" : "<set>");
    }
}
