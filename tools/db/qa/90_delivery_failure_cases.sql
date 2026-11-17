-- =============================================================================
-- Adversarial checks + happy paths: delivery failure cases (V20261121000300, WF-05 fix plan sections 2-3; kltn-docs 09
-- §4.3, 03 §4.2, 17 §4.8, 15 §4.6): a shipment the carrier brought back holds its order, its return receipt opens a
-- case, and Sales deliver again or cancel what was not delivered. Needs the demo seed (HCM, HCM-QC01, demo.editor and
-- demo.approver). Rolled back at the end.
-- =============================================================================
\set ON_ERROR_STOP on
BEGIN;
CREATE FUNCTION pg_temp.expect_fail(label TEXT, stmt TEXT) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE stmt;
        -- Deferred constraints and constraint triggers fire here, still inside the sub-transaction.
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

-- A case opened for a shipment on a receipt, as a statement.
CREATE FUNCTION pg_temp.open_case(case_key TEXT, shipment_key TEXT, receipt_key TEXT, reason TEXT,
                                  status TEXT DEFAULT 'OPEN', order_key TEXT DEFAULT 'qa90:o') RETURNS TEXT
    LANGUAGE sql AS $$
    SELECT format($f$
        INSERT INTO ordering.delivery_failure_case (id, order_id, shipment_id, receipt_id, receipt_number, warehouse_id,
                                                    failure_reason, status, opened_at)
        VALUES (md5(%L)::uuid, md5(%L)::uuid, md5(%L)::uuid, md5(%L)::uuid, %L, md5('demo:wh:HCM')::uuid, %L, %L, NOW())$f$,
        case_key, order_key, shipment_key, receipt_key, upper(replace(receipt_key, ':', '-')), reason, status)
$$;

-- Fixtures, written with triggers and foreign keys off (how an order gets shipments and a receipt its cartons is not
-- this file's subject): order O with one line of 8 sofas and a second line; its shipments S1 (came back, REFUSED), S2
-- (came back, LOST), S3 (DELIVERED) and S4 (came back, its receipt still a DRAFT), each carrying 2 of the first line;
-- closed return receipts R1 (of S1) and R2 (of S2), the draft R4 (of S4); another order O2 with a returned shipment
-- and its closed receipt.
SET session_replication_role = replica;
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at,
                                     created_at, warehouse_id, shipped_at, delivered_at)
VALUES (md5('qa90:o')::uuid, 'SO-QA90-O1', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'SHIPPED', 1000,
        'VND', NOW(), NOW(), md5('demo:wh:HCM')::uuid, NOW(), NULL),
       (md5('qa90:o2')::uuid, 'SO-QA90-O2', 'c0000000-0000-4000-8000-000000000001', gen_random_uuid(), 'DELIVERED', 1000,
        'VND', NOW(), NOW(), md5('demo:wh:HCM')::uuid, NOW(), NOW());
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
VALUES (md5('qa90:o:l1')::uuid, md5('qa90:o')::uuid, 'SOFA-3S-GREY', 8, 100, 'VND'),
       (md5('qa90:o:l2')::uuid, md5('qa90:o')::uuid, 'TABLE-OAK-160', 2, 100, 'VND'),
       (md5('qa90:o2:l1')::uuid, md5('qa90:o2')::uuid, 'SOFA-3S-GREY', 2, 100, 'VND');
INSERT INTO ordering.order_shipment (id, order_id, status, amount, handed_over_at, outcome_at, failure_reason)
VALUES (md5('qa90:s1')::uuid, md5('qa90:o')::uuid, 'RETURNED', 200, NOW(), NOW(), 'REFUSED'),
       (md5('qa90:s2')::uuid, md5('qa90:o')::uuid, 'RETURNED', 200, NOW(), NOW(), 'LOST'),
       (md5('qa90:s3')::uuid, md5('qa90:o')::uuid, 'DELIVERED', 200, NOW(), NOW(), NULL),
       (md5('qa90:s4')::uuid, md5('qa90:o')::uuid, 'RETURNED', 200, NOW(), NOW(), 'WRONG_ADDRESS'),
       (md5('qa90:o2:s1')::uuid, md5('qa90:o2')::uuid, 'RETURNED', 200, NOW(), NOW(), 'REFUSED');
