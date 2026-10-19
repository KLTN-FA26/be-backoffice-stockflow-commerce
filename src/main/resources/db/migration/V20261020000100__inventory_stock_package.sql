-- =============================================================================
-- Stock package: one physical carton received from a supplier, labelled with its own LPN.
-- Module: inventory   Issue: GitHub #125, Jira SCRUM-473
--
-- Goods arrive, are put away and are counted by the carton, and a carton does not always hold what
-- its packing unit says (inventory.uom_conversions, V20261019000300): a supplier ships 980 in a
-- carton of 1,000. The warehouse needs to know, for every stock package, where it is and how much is
-- left in it. Stock is still counted in the base unit; a stock package is the box the stock sits in:
--
--   * the stock package says WHICH carton (lpn), of WHICH packing unit, and WHERE it is;
--   * its stock rows (inventory.stock_item.stock_package_id, V20261020000200) say how much is in
--     it. What is left is the sum of their on_hand, as a package's quants in Odoo or a handling
--     unit's quants in SAP EWM. Usually one row; more for a serial-tracked item (a row per serial)
--     or when stock of another receipt time was put back into it. A QC disposition moves stock to
--     another location (StockOperationsServiceImpl.relocate refuses from = to), so the part QC
--     quarantines leaves the stock package; an expiry block (ExpirySweeper) changes the status in
--     place and the stock stays in it.
--
-- "Stock package", not "package": fulfillment.package is the parcel shipped to a customer, with its
-- own SEALED status. This one is "unopened", not "sealed", for the same reason.
--
-- No sku or lot column: the SKU comes from the item, the lot from the stock rows, so neither is
-- stored twice. location_code IS stored, because scanning a label must answer "where is this
-- carton" without reading its rows; V20261020000200 keeps it equal to the rows' location.
--
-- Single-column foreign keys, and a trigger for "the unit is a unit of this item" and "the receipt
-- line received this item" (ADR-0007 §4: composite keys were rejected by the team).
--
-- Status is the stock package's life, unopened is whether it is still closed; they are separate
-- because one in stock may be opened or not. Whether it is held for an order is not stored: that is
-- inventory.stock_reservation, and a second copy would drift.
--
--   RECEIVING ──▶ IN_STOCK ──▶ CONSUMED       (emptied by picks, issues, scrap)
--       │            │  ▲
--       ▼            ▼  │
--     VOID          LOST                       (VOID: its receipt was cancelled before posting;
--                                               LOST: a count did not find it, and may find it later)
--
-- A stock package is created RECEIVING (from a receipt) or IN_STOCK (repacked in the warehouse);
-- every other status is reached through a transition.
--
-- Not yet: transfers between warehouses (TransferOrderServiceImpl.dispatch takes the stock out of
-- every warehouse until receipt, and this lifecycle has no IN_TRANSIT), pallets and nested packages.
-- Each is a status value or a nullable column added when its flow is built.
--
-- Lock order for code that touches a stock package (the deferred checks of V20261020000200 read
-- without locking, so this is what makes them safe): the SKU advisory lock, then the stock package
-- row FOR UPDATE, then its stock rows in id order.
-- =============================================================================


