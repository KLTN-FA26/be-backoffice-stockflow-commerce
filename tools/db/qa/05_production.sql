-- =============================================================================
-- Adversarial checks + happy paths: production orders, sample requests, order release, SUBCONTRACT POs,
-- roles (SCRUM-422, docs 19). Needs the demo seed. Rolled back at the end.
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

-- Fixtures: a customer, a confirmed design snapshot, a released order with one print line.
INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
SELECT md5('qa:draft:1')::uuid, id, (SELECT p.id FROM product.products p ORDER BY p.code LIMIT 1), 'DRAFT', NOW(),
       '{}'::jsonb FROM customer.customer LIMIT 1;
INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at, artifact_manifest)
VALUES (md5('qa:snap:1')::uuid, md5('qa:draft:1')::uuid, repeat('a', 64), 'https://example.com/a.pdf', NOW(), NOW(), '[]'::jsonb);
INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount, currency, placed_at, created_at,
                                     paid_amount, paid_in_full_at)
SELECT md5('qa:order:1')::uuid, 'ORD-QA-PRD-1', id, gen_random_uuid(), 'CONFIRMED', 1000000, 'VND', NOW(), NOW(), 1000000, NOW()
  FROM customer.customer LIMIT 1;
INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, design_snapshot_id, design_checksum)
VALUES (md5('qa:oline:1')::uuid, md5('qa:order:1')::uuid, 'SOFA-3S-GREY', 500, 2000, 'VND', md5('qa:snap:1')::uuid, repeat('a', 64));

\echo '--- order release (SCRUM-423 schema)'
SELECT pg_temp.expect_fail('PR1 IN_PRODUCTION without a release', $q$
    UPDATE ordering.customer_order SET status = 'IN_PRODUCTION' WHERE id = md5('qa:order:1')::uuid $q$);
