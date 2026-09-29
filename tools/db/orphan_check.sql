-- =============================================================================
-- Orphan report: for every cross-module reference, how many rows point at nothing.
-- Read-only. Run it before applying a db/pending contract file, and after V20260928004000 warned
-- that a key stayed NOT VALID:
--
--   docker exec -i stockflow-postgres psql -U stockflow -d stockflow < tools/db/orphan_check.sql
--
-- "live"    keys exist since V20260928004000; orphans here mean old test rows. Fix or delete
--           them, then apply db/pending/C0__validate_foreign_keys.sql.
-- "C1".."C4" keys a contract step will add; they must be 0 before that step can run.
-- =============================================================================

WITH checks(step, reference, orphans) AS (
    VALUES
    ('live', 'inventory.stock_reservation.order_id -> ordering.customer_order',
        (SELECT count(*) FROM inventory.stock_reservation c WHERE NOT EXISTS (SELECT 1 FROM ordering.customer_order p WHERE p.id = c.order_id))),
    ('live', 'ordering.order_line_reservation.reservation_id -> inventory.stock_reservation',
        (SELECT count(*) FROM ordering.order_line_reservation c WHERE NOT EXISTS (SELECT 1 FROM inventory.stock_reservation p WHERE p.id = c.reservation_id))),
    ('live', 'ordering.customer_order.customer_id -> customer.customer',
        (SELECT count(*) FROM ordering.customer_order c WHERE c.customer_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM customer.customer p WHERE p.id = c.customer_id))),
    ('live', 'ordering.order_line.design_snapshot_id -> design.design_snapshot',
        (SELECT count(*) FROM ordering.order_line c WHERE c.design_snapshot_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM design.design_snapshot p WHERE p.id = c.design_snapshot_id))),
    ('live', 'ordering.order_hold.resolved_by -> identity.app_user',
        (SELECT count(*) FROM ordering.order_hold c WHERE c.resolved_by IS NOT NULL AND NOT EXISTS (SELECT 1 FROM identity.app_user p WHERE p.id = c.resolved_by))),
    ('live', 'payment.payment.order_id -> ordering.customer_order',
        (SELECT count(*) FROM payment.payment c WHERE NOT EXISTS (SELECT 1 FROM ordering.customer_order p WHERE p.id = c.order_id))),
    ('live', 'fulfillment.pick.order_id -> ordering.customer_order',
        (SELECT count(*) FROM fulfillment.pick c WHERE NOT EXISTS (SELECT 1 FROM ordering.customer_order p WHERE p.id = c.order_id))),
    ('live', 'fulfillment.pick.assigned_user_id -> identity.app_user',
        (SELECT count(*) FROM fulfillment.pick c WHERE c.assigned_user_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM identity.app_user p WHERE p.id = c.assigned_user_id))),
    ('live', 'fulfillment.shipment.order_id -> ordering.customer_order',
        (SELECT count(*) FROM fulfillment.shipment c WHERE NOT EXISTS (SELECT 1 FROM ordering.customer_order p WHERE p.id = c.order_id))),
    ('live', 'fulfillment.design_verification.order_id -> ordering.customer_order',
        (SELECT count(*) FROM fulfillment.design_verification c WHERE NOT EXISTS (SELECT 1 FROM ordering.customer_order p WHERE p.id = c.order_id))),
    ('live', 'fulfillment.design_verification.order_line_id -> ordering.order_line',
        (SELECT count(*) FROM fulfillment.design_verification c WHERE NOT EXISTS (SELECT 1 FROM ordering.order_line p WHERE p.id = c.order_line_id))),
    ('live', 'fulfillment.design_verification.snapshot_id -> design.design_snapshot',
        (SELECT count(*) FROM fulfillment.design_verification c WHERE NOT EXISTS (SELECT 1 FROM design.design_snapshot p WHERE p.id = c.snapshot_id))),
    ('live', 'fulfillment.design_verification.verified_by -> identity.app_user',
        (SELECT count(*) FROM fulfillment.design_verification c WHERE NOT EXISTS (SELECT 1 FROM identity.app_user p WHERE p.id = c.verified_by))),
    ('live', 'chat.conversation.customer_id -> customer.customer',
        (SELECT count(*) FROM chat.conversation c WHERE NOT EXISTS (SELECT 1 FROM customer.customer p WHERE p.id = c.customer_id))),
    ('live', 'chat.conversation.order_id -> ordering.customer_order',
        (SELECT count(*) FROM chat.conversation c WHERE c.order_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ordering.customer_order p WHERE p.id = c.order_id))),
    ('live', 'chat.conversation.assigned_agent_id -> identity.app_user',
        (SELECT count(*) FROM chat.conversation c WHERE c.assigned_agent_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM identity.app_user p WHERE p.id = c.assigned_agent_id))),
    ('live', 'design.design_draft.customer_id -> customer.customer',
        (SELECT count(*) FROM design.design_draft c WHERE NOT EXISTS (SELECT 1 FROM customer.customer p WHERE p.id = c.customer_id))),
    ('live', 'design.design_draft.{owner,assigned,reviewer,last_editor,reviewed_by} -> identity.app_user',
        (SELECT count(*) FROM design.design_draft c WHERE EXISTS (
            SELECT 1 FROM unnest(ARRAY[c.owner_user_id, c.assigned_user_id, c.reviewer_user_id, c.last_editor_user_id, c.reviewed_by]) u(id)
             WHERE u.id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM identity.app_user p WHERE p.id = u.id)))),
    ('live', 'design.design_snapshot.{confirmed_by,reviewed_by} -> identity.app_user',
        (SELECT count(*) FROM design.design_snapshot c WHERE EXISTS (
            SELECT 1 FROM unnest(ARRAY[c.confirmed_by, c.reviewed_by]) u(id)
             WHERE u.id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM identity.app_user p WHERE p.id = u.id)))),
    ('live', 'design.design_decision.actor_id -> identity.app_user',
        (SELECT count(*) FROM design.design_decision c WHERE NOT EXISTS (SELECT 1 FROM identity.app_user p WHERE p.id = c.actor_id))),
    ('live', 'customer.customer.user_id -> identity.app_user',
        (SELECT count(*) FROM customer.customer c WHERE c.user_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM identity.app_user p WHERE p.id = c.user_id))),
    ('live', 'catalog.pricing_rule.segment_id -> customer.segment',
        (SELECT count(*) FROM catalog.pricing_rule c WHERE c.segment_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM customer.segment p WHERE p.id = c.segment_id))),
    ('live', 'notification.preference.user_id -> identity.app_user',
        (SELECT count(*) FROM notification.preference c WHERE NOT EXISTS (SELECT 1 FROM identity.app_user p WHERE p.id = c.user_id))),

    ('C1', 'ordering.order_line.sku -> product.variants',
        (SELECT count(*) FROM ordering.order_line c WHERE NOT EXISTS (SELECT 1 FROM product.variants p WHERE p.sku = c.sku))),
    ('C1', 'catalog.catalog_entry.sku -> product.variants',
        (SELECT count(*) FROM catalog.catalog_entry c WHERE NOT EXISTS (SELECT 1 FROM product.variants p WHERE p.sku = c.sku))),
    ('C1', 'catalog.pricing_rule.sku -> product.variants',
        (SELECT count(*) FROM catalog.pricing_rule c WHERE c.sku IS NOT NULL AND NOT EXISTS (SELECT 1 FROM product.variants p WHERE p.sku = c.sku))),
    ('C1', 'reporting.product_sales_summary.sku -> product.variants',
        (SELECT count(*) FROM reporting.product_sales_summary c WHERE NOT EXISTS (SELECT 1 FROM product.variants p WHERE p.sku = c.sku))),
    ('C1', 'design.design_draft.product_id -> product.products',
        (SELECT count(*) FROM design.design_draft c WHERE NOT EXISTS (SELECT 1 FROM product.products p WHERE p.id = c.product_id))),
    ('C2', 'warehouse.warehouse without prefix/address/map frame',
        (SELECT count(*) FROM warehouse.warehouse WHERE prefix IS NULL OR address IS NULL OR map_unit IS NULL)),
    ('C2', 'inventory.stock_item.location_code -> warehouse.storage_location',
        (SELECT count(*) FROM inventory.stock_item c WHERE NOT EXISTS (SELECT 1 FROM warehouse.storage_location p WHERE p.location_code = c.location_code))),
    ('C2', 'warehouse.putaway_task.target_location_id -> warehouse.storage_location',
        (SELECT count(*) FROM warehouse.putaway_task c WHERE c.target_location_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM warehouse.storage_location p WHERE p.id = c.target_location_id))),
    ('C3', 'inventory.stock_item.sku -> inventory.inventory_items',
        (SELECT count(*) FROM inventory.stock_item c WHERE NOT EXISTS (SELECT 1 FROM inventory.inventory_items p WHERE p.sku = c.sku))),
    ('C3', 'warehouse.putaway_task.sku -> inventory.inventory_items',
        (SELECT count(*) FROM warehouse.putaway_task c WHERE NOT EXISTS (SELECT 1 FROM inventory.inventory_items p WHERE p.sku = c.sku))),
    ('C4', 'warehouse.putaway_task.goods_receipt_id -> procurement.goods_receipts',
        (SELECT count(*) FROM warehouse.putaway_task c WHERE c.goods_receipt_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM procurement.goods_receipts p WHERE p.id = c.goods_receipt_id))),
    ('C4', 'warehouse.putaway_task without exactly one source line',
        (SELECT count(*) FROM warehouse.putaway_task WHERE num_nonnulls(goods_receipt_line_id, transfer_order_line_id, return_request_line_id) <> 1))
)
SELECT step, reference, orphans, CASE WHEN orphans = 0 THEN 'ok' ELSE 'FIX' END AS status
FROM checks
ORDER BY step, orphans DESC, reference;

-- Keys V20260928004000 had to leave NOT VALID on this database (empty = nothing to clean).
SELECT conrelid::regclass AS table_name, conname AS not_valid_key
FROM pg_constraint WHERE contype = 'f' AND NOT convalidated ORDER BY 1, 2;