CREATE TABLE inventory.stock_package
(
    id                    UUID         NOT NULL,
    -- The number printed on the label, drawn from platform.document_sequence (type 'LPN').
    -- Unique across warehouses: two labels never collide, whatever warehouse prints them.
    lpn                   VARCHAR(30)  NOT NULL,
    inventory_item_id     UUID         NOT NULL,
    uom_conversion_id     UUID         NOT NULL,
    -- The unit's factor when the stock package was made: what it should hold.
    nominal_qty           INTEGER      NOT NULL,
    -- What was counted into it at receipt. NULL until counted; fixed once it is in stock. Compared
    -- with nominal_qty it is the supplier's shortfall or excess.
    received_qty          INTEGER,
    location_code         VARCHAR(64)  NOT NULL,
    status                VARCHAR(16)  NOT NULL DEFAULT 'RECEIVING',
    -- Still closed as it arrived. An unopened stock package in stock holds exactly received_qty
    -- (checked in V20261020000200), which is what lets a cycle count confirm it without opening it.
    unopened              BOOLEAN      NOT NULL DEFAULT TRUE,
    -- The receipt line it arrived on. NULL for one made in the warehouse (repacking).
    goods_receipt_line_id UUID,
    closed_at             TIMESTAMPTZ,

    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by            VARCHAR(100),
    last_modified_at      TIMESTAMPTZ,
    last_modified_by      VARCHAR(100),

    CONSTRAINT pk_stock_package PRIMARY KEY (id),
    CONSTRAINT uk_stock_package_lpn UNIQUE (lpn),
    CONSTRAINT fk_stock_package_item FOREIGN KEY (inventory_item_id)
        REFERENCES inventory.inventory_items (id) ON DELETE RESTRICT,
    -- RESTRICT: a unit a stock package was made in is not deleted (docs 01 BR-10: retire it instead).
    CONSTRAINT fk_stock_package_unit FOREIGN KEY (uom_conversion_id)
        REFERENCES inventory.uom_conversions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_package_location FOREIGN KEY (location_code)
        REFERENCES warehouse.storage_location (location_code) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_package_receipt_line FOREIGN KEY (goods_receipt_line_id)
        REFERENCES procurement.goods_receipt_lines (id) ON DELETE RESTRICT,
    CONSTRAINT ck_stock_package_lpn CHECK (lpn ~ '^[A-Z0-9][A-Z0-9-]{3,29}$'),
    CONSTRAINT ck_stock_package_nominal CHECK (nominal_qty > 0),
    CONSTRAINT ck_stock_package_received CHECK (received_qty IS NULL OR received_qty >= 0),
    CONSTRAINT ck_stock_package_status
        CHECK (status IN ('RECEIVING', 'IN_STOCK', 'CONSUMED', 'LOST', 'VOID')),
    -- In stock means counted in.
    CONSTRAINT ck_stock_package_counted CHECK (status IN ('RECEIVING', 'VOID') OR received_qty IS NOT NULL),
    CONSTRAINT ck_stock_package_closed
        CHECK ((status IN ('CONSUMED', 'LOST', 'VOID')) = (closed_at IS NOT NULL)),
    -- An emptied one was opened, and a lost one is unknown: neither can be trusted as unopened.
    CONSTRAINT ck_stock_package_unopened CHECK (NOT unopened OR status IN ('RECEIVING', 'IN_STOCK', 'VOID'))
);

CREATE INDEX ix_stock_package_location ON inventory.stock_package (location_code);
CREATE INDEX ix_stock_package_item ON inventory.stock_package (inventory_item_id);
CREATE INDEX ix_stock_package_unit ON inventory.stock_package (uom_conversion_id);
CREATE INDEX ix_stock_package_receipt_line ON inventory.stock_package (goods_receipt_line_id)
    WHERE goods_receipt_line_id IS NOT NULL;

COMMENT ON TABLE inventory.stock_package IS
    'One physical carton with its own LPN. Its contents are the stock_item rows that point at it.';
COMMENT ON COLUMN inventory.stock_package.lpn IS 'License plate number printed on the label; unique everywhere.';
COMMENT ON COLUMN inventory.stock_package.nominal_qty IS 'The packing unit''s factor when it was made.';
COMMENT ON COLUMN inventory.stock_package.received_qty IS 'Counted into it at receipt; fixed once in stock.';
COMMENT ON COLUMN inventory.stock_package.location_code IS 'Where it is; always where its stock rows are.';
COMMENT ON COLUMN inventory.stock_package.unopened IS 'Still closed as received: then it holds exactly received_qty.';


-- What identifies a stock package is not changed once set. forbid_update_of lets a NULL become a
-- value, so a repacked one may still be tied to a receipt line later. received_qty is left out: it
-- is counted and recounted while the receipt is a draft, and frozen by the transition check below.
CREATE TRIGGER tg_stock_package_immutable BEFORE UPDATE ON inventory.stock_package
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of(
        'lpn', 'inventory_item_id', 'uom_conversion_id', 'nominal_qty', 'goods_receipt_line_id');


