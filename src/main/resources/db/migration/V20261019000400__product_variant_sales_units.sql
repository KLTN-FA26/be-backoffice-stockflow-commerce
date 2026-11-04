-- =============================================================================
-- Sales units of a variant, and the product history entries for the new SKU data.
-- Module: product
--
-- kltn-docs 01 §3 Input "Đơn vị bán (sales UoM) | Variant | Các đơn vị khách được đặt (chọn từ danh
-- sách đơn vị quy đổi của Inventory Item, ví dụ chỉ bán theo thùng), giá bán theo từng đơn vị bán |
-- Có (mặc định: đơn vị cơ sở)"; BR-11 (chosen only among the item's units, configured per variant,
-- ordered in whole sales units - assumption A9).
--
-- A variant with no row sells in its base unit only, at the catalog base price (the default the docs
-- name); listing units replaces that default. The catalog base price (catalog.pricing_rule 'BASE:')
-- is already the price of one base unit, so the base unit's row carries no price of its own (NULL)
-- and every other unit carries its price, in VND whole dong like the base price
-- (ck_catalog_base_vnd). Both are checked by the application; the table checks the shape.
-- =============================================================================

CREATE TABLE product.variant_sales_units
(
    id                UUID          NOT NULL,
    variant_id        UUID          NOT NULL,
    uom_conversion_id UUID          NOT NULL,
    price             NUMERIC(18, 2),

    version           BIGINT        NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(100),
    last_modified_at  TIMESTAMPTZ,
    last_modified_by  VARCHAR(100),

    CONSTRAINT pk_variant_sales_units PRIMARY KEY (id),
    CONSTRAINT fk_variant_sales_units_variant FOREIGN KEY (variant_id)
        REFERENCES product.variants (id) ON DELETE CASCADE,
    -- The application refuses to delete or retire a unit a variant sells in; the cascade only serves
    -- the removal of an unused draft variant, whose item (and units) go with it.
    CONSTRAINT fk_variant_sales_units_conversion FOREIGN KEY (uom_conversion_id)
        REFERENCES inventory.uom_conversions (id) ON DELETE CASCADE,
    CONSTRAINT uk_variant_sales_units_unit UNIQUE (variant_id, uom_conversion_id),
    CONSTRAINT ck_variant_sales_units_price CHECK (price IS NULL OR (price > 0 AND price = trunc(price)))
);

CREATE INDEX ix_variant_sales_units_conversion ON product.variant_sales_units (uom_conversion_id);

-- BR-11: the unit is one of the variant's own inventory item, whatever path wrote the row.
CREATE FUNCTION product.check_sales_unit_of_item() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM product.variants v
                   JOIN inventory.inventory_items i ON i.sku = v.sku
                   JOIN inventory.uom_conversions c ON c.inventory_item_id = i.id
                   WHERE v.id = NEW.variant_id AND c.id = NEW.uom_conversion_id) THEN
        RAISE EXCEPTION 'unit % is not a unit of the inventory item of variant %', NEW.uom_conversion_id,
            NEW.variant_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER tg_variant_sales_units_item BEFORE INSERT OR UPDATE OF variant_id, uom_conversion_id
    ON product.variant_sales_units FOR EACH ROW EXECUTE FUNCTION product.check_sales_unit_of_item();

-- The product's history records the new SKU data like logistics and inventory control
-- (subject_id = the variant's id).
ALTER TABLE product.product_change_log DROP CONSTRAINT ck_product_change_log_type;
ALTER TABLE product.product_change_log ADD CONSTRAINT ck_product_change_log_type CHECK (
    (subject_type = 'PRODUCT' AND change_type IN
        ('CREATED', 'UPDATED', 'SUBMITTED', 'APPROVED', 'REJECTED', 'SUSPENDED', 'RESUMED', 'DISCONTINUED'))
    OR (subject_type = 'VARIANT' AND change_type IN ('VARIANT_ADDED', 'VARIANT_UPDATED', 'VARIANT_STATUS'))
    OR (subject_type = 'MEDIA' AND change_type = 'MEDIA')
    OR (subject_type = 'LOGISTICS' AND change_type = 'LOGISTICS')
    OR (subject_type = 'INVENTORY_CONTROL' AND change_type = 'INVENTORY_CONTROL')
    OR (subject_type = 'PURCHASING' AND change_type = 'PURCHASING')
    OR (subject_type = 'UOM_CONVERSION' AND change_type = 'UOM_CONVERSION')
    OR (subject_type = 'SALES_UNIT' AND change_type = 'SALES_UNIT'));