SELECT pg_temp.expect_fail('PR2 a released order without a warehouse', $q$
    UPDATE ordering.customer_order SET released_at = NOW(), released_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa:order:1')::uuid $q$);
SELECT pg_temp.expect_fail('PR3 a deposit order without the amount required', $q$
    UPDATE ordering.customer_order SET payment_term = 'DEPOSIT' WHERE id = md5('qa:order:1')::uuid $q$);
SELECT pg_temp.expect_fail('PR4 a deposit order in production before the deposit arrived (BR-PRD-09)', $q$
    UPDATE ordering.customer_order SET payment_term = 'DEPOSIT', deposit_required = 300000, status = 'IN_PRODUCTION',
           warehouse_id = md5('demo:wh:HCM')::uuid, released_at = NOW(), released_by = md5('demo:user:editor')::uuid
     WHERE id = md5('qa:order:1')::uuid $q$);
SELECT pg_temp.expect_ok('PR5 released to production from HCM', $q$
    UPDATE ordering.customer_order SET status = 'IN_PRODUCTION', warehouse_id = md5('demo:wh:HCM')::uuid,
           released_at = NOW(), released_by = md5('demo:user:editor')::uuid WHERE id = md5('qa:order:1')::uuid $q$);

\echo '--- production orders'
SELECT pg_temp.expect_fail('PR6 an ORDER production order without its order line', $q$
    INSERT INTO production.production_order (id, order_number, type, status, warehouse_id, blank_sku, design_snapshot_id,
                                             design_checksum, planned_quantity, sales_order_id)
    VALUES (gen_random_uuid(), 'LSX-QA-X', 'ORDER', 'PENDING_PREPRESS', md5('demo:wh:HCM')::uuid, 'SOFA-3S-GREY',
            md5('qa:snap:1')::uuid, repeat('a', 64), 500, md5('qa:order:1')::uuid) $q$);
SELECT pg_temp.expect_ok('PR7 ORDER production order for the line', $q$
    INSERT INTO production.production_order (id, order_number, type, status, warehouse_id, blank_sku, design_snapshot_id,
                                             design_checksum, planned_quantity, sales_order_id, sales_order_line_id)
    VALUES (md5('qa:lsx:1')::uuid, 'LSX-QA-1', 'ORDER', 'PENDING_PREPRESS', md5('demo:wh:HCM')::uuid, 'SOFA-3S-GREY',
            md5('qa:snap:1')::uuid, repeat('a', 64), 500, md5('qa:order:1')::uuid, md5('qa:oline:1')::uuid) $q$);
SELECT pg_temp.expect_fail('PR8 a second root production order for the same line (BR-01)', $q$
    INSERT INTO production.production_order (id, order_number, type, status, warehouse_id, blank_sku, design_snapshot_id,
                                             design_checksum, planned_quantity, sales_order_id, sales_order_line_id)
    VALUES (gen_random_uuid(), 'LSX-QA-2', 'ORDER', 'PENDING_PREPRESS', md5('demo:wh:HCM')::uuid, 'SOFA-3S-GREY',
            md5('qa:snap:1')::uuid, repeat('a', 64), 500, md5('qa:order:1')::uuid, md5('qa:oline:1')::uuid) $q$);
SELECT pg_temp.expect_fail('PR9 printing before prepress (BR-02)', $q$
    UPDATE production.production_order SET status = 'PRINTING' WHERE id = md5('qa:lsx:1')::uuid $q$);
SELECT pg_temp.expect_fail('PR10 good + scrap different from printed (BR-03)', $q$
    UPDATE production.production_order SET printed_quantity = 100, good_quantity = 90, scrap_quantity = 5
     WHERE id = md5('qa:lsx:1')::uuid $q$);
SELECT pg_temp.expect_fail('PR11 COMPLETED short of the planned quantity', $q$
    UPDATE production.production_order SET status = 'COMPLETED', completed_at = NOW(), prepress_checked_at = NOW(),
           prepress_checked_by = md5('demo:user:editor')::uuid, printed_quantity = 400, good_quantity = 400
     WHERE id = md5('qa:lsx:1')::uuid $q$);
SELECT pg_temp.expect_fail('PR12 an in-house order in SUBCONTRACTED status (branches do not cross)', $q$
    UPDATE production.production_order SET status = 'SUBCONTRACTED', prepress_checked_at = NOW(),
           prepress_checked_by = md5('demo:user:editor')::uuid WHERE id = md5('qa:lsx:1')::uuid $q$);
SELECT pg_temp.expect_fail('PR13 ON_HOLD without the status to return to', $q$
    UPDATE production.production_order SET status = 'ON_HOLD', hold_reason = 'MACHINE_DOWN' WHERE id = md5('qa:lsx:1')::uuid $q$);
SELECT pg_temp.expect_ok('PR14 split: parent planned 300, SUBCONTRACTED child 200', $q$
    UPDATE production.production_order SET planned_quantity = 300 WHERE id = md5('qa:lsx:1')::uuid;
    INSERT INTO production.production_order (id, order_number, type, mode, status, warehouse_id, blank_sku, design_snapshot_id,
                                             design_checksum, planned_quantity, sales_order_id, sales_order_line_id, parent_id,
                                             subcontract_method, subcontractor_id, subcontract_unit_price, subcontract_currency)
    VALUES (md5('qa:lsx:1c')::uuid, 'LSX-QA-1C', 'ORDER', 'SUBCONTRACTED', 'PENDING_PREPRESS', md5('demo:wh:HCM')::uuid,
            'SOFA-3S-GREY', md5('qa:snap:1')::uuid, repeat('a', 64), 200, md5('qa:order:1')::uuid,
            md5('qa:oline:1')::uuid, md5('qa:lsx:1')::uuid, 'SUPPLIED_BLANKS', md5('demo:supplier:GOVIET')::uuid, 800, 'VND') $q$);
SELECT pg_temp.expect_fail('PR15 a subcontracted child without a subcontractor', $q$
    INSERT INTO production.production_order (id, order_number, type, mode, status, warehouse_id, blank_sku, design_snapshot_id,
                                             design_checksum, planned_quantity, sales_order_id, sales_order_line_id, parent_id,
                                             subcontract_method)
    VALUES (gen_random_uuid(), 'LSX-QA-1D', 'ORDER', 'SUBCONTRACTED', 'PENDING_PREPRESS', md5('demo:wh:HCM')::uuid,
            'SOFA-3S-GREY', md5('qa:snap:1')::uuid, repeat('a', 64), 10, md5('qa:order:1')::uuid,
            md5('qa:oline:1')::uuid, md5('qa:lsx:1')::uuid, 'FULL_SERVICE') $q$);
SELECT pg_temp.expect_fail('PR16 scrap OTHER without a note (BR-04)', $q$
    INSERT INTO production.production_scrap (id, production_order_id, quantity, reason, recorded_at, recorded_by)
    VALUES (gen_random_uuid(), md5('qa:lsx:1')::uuid, 3, 'OTHER', NOW(), md5('demo:user:editor')::uuid) $q$);
SELECT pg_temp.expect_ok('PR17 scrap with a reason; a SCRAP stock adjustment', $q$
    INSERT INTO production.production_scrap (id, production_order_id, quantity, reason, recorded_at, recorded_by)
    VALUES (gen_random_uuid(), md5('qa:lsx:1')::uuid, 3, 'PRINT_DEFECT', NOW(), md5('demo:user:editor')::uuid);
    INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, quantity_delta, reason_code,
                                            requested_by, status, created_at)
    VALUES (gen_random_uuid(), 'ADJ-QA-SCRAP', 'HCM-A01-2-B', 'SOFA-3S-GREY', -3, 'SCRAP',
            md5('demo:user:editor')::uuid, 'PENDING_APPROVAL', NOW()) $q$);
