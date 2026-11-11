-- =============================================================================
-- 5-3 (kltn-docs 13 BR-04, BR-06, BR-10; open-questions D14): the storefront shows no price anywhere, and the
-- customer's own price list - an internal price only staff see, Sales' suggestion when quoting - is the one source of
-- a price. The base price of a variant, the price of a variant's sales unit (01 BR-11: a customer's SKU is sold by the
-- carton at a price per piece, agreed on the quote), the price of a customer group, the never-used promotions and the
-- price copied onto each storefront entry are gone from the code; this removes what the database kept for them. Every
-- row dropped, and every value removed, is archived in platform.legacy_archive first.
--
-- Kept on purpose:
--   * catalog.customer_price and its tiers: the internal price (13 §4.2). A row set on a stock variant before 4B-2
--     still prices that variant at checkout until the checkout endpoint goes (team plan 5-2);
--   * customer.segment: a group still classifies customers (18), it just has no price;
--   * product.variant_sales_units: which units a variant is sold in; only the price on a unit goes;
--   * catalog.catalog_entry.atp: not a price; nothing writes it, and it is out of this package's reach;
--   * the repeatable demo seed R__demo_catalog_selling_prices.sql, emptied rather than deleted: Flyway validates the
--     repeatable migrations it has applied, and a demo database would refuse to start with one missing.
--
-- Data:
--   * every catalog.pricing_rule row (base prices 'BASE:<sku>', group prices, any other rule) and every
--     catalog.promotion row is archived whole, then the tables are dropped;
--   * every catalog.catalog_entry row with a price keeps its row; its price and currency are archived (a row without
--     a price loses only the currency, always the column's default 'VND', so nothing of it is archived);
--   * every product.variant_sales_units row with a price keeps its row; its price is archived;
--   * the catalog-segment-prices permission and every grant of it are archived and deleted, advancing
--     app_role.version of each role that held it (ADR-0008).
--
-- Nothing is in flight: a price is not a document, and no event carries one, so event_publication is untouched.
--
-- Order:
--   1. archive; 2. inventory.item_in_use stops reading catalog.pricing_rule (Postgres does not track a function
--   body's tables: the DROP would pass and the function fail at its next call); 3. the tables and columns, the
--   CHECK on a sales unit's price with its column; 4. the permission, grants first.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. Archive
-- ---------------------------------------------------------------------------------------------
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'catalog.pricing_rule', r.id, to_jsonb(r) FROM catalog.pricing_rule r
UNION ALL
SELECT 'catalog.promotion', p.id, to_jsonb(p) FROM catalog.promotion p
UNION ALL
SELECT 'catalog.catalog_entry.price', e.id,
       jsonb_build_object('id', e.id, 'sku', e.sku, 'price', e.price, 'currency', e.currency)
FROM catalog.catalog_entry e WHERE e.price IS NOT NULL
UNION ALL
SELECT 'product.variant_sales_units.price', u.id,
       jsonb_build_object('id', u.id, 'variant_id', u.variant_id, 'uom_conversion_id', u.uom_conversion_id,
                          'price', u.price)
FROM product.variant_sales_units u WHERE u.price IS NOT NULL
UNION ALL
SELECT 'identity.permission', p.id, to_jsonb(p) FROM identity.permission p
WHERE p.code LIKE 'catalog-segment-prices:%'
UNION ALL
SELECT 'identity.role_permission', rp.id, to_jsonb(rp) FROM identity.role_permission rp
WHERE rp.permission_id IN (SELECT id FROM identity.permission WHERE code LIKE 'catalog-segment-prices:%');

COMMENT ON TABLE platform.legacy_archive IS
    'Rows of tables dropped by contracts C1-C4 (V20261011000200), by the removal of in-house production '
        '(V20261108000100), of receiving QC (V20261108000200), of transfers between warehouses (V20261108000300), '
        'of parcel shipping, the delivery-mode choice and COD (V20261108000400) and of the design check at packing '
        '(V20261108000500), the customization templates and print areas as they were before templates got a model '
        '(V20261108000600), design statuses renamed when customers began designing (V20261108000800), the staff '
        'design workflow''s assignments, its designs left waiting for confirmation and its permission '
        '(V20261108001000), the parcel carriers'' status before they were retired (V20261108001300), and the base '
        'and group prices, sales-unit and storefront entry prices and promotions (V20261110000100). Read-only '
        'history.';


-- ---------------------------------------------------------------------------------------------
-- 2. "Is this item used by a document?" without the price rules
-- ---------------------------------------------------------------------------------------------
-- As V20261108002000 defined it, less catalog.pricing_rule. A customer's price still counts (catalog.customer_price).
CREATE OR REPLACE FUNCTION inventory.item_in_use(p_item_id uuid, p_sku character varying)
    RETURNS boolean
    LANGUAGE sql
    STABLE
AS $function$
    SELECT EXISTS (SELECT 1 FROM inventory.stock_item WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_movement WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_adjustment WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.cycle_count_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_package WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM warehouse.putaway_task WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM warehouse.move_task WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM fulfillment.pick_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM procurement.supplier_items WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.replenishment_proposal_lines WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.purchase_order_lines WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.purchase_order_line_revisions WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.goods_receipt_lines WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM catalog.customer_price WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM ordering.quote_line WHERE blank_sku = p_sku OR sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_reservation WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_lot WHERE sku = p_sku)
$function$;


-- ---------------------------------------------------------------------------------------------
-- 3. Tables and columns
-- ---------------------------------------------------------------------------------------------
-- Nothing references either table; their own foreign keys (to customer.segment and product.variants), checks and
-- indexes go with them.
DROP TABLE catalog.pricing_rule;
DROP TABLE catalog.promotion;

-- No check, index or view reads the two columns.
ALTER TABLE catalog.catalog_entry
    DROP COLUMN price,
    DROP COLUMN currency;

-- A sales unit names a unit, not a price: the CHECK on the price, then the column.
ALTER TABLE product.variant_sales_units
    DROP CONSTRAINT ck_variant_sales_units_price,
    DROP COLUMN price;


-- ---------------------------------------------------------------------------------------------
-- 4. The group-price permission
-- ---------------------------------------------------------------------------------------------
-- role_permission first, advancing the version of every role that loses a grant in the same statement: deleting the
-- permission rows first would cascade the grants away without moving any version.
WITH lost AS (
    DELETE FROM identity.role_permission rp
    USING identity.permission p
    WHERE p.id = rp.permission_id AND p.code LIKE 'catalog-segment-prices:%'
    RETURNING rp.role_id)
UPDATE identity.app_role r
SET version          = r.version + 1,
    last_modified_at = NOW(),
    last_modified_by = 'flyway'
WHERE r.id IN (SELECT role_id FROM lost);

DELETE FROM identity.permission WHERE code LIKE 'catalog-segment-prices:%';
