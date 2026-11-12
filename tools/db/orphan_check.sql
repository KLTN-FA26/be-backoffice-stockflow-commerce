-- =============================================================================
-- Orphan report for the final schema: how many rows point at nothing through a reference that has NO foreign key.
-- Read-only. Run it on any database, after the demo seed or real data:
--
--   docker exec -i stockflow-postgres psql -U stockflow -d stockflow < tools/db/orphan_check.sql
--
-- Since ADR-0007 and the contract steps C1-C4 almost every cross-module reference is a real foreign key, and a key
-- cannot be orphaned, so this file no longer counts those (the old "live" and "C1".."C4" rows were vacuous: they counted
-- rows the database already refuses). What is left is the references that carry a value without a key.
--
--   section 1  value references (SKU, order number, LPN, carrier code) and ids whose column has no key: every row is
--              the number of children whose target is missing, and every row must be 0.
--   section 2  foreign keys and CHECKs the database holds NOT VALID (empty = nothing to clean).
--   section 3  reference-looking columns (uuid / text named *_id, *_by, *_code, *_number, *_no, sku, lpn) with neither a key nor an entry
--              in the reviewed list. A row here is a new reference nobody decided about: give it a key, count it in
--              section 1, or list it as reviewed with the reason. Empty = every such column is accounted for.
-- =============================================================================

