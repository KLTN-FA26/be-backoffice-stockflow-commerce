-- =============================================================================
-- Units of measure of an inventory item: the base unit and its packing units (UoM conversions).
-- Module: inventory
--
-- kltn-docs 01 §3 Input "Đơn vị quy đổi (UoM conversion) | Inventory Item | Danh sách đơn vị đóng gói
-- kèm hệ số quy về đơn vị cơ sở (ví dụ thùng = 1.000 cái), kích thước & khối lượng của từng đơn vị;
-- đánh dấu đơn vị mua mặc định dùng trên PO | Không (mặc định chỉ có đơn vị cơ sở)"; BR-09 (the factor
-- is a positive integer, no SKU per packing), BR-10 (a factor in use is not edited: declare a new unit
-- and retire the old one), glossary "Purchase UoM", "Sales UoM".
--
-- Every item has exactly one BASE row: the unit stock is counted in (inventory_items.unit_of_measure),
-- factor 1. It is a row so that the default purchase unit and a variant's sales units can point at it
-- like at any other unit. Its code follows the item's (trigger below); its size and weight are the
-- item's own logistics, so the row carries none. Until someone marks another unit, the base unit is
-- the default purchase unit: "mặc định chỉ có đơn vị cơ sở".
--
-- A retired unit (active = false) stays for the documents that already used it (01 edge case "NCC đổi
-- quy cách thùng") but cannot be the default purchase unit any more.
-- =============================================================================

CREATE TABLE inventory.uom_conversions
(
    id                UUID           NOT NULL,
    inventory_item_id UUID           NOT NULL,
    unit_of_measure   VARCHAR(16)    NOT NULL,
    factor            INTEGER        NOT NULL,
    is_base           BOOLEAN        NOT NULL DEFAULT FALSE,
    weight_kg         NUMERIC(10, 3),
    length_cm         NUMERIC(10, 2),
    width_cm          NUMERIC(10, 2),
    height_cm         NUMERIC(10, 2),
    default_purchase  BOOLEAN        NOT NULL DEFAULT FALSE,
    active            BOOLEAN        NOT NULL DEFAULT TRUE,

    version           BIGINT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(100),
    last_modified_at  TIMESTAMPTZ,
    last_modified_by  VARCHAR(100),

    CONSTRAINT pk_uom_conversions PRIMARY KEY (id),
    -- ON UPDATE CASCADE: the test-data seed re-keys inventory items; ON DELETE CASCADE: an item is only
    -- ever deleted together with its unused draft variant, and its units mean nothing without it.
    CONSTRAINT fk_uom_conversions_item FOREIGN KEY (inventory_item_id)
        REFERENCES inventory.inventory_items (id) ON UPDATE CASCADE ON DELETE CASCADE,
    CONSTRAINT uk_uom_conversions_unit UNIQUE (inventory_item_id, unit_of_measure),
    CONSTRAINT ck_uom_conversions_unit CHECK (unit_of_measure ~ '^[A-Z0-9_]{1,16}$'),
    CONSTRAINT ck_uom_conversions_factor CHECK (factor >= 1),
    CONSTRAINT ck_uom_conversions_weight CHECK (weight_kg IS NULL OR weight_kg > 0),
    CONSTRAINT ck_uom_conversions_size CHECK ((length_cm IS NULL OR length_cm > 0)
        AND (width_cm IS NULL OR width_cm > 0) AND (height_cm IS NULL OR height_cm > 0)),
    CONSTRAINT ck_uom_conversions_base CHECK (NOT is_base OR (factor = 1 AND active AND weight_kg IS NULL
        AND length_cm IS NULL AND width_cm IS NULL AND height_cm IS NULL)),
    CONSTRAINT ck_uom_conversions_default_purchase CHECK (NOT default_purchase OR active)
);

CREATE UNIQUE INDEX uk_uom_conversions_base ON inventory.uom_conversions (inventory_item_id) WHERE is_base;
CREATE UNIQUE INDEX uk_uom_conversions_default_purchase ON inventory.uom_conversions (inventory_item_id)
    WHERE default_purchase;

-- Every existing item gets its base unit, which is also its default purchase unit.
INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base, default_purchase,
                                       created_by)
SELECT gen_random_uuid(), i.id, i.unit_of_measure, 1, TRUE, TRUE, 'flyway'
FROM inventory.inventory_items i;

-- A new item (created with its variant, docs 01 BR-07) gets its base unit in the same statement.
CREATE FUNCTION inventory.create_base_uom() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO inventory.uom_conversions (id, inventory_item_id, unit_of_measure, factor, is_base,
                                           default_purchase, created_by)
    VALUES (gen_random_uuid(), NEW.id, NEW.unit_of_measure, 1, TRUE, TRUE, NEW.created_by);
    RETURN NEW;
END $$;
CREATE TRIGGER tg_inventory_items_base_uom AFTER INSERT ON inventory.inventory_items
    FOR EACH ROW EXECUTE FUNCTION inventory.create_base_uom();

-- The base row carries the item's unit code; the item is where it is edited (logistics), and only
-- while no stock is held. A packing unit with the new code makes the UPDATE fail on uk_uom_conversions_unit.
CREATE FUNCTION inventory.follow_base_uom() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE inventory.uom_conversions
    SET unit_of_measure = NEW.unit_of_measure, version = version + 1, last_modified_at = NOW()
    WHERE inventory_item_id = NEW.id AND is_base AND unit_of_measure <> NEW.unit_of_measure;
    RETURN NEW;
END $$;
CREATE TRIGGER tg_inventory_items_follow_base_uom AFTER UPDATE OF unit_of_measure ON inventory.inventory_items
    FOR EACH ROW WHEN (NEW.unit_of_measure IS DISTINCT FROM OLD.unit_of_measure)
    EXECUTE FUNCTION inventory.follow_base_uom();
