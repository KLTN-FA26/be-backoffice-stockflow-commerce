-- =============================================================================
-- Adversarial checks + happy paths: the Production Coordinator sends a production order without approval, and the
-- producer's acceptance takes an optional note (V20261213000100; design 2026-11-20 §2-§3; kltn-docs 17 §4.3 step 7,
-- §5.3, BR-23 rewritten). DRAFT -> SENT is an edge, nothing leads into PENDING_APPROVAL any more; sent_at and sent_by
-- are written together and once; acceptance_note is non-blank, only after the acceptance, and written once.
-- Each rule is attacked by the statement that breaks only it (expect_fail_like names the rule). Needs the demo seed:
-- the fixture is a second production order drawn up for the batch of demo order SO-20261031-910010, a copy of the one
-- that order already has. Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.expect_fail_like(label TEXT, fragment TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        SET CONSTRAINTS ALL IMMEDIATE;
        SET CONSTRAINTS ALL DEFERRED;
    EXCEPTION WHEN OTHERS THEN
        IF position(fragment IN SQLERRM) = 0 THEN
            RAISE EXCEPTION 'FAIL  %: refused, but for another reason: %', label, SQLERRM;
        END IF;
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

-- Fixtures: a DRAFT production order {po_code} for the batch of SO-20261031-910010, copied from the one it has.
CREATE FUNCTION pg_temp.draft(po_id UUID, po_code TEXT) RETURNS void LANGUAGE sql AS $$
    INSERT INTO ordering.production_order (id, code, sales_order_id, delivery_batch_id, customer_id, producer_id,
                                           receiving_warehouse_id, need_by_date, status, sample_approval_required,
                                           sample_required_by_default, sample_waiver_reason, drawn_up_by)
    SELECT po_id, po_code, p.sales_order_id, p.delivery_batch_id, p.customer_id, p.producer_id,
           p.receiving_warehouse_id, p.need_by_date, 'DRAFT', FALSE, p.sample_required_by_default,
           p.sample_waiver_reason, p.drawn_up_by
      FROM ordering.production_order p JOIN ordering.customer_order o ON o.id = p.sales_order_id
     WHERE o.order_number = 'SO-20261031-910010' AND p.status <> 'DRAFT';
    INSERT INTO ordering.production_order_line (id, production_order_id, order_line_id, sku, cartons, units_per_carton,
                                                design_snapshot_id, print_file_checksum)
    SELECT gen_random_uuid(), po_id, l.order_line_id, l.sku, 10, l.units_per_carton, l.design_snapshot_id,
           l.print_file_checksum
      FROM ordering.production_order_line l JOIN ordering.production_order p ON p.id = l.production_order_id
      JOIN ordering.customer_order o ON o.id = p.sales_order_id
     WHERE o.order_number = 'SO-20261031-910010' AND p.status <> 'DRAFT';
$$;
SELECT pg_temp.draft(md5('qa36:po1')::uuid, 'PRD-20261120-3601');
SELECT pg_temp.draft(md5('qa36:po2')::uuid, 'PRD-20261120-3602');
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

\echo '--- the state machine: DRAFT -> SENT, nothing into PENDING_APPROVAL (17 §5.3)'
SELECT pg_temp.expect_fail_like('PS1 a draft submitted for approval: the step is gone', 'is not a transition', $q$
    UPDATE ordering.production_order SET status = 'PENDING_APPROVAL', submitted_at = NOW()
     WHERE id = md5('qa36:po1')::uuid $q$);
SELECT pg_temp.expect_true('PS2 the 15 edges: DRAFT -> SENT in, every edge into or out of PENDING_APPROVAL out',
    (SELECT count(*) FROM ordering.production_order_status_transition) = 15
    AND EXISTS (SELECT 1 FROM ordering.production_order_status_transition
                 WHERE from_status = 'DRAFT' AND to_status = 'SENT')
    AND NOT EXISTS (SELECT 1 FROM ordering.production_order_status_transition
                     WHERE 'PENDING_APPROVAL' IN (from_status, to_status)));

\echo '--- sent by the coordinator: who and when, written together and once (BR-23 rewritten)'
SELECT pg_temp.expect_fail_like('PS3 sent with no sender', 'ck_production_order_sent', $q$
    UPDATE ordering.production_order SET status = 'SENT', sent_at = NOW() WHERE id = md5('qa36:po1')::uuid $q$);
SELECT pg_temp.expect_fail_like('PS4 a sender on a draft', 'ck_production_order_sent', $q$
    UPDATE ordering.production_order SET sent_by = md5('demo:user:editor')::uuid, sent_at = NOW()
     WHERE id = md5('qa36:po1')::uuid $q$);
SELECT pg_temp.expect_ok('PS5 sent by the one who drew it up, with no approval', $q$
    UPDATE ordering.production_order SET status = 'SENT', sent_by = drawn_up_by, sent_at = NOW()
     WHERE id = md5('qa36:po1')::uuid $q$);
SELECT pg_temp.expect_true('PS5a nothing approved it', (SELECT approved_by IS NULL AND approved_at IS NULL
    AND submitted_at IS NULL FROM ordering.production_order WHERE id = md5('qa36:po1')::uuid));
SELECT pg_temp.expect_fail_like('PS6 its sender rewritten', 'kept as written', $q$
    UPDATE ordering.production_order SET sent_by = md5('demo:user:approver')::uuid
     WHERE id = md5('qa36:po1')::uuid $q$);
SELECT pg_temp.expect_fail_like('PS6a the day it was sent rewritten', 'kept as written', $q$
    UPDATE ordering.production_order SET sent_at = sent_at - INTERVAL '1 hour' WHERE id = md5('qa36:po1')::uuid $q$);
SELECT pg_temp.expect_fail_like('PS6b an approval added to a production order sent without one', 'ck_production_order_approval', $q$
    UPDATE ordering.production_order SET approved_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa36:po1')::uuid $q$);
-- Approved by someone other than its drafter, so only the "once submitted" clause is broken.
SELECT pg_temp.expect_fail_like('PS6c a whole approval added to a production order never submitted', 'ck_production_order_approval', $q$
    UPDATE ordering.production_order po
       SET approved_by = (SELECT u.id FROM identity.app_user u WHERE u.id <> po.drawn_up_by ORDER BY u.id LIMIT 1),
           approved_at = NOW()
     WHERE po.id = md5('qa36:po1')::uuid $q$);
SELECT pg_temp.expect_ok('PS7 a draft cancelled: no sender, no sending', $q$
    UPDATE ordering.production_order SET status = 'CANCELLED', cancelled_at = NOW(),
           cancelled_by = md5('demo:user:editor')::uuid, cancellation_reason = 'drawn up twice'
     WHERE id = md5('qa36:po2')::uuid $q$);
SELECT pg_temp.expect_true('PS8 the orders sent before keep their approver as their sender', NOT EXISTS (
    SELECT 1 FROM ordering.production_order WHERE sent_at IS NOT NULL AND approved_at IS NOT NULL
                                              AND sent_by IS DISTINCT FROM approved_by));

\echo '--- the producer''s acceptance takes an optional note, written once (design 2026-11-20 §3)'
SELECT pg_temp.expect_fail_like('PS9 a note on a production order only sent', 'ck_production_order_acceptance_note', $q$
    UPDATE ordering.production_order SET acceptance_note = 'anh Hung xac nhan qua Zalo'
     WHERE id = md5('qa36:po1')::uuid $q$);
SELECT pg_temp.expect_fail_like('PS10 a blank note on the acceptance', 'ck_production_order_acceptance_note', $q$
    UPDATE ordering.production_order SET status = 'ACCEPTED', expected_completion_date = CURRENT_DATE + 20,
           acceptance_note = '  ' WHERE id = md5('qa36:po1')::uuid $q$);
SELECT pg_temp.expect_ok('PS11 accepted with a note', $q$
    UPDATE ordering.production_order SET status = 'ACCEPTED', expected_completion_date = CURRENT_DATE + 20,
           acceptance_note = 'anh Hung xac nhan qua Zalo' WHERE id = md5('qa36:po1')::uuid $q$);
SELECT pg_temp.expect_fail_like('PS12 its note rewritten', 'acceptance note', $q$
    UPDATE ordering.production_order SET acceptance_note = 'chi Lan xac nhan qua e-mail'
     WHERE id = md5('qa36:po1')::uuid $q$);
SELECT pg_temp.expect_fail_like('PS12a its note removed', 'acceptance note', $q$
    UPDATE ordering.production_order SET acceptance_note = NULL WHERE id = md5('qa36:po1')::uuid $q$);
SELECT pg_temp.expect_ok('PS13 cancelled after the acceptance, keeping its sender and its note', $q$
    UPDATE ordering.production_order SET status = 'CANCELLED', cancelled_at = NOW(),
           cancelled_by = md5('demo:user:editor')::uuid, cancellation_reason = 'the customer cancelled'
     WHERE id = md5('qa36:po1')::uuid $q$);

ROLLBACK;
