-- 4A-5 (kltn-docs 03 §4.1, BR-01..BR-15 and edge cases; 17 §4.3 steps 10-11, §5.3; team plan 4A-5): finished goods
-- received against a production order. A PRODUCTION goods receipt is against exactly one production order, at the
-- warehouse it is received at, while it can receive goods (BR-01, BR-15); its lines are the production order's lines,
-- counted in whole cartons of the line's standard count (BR-12, BR-13), in the lot that is the production order's code
-- with no expiry date (BR-03, BR-06); the cartons on every receipt not cancelled - drafts too - never add up to more
-- than the line orders (BR-02). There is no tolerance and no approval: nothing over is received.
--
-- The order module keeps which receipts each production order has open (ordering.production_order_receipt), so a
-- production order with a draft receipt is neither cancelled nor closed (03 edge cases). The lot of the goods is one
-- per production order and SKU, whatever the number of receipts (BR-03): inventory.stock_lot gets the origin
-- PRODUCTION_ORDER. Purchase receipts keep every rule they had (their removal is 4A-8/4A-9).

-- 1. Goods receipts: a type, and the production order a PRODUCTION receipt is against -----------------------------------
ALTER TABLE procurement.goods_receipts
    ADD COLUMN receipt_type        VARCHAR(16) NOT NULL DEFAULT 'PURCHASE',
    ADD COLUMN production_order_id UUID;

COMMENT ON COLUMN procurement.goods_receipts.receipt_type IS
    'PURCHASE: against a purchase order, or none (blind); PRODUCTION: against one production order (kltn-docs 03 §1).';

ALTER TABLE procurement.goods_receipts
    ADD CONSTRAINT fk_goods_receipts_production_order FOREIGN KEY (production_order_id)
        REFERENCES ordering.production_order (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_goods_receipts_type CHECK (receipt_type IN ('PURCHASE', 'PRODUCTION')),
    -- 03 BR-01: one receipt, one production order.
    ADD CONSTRAINT ck_goods_receipts_production_order
        CHECK ((receipt_type = 'PRODUCTION') = (production_order_id IS NOT NULL)),
    -- 03 BR-02: no tolerance, so nothing waits for the warehouse manager; and none of a purchase receipt's references.
    ADD CONSTRAINT ck_goods_receipts_production CHECK (receipt_type <> 'PRODUCTION'
        OR (po_id IS NULL AND po_revision_id IS NULL AND supplier_id IS NULL AND blind_reason IS NULL
            AND referenced_po_id IS NULL AND approval_requested_at IS NULL AND status <> 'PENDING_APPROVAL'));

-- "No purchase order, so blind" holds for purchase receipts only.
ALTER TABLE procurement.goods_receipts
    DROP CONSTRAINT ck_goods_receipts_blind,
    ADD CONSTRAINT ck_goods_receipts_blind CHECK (receipt_type <> 'PURCHASE'
        OR (po_id IS NOT NULL AND supplier_id IS NULL AND blind_reason IS NULL AND referenced_po_id IS NULL)
        OR (po_id IS NULL AND supplier_id IS NOT NULL AND blind_reason IS NOT NULL AND btrim(blind_reason) <> '')),
    DROP CONSTRAINT ck_goods_receipts_blind_approved,
    ADD CONSTRAINT ck_goods_receipts_blind_approved CHECK (receipt_type <> 'PURCHASE' OR po_id IS NOT NULL
        OR status IN ('DRAFT', 'PENDING_APPROVAL', 'CANCELLED') OR COALESCE(approval_decision, '') = 'APPROVED');

CREATE INDEX ix_goods_receipts_production_order ON procurement.goods_receipts (production_order_id)
    WHERE production_order_id IS NOT NULL;

-- What a receipt is against never changes. A production receipt is into the warehouse its production order is received
-- at, and is started only while the production order can receive goods (03 BR-01, BR-15): IN_PRODUCTION or
-- PARTIALLY_RECEIVED; SENT or ACCEPTED when no print sample is to be approved first.
CREATE OR REPLACE FUNCTION procurement.check_goods_receipt() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    po         procurement.purchase_orders%ROWTYPE;
    production RECORD;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.receipt_type IS DISTINCT FROM OLD.receipt_type
            OR NEW.production_order_id IS DISTINCT FROM OLD.production_order_id THEN
            RAISE EXCEPTION 'receipt %: what it is against does not change', OLD.receipt_number
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF NEW.referenced_po_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM procurement.purchase_orders p
         WHERE p.id = NEW.referenced_po_id AND p.supplier_id = NEW.supplier_id) THEN
        RAISE EXCEPTION 'receipt % references a PO of another supplier', NEW.receipt_number
            USING ERRCODE = 'check_violation';
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
        RETURN NEW;
    END IF;
    IF NEW.po_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT * INTO po FROM procurement.purchase_orders WHERE id = NEW.po_id;
    IF NOT EXISTS (SELECT 1 FROM procurement.purchase_order_revisions r
                    WHERE r.id = NEW.po_revision_id AND r.po_id = NEW.po_id) THEN
        RAISE EXCEPTION 'receipt % is against a revision of another PO', NEW.receipt_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.warehouse_id <> po.warehouse_id THEN
        RAISE EXCEPTION 'receipt % is into another warehouse than PO %', NEW.receipt_number, po.po_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' AND po.status NOT IN ('CONFIRMED', 'PARTIALLY_RECEIVED') THEN
        RAISE EXCEPTION 'PO % is %, goods can be received only on a CONFIRMED PO', po.po_number, po.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER tg_goods_receipts_check ON procurement.goods_receipts;
