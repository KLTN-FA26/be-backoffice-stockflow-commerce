-- =============================================================================
-- A packing unit that a document counts in keeps its factor, its item and its name.
-- Module: inventory   Issue: GitHub #125, Jira SCRUM-473 (docs 01 BR-10)
--
-- docs 01 BR-10: "hệ số quy đổi đã phát sinh giao dịch không được sửa". Once a stock package, a
-- purchase line or a receipt line was made in a unit, its factor is what those documents were
-- converted with, and V20261020000400 does not copy it onto them. A supplier who changes its carton
-- gets a new unit, and the old one is retired (uom_conversions.active), as V20261019000300 says.
-- Deleting such a unit is already refused by the foreign keys of V20261020000100 and 0400.
--
-- The documents copy neither the unit's item nor its name either, so those are locked the same way.
-- The checks that a document's unit is a unit of the document's item run on the document tables only
-- (V20261020000100, 0400): moving a unit in use to another item would leave every one of them
-- pointing at another item's unit, and nothing would fire. Renaming CARTON to BOX
-- (UomConversionServiceImpl.change allows it) would rewrite what a confirmed receipt was counted in.
--
-- A document still being written is not visible to unit_in_use, and its foreign key takes only KEY
-- SHARE on the unit row, which a factor change does not wait for: both would commit. So a document's
-- trigger (V20261020000100, 0400) takes the unit's advisory lock SHARED before it reads the unit, and
-- a change here TRIES the same lock exclusively and refuses when it cannot have it: a document is
-- being written in this unit, so it is in use. A document that starts after the change waits for it
-- and reads the new factor.
--
-- An advisory lock rather than FOR SHARE on the unit row: a row lock would also hold up every other
-- edit of the unit (its size, weight, retiring it, moving the default purchase unit), and could
-- deadlock with UomConversionServiceImpl.change, which updates two units of an item. Trying rather
-- than waiting: renaming a unit locks its row FOR UPDATE (the name is in uk_uom_conversions_unit),
-- which a document's foreign key check waits for, so a change waiting here for that document would
-- wait in a circle.
--
-- "Is this unit used by a document?" is answered in one place, inventory.unit_in_use, like
-- inventory.item_in_use (V20261016000100): the trigger asks it, and UomConversionServiceImpl must ask
-- it too before it changes a factor, an item or a name or deletes a unit, so the user gets
-- UOM_CONVERSION_IN_USE instead of the generic 409 a refused statement turns into. That service check
-- does not exist yet; it has to land before any code writes a unit onto a document.
--
-- The base unit's name is not locked here. It follows inventory_items.unit_of_measure
-- (inventory.follow_base_uom, V20261019000300), and whether that may change once used is GitHub #122,
-- the schema owner's decision. Its item and factor are locked like any other unit's.
-- =============================================================================

CREATE FUNCTION inventory.unit_in_use(p_uom_conversion_id UUID) RETURNS BOOLEAN
    LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM inventory.stock_package WHERE uom_conversion_id = p_uom_conversion_id)
        OR EXISTS (SELECT 1 FROM procurement.purchase_order_lines WHERE uom_conversion_id = p_uom_conversion_id)
        OR EXISTS (SELECT 1 FROM procurement.purchase_order_line_revisions WHERE uom_conversion_id = p_uom_conversion_id)
        OR EXISTS (SELECT 1 FROM procurement.goods_receipt_lines WHERE uom_conversion_id = p_uom_conversion_id)
$$;

COMMENT ON FUNCTION inventory.unit_in_use(UUID) IS
    'Whether a document counts in this unit: such a unit keeps its factor and is retired, never deleted (BR-10).';

-- The advisory lock key of a unit, in a namespace of its own (701 is the SKU stock lock, 702 the
-- stock alert evaluation).
CREATE FUNCTION inventory.unit_lock_key(p_uom_conversion_id UUID) RETURNS BIGINT
    LANGUAGE sql IMMUTABLE AS $$
    SELECT hashtextextended(p_uom_conversion_id::text, 703)
$$;

CREATE FUNCTION inventory.guard_unit_in_use_change()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT pg_try_advisory_xact_lock(inventory.unit_lock_key(OLD.id)) THEN
        RAISE EXCEPTION 'unit % (factor %) is being written onto a document; declare a new unit and retire this one (BR-10)',
            OLD.unit_of_measure, OLD.factor USING ERRCODE = 'check_violation';
    END IF;
    IF inventory.unit_in_use(OLD.id) THEN
        RAISE EXCEPTION 'unit % (factor %) is used by a document; declare a new unit and retire this one (BR-10)',
            OLD.unit_of_measure, OLD.factor USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_uom_conversions_locked_in_use
    BEFORE UPDATE OF factor, inventory_item_id, unit_of_measure ON inventory.uom_conversions
    FOR EACH ROW WHEN (NEW.factor IS DISTINCT FROM OLD.factor
                       OR NEW.inventory_item_id IS DISTINCT FROM OLD.inventory_item_id
                       OR (NOT OLD.is_base AND NEW.unit_of_measure IS DISTINCT FROM OLD.unit_of_measure))
    EXECUTE FUNCTION inventory.guard_unit_in_use_change();
