-- A SKU has exactly one carton specification (kltn-docs 01 BR-03, decided 2026-11-06), and BR-10 says a unit a
-- document used is retired, never changed or deleted. Until now nothing in the database held the first rule: an item
-- could carry several active packing units, and receiving had a ladder (named unit, the line's own, the only active
-- one, the default shipping one) to choose between them. From here an item has at most one ACTIVE non-base unit; the
-- retired ones stay beside it as history.
--
-- Items that already carry several active packing units keep one, the one still in use first:
--   1. a unit a variant sells in (product.variant_sales_units) - the application never retires one (BR-11);
--   2. a unit holding the carton count of a production order of the SKU not yet received, cancelled or closed - its
--      goods are received in the SKU's one active carton, which must hold that count (03 BR-13);
--   3. the default shipping unit;
--   4. the most recently created.
-- The others are retired (and lose their default shipping mark: a retired unit cannot carry it,
-- ck_uom_conversions_default_shipping). Retiring changes neither factor nor code, so tg_uom_conversions_locked_in_use
-- has nothing to refuse. Rows are never deleted: a stock package or a receipt line may name them. An item whose units
-- are needed in two of these ways at once cannot keep both: the first wins, and the rest waits for the carton decision
-- of the PR that brought this migration.

UPDATE inventory.uom_conversions c
   SET active = FALSE, default_shipping = FALSE, version = c.version + 1, last_modified_at = now(),
       last_modified_by = 'migration V20261214000400'
 WHERE NOT c.is_base AND c.active
   AND c.id <> (SELECT k.id FROM inventory.uom_conversions k
                  JOIN inventory.inventory_items i ON i.id = k.inventory_item_id
                 WHERE k.inventory_item_id = c.inventory_item_id AND NOT k.is_base AND k.active
                 ORDER BY EXISTS (SELECT 1 FROM product.variant_sales_units s WHERE s.uom_conversion_id = k.id) DESC,
                          EXISTS (SELECT 1 FROM ordering.production_order_line pl
                                    JOIN ordering.production_order po ON po.id = pl.production_order_id
                                   WHERE pl.sku = i.sku AND pl.units_per_carton = k.factor
                                     AND po.status NOT IN ('RECEIVED', 'CANCELLED', 'CLOSED')) DESC,
                          k.default_shipping DESC, k.created_at DESC, k.id DESC
                 LIMIT 1);

CREATE UNIQUE INDEX uk_uom_conversions_one_carton ON inventory.uom_conversions (inventory_item_id)
    WHERE NOT is_base AND active;

COMMENT ON INDEX inventory.uk_uom_conversions_one_carton IS
    'kltn-docs 01 BR-03: a SKU has one carton specification - at most one active non-base unit per item. Retired units (BR-10) stay as history. The application asks first (UOM_CARTON_ALREADY_DECLARED, 409).';
