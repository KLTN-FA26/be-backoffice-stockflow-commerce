-- =============================================================================
-- Adversarial checks: the print shop (SCRUM-396..401, lane A) - who owns a stock hold, the IN_PRODUCTION
-- status, blanks issued, a write-off's layer, an agreed cancellation fee, and who does which workshop step
-- (V20261014000400 / 000420 / 000430), blanks at a subcontractor (000470). Needs the demo seed. Rolled back at
-- the end.
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

CREATE FUNCTION pg_temp.expect_true(label TEXT, condition BOOLEAN) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF condition THEN
        RAISE NOTICE 'ok    %', label;
    ELSE
        RAISE EXCEPTION 'FAIL  %', label;
    END IF;
END $$;

CREATE FUNCTION pg_temp.grants(role_code TEXT, permission_code TEXT) RETURNS BOOLEAN LANGUAGE sql AS $$
    SELECT EXISTS (SELECT 1 FROM identity.role_permission rp
                     JOIN identity.app_role r ON r.id = rp.role_id
                     JOIN identity.permission p ON p.id = rp.permission_id
                    WHERE r.code = role_code AND p.code = permission_code)
$$;

-- Fixtures: a paid order of the first customer, and a stock layer to hold.
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency,
                                     placed_at, version, created_at, paid_amount, paid_in_full_at)
SELECT md5('qa11:order')::uuid, 'SO-QA-11', c.id, gen_random_uuid(), 'CONFIRMED', 20000, 'VND', NOW(), 0, NOW(),
       20000, NOW() FROM customer.customer c ORDER BY c.id LIMIT 1;
INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, version, created_at, received_at)
VALUES (md5('qa11:layer')::uuid, 'SOFA-3S-GREY', 'HCM-PRD01', 5, 0, 'AVAILABLE', 0, NOW(), NOW() - interval '9 days');

\echo '--- who owns a hold (V20261014000400)'
SELECT pg_temp.expect_ok('WK1 an order hold, as before', $q$
    INSERT INTO inventory.stock_reservation (id, stock_item_id, order_id, root_request_id, request_id, quantity,
        reserved_at, expires_at, status)
    VALUES (md5('qa11:hold')::uuid, md5('qa11:layer')::uuid, md5('qa11:order')::uuid, gen_random_uuid(),
        gen_random_uuid(), 2, NOW(), NOW() + interval '30 minutes', 'HELD') $q$);
SELECT pg_temp.expect_fail('WK2 a hold with no owner', $q$
    INSERT INTO inventory.stock_reservation (id, stock_item_id, root_request_id, request_id, quantity, reserved_at,
        status) VALUES (gen_random_uuid(), md5('qa11:layer')::uuid, gen_random_uuid(), gen_random_uuid(), 1, NOW(),
        'HELD') $q$);
SELECT pg_temp.expect_fail('WK3 a hold with two owners', $q$
    UPDATE inventory.stock_reservation SET production_order_id = gen_random_uuid()
     WHERE id = md5('qa11:hold')::uuid $q$);
SELECT pg_temp.expect_fail('WK4 a production hold with a deadline', $q$
    INSERT INTO inventory.stock_reservation (id, stock_item_id, production_order_id, root_request_id, request_id,
        quantity, reserved_at, expires_at, status)
    VALUES (gen_random_uuid(), md5('qa11:layer')::uuid, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 1,
        NOW(), NOW() + interval '1 hour', 'HELD') $q$);
SELECT pg_temp.expect_fail('WK5 a production hold of a production order that does not exist', $q$
    INSERT INTO inventory.stock_reservation (id, stock_item_id, production_order_id, root_request_id, request_id,
        quantity, reserved_at, status)
    VALUES (gen_random_uuid(), md5('qa11:layer')::uuid, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 1,
        NOW(), 'HELD') $q$);

\echo '--- IN_PRODUCTION (V20261014000400)'
SELECT pg_temp.expect_ok('WK6 stock in the print shop', $q$
    UPDATE inventory.stock_item SET status = 'IN_PRODUCTION' WHERE id = md5('qa11:layer')::uuid $q$);
SELECT pg_temp.expect_fail('WK7 a status nobody knows', $q$
    UPDATE inventory.stock_item SET status = 'PRINTED' WHERE id = md5('qa11:layer')::uuid $q$);
SELECT pg_temp.expect_fail('WK8 a ledger line to a status nobody knows', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity,
        from_status, to_status, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'MOVE', 'SOFA-3S-GREY', 'HCM-A01-2-B', 'HCM-PRD01', 1, 'AVAILABLE', 'PRINTED',
        'PRODUCTION_ORDER', gen_random_uuid(), NOW()) $q$);