WITH checks(reference, orphans) AS (
    VALUES
    ('catalog.catalog_entry.product_id -> product.products.id',
        (SELECT count(*) FROM catalog.catalog_entry c WHERE c.product_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM product.products p WHERE p.id = c.product_id))),
    ('design.design_draft.sku -> product.variants.sku',
        (SELECT count(*) FROM design.design_draft c WHERE c.sku IS NOT NULL AND NOT EXISTS (SELECT 1 FROM product.variants p WHERE p.sku = c.sku))),
    ('ordering.production_order_line.sku -> product.variants.sku',
        (SELECT count(*) FROM ordering.production_order_line c WHERE c.sku IS NOT NULL AND NOT EXISTS (SELECT 1 FROM product.variants p WHERE p.sku = c.sku))),
    ('ordering.return_request_discrepancy.sku -> product.variants.sku',
        (SELECT count(*) FROM ordering.return_request_discrepancy c WHERE c.sku IS NOT NULL AND NOT EXISTS (SELECT 1 FROM product.variants p WHERE p.sku = c.sku))),
    ('procurement.goods_receipt_return_cartons.sku -> product.variants.sku',
        (SELECT count(*) FROM procurement.goods_receipt_return_cartons c WHERE c.sku IS NOT NULL AND NOT EXISTS (SELECT 1 FROM product.variants p WHERE p.sku = c.sku))),
    ('procurement.goods_receipt_return_cartons.lpn -> inventory.stock_package.lpn',
        (SELECT count(*) FROM procurement.goods_receipt_return_cartons c WHERE c.lpn IS NOT NULL AND NOT EXISTS (SELECT 1 FROM inventory.stock_package p WHERE p.lpn = c.lpn))),
    ('ordering.delivery_failure_case.receipt_number -> procurement.goods_receipts.receipt_number',
        (SELECT count(*) FROM ordering.delivery_failure_case c WHERE NOT EXISTS (SELECT 1 FROM procurement.goods_receipts p WHERE p.id = c.receipt_id AND p.receipt_number = c.receipt_number))),
    ('ordering.delivery_failure_case_carton.sku -> product.variants.sku',
        (SELECT count(*) FROM ordering.delivery_failure_case_carton c WHERE NOT EXISTS (SELECT 1 FROM product.variants p WHERE p.sku = c.sku))),
    ('ordering.delivery_failure_case_carton.lpn -> inventory.stock_package.lpn',
        (SELECT count(*) FROM ordering.delivery_failure_case_carton c WHERE NOT EXISTS (SELECT 1 FROM inventory.stock_package p WHERE p.lpn = c.lpn))),
    ('fulfillment.carrier_event.carrier_code -> fulfillment.carrier.code',
        (SELECT count(*) FROM fulfillment.carrier_event c WHERE c.carrier_code IS NOT NULL AND NOT EXISTS (SELECT 1 FROM fulfillment.carrier p WHERE p.code = c.carrier_code))),
    ('fulfillment.pick.order_number -> ordering.customer_order.order_number',
        (SELECT count(*) FROM fulfillment.pick c WHERE c.order_number IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ordering.customer_order p WHERE p.order_number = c.order_number))),
    ('inventory.stock_movement.order_number -> ordering.customer_order.order_number',
        (SELECT count(*) FROM inventory.stock_movement c WHERE c.order_number IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ordering.customer_order p WHERE p.order_number = c.order_number))),
    ('inventory.stock_reservation.order_number -> ordering.customer_order.order_number',
        (SELECT count(*) FROM inventory.stock_reservation c WHERE c.order_number IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ordering.customer_order p WHERE p.order_number = c.order_number))),
    ('payment.installment.order_number -> ordering.customer_order.order_number',
        (SELECT count(*) FROM payment.installment c WHERE c.order_number IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ordering.customer_order p WHERE p.order_number = c.order_number))),
    ('payment.order_settlement.order_number -> ordering.customer_order.order_number',
        (SELECT count(*) FROM payment.order_settlement c WHERE c.order_number IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ordering.customer_order p WHERE p.order_number = c.order_number))),
    ('payment.refund_record.order_number -> ordering.customer_order.order_number',
        (SELECT count(*) FROM payment.refund_record c WHERE c.order_number IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ordering.customer_order p WHERE p.order_number = c.order_number))),
    ('ordering.return_request.order_number -> ordering.customer_order.order_number',
        (SELECT count(*) FROM ordering.return_request c WHERE c.order_number IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ordering.customer_order p WHERE p.order_number = c.order_number))),
    ('reporting.processed_order_events.order_id -> ordering.customer_order.id',
        (SELECT count(*) FROM reporting.processed_order_events c WHERE c.order_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ordering.customer_order p WHERE p.id = c.order_id))),
    ('reporting.processed_return_events.order_id -> ordering.customer_order.id',
        (SELECT count(*) FROM reporting.processed_return_events c WHERE c.order_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ordering.customer_order p WHERE p.id = c.order_id))),
    ('reporting.processed_return_events.return_request_id -> ordering.return_request.id',
        (SELECT count(*) FROM reporting.processed_return_events c WHERE c.return_request_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ordering.return_request p WHERE p.id = c.return_request_id))),
    ('reporting.processed_order_closures.order_id -> ordering.customer_order.id',
        (SELECT count(*) FROM reporting.processed_order_closures c WHERE c.order_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ordering.customer_order p WHERE p.id = c.order_id))),
    ('design.design_draft.template_code -> product.customization_templates.code',
        (SELECT count(*) FROM design.design_draft c WHERE c.template_code IS NOT NULL AND NOT EXISTS (SELECT 1 FROM product.customization_templates p WHERE p.code = c.template_code))),
    ('fulfillment.shipment.code -> fulfillment.shipment.code',
        (SELECT count(*) FROM fulfillment.shipment c WHERE c.code IS NOT NULL AND NOT EXISTS (SELECT 1 FROM fulfillment.shipment p WHERE p.code = c.code))),
    ('ordering.order_shipment.shipment_code -> fulfillment.shipment.code',
        (SELECT count(*) FROM ordering.order_shipment c WHERE c.shipment_code IS NOT NULL AND NOT EXISTS (SELECT 1 FROM fulfillment.shipment p WHERE p.code = c.shipment_code))),
    ('inventory.stock_lot.production_order_code -> ordering.production_order.code',
        (SELECT count(*) FROM inventory.stock_lot c WHERE c.production_order_code IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ordering.production_order p WHERE p.code = c.production_order_code))),
    ('inventory.stock_lot.goods_receipt_number -> procurement.goods_receipts.receipt_number',
        (SELECT count(*) FROM inventory.stock_lot c WHERE c.goods_receipt_number IS NOT NULL AND NOT EXISTS (SELECT 1 FROM procurement.goods_receipts p WHERE p.receipt_number = c.goods_receipt_number))),
    ('payment.installment.contract_number -> ordering.sales_contract.contract_number',
        (SELECT count(*) FROM payment.installment c WHERE c.contract_number IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ordering.sales_contract p WHERE p.contract_number = c.contract_number))),
    ('payment.order_settlement.contract_number -> ordering.sales_contract.contract_number',
        (SELECT count(*) FROM payment.order_settlement c WHERE c.contract_number IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ordering.sales_contract p WHERE p.contract_number = c.contract_number))),
    ('notification.delivery_log.template_code -> notification.template.code',
        (SELECT count(*) FROM notification.delivery_log c WHERE c.template_code IS NOT NULL AND NOT EXISTS (SELECT 1 FROM notification.template p WHERE p.code = c.template_code)))
)
SELECT 'values' AS section, reference, orphans, CASE WHEN orphans = 0 THEN 'ok' ELSE 'FIX' END AS status
FROM checks
ORDER BY orphans DESC, reference;