INSERT INTO ordering.order_shipment_line (shipment_id, order_line_id, quantity)
VALUES (md5('qa90:s1')::uuid, md5('qa90:o:l1')::uuid, 2), (md5('qa90:s2')::uuid, md5('qa90:o:l1')::uuid, 2),
       (md5('qa90:s3')::uuid, md5('qa90:o:l1')::uuid, 2), (md5('qa90:s4')::uuid, md5('qa90:o:l1')::uuid, 2),
       (md5('qa90:o2:s1')::uuid, md5('qa90:o2:l1')::uuid, 2);
INSERT INTO fulfillment.shipment (id, order_id, status, code, warehouse_id, ready_at, created_at, handed_over_at,
                                  outcome_at, whole_order_handover)
SELECT s.id, s.order_id, s.status, 'SO-QA90-' || left(s.id::text, 8), md5('demo:wh:HCM')::uuid, NOW(), NOW(), NOW(),
       NOW(), TRUE
  FROM ordering.order_shipment s WHERE s.order_id IN (md5('qa90:o')::uuid, md5('qa90:o2')::uuid);
INSERT INTO procurement.goods_receipts (id, receipt_number, warehouse_id, status, received_at, received_by, confirmed_at,
                                        confirmed_by, closed_at, receipt_type, customer_order_id, return_shipment_id,
                                        quarantine_location_id)
SELECT md5(r.key)::uuid, upper(replace(r.key, ':', '-')), md5('demo:wh:HCM')::uuid, r.status, NOW(),
       md5('demo:user:editor')::uuid, r.at, CASE WHEN r.at IS NULL THEN NULL ELSE md5('demo:user:editor')::uuid END, r.at,
       'RETURN', md5(r.order_key)::uuid, md5(r.shipment_key)::uuid,
       (SELECT id FROM warehouse.storage_location WHERE location_code = 'HCM-QC01')
  FROM (VALUES ('qa90:r1', 'CLOSED', NOW(), 'qa90:o', 'qa90:s1'), ('qa90:r2', 'CLOSED', NOW(), 'qa90:o', 'qa90:s2'),
               ('qa90:r4', 'DRAFT', NULL, 'qa90:o', 'qa90:s4'), ('qa90:o2:r1', 'CLOSED', NOW(), 'qa90:o2', 'qa90:o2:s1'))
       AS r(key, status, at, order_key, shipment_key);
INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at)
VALUES (md5('qa90:p0')::uuid, md5('qa90:o')::uuid, 'SO-QA90-O1', md5('demo:wh:HCM')::uuid, 'PICKED', NOW());
SET session_replication_role = DEFAULT;

\echo '--- a case is opened OPEN, for a returned shipment of its order, on that shipment''s closed return receipt'
SELECT pg_temp.expect_fail('DF1 a case for a shipment that was delivered',
    pg_temp.open_case('qa90:cx1', 'qa90:s3', 'qa90:r1', 'REFUSED'));
SELECT pg_temp.expect_fail('DF2 a case on the receipt of another shipment',
    pg_temp.open_case('qa90:cx2', 'qa90:s1', 'qa90:r2', 'REFUSED'));
SELECT pg_temp.expect_fail('DF3 a case on a receipt still a draft: the goods are not back',
    pg_temp.open_case('qa90:cx3', 'qa90:s4', 'qa90:r4', 'WRONG_ADDRESS'));
SELECT pg_temp.expect_fail('DF4 a case of another order''s shipment',
    pg_temp.open_case('qa90:cx4', 'qa90:o2:s1', 'qa90:o2:r1', 'REFUSED'));
SELECT pg_temp.expect_fail('DF5 a case saying another reason than its shipment failed for',
    pg_temp.open_case('qa90:cx5', 'qa90:s1', 'qa90:r1', 'LOST'));
SELECT pg_temp.expect_fail('DF6 a case opened already decided',
    pg_temp.open_case('qa90:cx6', 'qa90:s1', 'qa90:r1', 'REFUSED', 'CANCELLED'));
SELECT pg_temp.expect_ok('DF7 the case of the refused shipment, and of the lost one',
    pg_temp.open_case('qa90:c1', 'qa90:s1', 'qa90:r1', 'REFUSED') || ';'
    || pg_temp.open_case('qa90:c2', 'qa90:s2', 'qa90:r2', 'LOST'));
