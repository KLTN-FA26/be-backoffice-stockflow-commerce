package com.stockflow.assistant.internal.domain;

import java.util.List;

/**
 * The port to an embedding provider: texts in, one vector per text out. It is what lets the knowledge search find a
 * rule by what a question means and not only by the words the two share (design §6.4): the rules are written in
 * English, the staff ask in Vietnamese, and the question often shares no word with the rule that answers it.
 *
 * <p><b>Optional by design.</b> {@link #available()} is false when embeddings are switched off, have no key, or the
 * assistant runs on a provider with no embedding API (Anthropic). The search is then keyword-only, exactly as before
 * vectors existed, so nothing in {@code mvn test} needs a key or a network.</p>
 */
public interface EmbeddingModel {

    /**
     * What a text is embedded as. A retrieval model places a question near the passage that answers it, rather than
     * near passages merely worded like the question, only when it is told which side each text is on.
     */
    enum Purpose { DOCUMENT, QUERY }

    /** False when the search must stay keyword-only: switched off, no key, or no embedding API at the provider. */
    boolean available();

    /**
     * The model the vectors come from, stored beside every vector. A vector of another model, or of another length,
     * is never compared with this model's: a change of model re-embeds the passages instead of ranking nonsense.
     */
    String name();

    /** The length of every vector this model returns. */
    int dimensions();

    /**
     * One vector per text, in the order given, each {@link #dimensions()} long and every value finite.
     *
     * @throws com.stockflow.common.error.BusinessException {@code ASSISTANT_UNAVAILABLE} when the provider cannot be
     *         reached, times out or fails (worth trying again later); {@code ASSISTANT_QUOTA_EXHAUSTED} when its quota
     *         is spent and {@code ASSISTANT_MISCONFIGURED} when it refuses the key, the model, the length asked for or
     *         the request (neither changes by trying again); {@code ASSISTANT_DISABLED} when {@link #available()} is
     *         false
     */
    List<float[]> embed(List<String> texts, Purpose purpose);
}
