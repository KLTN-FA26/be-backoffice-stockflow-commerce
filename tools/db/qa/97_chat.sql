-- =============================================================================
-- Adversarial checks + happy paths for the sales chat foundation (V20261124000200; kltn-docs 16 §5, BR-01, BR-02,
-- BR-07; feature F-13): the eleven conversation states and every move between them (all 110, the 18 allowed ones
-- accepted and the 92 others refused), the context of a conversation always its customer's, internal notes only the
-- staff's, one message per client id, the append-only log, read markers that only move forward, attachments, cards,
-- canned replies and the grants. Each rule is attacked by the statement that breaks only it (expect_fail_by names the
-- rule). Needs the demo seed (its customer, products and the two demo accounts). Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
-- Refused by the named rule: a statement that breaks something else proves nothing.
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

CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;

-- Fixtures: the demo customer (A) with an order, another customer (B) with an order and a quote, the demo editor as
-- the agent and the demo approver as A's portal account. Conversation C1 of A, queued; C2 of B, assigned.
INSERT INTO customer.customer (id, full_name, email, status, version, created_at)
VALUES (md5('qa97:customer-b')::uuid, 'QA97 other customer', 'qa97-b@example.com', 'ACTIVE', 0, NOW());
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                     placed_at, version, created_at, hold_reason)
VALUES (md5('qa97:order-a')::uuid, 'SO-20991231-970001', 'c0000000-0000-4000-8000-000000000001',
        md5('qa97:order-a:request')::uuid, 'ON_HOLD', 1000, 'VND', NOW(), 0, NOW(), 'CREDIT'),
       (md5('qa97:order-b')::uuid, 'SO-20991231-970002', md5('qa97:customer-b')::uuid,
        md5('qa97:order-b:request')::uuid, 'ON_HOLD', 1000, 'VND', NOW(), 0, NOW(), 'CREDIT');
INSERT INTO ordering.quote (id, quote_number, request_id, customer_id, status, valid_until, sent_at, sent_by, version,
                            created_at)
VALUES (md5('qa97:quote-b')::uuid, 'QT-20991231-970001', md5('qa97:quote-b:request')::uuid,
        md5('qa97:customer-b')::uuid, 'SENT', NOW() + INTERVAL '7 days', NOW(), md5('demo:user:editor')::uuid, 0,
        NOW());
INSERT INTO chat.conversation (id, customer_id, subject, status, opened_by_type, opened_by, status_changed_at,
                               first_response_due_at, version, created_at)
VALUES (md5('qa97:c1')::uuid, 'c0000000-0000-4000-8000-000000000001', 'QA97 A', 'QUEUED', 'CUSTOMER',
        (SELECT id FROM identity.app_user WHERE username = 'demo.approver'), NOW(), NOW() + INTERVAL '30 minutes', 0,
        NOW());
INSERT INTO chat.conversation (id, customer_id, subject, status, opened_by_type, assigned_agent_id, assigned_at,
                               status_changed_at, version, created_at)
VALUES (md5('qa97:c2')::uuid, md5('qa97:customer-b')::uuid, 'QA97 B', 'ASSIGNED', 'AGENT',
        md5('demo:user:editor')::uuid, NOW(), NOW(), 0, NOW());
INSERT INTO chat.conversation_context (id, conversation_id, kind, order_id, is_primary, attached_at, version,
                                       created_at)
VALUES (md5('qa97:c1:order-a')::uuid, md5('qa97:c1')::uuid, 'ORDER', md5('qa97:order-a')::uuid, TRUE, NOW(), 0,
        NOW()),
       (md5('qa97:c2:order-b')::uuid, md5('qa97:c2')::uuid, 'ORDER', md5('qa97:order-b')::uuid, TRUE, NOW(), 0, NOW());
INSERT INTO chat.message (id, conversation_id, seq, kind, visibility, sender_type, sender_id, body, client_message_id,
                          sent_at, version, created_at)
VALUES (md5('qa97:c1:m1')::uuid, md5('qa97:c1')::uuid, 1, 'TEXT', 'CUSTOMER', 'CUSTOMER',
        (SELECT id FROM identity.app_user WHERE username = 'demo.approver'), 'Xin chao', 'web-1', NOW(), 0, NOW()),
       (md5('qa97:c1:m2')::uuid, md5('qa97:c1')::uuid, 2, 'INTERNAL_NOTE', 'STAFF', 'AGENT',
        md5('demo:user:editor')::uuid, 'Khach VIP', NULL, NOW(), 0, NOW());
UPDATE chat.conversation SET last_message_seq = 2, last_message_at = NOW() WHERE id = md5('qa97:c1')::uuid;