SELECT pg_temp.expect_fail('DF8 a second case for the same shipment',
    pg_temp.open_case('qa90:cx8', 'qa90:s1', 'qa90:r1', 'REFUSED'));
SELECT pg_temp.expect_ok('DF9 another order''s shipment has its own case',
    pg_temp.open_case('qa90:o2:c1', 'qa90:o2:s1', 'qa90:o2:r1', 'REFUSED', 'OPEN', 'qa90:o2'));

\echo '--- what a case is about never changes; it is kept'
SELECT pg_temp.expect_fail('DF10 its shipment changed', $q$
    UPDATE ordering.delivery_failure_case SET shipment_id = md5('qa90:s4')::uuid WHERE id = md5('qa90:c1')::uuid $q$);
SELECT pg_temp.expect_fail('DF11 its failure reason changed', $q$
    UPDATE ordering.delivery_failure_case SET failure_reason = 'LOST' WHERE id = md5('qa90:c1')::uuid $q$);
SELECT pg_temp.expect_fail('DF12 a case deleted', $q$
    DELETE FROM ordering.delivery_failure_case WHERE id = md5('qa90:c1')::uuid $q$);

\echo '--- the cartons that came back are a copy written once'
SELECT pg_temp.expect_ok('DF13 the cartons of the case', $q$
    INSERT INTO ordering.delivery_failure_case_carton (case_id, lpn, sku, lot_number, quantity, condition)
    VALUES (md5('qa90:c1')::uuid, 'QA90-LPN-1', 'SOFA-3S-GREY', 'LOT-1', 2, 'dented') $q$);
SELECT pg_temp.expect_fail('DF14 a carton''s condition rewritten', $q$
    UPDATE ordering.delivery_failure_case_carton SET condition = NULL WHERE case_id = md5('qa90:c1')::uuid $q$);
SELECT pg_temp.expect_fail('DF15 a carton of nothing', $q$
    INSERT INTO ordering.delivery_failure_case_carton (case_id, lpn, sku, quantity)
    VALUES (md5('qa90:c1')::uuid, 'QA90-LPN-2', 'SOFA-3S-GREY', 0) $q$);

\echo '--- deliver again: the customer pays the trip they made fail, and only that trip'
SELECT pg_temp.expect_fail('DF16 OPEN straight to REDELIVERED', $q$
    UPDATE ordering.delivery_failure_case SET status = 'REDELIVERED', decided_by = md5('demo:user:editor')::uuid,
           decided_at = NOW(), decision_note = 'x', carrier_claim = TRUE, released_at = NOW(),
           released_by = md5('demo:user:approver')::uuid, redelivery_shipment_id = md5('qa90:s3')::uuid,
           redelivered_at = NOW(), closed_at = NOW() WHERE id = md5('qa90:c2')::uuid $q$);
SELECT pg_temp.expect_fail('DF17 decided without saying who, when or why', $q$
    UPDATE ordering.delivery_failure_case SET status = 'REDELIVERY', carrier_claim = TRUE
     WHERE id = md5('qa90:c2')::uuid $q$);
SELECT pg_temp.expect_fail('DF18 a refused delivery delivered again without charging the trip', $q$
    UPDATE ordering.delivery_failure_case SET status = 'REDELIVERY', decided_by = md5('demo:user:editor')::uuid,
           decided_at = NOW(), decision_note = 'giao lai' WHERE id = md5('qa90:c1')::uuid $q$);
SELECT pg_temp.expect_fail('DF19 a refused delivery marked for a claim against the carrier', $q$
    UPDATE ordering.delivery_failure_case SET status = 'REDELIVERY', decided_by = md5('demo:user:editor')::uuid,
           decided_at = NOW(), decision_note = 'giao lai', carrier_claim = TRUE WHERE id = md5('qa90:c1')::uuid $q$);
SELECT pg_temp.expect_fail('DF20 a fee with no fee item owing it', $q$
    UPDATE ordering.delivery_failure_case SET status = 'REDELIVERY', decided_by = md5('demo:user:editor')::uuid,
           decided_at = NOW(), decision_note = 'giao lai', failed_trip_fee = 850000
     WHERE id = md5('qa90:c1')::uuid $q$);
