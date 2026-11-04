-- =============================================================================
-- Inventory item: the warehouse's view of a SKU - EXPAND.
-- Module: inventory   Plan: docs/business-design/db-design/2026-09-28-migration-plan.md, phase I
--
-- docs 01: every SKU has exactly one inventory item (BR-07), which carries what the warehouse and
-- purchasing need and nothing a shopper sees (BR-08): unit of measure, barcode, physical size and
-- weight, packaging, storage class, lot/expiry/serial tracking, stock thresholds, default
-- supplier. A receipt cannot be posted before these are filled (BR-03, trigger below).
--
-- Keyed to the variant by sku (string), the same key inventory.stock_item and the scanners use.
-- stock_item.sku and putaway_task.sku get their foreign key here in contract C3, once every
-- existing SKU has an inventory item (the local demo data gets one in the demo seed).
--
-- package_count is not in DBML v9 but is in the live product.product (SCRUM-68): furniture often
-- ships as several boxes per unit, and dropping it would lose a field the team already built.
-- =============================================================================


CREATE TABLE inventory.inventory_items
(
    id                        UUID           NOT NULL,
    sku                       VARCHAR(64)    NOT NULL,
    unit_of_measure           VARCHAR(16)    NOT NULL DEFAULT 'EACH',
    barcode                   VARCHAR(64),
    weight_kg                 NUMERIC(10, 3),
    length_cm                 NUMERIC(10, 2),
    width_cm                  NUMERIC(10, 2),
    height_cm                 NUMERIC(10, 2),
    package_weight_kg         NUMERIC(10, 3),
    package_length_cm         NUMERIC(10, 2),
    package_width_cm          NUMERIC(10, 2),
    package_height_cm         NUMERIC(10, 2),
    package_count             INTEGER        NOT NULL DEFAULT 1,
    pack_size                 INTEGER        NOT NULL DEFAULT 1,
    storage_class             VARCHAR(32)    NOT NULL DEFAULT 'NORMAL',
    lot_tracked               BOOLEAN        NOT NULL DEFAULT FALSE,
    expiry_tracked            BOOLEAN        NOT NULL DEFAULT FALSE,
    serial_tracked            BOOLEAN        NOT NULL DEFAULT FALSE,
    min_qty                   INTEGER,
    max_qty                   INTEGER,
    reorder_point             INTEGER,
    default_supplier_id       UUID,
    requires_adult_signature  BOOLEAN        NOT NULL DEFAULT FALSE,
    shipping_restriction_note VARCHAR(500),

    version                   BIGINT         NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by                VARCHAR(100),
    last_modified_at          TIMESTAMPTZ,
    last_modified_by          VARCHAR(100),

    CONSTRAINT pk_inventory_items PRIMARY KEY (id),
    CONSTRAINT uk_inventory_items_sku UNIQUE (sku),
    CONSTRAINT fk_inventory_items_variant FOREIGN KEY (sku)
        REFERENCES product.variants (sku) ON DELETE RESTRICT,
    CONSTRAINT fk_inventory_items_default_supplier FOREIGN KEY (default_supplier_id)
        REFERENCES procurement.suppliers (id) ON DELETE SET NULL,
    CONSTRAINT ck_inventory_items_uom CHECK (unit_of_measure ~ '^[A-Z0-9_]{1,16}$'),
    CONSTRAINT ck_inventory_items_weight CHECK (weight_kg IS NULL OR weight_kg > 0),
    CONSTRAINT ck_inventory_items_size CHECK ((length_cm IS NULL OR length_cm > 0)
        AND (width_cm IS NULL OR width_cm > 0) AND (height_cm IS NULL OR height_cm > 0)),
    CONSTRAINT ck_inventory_items_package_weight
        CHECK (package_weight_kg IS NULL OR package_weight_kg > 0),
    CONSTRAINT ck_inventory_items_package_size CHECK ((package_length_cm IS NULL OR package_length_cm > 0)
        AND (package_width_cm IS NULL OR package_width_cm > 0)
        AND (package_height_cm IS NULL OR package_height_cm > 0)),
    CONSTRAINT ck_inventory_items_package_count CHECK (package_count >= 1),
    CONSTRAINT ck_inventory_items_pack_size CHECK (pack_size >= 1),
    CONSTRAINT ck_inventory_items_storage_class
        CHECK (storage_class IN ('NORMAL', 'COLD', 'HAZMAT', 'FRAGILE', 'OVERSIZE')),
    -- An expiry date is per lot, so tracking expiry without lots has nothing to hang it on.
    CONSTRAINT ck_inventory_items_expiry_needs_lot CHECK (NOT expiry_tracked OR lot_tracked),
    CONSTRAINT ck_inventory_items_thresholds CHECK ((min_qty IS NULL OR min_qty >= 0)
        AND (max_qty IS NULL OR max_qty >= 0) AND (reorder_point IS NULL OR reorder_point >= 0)
        AND (min_qty IS NULL OR max_qty IS NULL OR max_qty >= min_qty))
);

