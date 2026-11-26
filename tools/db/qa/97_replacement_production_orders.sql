-- =============================================================================
-- Adversarial checks + happy paths: replacement production orders (V20261225000100, SCRUM-839; kltn-docs 17 §4.13,
-- BR-31). Needs the demo seed: a demo production order of a signed, shipping order is the one replaced. Its lot, a
-- carton of it and the write-offs are fixtures written with the triggers off (the inventory rules are other scripts').
-- Rolled back at the end.
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

-- The production order replaced: a demo one whose sales order is signed and ships from a warehouse, and one of its
-- lines. Its draft copies are drawn up for the same batch, received where the order ships.
CREATE TEMP TABLE base ON COMMIT DROP AS
SELECT p.id AS po, p.code, p.sales_order_id, p.delivery_batch_id, p.customer_id, p.producer_id,
       o.shipping_warehouse_id, l.order_line_id, l.sku, l.units_per_carton, l.design_snapshot_id, l.print_file_checksum
  FROM ordering.production_order p
  JOIN ordering.customer_order o ON o.id = p.sales_order_id
  JOIN LATERAL (SELECT * FROM ordering.production_order_line x WHERE x.production_order_id = p.id
                ORDER BY x.id LIMIT 1) l ON TRUE
 WHERE o.contract_signed_at IS NOT NULL AND o.shipping_warehouse_id IS NOT NULL
   AND EXISTS (SELECT 1 FROM ordering.delivery_batch_line b
                WHERE b.delivery_batch_id = p.delivery_batch_id AND b.order_line_id = l.order_line_id
                  AND b.cartons >= 2)
 ORDER BY p.code LIMIT 1;
SELECT pg_temp.expect_true('RP0 the demo seed has a production order to replace', (SELECT count(*) FROM base) = 1);

-- Its lot (the demo's, if it was received), one carton of it, and write-offs of that carton: posted DAMAGED (two), posted for another reason, pending,
-- and posted DAMAGED of another lot. Written with the triggers off: they are only what a replacement points at.
SET LOCAL session_replication_role = replica;
INSERT INTO inventory.stock_lot (id, sku, lot_number, received_at, origin_type, production_order_id,
                                 production_order_code)
SELECT md5('qa97:lot')::uuid, sku, code, NOW(), 'PRODUCTION_ORDER', po, code FROM base b
 WHERE NOT EXISTS (SELECT 1 FROM inventory.stock_lot l WHERE l.production_order_id = b.po AND l.sku = b.sku);
-- A carton that has been in stock names its lot (ck_stock_package_lot, V20261230000600): the production order's.
INSERT INTO inventory.stock_package (id, lpn, inventory_item_id, uom_conversion_id, nominal_qty, counted_at,
                                     location_code, status, closed_at, lot_id)
SELECT md5('qa97:carton')::uuid, 'QA97-LPN-1', i.id, i.id, b.units_per_carton, NOW(), 'HCM-QUA01',
       'CONSUMED', NOW(), (SELECT l.id FROM inventory.stock_lot l WHERE l.production_order_id = b.po AND l.sku = b.sku)
  FROM base b JOIN inventory.inventory_items i ON i.sku = b.sku;
INSERT INTO inventory.stock_adjustment (id, adjustment_number, location_code, sku, lot_number, direction,
                                        reason_code, note, status, requested_by, decided_by, decided_at, posted_at,
                                        stock_package_id)
SELECT md5('qa97:adj:' || k)::uuid, 'ADJ-QA97-' || k, 'HCM-QUA01', b.sku,
       CASE WHEN k = 'other-lot' THEN 'LOT-ELSEWHERE' ELSE b.code END,
       CASE WHEN k = 'restock' THEN 'RESTOCK' ELSE 'WRITE_OFF' END,
       CASE WHEN k = 'other-reason' THEN 'OTHER' ELSE 'DAMAGED' END,
       CASE WHEN k = 'other-reason' THEN 'counted wrong' END,
       CASE WHEN k = 'pending' THEN 'PENDING_APPROVAL' ELSE 'POSTED' END,
       md5('demo:user:editor')::uuid,
       CASE WHEN k = 'pending' THEN NULL ELSE md5('demo:user:admin')::uuid END,
       CASE WHEN k = 'pending' THEN NULL ELSE NOW() END, CASE WHEN k = 'pending' THEN NULL ELSE NOW() END,
       md5('qa97:carton')::uuid
  FROM base b, unnest(ARRAY['d1', 'd2', 'd3', 'other-reason', 'pending', 'other-lot', 'restock']) k;
SET LOCAL session_replication_role = origin;

CREATE FUNCTION pg_temp.draft(po_id UUID, po_code TEXT, po_kind TEXT, replaces UUID) RETURNS void
    LANGUAGE sql AS $$
    INSERT INTO ordering.production_order (id, code, sales_order_id, delivery_batch_id, customer_id, producer_id,
                                           receiving_warehouse_id, need_by_date, status, sample_approval_required,
                                           sample_required_by_default, drawn_up_by, kind, replaces_production_order_id)
    SELECT po_id, po_code, sales_order_id, delivery_batch_id, customer_id, producer_id, shipping_warehouse_id,
           CURRENT_DATE + 30, 'DRAFT', FALSE, FALSE, md5('demo:user:editor')::uuid, po_kind, replaces
      FROM base
$$;
CREATE FUNCTION pg_temp.line(po_id UUID, cartons INTEGER) RETURNS void LANGUAGE sql AS $$
    INSERT INTO ordering.production_order_line (id, production_order_id, order_line_id, sku, cartons, units_per_carton,
                                                design_snapshot_id, print_file_checksum)
    SELECT gen_random_uuid(), po_id, order_line_id, sku, cartons, units_per_carton, design_snapshot_id,
           print_file_checksum
      FROM base
$$;
CREATE FUNCTION pg_temp.write_off(po_id UUID, adjustment TEXT) RETURNS void LANGUAGE sql AS $$
    INSERT INTO ordering.production_order_write_off (id, production_order_id, stock_adjustment_id, sku)
    SELECT gen_random_uuid(), po_id, md5('qa97:adj:' || adjustment)::uuid, sku FROM base
$$;

\echo '--- the kind and the production order replaced (ck_production_order_replaces)'
SELECT pg_temp.expect_true('RP1 every production order of before is STANDARD', NOT EXISTS (
    SELECT 1 FROM ordering.production_order WHERE kind <> 'STANDARD' OR replaces_production_order_id IS NOT NULL));
SELECT pg_temp.expect_fail('RP2 a kind that is neither STANDARD nor REPLACEMENT', $q$
    SELECT pg_temp.draft(md5('qa97:r0')::uuid, 'PRD-20261216-9700', 'REWORK', NULL);
    SELECT pg_temp.line(md5('qa97:r0')::uuid, 1) $q$);
SELECT pg_temp.expect_fail('RP3 a REPLACEMENT that replaces nothing', $q$
    SELECT pg_temp.draft(md5('qa97:r0')::uuid, 'PRD-20261216-9700', 'REPLACEMENT', NULL);
    SELECT pg_temp.line(md5('qa97:r0')::uuid, 1) $q$);
SELECT pg_temp.expect_fail('RP4 a STANDARD one that names a production order replaced', $q$
    SELECT pg_temp.draft(md5('qa97:r0')::uuid, 'PRD-20261216-9700', 'STANDARD', (SELECT po FROM base));
    SELECT pg_temp.line(md5('qa97:r0')::uuid, 1) $q$);
SELECT pg_temp.expect_fail('RP5 a REPLACEMENT of itself', $q$
    SELECT pg_temp.draft(md5('qa97:r0')::uuid, 'PRD-20261216-9700', 'REPLACEMENT', md5('qa97:r0')::uuid);
    SELECT pg_temp.line(md5('qa97:r0')::uuid, 1);
    SELECT pg_temp.write_off(md5('qa97:r0')::uuid, 'd1') $q$);
SELECT pg_temp.expect_fail('RP6 a REPLACEMENT of a production order of another sales order', $q$
    SELECT pg_temp.draft(md5('qa97:r0')::uuid, 'PRD-20261216-9700', 'REPLACEMENT',
                         (SELECT p.id FROM ordering.production_order p, base b
                           WHERE p.sales_order_id <> b.sales_order_id ORDER BY p.code LIMIT 1));
    SELECT pg_temp.line(md5('qa97:r0')::uuid, 1) $q$);

\echo '--- the write-offs it makes up (tg_production_order_write_off, 17 BR-31)'
SELECT pg_temp.expect_fail('RP7 a REPLACEMENT naming no write-off', $q$
    SELECT pg_temp.draft(md5('qa97:r0')::uuid, 'PRD-20261216-9700', 'REPLACEMENT', (SELECT po FROM base));
    SELECT pg_temp.line(md5('qa97:r0')::uuid, 1) $q$);
SELECT pg_temp.expect_fail('RP8 a write-off for another reason than DAMAGED', $q$
    SELECT pg_temp.draft(md5('qa97:r0')::uuid, 'PRD-20261216-9700', 'REPLACEMENT', (SELECT po FROM base));
    SELECT pg_temp.line(md5('qa97:r0')::uuid, 1);
    SELECT pg_temp.write_off(md5('qa97:r0')::uuid, 'other-reason') $q$);
SELECT pg_temp.expect_fail('RP9 a write-off not yet approved', $q$
    SELECT pg_temp.draft(md5('qa97:r0')::uuid, 'PRD-20261216-9700', 'REPLACEMENT', (SELECT po FROM base));
    SELECT pg_temp.line(md5('qa97:r0')::uuid, 1);
    SELECT pg_temp.write_off(md5('qa97:r0')::uuid, 'pending') $q$);
SELECT pg_temp.expect_fail('RP9b a posted DAMAGED adjustment that restocks: its direction is not WRITE_OFF (V20261230000100)', $q$
    SELECT pg_temp.draft(md5('qa97:r0')::uuid, 'PRD-20261216-9700', 'REPLACEMENT', (SELECT po FROM base));
    SELECT pg_temp.line(md5('qa97:r0')::uuid, 1);
    SELECT pg_temp.write_off(md5('qa97:r0')::uuid, 'restock') $q$);
SELECT pg_temp.expect_fail('RP10 a write-off of a carton of another lot', $q$
    SELECT pg_temp.draft(md5('qa97:r0')::uuid, 'PRD-20261216-9700', 'REPLACEMENT', (SELECT po FROM base));
    SELECT pg_temp.line(md5('qa97:r0')::uuid, 1);
    SELECT pg_temp.write_off(md5('qa97:r0')::uuid, 'other-lot') $q$);
SELECT pg_temp.expect_fail('RP11 a write-off under another SKU than the adjustment''s', $q$
    SELECT pg_temp.draft(md5('qa97:r0')::uuid, 'PRD-20261216-9700', 'REPLACEMENT', (SELECT po FROM base));
    SELECT pg_temp.line(md5('qa97:r0')::uuid, 1);
    INSERT INTO ordering.production_order_write_off (id, production_order_id, stock_adjustment_id, sku)
    VALUES (gen_random_uuid(), md5('qa97:r0')::uuid, md5('qa97:adj:d1')::uuid, 'NOT-THE-SKU') $q$);
SELECT pg_temp.expect_fail('RP12 a write-off named by a STANDARD production order', $q$
    SELECT pg_temp.draft(md5('qa97:r0')::uuid, 'PRD-20261216-9700', 'STANDARD', NULL);
    SELECT pg_temp.line(md5('qa97:r0')::uuid, 1);
    SELECT pg_temp.write_off(md5('qa97:r0')::uuid, 'd1') $q$);
SELECT pg_temp.expect_fail('RP13 two cartons made for one written off', $q$
    SELECT pg_temp.draft(md5('qa97:r0')::uuid, 'PRD-20261216-9700', 'REPLACEMENT', (SELECT po FROM base));
    SELECT pg_temp.line(md5('qa97:r0')::uuid, 2);
    SELECT pg_temp.write_off(md5('qa97:r0')::uuid, 'd1') $q$);
SELECT pg_temp.expect_ok('RP14 a REPLACEMENT draft of one carton for one carton written off', $q$
    SELECT pg_temp.draft(md5('qa97:r1')::uuid, 'PRD-20261216-9701', 'REPLACEMENT', (SELECT po FROM base));
    SELECT pg_temp.line(md5('qa97:r1')::uuid, 1);
    SELECT pg_temp.write_off(md5('qa97:r1')::uuid, 'd1') $q$);
SELECT pg_temp.expect_ok('RP15 a second replacement, of another write-off', $q$
    SELECT pg_temp.draft(md5('qa97:r2')::uuid, 'PRD-20261216-9702', 'REPLACEMENT', (SELECT po FROM base));
    SELECT pg_temp.line(md5('qa97:r2')::uuid, 1);
    SELECT pg_temp.write_off(md5('qa97:r2')::uuid, 'd2') $q$);
SELECT pg_temp.expect_fail('RP16 a draft edited past its write-offs', $q$
    UPDATE ordering.production_order_line SET cartons = 2 WHERE production_order_id = md5('qa97:r1')::uuid $q$);
SELECT pg_temp.expect_fail('RP17 a write-off made up by a second live replacement', $q$
    SELECT pg_temp.draft(md5('qa97:r0')::uuid, 'PRD-20261216-9700', 'REPLACEMENT', (SELECT po FROM base));
    SELECT pg_temp.line(md5('qa97:r0')::uuid, 1);
    SELECT pg_temp.write_off(md5('qa97:r0')::uuid, 'd1') $q$);
SELECT pg_temp.expect_fail('RP18 a write-off taken back', $q$
    DELETE FROM ordering.production_order_write_off WHERE production_order_id = md5('qa97:r1')::uuid $q$);
SELECT pg_temp.expect_fail('RP19 a write-off swapped for another', $q$
    UPDATE ordering.production_order_write_off SET stock_adjustment_id = md5('qa97:adj:d2')::uuid
     WHERE production_order_id = md5('qa97:r1')::uuid $q$);
SELECT pg_temp.expect_fail('RP20 a replacement turned STANDARD', $q$
    UPDATE ordering.production_order SET kind = 'STANDARD', replaces_production_order_id = NULL
     WHERE id = md5('qa97:r1')::uuid $q$);
SELECT pg_temp.expect_fail('RP21 a replacement moved to replace another production order', $q$
    UPDATE ordering.production_order SET replaces_production_order_id = md5('qa97:r2')::uuid
     WHERE id = md5('qa97:r1')::uuid $q$);
SELECT pg_temp.expect_ok('RP22 a cancelled replacement gives its write-off back to another', $q$
    UPDATE ordering.production_order SET status = 'CANCELLED', cancelled_at = NOW(),
           cancelled_by = md5('demo:user:editor')::uuid, cancellation_reason = 'another producer'
     WHERE id = md5('qa97:r1')::uuid;
    SELECT pg_temp.draft(md5('qa97:r3')::uuid, 'PRD-20261216-9703', 'REPLACEMENT', (SELECT po FROM base));
    SELECT pg_temp.line(md5('qa97:r3')::uuid, 1);
    SELECT pg_temp.write_off(md5('qa97:r3')::uuid, 'd1') $q$);
SELECT pg_temp.expect_fail('RP23 a write-off added once the replacement is no longer a draft', $q$
    SELECT pg_temp.write_off(md5('qa97:r1')::uuid, 'd3') $q$);
SELECT pg_temp.expect_ok('RP24 a write-off added to a replacement draft, and a carton with it', $q$
    SELECT pg_temp.write_off(md5('qa97:r3')::uuid, 'd3');
    UPDATE ordering.production_order_line SET cartons = 2 WHERE production_order_id = md5('qa97:r3')::uuid $q$);

ROLLBACK;