SELECT pg_temp.expect_fail('DF21 a lost shipment delivered again without the claim against the carrier', $q$
    UPDATE ordering.delivery_failure_case SET status = 'REDELIVERY', decided_by = md5('demo:user:editor')::uuid,
           decided_at = NOW(), decision_note = 'giao lai' WHERE id = md5('qa90:c2')::uuid $q$);
SELECT pg_temp.expect_ok('DF22 the lost shipment delivered again at the carrier''s cost', $q$
    UPDATE ordering.delivery_failure_case SET status = 'REDELIVERY', decided_by = md5('demo:user:editor')::uuid,
           decided_at = NOW(), decision_note = 'Hang lam mat; giao lai', carrier_claim = TRUE
     WHERE id = md5('qa90:c2')::uuid $q$);
SELECT pg_temp.expect_fail('DF23 the decision rewritten', $q$
    UPDATE ordering.delivery_failure_case SET decided_by = md5('demo:user:approver')::uuid
     WHERE id = md5('qa90:c2')::uuid $q$);

\echo '--- released, picked again on a task of its own, handed over'
SELECT pg_temp.expect_fail('DF24 a pick for a redelivery not released yet', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at, redelivery_case_id)
    VALUES (gen_random_uuid(), md5('qa90:o')::uuid, 'SO-QA90-O1', md5('demo:wh:HCM')::uuid, 'PENDING', NOW(),
            md5('qa90:c2')::uuid) $q$);
SELECT pg_temp.expect_fail('DF25 handed over again before it was released', $q$
    UPDATE ordering.delivery_failure_case SET status = 'REDELIVERED', redelivery_shipment_id = md5('qa90:s3')::uuid,
           redelivered_at = NOW(), closed_at = NOW() WHERE id = md5('qa90:c2')::uuid $q$);
SELECT pg_temp.expect_fail('DF26 released by nobody', $q$
    UPDATE ordering.delivery_failure_case SET released_at = NOW() WHERE id = md5('qa90:c2')::uuid $q$);
SELECT pg_temp.expect_fail('DF27 an undecided case released', $q$
    UPDATE ordering.delivery_failure_case SET released_at = NOW(), released_by = md5('demo:user:approver')::uuid
     WHERE id = md5('qa90:c1')::uuid $q$);
SELECT pg_temp.expect_ok('DF28 the redelivery released to the warehouse', $q$
    UPDATE ordering.delivery_failure_case SET released_at = NOW(), released_by = md5('demo:user:approver')::uuid
     WHERE id = md5('qa90:c2')::uuid $q$);
SELECT pg_temp.expect_fail('DF29 the release rewritten', $q$
    UPDATE ordering.delivery_failure_case SET released_at = NOW() + interval '1 hour' WHERE id = md5('qa90:c2')::uuid $q$);
SELECT pg_temp.expect_fail('DF30 a pick of another order naming the case', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at, redelivery_case_id)
    VALUES (gen_random_uuid(), md5('qa90:o2')::uuid, 'SO-QA90-O2', md5('demo:wh:HCM')::uuid, 'PENDING', NOW(),
            md5('qa90:c2')::uuid) $q$);
SELECT pg_temp.expect_ok('DF31 a second pick of the order, for the redelivery', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at, redelivery_case_id)
    VALUES (md5('qa90:p1')::uuid, md5('qa90:o')::uuid, 'SO-QA90-O1', md5('demo:wh:HCM')::uuid, 'PENDING', NOW(),
            md5('qa90:c2')::uuid) $q$);
SELECT pg_temp.expect_fail('DF32 a third pick of the same redelivery', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at, redelivery_case_id)
    VALUES (gen_random_uuid(), md5('qa90:o')::uuid, 'SO-QA90-O1', md5('demo:wh:HCM')::uuid, 'PENDING', NOW(),
            md5('qa90:c2')::uuid) $q$);
SELECT pg_temp.expect_fail('DF33 a second first pick of an order without a schedule', $q$
    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, created_at)
    VALUES (gen_random_uuid(), md5('qa90:o')::uuid, 'SO-QA90-O1', md5('demo:wh:HCM')::uuid, 'PENDING', NOW()) $q$);
