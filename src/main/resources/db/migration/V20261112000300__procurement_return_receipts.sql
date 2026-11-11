-- =============================================================================
-- Return receipts: goods that came back, received by LPN straight into quarantine (team plan 4D-2; kltn-docs 03 §1,
-- §3 "Phiếu hàng hoàn", §4.2 steps 9-13, §5, BR-03, BR-04, BR-05, BR-08, BR-14; 17 BR-18; 09 BR-06).
--
-- A RETURN goods receipt is against exactly one returned shipment (RTO: the carrier brought it back, shipment RETURNED)
-- or one approved return request (RMA, AWAITING_RETURN), of one customer order, at the warehouse the goods left from.
-- The warehouse scans the cartons that came back by LPN; only a carton picked for that order is taken, and it keeps its
-- LPN, SKU, count and original lot (BR-03). Confirming puts each carton back into stock - CONSUMED -> IN_STOCK, sealed,
-- at the receipt's QUARANTINE area, its stock QUARANTINE (out of ATP, BR-04), one RECEIPT ledger line per carton naming
-- the receipt (BR-08) - and closes the receipt at once: DRAFT -> CLOSED, no RECEIVING, no putaway task (BR-14). A
-- warehouse manager then releases or writes off each carton (4D-1). Photos of the cartons' condition are the receipt's
-- photos (procurement.goods_receipt_photos), as for any receipt. Each rule names the code that states it first.
--
--   1. goods_receipts.receipt_type gains RETURN. Package 4A-5 (V20261108002800) adds the column with PURCHASE and
--      PRODUCTION; this branch is not on 4A-5, so the column is added IF NOT EXISTS with the same type and default, and
--      ck_goods_receipts_type is dropped IF EXISTS and added again as the union of both packages' values. Either branch
--      may merge first: on a database built with both, 4A-5 runs first (its version is older) and this one only widens
--      the CHECK. GoodsReceiptType.
--   2. A RETURN receipt names its order, its quarantine area, and exactly one source - a returned shipment or a return
--      request - and nothing a purchase or production receipt names (ck_goods_receipts_return_source). Built in a DO
--      block: when 4A-5's production_order_id is there, the CHECK says a RETURN receipt has none; when it is not, the
--      CHECK cannot name it. The purchase columns are in their own CHECK (ck_goods_receipts_return_purchase), so team
--      plan 4A-9, which drops those columns, takes that CHECK with them and leaves the source rule standing.
--      ck_goods_receipts_blind and ck_goods_receipts_blind_approved say "a receipt without a PO is blind" of purchase
--      receipts only, word for word as 4A-5 writes them (so whichever runs last writes the same CHECK).
--      ReturnReceipt.draft.
--   3. A RETURN receipt goes DRAFT -> CLOSED (confirmed and closed in one step) or DRAFT -> CANCELLED, never through
--      putaway (ck_goods_receipts_return_status). ReturnReceipt.confirm / cancel.
--   4. What it is against does not change; it is opened only for a shipment RETURNED or a return request
--      AWAITING_RETURN, of its order, at that order's warehouse, into a usable QUARANTINE area of that warehouse
--      (check_return_receipt). One receipt not cancelled per shipment and per return request
--      (uk_goods_receipts_return_shipment / _request): the goods come back once, and the event a closed receipt
--      publishes is what refunds them. ReturnReceiptService.create (SHIPMENT_NOT_RETURNED, RETURN_NOT_AWAITING_GOODS,
--      RETURN_RECEIPT_ALREADY_OPEN, LOCATION_AREA_MISMATCH).
--   5. procurement.goods_receipt_return_cartons: the cartons scanned on a RETURN receipt, one row per carton, changed
--      only while it is a DRAFT; each is a carton picked for the receipt's order and not put back, named as it was
--      shipped - its LPN, SKU, count and lot (check_return_receipt_carton). The finer rule, a carton of THAT shipment
--      (or one delivered on the order, for a return request), is the service's: the shipment's cartons are read
--      through fulfillment :: api. ReturnReceiptService.scan (RETURN_CARTON_NOT_OF_SOURCE).
--   6. inventory.stock_movement: reference GOODS_RECEIPT (a return receipt; its cartons have no receipt line). Such a
--      line is a RECEIPT into QUARANTINE naming the carton it brings back (ck_stock_movement_return_receipt), once per
--      receipt and carton (uk_stock_movement_return_receipt). CartonReturnService.receive.
--   7. At commit, a CLOSED RETURN receipt has at least one carton and exactly one such ledger line per carton, into its
--      quarantine area (check_return_receipt_closed). ReturnReceiptService.confirm.
--
-- Nothing here changes data: no receipt was a RETURN receipt before.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. receipt_type: PURCHASE | PRODUCTION (4A-5) | RETURN
-- ---------------------------------------------------------------------------------------------
ALTER TABLE procurement.goods_receipts ADD COLUMN IF NOT EXISTS receipt_type VARCHAR(16) NOT NULL DEFAULT 'PURCHASE';

