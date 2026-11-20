-- =============================================================================
-- Adversarial checks + happy paths: the receiving-flow leftovers removed (plan 2026-11-20-receiving-redundancy-removal).
-- Needs the demo seed (the HCM warehouse and its RECEIVING area HCM-RCV01). Rolled back at the end. Each section below
-- is one task of the plan; a later task appends its own section, with the same helpers, before the final ROLLBACK.
--
--   A2  purchase receipts and the CONFIRMED status (V20261213000100): a receipt is PRODUCTION or RETURN, a line always
--       names a production order line, and no receipt is stored CONFIRMED.
--
-- The fixture follows 61_purchasing_removal.sql: a production order written with the row triggers off (its rules are
-- 21_production_orders.sql's), a DRAFT receipt against it, registered with it.
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

-- Refused, and for the reason named: the message says {fragment}. A refusal for any other reason (a typo, a missing
-- column) is not the rule under test.
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
EXCEPTION WHEN OTHERS THEN
    RAISE EXCEPTION 'FAIL  %: %', label, SQLERRM;
END $$;

CREATE FUNCTION pg_temp.expect_true(label TEXT, cond TEXT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    result BOOLEAN;
BEGIN
    EXECUTE 'SELECT (' || cond || ')' INTO result;
    IF result IS NOT TRUE THEN
        RAISE EXCEPTION 'FAIL  %: % is not true', label, cond;
    END IF;
    RAISE NOTICE 'ok    %', label;
END $$;


-- ============================================================================= A2
\echo '--- A2 fixture: a SENT production order of 5 cartons of 10 blue cups at HCM, a DRAFT receipt against it'
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, length_cm, width_cm, height_cm, weight_kg)
VALUES (md5('qa96:carton')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'), 'CARTON10', 10, 60, 40, 50, 12);
SET LOCAL session_replication_role = replica;
INSERT INTO ordering.production_order (id, code, sales_order_id, delivery_batch_id, customer_id, producer_id,
                                       receiving_warehouse_id, need_by_date, status, sample_approval_required,
                                       sample_required_by_default, drawn_up_by, submitted_at, approved_by, approved_at, sent_at)
VALUES (md5('qa96:prod')::uuid, 'PRD-20261109-9601', md5('qa96:so')::uuid, md5('qa96:batch')::uuid,
        md5('qa96:cust')::uuid, md5('qa96:producer')::uuid, md5('demo:wh:HCM')::uuid, CURRENT_DATE + 30, 'SENT', FALSE,
        FALSE, md5('demo:user:editor')::uuid, NOW(), md5('demo:user:approver')::uuid, NOW(), NOW());
INSERT INTO ordering.production_order_line (id, production_order_id, order_line_id, sku, cartons, units_per_carton,
                                            design_snapshot_id, print_file_checksum)
VALUES (md5('qa96:prod:line')::uuid, md5('qa96:prod')::uuid, md5('qa96:ol')::uuid, 'LY-GIAY-DEMO-12OZ-XANH', 5, 10,
        md5('qa96:snap')::uuid, repeat('d', 64));
SET LOCAL session_replication_role = origin;
INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, production_order_id, warehouse_id, status,
                                        received_at, received_by)
VALUES (md5('qa96:gr')::uuid, 'GR-QA96', 'PRODUCTION', md5('qa96:prod')::uuid, md5('demo:wh:HCM')::uuid, 'DRAFT', NOW(),
        md5('demo:user:editor')::uuid);
INSERT INTO ordering.production_order_receipt (production_order_id, goods_receipt_id, receipt_number, status, opened_at)
VALUES (md5('qa96:prod')::uuid, md5('qa96:gr')::uuid, 'GR-QA96', 'DRAFT', NOW());
SET CONSTRAINTS ALL IMMEDIATE;
SET CONSTRAINTS ALL DEFERRED;

\echo '--- A2 no purchase receipt, no CONFIRMED status, no line without a production order line'
SELECT pg_temp.expect_fail_like('A2.1 a PURCHASE receipt', 'ck_goods_receipts_type', $q$
    SET LOCAL session_replication_role = replica;
    INSERT INTO procurement.goods_receipts (id, receipt_number, receipt_type, warehouse_id, status, received_at, received_by)
    VALUES (gen_random_uuid(), 'GR-QA96-P', 'PURCHASE', md5('demo:wh:HCM')::uuid, 'DRAFT', NOW(), md5('demo:user:editor')::uuid) $q$);
-- Written with the row triggers off: the row trigger of a line (a line names a production order line exactly when its
-- receipt is against a production order) answers first with triggers on, so the column is what is under test here.
SELECT pg_temp.expect_fail_like('A2.2 a receipt line with no production order line', 'production_order_line_id', $q$
    SET LOCAL session_replication_role = replica;
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, inventory_item_id, location_id, received_qty, lot_number,
                                                 uom_conversion_id)
    VALUES (gen_random_uuid(), md5('qa96:gr')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'),
            md5('demo:loc:HCM-RCV01')::uuid, 10, 'PRD-20261109-9601', md5('qa96:carton')::uuid) $q$);