SELECT pg_temp.expect_fail('DF34 the redelivery is the shipment that came back', $q$
    UPDATE ordering.delivery_failure_case SET status = 'REDELIVERED', redelivery_shipment_id = shipment_id,
           redelivered_at = NOW(), closed_at = NOW() WHERE id = md5('qa90:c2')::uuid $q$);
SELECT pg_temp.expect_fail('DF35 the redelivery is another order''s shipment', $q$
    UPDATE ordering.delivery_failure_case SET status = 'REDELIVERED', redelivery_shipment_id = md5('qa90:o2:s1')::uuid,
           redelivered_at = NOW(), closed_at = NOW() WHERE id = md5('qa90:c2')::uuid $q$);
SELECT pg_temp.expect_ok('DF36 handed over again on a new shipment of the order: REDELIVERED', $q$
    UPDATE ordering.delivery_failure_case SET status = 'REDELIVERED', redelivery_shipment_id = md5('qa90:s3')::uuid,
           redelivered_at = NOW(), closed_at = NOW() WHERE id = md5('qa90:c2')::uuid $q$);
SELECT pg_temp.expect_fail('DF37 a case delivered again decided once more', $q$
    UPDATE ordering.delivery_failure_case SET status = 'CANCELLED', closed_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa90:c2')::uuid $q$);

\echo '--- cancel what was not delivered'
SELECT pg_temp.expect_fail('DF38 cancelled by nobody', $q$
    UPDATE ordering.delivery_failure_case SET status = 'CANCELLED', decided_by = md5('demo:user:editor')::uuid,
           decided_at = NOW(), decision_note = 'Khach khong nhan lai', closed_at = NOW()
     WHERE id = md5('qa90:c1')::uuid $q$);
SELECT pg_temp.expect_fail('DF39 cancelled and still open', $q$
    UPDATE ordering.delivery_failure_case SET status = 'CANCELLED', decided_by = md5('demo:user:editor')::uuid,
           decided_at = NOW(), decision_note = 'Khach khong nhan lai', closed_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa90:c1')::uuid $q$);
SELECT pg_temp.expect_ok('DF40 the refused shipment''s goods cancelled, nothing charged for a redelivery never made', $q$
    UPDATE ordering.delivery_failure_case SET status = 'CANCELLED', decided_by = md5('demo:user:editor')::uuid,
           decided_at = NOW(), decision_note = 'Khach khong nhan lai', closed_at = NOW(),
           closed_by = md5('demo:user:editor')::uuid WHERE id = md5('qa90:c1')::uuid $q$);
SELECT pg_temp.expect_fail('DF41 a cancelled case opened again', $q$
    UPDATE ordering.delivery_failure_case SET status = 'OPEN', decided_by = NULL, decided_at = NULL,
           decision_note = NULL, closed_at = NULL, closed_by = NULL WHERE id = md5('qa90:c1')::uuid $q$);

\echo '--- a hold of a case stands for a line its returned shipment carried'
SELECT pg_temp.expect_fail('DF42 a hold for a line the shipment did not carry', $q$
    INSERT INTO ordering.delivery_failure_case_hold (reservation_id, case_id, order_line_id)
    VALUES (gen_random_uuid(), md5('qa90:o2:c1')::uuid, md5('qa90:o:l2')::uuid) $q$);
SELECT pg_temp.expect_fail('DF43 a hold that is no reservation', $q$
    INSERT INTO ordering.delivery_failure_case_hold (reservation_id, case_id, order_line_id)
    VALUES (gen_random_uuid(), md5('qa90:o2:c1')::uuid, md5('qa90:o2:l1')::uuid) $q$);

\echo '--- the order is held for the failed delivery, from PARTIALLY_FULFILLED'
SELECT pg_temp.expect_fail('DF44 held for a failed delivery from a delivered order (no such edge)', $q$
    UPDATE ordering.customer_order SET status = 'ON_HOLD', hold_reason = 'DELIVERY_FAILED',
           status_before_hold = 'PARTIALLY_FULFILLED' WHERE id = md5('qa90:o2')::uuid $q$);
SELECT pg_temp.expect_fail('DF45 held for a failed delivery, going back to PICKING', $q$
    UPDATE ordering.customer_order SET status = 'ON_HOLD', hold_reason = 'DELIVERY_FAILED',
           status_before_hold = 'PICKING' WHERE id = md5('qa90:o')::uuid $q$);
SELECT pg_temp.expect_fail('DF46 a hold reason nobody knows', $q$
    UPDATE ordering.customer_order SET status = 'ON_HOLD', hold_reason = 'CARRIER', status_before_hold = 'PARTIALLY_FULFILLED'
     WHERE id = md5('qa90:o')::uuid $q$);
SELECT pg_temp.expect_ok('DF47 SHIPPED -> ON_HOLD for DELIVERY_FAILED, its hold on record', $q$
    UPDATE ordering.customer_order SET status = 'ON_HOLD', hold_reason = 'DELIVERY_FAILED',
           status_before_hold = 'PARTIALLY_FULFILLED', hold_note = 'REFUSED' WHERE id = md5('qa90:o')::uuid;
    INSERT INTO ordering.order_hold (id, order_id, reason, raised_at, created_at)
    VALUES (gen_random_uuid(), md5('qa90:o')::uuid, 'DELIVERY_FAILED', NOW(), NOW()) $q$);
SELECT pg_temp.expect_fail('DF48 the hold left for SHIPPED, not where it was held from', $q$
    UPDATE ordering.customer_order SET status = 'SHIPPED', hold_reason = NULL, status_before_hold = NULL,
           hold_note = NULL WHERE id = md5('qa90:o')::uuid $q$);
SELECT pg_temp.expect_ok('DF49 decided: back to PARTIALLY_FULFILLED', $q$
    UPDATE ordering.customer_order SET status = 'PARTIALLY_FULFILLED', hold_reason = NULL, status_before_hold = NULL,
           hold_note = NULL WHERE id = md5('qa90:o')::uuid $q$);
SELECT pg_temp.expect_true('DF50 the two new edges are in the transition table, and the cancellation reason is known',
    (SELECT count(*) FROM ordering.order_status_transition
      WHERE to_status = 'ON_HOLD' AND from_status IN ('SHIPPED', 'DELIVERY_FAILED')) = 2
    AND (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_order_cancellation_code_values')
        LIKE '%DELIVERY_FAILED%');

\echo '--- the fee item of a failed trip is a fee item: no plan row, numbered after the plan, due at once'
SET session_replication_role = replica;   -- the CHECKs alone: a signature's installments are 18_installments.sql's
SELECT pg_temp.expect_fail('DF51 a fee item numbered inside the plan', $q$
    INSERT INTO payment.installment (id, contract_id, order_id, customer_id, contract_number, order_number, seq, name,
                                     milestone, amount, currency, due_days, reached_at, due_date, status)
    VALUES (gen_random_uuid(), gen_random_uuid(), md5('qa90:o')::uuid, 'c0000000-0000-4000-8000-000000000001',
            'HD-QA90-1', 'SO-QA90-O1', 5, 'Failed delivery', 'FAILED_TRIP_FEE', 850000, 'VND', 0, NOW(),
            CURRENT_DATE, 'DUE') $q$);
SELECT pg_temp.expect_fail('DF52 a fee item naming a plan row', $q$
    INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, customer_id, contract_number,
                                     order_number, seq, name, milestone, amount, currency, due_days, reached_at,
                                     due_date, status)
    VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), md5('qa90:o')::uuid,
            'c0000000-0000-4000-8000-000000000001', 'HD-QA90-1', 'SO-QA90-O1', 21, 'Failed delivery',
            'FAILED_TRIP_FEE', 850000, 'VND', 0, NOW(), CURRENT_DATE, 'DUE') $q$);