\echo '--- a conversation: its states and what goes with them (ck_conversation_*)'
-- With the lifecycle trigger off (rolled back with the refused statement): the trigger would refuse it first.
SELECT pg_temp.expect_fail_by('CH1 a status the conversation does not have', 'ck_conversation_status', $q$
    ALTER TABLE chat.conversation DISABLE TRIGGER tg_conversation_lifecycle;
    UPDATE chat.conversation SET status = 'OPEN' WHERE id = md5('qa97:c1')::uuid $q$);
SELECT pg_temp.expect_fail_by('CH1b the lifecycle names what it refuses', 'QUEUED -> OPEN is not a transition', $q$
    UPDATE chat.conversation SET status = 'OPEN' WHERE id = md5('qa97:c1')::uuid $q$);
SELECT pg_temp.expect_fail_by('CH2 a conversation is not born active', 'starts NEW, QUEUED or ASSIGNED', $q$
    INSERT INTO chat.conversation (id, customer_id, status, opened_by_type, assigned_agent_id, assigned_at,
                                   status_changed_at, version, created_at)
    VALUES (gen_random_uuid(), 'c0000000-0000-4000-8000-000000000001', 'ACTIVE', 'CUSTOMER',
            md5('demo:user:editor')::uuid, NOW(), NOW(), 0, NOW()) $q$);
SELECT pg_temp.expect_fail_by('CH3 an assigned conversation has an agent', 'ck_conversation_agent', $q$
    UPDATE chat.conversation SET assigned_agent_id = NULL, assigned_at = NULL WHERE id = md5('qa97:c2')::uuid $q$);
SELECT pg_temp.expect_fail_by('CH4 a queued conversation has no agent yet', 'ck_conversation_unassigned', $q$
    UPDATE chat.conversation SET assigned_agent_id = md5('demo:user:editor')::uuid, assigned_at = NOW()
     WHERE id = md5('qa97:c1')::uuid $q$);
SELECT pg_temp.expect_fail_by('CH5 an agent is assigned at a moment', 'ck_conversation_assigned_at', $q$
    UPDATE chat.conversation SET assigned_at = NULL WHERE id = md5('qa97:c2')::uuid $q$);
SELECT pg_temp.expect_fail_by('CH6 a CSAT score is 1 to 5', 'ck_conversation_csat', $q$
    UPDATE chat.conversation SET csat_score = 6, csat_submitted_at = NOW() WHERE id = md5('qa97:c2')::uuid $q$);
SELECT pg_temp.expect_fail_by('CH7 a CSAT comment comes with its score', 'ck_conversation_csat_submitted', $q$
    UPDATE chat.conversation SET csat_comment = 'Tot' WHERE id = md5('qa97:c2')::uuid $q$);
SELECT pg_temp.expect_fail_by('CH8 a conversation does not continue itself', 'ck_conversation_continued', $q$
    INSERT INTO chat.conversation (id, customer_id, status, opened_by_type, status_changed_at, continued_from_id,
                                   version, created_at)
    VALUES (md5('qa97:self')::uuid, 'c0000000-0000-4000-8000-000000000001', 'NEW', 'CUSTOMER', NOW(),
            md5('qa97:self')::uuid, 0, NOW()) $q$);
SELECT pg_temp.expect_fail_by('CH9 a conversation keeps its customer', 'keeps its customer', $q$
    UPDATE chat.conversation SET customer_id = md5('qa97:customer-b')::uuid WHERE id = md5('qa97:c1')::uuid $q$);
SELECT pg_temp.expect_fail_by('CH10 the message sequence does not go back', 'does not go back', $q$
    UPDATE chat.conversation SET last_message_seq = 1 WHERE id = md5('qa97:c1')::uuid $q$);
SELECT pg_temp.expect_fail_by('CH11 a conversation with no message has no last message time',
    'ck_conversation_last_message', $q$
    UPDATE chat.conversation SET last_message_at = NOW() WHERE id = md5('qa97:c2')::uuid $q$);