SELECT pg_temp.expect_fail_like('A2.2b ... and with the triggers on', 'names a production order line', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, inventory_item_id, location_id, received_qty, lot_number,
                                                 uom_conversion_id)
    VALUES (gen_random_uuid(), md5('qa96:gr')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'),
            md5('demo:loc:HCM-RCV01')::uuid, 10, 'PRD-20261109-9601', md5('qa96:carton')::uuid) $q$);
SELECT pg_temp.expect_fail_like('A2.3 a receipt stored CONFIRMED', 'ck_goods_receipts_status', $q$
    UPDATE procurement.goods_receipts SET status = 'CONFIRMED', confirmed_at = NOW(),
           confirmed_by = md5('demo:user:editor')::uuid WHERE id = md5('qa96:gr')::uuid $q$);
SELECT pg_temp.expect_true('A2.4 receipt_type and status name exactly what is left', $c$
    (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_goods_receipts_type') LIKE '%PRODUCTION%'
    AND (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_goods_receipts_type') LIKE '%RETURN%'
    AND (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_goods_receipts_type') NOT LIKE '%PURCHASE%'
    AND (SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_goods_receipts_status') NOT LIKE '%CONFIRMED%' $c$);
SELECT pg_temp.expect_true('A2.5 production_order_line_id is NOT NULL and check_goods_receipt no longer names PURCHASE', $c$
    (SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'procurement'
        AND table_name = 'goods_receipt_lines' AND column_name = 'production_order_line_id') = 'NO'
    AND (SELECT prosrc FROM pg_proc WHERE oid = 'procurement.check_goods_receipt()'::regprocedure) NOT LIKE '%PURCHASE%' $c$);

\echo '--- A2 happy path: a production receipt still takes a line, counts and is posted'
SELECT pg_temp.expect_ok('A2.6 a whole carton of the production order line, in its lot', $q$
    INSERT INTO procurement.goods_receipt_lines (id, receipt_id, production_order_line_id, inventory_item_id, location_id,
                                                 received_qty, lot_number, uom_conversion_id)
    VALUES (md5('qa96:l1')::uuid, md5('qa96:gr')::uuid, md5('qa96:prod:line')::uuid, (SELECT id FROM inventory.inventory_items WHERE sku = 'LY-GIAY-DEMO-12OZ-XANH'),
            md5('demo:loc:HCM-RCV01')::uuid, 20, 'PRD-20261109-9601', md5('qa96:carton')::uuid) $q$);
SELECT pg_temp.expect_ok('A2.7 the draft posted (IN_PUTAWAY) and its registration confirmed', $q$
    UPDATE procurement.goods_receipts SET status = 'IN_PUTAWAY', confirmed_at = NOW(),
           confirmed_by = md5('demo:user:editor')::uuid WHERE id = md5('qa96:gr')::uuid;
    UPDATE ordering.production_order_receipt SET status = 'CONFIRMED', settled_at = NOW()
     WHERE goods_receipt_id = md5('qa96:gr')::uuid $q$);

ROLLBACK;