CREATE TRIGGER tg_goods_receipts_check
    BEFORE INSERT OR UPDATE OF po_id, po_revision_id, warehouse_id, supplier_id, referenced_po_id, receipt_type,
        production_order_id
    ON procurement.goods_receipts
    FOR EACH ROW EXECUTE FUNCTION procurement.check_goods_receipt();

-- 2. Receipt lines: the production order line each one counts ---------------------------------------------------------
ALTER TABLE procurement.goods_receipt_lines
    ADD COLUMN production_order_line_id UUID;

ALTER TABLE procurement.goods_receipt_lines
    ADD CONSTRAINT fk_goods_receipt_lines_production_order_line FOREIGN KEY (production_order_line_id)
        REFERENCES ordering.production_order_line (id) ON DELETE RESTRICT,
    -- 03 §3: one receipt line per production order line.
    ADD CONSTRAINT uk_goods_receipt_lines_production_order_line UNIQUE (receipt_id, production_order_line_id),
    ADD CONSTRAINT ck_goods_receipt_lines_source CHECK (po_line_id IS NULL OR production_order_line_id IS NULL);

CREATE INDEX ix_goods_receipt_lines_production_order_line ON procurement.goods_receipt_lines (production_order_line_id)
    WHERE production_order_line_id IS NOT NULL;

-- The lines of a blind receipt are told apart by item and lot; those of a production receipt by their production order
-- line (two lines of one SKU share its lot, BR-03).
DROP INDEX procurement.uk_goods_receipt_lines_blind_lot;
CREATE UNIQUE INDEX uk_goods_receipt_lines_blind_lot
    ON procurement.goods_receipt_lines (receipt_id, inventory_item_id, COALESCE(lot_number, ''))
    WHERE po_line_id IS NULL AND production_order_line_id IS NULL;

-- A line of a production receipt is a line of its production order, of that line's SKU, in the production order's lot
-- with no expiry date (BR-03, BR-06, BR-11), in whole cartons of the line's standard count in a packing unit holding
-- exactly that many (BR-12, BR-13). The production order line is locked as a PO line is: two receipts counting it are
-- decided one after the other, so the deferred BR-02 check of the second sees what the first committed.
CREATE OR REPLACE FUNCTION procurement.check_goods_receipt_line() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    receipt    procurement.goods_receipts%ROWTYPE;
    line       procurement.purchase_order_lines%ROWTYPE;
    production RECORD;