CREATE UNIQUE INDEX uk_inventory_items_barcode ON inventory.inventory_items (barcode) WHERE barcode IS NOT NULL;
CREATE INDEX ix_inventory_items_default_supplier ON inventory.inventory_items (default_supplier_id);

-- The sku is the join key to the variant, stock and every scanner label.
CREATE TRIGGER tg_inventory_items_immutable BEFORE UPDATE ON inventory.inventory_items
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('sku');


-- -----------------------------------------------------------------------------
-- Purchasing documents record the inventory item (declared in V20260928002000..002300).
-- -----------------------------------------------------------------------------
ALTER TABLE procurement.supplier_items
    ADD CONSTRAINT fk_supplier_items_item FOREIGN KEY (inventory_item_id)
        REFERENCES inventory.inventory_items (id) ON DELETE RESTRICT;
ALTER TABLE procurement.replenishment_proposal_lines
    ADD CONSTRAINT fk_replenishment_proposal_lines_item FOREIGN KEY (inventory_item_id)
        REFERENCES inventory.inventory_items (id) ON DELETE RESTRICT;
ALTER TABLE procurement.purchase_order_lines
    ADD CONSTRAINT fk_purchase_order_lines_item FOREIGN KEY (inventory_item_id)
        REFERENCES inventory.inventory_items (id) ON DELETE RESTRICT;
ALTER TABLE procurement.purchase_order_line_revisions
    ADD CONSTRAINT fk_purchase_order_line_revisions_item FOREIGN KEY (inventory_item_id)
        REFERENCES inventory.inventory_items (id) ON DELETE RESTRICT;
ALTER TABLE procurement.goods_receipt_lines
    ADD CONSTRAINT fk_goods_receipt_lines_item FOREIGN KEY (inventory_item_id)
        REFERENCES inventory.inventory_items (id) ON DELETE RESTRICT;


-- -----------------------------------------------------------------------------
-- BR-03 (docs 01): goods are received only for an item whose logistics data is complete, and a
-- lot/expiry-tracked item is received with its lot and expiry. Putaway and slotting cannot place
-- an item whose size and weight are unknown.
-- -----------------------------------------------------------------------------
CREATE FUNCTION inventory.check_receivable_item()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    item inventory.inventory_items%ROWTYPE;
BEGIN
    SELECT * INTO item FROM inventory.inventory_items WHERE id = NEW.inventory_item_id;
    IF item.weight_kg IS NULL OR item.length_cm IS NULL OR item.width_cm IS NULL OR item.height_cm IS NULL THEN
        RAISE EXCEPTION 'inventory item % has no weight/dimensions yet; complete it before receiving (BR-03)', item.sku
            USING ERRCODE = 'check_violation';
    END IF;
    IF item.lot_tracked AND NEW.lot_number IS NULL THEN
        RAISE EXCEPTION 'inventory item % is lot-tracked; the receipt line needs a lot number', item.sku
            USING ERRCODE = 'check_violation';
    END IF;
    IF item.expiry_tracked AND NEW.expiry_date IS NULL THEN
        RAISE EXCEPTION 'inventory item % is expiry-tracked; the receipt line needs an expiry date', item.sku
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_goods_receipt_lines_item BEFORE INSERT OR UPDATE ON procurement.goods_receipt_lines
    FOR EACH ROW EXECUTE FUNCTION inventory.check_receivable_item();
