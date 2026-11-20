-- =============================================================================
-- A print configuration's print version (V20261211000100, decision 1). Needs the demo seed; rolled back.
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

CREATE FUNCTION pg_temp.expect_ok(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE stmt;
    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;
    RAISE NOTICE 'ok    %', label;
END $$;

CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

-- PV1: the demo template was copied, so the files of its designs still match it as they did.
SELECT pg_temp.expect_true('PV1 the demo template''s print version is its version',
    (SELECT print_version = version FROM product.customization_templates
      WHERE id = md5('demo:wf02:template:LY-GIAY-DEMO-12OZ')::uuid));
SELECT pg_temp.expect_true('PV2 every rendered file of a demo design still names its template''s print version',
    NOT EXISTS (SELECT 1 FROM design.design_artifact a
                  JOIN design.design_draft d ON d.id = a.draft_id
                  JOIN product.customization_templates t ON t.id = d.template_id
                 WHERE a.template_version IS NOT NULL AND a.template_version > t.print_version));
SELECT pg_temp.expect_fail('PV3 a negative print version', $q$
    UPDATE product.customization_templates SET print_version = -1
     WHERE id = md5('demo:wf02:template:LY-GIAY-DEMO-12OZ')::uuid $q$);
SELECT pg_temp.expect_fail('PV4 no print version at all', $q$
    UPDATE product.customization_templates SET print_version = NULL
     WHERE id = md5('demo:wf02:template:LY-GIAY-DEMO-12OZ')::uuid $q$);
SELECT pg_temp.expect_ok('PV5 renaming moves the lock version, not the print version', $q$
    UPDATE product.customization_templates SET name = 'QA', version = version + 1
     WHERE id = md5('demo:wf02:template:LY-GIAY-DEMO-12OZ')::uuid $q$);
SELECT pg_temp.expect_true('PV6 ... so the two now differ, and the files still match',
    (SELECT print_version = version - 1 FROM product.customization_templates
      WHERE id = md5('demo:wf02:template:LY-GIAY-DEMO-12OZ')::uuid));

ROLLBACK;
\echo 'PASS 99_template_print_version'
