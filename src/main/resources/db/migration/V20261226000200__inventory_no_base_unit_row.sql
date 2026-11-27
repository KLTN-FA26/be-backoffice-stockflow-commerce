-- An inventory item's base unit - the unit its pieces are counted in, "cái" (kltn-docs 01 §3 "Đơn vị cơ sở", BR-09,
-- BR-17) - is inventory.inventory_items.unit_of_measure. Since V20261019000300 it also had a row of its own in
-- inventory.uom_conversions: is_base, factor 1, never measured, made by a trigger when the item was made and renamed by
-- another when the item's unit was renamed. That row said "one piece is one piece" and nothing used it:
--   - a carton, a receipt line and a pick are in a packing unit and refused the base row (check_stock_package_created,
--     check_goods_receipt_line); a quote and an order line count cartons of more than one piece
--     (ck_quote_line_units_per_carton, ck_order_line_cartons), so they could not name it either;
--   - every read of a SKU's carton skipped it (NOT is_base), and the application's own writes refused to change it.
-- The warehouse handles whole cartons only (20 BR-14), so the units of an item are its carton specifications and
-- nothing else. The base row goes; the item's unit stays where it always was.
--
--  1. The base rows are deleted, with the two triggers that made and renamed them. Nothing references one: the tables
--     that name a unit refuse the base unit (above); should a database hold such a row all the same, its foreign key
--     stops this migration and names the table, rather than a document silently losing its unit.
--  2. ck_uom_conversions_no_base: no row is a base unit any more. The column is_base stays for now, always false -
--     scripts and fixtures still write and filter it - and is dropped with default_shipping in the follow-up (01 BR-03:
--     one active carton, so no unit to choose to ship in). check_stock_package_created and check_goods_receipt_line
--     keep their base-unit refusals until then; they can no longer fire.
--  3. The base row's code was the item's unit, and uk_uom_conversions_unit kept a packing unit from taking it. Two
--     triggers keep that rule now: a packing unit never has the code of its item's unit (a carton called EACH would be
--     read as a piece), on either side of the pair.

DO $$
DECLARE
    used TEXT;
BEGIN
    SELECT string_agg(x.tbl || ' (' || x.n || ')', ', ') INTO used
      FROM (SELECT 'inventory.stock_package' AS tbl, count(*) AS n FROM inventory.stock_package p
              JOIN inventory.uom_conversions u ON u.id = p.uom_conversion_id WHERE u.is_base
            UNION ALL
            SELECT 'procurement.goods_receipt_lines', count(*) FROM procurement.goods_receipt_lines l
              JOIN inventory.uom_conversions u ON u.id = l.uom_conversion_id WHERE u.is_base
            UNION ALL
            SELECT 'ordering.quote_line', count(*) FROM ordering.quote_line l
              JOIN inventory.uom_conversions u ON u.id = l.uom_conversion_id WHERE u.is_base
            UNION ALL
            SELECT 'ordering.order_line', count(*) FROM ordering.order_line l
              JOIN inventory.uom_conversions u ON u.id = l.uom_conversion_id WHERE u.is_base) x
     WHERE x.n > 0;
    IF used IS NOT NULL THEN
        RAISE EXCEPTION 'base units are still named by: %; give those rows their carton unit first', used;
    END IF;
END $$;

DELETE FROM inventory.uom_conversions WHERE is_base;

DROP TRIGGER tg_inventory_items_base_uom ON inventory.inventory_items;
DROP TRIGGER tg_inventory_items_follow_base_uom ON inventory.inventory_items;
DROP FUNCTION inventory.create_base_uom();
DROP FUNCTION inventory.follow_base_uom();

DROP INDEX inventory.uk_uom_conversions_base;
ALTER TABLE inventory.uom_conversions DROP CONSTRAINT ck_uom_conversions_base;
ALTER TABLE inventory.uom_conversions ADD CONSTRAINT ck_uom_conversions_no_base CHECK (NOT is_base);

COMMENT ON CONSTRAINT ck_uom_conversions_no_base ON inventory.uom_conversions IS 'A unit of an item is a carton specification; the base unit is inventory_items.unit_of_measure and has no row (V20261226000200). is_base is always false until the column is dropped.';
COMMENT ON TABLE inventory.uom_conversions IS 'The packing units of an inventory item: its carton specifications (kltn-docs 01 BR-03) - pieces per carton (factor), size and full weight. One active per item, the retired ones kept as history (BR-10). The unit the pieces are counted in is inventory_items.unit_of_measure.';
COMMENT ON COLUMN inventory.inventory_items.unit_of_measure IS 'The base unit: what one piece of the SKU is counted in (kltn-docs 01 §3, default EACH). Stock quantities, a carton''s factor and prices per piece are all in it. It has no row in uom_conversions.';

CREATE FUNCTION inventory.check_packing_unit_code() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM inventory.inventory_items i
                WHERE i.id = NEW.inventory_item_id AND i.unit_of_measure = NEW.unit_of_measure) THEN
        RAISE EXCEPTION 'packing unit %: that is the unit the pieces of its item are counted in', NEW.unit_of_measure
            USING ERRCODE = 'unique_violation', CONSTRAINT = 'tg_uom_conversions_unit_code';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_uom_conversions_unit_code BEFORE INSERT OR UPDATE OF unit_of_measure, inventory_item_id
    ON inventory.uom_conversions
    FOR EACH ROW EXECUTE FUNCTION inventory.check_packing_unit_code();

CREATE FUNCTION inventory.check_item_unit_code() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM inventory.uom_conversions c
                WHERE c.inventory_item_id = NEW.id AND c.unit_of_measure = NEW.unit_of_measure) THEN
        RAISE EXCEPTION 'item %: a packing unit of it is already called %', NEW.sku, NEW.unit_of_measure
            USING ERRCODE = 'unique_violation', CONSTRAINT = 'tg_inventory_items_unit_code';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_inventory_items_unit_code BEFORE UPDATE OF unit_of_measure ON inventory.inventory_items
    FOR EACH ROW WHEN (NEW.unit_of_measure IS DISTINCT FROM OLD.unit_of_measure)
    EXECUTE FUNCTION inventory.check_item_unit_code();