ALTER TABLE procurement.goods_receipts DROP CONSTRAINT IF EXISTS ck_goods_receipts_type;
ALTER TABLE procurement.goods_receipts
    ADD CONSTRAINT ck_goods_receipts_type CHECK (receipt_type IN ('PURCHASE', 'PRODUCTION', 'RETURN'));

COMMENT ON COLUMN procurement.goods_receipts.receipt_type IS
    'PURCHASE: against a purchase order, or none (blind); PRODUCTION: against one production order; RETURN: goods that '
    'came back, against one returned shipment or one return request (kltn-docs 03 §1).';


-- ---------------------------------------------------------------------------------------------
-- 2. What a RETURN receipt is against
-- ---------------------------------------------------------------------------------------------
ALTER TABLE procurement.goods_receipts
    ADD COLUMN customer_order_id      UUID,
    ADD COLUMN return_shipment_id     UUID,
    ADD COLUMN return_request_id      UUID,
    ADD COLUMN quarantine_location_id UUID;

COMMENT ON COLUMN procurement.goods_receipts.customer_order_id IS
    'RETURN: the customer order whose goods came back (the shipment''s or the return request''s order).';
COMMENT ON COLUMN procurement.goods_receipts.return_shipment_id IS
    'RETURN: the shipment the carrier brought back (RTO, kltn-docs 09 BR-06); NULL when against a return request.';
COMMENT ON COLUMN procurement.goods_receipts.return_request_id IS
    'RETURN: the approved return request (RMA, kltn-docs 17 §4.10); NULL when against a returned shipment.';
COMMENT ON COLUMN procurement.goods_receipts.quarantine_location_id IS
    'RETURN: the QUARANTINE area the cartons go into (kltn-docs 03 BR-14).';

