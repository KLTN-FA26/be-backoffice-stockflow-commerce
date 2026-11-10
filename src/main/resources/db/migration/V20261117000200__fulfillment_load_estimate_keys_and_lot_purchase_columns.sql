-- =============================================================================
-- Review Q-1, Q-2 (cluster 4): the references the rescope left unkeyed or pointing at nothing (ADR-0007: a cross-module
-- reference is a foreign key; QD-1: what is gone is removed, not kept).
--
-- Q-1. fulfillment.load_estimate.warehouse_id and source_id have never had a key: under V20261022001200 source_id named
--   an order or a transfer, so it could not have one. Transfers went in V20261108000300 and ck_load_estimate_source_type
--   allows ORDER only, so both are keyed now: warehouse_id -> warehouse.warehouse (RESTRICT, as every reference to a
--   warehouse), source_id -> ordering.customer_order (CASCADE: an estimate is derived from its order and recomputed on
--   demand, it does not outlive it).
-- Q-2. inventory.stock_lot.purchase_order_id, purchase_order_number, supplier_id and supplier_name snapshot a purchase
--   order and a supplier. Purchasing went in 4A-8 with their tables (and their keys); every lot since is a production
--   order's or UNTRACED, and the columns are null on every row a rebuilt database holds. Dropped, with the two CHECKs
--   that name them restated without them.
--
-- Data (QD-1, no archive, no stop condition):
--   * an estimate whose warehouse or order no longer exists is deleted: it is recomputed on demand, nothing is lost;
--   * the four lot columns go with what they hold.
-- Order: estimates cleaned, keys added validated; lot CHECKs dropped, columns dropped, CHECKs restated.
-- =============================================================================

-- Q-1 --------------------------------------------------------------------------------------------------------------
DELETE FROM fulfillment.load_estimate e
 WHERE NOT EXISTS (SELECT 1 FROM ordering.customer_order o WHERE o.id = e.source_id)
    OR (e.warehouse_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM warehouse.warehouse w WHERE w.id = e.warehouse_id));

ALTER TABLE fulfillment.load_estimate
    ADD CONSTRAINT fk_load_estimate_warehouse FOREIGN KEY (warehouse_id)
        REFERENCES warehouse.warehouse (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_load_estimate_order FOREIGN KEY (source_id)
        REFERENCES ordering.customer_order (id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_load_estimate_source ON fulfillment.load_estimate (source_id);
CREATE INDEX IF NOT EXISTS idx_load_estimate_warehouse ON fulfillment.load_estimate (warehouse_id);

-- Q-2 --------------------------------------------------------------------------------------------------------------
ALTER TABLE inventory.stock_lot
    DROP CONSTRAINT ck_stock_lot_production_order,
    DROP CONSTRAINT ck_stock_lot_untraced;

ALTER TABLE inventory.stock_lot
    DROP COLUMN purchase_order_id,
    DROP COLUMN purchase_order_number,
    DROP COLUMN supplier_id,
    DROP COLUMN supplier_name;

ALTER TABLE inventory.stock_lot
    ADD CONSTRAINT ck_stock_lot_production_order CHECK (
        ((origin_type = 'PRODUCTION_ORDER') = (production_order_id IS NOT NULL))
        AND ((production_order_id IS NULL) = (production_order_code IS NULL))
        AND (origin_type <> 'PRODUCTION_ORDER'
             OR (lot_number = production_order_code AND received_at IS NOT NULL
                 AND goods_receipt_id IS NULL AND goods_receipt_number IS NULL))),
    ADD CONSTRAINT ck_stock_lot_untraced CHECK (origin_type <> 'UNTRACED' OR goods_receipt_id IS NULL);
