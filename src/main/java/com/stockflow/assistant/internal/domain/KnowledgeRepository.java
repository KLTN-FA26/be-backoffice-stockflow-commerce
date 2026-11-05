package com.stockflow.assistant.internal.domain;

import java.time.Instant;
import java.util.List;

/** The retrieval corpus (design §6). Runs inside the caller's transaction. */
public interface KnowledgeRepository {

    /**
     * Makes {@code source} hold exactly {@code chunks}: inserts new anchors, rewrites changed ones (by content hash),
     * deletes anchors no longer in the document — unless a newer build indexed it last, which is left alone.
     * Serialised across instances by a database lock, so two instances starting together do not race on the rows.
     *
     * @param builtAt when this build was made; null when unknown, which never counts as older
     */
    Sync sync(String source, List<KnowledgeChunk> chunks, Instant builtAt);

    /**
     * @param changes      rows written or deleted; zero when the document did not change
     * @param newerIndexed the source was left alone: a newer build indexed it
     */
    record Sync(int changes, boolean newerIndexed) {
    }

    /**
     * The keyword ranking: the best {@code limit} chunks for {@code query} by its words in the text (accents and case
     * ignored), title similarity for typos, and a rule the query names first ({@link KnowledgeSearch}).
     *
     * @param minMatched how many of the query's words a chunk's text must hold to be found by its words, at most as
     *                   many as the query has: one lets a single shared word ("calendar") bring back a passage about
     *                   something else. A rule the query names, and a title close to it, are found regardless.
     */
    List<KnowledgeChunk> search(String query, int limit, int minMatched);

    /**
     * The chunks to embed for {@code model}: no vector yet, a vector of another model or of another length, or one
     * made from text the document has changed since. Ordered by source and anchor.
     */
    List<KnowledgeChunk> withoutEmbedding(String model, int dimensions);

    /**
     * Stores {@code embedding}, made by {@code model} from {@code chunk}'s title and body, as that chunk's vector —
     * unless the stored chunk no longer has that text (another instance's sync rewrote it meanwhile), which leaves the
     * row for the next pass rather than putting the vector of the old text on the new one.
     *
     * @return whether it was stored
     */
    boolean saveEmbedding(KnowledgeChunk chunk, String model, float[] embedding);

    /** Every chunk holding a vector of {@code model}, {@code dimensions} long, made from its current text. */
    List<EmbeddedChunk> embedded(String model, int dimensions);

    /** @param embedding the chunk's vector, shared with whoever read it: never modified */
    record EmbeddedChunk(KnowledgeChunk chunk, float[] embedding) {
    }
}
