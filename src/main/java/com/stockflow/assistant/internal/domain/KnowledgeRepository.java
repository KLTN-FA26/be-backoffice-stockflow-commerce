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
     * The best {@code limit} chunks for {@code query}: any of its words in the text (accents and case ignored), title
     * similarity for typos, and an exact anchor named in the query first.
     */
    List<KnowledgeChunk> search(String query, int limit);
}
