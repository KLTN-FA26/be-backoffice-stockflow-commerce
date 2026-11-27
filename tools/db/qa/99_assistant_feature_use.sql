-- =============================================================================
-- The assistant's feature-use counts and the rater's roles on a verdict (V20270120001300; contract §5): the checks of
-- assistant.feature_use and the new columns of assistant.answer_feedback, and who holds assistant-feedback and
-- assistant-usage. Needs any staff user (the demo seed has them). Rolled back at the end.
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
EXCEPTION WHEN OTHERS THEN
    RAISE EXCEPTION 'FAIL  %: %', label, SQLERRM;
END $$;

\echo '--- feature use: one row per feature, role and day, counts only'
SELECT pg_temp.expect_ok('FU1 a day''s chat questions of Sales', $q$
    INSERT INTO assistant.feature_use (feature_key, role, day, uses) VALUES ('chat', 'SALES_STAFF', DATE '2026-11-27', 3)
$q$);
SELECT pg_temp.expect_ok('FU2 a user holding two roles counts under both, joined', $q$
    INSERT INTO assistant.feature_use (feature_key, role, day, uses)
    VALUES ('error-explanation', 'SALES_STAFF+SALES_MANAGER', DATE '2026-11-27', 1) $q$);
SELECT pg_temp.expect_ok('FU3 a flush adds to the day''s row', $q$
    INSERT INTO assistant.feature_use (feature_key, role, day, uses) VALUES ('chat', 'SALES_STAFF', DATE '2026-11-27', 2)
    ON CONFLICT (day, feature_key, role) DO UPDATE SET uses = assistant.feature_use.uses + EXCLUDED.uses $q$);
DO $d$ BEGIN
    IF (SELECT uses FROM assistant.feature_use WHERE feature_key = 'chat' AND role = 'SALES_STAFF'
          AND day = DATE '2026-11-27') <> 5 THEN
        RAISE EXCEPTION 'FAIL  FU4 the second flush did not add to the first';
    END IF;
    RAISE NOTICE 'ok    FU4 3 + 2 = 5';
END $d$;
SELECT pg_temp.expect_fail('FU5 the same feature, role and day twice', $q$
    INSERT INTO assistant.feature_use (feature_key, role, day, uses) VALUES ('chat', 'SALES_STAFF', DATE '2026-11-27', 1)
$q$);
SELECT pg_temp.expect_fail('FU6 no use at all', $q$
    INSERT INTO assistant.feature_use (feature_key, role, day, uses) VALUES ('today', 'ACCOUNTANT', DATE '2026-11-27', 0)
$q$);
SELECT pg_temp.expect_fail('FU7 a negative count', $q$
    INSERT INTO assistant.feature_use (feature_key, role, day, uses) VALUES ('today', 'ACCOUNTANT', DATE '2026-11-27', -1)
$q$);
SELECT pg_temp.expect_fail('FU8 a feature key with capitals (what a client sent, not one of ours)', $q$
    INSERT INTO assistant.feature_use (feature_key, role, day, uses) VALUES ('Chat', 'ACCOUNTANT', DATE '2026-11-27', 1)
$q$);
SELECT pg_temp.expect_fail('FU9 a feature key with a space', $q$
    INSERT INTO assistant.feature_use (feature_key, role, day, uses) VALUES ('next steps', 'ACCOUNTANT', DATE '2026-11-27', 1)
$q$);
SELECT pg_temp.expect_fail('FU10 a feature key longer than the column', $q$
    INSERT INTO assistant.feature_use (feature_key, role, day, uses)
    VALUES (repeat('a', 41), 'ACCOUNTANT', DATE '2026-11-27', 1) $q$);
SELECT pg_temp.expect_fail('FU11 a role in lower case', $q$
    INSERT INTO assistant.feature_use (feature_key, role, day, uses) VALUES ('today', 'accountant', DATE '2026-11-27', 1)
$q$);
SELECT pg_temp.expect_fail('FU12 a user id where the role goes', $q$
    INSERT INTO assistant.feature_use (feature_key, role, day, uses)
    VALUES ('today', gen_random_uuid()::text, DATE '2026-11-27', 1) $q$);
SELECT pg_temp.expect_fail('FU13 two roles joined by a comma', $q$
    INSERT INTO assistant.feature_use (feature_key, role, day, uses)
    VALUES ('today', 'SALES_MANAGER,SALES_STAFF', DATE '2026-11-27', 1) $q$);
SELECT pg_temp.expect_fail('FU14 no day', $q$
    INSERT INTO assistant.feature_use (feature_key, role, day, uses) VALUES ('today', 'ACCOUNTANT', NULL, 1) $q$);

