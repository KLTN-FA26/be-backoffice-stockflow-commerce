-- =============================================================================
-- G4-Q2 / PACK-PRINT (lead's decision 2026-10-21): the stock of printed goods is deducted when their production
-- order COMPLETES, not when they are packed.
--
-- Until now a completed ORDER production order moved its goods to a PACKING area (MOVE, reference PRODUCTION_ORDER,
-- "Hold <id>", IN_PRODUCTION -> AVAILABLE) and left them HELD for the sales order there; the package that completed
-- the print line deducted them (PICK, reference PACKAGE). From now on the completion itself consumes them (CONSUMPTION,
-- reference PRODUCTION_ORDER, "Hold <id>", from the packing area) and packing deducts nothing.
--
-- Schema: ck_stock_movement_shape lets a PUT_BACK with reference ORDER come from nowhere, as a pick line's does:
-- printed goods consumed at completion whose order is then cancelled come back only to be written off as SCRAP
-- (kltn-docs 19 BR-10).
--
-- Data: goods handed over under the old model and not packed yet are still HELD in a packing area, and the new
-- packing would never deduct them. They are converted here exactly as the completion now does it: each such hold is
-- CONSUMED, its stock row's on_hand and reserved fall by its quantity, and one CONSUMPTION line names the production
-- order its hand-over line named, with the hand-over's actor and this migration's time.
--
-- Left as they are, on purpose:
--   * holds of a CANCELLED order: BR-PRD-10 kept them for their SCRAP write-off, whose approval deducts them;
--   * holds a SCRAP write-off was already asked for (a cancellation, or a closed remainder): the same;
--   * anything not handed over from production (free stock somebody moved into a packing area and an order held).
-- No StockDeducted event is published for the converted holds: nothing listens to it for printed goods, and
-- available-to-promise does not move (the units were held already).
-- =============================================================================

-- ---- ledger shape --------------------------------------------------------------
-- kltn-docs 19 BR-10: printed goods consumed at completion whose order is then cancelled (or whose unshipped rest is
-- closed) are scrapped. They come back onto their packing-area layer from nowhere - as goods deducted when picked do -
-- only to be written off (PUT_BACK, reference ORDER, "Hold <id>" naming the hold that keeps them), and a SCRAP
-- adjustment takes them off again when approved. Every other branch is V20261017000470's, unchanged.
ALTER TABLE inventory.stock_movement DROP CONSTRAINT ck_stock_movement_shape;
ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_shape CHECK (CASE
    WHEN movement_type IN ('RECEIPT', 'TRANSFER_IN', 'RETURN_IN')
        THEN from_location_code IS NULL AND to_location_code IS NOT NULL
    WHEN movement_type IN ('PICK', 'TRANSFER_OUT', 'SCRAP', 'CONSUMPTION')
        THEN from_location_code IS NOT NULL AND to_location_code IS NULL
    WHEN movement_type IN ('PUTAWAY', 'MOVE')
        THEN from_location_code IS NOT NULL AND to_location_code IS NOT NULL
             AND from_location_code <> to_location_code
    -- deducted when picked (or consumed at completion, G4-Q2), so back from nowhere; or moved back from staging
    WHEN movement_type = 'PUT_BACK'
        THEN to_location_code IS NOT NULL
             AND ((reference_type IN ('PICK_LINE', 'ORDER') AND from_location_code IS NULL)
                  OR (from_location_code IS NOT NULL AND from_location_code <> to_location_code))
    WHEN movement_type IN ('ADJUSTMENT', 'COUNT_CORRECTION')
        THEN num_nonnulls(from_location_code, to_location_code) = 1
    WHEN movement_type = 'STATUS_CHANGE'
        THEN from_location_code = to_location_code
             AND from_status IS NOT NULL AND to_status IS NOT NULL AND from_status <> to_status
    END);

-- ---- in-flight data ------------------------------------------------------------
CREATE TEMPORARY TABLE g4q2_finished AS
SELECT r.id                AS reservation_id,
       r.stock_item_id,
       r.quantity,
       si.sku,
       si.lot_number,
       si.location_code,
       handed.reference_id AS production_order_id,
       handed.actor_id
  FROM inventory.stock_reservation r
  JOIN inventory.stock_item si ON si.id = r.stock_item_id
  JOIN warehouse.storage_location sl ON sl.location_code = si.location_code
  JOIN warehouse.area a ON a.location_id = sl.id AND a.type = 'PACKING'
  JOIN ordering.customer_order o ON o.id = r.order_id
  JOIN LATERAL (SELECT sm.reference_id, sm.actor_id
                  FROM inventory.stock_movement sm
                 WHERE sm.movement_type = 'MOVE'
                   AND sm.reference_type = 'PRODUCTION_ORDER'
                   AND sm.reason = 'Hold ' || r.id
                   AND sm.to_location_code = si.location_code
                   AND sm.from_status = 'IN_PRODUCTION'
                   AND sm.to_status = 'AVAILABLE'
                 ORDER BY sm.occurred_at DESC, sm.id DESC
                 LIMIT 1) handed ON TRUE
 WHERE r.status = 'HELD'
   AND r.order_id IS NOT NULL
   AND r.production_order_id IS NULL
   AND si.status = 'AVAILABLE'
   AND o.status <> 'CANCELLED'
   AND NOT EXISTS (SELECT 1 FROM inventory.stock_adjustment adj WHERE adj.reservation_id = r.id);

INSERT INTO inventory.stock_movement (id, movement_type, sku, lot_number, from_location_code, to_location_code,
                                      quantity, from_status, to_status, reference_type, reference_id, reason, actor_id,
                                      occurred_at, created_by)
SELECT gen_random_uuid(), 'CONSUMPTION', f.sku, f.lot_number, f.location_code, NULL, f.quantity, 'AVAILABLE',
       'AVAILABLE', 'PRODUCTION_ORDER', f.production_order_id, 'Hold ' || f.reservation_id, f.actor_id, NOW(),
       'V20261021001300'
  FROM g4q2_finished f;

UPDATE inventory.stock_item si
   SET on_hand          = si.on_hand - t.quantity,
       reserved         = si.reserved - t.quantity,
       version          = si.version + 1,
       last_modified_at = NOW(),
       last_modified_by = 'V20261021001300'
  FROM (SELECT stock_item_id, SUM(quantity) AS quantity FROM g4q2_finished GROUP BY stock_item_id) t
 WHERE si.id = t.stock_item_id;

UPDATE inventory.stock_reservation r
   SET status    = 'CONSUMED',
       closed_at = NOW()
  FROM g4q2_finished f
 WHERE r.id = f.reservation_id;

DROP TABLE g4q2_finished;