-- At creation: made in an active packing unit of its own item, never in the base unit (a "carton"
-- of one is the item itself); nominal_qty is that unit's factor; a receipt line, when named,
-- received this item; and it starts RECEIVING or IN_STOCK.
CREATE FUNCTION inventory.check_stock_package_created()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    unit inventory.uom_conversions%ROWTYPE;
BEGIN
    IF NEW.status NOT IN ('RECEIVING', 'IN_STOCK') THEN
        RAISE EXCEPTION 'stock package % is created RECEIVING or IN_STOCK, not %', NEW.lpn, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT * INTO unit FROM inventory.uom_conversions WHERE id = NEW.uom_conversion_id;
    IF NOT FOUND THEN
        RETURN NEW; -- fk_stock_package_unit reports it
    END IF;
    IF unit.inventory_item_id <> NEW.inventory_item_id THEN
        RAISE EXCEPTION 'stock package %: unit % is not a unit of its item', NEW.lpn, unit.unit_of_measure
            USING ERRCODE = 'check_violation';
    END IF;
    IF unit.is_base OR NOT unit.active THEN
        RAISE EXCEPTION 'stock package % must be made in an active packing unit, not % (%)', NEW.lpn,
            unit.unit_of_measure, CASE WHEN unit.is_base THEN 'the base unit' ELSE 'retired' END
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.nominal_qty <> unit.factor THEN
        RAISE EXCEPTION 'stock package % holds % per %, not %', NEW.lpn, unit.factor, unit.unit_of_measure,
            NEW.nominal_qty USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_stock_package_created BEFORE INSERT ON inventory.stock_package
    FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_package_created();


-- The receipt line received this stock package's item (on insert, or when a repacked one is tied
-- to a line later).
CREATE FUNCTION inventory.check_stock_package_receipt_line()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM procurement.goods_receipt_lines
                    WHERE id = NEW.goods_receipt_line_id AND inventory_item_id = NEW.inventory_item_id) THEN
        RAISE EXCEPTION 'stock package % names a receipt line of another item', NEW.lpn
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_stock_package_receipt_line BEFORE INSERT OR UPDATE OF goods_receipt_line_id
    ON inventory.stock_package
    FOR EACH ROW WHEN (NEW.goods_receipt_line_id IS NOT NULL)
    EXECUTE FUNCTION inventory.check_stock_package_receipt_line();


-- The lifecycle drawn at the top. received_qty changes only while receiving.
CREATE FUNCTION inventory.check_stock_package_transition()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
           (OLD.status = 'RECEIVING' AND NEW.status IN ('IN_STOCK', 'VOID'))
        OR (OLD.status = 'IN_STOCK' AND NEW.status IN ('CONSUMED', 'LOST'))
        OR (OLD.status = 'LOST' AND NEW.status = 'IN_STOCK')) THEN
        RAISE EXCEPTION 'stock package % cannot go from % to %', NEW.lpn, OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.received_qty IS DISTINCT FROM OLD.received_qty AND OLD.status <> 'RECEIVING' THEN
        RAISE EXCEPTION 'stock package % was counted in at %; a later difference is an adjustment', NEW.lpn,
            OLD.received_qty USING ERRCODE = 'check_violation';
    END IF;
    -- Once opened, it stays open.
    IF NEW.unopened AND NOT OLD.unopened THEN
        RAISE EXCEPTION 'stock package % was opened; it cannot be unopened again', NEW.lpn
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_stock_package_transition BEFORE UPDATE OF status, received_qty, unopened
    ON inventory.stock_package
    FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_package_transition();


-- A stock package is a document that uses its item: an item with one is neither renamed nor deleted.
-- The body is V20261017001200's, unchanged, with the stock package line added.
CREATE OR REPLACE FUNCTION inventory.item_in_use(p_item_id UUID, p_sku VARCHAR) RETURNS BOOLEAN
    LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM inventory.stock_item WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_movement WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_adjustment WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.cycle_count_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.transfer_order_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_package WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM warehouse.putaway_task WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM warehouse.move_task WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM fulfillment.pick_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM catalog.pricing_rule WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM procurement.supplier_items WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.replenishment_proposal_lines WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.purchase_order_lines WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.purchase_order_line_revisions WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.goods_receipt_lines WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM catalog.customer_price WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM ordering.quote_line WHERE blank_sku = p_sku)
        OR EXISTS (SELECT 1 FROM production.production_order WHERE blank_sku = p_sku)
        OR EXISTS (SELECT 1 FROM production.sample_request WHERE blank_sku = p_sku)
        OR EXISTS (SELECT 1 FROM ordering.approved_sample WHERE blank_sku = p_sku)
        OR EXISTS (SELECT 1 FROM production.production_material_issue WHERE sku = p_sku)
$$;
