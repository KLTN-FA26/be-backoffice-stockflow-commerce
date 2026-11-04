package com.stockflow.assistant.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Searching the rules by meaning ({@code stockflow.assistant.embeddings}, design §6.4 and §11): a vector for every
 * passage and every question from a multilingual embedding model, beside the keyword search.
 *
 * <p><b>Two providers, and no new third party.</b> What is embedded is the knowledge base's passages and the search
 * terms the model writes for a question, so the vendor that makes the vectors learns what users ask.</p>
 * <ul>
 *   <li>{@code gemini} only for a Gemini assistant, the vendor that already receives the whole conversation. Under
 *       another provider the search stays keyword-only, even with a key set here: a data flow design §5.5 and AQ-01
 *       have not agreed to.</li>
 *   <li>{@code openai}, a server speaking OpenAI's {@code /embeddings} API at
 *       {@code stockflow.assistant.openai-compatible.base-url}, such as a local Ollama serving {@code bge-m3}. Inside the
 *       company's network it suits any chat provider, Claude included, which has no embedding API of its own. On a
 *       public host it is a vendor like any other, and only an {@code openai} assistant may use it.</li>
 * </ul>
 *
 * <p><b>The key</b> of {@code gemini} is {@code api-key} when set, otherwise the Gemini assistant's own — never an
 * Anthropic key, for the reason {@link AssistantProperties} gives. That of {@code openai} is the server's own,
 * {@code OPENAI_COMPATIBLE_API_KEY}, usually none.</p>
 *
 * <p>Like the assistant itself, never a reason not to start: switched off, without a key, or against the data policy,
 * the search is keyword-only. A value that cannot work (a blank model, an unknown provider, a length no model makes)
 * fails the start, as a wrong {@code stockflow.assistant} value does.</p>
 *
 * @param enabled    on unless switched off; it takes effect only where it can work. Switching it off is also how the
 *                   golden set's recall@5 is measured without vectors
 * @param provider   {@code gemini} (the default) or {@code openai}
 * @param apiKey     {@code ASSISTANT_EMBEDDINGS_API_KEY}, for {@code gemini}; blank for the Gemini assistant's own key
 * @param model      the embedding model ({@code gemini-embedding-001}, {@code bge-m3}). Every vector is stored with its
 *                   name, so a new model re-embeds every passage on the next start, and searches by keyword alone
 *                   until it has
 * @param dimensions the length of a vector, {@value #MIN_DIMENSIONS} to {@value #MAX_DIMENSIONS}: what Gemini is asked
 *                   for, and what any other model must return (1024 for {@code bge-m3}). A new length re-embeds every
 *                   passage too
 */
@ConfigurationProperties(prefix = "stockflow.assistant.embeddings")
public record EmbeddingProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("gemini") String provider,
        @DefaultValue("") String apiKey,
        @DefaultValue("gemini-embedding-001") String model,
        @DefaultValue("768") int dimensions) {

    /** The shortest vector Gemini's embedding models make. */
    public static final int MIN_DIMENSIONS = 128;

    /** The longest: {@code gemini-embedding-001} at full length. */
    public static final int MAX_DIMENSIONS = 3072;

    /** The constructor the binder uses: the record has a second one, for Gemini settings given directly. */
    @ConstructorBinding
    public EmbeddingProperties {
        provider = provider == null || provider.isBlank() ? "gemini" : provider.strip().toLowerCase();
        ModelProvider kind = ModelProvider.named(provider);
        if (kind != ModelProvider.GEMINI && kind != ModelProvider.OPENAI) {
            throw new IllegalArgumentException("stockflow.assistant.embeddings.provider must be gemini or openai");
        }
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

    /** The settings as they were before the {@code openai} provider: Gemini's. */
    public EmbeddingProperties(boolean enabled, String apiKey, String model, int dimensions) {
        this(enabled, "gemini", apiKey, model, dimensions);
    }

    /** The provider the vectors are made by. */
    public ModelProvider providerKind() {
        return ModelProvider.named(provider);
    }

    /**
     * The key vectors are made with for {@code assistant}. For {@code gemini}: none unless the assistant runs on
     * Gemini, and then this key or else the assistant's own. For {@code openai}: the server's own key, if it has one.
     */
    public String keyFor(AssistantProperties assistant) {
        if (providerKind() == ModelProvider.OPENAI) {
            return assistant.openaiCompatible().apiKey();
        }
        if (assistant.providerKind() != ModelProvider.GEMINI) {
            return "";
        }
        return apiKey.isEmpty() ? assistant.apiKey() : apiKey;
    }

    /** Hides the key: these properties are logged by the actuator and by startup diagnostics. */
    @Override
    public String toString() {
        return "EmbeddingProperties[enabled=%s, provider=%s, model=%s, dimensions=%d, apiKey=%s]"
                .formatted(enabled, provider, model, dimensions, apiKey.isEmpty() ? "<none>" : "<set>");
    }
}
