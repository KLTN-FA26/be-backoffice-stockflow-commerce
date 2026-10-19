-- =============================================================================
-- A packing unit that a document counts in keeps its factor.
-- Module: inventory   Issue: GitHub #125, Jira SCRUM-473 (docs 01 BR-10)
--
-- docs 01 BR-10: "hệ số quy đổi đã phát sinh giao dịch không được sửa". Once a stock package, a
-- purchase line or a receipt line was made in a unit, its factor is what those documents were
-- converted with, and V20261020000400 does not copy it onto them. A supplier who changes its carton
-- gets a new unit, and the old one is retired (uom_conversions.active), as V20261019000300 says.
-- Deleting such a unit is already refused by the foreign keys of V20261020000100 and 0400.
--
-- "Is this unit used by a document?" is answered in one place, inventory.unit_in_use, like
-- inventory.item_in_use (V20261016000100): the trigger asks it, and UomConversionServiceImpl must ask
-- it too before it changes a factor or deletes a unit, so the user gets UOM_CONVERSION_IN_USE
-- instead of the generic 409 a refused statement turns into. That service check does not exist yet;
-- it has to land before any code writes a unit onto a document.
--
-- The base unit of an item (inventory_items.unit_of_measure) is not locked here: that is GitHub #122,
-- the schema owner's decision.
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

CREATE FUNCTION inventory.guard_unit_factor_change()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF inventory.unit_in_use(OLD.id) THEN
        RAISE EXCEPTION 'unit % (factor %) is used by a document; declare a new unit and retire this one (BR-10)',
            OLD.unit_of_measure, OLD.factor USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_uom_conversions_factor_locked BEFORE UPDATE OF factor ON inventory.uom_conversions
    FOR EACH ROW WHEN (NEW.factor IS DISTINCT FROM OLD.factor)
    EXECUTE FUNCTION inventory.guard_unit_factor_change();