ALTER TABLE procurement.goods_receipts
    ADD CONSTRAINT fk_goods_receipts_customer_order FOREIGN KEY (customer_order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_goods_receipts_return_shipment FOREIGN KEY (return_shipment_id)
        REFERENCES fulfillment.shipment (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_goods_receipts_return_request FOREIGN KEY (return_request_id)
        REFERENCES ordering.return_request (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_goods_receipts_quarantine_location FOREIGN KEY (quarantine_location_id)
        REFERENCES warehouse.storage_location (id) ON DELETE RESTRICT;

DO $$
DECLARE
    production BOOLEAN;
BEGIN
    SELECT EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'procurement' AND table_name = 'goods_receipts'
                      AND column_name = 'production_order_id')
      INTO production;
    EXECUTE format($ddl$
        ALTER TABLE procurement.goods_receipts ADD CONSTRAINT ck_goods_receipts_return_source CHECK (CASE
            WHEN receipt_type = 'RETURN'
                THEN customer_order_id IS NOT NULL AND quarantine_location_id IS NOT NULL
                     AND num_nonnulls(return_shipment_id, return_request_id) = 1 %s
            ELSE customer_order_id IS NULL AND quarantine_location_id IS NULL
                 AND return_shipment_id IS NULL AND return_request_id IS NULL
            END)
        $ddl$, CASE WHEN production THEN 'AND production_order_id IS NULL' ELSE '' END);
END
$$;

-- In their own CHECK: team plan 4A-9 drops these columns, and this goes with them.
ALTER TABLE procurement.goods_receipts
    ADD CONSTRAINT ck_goods_receipts_return_purchase CHECK (receipt_type <> 'RETURN'
        OR (po_id IS NULL AND po_revision_id IS NULL AND supplier_id IS NULL AND blind_reason IS NULL
            AND referenced_po_id IS NULL AND approval_requested_at IS NULL AND approval_decision IS NULL));

-- "No purchase order, so blind" holds for purchase receipts only (word for word V20261108002800's).
ALTER TABLE procurement.goods_receipts
    DROP CONSTRAINT ck_goods_receipts_blind,
    ADD CONSTRAINT ck_goods_receipts_blind CHECK (receipt_type <> 'PURCHASE'
        OR (po_id IS NOT NULL AND supplier_id IS NULL AND blind_reason IS NULL AND referenced_po_id IS NULL)
        OR (po_id IS NULL AND supplier_id IS NOT NULL AND blind_reason IS NOT NULL AND btrim(blind_reason) <> '')),
    DROP CONSTRAINT ck_goods_receipts_blind_approved,
    ADD CONSTRAINT ck_goods_receipts_blind_approved CHECK (receipt_type <> 'PURCHASE' OR po_id IS NOT NULL
        OR status IN ('DRAFT', 'PENDING_APPROVAL', 'CANCELLED') OR COALESCE(approval_decision, '') = 'APPROVED');


-- ---------------------------------------------------------------------------------------------
-- 3. DRAFT -> CLOSED | CANCELLED
-- ---------------------------------------------------------------------------------------------
ALTER TABLE procurement.goods_receipts
    ADD CONSTRAINT ck_goods_receipts_return_status CHECK (receipt_type <> 'RETURN'
        OR status IN ('DRAFT', 'CANCELLED')
        OR (status = 'CLOSED' AND confirmed_at IS NOT NULL AND closed_at = confirmed_at));


-- ---------------------------------------------------------------------------------------------
-- 4. Opened only for goods that are coming back, at their warehouse; once per shipment or return request
-- ---------------------------------------------------------------------------------------------
CREATE UNIQUE INDEX uk_goods_receipts_return_shipment ON procurement.goods_receipts (return_shipment_id)
    WHERE return_shipment_id IS NOT NULL AND status <> 'CANCELLED';
CREATE UNIQUE INDEX uk_goods_receipts_return_request ON procurement.goods_receipts (return_request_id)
    WHERE return_request_id IS NOT NULL AND status <> 'CANCELLED';
CREATE INDEX ix_goods_receipts_customer_order ON procurement.goods_receipts (customer_order_id)
    WHERE customer_order_id IS NOT NULL;

CREATE FUNCTION procurement.check_return_receipt() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    source_order     UUID;
    source_status    VARCHAR(32);
    source_warehouse UUID;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.receipt_type IS DISTINCT FROM OLD.receipt_type
            OR NEW.customer_order_id IS DISTINCT FROM OLD.customer_order_id
            OR NEW.return_shipment_id IS DISTINCT FROM OLD.return_shipment_id
            OR NEW.return_request_id IS DISTINCT FROM OLD.return_request_id
            OR NEW.quarantine_location_id IS DISTINCT FROM OLD.quarantine_location_id THEN
            RAISE EXCEPTION 'return receipt %: what it is against and where its goods go do not change',
                OLD.receipt_number USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.return_shipment_id IS NOT NULL THEN
        SELECT s.order_id, s.status, s.warehouse_id INTO source_order, source_status, source_warehouse
          FROM fulfillment.shipment s WHERE s.id = NEW.return_shipment_id;
        IF NOT FOUND THEN
            RETURN NEW; -- fk_goods_receipts_return_shipment reports it
        END IF;
        IF source_status <> 'RETURNED' THEN
            RAISE EXCEPTION 'return receipt %: the shipment is %, not RETURNED (kltn-docs 09 BR-06)',
                NEW.receipt_number, source_status USING ERRCODE = 'check_violation';
        END IF;
    ELSE
        SELECT r.order_id, r.status, o.warehouse_id INTO source_order, source_status, source_warehouse
          FROM ordering.return_request r JOIN ordering.customer_order o ON o.id = r.order_id
         WHERE r.id = NEW.return_request_id;
        IF NOT FOUND THEN
            RETURN NEW; -- fk_goods_receipts_return_request reports it
        END IF;
        IF source_status <> 'AWAITING_RETURN' THEN
            RAISE EXCEPTION 'return receipt %: the return request is %, not approved and awaiting its goods',
                NEW.receipt_number, source_status USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF source_order IS DISTINCT FROM NEW.customer_order_id THEN
        RAISE EXCEPTION 'return receipt %: its order is not the order of the shipment or return request',
            NEW.receipt_number USING ERRCODE = 'check_violation';
    END IF;
    IF source_warehouse IS DISTINCT FROM NEW.warehouse_id THEN
        RAISE EXCEPTION 'return receipt %: goods come back to the warehouse they left from (kltn-docs 03 §4.2)',
            NEW.receipt_number USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM warehouse.storage_location s JOIN warehouse.area a ON a.location_id = s.id
                    WHERE s.id = NEW.quarantine_location_id AND s.warehouse_id = NEW.warehouse_id
                      AND a.type = 'QUARANTINE' AND s.status = 'ACTIVE' AND a.status = 'ACTIVE') THEN
        RAISE EXCEPTION 'return receipt %: goods come back into an active QUARANTINE area of its warehouse (BR-14)',
            NEW.receipt_number USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_goods_receipts_return
    BEFORE INSERT OR UPDATE OF receipt_type, customer_order_id, return_shipment_id, return_request_id,
        quarantine_location_id
    ON procurement.goods_receipts
    FOR EACH ROW WHEN (NEW.receipt_type = 'RETURN' OR NEW.customer_order_id IS NOT NULL)
    EXECUTE FUNCTION procurement.check_return_receipt();


-- ---------------------------------------------------------------------------------------------
-- 5. The cartons scanned on a return receipt
-- ---------------------------------------------------------------------------------------------
CREATE TABLE procurement.goods_receipt_return_cartons
(
    id               UUID         NOT NULL,
    receipt_id       UUID         NOT NULL,
    stock_package_id UUID         NOT NULL,
    lpn              VARCHAR(30)  NOT NULL,
    sku              VARCHAR(64)  NOT NULL,
    lot_number       VARCHAR(64),
    quantity         INTEGER      NOT NULL,
    condition_note   VARCHAR(500),
    scanned_by       UUID         NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_goods_receipt_return_cartons PRIMARY KEY (id),
    CONSTRAINT uk_goods_receipt_return_cartons UNIQUE (receipt_id, stock_package_id),
    CONSTRAINT fk_goods_receipt_return_cartons_receipt FOREIGN KEY (receipt_id)
        REFERENCES procurement.goods_receipts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_goods_receipt_return_cartons_package FOREIGN KEY (stock_package_id)
        REFERENCES inventory.stock_package (id) ON DELETE RESTRICT,
    CONSTRAINT fk_goods_receipt_return_cartons_scanned_by FOREIGN KEY (scanned_by)
        REFERENCES identity.app_user (id),
    CONSTRAINT ck_goods_receipt_return_cartons_quantity CHECK (quantity > 0),
    CONSTRAINT ck_goods_receipt_return_cartons_note CHECK (condition_note IS NULL OR btrim(condition_note) <> '')
);

CREATE INDEX ix_goods_receipt_return_cartons_package ON procurement.goods_receipt_return_cartons (stock_package_id);

COMMENT ON TABLE procurement.goods_receipt_return_cartons IS
    'The cartons scanned on a RETURN goods receipt (kltn-docs 03 §4.2 step 10): each keeps the LPN, SKU, count and lot '
    'it was shipped with (BR-03, BR-14); condition_note is what the warehouse saw of its outside (dented, wet, opened). '
    'Scanned (created_at) and removed only while the receipt is a DRAFT.';

-- A carton of the receipt's order, as it was shipped; the receipt still a draft.
CREATE FUNCTION procurement.check_return_receipt_carton() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    receipt procurement.goods_receipts%ROWTYPE;
    pkg     RECORD;
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
    IF TG_OP = 'UPDATE' AND (NEW.receipt_id IS DISTINCT FROM OLD.receipt_id
        OR NEW.stock_package_id IS DISTINCT FROM OLD.stock_package_id OR NEW.lpn IS DISTINCT FROM OLD.lpn
        OR NEW.sku IS DISTINCT FROM OLD.sku OR NEW.lot_number IS DISTINCT FROM OLD.lot_number
        OR NEW.quantity IS DISTINCT FROM OLD.quantity OR NEW.scanned_by IS DISTINCT FROM OLD.scanned_by) THEN
        RAISE EXCEPTION 'return receipt %: only the condition of a scanned carton is changed', receipt.receipt_number
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT p.lpn, i.sku, p.received_qty INTO pkg
      FROM inventory.stock_package p JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
     WHERE p.id = NEW.stock_package_id;
    IF NOT FOUND THEN
        RETURN NEW; -- fk_goods_receipt_return_cartons_package reports it
    END IF;
    IF pkg.lpn <> NEW.lpn OR pkg.sku <> NEW.sku OR pkg.received_qty IS DISTINCT FROM NEW.quantity
       OR NOT EXISTS (SELECT 1 FROM inventory.stock_item s
                       WHERE s.stock_package_id = NEW.stock_package_id
                         AND s.lot_number IS NOT DISTINCT FROM NEW.lot_number) THEN
        RAISE EXCEPTION 'return receipt %: carton % is named as it was shipped - its LPN, SKU, count and lot (BR-03)',
            receipt.receipt_number, NEW.lpn USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM fulfillment.pick_line l JOIN fulfillment.pick k ON k.id = l.pick_id
                    WHERE k.order_id = receipt.customer_order_id AND l.lpn = NEW.lpn
                      AND l.picked_qty > l.put_back_qty) THEN
        RAISE EXCEPTION 'return receipt %: carton % was not shipped on its order (BR-14)', receipt.receipt_number,
            NEW.lpn USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_goods_receipt_return_cartons
    BEFORE INSERT OR UPDATE OR DELETE ON procurement.goods_receipt_return_cartons
    FOR EACH ROW EXECUTE FUNCTION procurement.check_return_receipt_carton();


-- ---------------------------------------------------------------------------------------------
-- 6. The ledger line of a carton received back
-- ---------------------------------------------------------------------------------------------
ALTER TABLE inventory.stock_movement DROP CONSTRAINT ck_stock_movement_reference;
ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_reference CHECK (reference_type IN
    ('GOODS_RECEIPT_LINE', 'QC_INSPECTION', 'PUTAWAY_TASK', 'PICK_LINE', 'PACKAGE', 'MOVE_TASK', 'TRANSFER_ORDER_LINE',
     'STOCK_ADJUSTMENT', 'CYCLE_COUNT_LINE', 'RETURN_REQUEST_LINE', 'ORDER', 'PRODUCTION_ORDER', 'EXPIRY_SWEEP',
     'DAMAGE_REPORT', 'STOCK_PACKAGE', 'GOODS_RECEIPT'));

ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_return_receipt CHECK (
    reference_type <> 'GOODS_RECEIPT'
    OR (movement_type = 'RECEIPT' AND to_stock_package_id IS NOT NULL AND to_status = 'QUARANTINE'));
COMMENT ON CONSTRAINT ck_stock_movement_return_receipt ON inventory.stock_movement IS
    'Kltn-docs 03 BR-14: a return receipt brings each carton back whole into QUARANTINE, one RECEIPT line per carton.';

CREATE UNIQUE INDEX uk_stock_movement_return_receipt ON inventory.stock_movement (reference_id, to_stock_package_id)
    WHERE reference_type = 'GOODS_RECEIPT';


-- ---------------------------------------------------------------------------------------------
-- 7. A closed return receipt received every carton it lists, and nothing else
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION procurement.check_return_receipt_closed() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    area_code VARCHAR(64);
    cartons   BIGINT;
    received  BIGINT;
    missing   TEXT;
BEGIN
    SELECT location_code INTO area_code FROM warehouse.storage_location WHERE id = NEW.quarantine_location_id;
    SELECT count(*) INTO cartons FROM procurement.goods_receipt_return_cartons WHERE receipt_id = NEW.id;
    IF cartons = 0 THEN
        RAISE EXCEPTION 'return receipt % is closed with no carton', NEW.receipt_number
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT string_agg(c.lpn, ', ' ORDER BY c.lpn) INTO missing
      FROM procurement.goods_receipt_return_cartons c
     WHERE c.receipt_id = NEW.id
       AND NOT EXISTS (SELECT 1 FROM inventory.stock_movement m
                        WHERE m.reference_type = 'GOODS_RECEIPT' AND m.reference_id = NEW.id
                          AND m.to_stock_package_id = c.stock_package_id AND m.to_location_code = area_code);
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'return receipt % is closed but carton(s) % were not received into %', NEW.receipt_number,
            missing, area_code USING ERRCODE = 'check_violation';
    END IF;
    SELECT count(*) INTO received FROM inventory.stock_movement
     WHERE reference_type = 'GOODS_RECEIPT' AND reference_id = NEW.id;
    IF received <> cartons THEN
        RAISE EXCEPTION 'return receipt % lists % carton(s) but received %', NEW.receipt_number, cartons, received
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_goods_receipts_return_closed
    AFTER UPDATE OF status ON procurement.goods_receipts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.receipt_type = 'RETURN' AND NEW.status = 'CLOSED' AND OLD.status IS DISTINCT FROM 'CLOSED')
    EXECUTE FUNCTION procurement.check_return_receipt_closed();
