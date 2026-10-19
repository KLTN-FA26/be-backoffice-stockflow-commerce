-- =============================================================================
-- The blank of a production order, a sample request and an approved sample is an inventory item.
-- Module: production / ordering (FKs into inventory)
--
-- kltn-docs glossary "Phôi / Blank": ly, hộp, túi chưa in - the Inventory Item of the SKU; 01 §1
-- (B9, decided 2026-10-06) and 19 §7: "Inventory Item của SKU tùy chỉnh là phôi được cấp cho xưởng".
-- The three blank_sku columns were plain strings, so a typo or a deleted item could leave a lot
-- that no stock, reservation or pick could ever be found for.
--
-- Plain keys, no cascade either way: a blank named by a production or sample document counts as
-- "used", so its DRAFT SKU may be neither renamed (the variant rename cascades to the item and stops
-- here) nor deleted. inventory.item_in_use() already counts these three tables (V20261017001200), so
-- the rename guard and the DRAFT delete answer the friendly "SKU in use" error before this key would.
--
-- Existing rows are checked first and the migration stops with the table and the count, rather
-- than half-way through an ALTER: a blank without an inventory item has no meaning in the docs, so
-- there is no rule by which to repair one automatically.
-- =============================================================================

DO $$
DECLARE
    orphans BIGINT;
    check_sql TEXT;
BEGIN
    FOREACH check_sql IN ARRAY ARRAY[
        'production.production_order.blank_sku|SELECT count(*) FROM production.production_order c WHERE NOT EXISTS (SELECT 1 FROM inventory.inventory_items i WHERE i.sku = c.blank_sku)',
        'production.sample_request.blank_sku|SELECT count(*) FROM production.sample_request c WHERE NOT EXISTS (SELECT 1 FROM inventory.inventory_items i WHERE i.sku = c.blank_sku)',
        'ordering.approved_sample.blank_sku|SELECT count(*) FROM ordering.approved_sample c WHERE NOT EXISTS (SELECT 1 FROM inventory.inventory_items i WHERE i.sku = c.blank_sku)'
    ] LOOP
        EXECUTE split_part(check_sql, '|', 2) INTO orphans;
        IF orphans > 0 THEN
            RAISE EXCEPTION '% row(s) of % name a blank that has no inventory item', orphans, split_part(check_sql, '|', 1);
        END IF;
    END LOOP;
END $$;

ALTER TABLE production.production_order
    ADD CONSTRAINT fk_production_order_blank FOREIGN KEY (blank_sku)
        REFERENCES inventory.inventory_items (sku);
ALTER TABLE production.sample_request
    ADD CONSTRAINT fk_sample_request_blank FOREIGN KEY (blank_sku)
        REFERENCES inventory.inventory_items (sku);
ALTER TABLE ordering.approved_sample
    ADD CONSTRAINT fk_approved_sample_blank FOREIGN KEY (blank_sku)
        REFERENCES inventory.inventory_items (sku);

-- The referencing side of each key, so a rename or a delete of an item does not scan these tables.
CREATE INDEX ix_production_order_blank ON production.production_order (blank_sku);
CREATE INDEX ix_sample_request_blank ON production.sample_request (blank_sku);
CREATE INDEX ix_approved_sample_blank ON ordering.approved_sample (blank_sku);