SELECT pg_temp.expect_fail('DF53 a fee item not due yet', $q$
    INSERT INTO payment.installment (id, contract_id, order_id, customer_id, contract_number, order_number, seq, name,
                                     milestone, amount, currency, due_days, status)
    VALUES (gen_random_uuid(), gen_random_uuid(), md5('qa90:o')::uuid, 'c0000000-0000-4000-8000-000000000001',
            'HD-QA90-1', 'SO-QA90-O1', 21, 'Failed delivery', 'FAILED_TRIP_FEE', 850000, 'VND', 0, 'NOT_DUE') $q$);
SELECT pg_temp.expect_fail('DF54 a plan installment naming no plan row', $q$
    INSERT INTO payment.installment (id, contract_id, order_id, customer_id, contract_number, order_number, seq, name,
                                     milestone, amount, currency, due_days, status)
    VALUES (gen_random_uuid(), gen_random_uuid(), md5('qa90:o')::uuid, 'c0000000-0000-4000-8000-000000000001',
            'HD-QA90-1', 'SO-QA90-O1', 1, 'Deposit', 'ON_SIGNING', 850000, 'VND', 0, 'NOT_DUE') $q$);
SELECT pg_temp.expect_ok('DF55 the fee item of a failed trip', $q$
    INSERT INTO payment.installment (id, contract_id, order_id, customer_id, contract_number, order_number, seq, name,
                                     milestone, amount, currency, due_days, reached_at, due_date, status)
    VALUES (md5('qa90:fee')::uuid, gen_random_uuid(), md5('qa90:o')::uuid, 'c0000000-0000-4000-8000-000000000001',
            'HD-QA90-1', 'SO-QA90-O1', 21, 'Failed delivery', 'FAILED_TRIP_FEE', 850000, 'VND', 0, NOW(),
            CURRENT_DATE, 'DUE') $q$);
