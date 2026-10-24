-- =============================================================================
-- Assistant: the retrieval corpus (ADR-0010, docs/assistant-design.md §6).
-- Module: assistant
--
-- One row per business rule, glossary term or state-machine passage, indexed from docs/business-design
-- on every start (KnowledgeService). Search is lexical, in PostgreSQL, with the same machinery as the
-- catalog (ADR-0009): a generated tsvector over unaccented lower-case text, pg_trgm similarity on the
-- title, and a GIN index for each. Vector search is added only if the golden set's recall@5 says
-- lexical search falls short (design §6.4).
-- =============================================================================

-- The assistant's own copy of the catalog's wrapper: calling catalog.immutable_unaccent from here would be
-- a cross-schema dependency that nothing in the build sees. Same definition, same reason it is IMMUTABLE
-- (the dictionary is pinned by name, so the result is a pure function of the input).
CREATE FUNCTION assistant.immutable_unaccent(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $$;


CREATE TABLE assistant.knowledge_chunk
(
    id               UUID          NOT NULL,
    -- The document, by file name without extension: 05-business-rules, 01-ubiquitous-language, ...
    source           VARCHAR(64)   NOT NULL,
    -- What an answer cites: BR-RMA-002, a glossary term, a state machine's heading.
    anchor           VARCHAR(160)  NOT NULL,
    kind             VARCHAR(16)   NOT NULL,
    title            VARCHAR(300)  NOT NULL,
    body             TEXT          NOT NULL,
    -- SHA-256 of title and body: the indexer rewrites a chunk only when its text changed.
    content_hash     VARCHAR(64)   NOT NULL,
    -- Anchor and title weigh A, body C. Generated, so it can never disagree with the text.
    search_document  tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', assistant.immutable_unaccent(lower(anchor || ' ' || title))), 'A')
        || setweight(to_tsvector('simple', assistant.immutable_unaccent(lower(body))), 'C')
    ) STORED,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_knowledge_chunk PRIMARY KEY (id),
    CONSTRAINT uk_knowledge_chunk_anchor UNIQUE (source, anchor),
    CONSTRAINT ck_knowledge_chunk_kind CHECK (kind IN ('RULE', 'TERM', 'STATE_MACHINE')),
    CONSTRAINT ck_knowledge_chunk_text CHECK (length(btrim(anchor)) > 0 AND length(btrim(title)) > 0
        AND length(btrim(body)) > 0 AND length(body) <= 20000),
    CONSTRAINT ck_knowledge_chunk_hash CHECK (content_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_knowledge_chunk_search ON assistant.knowledge_chunk USING gin (search_document);

-- Which build last indexed each document. Every start syncs the index to that build's copy of the
-- documents, so an older build started after a newer one (a staggered rollout, an old instance
-- restarting after a crash) would put the older rules back and every instance would cite them. A
-- start leaves alone a document whose stored build is newer than its own. built_at is the build
-- time from build-info.properties; NULL when a run has none (an IDE), which never blocks a later sync.
CREATE TABLE assistant.knowledge_source
(
    source     VARCHAR(64)  NOT NULL,
    built_at   TIMESTAMPTZ,
    synced_at  TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_knowledge_source PRIMARY KEY (source)
);
CREATE INDEX ix_knowledge_chunk_title_trgm
    ON assistant.knowledge_chunk USING gin (assistant.immutable_unaccent(lower(title)) public.gin_trgm_ops);
