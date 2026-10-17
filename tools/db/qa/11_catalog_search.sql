-- =============================================================================
-- Catalog search on PostgreSQL (SCRUM-170/181/182, V20261014000800, ADR-0009).
-- Needs the demo seed (a listing row is made here for a demo product when the application has not
-- yet projected one, so the script also runs against a freshly migrated database). Rolled back at the end.
-- Rule ids S1..S8 (not used by another script).
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;

CREATE FUNCTION pg_temp.expect_fail(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'ok    %  [%]', label, left(SQLERRM, 110);
        RETURN;
    END;
    RAISE EXCEPTION 'FAIL  %: the database accepted it', label;
END $$;

CREATE FUNCTION pg_temp.expect_true(label TEXT, query TEXT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE ok BOOLEAN;
BEGIN
    EXECUTE query INTO ok;
    IF ok IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'FAIL  %: expected true, got %', label, ok;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

\echo '--- the search document is generated: no writer can store one that disagrees with the title'
INSERT INTO catalog.product_listing (product_id, slug, enabled, ever_published, published_title)
SELECT md5('demo:product:SOFA-3S')::uuid, 'qa-search-sofa', false, false, 'Sofa'
WHERE NOT EXISTS (SELECT 1 FROM catalog.product_listing WHERE product_id = md5('demo:product:SOFA-3S')::uuid);
INSERT INTO catalog.product_listing (product_id, slug, enabled, ever_published, published_title)
SELECT md5('demo:product:TABLE-OAK')::uuid, 'qa-search-table', false, false, 'Table'
WHERE NOT EXISTS (SELECT 1 FROM catalog.product_listing WHERE product_id = md5('demo:product:TABLE-OAK')::uuid);

SELECT pg_temp.expect_fail('S1 writing search_document directly (update)', $q$
    UPDATE catalog.product_listing SET search_document = to_tsvector('simple', 'forged')
    WHERE product_id = md5('demo:product:SOFA-3S')::uuid $q$);
SELECT pg_temp.expect_fail('S1b writing search_document directly (insert)', $q$
    INSERT INTO catalog.product_listing (product_id, slug, search_document)
    VALUES (md5('demo:product:SOFA-3S')::uuid, 'forged-slug', to_tsvector('simple', 'forged')) $q$);

\echo '--- the accent fold is a pure, Vietnamese-aware function'
SELECT pg_temp.expect_true('S2 immutable_unaccent(Ly nhựa) = Ly nhua', $q$
    SELECT catalog.immutable_unaccent('Ly nhựa') = 'Ly nhua' $q$);
SELECT pg_temp.expect_true('S3 every Vietnamese vowel and đ fold to ASCII', $q$
    SELECT catalog.immutable_unaccent('Bàn ăn gỗ sồi, Đà Nẵng, ướt ỷ ữ') = 'Ban an go soi, Da Nang, uot y u' $q$);
SELECT pg_temp.expect_true('S4 the fold is IMMUTABLE and PARALLEL SAFE (it sits in an index and a generated column)', $q$
    SELECT provolatile = 'i' AND proparallel = 's' FROM pg_proc
    WHERE oid = 'catalog.immutable_unaccent(text)'::regprocedure $q$);

\echo '--- the document follows the title, accent-folded, title heavier than description'
UPDATE catalog.product_listing
SET published_title = 'Bàn gỗ sồi', published_description = 'Chất liệu đặc biệt'
WHERE product_id = md5('demo:product:TABLE-OAK')::uuid;
SELECT pg_temp.expect_true('S5 a title is searchable without its accents, with weight A', $q$
    SELECT search_document::text LIKE '%''ban'':1A%' AND search_document::text LIKE '%''go'':2A%'
       AND search_document @@ to_tsquery('simple', 'ban & go:*')
    FROM catalog.product_listing WHERE product_id = md5('demo:product:TABLE-OAK')::uuid $q$);
SELECT pg_temp.expect_true('S6 a description word is searchable with weight C', $q$
    SELECT search_document::text LIKE '%''dac'':%C%'
    FROM catalog.product_listing WHERE product_id = md5('demo:product:TABLE-OAK')::uuid $q$);
UPDATE catalog.product_listing SET published_title = NULL, published_description = NULL
WHERE product_id = md5('demo:product:TABLE-OAK')::uuid;
SELECT pg_temp.expect_true('S7 a listing with no published text has an empty document, not NULL', $q$
    SELECT search_document = ''::tsvector
    FROM catalog.product_listing WHERE product_id = md5('demo:product:TABLE-OAK')::uuid $q$);

\echo '--- the indexes exist and are GIN'
SELECT pg_temp.expect_true('S8 GIN on search_document, trigram on title and on sku', $q$
    SELECT count(*) = 3 FROM pg_indexes
    WHERE schemaname = 'catalog'
      AND ((indexname = 'ix_product_listing_search_document' AND indexdef LIKE '%USING gin (search_document)%')
        OR (indexname = 'ix_product_listing_title_trgm' AND indexdef LIKE '%gin_trgm_ops%')
        OR (indexname = 'ix_catalog_entry_sku_trgm' AND indexdef LIKE '%gin_trgm_ops%')) $q$);

ROLLBACK;
\echo 'PASS 10_catalog_search'