\echo '--- blanks issued (V20261014000400)'
SELECT pg_temp.expect_fail('WK9 an issue of no blanks', $q$
    INSERT INTO production.production_material_issue (id, production_order_id, request_id, reservation_id, sku,
        from_location, to_location, quantity, issued_at, issued_by)
    VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), md5('qa11:hold')::uuid, 'SOFA-3S-GREY',
        'HCM-A01-2-B', 'HCM-PRD01', 0, NOW(), gen_random_uuid()) $q$);
SELECT pg_temp.expect_fail('WK10 an issue that goes nowhere', $q$
    INSERT INTO production.production_material_issue (id, production_order_id, request_id, reservation_id, sku,
        from_location, to_location, quantity, issued_at, issued_by)
    VALUES (gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), md5('qa11:hold')::uuid, 'SOFA-3S-GREY',
        'HCM-PRD01', 'HCM-PRD01', 1, NOW(), gen_random_uuid()) $q$);
SELECT pg_temp.expect_fail('WK11 an issue for a production order that does not exist', $q$
    INSERT INTO production.production_material_issue (id, production_order_id, request_id, reservation_id, sku,
        from_location, to_location, quantity, issued_at, issued_by)
    SELECT gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), md5('qa11:hold')::uuid, 'SOFA-3S-GREY',
        'HCM-A01-2-B', 'HCM-PRD01', 1, NOW(), u.id FROM identity.app_user u LIMIT 1 $q$);

\echo '--- a write-off names its layer (V20261014000420)'
SELECT pg_temp.expect_true('WK12 stock_adjustment.received_at exists and may be empty',
    EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'inventory'
             AND table_name = 'stock_adjustment' AND column_name = 'received_at' AND is_nullable = 'YES'));

\echo '--- an agreed cancellation fee (V20261014000430)'
SELECT pg_temp.expect_fail('WK13 fee terms on an order that is not cancelled', $q$
    UPDATE ordering.customer_order SET cancellation_fee_reason = 'r', cancellation_fee_detail = 'd'
     WHERE id = md5('qa11:order')::uuid $q$);
SELECT pg_temp.expect_fail('WK14 a fee reason without its detail', $q$
    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason_code = 'CUSTOMER_REQUEST', cancellation_reason = 'CUSTOMER_REQUEST',
        cancellation_retained_amount = 5000, cancellation_fee_reason = 'r'
     WHERE id = md5('qa11:order')::uuid $q$);
SELECT pg_temp.expect_fail('WK15 an agreed amount without its terms', $q$
    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason_code = 'CUSTOMER_REQUEST', cancellation_reason = 'CUSTOMER_REQUEST',
        cancellation_retained_amount = 12000, cancellation_fee_amount = 12000
     WHERE id = md5('qa11:order')::uuid $q$);
SELECT pg_temp.expect_fail('WK16 kept is not what the money received covers of the agreed amount (Q6a/Q6b)', $q$
    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason_code = 'CUSTOMER_REQUEST', cancellation_reason = 'CUSTOMER_REQUEST',
        cancellation_retained_amount = 12000, cancellation_fee_amount = 30000, cancellation_fee_reason = 'r',
        cancellation_fee_detail = 'd' WHERE id = md5('qa11:order')::uuid $q$);
SELECT pg_temp.expect_ok('WK17 an agreed amount above the 20,000 paid keeps all of it; the rest is owed elsewhere', $q$
    UPDATE ordering.customer_order SET status = 'CANCELLED', cancellation_reason_code = 'CUSTOMER_REQUEST', cancellation_reason = 'CUSTOMER_REQUEST',
        cancellation_retained_amount = 20000, cancellation_fee_amount = 30000, cancellation_fee_reason = 'Printing had started',
        cancellation_fee_detail = 'Agreed with the buyer' WHERE id = md5('qa11:order')::uuid $q$);
SELECT pg_temp.expect_ok('WK18 a cancellation fee receivable', $q$
    INSERT INTO payment.receivable (id, order_id, customer_id, kind, amount, currency, issued_at, due_date)
    SELECT gen_random_uuid(), o.id, o.customer_id, 'CANCELLATION_FEE', 10000, 'VND', NOW(), CURRENT_DATE
      FROM ordering.customer_order o WHERE o.id = md5('qa11:order')::uuid $q$);

\echo '--- blanks at a subcontractor, per subcontractor and sending warehouse (V20261014000470, Q7/Q9)'
SELECT pg_temp.expect_fail('WK19 a SUBCONTRACTOR location owned by no site', $q$
    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable,
        is_putaway_target, status)
    SELECT md5('qa11:sub-orphan')::uuid, w.id, 'SUBCONTRACTOR', 'HCM-SUB-QAORPHAN', 'NORMAL', FALSE, FALSE, 'ACTIVE'
      FROM warehouse.warehouse w WHERE w.prefix = 'HCM' $q$);
SELECT pg_temp.expect_fail('WK20 a pickable subcontractor location', $q$
    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable,
        is_putaway_target, status)
    SELECT gen_random_uuid(), w.id, 'SUBCONTRACTOR', 'HCM-SUB-QAPICK', 'NORMAL', TRUE, FALSE, 'ACTIVE'
      FROM warehouse.warehouse w WHERE w.prefix = 'HCM' $q$);