BEGIN
    SELECT * INTO receipt FROM procurement.goods_receipts WHERE id = NEW.receipt_id;
    IF (NEW.production_order_line_id IS NULL) <> (receipt.production_order_id IS NULL) THEN
        RAISE EXCEPTION 'a receipt line names a production order line exactly when its receipt is against a production order'
            USING ERRCODE = 'check_violation';
    END IF;
    IF (NEW.po_line_id IS NULL) <> (receipt.po_id IS NULL) THEN
        RAISE EXCEPTION 'a receipt line has a PO line exactly when its receipt is against a PO'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.po_line_id IS NOT NULL THEN
        SELECT * INTO line FROM procurement.purchase_order_lines WHERE id = NEW.po_line_id FOR UPDATE;
        IF line.po_id IS DISTINCT FROM receipt.po_id THEN
            RAISE EXCEPTION 'receipt line is for a line of another PO' USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.inventory_item_id IS DISTINCT FROM line.inventory_item_id THEN
            RAISE EXCEPTION 'received item differs from the ordered item of PO line %', line.line_no
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF NEW.production_order_line_id IS NOT NULL THEN
        SELECT l.production_order_id, l.sku, l.units_per_carton, p.code INTO production
          FROM ordering.production_order_line l
          JOIN ordering.production_order p ON p.id = l.production_order_id
         WHERE l.id = NEW.production_order_line_id
           FOR UPDATE OF l;
        IF NOT FOUND THEN
            RETURN NEW; -- fk_goods_receipt_lines_production_order_line reports it
        END IF;
        IF production.production_order_id IS DISTINCT FROM receipt.production_order_id THEN
            RAISE EXCEPTION 'receipt line is for a line of another production order (03 BR-01)'
                USING ERRCODE = 'check_violation';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM inventory.inventory_items i
                        WHERE i.id = NEW.inventory_item_id AND i.sku = production.sku) THEN
            RAISE EXCEPTION 'received item differs from SKU % of the production order line (03 BR-11)', production.sku
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.lot_number IS DISTINCT FROM production.code OR NEW.expiry_date IS NOT NULL THEN
            RAISE EXCEPTION 'receipt line: the lot is production order %''s code, with no expiry date (03 BR-03, BR-06)',
                production.code USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.received_qty % production.units_per_carton <> 0 THEN
            RAISE EXCEPTION 'receipt line: % units are not whole cartons of % (03 BR-12, BR-13)',
                trim_scale(NEW.received_qty), production.units_per_carton USING ERRCODE = 'check_violation';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM inventory.uom_conversions u
                        WHERE u.id = NEW.uom_conversion_id AND NOT u.is_base
                          AND u.factor = production.units_per_carton) THEN
            RAISE EXCEPTION 'receipt line: counted in cartons holding the line''s % units (03 BR-12, BR-13)',
                production.units_per_carton USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM warehouse.storage_location s
                    WHERE s.id = NEW.location_id AND s.warehouse_id = receipt.warehouse_id) THEN
        RAISE EXCEPTION 'receipt location is not in the receiving warehouse' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