SET session_replication_role = DEFAULT;
SELECT pg_temp.expect_fail('DF56 a fee item cancelled: the trip failed whatever became of the order', $q$
    UPDATE payment.installment SET status = 'CANCELLED', cancelled_at = NOW() WHERE id = md5('qa90:fee')::uuid $q$);
SELECT pg_temp.expect_fail('DF57 the fee of a case that is not the fee item''s amount', $q$
    UPDATE ordering.delivery_failure_case SET status = 'REDELIVERY', decided_by = md5('demo:user:editor')::uuid,
           decided_at = NOW(), decision_note = 'giao lai', failed_trip_fee = 850000.5,
           fee_installment_id = md5('qa90:fee')::uuid WHERE id = md5('qa90:o2:c1')::uuid $q$);
SELECT pg_temp.expect_ok('DF58 a refused delivery delivered again, its trip charged on the fee item', $q$
    UPDATE ordering.delivery_failure_case SET status = 'REDELIVERY', decided_by = md5('demo:user:editor')::uuid,
           decided_at = NOW(), decision_note = 'giao lai', failed_trip_fee = 850000,
           fee_installment_id = md5('qa90:fee')::uuid WHERE id = md5('qa90:o2:c1')::uuid $q$);
SELECT pg_temp.expect_fail('DF59 the fee of the failed trip changed afterwards', $q$
    UPDATE ordering.delivery_failure_case SET failed_trip_fee = 100000 WHERE id = md5('qa90:o2:c1')::uuid $q$);
SELECT pg_temp.expect_ok('DF60 cancelled after all: the fee of the trip that failed stays', $q$
    UPDATE ordering.delivery_failure_case SET status = 'CANCELLED', closed_at = NOW(),
           closed_by = md5('demo:user:editor')::uuid WHERE id = md5('qa90:o2:c1')::uuid $q$);

\echo '--- what the migration put in place'
SELECT pg_temp.expect_true('DF61 the tables, their triggers and the pick''s unique key',
    (SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal AND tgname IN ('tg_delivery_failure_case',
        'tg_delivery_failure_case_carton_append_only', 'tg_delivery_failure_case_hold', 'tg_pick_redelivery_case',
        'tg_installment_delivery_failure_fee')) = 5
    AND (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'uk_fulfillment_pick_order_batch')
        LIKE '%redelivery_case_id%');
SELECT pg_temp.expect_true('DF62 a settlement counts no fee item''s money as collected, of either kind',
    (SELECT pg_get_functiondef('payment.check_order_settlement'::regproc)) LIKE '%FAILED_TRIP_FEE%'
    AND (SELECT pg_get_functiondef('payment.check_payment_receipt_void'::regproc)) LIKE '%FAILED_TRIP_FEE%'
    AND (SELECT pg_get_functiondef('payment.check_installment_reduction'::regproc)) LIKE '%FAILED_TRIP_FEE%');

ROLLBACK;
\echo 'PASS 90_delivery_failure_cases'