SELECT pg_temp.expect_fail('WK21 a subcontractor location without the -SUB- code', $q$
    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable,
        is_putaway_target, status)
    SELECT gen_random_uuid(), w.id, 'SUBCONTRACTOR', 'HCM-QASUB', 'NORMAL', FALSE, FALSE, 'ACTIVE'
      FROM warehouse.warehouse w WHERE w.prefix = 'HCM' $q$);
SELECT pg_temp.expect_ok('WK22 a subcontractor site with its location', $q$
    WITH loc AS (
        INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable,
            is_putaway_target, status)
        SELECT md5('qa11:sub-loc')::uuid, w.id, 'SUBCONTRACTOR', 'HCM-SUB-QA11', 'NORMAL', FALSE, FALSE, 'ACTIVE'
          FROM warehouse.warehouse w WHERE w.prefix = 'HCM' RETURNING id, warehouse_id)
    INSERT INTO warehouse.subcontractor_site (id, warehouse_id, supplier_id, location_id)
    SELECT md5('qa11:sub-site')::uuid, loc.warehouse_id, s.id, loc.id FROM loc,
        (SELECT id FROM procurement.suppliers WHERE id NOT IN (SELECT supplier_id FROM warehouse.subcontractor_site)
          ORDER BY id LIMIT 1) s $q$);
SELECT pg_temp.expect_fail('WK23 a second site for the same subcontractor and warehouse', $q$
    INSERT INTO warehouse.subcontractor_site (id, warehouse_id, supplier_id, location_id)
    SELECT gen_random_uuid(), warehouse_id, supplier_id, location_id
      FROM warehouse.subcontractor_site WHERE id = md5('qa11:sub-site')::uuid $q$);
SELECT pg_temp.expect_fail('WK24 a site pointing at an area, not a SUBCONTRACTOR location', $q$
    INSERT INTO warehouse.subcontractor_site (id, warehouse_id, supplier_id, location_id)
    SELECT gen_random_uuid(), l.warehouse_id, s.id, l.id
      FROM warehouse.storage_location l,
           (SELECT id FROM procurement.suppliers WHERE id NOT IN (SELECT supplier_id FROM warehouse.subcontractor_site)
             ORDER BY id LIMIT 1) s
     WHERE l.location_code = 'HCM-PRD01' $q$);
SELECT pg_temp.expect_fail('WK25 a consumption that goes somewhere', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity,
        from_status, to_status, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'CONSUMPTION', 'SOFA-3S-GREY', 'HCM-SUB-QA11', 'HCM-PRD01', 1, 'IN_PRODUCTION',
        'IN_PRODUCTION', 'PRODUCTION_ORDER', gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_ok('WK26 blanks used by the subcontractor leave its location', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity,
        from_status, to_status, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'CONSUMPTION', 'SOFA-3S-GREY', 'HCM-SUB-QA11', NULL, 1, 'IN_PRODUCTION',
        'IN_PRODUCTION', 'PRODUCTION_ORDER', gen_random_uuid(), NOW()) $q$);
SELECT pg_temp.expect_true('WK27 spoilt blanks have their own adjustment reason, used = received, reconciled once',
    pg_get_constraintdef((SELECT oid FROM pg_constraint WHERE conname = 'ck_stock_adjustment_reason'))
        LIKE '%SUBCONTRACT_LOSS%'
    AND pg_get_constraintdef((SELECT oid FROM pg_constraint WHERE conname = 'ck_production_subcontract_receipt_used'))
        LIKE '%used_blanks = quantity%');

\echo '--- who does which workshop step (V20261014000400 / 000420)'
SELECT pg_temp.expect_true('GR5 the warehouse issues blanks; the print shop reads them',
    pg_temp.grants('WAREHOUSE_STAFF', 'production-material-issues:CREATE')
    AND pg_temp.grants('WAREHOUSE_MANAGER', 'production-material-issues:CREATE')
    AND pg_temp.grants('PRODUCTION_STAFF', 'production-material-issues:READ')
    AND NOT pg_temp.grants('PRODUCTION_STAFF', 'production-material-issues:CREATE')
    AND NOT pg_temp.grants('WAREHOUSE_STAFF', 'production-orders:UPDATE'));
SELECT pg_temp.expect_true('GR6 QC records QC and nothing else in the print shop',
    pg_temp.grants('QC_STAFF', 'production-qc-results:CREATE')
    AND NOT pg_temp.grants('QC_STAFF', 'production-orders:UPDATE')
    AND pg_temp.grants('QC_STAFF', 'production-orders:READ')
    AND NOT pg_temp.grants('PRODUCTION_STAFF', 'production-qc-results:CREATE')
    AND pg_temp.grants('WAREHOUSE_MANAGER', 'production-qc-results:CREATE'));

ROLLBACK;
