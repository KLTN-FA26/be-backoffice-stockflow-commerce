-- =============================================================================
-- inventory.item_in_use (V20261016000100, #120) counts Sprint 2's references too (audit of #109).
--
-- The function answers "does a document use this inventory item?" for the SKU rename guard and the DRAFT product delete,
-- so both refuse with "this SKU is referenced" instead of a foreign-key error. Sprint 2 added documents that name a SKU
-- and #120 could not know about: a customer's own price (catalog.customer_price), a quote line's blank
-- (ordering.quote_line), and the blank of a production order, a sample request and an approved sample, and the blanks
-- issued to the print shop (production.production_material_issue). #121 plans foreign keys on the three blank columns;
-- counting them here is what its own header asks for once both are merged.
--
-- The body is #120's, unchanged, with those six lines added. Nothing else reads it differently.
-- =============================================================================

CREATE OR REPLACE FUNCTION inventory.item_in_use(p_item_id UUID, p_sku VARCHAR) RETURNS BOOLEAN
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
        OR EXISTS (SELECT 1 FROM catalog.customer_price WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM ordering.quote_line WHERE blank_sku = p_sku)
        OR EXISTS (SELECT 1 FROM production.production_order WHERE blank_sku = p_sku)
        OR EXISTS (SELECT 1 FROM production.sample_request WHERE blank_sku = p_sku)
        OR EXISTS (SELECT 1 FROM ordering.approved_sample WHERE blank_sku = p_sku)
        OR EXISTS (SELECT 1 FROM production.production_material_issue WHERE sku = p_sku)
$$;

COMMENT ON FUNCTION inventory.item_in_use(UUID, VARCHAR) IS
    'Whether a document uses this inventory item: such an item is neither renamed nor deleted. Stock alerts do not count (they follow the item).';
