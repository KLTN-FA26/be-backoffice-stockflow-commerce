-- =============================================================================
-- Assistant: search the rules by meaning as well as by their words (ADR-0010, docs/assistant-design.md §6.4).
-- Module: assistant
--
-- The rules and the glossary are written in English and the staff ask in Vietnamese, so a question
-- often shares no word with the rule that answers it. Each passage gets a vector from a multilingual
-- embedding model (Gemini), and a search ranks the passages by cosine similarity to the question's
-- vector beside the keyword ranking, the two fused by reciprocal rank (KnowledgeService).
--
-- A plain real[] ranked in Java, not pgvector. About 170 passages are read and compared in a few
-- milliseconds, while pgvector means another postgres image in docker-compose, the deploy, the
-- Testcontainers support and the migration-upgrade tests, for the whole team. It is worth that once
-- the corpus passes a few thousand passages or reading the vectors shows in a search's latency; this
-- column then converts with ALTER ... TYPE vector(n) USING embedding::vector(n), and an HNSW index.
--
-- A vector is kept with the model that made it and the content_hash of the text it was made from.
-- A search compares only vectors of the configured model made from the passage's current text, so a
-- passage the keyword sync rewrote, or a change of model, never meets an old vector: the indexer
-- embeds those passages again. JPA does not map these columns; the repository adapter writes them.
-- =============================================================================

ALTER TABLE assistant.knowledge_chunk
    ADD COLUMN embedding        REAL[],
    -- The embedding model, as the provider names it (gemini-embedding-001).
    ADD COLUMN embedding_model  VARCHAR(100),
    -- The content_hash of the title and body the vector was made from.
    ADD COLUMN embedding_hash   VARCHAR(64);

-- All three or none; a vector is one-dimensional, not empty, and every value is a finite number.
-- Written so it is never NULL (a NULL check passes): each branch is TRUE or FALSE for any row.
ALTER TABLE assistant.knowledge_chunk
    ADD CONSTRAINT ck_knowledge_chunk_embedding CHECK (
        (embedding IS NULL AND embedding_model IS NULL AND embedding_hash IS NULL)
        OR (embedding IS NOT NULL AND embedding_model IS NOT NULL AND embedding_hash IS NOT NULL
            AND cardinality(embedding) > 0 AND array_ndims(embedding) = 1
            AND array_position(embedding, NULL) IS NULL
            AND NOT (embedding && '{NaN,Infinity,-Infinity}'::real[])
            AND length(btrim(embedding_model)) > 0
            AND embedding_hash ~ '^[0-9a-f]{64}$'));