-- A posted receipt's lines keep what was counted (BR-05), the production order line among it.
CREATE OR REPLACE FUNCTION procurement.check_goods_receipt_line_frozen() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    receipt_status VARCHAR(16);
BEGIN
    SELECT status INTO receipt_status FROM procurement.goods_receipts
     WHERE id = COALESCE(NEW.receipt_id, OLD.receipt_id);
    IF receipt_status IS NULL OR receipt_status IN ('DRAFT', 'PENDING_APPROVAL') THEN
        RETURN COALESCE(NEW, OLD);
    END IF;
    IF TG_OP = 'UPDATE'
       AND NEW.receipt_id = OLD.receipt_id AND NEW.po_line_id IS NOT DISTINCT FROM OLD.po_line_id
       AND NEW.production_order_line_id IS NOT DISTINCT FROM OLD.production_order_line_id
       AND NEW.inventory_item_id = OLD.inventory_item_id AND NEW.location_id = OLD.location_id
       AND NEW.received_qty = OLD.received_qty
       AND NEW.lot_number IS NOT DISTINCT FROM OLD.lot_number
       AND NEW.expiry_date IS NOT DISTINCT FROM OLD.expiry_date
       AND NEW.uom_conversion_id IS NOT DISTINCT FROM OLD.uom_conversion_id THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'receipt is %: its lines keep what was counted (BR-05)', receipt_status
        USING ERRCODE = 'check_violation';
END
$$;

-- 03 BR-02, at commit: the cartons of a production order line on every receipt not cancelled - drafts included - are
-- never more than the line orders. Deferred like the purchase tolerance (V20261029002000): the totals are judged once
-- a count is complete, and GoodsReceiptRepositoryAdapter.save runs it IMMEDIATE after each save, so a refusal is a
-- 409 from the save rather than a failed commit.
CREATE FUNCTION procurement.check_production_receipt_cartons() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    line     RECORD;
    received NUMERIC;
BEGIN
    SELECT l.cartons, l.units_per_carton, l.sku, p.code INTO line
      FROM ordering.production_order_line l
      JOIN ordering.production_order p ON p.id = l.production_order_id
     WHERE l.id = NEW.production_order_line_id;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    SELECT COALESCE(SUM(g.received_qty), 0) INTO received
      FROM procurement.goods_receipt_lines g
      JOIN procurement.goods_receipts r ON r.id = g.receipt_id
     WHERE g.production_order_line_id = NEW.production_order_line_id AND r.status <> 'CANCELLED';
    IF received > line.cartons * line.units_per_carton THEN
        RAISE EXCEPTION 'production order % line %: % carton(s) on receipts not cancelled, % ordered (03 BR-02)',
            line.code, line.sku, trunc(received / line.units_per_carton), line.cartons
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_goods_receipt_lines_production_cartons
    AFTER INSERT OR UPDATE OF received_qty, production_order_line_id ON procurement.goods_receipt_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.production_order_line_id IS NOT NULL)
    EXECUTE FUNCTION procurement.check_production_receipt_cartons();

