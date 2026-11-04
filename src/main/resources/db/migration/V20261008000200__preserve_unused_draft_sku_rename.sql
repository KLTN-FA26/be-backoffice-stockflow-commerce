-- Auto-created inventory items must not disable the existing DRAFT variant rename contract.
-- Preserve item identity/policy by cascading only an unused DRAFT parent's rename. Operational
-- references remain restrictive; no historical movement/receipt/price is rewritten.
CREATE FUNCTION product.lock_inventory_sku_rename() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE code text;
BEGIN
    IF NEW.sku IS DISTINCT FROM OLD.sku THEN
        FOR code IN SELECT unnest(ARRAY[OLD.sku, NEW.sku]) ORDER BY 1 LOOP
            PERFORM pg_advisory_xact_lock(hashtextextended(code, 701));
        END LOOP;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER tg_variants_inventory_rename BEFORE UPDATE OF sku ON product.variants
    FOR EACH ROW EXECUTE FUNCTION product.lock_inventory_sku_rename();

CREATE FUNCTION inventory.guard_item_sku_rename() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.sku IS NOT DISTINCT FROM OLD.sku THEN RETURN NEW; END IF;
    -- The old parent disappears only during its FK cascade; direct item reassignment is refused.
    IF EXISTS (SELECT 1 FROM product.variants WHERE sku=OLD.sku)
       OR NOT EXISTS (SELECT 1 FROM product.variants WHERE sku=NEW.sku AND status='DRAFT')
       OR EXISTS (SELECT 1 FROM inventory.stock_item WHERE sku=OLD.sku)
       OR EXISTS (SELECT 1 FROM catalog.pricing_rule WHERE sku=OLD.sku)
       OR EXISTS (SELECT 1 FROM procurement.supplier_items WHERE inventory_item_id=OLD.id)
       OR EXISTS (SELECT 1 FROM procurement.replenishment_proposal_lines WHERE inventory_item_id=OLD.id)
       OR EXISTS (SELECT 1 FROM procurement.purchase_order_lines WHERE inventory_item_id=OLD.id)
       OR EXISTS (SELECT 1 FROM procurement.purchase_order_line_revisions WHERE inventory_item_id=OLD.id)
       OR EXISTS (SELECT 1 FROM procurement.goods_receipt_lines WHERE inventory_item_id=OLD.id) THEN
        RAISE EXCEPTION 'Only an unused DRAFT variant can rename its inventory SKU'
            USING ERRCODE='23514';
    END IF;
    -- SKU-based ledger/count/transfer/move/pick FKs independently reject referenced items.
    NEW.version := OLD.version + 1;
    NEW.last_modified_at := CURRENT_TIMESTAMP;
    RETURN NEW;
END $$;
DROP TRIGGER tg_inventory_items_immutable ON inventory.inventory_items;
CREATE TRIGGER tg_inventory_items_immutable BEFORE UPDATE OF sku ON inventory.inventory_items
    FOR EACH ROW EXECUTE FUNCTION inventory.guard_item_sku_rename();
ALTER TABLE inventory.inventory_items DROP CONSTRAINT fk_inventory_items_variant;
ALTER TABLE inventory.inventory_items ADD CONSTRAINT fk_inventory_items_variant
    FOREIGN KEY (sku) REFERENCES product.variants(sku) ON UPDATE CASCADE ON DELETE RESTRICT;
