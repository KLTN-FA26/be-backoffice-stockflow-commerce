-- A SKU has exactly one carton specification (kltn-docs 01 BR-03, decided 2026-11-06), and BR-10 says a unit a
-- document used is retired, never changed or deleted. Until now nothing in the database held the first rule: an item
-- could carry several active packing units, and receiving had a ladder (named unit, the line's own, the only active
-- one, the default shipping one) to choose between them. From here an item has at most one ACTIVE non-base unit; the
-- retired ones stay beside it as history.
--
-- Items that already carry several active packing units keep one: the default shipping one, else the most recently
-- created. The others are retired (and lose their default shipping mark: a retired unit cannot carry it,
-- ck_uom_conversions_default_shipping). Retiring changes neither factor nor code, so tg_uom_conversions_locked_in_use
-- has nothing to refuse. Rows are never deleted: a stock package or a receipt line may name them.

UPDATE inventory.uom_conversions c
   SET active = FALSE, default_shipping = FALSE, version = c.version + 1, last_modified_at = now(),
       last_modified_by = 'migration V20261213000400'
 WHERE NOT c.is_base AND c.active
   AND c.id <> (SELECT k.id FROM inventory.uom_conversions k
                 WHERE k.inventory_item_id = c.inventory_item_id AND NOT k.is_base AND k.active
                 ORDER BY k.default_shipping DESC, k.created_at DESC, k.id DESC
                 LIMIT 1);

CREATE UNIQUE INDEX uk_uom_conversions_one_carton ON inventory.uom_conversions (inventory_item_id)
    WHERE NOT is_base AND active;

COMMENT ON INDEX inventory.uk_uom_conversions_one_carton IS
    'kltn-docs 01 BR-03: a SKU has one carton specification - at most one active non-base unit per item. Retired units (BR-10) stay as history. The application asks first (UOM_CARTON_ALREADY_DECLARED, 409).';
