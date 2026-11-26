-- A carton records when it was counted in, not a number of units (owner decision 2026-11-26, ADR-0011, step 3a).
--
-- inventory.stock_package.received_qty was "the units counted into the carton at receiving". Stock is only ever whole
-- sealed LPN cartons of the SKU's standard count, so ck_stock_package_standard_count forced the figure to equal
-- nominal_qty whenever it was set: it could say nothing nominal_qty does not say. The one fact it still carried was
-- whether the carton has been counted in yet (NULL: labelled on a DRAFT receipt, not counted). Written as what it is:
--
--   inventory.stock_package   received_qty integer -> counted_at timestamptz (NULL until counted)
--
-- How many units a carton stands for is nominal_qty, and only that (ADR-0011).
--
-- Backfill: a counted carton takes its own created_at. The application counts a carton in the very call that labels
-- it (StockOperationsServiceImpl.countReceivingPackages), so created_at is a moment it really was counted at; a later
-- recount of the draft was never recorded anywhere, last_modified_at is overwritten by every move and status change
-- after it, and the RECEIPT ledger line's occurred_at is the receipt's confirmation, which comes after the count (and
-- a carton labelled before the ledger named cartons has no such line).
--
-- CHECKs: ck_stock_package_counted keeps its rule on the new column (only a RECEIVING or VOID carton may be
-- uncounted). ck_stock_package_received (the figure is not negative) and ck_stock_package_standard_count (the figure
-- is the standard count) constrained the number only and go with it.
--
-- Every function and view whose live definition read the column is rewritten here (found in pg_proc.prosrc and
-- pg_views of a fully migrated database, since Postgres does not track what a function body mentions):
--   inventory.check_stock_package_contents       an IN_STOCK carton's rows hold nominal_qty (was received_qty)
--   inventory.check_stock_package_transition     counted_at changes only while RECEIVING
--   procurement.check_return_receipt_carton      a scanned returned carton is named at nominal_qty, and was counted
--   ordering.check_return_request_goods_settled  a sealed returned carton counts nominal_qty units (0 if uncounted)
--   inventory.stock_package_balance (view)       answers counted_at where it answered received_qty
-- and the two triggers of stock_package that fired on "UPDATE OF received_qty" now fire on counted_at.
-- procurement.goods_receipt_lines.received_qty (units a receipt line received) and
-- procurement.goods_receipt_return_cartons.counted_qty (units found inside a returned carton) are other facts on other
-- tables and are not touched.

-- ---------------------------------------------------------------------------------------------- 1. nothing is lost

-- A carton counted at another figure than its standard count cannot be said by a timestamp. No database built from
-- the baseline has one (585 cartons of the demo seed looked at: none); one kept by hand is looked at first.
DO $$
DECLARE
    odd TEXT;
BEGIN
    SELECT string_agg(lpn || ' (' || received_qty || ' of ' || nominal_qty || ')', ', ' ORDER BY lpn) INTO odd
      FROM (SELECT lpn, received_qty, nominal_qty FROM inventory.stock_package
             WHERE received_qty IS NOT NULL AND received_qty <> nominal_qty ORDER BY lpn LIMIT 10) x;
    IF odd IS NOT NULL THEN
        RAISE EXCEPTION 'stock_package: carton(s) counted in at another figure than their standard count: %; '
            'stock is whole cartons only (ADR-0011) - settle these rows before migrating', odd;
    END IF;
END $$;

-- ---------------------------------------------------------------------------------------------- 2. the column

ALTER TABLE inventory.stock_package ADD COLUMN counted_at TIMESTAMPTZ;

-- Neither tg_stock_package_transition nor tg_stock_package_rows fires on this column yet: nothing is judged here.
UPDATE inventory.stock_package SET counted_at = created_at WHERE received_qty IS NOT NULL;

DROP VIEW inventory.stock_package_balance;
DROP TRIGGER tg_stock_package_rows ON inventory.stock_package;
DROP TRIGGER tg_stock_package_transition ON inventory.stock_package;

ALTER TABLE inventory.stock_package
    DROP CONSTRAINT ck_stock_package_counted,
    DROP CONSTRAINT ck_stock_package_received,
    DROP CONSTRAINT ck_stock_package_standard_count,
    DROP COLUMN received_qty;

ALTER TABLE inventory.stock_package
    ADD CONSTRAINT ck_stock_package_counted CHECK (status IN ('RECEIVING', 'VOID') OR counted_at IS NOT NULL);

COMMENT ON COLUMN inventory.stock_package.counted_at IS
    'When it was counted in at receiving; NULL while only labelled. Fixed once in stock. Its units are nominal_qty.';
