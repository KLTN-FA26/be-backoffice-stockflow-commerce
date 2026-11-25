-- =============================================================================
-- A project quote request's close reason (V20261228000100, kltn-docs 12 BR-25.3): only on a request a salesperson
-- closed, never blank. Builds its own project and request on a demo customer; rolled back.
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

-- A project of a demo customer, owned by one of its users, and one open request on it.
CREATE TEMP TABLE qa_who ON COMMIT DROP AS
SELECT c.id AS customer_id, u.id AS user_id
  FROM customer.customer c
  JOIN identity.app_user u ON u.id = c.user_id
 ORDER BY c.id
 LIMIT 1;

SELECT pg_temp.expect_true('CR0 a demo customer with a user to own the project', (SELECT count(*) = 1 FROM qa_who));

INSERT INTO design.design_project (id, customer_id, owner_user_id, name, status, version, created_at)
SELECT md5('qa:close-reason:project')::uuid, customer_id, user_id, 'QA close reason', 'ACTIVE', 0, NOW() FROM qa_who;
INSERT INTO design.design_project_quote_request (id, project_id, requested_by, status, version, created_at)
SELECT md5('qa:close-reason:request')::uuid, md5('qa:close-reason:project')::uuid, user_id, 'OPEN', 0, NOW()
  FROM qa_who;

SELECT pg_temp.expect_fail('CR1 a reason on an open request', $q$
    UPDATE design.design_project_quote_request SET close_reason = 'treo'
     WHERE id = md5('qa:close-reason:request')::uuid $q$);
SELECT pg_temp.expect_fail('CR2 a reason on a request closed by nobody (the customer, or the system)', $q$
    UPDATE design.design_project_quote_request SET status = 'CLOSED', closed_at = NOW(), close_reason = 'treo'
     WHERE id = md5('qa:close-reason:request')::uuid $q$);
SELECT pg_temp.expect_fail('CR3 a blank reason', $q$
    UPDATE design.design_project_quote_request
       SET status = 'CLOSED', closed_at = NOW(), closed_by = (SELECT user_id FROM qa_who), close_reason = '   '
     WHERE id = md5('qa:close-reason:request')::uuid $q$);
SELECT pg_temp.expect_fail('CR4 a reason over 1000 characters', $q$
    UPDATE design.design_project_quote_request
       SET status = 'CLOSED', closed_at = NOW(), closed_by = (SELECT user_id FROM qa_who), close_reason = repeat('x', 1001)
     WHERE id = md5('qa:close-reason:request')::uuid $q$);
SELECT pg_temp.expect_ok('CR5 a salesperson closes it with a reason', $q$
    UPDATE design.design_project_quote_request
       SET status = 'CLOSED', closed_at = NOW(), closed_by = (SELECT user_id FROM qa_who),
           close_reason = 'Khách không sửa bản bị trả lại'
     WHERE id = md5('qa:close-reason:request')::uuid $q$);
SELECT pg_temp.expect_fail('CR6 reopened, it keeps no reason', $q$
    UPDATE design.design_project_quote_request SET status = 'OPEN', closed_at = NULL, closed_by = NULL
     WHERE id = md5('qa:close-reason:request')::uuid $q$);
SELECT pg_temp.expect_ok('CR7 a request closed with no reason (a quote answered it) is as before', $q$
    INSERT INTO design.design_project_quote_request (id, project_id, requested_by, status, closed_at, closed_by,
                                                     version, created_at)
    SELECT md5('qa:close-reason:request-2')::uuid, md5('qa:close-reason:project')::uuid, user_id, 'CLOSED', NOW(),
           user_id, 0, NOW() FROM qa_who $q$);
SELECT pg_temp.expect_true('CR8 no seeded request carries a reason it may not',
    NOT EXISTS (SELECT 1 FROM design.design_project_quote_request
                 WHERE close_reason IS NOT NULL AND (status <> 'CLOSED' OR closed_by IS NULL)));

ROLLBACK;
\echo 'PASS 99_design_project_quote_close_reason'