SELECT pg_temp.expect_ok('PR18 ledger line referencing a production order', $q$
    INSERT INTO inventory.stock_movement (id, movement_type, sku, from_location_code, to_location_code, quantity,
                                          from_status, to_status, reference_type, reference_id, occurred_at)
    VALUES (gen_random_uuid(), 'MOVE', 'SOFA-3S-GREY', 'HCM-A01-2-B', 'HCM-PACK01', 1, 'AVAILABLE', 'AVAILABLE',
            'PRODUCTION_ORDER', md5('qa:lsx:1')::uuid, NOW()) $q$);
SELECT pg_temp.expect_ok('PR19 a PRODUCTION area on the map; QUALITY_CONTROL still allowed', $q$
    INSERT INTO warehouse.storage_location (id, warehouse_id, kind, location_code, storage_class, is_pickable,
                                            is_putaway_target, status, created_by)
    VALUES (md5('qa:loc:PRD')::uuid, md5('demo:wh:HCM')::uuid, 'AREA', 'HCM-PRDQA', 'NORMAL', FALSE, FALSE, 'ACTIVE', 'qa'),
           (md5('qa:loc:QCX')::uuid, md5('demo:wh:HCM')::uuid, 'AREA', 'HCM-QCXQA', 'NORMAL', FALSE, FALSE, 'ACTIVE', 'qa');
    INSERT INTO warehouse.area (id, warehouse_id, location_id, code, type, name, x, y, width, length, rotation, is_obstacle, status)
    VALUES (gen_random_uuid(), md5('demo:wh:HCM')::uuid, md5('qa:loc:PRD')::uuid, 'PRDQA', 'PRODUCTION', 'Xưởng in', 2, 18, 6, 6, 0, FALSE, 'ACTIVE'),
           (gen_random_uuid(), md5('demo:wh:HCM')::uuid, md5('qa:loc:QCX')::uuid, 'QCXQA', 'QUALITY_CONTROL', 'QC', 10, 18, 4, 4, 0, FALSE, 'ACTIVE') $q$);

\echo '--- SUBCONTRACT purchase orders (SCRUM-434 schema)'
SELECT pg_temp.expect_fail('PR20 SUBCONTRACT PO without its production order', $q$
    UPDATE procurement.suppliers SET is_print_subcontractor = TRUE WHERE id = md5('demo:supplier:GOVIET')::uuid;
    INSERT INTO procurement.purchase_orders (id, po_number, po_type, supplier_id, warehouse_id, currency, order_date)
    VALUES (gen_random_uuid(), 'PO-QA-SC0', 'SUBCONTRACT', md5('demo:supplier:GOVIET')::uuid, md5('demo:wh:HCM')::uuid,
            'VND', CURRENT_DATE) $q$);
