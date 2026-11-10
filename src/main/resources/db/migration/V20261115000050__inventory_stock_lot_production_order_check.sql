-- =============================================================================
-- The rule of a production order's lot, stated again (4A-5's ck_stock_lot_production_order; kltn-docs 03 BR-03).
--
-- Written when the carton line (team plan 5-1) and the production line (4A-5 .. 4A-9) were merged. V20261108002800 made
-- ck_stock_lot_production_order name stock_lot.expiry_date ("a production order lot has no expiry"), and
-- V20261111000300 then dropped that column: PostgreSQL drops a CHECK naming a dropped column with it, silently, so the
-- whole rule went - a production order lot could name a goods receipt, or be numbered other than its production order.
-- The same rule again, without the column that no longer exists (nothing has an expiry date any more, 01 BR-17).
-- StockLotJpaEntity.ofProductionOrder writes such a lot.
-- =============================================================================

ALTER TABLE inventory.stock_lot
    ADD CONSTRAINT ck_stock_lot_production_order CHECK ((origin_type = 'PRODUCTION_ORDER') = (production_order_id IS NOT NULL)
        AND (production_order_id IS NULL) = (production_order_code IS NULL)
        AND (origin_type <> 'PRODUCTION_ORDER'
             OR (lot_number = production_order_code AND received_at IS NOT NULL
                 AND goods_receipt_id IS NULL AND goods_receipt_number IS NULL AND purchase_order_id IS NULL
                 AND purchase_order_number IS NULL AND supplier_id IS NULL AND supplier_name IS NULL)));
