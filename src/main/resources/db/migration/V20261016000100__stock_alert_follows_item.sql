-- =============================================================================
-- A stock alert belongs to its inventory item; "is this item in use?" is answered in one place.
--
-- 1. fk_stock_alert_item had neither ON DELETE nor ON UPDATE, so one REORDER alert (and the RESOLVED rows that
--    are kept as history) refused, for ever, the two things kltn-docs 01 allows on an unused DRAFT SKU: deleting
--    the draft product (BR-02: only what has stock, purchase orders or orders is kept) and renaming its SKU
--    (BR-01: immutable only once stock transactions exist). An alert is a note about an item, not a use of it:
--    it now goes with the item and follows its rename. stock_alert_dirty has no foreign key on purpose (it must
--    never block a stock write); InventoryPolicyRepository.deleteItems removes its rows itself.
--
-- 2. inventory.item_in_use(id, sku): every document that makes an item "used" — stock, the ledger, adjustments,
--    counts, transfer lines, putaway / move tasks, pick lines, a selling price, supplier items and every
--    procurement line. The rename guard (V20261008000200) checked only part of the list and left the SKU-keyed
--    ledger/count/transfer/move/pick references to their foreign keys, which answered a generic CONFLICT and an
--    ERROR log instead of "this SKU is referenced". The product delete asks the same function before deleting,
--    so both refusals name the same set of documents and cannot drift apart. None of those references cascade:
--    referenced means used.
--
-- 3. Resolution NOT_PURCHASABLE for REORDER / SAFETY: a low-stock alert asks somebody to put the SKU on a
--    purchase order (docs 02 step 1, BR-08), which only an ACTIVE variant of an APPROVED or PUBLISHED product can
--    go on (docs 01 BR-04, 02 BR-01). StockAlertEvaluator no longer opens one for any other SKU and closes the
--    open ones with this resolution — neither RECOVERED (stock did not come back) nor UNCONFIGURED (nobody
--    removed the threshold). EXPIRING / EXPIRED are about stock on a shelf and keep their endings.
-- =============================================================================

ALTER TABLE inventory.stock_alert DROP CONSTRAINT fk_stock_alert_item;
ALTER TABLE inventory.stock_alert ADD CONSTRAINT fk_stock_alert_item
    FOREIGN KEY (sku) REFERENCES inventory.inventory_items (sku) ON UPDATE CASCADE ON DELETE CASCADE;

CREATE FUNCTION inventory.item_in_use(p_item_id UUID, p_sku VARCHAR) RETURNS BOOLEAN
    LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM inventory.stock_item WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_movement WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_adjustment WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.cycle_count_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.transfer_order_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM warehouse.putaway_task WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM warehouse.move_task WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM fulfillment.pick_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM catalog.pricing_rule WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM procurement.supplier_items WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.replenishment_proposal_lines WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.purchase_order_lines WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.purchase_order_line_revisions WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM procurement.goods_receipt_lines WHERE inventory_item_id = p_item_id)
$$;

COMMENT ON FUNCTION inventory.item_in_use(UUID, VARCHAR) IS
    'Whether a document uses this inventory item: such an item is neither renamed nor deleted. Stock alerts do not count (they follow the item).';

CREATE OR REPLACE FUNCTION inventory.guard_item_sku_rename() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.sku IS NOT DISTINCT FROM OLD.sku THEN RETURN NEW; END IF;
    -- The old parent disappears only during its FK cascade; direct item reassignment is refused.
    IF EXISTS (SELECT 1 FROM product.variants WHERE sku = OLD.sku)
       OR NOT EXISTS (SELECT 1 FROM product.variants WHERE sku = NEW.sku AND status = 'DRAFT')
       OR inventory.item_in_use(OLD.id, OLD.sku) THEN
        RAISE EXCEPTION 'Only an unused DRAFT variant can rename its inventory SKU'
            USING ERRCODE = '23514';
    END IF;
    NEW.version := OLD.version + 1;
    NEW.last_modified_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END $$;

ALTER TABLE inventory.stock_alert DROP CONSTRAINT ck_stock_alert_resolution;
ALTER TABLE inventory.stock_alert ADD CONSTRAINT ck_stock_alert_resolution CHECK (resolution IS NULL
    OR resolution IN ('RECOVERED', 'UNCONFIGURED', 'NOT_PURCHASABLE', 'STOCK_GONE', 'EXPIRED_OUT'));
ALTER TABLE inventory.stock_alert DROP CONSTRAINT ck_stock_alert_resolution_kind;
ALTER TABLE inventory.stock_alert ADD CONSTRAINT ck_stock_alert_resolution_kind CHECK (resolution IS NULL OR CASE
    WHEN kind IN ('REORDER', 'SAFETY') THEN resolution IN ('RECOVERED', 'UNCONFIGURED', 'NOT_PURCHASABLE')
    WHEN kind = 'EXPIRING' THEN resolution IN ('STOCK_GONE', 'EXPIRED_OUT')
    ELSE resolution = 'STOCK_GONE'
    END);
