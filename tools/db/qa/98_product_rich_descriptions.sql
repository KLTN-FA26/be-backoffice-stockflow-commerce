-- =============================================================================
-- Rich-text descriptions (V20270106000300, ADR-0013): a variant's own description, at most 100 000 characters and
-- never an empty string (ck_variants_description). Needs the demo seed (any variant). Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.expect_fail_by(label TEXT, rule TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM NOT LIKE '%' || rule || '%' THEN
            RAISE EXCEPTION 'FAIL  %: refused, but not by %: %', label, rule, SQLERRM;
        END IF;
        RAISE NOTICE 'ok    %  [%]', label, rule;
        RETURN;
    END;
    RAISE EXCEPTION 'FAIL  %: the database accepted it', label;
END $$;

CREATE FUNCTION pg_temp.expect_ok(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE stmt;
    RAISE NOTICE 'ok    %', label;
EXCEPTION WHEN OTHERS THEN
    RAISE EXCEPTION 'FAIL  %: %', label, SQLERRM;
END $$;

CREATE TEMP TABLE qa_variant AS SELECT id FROM product.variants ORDER BY id LIMIT 1;

SELECT pg_temp.expect_ok('a variant starts without a description of its own', format(
    $f$DO $d$ BEGIN IF (SELECT description FROM product.variants WHERE id = %L) IS NOT NULL THEN
        RAISE EXCEPTION 'has one'; END IF; END $d$$f$, (SELECT id FROM qa_variant)));
SELECT pg_temp.expect_ok('it takes HTML', format(
    $f$UPDATE product.variants SET description = '<p>Màu <strong>trắng</strong></p>' WHERE id = %L$f$,
    (SELECT id FROM qa_variant)));
SELECT pg_temp.expect_ok('the longest one there may be', format(
    $f$UPDATE product.variants SET description = repeat('x', 100000) WHERE id = %L$f$, (SELECT id FROM qa_variant)));
SELECT pg_temp.expect_ok('and back to none (the product''s)', format(
    $f$UPDATE product.variants SET description = NULL WHERE id = %L$f$, (SELECT id FROM qa_variant)));
SELECT pg_temp.expect_fail_by('an empty description is none, not ''''', 'ck_variants_description', format(
    $f$UPDATE product.variants SET description = '' WHERE id = %L$f$, (SELECT id FROM qa_variant)));
SELECT pg_temp.expect_fail_by('one character over the limit', 'ck_variants_description', format(
    $f$UPDATE product.variants SET description = repeat('x', 100001) WHERE id = %L$f$, (SELECT id FROM qa_variant)));

ROLLBACK;