COMMENT ON CONSTRAINT ck_stock_package_counted ON inventory.stock_package IS
    'kltn-docs 03 BR-13: only a carton still being received, or voided before it was received, may be uncounted.';

-- ---------------------------------------------------------------------------------------------- 3. inventory

CREATE OR REPLACE FUNCTION inventory.check_stock_package_contents(package_id uuid)
 RETURNS void
 LANGUAGE plpgsql
AS $$
DECLARE
    pkg       inventory.stock_package%ROWTYPE;
    item_sku  VARCHAR(64);
    wrong_sku INTEGER;
    elsewhere INTEGER;
    lots      INTEGER;
    held      BIGINT;
BEGIN
    SELECT * INTO pkg FROM inventory.stock_package WHERE id = package_id;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    SELECT sku INTO item_sku FROM inventory.inventory_items WHERE id = pkg.inventory_item_id;

    SELECT count(*) FILTER (WHERE s.sku <> item_sku),
           count(*) FILTER (WHERE s.on_hand > 0 AND s.location_code <> pkg.location_code),
           count(DISTINCT s.lot_number) FILTER (WHERE s.on_hand > 0),
           COALESCE(sum(s.on_hand), 0)
      INTO wrong_sku, elsewhere, lots, held
      FROM inventory.stock_item s
     WHERE s.stock_package_id = package_id;

    IF wrong_sku > 0 THEN
        RAISE EXCEPTION 'stock package % holds stock of another SKU than %', pkg.lpn, item_sku
            USING ERRCODE = 'check_violation';
    END IF;
    IF elsewhere > 0 THEN
        RAISE EXCEPTION 'stock package % is at % but some of its stock is not', pkg.lpn, pkg.location_code
            USING ERRCODE = 'check_violation';
    END IF;
    IF lots > 1 THEN
        RAISE EXCEPTION 'stock package % holds more than one lot', pkg.lpn USING ERRCODE = 'check_violation';
    END IF;
    IF pkg.status = 'IN_STOCK' AND held <> pkg.nominal_qty THEN
        RAISE EXCEPTION 'stock package % is a carton of % but holds %; a carton in stock is whole', pkg.lpn,
            pkg.nominal_qty, held USING ERRCODE = 'check_violation';
    END IF;
    IF pkg.status <> 'IN_STOCK' AND held > 0 THEN
        RAISE EXCEPTION 'stock package % is % but holds %', pkg.lpn, pkg.status, held
            USING ERRCODE = 'check_violation';
    END IF;
END
$$;

COMMENT ON FUNCTION inventory.check_stock_package_contents(uuid) IS
    'A stock package and its stock row agree: its SKU, its place, one lot, and exactly its standard count '
    '(nominal_qty) while IN_STOCK, nothing otherwise. Run at commit by the constraint triggers.';

CREATE OR REPLACE FUNCTION inventory.check_stock_package_transition()
 RETURNS trigger
 LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
           (OLD.status = 'RECEIVING' AND NEW.status IN ('IN_STOCK', 'VOID'))
        OR (OLD.status = 'IN_STOCK' AND NEW.status IN ('CONSUMED', 'LOST'))
        OR (OLD.status = 'CONSUMED' AND NEW.status = 'IN_STOCK')
        OR (OLD.status = 'LOST' AND NEW.status = 'IN_STOCK')) THEN
        RAISE EXCEPTION 'stock package % cannot go from % to %', NEW.lpn, OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.counted_at IS DISTINCT FROM OLD.counted_at AND OLD.status <> 'RECEIVING' THEN
        RAISE EXCEPTION 'stock package % is %: a carton is counted in only while it is being received', NEW.lpn,
            OLD.status USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

COMMENT ON FUNCTION inventory.check_stock_package_transition() IS
    'Carton lifecycle (kltn-docs 20 §5.5): RECEIVING->IN_STOCK|VOID, IN_STOCK->CONSUMED|LOST, CONSUMED|LOST->IN_STOCK; '
    'counted (counted_at) only while RECEIVING.';

CREATE TRIGGER tg_stock_package_transition BEFORE UPDATE OF status, counted_at ON inventory.stock_package
    FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_package_transition();

CREATE CONSTRAINT TRIGGER tg_stock_package_rows AFTER INSERT OR UPDATE OF location_code, status, counted_at
    ON inventory.stock_package DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_package_rows();