\echo '--- 16 §5: every move between the eleven states, accepted exactly when the lifecycle allows it'
-- A conversation of its own, put in each state with the trigger off (the columns each state needs, nothing more), then
-- moved with the trigger on. Every state but CLOSED keeps the dates the ended states need, so only the move is judged.
INSERT INTO chat.conversation (id, customer_id, status, opened_by_type, status_changed_at, version, created_at)
VALUES (md5('qa97:sweep')::uuid, 'c0000000-0000-4000-8000-000000000001', 'NEW', 'CUSTOMER', NOW(), 0, NOW());
CREATE FUNCTION pg_temp.place(state TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    UPDATE chat.conversation
       SET status = state,
           assigned_agent_id = CASE WHEN state IN ('NEW', 'QUEUED') THEN NULL ELSE md5('demo:user:editor')::uuid END,
           assigned_at = CASE WHEN state IN ('NEW', 'QUEUED') THEN NULL ELSE NOW() END,
           escalation_reason = 'Vuot han muc', resolved_at = NOW(), auto_closed_at = NOW(),
           reopen_until = NOW() + INTERVAL '7 days',
           closed_at = CASE WHEN state = 'CLOSED' THEN NOW() END
     WHERE id = md5('qa97:sweep')::uuid;
END $$;
DO $$
DECLARE
    states  TEXT[] := ARRAY['NEW', 'QUEUED', 'ASSIGNED', 'ACTIVE', 'WAITING_ON_CUSTOMER', 'WAITING_ON_INTERNAL',
                            'ESCALATED', 'RESOLVED', 'REOPENED', 'AUTO_CLOSED', 'CLOSED'];
    allowed TEXT[] := ARRAY['NEW>QUEUED', 'NEW>ASSIGNED', 'QUEUED>ASSIGNED', 'ASSIGNED>ACTIVE',
                            'ACTIVE>WAITING_ON_CUSTOMER', 'ACTIVE>WAITING_ON_INTERNAL', 'ACTIVE>ESCALATED',
                            'ACTIVE>RESOLVED', 'WAITING_ON_CUSTOMER>ACTIVE', 'WAITING_ON_CUSTOMER>AUTO_CLOSED',
                            'WAITING_ON_INTERNAL>ACTIVE', 'ESCALATED>ACTIVE', 'ESCALATED>RESOLVED',
                            'RESOLVED>REOPENED', 'RESOLVED>CLOSED', 'REOPENED>ACTIVE', 'AUTO_CLOSED>REOPENED',
                            'AUTO_CLOSED>CLOSED'];
    source  TEXT;
    target  TEXT;
    accepted INT := 0;
    refused  INT := 0;
BEGIN
    FOREACH source IN ARRAY states LOOP
        FOREACH target IN ARRAY states LOOP
            CONTINUE WHEN source = target;
            ALTER TABLE chat.conversation DISABLE TRIGGER tg_conversation_lifecycle;
            PERFORM pg_temp.place(source);
            ALTER TABLE chat.conversation ENABLE TRIGGER tg_conversation_lifecycle;
            BEGIN
                UPDATE chat.conversation
                   SET status = target,
                       assigned_agent_id = CASE WHEN target IN ('NEW', 'QUEUED') THEN NULL
                                                ELSE md5('demo:user:editor')::uuid END,
                       assigned_at = CASE WHEN target IN ('NEW', 'QUEUED') THEN NULL ELSE NOW() END,
                       closed_at = CASE WHEN target = 'CLOSED' THEN NOW() END
                 WHERE id = md5('qa97:sweep')::uuid;
                IF NOT (source || '>' || target) = ANY (allowed) THEN
                    RAISE EXCEPTION 'FAIL  CH12 % -> % was accepted; 16 §5 does not allow it', source, target;
                END IF;
                accepted := accepted + 1;
            EXCEPTION WHEN check_violation THEN
                IF (source || '>' || target) = ANY (allowed) THEN
                    RAISE EXCEPTION 'FAIL  CH12 % -> % was refused; 16 §5 allows it: %', source, target, SQLERRM;
                END IF;
                IF SQLERRM NOT LIKE '%is not a transition%' THEN
                    RAISE EXCEPTION 'FAIL  CH12 % -> % refused, but not by the lifecycle: %', source, target, SQLERRM;
                END IF;
                refused := refused + 1;
            END;
        END LOOP;
    END LOOP;
    IF accepted <> 18 OR refused <> 92 THEN
        RAISE EXCEPTION 'FAIL  CH12 % moves accepted and % refused, expected 18 and 92', accepted, refused;
    END IF;
    RAISE NOTICE 'ok    CH12 the 18 moves of 16 §5 are accepted, the 92 others refused';
END $$;
SELECT pg_temp.expect_fail_by('CH13 a closed conversation is handed to nobody', 'is not handed to anyone', $q$
    UPDATE chat.conversation SET assigned_agent_id = (SELECT id FROM identity.app_user WHERE username = 'demo.approver')
     WHERE id = md5('qa97:sweep')::uuid AND status = 'CLOSED' $q$);
-- The next two put the sweep's conversation straight in a state with the lifecycle trigger off (rolled back with the
-- refused statement), so only the CHECK is judged.
SELECT pg_temp.expect_fail_by('CH14 an escalation says why', 'ck_conversation_escalation', $q$
    ALTER TABLE chat.conversation DISABLE TRIGGER tg_conversation_lifecycle;
    UPDATE chat.conversation SET status = 'ESCALATED', escalation_reason = NULL, closed_at = NULL
     WHERE id = md5('qa97:sweep')::uuid $q$);