SELECT pg_temp.expect_fail('PR21 SUBCONTRACT PO to a supplier that is not a print subcontractor', $q$
    INSERT INTO procurement.purchase_orders (id, po_number, po_type, production_order_id, supplier_id, warehouse_id, currency, order_date)
    VALUES (gen_random_uuid(), 'PO-QA-SC1', 'SUBCONTRACT', md5('qa:lsx:1c')::uuid, md5('demo:supplier:GOVIET')::uuid,
            md5('demo:wh:HCM')::uuid, 'VND', CURRENT_DATE) $q$);
SELECT pg_temp.expect_fail('PR22 SUBCONTRACT PO for an in-house production order', $q$
    UPDATE procurement.suppliers SET is_print_subcontractor = TRUE WHERE id = md5('demo:supplier:GOVIET')::uuid;
    INSERT INTO procurement.purchase_orders (id, po_number, po_type, production_order_id, supplier_id, warehouse_id, currency, order_date)
    VALUES (gen_random_uuid(), 'PO-QA-SC2', 'SUBCONTRACT', md5('qa:lsx:1')::uuid, md5('demo:supplier:GOVIET')::uuid,
            md5('demo:wh:HCM')::uuid, 'VND', CURRENT_DATE) $q$);
SELECT pg_temp.expect_ok('PR23 SUBCONTRACT PO for the child, one line; the child points back at it', $q$
    UPDATE procurement.suppliers SET is_print_subcontractor = TRUE WHERE id = md5('demo:supplier:GOVIET')::uuid;
    INSERT INTO procurement.purchase_orders (id, po_number, po_type, production_order_id, supplier_id, warehouse_id, currency, order_date)
    VALUES (md5('qa:po:sc')::uuid, 'PO-QA-SC3', 'SUBCONTRACT', md5('qa:lsx:1c')::uuid, md5('demo:supplier:GOVIET')::uuid,
            md5('demo:wh:HCM')::uuid, 'VND', CURRENT_DATE);
    INSERT INTO procurement.purchase_order_lines (id, po_id, line_no, inventory_item_id, ordered_qty, unit_price)
    VALUES (gen_random_uuid(), md5('qa:po:sc')::uuid, 1, md5('demo:item:SOFA-3S-GREY')::uuid, 200, 800);
    UPDATE production.production_order SET purchase_order_id = md5('qa:po:sc')::uuid WHERE id = md5('qa:lsx:1c')::uuid $q$);
SELECT pg_temp.expect_fail('PR24 a second line on a SUBCONTRACT PO', $q$
    INSERT INTO procurement.purchase_order_lines (id, po_id, line_no, inventory_item_id, ordered_qty, unit_price)
    VALUES (gen_random_uuid(), md5('qa:po:sc')::uuid, 2, md5('demo:item:TABLE-OAK-160')::uuid, 1, 800) $q$);
SELECT pg_temp.expect_fail('PR25 a second live SUBCONTRACT PO for the same production order', $q$
    INSERT INTO procurement.purchase_orders (id, po_number, po_type, production_order_id, supplier_id, warehouse_id, currency, order_date)
    VALUES (gen_random_uuid(), 'PO-QA-SC4', 'SUBCONTRACT', md5('qa:lsx:1c')::uuid, md5('demo:supplier:GOVIET')::uuid,
            md5('demo:wh:HCM')::uuid, 'VND', CURRENT_DATE) $q$);
SELECT pg_temp.expect_fail('PR26 turning a SUBCONTRACT PO into a standard one', $q$
    UPDATE procurement.purchase_orders SET po_type = 'STANDARD', production_order_id = NULL WHERE id = md5('qa:po:sc')::uuid $q$);
SELECT pg_temp.expect_fail('PR27 loss tolerance above 100 %', $q$
    UPDATE procurement.suppliers SET loss_tolerance_percent = 120 WHERE id = md5('demo:supplier:GOVIET')::uuid $q$);
SELECT pg_temp.expect_ok('PR28 a standard PO keeps working without a production order', $q$
    INSERT INTO procurement.purchase_orders (id, po_number, supplier_id, warehouse_id, currency, order_date)
    VALUES (gen_random_uuid(), 'PO-QA-ST1', md5('demo:supplier:GOVIET')::uuid, md5('demo:wh:HCM')::uuid, 'VND', CURRENT_DATE) $q$);