-- Section 2: foreign keys and CHECKs left NOT VALID on this database (a NOT VALID CHECK guards new rows only; old rows may still break it, VALIDATE CONSTRAINT says which).
SELECT conrelid::regclass AS table_name, conname AS not_valid_constraint, contype
FROM pg_constraint WHERE contype IN ('f', 'c') AND NOT convalidated ORDER BY 1, 2;

-- Section 3: reviewed means polymorphic (the target table depends on another column), an idempotency or correlation id,
-- an external system's id, the row's own identifier, or counted in section 1.
WITH reviewed(col, why) AS (
    VALUES
    ('catalog.catalog_entry.product_id', 'counted in section 1'),
    ('design.design_draft.sku', 'counted in section 1'),
    ('ordering.production_order_line.sku', 'counted in section 1'),
    ('ordering.return_request_discrepancy.sku', 'counted in section 1'),
    ('procurement.goods_receipt_return_cartons.sku', 'counted in section 1'),
    ('procurement.goods_receipt_return_cartons.lpn', 'counted in section 1'),
    ('ordering.delivery_failure_case.receipt_number', 'counted in section 1'),
    ('ordering.delivery_failure_case_carton.sku', 'counted in section 1'),
    ('ordering.delivery_failure_case_carton.lpn', 'counted in section 1'),
    ('fulfillment.carrier_event.carrier_code', 'counted in section 1'),
    ('fulfillment.pick.order_number', 'counted in section 1'),
    ('inventory.stock_movement.order_number', 'counted in section 1'),
    ('inventory.stock_reservation.order_number', 'counted in section 1'),
    ('payment.installment.order_number', 'counted in section 1'),
    ('payment.order_settlement.order_number', 'counted in section 1'),
    ('payment.refund_record.order_number', 'counted in section 1'),
    ('ordering.return_request.order_number', 'counted in section 1'),
    ('reporting.processed_order_events.order_id', 'counted in section 1'),
    ('reporting.processed_return_events.order_id', 'counted in section 1'),
    ('reporting.processed_return_events.return_request_id', 'counted in section 1'),
    ('reporting.processed_order_closures.order_id', 'counted in section 1'),
    ('design.design_draft.template_code', 'counted in section 1'),
    ('fulfillment.shipment.code', 'counted in section 1'),
    ('ordering.order_shipment.shipment_code', 'counted in section 1'),
    ('inventory.stock_lot.production_order_code', 'counted in section 1'),
    ('inventory.stock_lot.goods_receipt_number', 'counted in section 1'),
    ('payment.installment.contract_number', 'counted in section 1'),
    ('payment.order_settlement.contract_number', 'counted in section 1'),
    ('notification.delivery_log.template_code', 'counted in section 1'),
    ('chat.message.sender_id', 'customer or user, by sender type'),
    ('fulfillment.carrier_booking.carrier_order_id', 'the carrier''s own id'),
    ('fulfillment.dispatch_load.request_id', 'idempotency key'),
    ('fulfillment.dispatch_load_package.scan_id', 'idempotency key of the scan'),
    ('fulfillment.load_plan.request_id', 'idempotency key'),
    ('fulfillment.shipment.request_id', 'idempotency key'),
    ('fulfillment.shipment_event.carrier_event_id', 'the carrier''s event id'),
    ('inventory.stock_lot.purchase_order_id', 'purchase orders are gone; historical id only'),
    ('inventory.stock_lot.supplier_id', 'suppliers are gone; historical id only'),
    ('inventory.stock_movement.reference_id', 'polymorphic: reference_type names the table'),
    ('inventory.stock_reservation.request_id', 'idempotency key'),
    ('inventory.stock_reservation.root_request_id', 'idempotency key'),
    ('notification.delivery_log.provider_message_id', 'the provider''s own id'),
    ('notification.user_notification.source_id', 'polymorphic: the source type names the table'),
    ('ordering.customer_order.order_number', 'the order''s own number'),
    ('ordering.customer_order.request_id', 'idempotency key'),
    ('ordering.delay_alert.subject_id', 'redundant copy of production_order_id / delivery_batch_id, kept consistent by ck_delay_alert_subject'),
    ('ordering.quote.request_id', 'idempotency key'),
    ('ordering.return_request.request_id', 'idempotency key'),
    ('payment.order_settlement.source_id', 'polymorphic: kind names the table'),
    ('product.product_change_log.subject_id', 'polymorphic: subject_type names the table'),
    ('product.variants.sku', 'the SKU''s own definition'),
    ('inventory.stock_package.lpn', 'the carton''s own LPN'),
    ('warehouse.storage_location.location_code', 'the location''s own code'),
    ('reporting.processed_order_closures.closure_id', 'the closure event''s own id'),
    ('inventory.stock_lot.purchase_order_number', 'purchase orders are gone; historical text only'),
    ('fulfillment.carrier_booking.service_code', 'the carrier''s own service vocabulary'),
    ('fulfillment.waybill_import_row.load_code', 'text from an uploaded file; an unmatched row is the point'),
    ('inventory.cycle_count.count_number', 'the row''s own business number'),
    ('inventory.stock_adjustment.adjustment_number', 'the row''s own business number'),
    ('procurement.goods_receipts.receipt_number', 'the row''s own business number'),
    ('ordering.production_order_receipt.receipt_number', 'the row''s own business number'),
    ('ordering.quote.quote_number', 'the row''s own business number'),
    ('ordering.return_request.rma_number', 'the row''s own business number'),
    ('ordering.sales_contract.contract_number', 'the row''s own business number'),
    ('warehouse.move_task.task_number', 'the row''s own business number')
), reviewed_pattern(pat, why) AS (
    VALUES
    ('%reason_code', 'enumeration code, checked by a CHECK'),    ('%province_code', 'address code'),    ('%ward_code', 'address code'),    ('%postal_code', 'address code'),    ('%country_code', 'address code'),    ('carrier_city_code', 'the carrier''s city code'),    ('%tax_code', 'tax identifier of a party'),    ('lot_number', 'copy of the lot, which the row reaches by a keyed lot id or the lot table itself'),    ('%waybill_no', 'the carrier''s waybill number, free text'),    ('tracking_number', 'the carrier''s tracking number'),    ('scanned_code', 'what a scanner read, kept verbatim'),    ('scanned_location_code', 'what a scanner read, kept verbatim'),    ('label_code', 'the carton''s own label'),    ('vehicle_code', 'snapshot of the vehicle type''s code'),    ('service_code', 'the carrier''s own service vocabulary')
)
SELECT 'unreviewed' AS section, c.table_schema || '.' || c.table_name || '.' || c.column_name AS reference
FROM information_schema.columns c
JOIN information_schema.tables t USING (table_schema, table_name)
WHERE t.table_type = 'BASE TABLE'
  AND c.table_schema NOT IN ('pg_catalog', 'information_schema', 'public', 'platform')
  AND c.data_type IN ('uuid', 'character varying', 'text')
  AND (c.column_name LIKE '%\_id' OR c.column_name LIKE '%\_by'
       OR c.column_name LIKE '%\_code' OR c.column_name LIKE '%\_number' OR c.column_name LIKE '%\_no'
       OR c.column_name IN ('sku', 'lpn'))
  AND c.column_name NOT IN ('id', 'created_by', 'last_modified_by')
  AND NOT EXISTS (SELECT 1 FROM pg_constraint k
                    JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = ANY (k.conkey)
                   WHERE k.contype = 'f'
                     AND k.conrelid = (quote_ident(c.table_schema) || '.' || quote_ident(c.table_name))::regclass
                     AND a.attname = c.column_name)
  AND NOT EXISTS (SELECT 1 FROM reviewed_pattern rp WHERE c.column_name LIKE rp.pat)
  AND NOT EXISTS (SELECT 1 FROM reviewed r WHERE r.col = c.table_schema || '.' || c.table_name || '.' || c.column_name)
ORDER BY 2;