CREATE VIEW inventory.stock_package_balance AS
SELECT p.id AS stock_package_id,
       p.lpn,
       i.sku,
       p.inventory_item_id,
       p.uom_conversion_id,
       u.unit_of_measure,
       p.nominal_qty,
       p.counted_at,
       p.location_code,
       p.status,
       s.id AS stock_item_id,
       s.status AS stock_status,
       s.lot_number,
       COALESCE(s.on_hand, 0) AS on_hand,
       COALESCE(s.reserved, 0) AS reserved,
       CASE WHEN s.status = 'AVAILABLE' THEN s.on_hand - s.reserved ELSE 0 END AS available,
       p.goods_receipt_line_id,
       p.closed_at
  FROM inventory.stock_package p
  JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
  JOIN inventory.uom_conversions u ON u.id = p.uom_conversion_id
  LEFT JOIN inventory.stock_item s ON s.stock_package_id = p.id;

COMMENT ON VIEW inventory.stock_package_balance IS
    'One row per LPN carton with its stock row: lot, stock status, on hand, reserved, available (team plan 4C-3).';

-- ---------------------------------------------------------------------------------------------- 4. its readers

-- "Named as it was shipped - its LPN, SKU, count and lot": the count a carton was shipped with is its standard count.
-- An uncounted carton (never in stock, so never shipped) is still refused here, as NULL IS DISTINCT FROM refused it.
CREATE OR REPLACE FUNCTION procurement.check_return_receipt_carton()
 RETURNS trigger
 LANGUAGE plpgsql
AS $$
DECLARE
    receipt          procurement.goods_receipts%ROWTYPE;
    pkg              RECORD;
    request_shipment UUID;
    approved         BIGINT;
BEGIN
    SELECT * INTO receipt FROM procurement.goods_receipts WHERE id = COALESCE(NEW.receipt_id, OLD.receipt_id);
    IF receipt.receipt_type IS DISTINCT FROM 'RETURN' THEN
        RAISE EXCEPTION 'cartons are scanned on a RETURN receipt only' USING ERRCODE = 'check_violation';
    END IF;
    IF receipt.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'return receipt % is %: its cartons are kept as they were confirmed', receipt.receipt_number,
            receipt.status USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    -- A writer that says nothing of the count took the carton whole: what it was before counted_qty existed. NOT NULL
    -- is checked after this trigger, so it still refuses an explicit NULL on an update.
    IF TG_OP = 'INSERT' AND NEW.counted_qty IS NULL THEN
        NEW.counted_qty := NEW.quantity;
    END IF;
    IF TG_OP = 'INSERT' AND NEW.counted_by IS NULL THEN
        NEW.counted_by := NEW.scanned_by;
    END IF;
    IF TG_OP = 'INSERT' AND NEW.stock_entered IS NULL THEN
        NEW.stock_entered := NEW.counted_qty = NEW.quantity;
    END IF;
    -- On a draft, whether the carton will enter stock follows its count: a recount (by the service or straight in the
    -- database) moves both together, so ck_goods_receipt_return_cartons_stock_entered never sees them disagree.
    IF TG_OP = 'UPDATE' THEN
        NEW.stock_entered := NEW.counted_qty = NEW.quantity;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.receipt_id IS DISTINCT FROM OLD.receipt_id
        OR NEW.stock_package_id IS DISTINCT FROM OLD.stock_package_id OR NEW.lpn IS DISTINCT FROM OLD.lpn
        OR NEW.sku IS DISTINCT FROM OLD.sku OR NEW.lot_number IS DISTINCT FROM OLD.lot_number
        OR NEW.quantity IS DISTINCT FROM OLD.quantity OR NEW.scanned_by IS DISTINCT FROM OLD.scanned_by) THEN
        RAISE EXCEPTION 'return receipt %: only the condition and the count inside a scanned carton are changed',
            receipt.receipt_number USING ERRCODE = 'check_violation';
    END IF;
    SELECT p.lpn, i.sku, p.nominal_qty, p.counted_at, p.return_opened_at, p.return_ownership INTO pkg
      FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
     WHERE p.id = NEW.stock_package_id;
    IF NOT FOUND THEN
        RETURN NEW; -- fk_goods_receipt_return_cartons_package reports it
    END IF;
    IF pkg.lpn <> NEW.lpn OR pkg.sku <> NEW.sku OR pkg.counted_at IS NULL OR pkg.nominal_qty IS DISTINCT FROM NEW.quantity
       OR NOT EXISTS (SELECT 1 FROM inventory.stock_item s
                       WHERE s.stock_package_id = NEW.stock_package_id
                         AND s.lot_number IS NOT DISTINCT FROM NEW.lot_number) THEN
        RAISE EXCEPTION 'return receipt %: carton % is named as it was shipped - its LPN, SKU, count and lot (BR-03)',
            receipt.receipt_number, NEW.lpn USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' AND pkg.return_opened_at IS NOT NULL THEN
        RAISE EXCEPTION 'return receipt %: carton % came back opened on an earlier return receipt (WF-05 Q-18)',
            receipt.receipt_number, NEW.lpn USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' AND pkg.return_ownership IN ('CUSTOMER_HELD', 'CUSTOMER_DESTROYED') THEN
        RAISE EXCEPTION 'return receipt %: carton % is the customer''s, held for them or destroyed on their behalf '
            '(WF-05 Q-4)', receipt.receipt_number, NEW.lpn USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM fulfillment.pick_line l JOIN fulfillment.pick k ON k.id = l.pick_id
                    WHERE k.order_id = receipt.customer_order_id AND l.lpn = NEW.lpn
                      AND l.picked_qty > l.put_back_qty) THEN
        RAISE EXCEPTION 'return receipt %: carton % was not shipped on its order (BR-14)', receipt.receipt_number,
            NEW.lpn USING ERRCODE = 'check_violation';
    END IF;
    IF receipt.return_request_id IS NOT NULL THEN
        IF TG_OP = 'INSERT' THEN
            -- WF-05 TH-1: a return against a delivery takes back that delivery's cartons only.
            SELECT r.shipment_id INTO request_shipment FROM ordering.return_request r
             WHERE r.id = receipt.return_request_id;
            IF request_shipment IS NOT NULL AND NOT EXISTS (
                    SELECT 1 FROM fulfillment.package p JOIN fulfillment.pick_line l ON l.id = p.pick_line_id
                     WHERE p.shipment_id = request_shipment AND p.status = 'LABELED' AND l.lpn = NEW.lpn) THEN
                RAISE EXCEPTION 'return receipt %: carton % was not delivered on the shipment the return is against',
                    receipt.receipt_number, NEW.lpn USING ERRCODE = 'check_violation';
            END IF;
        END IF;
        -- WF-05 Q-11 (BE-22, M3, L6): only goods the return approved, and never above it - on the scan and on a
        -- recount alike. Units beyond are the customer's, kept in custody on the return request.
        IF TG_OP = 'INSERT' OR NEW.counted_qty > OLD.counted_qty THEN
            approved := procurement.return_units_approved(receipt.return_request_id, NEW.sku);
            IF approved = 0 OR procurement.return_units_received(receipt.return_request_id, NEW.sku, NEW.id, NULL)
                               + NEW.counted_qty > approved THEN
                RAISE EXCEPTION 'return receipt %: carton % holds % the return did not approve, or more of it than is '
                    'still to come back: it is kept in custody on the return request (WF-05 Q-11)',
                    receipt.receipt_number, NEW.lpn, NEW.sku USING ERRCODE = 'check_violation';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END
