-- A goods receipt is against a production order (PRODUCTION) or brings returned goods back (RETURN). The PURCHASE type
-- (V20261113000200 removed purchasing and only stopped new ones) and the CONFIRMED status (the count's own moment: the
-- receipt leaves it for IN_PUTAWAY in the same transaction) are taken out of the table's vocabulary, and a receipt line
-- always names the production order line it counts (kltn-docs 03 §1).

-- 1. Leftover purchase receipts, if a database kept one (none on a database rebuilt from the baseline, owner decision
--    QD-1): their photos, lines and header. Only a receipt that never posted can go this way: one that did is named by
--    rows that keep it (ON DELETE RESTRICT: stock packages, putaway tasks, production order and return request links),
--    and the foreign key refusing stops the migration, which is the right failure.
-- A lot a purchase receipt opened (origin GOODS_RECEIPT, fk_stock_lot_goods_receipt ON DELETE RESTRICT) would stop the
-- delete below, and the next migration, which retires that origin, runs only after it. Such a lot is kept - stock rows and
-- ledger lines name it - but is UNTRACED from here, naming no receipt: what the next migration does to every lot of the
-- origin, done early for these. A lot of a production or return receipt keeps its origin until that migration.
UPDATE inventory.stock_lot
   SET origin_type = 'UNTRACED', goods_receipt_id = NULL, goods_receipt_number = NULL
 WHERE goods_receipt_id IN (SELECT id FROM procurement.goods_receipts WHERE receipt_type = 'PURCHASE');
-- The photos are append-only and the lines of a posted receipt are frozen (BR-05): those two triggers step aside for
-- this clean-up and nothing else.
ALTER TABLE procurement.goods_receipt_photos DISABLE TRIGGER tg_goods_receipt_photos_append_only;
ALTER TABLE procurement.goods_receipt_lines DISABLE TRIGGER tg_goods_receipt_lines_frozen;
DELETE FROM procurement.goods_receipt_photos
 WHERE receipt_id IN (SELECT id FROM procurement.goods_receipts WHERE receipt_type = 'PURCHASE');
DELETE FROM procurement.goods_receipt_lines
 WHERE receipt_id IN (SELECT id FROM procurement.goods_receipts WHERE receipt_type = 'PURCHASE');
DELETE FROM procurement.goods_receipts WHERE receipt_type = 'PURCHASE';
ALTER TABLE procurement.goods_receipt_photos ENABLE TRIGGER tg_goods_receipt_photos_append_only;
ALTER TABLE procurement.goods_receipt_lines ENABLE TRIGGER tg_goods_receipt_lines_frozen;

-- Nothing is stored CONFIRMED (the status was only ever passed through): move any such row on, as the confirm
-- transaction would have.
UPDATE procurement.goods_receipts SET status = 'IN_PUTAWAY' WHERE status = 'CONFIRMED';
-- The row's deferred constraint triggers fire now: a table with pending trigger events cannot be altered below.
SET CONSTRAINTS ALL IMMEDIATE;

-- 2. The CHECKs say what is left.
ALTER TABLE procurement.goods_receipts DROP CONSTRAINT ck_goods_receipts_type;
ALTER TABLE procurement.goods_receipts ADD CONSTRAINT ck_goods_receipts_type
    CHECK (receipt_type IN ('PRODUCTION', 'RETURN'));
ALTER TABLE procurement.goods_receipts DROP CONSTRAINT ck_goods_receipts_status;
ALTER TABLE procurement.goods_receipts ADD CONSTRAINT ck_goods_receipts_status
    CHECK (status IN ('DRAFT', 'IN_PUTAWAY', 'CLOSED', 'CANCELLED'));

-- 3. A line counts a production order line, always.
ALTER TABLE procurement.goods_receipt_lines ALTER COLUMN production_order_line_id SET NOT NULL;

-- 4. check_goods_receipt without the branch that refused a new PURCHASE receipt: ck_goods_receipts_type says it now.
CREATE OR REPLACE FUNCTION procurement.check_goods_receipt() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    production RECORD;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.receipt_type IS DISTINCT FROM OLD.receipt_type
            OR NEW.production_order_id IS DISTINCT FROM OLD.production_order_id THEN
            RAISE EXCEPTION 'receipt %: what it is against does not change', OLD.receipt_number
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF NEW.production_order_id IS NOT NULL THEN
        SELECT p.code, p.status, p.sample_approval_required, p.receiving_warehouse_id INTO production
          FROM ordering.production_order p WHERE p.id = NEW.production_order_id;
        IF NOT FOUND THEN
            RETURN NEW; -- fk_goods_receipts_production_order reports it
        END IF;
        IF NEW.warehouse_id IS DISTINCT FROM production.receiving_warehouse_id THEN
            RAISE EXCEPTION 'receipt % is into another warehouse than production order % is received at (03 BR-01)',
                NEW.receipt_number, production.code USING ERRCODE = 'check_violation';
        END IF;
        IF TG_OP = 'INSERT' AND NOT (production.status IN ('IN_PRODUCTION', 'PARTIALLY_RECEIVED')
            OR (production.status IN ('SENT', 'ACCEPTED') AND NOT production.sample_approval_required)) THEN
            RAISE EXCEPTION 'production order % is %: goods are not received against it (03 BR-01, BR-15)',
                production.code, production.status USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

-- 5.
COMMENT ON COLUMN procurement.goods_receipts.receipt_type IS 'PRODUCTION: against one production order (kltn-docs 03 §1). RETURN: goods that came back, scanned by LPN into quarantine (4D-2).';