\echo '--- what ordering learns from production'
SELECT pg_temp.expect_ok('PR30 an approved sample, recorded for the order module', $q$
    INSERT INTO production.sample_request (id, request_number, customer_id, blank_sku, design_snapshot_id, quantity, status,
                                           sent_at, responded_at, responded_by)
    SELECT md5('qa:sr:1')::uuid, 'SR-QA-1', customer_id, 'SOFA-3S-GREY', md5('qa:snap:1')::uuid, 3, 'APPROVED',
           NOW(), NOW(), md5('demo:user:editor')::uuid FROM ordering.customer_order WHERE id = md5('qa:order:1')::uuid;
    INSERT INTO ordering.approved_sample (sample_request_id, customer_id, design_snapshot_id, blank_sku, approved_at)
    SELECT md5('qa:sr:1')::uuid, customer_id, md5('qa:snap:1')::uuid, 'SOFA-3S-GREY', NOW()
      FROM ordering.customer_order WHERE id = md5('qa:order:1')::uuid $q$);
SELECT pg_temp.expect_fail('PR31 an approved sample for a sample request that does not exist', $q$
    INSERT INTO ordering.approved_sample (sample_request_id, customer_id, design_snapshot_id, blank_sku, approved_at)
    SELECT gen_random_uuid(), customer_id, md5('qa:snap:1')::uuid, 'SOFA-3S-GREY', NOW()
      FROM ordering.customer_order WHERE id = md5('qa:order:1')::uuid $q$);
SELECT pg_temp.expect_ok('PR32 a finished production order of the line', $q$
    INSERT INTO ordering.order_line_production (production_order_id, order_line_id, good_quantity, completed_at)
    VALUES (md5('qa:lsx:1')::uuid, md5('qa:oline:1')::uuid, 300, NOW()) $q$);
SELECT pg_temp.expect_fail('PR33 the same production order recorded twice', $q$
    INSERT INTO ordering.order_line_production (production_order_id, order_line_id, good_quantity, completed_at)
    VALUES (md5('qa:lsx:1')::uuid, md5('qa:oline:1')::uuid, 300, NOW()) $q$);
SELECT pg_temp.expect_fail('PR34 negative good quantity', $q$
    INSERT INTO ordering.order_line_production (production_order_id, order_line_id, good_quantity, completed_at)
    VALUES (md5('qa:lsx:1c')::uuid, md5('qa:oline:1')::uuid, -1, NOW()) $q$);
SELECT pg_temp.expect_ok('PR35 READY_TO_FULFILL once released', $q$
    UPDATE ordering.customer_order SET status = 'READY_TO_FULFILL' WHERE id = md5('qa:order:1')::uuid $q$);

\echo '--- roles and grants'
SELECT pg_temp.expect_ok('PR29 PRODUCTION_STAFF exists; WAREHOUSE_MANAGER reads the ledger; WAREHOUSE_STAFF works transfers', $q$
    DO $d$ BEGIN
        IF NOT EXISTS (SELECT 1 FROM identity.app_role WHERE code = 'PRODUCTION_STAFF')
           OR NOT EXISTS (SELECT 1 FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
                           JOIN identity.permission p ON p.id = rp.permission_id
                          WHERE r.code = 'WAREHOUSE_MANAGER' AND p.code = 'inventory-stock-movements:READ')
           OR NOT EXISTS (SELECT 1 FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
                           JOIN identity.permission p ON p.id = rp.permission_id
                          WHERE r.code = 'WAREHOUSE_STAFF' AND p.code = 'inventory-transfer-orders:UPDATE')
           OR EXISTS (SELECT 1 FROM identity.permission p WHERE NOT EXISTS (
                          SELECT 1 FROM identity.role_permission rp JOIN identity.app_role r ON r.id = rp.role_id
                           WHERE r.code = 'SYSTEM_ADMIN' AND rp.permission_id = p.id)) THEN
            RAISE EXCEPTION 'grants missing';
        END IF;
    END $d$ $q$);

ROLLBACK;
\echo 'PASS 05_production'
