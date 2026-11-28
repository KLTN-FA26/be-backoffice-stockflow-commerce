-- =============================================================================
-- Adversarial checks: the chat follows the customer's Sales owner (V20270128000200; sales-owner plan BR-S06, BR-S08;
-- kltn-docs 16 §5, BR-14).
--   CQ  chat.conversation: the move back to the queue from every open state with an agent is accepted only flagged
--       (requeued_at); the flag lives only on a queued conversation (ck_conversation_requeued); an ended conversation
--       never goes back to the queue. tools/db/qa/97_chat.sql still sweeps the unflagged moves: 18 accepted.
--   CA  chat.sales_owner_change_applied: one row per (customer, change time); a real customer and a real account.
-- Needs the demo seed (its customer and the demo editor). Rolled back at the end.
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
    SET CONSTRAINTS ALL IMMEDIATE;
    SET CONSTRAINTS ALL DEFERRED;
    RAISE NOTICE 'ok    %', label;
EXCEPTION WHEN OTHERS THEN
    RAISE EXCEPTION 'FAIL  %: %', label, SQLERRM;
END $$;

-- Fixture: one conversation of the demo customer, put in each state with the lifecycle trigger off (the columns each
-- state needs), then moved with the trigger on.
INSERT INTO chat.conversation (id, customer_id, status, opened_by_type, status_changed_at, version, created_at)
VALUES (md5('qa99cs:c')::uuid, 'c0000000-0000-4000-8000-000000000001', 'NEW', 'CUSTOMER', NOW(), 0, NOW());
CREATE FUNCTION pg_temp.place(state TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    UPDATE chat.conversation
       SET status = state,
           assigned_agent_id = CASE WHEN state IN ('NEW', 'QUEUED') THEN NULL ELSE md5('demo:user:editor')::uuid END,
           assigned_at = CASE WHEN state IN ('NEW', 'QUEUED') THEN NULL ELSE NOW() END,
           escalation_reason = 'Vuot han muc', resolved_at = NOW(), auto_closed_at = NOW(),
           reopen_until = NOW() + INTERVAL '7 days', requeued_at = NULL,
           closed_at = CASE WHEN state = 'CLOSED' THEN NOW() END
     WHERE id = md5('qa99cs:c')::uuid;
END $$;

\echo '--- 16 §5 / BR-S08: back to the queue, flagged, from every open state with an agent; never from an ended one'
DO $$
DECLARE
    states TEXT[] := ARRAY['ASSIGNED', 'ACTIVE', 'WAITING_ON_CUSTOMER', 'WAITING_ON_INTERNAL', 'ESCALATED', 'REOPENED',
                           'RESOLVED', 'AUTO_CLOSED', 'CLOSED'];
    open   TEXT[] := ARRAY['ASSIGNED', 'ACTIVE', 'WAITING_ON_CUSTOMER', 'WAITING_ON_INTERNAL', 'ESCALATED',
                           'REOPENED'];
    source TEXT;
    accepted INT := 0;
BEGIN
    FOREACH source IN ARRAY states LOOP
        -- Unflagged: refused from everywhere (the move of an agent's own choosing does not exist).
        ALTER TABLE chat.conversation DISABLE TRIGGER tg_conversation_lifecycle;
        PERFORM pg_temp.place(source);
        ALTER TABLE chat.conversation ENABLE TRIGGER tg_conversation_lifecycle;
        BEGIN
            UPDATE chat.conversation SET status = 'QUEUED', assigned_agent_id = NULL, assigned_at = NULL,
                                         closed_at = NULL
             WHERE id = md5('qa99cs:c')::uuid;
            RAISE EXCEPTION 'FAIL  CQ1 % -> QUEUED unflagged was accepted', source;
        EXCEPTION WHEN check_violation THEN
            IF SQLERRM NOT LIKE '%is not a transition%' THEN
                RAISE EXCEPTION 'FAIL  CQ1 % -> QUEUED refused, but not by the lifecycle: %', source, SQLERRM;
            END IF;
        END;
        -- Flagged: accepted exactly from the open states.
        ALTER TABLE chat.conversation DISABLE TRIGGER tg_conversation_lifecycle;
        PERFORM pg_temp.place(source);
        ALTER TABLE chat.conversation ENABLE TRIGGER tg_conversation_lifecycle;
        BEGIN
            UPDATE chat.conversation SET status = 'QUEUED', assigned_agent_id = NULL, assigned_at = NULL,
                                         closed_at = NULL, requeued_at = NOW()
             WHERE id = md5('qa99cs:c')::uuid;
            IF NOT source = ANY (open) THEN
                RAISE EXCEPTION 'FAIL  CQ2 % -> QUEUED flagged was accepted; an ended conversation stays', source;
            END IF;
            accepted := accepted + 1;
        EXCEPTION WHEN check_violation THEN
            IF source = ANY (open) THEN
                RAISE EXCEPTION 'FAIL  CQ2 % -> QUEUED flagged was refused: %', source, SQLERRM;
            END IF;
            IF SQLERRM NOT LIKE '%is not a transition%' THEN
                RAISE EXCEPTION 'FAIL  CQ2 % -> QUEUED refused, but not by the lifecycle: %', source, SQLERRM;
            END IF;
        END;
    END LOOP;
    IF accepted <> 6 THEN
        RAISE EXCEPTION 'FAIL  CQ2 % flagged moves back to the queue accepted, expected 6', accepted;
    END IF;
    RAISE NOTICE 'ok    CQ1-CQ2 the six open states go back to the queue only flagged; the ended ones never';
END $$;

-- The flag and the agent, one rule at a time, from an active conversation.
ALTER TABLE chat.conversation DISABLE TRIGGER tg_conversation_lifecycle;
SELECT pg_temp.place('ACTIVE');
ALTER TABLE chat.conversation ENABLE TRIGGER tg_conversation_lifecycle;
SELECT pg_temp.expect_ok('CQ3 an active conversation goes back to the queue flagged', $q$
    UPDATE chat.conversation SET status = 'QUEUED', assigned_agent_id = NULL, assigned_at = NULL,
                                 requeued_at = NOW()
     WHERE id = md5('qa99cs:c')::uuid $q$);
SELECT pg_temp.expect_fail_by('CQ4 the flag goes when someone has the conversation', 'ck_conversation_requeued', $q$
    UPDATE chat.conversation SET status = 'ASSIGNED', assigned_agent_id = md5('demo:user:editor')::uuid,
                                 assigned_at = NOW()
     WHERE id = md5('qa99cs:c')::uuid $q$);
SELECT pg_temp.expect_ok('CQ5 taken from the queue, the flag cleared', $q$
    UPDATE chat.conversation SET status = 'ASSIGNED', assigned_agent_id = md5('demo:user:editor')::uuid,
                                 assigned_at = NOW(), requeued_at = NULL
     WHERE id = md5('qa99cs:c')::uuid $q$);
SELECT pg_temp.expect_fail_by('CQ6 only a queued conversation carries the flag', 'ck_conversation_requeued', $q$
    UPDATE chat.conversation SET requeued_at = NOW() WHERE id = md5('qa99cs:c')::uuid $q$);
SELECT pg_temp.expect_fail_by('CQ7 a requeued conversation has no agent', 'ck_conversation_unassigned', $q$
    UPDATE chat.conversation SET status = 'QUEUED', requeued_at = NOW() WHERE id = md5('qa99cs:c')::uuid $q$);

\echo '--- BR-S06: an owner change is applied once (chat.sales_owner_change_applied)'
SELECT pg_temp.expect_ok('CA1 a change applied', $q$
    INSERT INTO chat.sales_owner_change_applied (customer_id, changed_at, to_user_id, applied_at)
    VALUES ('c0000000-0000-4000-8000-000000000001', '2026-11-28 10:00:00+07', md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail_by('CA2 the same change twice', 'pk_sales_owner_change_applied', $q$
    INSERT INTO chat.sales_owner_change_applied (customer_id, changed_at, to_user_id, applied_at)
    VALUES ('c0000000-0000-4000-8000-000000000001', '2026-11-28 10:00:00+07', md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail_by('CA3 a customer that exists', 'fk_sales_owner_change_applied_customer', $q$
    INSERT INTO chat.sales_owner_change_applied (customer_id, changed_at, to_user_id, applied_at)
    VALUES (gen_random_uuid(), NOW(), md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_fail_by('CA4 an owner account that exists', 'fk_sales_owner_change_applied_to_user', $q$
    INSERT INTO chat.sales_owner_change_applied (customer_id, changed_at, to_user_id, applied_at)
    VALUES ('c0000000-0000-4000-8000-000000000001', NOW(), gen_random_uuid(), NOW()) $q$);

ROLLBACK;