$$;

-- A sealed returned carton stands for its standard count of units; one never counted in for none, as COALESCE(.., 0)
-- said of it.
CREATE OR REPLACE FUNCTION ordering.check_return_request_goods_settled()
 RETURNS trigger
 LANGUAGE plpgsql
AS $$
DECLARE
    unsettled TEXT;
BEGIN
    -- Per SKU of the return: what came back, what was refunded; per carton of it that came back sealed on this return,
    -- in label order, the units of the cartons before it. A carton is the company's exactly when the refund, after
    -- what came back outside a sealed carton, still reaches it (ReturnedCartonSettlement.split).
    WITH lines AS (
        SELECT x.sku, SUM(x.refund_quantity) AS refunded, SUM(COALESCE(x.received_quantity, 0)) AS back
          FROM ordering.return_request_line x
         WHERE x.return_request_id = NEW.id
         GROUP BY x.sku
    ), cartons AS (
        SELECT p.lpn, p.return_ownership, i.sku,
               COALESCE(SUM(CASE WHEN p.counted_at IS NULL THEN 0 ELSE p.nominal_qty END) OVER (PARTITION BY i.sku ORDER BY p.lpn
                            ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS before,
               SUM(CASE WHEN p.counted_at IS NULL THEN 0 ELSE p.nominal_qty END) OVER (PARTITION BY i.sku) AS sealed
          FROM inventory.stock_package p
          JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
         WHERE p.return_request_id = NEW.id AND p.return_ownership IS NOT NULL
    )
    SELECT string_agg(c.lpn || ' ' || c.return_ownership, ', ' ORDER BY c.lpn) INTO unsettled
      FROM cartons c
      LEFT JOIN lines l ON l.sku = c.sku
     WHERE c.return_ownership = 'PENDING'
        OR (c.return_ownership = 'COMPANY')
           <> (c.before < GREATEST(0, COALESCE(l.refunded, 0) - GREATEST(0, COALESCE(l.back, 0) - c.sealed)));
    IF unsettled IS NOT NULL THEN
        RAISE EXCEPTION 'return % is RESOLVED but its carton(s) % do not follow the refund of their SKU (WF-05 Q-4)',
            NEW.rma_number, unsettled USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;
