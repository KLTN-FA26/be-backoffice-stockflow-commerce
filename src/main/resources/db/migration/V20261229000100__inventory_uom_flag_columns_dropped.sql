-- Renumbered from V20261226000300, which reached develop after V20261227000100 had been deployed: Flyway does not run a
-- migration older than the database's newest, so every deploy since failed validation and rolled back. Same statements,
-- now after every migration on develop (V20261228000500 included, the demo seed shares the history). The drops say
-- IF EXISTS so a database that ran it under its old number runs it again harmlessly (its history row for
-- 20261226000300 must still go, see the PR); nothing between the two numbers touches inventory.uom_conversions.
--
-- inventory.uom_conversions holds an item's carton specifications and nothing else (V20261226000200), and an item has
-- one active carton (kltn-docs 01 BR-03, uk_uom_conversions_one_carton). Two flags of the time when an item had several
-- units are left with nothing to say:
--   - is_base: always false since V20261226000200 (ck_uom_conversions_no_base). The base unit is
--     inventory_items.unit_of_measure and has no row.
--   - default_shipping: "the unit load planning estimates the SKU's cartons in", chosen among the item's units. With one
--     active unit there is nothing to choose: the carton readers (cartonOf, findCartonSpecs, cartonFactorOf) took the
--     marked unit only when they found several active ones, which the unique index no longer allows, and a retired
--     unit could not carry the mark. The carton a SKU ships in is its active carton. One visible change: the load
--     estimate (PackagingRules) tried the marked unit first, so an active carton nobody marked used to resolve at
--     LARGEST_PACKING_UNIT (warning PACKING_UNIT_INCOMPLETE); it now resolves at DEFAULT_SHIPPING_UNIT (warning
--     SHIPPING_UNIT_INCOMPLETE), like a marked one.
-- Both columns go, with what names them:
--  1. The two trigger functions that still refused a base unit: the refusal can no longer fire, and a function body
--     naming a dropped column fails at its next call (Postgres does not track dependencies in function bodies).
--  2. uk_uom_conversions_one_carton is rebuilt on "active" alone: its predicate named is_base, and dropping the column
--     would drop the index - and BR-03 with it - without a word.
--  3. tg_uom_conversions_locked_in_use is rebuilt: its WHEN clause let the base row be renamed with its item
--     ("NOT old.is_base AND the code changed"). With no base row the clause is just "the code changed"; what the trigger
--     guards (a unit a document uses keeps its code, factor and item, BR-10) is unchanged.
--  4. ck_uom_conversions_no_base, ck_uom_conversions_default_shipping and uk_uom_conversions_default_shipping, then the
--     columns.
-- No row changes: ids, codes, factors, sizes and the active flag stay as they are.

CREATE OR REPLACE FUNCTION inventory.check_stock_package_created() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    unit inventory.uom_conversions%ROWTYPE;
BEGIN
    IF NEW.status NOT IN ('RECEIVING', 'IN_STOCK') THEN
        RAISE EXCEPTION 'stock package % is created RECEIVING or IN_STOCK, not %', NEW.lpn, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    PERFORM pg_advisory_xact_lock_shared(inventory.unit_lock_key(NEW.uom_conversion_id));
    SELECT * INTO unit FROM inventory.uom_conversions WHERE id = NEW.uom_conversion_id;
    IF NOT FOUND THEN
        RETURN NEW; -- fk_stock_package_unit reports it
    END IF;
    IF unit.inventory_item_id <> NEW.inventory_item_id THEN
        RAISE EXCEPTION 'stock package %: unit % is not a unit of its item', NEW.lpn, unit.unit_of_measure
            USING ERRCODE = 'check_violation';
    END IF;
    IF NOT unit.active AND NOT EXISTS (SELECT 1 FROM procurement.goods_receipt_lines
                                        WHERE id = NEW.goods_receipt_line_id
                                          AND uom_conversion_id = NEW.uom_conversion_id) THEN
        RAISE EXCEPTION 'stock package %: unit % is retired; only goods received in it are still packed in it',
            NEW.lpn, unit.unit_of_measure USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.nominal_qty <> unit.factor THEN
        RAISE EXCEPTION 'stock package % holds % per %, not %', NEW.lpn, unit.factor, unit.unit_of_measure,
            NEW.nominal_qty USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION procurement.check_goods_receipt_line() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    receipt    procurement.goods_receipts%ROWTYPE;
    production RECORD;
BEGIN
    SELECT * INTO receipt FROM procurement.goods_receipts WHERE id = NEW.receipt_id;
    IF (NEW.production_order_line_id IS NULL) <> (receipt.production_order_id IS NULL) THEN
        RAISE EXCEPTION 'a receipt line names a production order line exactly when its receipt is against a production order'
            USING ERRCODE = 'check_violation';
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
        IF NEW.lot_number IS DISTINCT FROM production.code OR to_jsonb(NEW) ->> 'expiry_date' IS NOT NULL THEN
            RAISE EXCEPTION 'receipt line: the lot is production order %''s code, with no expiry date (03 BR-03, BR-06)',
                production.code USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.received_qty % production.units_per_carton <> 0 THEN
            RAISE EXCEPTION 'receipt line: % units are not whole cartons of % (03 BR-12, BR-13)',
                trim_scale(NEW.received_qty), production.units_per_carton USING ERRCODE = 'check_violation';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM inventory.uom_conversions u
                        WHERE u.id = NEW.uom_conversion_id AND u.factor = production.units_per_carton) THEN
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

DROP TRIGGER IF EXISTS tg_uom_conversions_locked_in_use ON inventory.uom_conversions;
CREATE TRIGGER tg_uom_conversions_locked_in_use BEFORE UPDATE OF factor, inventory_item_id, unit_of_measure
    ON inventory.uom_conversions
    FOR EACH ROW WHEN (NEW.factor IS DISTINCT FROM OLD.factor
                       OR NEW.inventory_item_id IS DISTINCT FROM OLD.inventory_item_id
                       OR NEW.unit_of_measure IS DISTINCT FROM OLD.unit_of_measure)
    EXECUTE FUNCTION inventory.guard_unit_in_use_change();

DROP INDEX IF EXISTS inventory.uk_uom_conversions_one_carton;
DROP INDEX IF EXISTS inventory.uk_uom_conversions_default_shipping;
ALTER TABLE inventory.uom_conversions DROP CONSTRAINT IF EXISTS ck_uom_conversions_no_base;
ALTER TABLE inventory.uom_conversions DROP CONSTRAINT IF EXISTS ck_uom_conversions_default_shipping;
ALTER TABLE inventory.uom_conversions DROP COLUMN IF EXISTS is_base;
ALTER TABLE inventory.uom_conversions DROP COLUMN IF EXISTS default_shipping;

CREATE UNIQUE INDEX uk_uom_conversions_one_carton ON inventory.uom_conversions (inventory_item_id) WHERE active;

COMMENT ON INDEX inventory.uk_uom_conversions_one_carton IS
    'kltn-docs 01 BR-03: a SKU has one carton specification - at most one active unit per item, the carton it is received, sold and shipped in. Retired units (BR-10) stay as history. The application asks first (UOM_CARTON_ALREADY_DECLARED, 409).';