\echo '--- the rater''s roles on a verdict'
-- A conversation with one answer, owned by any staff user.
INSERT INTO assistant.conversation (id, owner_user_id, title, message_count, last_activity_at)
SELECT md5('qa:fu:conversation')::uuid, id, 'QA', 2, NOW() FROM identity.app_user ORDER BY id LIMIT 1;
INSERT INTO assistant.message (id, conversation_id, sequence, role, text, content)
VALUES (md5('qa:fu:question')::uuid, md5('qa:fu:conversation')::uuid, 0, 'USER', 'Tồn kho?', '{}'::jsonb);
INSERT INTO assistant.message (id, conversation_id, sequence, role, text, content, stop_reason, model)
VALUES (md5('qa:fu:answer')::uuid, md5('qa:fu:conversation')::uuid, 1, 'ASSISTANT', 'Còn 5 thùng.', '{}'::jsonb,
        'END_TURN', 'qa-model');

SELECT pg_temp.expect_ok('FR1 a verdict given before the rater was recorded keeps both NULL', $q$
    INSERT INTO assistant.answer_feedback (id, message_id, owner_user_id, verdict)
    SELECT md5('qa:fu:feedback')::uuid, md5('qa:fu:answer')::uuid, owner_user_id, 'DOWN'
      FROM assistant.conversation WHERE id = md5('qa:fu:conversation')::uuid $q$);
SELECT pg_temp.expect_ok('FR2 a warehouse manager of HCM', $q$
    UPDATE assistant.answer_feedback SET rater_roles = 'WAREHOUSE_MANAGER', rater_warehouses = 'HCM'
     WHERE id = md5('qa:fu:feedback')::uuid $q$);
SELECT pg_temp.expect_ok('FR3 two roles, two warehouses', $q$
    UPDATE assistant.answer_feedback SET rater_roles = 'WAREHOUSE_MANAGER+WAREHOUSE_STAFF', rater_warehouses = 'HCM,HN'
     WHERE id = md5('qa:fu:feedback')::uuid $q$);
SELECT pg_temp.expect_ok('FR4 a rater not limited by warehouse', $q$
    UPDATE assistant.answer_feedback SET rater_roles = 'SALES_STAFF', rater_warehouses = ''
     WHERE id = md5('qa:fu:feedback')::uuid $q$);
SELECT pg_temp.expect_fail('FR5 roles without warehouses', $q$
    UPDATE assistant.answer_feedback SET rater_roles = 'SALES_STAFF', rater_warehouses = NULL
     WHERE id = md5('qa:fu:feedback')::uuid $q$);
SELECT pg_temp.expect_fail('FR6 warehouses without roles', $q$
    UPDATE assistant.answer_feedback SET rater_roles = NULL, rater_warehouses = 'HCM'
     WHERE id = md5('qa:fu:feedback')::uuid $q$);
SELECT pg_temp.expect_fail('FR7 a role in lower case', $q$
    UPDATE assistant.answer_feedback SET rater_roles = 'sales_staff', rater_warehouses = ''
     WHERE id = md5('qa:fu:feedback')::uuid $q$);
SELECT pg_temp.expect_fail('FR8 a user name where the roles go', $q$
    UPDATE assistant.answer_feedback SET rater_roles = 'Nguyễn Văn A', rater_warehouses = ''
     WHERE id = md5('qa:fu:feedback')::uuid $q$);
SELECT pg_temp.expect_fail('FR9 warehouses with a space', $q$
    UPDATE assistant.answer_feedback SET rater_roles = 'SALES_STAFF', rater_warehouses = 'HCM, HN'
     WHERE id = md5('qa:fu:feedback')::uuid $q$);
SELECT pg_temp.expect_fail('FR10 a trailing comma', $q$
    UPDATE assistant.answer_feedback SET rater_roles = 'SALES_STAFF', rater_warehouses = 'HCM,'
     WHERE id = md5('qa:fu:feedback')::uuid $q$);

\echo '--- who reads verdicts and counts'
DO $d$ BEGIN
    IF (SELECT array_agg(DISTINCT r.code ORDER BY r.code) FROM identity.role_permission rp
          JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
         WHERE p.code = 'assistant-feedback:READ') <> ARRAY['SYSTEM_ADMIN']::varchar[] THEN
        RAISE EXCEPTION 'FAIL  FR11 other people''s questions are not the System Admin''s alone';
    END IF;
    IF (SELECT array_agg(DISTINCT r.code ORDER BY r.code) FROM identity.role_permission rp
          JOIN identity.app_role r ON r.id = rp.role_id JOIN identity.permission p ON p.id = rp.permission_id
         WHERE p.code = 'assistant-usage:READ') <> ARRAY['SALES_MANAGER', 'SYSTEM_ADMIN']::varchar[] THEN
        RAISE EXCEPTION 'FAIL  FR12 the usage counts are not the System Admin''s and the Sales Manager''s alone';
    END IF;
    IF EXISTS (SELECT 1 FROM identity.permission WHERE resource IN ('assistant-feedback', 'assistant-usage')
                  AND action <> 'READ') THEN
        RAISE EXCEPTION 'FAIL  FR13 the review resources grant more than reading';
    END IF;
    RAISE NOTICE 'ok    FR11-13 verdicts: System Admin; counts: System Admin and Sales Manager; READ only';
END $d$;

ROLLBACK;
\echo 'PASS 99_assistant_feature_use'