SELECT pg_temp.expect_fail_by('CH15 a resolved conversation has a reopen window', 'ck_conversation_reopen_window', $q$
    ALTER TABLE chat.conversation DISABLE TRIGGER tg_conversation_lifecycle;
    UPDATE chat.conversation SET status = 'RESOLVED', reopen_until = NULL, closed_at = NULL
     WHERE id = md5('qa97:sweep')::uuid $q$);
SELECT pg_temp.expect_true('CH16 the lifecycle trigger is on again after the two above', (
    SELECT tgenabled <> 'D' FROM pg_trigger WHERE tgname = 'tg_conversation_lifecycle'));

\echo '--- BR-02: what a conversation is about is its customer''s (ck_conversation_context_*, tg_conversation_context_owner)'
SELECT pg_temp.expect_fail_by('CX1 another customer''s order', '16 BR-02', $q$
    INSERT INTO chat.conversation_context (id, conversation_id, kind, order_id, attached_at, version, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c1')::uuid, 'ORDER', md5('qa97:order-b')::uuid, NOW(), 0, NOW()) $q$);
SELECT pg_temp.expect_fail_by('CX2 another customer''s quote', '16 BR-02', $q$
    INSERT INTO chat.conversation_context (id, conversation_id, kind, quote_id, attached_at, version, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c1')::uuid, 'QUOTE', md5('qa97:quote-b')::uuid, NOW(), 0, NOW()) $q$);
SELECT pg_temp.expect_fail_by('CX3 a catalogue variant is no customer''s own SKU', '16 BR-02', $q$
    INSERT INTO chat.conversation_context (id, conversation_id, kind, variant_id, attached_at, version, created_at)
    SELECT gen_random_uuid(), md5('qa97:c1')::uuid, 'CUSTOMER_SKU', v.id, NOW(), 0, NOW()
      FROM product.variants v WHERE v.sku = 'SOFA-3S-GREY' $q$);
SELECT pg_temp.expect_fail_by('CX4 a context names the reference of its kind', 'ck_conversation_context_reference', $q$
    INSERT INTO chat.conversation_context (id, conversation_id, kind, quote_id, attached_at, version, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c2')::uuid, 'ORDER', md5('qa97:quote-b')::uuid, NOW(), 0, NOW()) $q$);
SELECT pg_temp.expect_fail_by('CX5 a context names one document', 'ck_conversation_context_reference', $q$
    INSERT INTO chat.conversation_context (id, conversation_id, kind, order_id, quote_id, attached_at, version,
                                           created_at)
    VALUES (gen_random_uuid(), md5('qa97:c2')::uuid, 'ORDER', md5('qa97:order-b')::uuid, md5('qa97:quote-b')::uuid,
            NOW(), 0, NOW()) $q$);
SELECT pg_temp.expect_fail_by('CX6 a document once per conversation', 'uk_conversation_context_reference', $q$
    INSERT INTO chat.conversation_context (id, conversation_id, kind, order_id, attached_at, version, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c2')::uuid, 'ORDER', md5('qa97:order-b')::uuid, NOW(), 0, NOW()) $q$);
SELECT pg_temp.expect_fail_by('CX7 one primary context per conversation', 'uk_conversation_context_primary', $q$
    INSERT INTO chat.conversation_context (id, conversation_id, kind, quote_id, is_primary, attached_at, version,
                                           created_at)
    VALUES (gen_random_uuid(), md5('qa97:c2')::uuid, 'QUOTE', md5('qa97:quote-b')::uuid, TRUE, NOW(), 0, NOW()) $q$);
SELECT pg_temp.expect_fail_by('CX8 a context does not move to another document', 'for good', $q$
    UPDATE chat.conversation_context SET order_id = md5('qa97:order-b')::uuid WHERE id = md5('qa97:c1:order-a')::uuid $q$);
SELECT pg_temp.expect_fail_by('CX9 a kind the chat does not know', 'ck_conversation_context_kind', $q$
    INSERT INTO chat.conversation_context (id, conversation_id, kind, order_id, attached_at, version, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c2')::uuid, 'SHIPMENT', md5('qa97:order-b')::uuid, NOW(), 0, NOW()) $q$);
SELECT pg_temp.expect_ok('CX10 the customer''s own quote, and a catalogue product anyone may ask about', $q$
    INSERT INTO chat.conversation_context (id, conversation_id, kind, quote_id, attached_at, version, created_at)
    VALUES (md5('qa97:c2:quote-b')::uuid, md5('qa97:c2')::uuid, 'QUOTE', md5('qa97:quote-b')::uuid, NOW(), 0, NOW());
    INSERT INTO chat.conversation_context (id, conversation_id, kind, product_id, attached_at, version, created_at)
    SELECT gen_random_uuid(), md5('qa97:c1')::uuid, 'PRODUCT', p.id, NOW(), 0, NOW()
      FROM product.products p WHERE p.code = 'SOFA-3S' $q$);

\echo '--- BR-07: internal notes, and who writes what (ck_message_*)'
SELECT pg_temp.expect_fail_by('MV1 an internal note shown to the customer', 'ck_message_internal_note', $q$
    INSERT INTO chat.message (id, conversation_id, seq, kind, visibility, sender_type, sender_id, body, sent_at,
                              version, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c1')::uuid, 3, 'INTERNAL_NOTE', 'CUSTOMER', 'AGENT',
            md5('demo:user:editor')::uuid, 'lo ra', NOW(), 0, NOW()) $q$);
SELECT pg_temp.expect_fail_by('MV2 a text hidden from the customer', 'ck_message_internal_note', $q$
    INSERT INTO chat.message (id, conversation_id, seq, kind, visibility, sender_type, sender_id, body, sent_at,
                              version, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c1')::uuid, 3, 'TEXT', 'STAFF', 'AGENT', md5('demo:user:editor')::uuid,
            'an', NOW(), 0, NOW()) $q$);
SELECT pg_temp.expect_fail_by('MV3 a customer does not send a card', 'ck_message_customer', $q$
    INSERT INTO chat.message (id, conversation_id, seq, kind, visibility, sender_type, sender_id, card_kind,
                              card_payload, card_state, sent_at, version, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c1')::uuid, 3, 'CARD', 'CUSTOMER', 'CUSTOMER',
            (SELECT id FROM identity.app_user WHERE username = 'demo.approver'), 'QUOTE', '{}'::jsonb, 'OPEN', NOW(),
            0, NOW()) $q$);
SELECT pg_temp.expect_fail_by('MV4 a system line is the system''s', 'ck_message_system', $q$
    INSERT INTO chat.message (id, conversation_id, seq, kind, visibility, sender_type, sender_id, body, sent_at,
                              version, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c1')::uuid, 3, 'SYSTEM', 'CUSTOMER', 'AGENT', md5('demo:user:editor')::uuid,
            'He thong', NOW(), 0, NOW()) $q$);
SELECT pg_temp.expect_fail_by('MV5 a person''s message names who sent it', 'ck_message_sender', $q$
    INSERT INTO chat.message (id, conversation_id, seq, sender_type, body, sent_at, version, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c1')::uuid, 3, 'AGENT', 'ai do', NOW(), 0, NOW()) $q$);
SELECT pg_temp.expect_fail_by('MV6 a text says something', 'ck_message_body', $q$
    INSERT INTO chat.message (id, conversation_id, seq, sender_type, sender_id, body, sent_at, version, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c1')::uuid, 3, 'AGENT', md5('demo:user:editor')::uuid, '   ', NOW(), 0,
            NOW()) $q$);
SELECT pg_temp.expect_fail_by('MV7 a message about another conversation''s context', 'fk_message_context', $q$
    INSERT INTO chat.message (id, conversation_id, seq, sender_type, sender_id, body, context_id, sent_at, version,
                              created_at)
    VALUES (gen_random_uuid(), md5('qa97:c1')::uuid, 3, 'AGENT', md5('demo:user:editor')::uuid, 've don B',
            md5('qa97:c2:order-b')::uuid, NOW(), 0, NOW()) $q$);

\echo '--- one message per client id, one per place in the conversation (uk_message_*)'
SELECT pg_temp.expect_fail_by('MD1 the same client id again in the conversation (a retried send)',
    'uk_message_client_id', $q$
    INSERT INTO chat.message (id, conversation_id, seq, sender_type, sender_id, body, client_message_id, sent_at,
                              version, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c1')::uuid, 3, 'CUSTOMER',
            (SELECT id FROM identity.app_user WHERE username = 'demo.approver'), 'Xin chao', 'web-1', NOW(), 0,
            NOW()) $q$);
SELECT pg_temp.expect_fail_by('MD2 two messages in one place', 'uk_message_seq', $q$
    INSERT INTO chat.message (id, conversation_id, seq, sender_type, sender_id, body, sent_at, version, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c1')::uuid, 2, 'AGENT', md5('demo:user:editor')::uuid, 'chen', NOW(), 0,
            NOW()) $q$);
SELECT pg_temp.expect_fail_by('MD3 a client id the chat does not accept', 'ck_message_client_id', $q$
    INSERT INTO chat.message (id, conversation_id, seq, sender_type, sender_id, body, client_message_id, sent_at,
                              version, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c1')::uuid, 3, 'AGENT', md5('demo:user:editor')::uuid, 'x', 'a b', NOW(),
            0, NOW()) $q$);
SELECT pg_temp.expect_ok('MD4 the same client id in another conversation is another message', $q$
    INSERT INTO chat.message (id, conversation_id, seq, sender_type, sender_id, body, client_message_id, sent_at,
                              version, created_at)
    VALUES (md5('qa97:c2:m1')::uuid, md5('qa97:c2')::uuid, 1, 'AGENT', md5('demo:user:editor')::uuid, 'Chao anh',
            'web-1', NOW(), 0, NOW());
    UPDATE chat.conversation SET last_message_seq = 1, last_message_at = NOW() WHERE id = md5('qa97:c2')::uuid $q$);

\echo '--- cards (package 3), and a message stays what was said (ck_message_card*, tg_message_immutable)'
SELECT pg_temp.expect_fail_by('MC1 a card has its payload', 'ck_message_card', $q$
    INSERT INTO chat.message (id, conversation_id, seq, kind, sender_type, card_kind, card_state, sent_at, version,
                              created_at)
    VALUES (gen_random_uuid(), md5('qa97:c2')::uuid, 2, 'CARD', 'SYSTEM', 'QUOTE', 'OPEN', NOW(), 0, NOW()) $q$);
SELECT pg_temp.expect_fail_by('MC2 a card payload is an object', 'ck_message_card', $q$
    INSERT INTO chat.message (id, conversation_id, seq, kind, sender_type, card_kind, card_payload, card_state,
                              sent_at, version, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c2')::uuid, 2, 'CARD', 'SYSTEM', 'QUOTE', '[1]'::jsonb, 'OPEN', NOW(), 0,
            NOW()) $q$);
SELECT pg_temp.expect_fail_by('MC3 a card the chat does not know', 'ck_message_card_kind', $q$
    INSERT INTO chat.message (id, conversation_id, seq, kind, sender_type, card_kind, card_payload, card_state,
                              sent_at, version, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c2')::uuid, 2, 'CARD', 'SYSTEM', 'COUPON', '{}'::jsonb, 'OPEN', NOW(), 0,
            NOW()) $q$);
SELECT pg_temp.expect_ok('MC4 a quote card about the conversation''s quote', $q$
    INSERT INTO chat.message (id, conversation_id, seq, kind, sender_type, sender_id, card_kind, card_payload,
                              card_state, context_id, sent_at, version, created_at)
    VALUES (md5('qa97:c2:card')::uuid, md5('qa97:c2')::uuid, 2, 'CARD', 'AGENT', md5('demo:user:editor')::uuid,
            'QUOTE', '{"quoteNumber": "QT-20991231-970001"}'::jsonb, 'OPEN', md5('qa97:c2:quote-b')::uuid, NOW(), 0,
            NOW()) $q$);
SELECT pg_temp.expect_fail_by('MI1 a note does not become a text', 'does not change', $q$
    UPDATE chat.message SET kind = 'TEXT', visibility = 'CUSTOMER' WHERE id = md5('qa97:c1:m2')::uuid $q$);
SELECT pg_temp.expect_fail_by('MI2 a note is not shown to the customer afterwards', 'does not change', $q$
    UPDATE chat.message SET visibility = 'CUSTOMER' WHERE id = md5('qa97:c1:m2')::uuid $q$);
SELECT pg_temp.expect_fail_by('MI3 a text changes only by an edit that says when', 'only an edit', $q$
    UPDATE chat.message SET body = 'Xin chao!' WHERE id = md5('qa97:c1:m1')::uuid $q$);
SELECT pg_temp.expect_ok('MI4 an edit that says when', $q$
    UPDATE chat.message SET body = 'Xin chao!', edited_at = NOW() + INTERVAL '1 second'
     WHERE id = md5('qa97:c1:m1')::uuid $q$);
SELECT pg_temp.expect_ok('MI5 a card''s state moves with its action', $q$
    UPDATE chat.message SET card_state = 'ACTED', card_updated_at = NOW() WHERE id = md5('qa97:c2:card')::uuid $q$);
SELECT pg_temp.expect_ok('MI6 a message is deleted softly', $q$
    UPDATE chat.message SET deleted_at = NOW(), deleted_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa97:c2:m1')::uuid $q$);
SELECT pg_temp.expect_fail_by('MI7 a deleted message stays deleted', 'stays as it was deleted', $q$
    UPDATE chat.message SET deleted_at = NULL, deleted_by = NULL WHERE id = md5('qa97:c2:m1')::uuid $q$);

\echo '--- mentions and attachments (message_mention, message_attachment)'
SELECT pg_temp.expect_fail_by('MA1 only an internal note mentions anyone', 'fk_message_mention_message', $q$
    INSERT INTO chat.message_mention (id, message_id, user_id, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c1:m1')::uuid, md5('demo:user:editor')::uuid, NOW()) $q$);
SELECT pg_temp.expect_ok('MA2 a note mentions a colleague', $q$
    INSERT INTO chat.message_mention (id, message_id, user_id, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c1:m2')::uuid, md5('demo:user:editor')::uuid, NOW()) $q$);
INSERT INTO chat.message (id, conversation_id, seq, kind, sender_type, sender_id, sent_at, version, created_at)
VALUES (md5('qa97:c1:m3')::uuid, md5('qa97:c1')::uuid, 3, 'ATTACHMENT', 'CUSTOMER',
        (SELECT id FROM identity.app_user WHERE username = 'demo.approver'), NOW(), 0, NOW());
SELECT pg_temp.expect_fail_by('MA3 a file larger than 20 MB', 'ck_message_attachment_size', $q$
    INSERT INTO chat.message_attachment (id, message_id, file_key, content_type, size_bytes, sha256, uploaded_by,
                                         version, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c1:m3')::uuid, 'chat-attachments/qa97/a.pdf', 'application/pdf', 20971521,
            repeat('a', 64), md5('demo:user:editor')::uuid, 0, NOW()) $q$);
SELECT pg_temp.expect_fail_by('MA4 a file the chat does not take', 'ck_message_attachment_type', $q$
    INSERT INTO chat.message_attachment (id, message_id, file_key, content_type, size_bytes, sha256, uploaded_by,
                                         version, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c1:m3')::uuid, 'chat-attachments/qa97/a.html', 'text/html', 10,
            repeat('a', 64), md5('demo:user:editor')::uuid, 0, NOW()) $q$);
SELECT pg_temp.expect_fail_by('MA5 a file on a plain text', 'fk_message_attachment_message', $q$
    INSERT INTO chat.message_attachment (id, message_id, file_key, content_type, size_bytes, sha256, uploaded_by,
                                         version, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c1:m1')::uuid, 'chat-attachments/qa97/b.pdf', 'application/pdf', 10,
            repeat('a', 64), md5('demo:user:editor')::uuid, 0, NOW()) $q$);
SELECT pg_temp.expect_ok('MA6 a PDF on an attachment message', $q$
    INSERT INTO chat.message_attachment (id, message_id, file_key, content_type, size_bytes, sha256, uploaded_by,
                                         version, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c1:m3')::uuid, 'chat-attachments/qa97/c.pdf', 'application/pdf', 10,
            repeat('a', 64), (SELECT id FROM identity.app_user WHERE username = 'demo.approver'), 0, NOW());
    UPDATE chat.conversation SET last_message_seq = 3, last_message_at = NOW() WHERE id = md5('qa97:c1')::uuid $q$);

\echo '--- BR-01: the conversation log is append-only; read markers move forward'
INSERT INTO chat.conversation_event (id, conversation_id, type, actor_type, actor_id, occurred_at, context_id,
                                     version, created_at)
VALUES (md5('qa97:c1:e1')::uuid, md5('qa97:c1')::uuid, 'OPENED', 'CUSTOMER',
        (SELECT id FROM identity.app_user WHERE username = 'demo.approver'), NOW(), md5('qa97:c1:order-a')::uuid, 0,
        NOW());
SELECT pg_temp.expect_fail_by('EV1 the log is not rewritten', 'append-only', $q$
    UPDATE chat.conversation_event SET note = 'sua' WHERE id = md5('qa97:c1:e1')::uuid $q$);
SELECT pg_temp.expect_fail_by('EV2 the log is not erased', 'append-only', $q$
    DELETE FROM chat.conversation_event WHERE id = md5('qa97:c1:e1')::uuid $q$);
SELECT pg_temp.expect_fail_by('EV3 a person''s action names the person', 'ck_conversation_event_actor', $q$
    INSERT INTO chat.conversation_event (id, conversation_id, type, actor_type, occurred_at, version, created_at)
    VALUES (gen_random_uuid(), md5('qa97:c1')::uuid, 'CLAIMED', 'AGENT', NOW(), 0, NOW()) $q$);
SELECT pg_temp.expect_fail_by('EV4 a status change says from what to what', 'ck_conversation_event_status', $q$
    INSERT INTO chat.conversation_event (id, conversation_id, type, actor_type, occurred_at, to_status, version,
                                         created_at)
    VALUES (gen_random_uuid(), md5('qa97:c1')::uuid, 'STATUS_CHANGED', 'SYSTEM', NOW(), 'ACTIVE', 0, NOW()) $q$);
SELECT pg_temp.expect_fail_by('EV5 an action on another conversation''s context', 'fk_conversation_event_context', $q$
    INSERT INTO chat.conversation_event (id, conversation_id, type, actor_type, occurred_at, context_id, version,
                                         created_at)
    VALUES (gen_random_uuid(), md5('qa97:c1')::uuid, 'CONTEXT_ATTACHED', 'SYSTEM', NOW(), md5('qa97:c2:order-b')::uuid,
            0, NOW()) $q$);
INSERT INTO chat.participant_read (id, conversation_id, user_id, reader_type, last_read_seq, last_read_message_id,
                                   last_read_at, version, created_at)
VALUES (md5('qa97:c1:read')::uuid, md5('qa97:c1')::uuid, md5('demo:user:editor')::uuid, 'STAFF', 2,
        md5('qa97:c1:m2')::uuid, NOW(), 0, NOW());
SELECT pg_temp.expect_fail_by('RD1 a read marker does not go back', 'only moves forward', $q$
    UPDATE chat.participant_read SET last_read_seq = 1, last_read_message_id = md5('qa97:c1:m1')::uuid
     WHERE id = md5('qa97:c1:read')::uuid $q$);
SELECT pg_temp.expect_fail_by('RD2 a read marker names a message of its conversation', 'fk_participant_read_message', $q$
    UPDATE chat.participant_read SET last_read_seq = 5, last_read_message_id = md5('qa97:c2:card')::uuid
     WHERE id = md5('qa97:c1:read')::uuid $q$);

\echo '--- canned replies'
INSERT INTO chat.canned_response (id, owner_id, title, body, shortcut, version, created_at)
VALUES (md5('qa97:canned')::uuid, md5('demo:user:editor')::uuid, 'Chao', 'Chao {customerName}!', 'chao', 0, NOW());
SELECT pg_temp.expect_fail_by('CR1 one live shortcut per agent', 'uk_canned_response_shortcut', $q$
    INSERT INTO chat.canned_response (id, owner_id, title, body, shortcut, version, created_at)
    VALUES (gen_random_uuid(), md5('demo:user:editor')::uuid, 'Chao 2', 'Xin chao', 'chao', 0, NOW()) $q$);
SELECT pg_temp.expect_fail_by('CR2 a reply says something', 'ck_canned_response_text', $q$
    INSERT INTO chat.canned_response (id, title, body, version, created_at)
    VALUES (gen_random_uuid(), 'Rong', ' ', 0, NOW()) $q$);
SELECT pg_temp.expect_ok('CR3 the same shortcut shared with everyone is another reply', $q$
    INSERT INTO chat.canned_response (id, title, body, shortcut, version, created_at)
    VALUES (gen_random_uuid(), 'Chao chung', 'Xin chao {customerName}', 'chao', 0, NOW()) $q$);

\echo '--- grants (ADR-0008)'
SELECT pg_temp.expect_true('GR1 chat supervision is the Sales Manager''s and the administrator''s', (
    SELECT array_agg(DISTINCT r.code ORDER BY r.code) FROM identity.role_permission rp
      JOIN identity.app_role r ON r.id = rp.role_id
      JOIN identity.permission p ON p.id = rp.permission_id
     WHERE p.resource = 'chat-supervision') = ARRAY['SALES_MANAGER', 'SYSTEM_ADMIN']::varchar[]);
SELECT pg_temp.expect_true('GR2 the customer, Sales and the administrator chat', (
    SELECT array_agg(DISTINCT r.code ORDER BY r.code) FROM identity.role_permission rp
      JOIN identity.app_role r ON r.id = rp.role_id
      JOIN identity.permission p ON p.id = rp.permission_id
     WHERE p.code = 'chat-conversations:UPDATE')
    = ARRAY['CUSTOMER', 'SALES_MANAGER', 'SALES_STAFF', 'SYSTEM_ADMIN']::varchar[]);
SELECT pg_temp.expect_true('GR3 canned replies are for those who answer customers, not the customer', (
    SELECT array_agg(DISTINCT r.code ORDER BY r.code) FROM identity.role_permission rp
      JOIN identity.app_role r ON r.id = rp.role_id
      JOIN identity.permission p ON p.id = rp.permission_id
     WHERE p.resource = 'chat-canned-responses') = ARRAY['SALES_MANAGER', 'SALES_STAFF', 'SYSTEM_ADMIN']::varchar[]);

ROLLBACK;
