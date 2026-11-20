-- A lot opened by a goods receipt on its own is gone (plan 2026-11-20-receiving-redundancy-removal, A3).
--
-- Goods are received against production orders only (V20261117000200), and a production order's goods have their own lot
-- (one per production order and SKU, numbered with its code: kltn-docs 03 BR-03). No code path opened a lot of origin
-- GOODS_RECEIPT any more: the origin, the two columns that named the receipt, the index that kept one lot per receipt
-- and the CHECKs that spoke of them go. A lot already stored with that origin is kept - stock rows and ledger lines name
-- it - but it is UNTRACED from now on, as stock whose receipt the system does not record. Historical enum constants stay
-- in Java; no row holds GOODS_RECEIPT.
--
-- ck_stock_lot_untraced said only "an untraced lot names no receipt"; with the column gone it says nothing and is dropped.
-- ck_stock_lot_receipt is about the dropped origin.

ALTER TABLE inventory.stock_lot DROP CONSTRAINT ck_stock_lot_untraced;
ALTER TABLE inventory.stock_lot DROP CONSTRAINT ck_stock_lot_receipt;
ALTER TABLE inventory.stock_lot DROP CONSTRAINT ck_stock_lot_production_order;
ALTER TABLE inventory.stock_lot DROP CONSTRAINT ck_stock_lot_origin;
DROP INDEX inventory.uk_stock_lot_receipt;

UPDATE inventory.stock_lot SET origin_type = 'UNTRACED' WHERE origin_type = 'GOODS_RECEIPT';

ALTER TABLE inventory.stock_lot DROP COLUMN goods_receipt_id;      -- takes fk_stock_lot_goods_receipt with it
ALTER TABLE inventory.stock_lot DROP COLUMN goods_receipt_number;

ALTER TABLE inventory.stock_lot ADD CONSTRAINT ck_stock_lot_origin
    CHECK (origin_type IN ('PRODUCTION_ORDER', 'UNTRACED'));
ALTER TABLE inventory.stock_lot ADD CONSTRAINT ck_stock_lot_production_order
    CHECK (((origin_type = 'PRODUCTION_ORDER') = (production_order_id IS NOT NULL))
       AND ((production_order_id IS NULL) = (production_order_code IS NULL))
       AND (origin_type <> 'PRODUCTION_ORDER' OR (lot_number = production_order_code AND received_at IS NOT NULL)));

COMMENT ON TABLE inventory.stock_lot IS 'A lot as received: one per production order and SKU, numbered with the production order''s code (kltn-docs 03 BR-03), or UNTRACED for stock whose receipt the system did not record.';