-- 3. The receipts each production order has open (03 edge cases: a production order with a draft receipt is neither
--    cancelled nor closed). Kept by the order module; the receipt itself is procurement's.
CREATE TABLE ordering.production_order_receipt
(
    production_order_id UUID        NOT NULL,
    goods_receipt_id    UUID        NOT NULL,
    receipt_number      VARCHAR(30) NOT NULL,
    -- DRAFT while it is counted; CONFIRMED once its cartons are counted in; CANCELLED when the draft was cancelled.
    status              VARCHAR(16) NOT NULL,
    opened_at           TIMESTAMPTZ NOT NULL,
    settled_at          TIMESTAMPTZ,

    CONSTRAINT pk_production_order_receipt PRIMARY KEY (production_order_id, goods_receipt_id),
    CONSTRAINT uk_production_order_receipt_receipt UNIQUE (goods_receipt_id),
    CONSTRAINT fk_production_order_receipt_order FOREIGN KEY (production_order_id)
        REFERENCES ordering.production_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_production_order_receipt_receipt FOREIGN KEY (goods_receipt_id)
        REFERENCES procurement.goods_receipts (id) ON DELETE RESTRICT,
    CONSTRAINT ck_production_order_receipt_status CHECK (status IN ('DRAFT', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT ck_production_order_receipt_settled CHECK ((status = 'DRAFT') = (settled_at IS NULL))
);

COMMENT ON TABLE ordering.production_order_receipt IS
    'The goods receipts against each production order and where each stands (kltn-docs 03 edge cases): a draft keeps '
    'the production order from being cancelled or closed; a confirmed one has counted its cartons in, once.';

-- Registered as a draft of a receipt against that production order; settled once - confirmed or cancelled - and as its
-- receipt stands; never removed.
CREATE FUNCTION ordering.check_production_order_receipt() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    receipt_status VARCHAR(16);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'production order receipt %: kept', OLD.receipt_number USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'production order receipt %: registered as a draft', NEW.receipt_number
                USING ERRCODE = 'check_violation';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM procurement.goods_receipts g
                        WHERE g.id = NEW.goods_receipt_id AND g.production_order_id = NEW.production_order_id
                          AND g.receipt_number = NEW.receipt_number) THEN
            RAISE EXCEPTION 'receipt % is not a receipt against this production order', NEW.receipt_number
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.production_order_id IS DISTINCT FROM OLD.production_order_id
        OR NEW.goods_receipt_id IS DISTINCT FROM OLD.goods_receipt_id
        OR NEW.receipt_number IS DISTINCT FROM OLD.receipt_number OR NEW.opened_at IS DISTINCT FROM OLD.opened_at THEN
        RAISE EXCEPTION 'production order receipt %: what it registers does not change', OLD.receipt_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'production order receipt %: settled once, % already', OLD.receipt_number, OLD.status
                USING ERRCODE = 'check_violation';
        END IF;
        SELECT g.status INTO receipt_status FROM procurement.goods_receipts g WHERE g.id = NEW.goods_receipt_id;
        IF (NEW.status = 'CANCELLED') <> (receipt_status = 'CANCELLED')
            OR receipt_status IN ('DRAFT', 'PENDING_APPROVAL') THEN
            RAISE EXCEPTION 'production order receipt %: % while the receipt is %', OLD.receipt_number, NEW.status,
                receipt_status USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_production_order_receipt
    BEFORE INSERT OR UPDATE OR DELETE ON ordering.production_order_receipt
    FOR EACH ROW EXECUTE FUNCTION ordering.check_production_order_receipt();

-- At commit, a production receipt is registered with its production order as it stands: a draft as a draft, a
-- cancelled one as cancelled, a posted one as confirmed. So the registry the order module decides on never drifts from
-- the receipts.
CREATE FUNCTION procurement.check_production_receipt_registered() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    expected VARCHAR(16);
BEGIN
    IF NEW.status = 'DRAFT' THEN
        expected := 'DRAFT';
    ELSIF NEW.status = 'CANCELLED' THEN
        expected := 'CANCELLED';
    ELSE
        expected := 'CONFIRMED';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ordering.production_order_receipt r
                    WHERE r.goods_receipt_id = NEW.id AND r.production_order_id = NEW.production_order_id
                      AND r.status = expected) THEN
        RAISE EXCEPTION 'receipt % is % but not registered % with its production order', NEW.receipt_number,
            NEW.status, expected USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_goods_receipts_production_registered
    AFTER INSERT OR UPDATE OF status ON procurement.goods_receipts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.production_order_id IS NOT NULL)
    EXECUTE FUNCTION procurement.check_production_receipt_registered();

-- 03 edge cases: a production order is neither cancelled nor closed while a receipt against it is a draft.
CREATE FUNCTION ordering.check_production_order_draft_receipts() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    drafts TEXT;
BEGIN
    SELECT string_agg(r.receipt_number, ', ' ORDER BY r.receipt_number) INTO drafts
      FROM ordering.production_order_receipt r
     WHERE r.production_order_id = NEW.id AND r.status = 'DRAFT';
    IF drafts IS NOT NULL THEN
        RAISE EXCEPTION 'production order %: draft receipt(s) % are confirmed or cancelled first (03 edge cases)',
            NEW.code, drafts USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_production_order_draft_receipts
    BEFORE UPDATE OF status ON ordering.production_order
    FOR EACH ROW WHEN (NEW.status IN ('CANCELLED', 'CLOSED') AND NEW.status IS DISTINCT FROM OLD.status)
    EXECUTE FUNCTION ordering.check_production_order_draft_receipts();

