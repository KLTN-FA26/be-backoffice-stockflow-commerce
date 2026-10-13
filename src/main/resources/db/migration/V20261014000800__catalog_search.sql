-- =============================================================================
-- Catalog search on PostgreSQL (SCRUM-170 / 181 / 182, ADR-0009; docs 13 §4.2 step 2, §6 edge cases).
-- Module: catalog
--
-- Replaces the Elasticsearch index the original plan assumed: no second system to run, and the
-- search document is a GENERATED column, so it cannot drift from the published title and
-- description (docs 13 §6 "index differs from source" has no way to occur for text).
--
-- Both extensions are "trusted" on PostgreSQL 13+, so the owner role of the database may create
-- them without being a superuser; both ship in the postgres:16-alpine image (contrib), exactly as
-- btree_gist does for V20260928002000.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

-- unaccent() is only STABLE (its dictionary can be replaced), which forbids it in an index or a
-- generated column. Pinning the dictionary by name makes the result a pure function of the input,
-- which is what IMMUTABLE promises. Vietnamese: "Bàn gỗ" -> "Ban go", "Đà" -> "Da".
CREATE FUNCTION catalog.immutable_unaccent(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $$;

-- Title weighs A, description C (ts_rank_cd's default weight table: A 1.0, C 0.2). Generated, so no
-- writer can store a document that disagrees with the title it was built from.
ALTER TABLE catalog.product_listing
    ADD COLUMN search_document tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', catalog.immutable_unaccent(coalesce(published_title, ''))), 'A')
        || setweight(to_tsvector('simple', catalog.immutable_unaccent(coalesce(published_description, ''))), 'C')
    ) STORED;

CREATE INDEX ix_product_listing_search_document
    ON catalog.product_listing USING gin (search_document);

-- Typo / partial-word fallback ("ly nhua" misspelt as "ly nhuaa"): trigram similarity on the
-- accent-folded, lower-cased title. The expression must match the query's text exactly to be used.
CREATE INDEX ix_product_listing_title_trgm
    ON catalog.product_listing USING gin (catalog.immutable_unaccent(lower(published_title)) public.gin_trgm_ops);

-- SKU lookups: exact code and "starts with" (LIKE 'abc%') both use a trigram index.
CREATE INDEX ix_catalog_entry_sku_trgm
    ON catalog.catalog_entry USING gin (lower(sku) public.gin_trgm_ops);

COMMENT ON COLUMN catalog.product_listing.search_document IS
    'Generated: tsvector (simple config, accent-folded) of the published title (A) and description (C). Never written directly.';