-- 4. Lots: one per production order and SKU, across its receipts (03 BR-03) -------------------------------------------
ALTER TABLE inventory.stock_lot
    ADD COLUMN production_order_id   UUID,
    ADD COLUMN production_order_code VARCHAR(32);

COMMENT ON COLUMN inventory.stock_lot.production_order_code IS
    'The production order as it read when its first goods were received: also the lot number (kltn-docs 03 BR-03).';

-- A production order lot names no goods receipt: it outlives the first one and gathers the next (the ledger lines name
-- each receipt line), and no purchase order or supplier.
ALTER TABLE inventory.stock_lot
    ADD CONSTRAINT fk_stock_lot_production_order FOREIGN KEY (production_order_id)
        REFERENCES ordering.production_order (id) ON DELETE RESTRICT,
    DROP CONSTRAINT ck_stock_lot_origin,
    ADD CONSTRAINT ck_stock_lot_origin CHECK (origin_type IN ('GOODS_RECEIPT', 'PRODUCTION_ORDER', 'UNTRACED')),
    DROP CONSTRAINT ck_stock_lot_receipt,
    ADD CONSTRAINT ck_stock_lot_receipt CHECK (origin_type <> 'GOODS_RECEIPT'
        OR (goods_receipt_id IS NOT NULL AND received_at IS NOT NULL)),
    ADD CONSTRAINT ck_stock_lot_production_order CHECK ((origin_type = 'PRODUCTION_ORDER') = (production_order_id IS NOT NULL)
        AND (production_order_id IS NULL) = (production_order_code IS NULL)
        AND (origin_type <> 'PRODUCTION_ORDER'
             OR (lot_number = production_order_code AND expiry_date IS NULL AND received_at IS NOT NULL
                 AND goods_receipt_id IS NULL AND goods_receipt_number IS NULL AND purchase_order_id IS NULL
                 AND purchase_order_number IS NULL AND supplier_id IS NULL AND supplier_name IS NULL))),
    ADD CONSTRAINT ck_stock_lot_untraced CHECK (origin_type <> 'UNTRACED'
        OR (goods_receipt_id IS NULL AND purchase_order_id IS NULL AND supplier_id IS NULL));

-- One lot per goods receipt and (SKU, lot number, expiry) stays the rule of purchase receipts only.
DROP INDEX inventory.uk_stock_lot_receipt;
CREATE UNIQUE INDEX uk_stock_lot_receipt
    ON inventory.stock_lot (goods_receipt_id, sku, COALESCE(lot_number, ''), COALESCE(expiry_date, 'infinity'))
    WHERE origin_type = 'GOODS_RECEIPT';
CREATE UNIQUE INDEX uk_stock_lot_production_order ON inventory.stock_lot (production_order_id, sku)
    WHERE production_order_id IS NOT NULL;

-- A production order lot is of a SKU the production order makes, under its code.
CREATE FUNCTION inventory.check_stock_lot_production_order() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ordering.production_order p
                     JOIN ordering.production_order_line l ON l.production_order_id = p.id
                    WHERE p.id = NEW.production_order_id AND p.code = NEW.production_order_code AND l.sku = NEW.sku) THEN
        RAISE EXCEPTION 'stock lot %: production order % makes no %', NEW.id, NEW.production_order_code, NEW.sku
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_stock_lot_production_order
    BEFORE INSERT OR UPDATE OF production_order_id, production_order_code, sku ON inventory.stock_lot
    FOR EACH ROW WHEN (NEW.production_order_id IS NOT NULL)
    EXECUTE FUNCTION inventory.check_stock_lot_production_order();
