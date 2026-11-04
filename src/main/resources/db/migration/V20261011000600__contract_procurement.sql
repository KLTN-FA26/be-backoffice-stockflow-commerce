-- =============================================================================
-- CONTRACT C4 - procurement: the new purchasing tables become the only purchasing model.
-- Activated from db/pending by the legacy-tables removal: the code no longer maps the old tables,
-- and V20261011000200 carried their rows over (and archived them in platform.legacy_archive).
-- PR #36 keeps its feature: po_delivery_decision and po_delivery_control follow the order, whose id
-- did not change, to procurement.purchase_orders.
-- The orphan check below stops with the table and the row count instead of failing half way.
-- =============================================================================

DO $$
DECLARE
    orphans BIGINT;
    check_sql TEXT;
BEGIN
    FOREACH check_sql IN ARRAY ARRAY[
        'warehouse.putaway_task.goods_receipt_id|SELECT count(*) FROM warehouse.putaway_task c WHERE c.goods_receipt_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM procurement.goods_receipts p WHERE p.id = c.goods_receipt_id)',
        'warehouse.putaway_task.goods_receipt_line_id|SELECT count(*) FROM warehouse.putaway_task c WHERE c.goods_receipt_line_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM procurement.goods_receipt_lines p WHERE p.id = c.goods_receipt_line_id)',
        'warehouse.putaway_task without exactly one source|SELECT count(*) FROM warehouse.putaway_task WHERE num_nonnulls(goods_receipt_line_id, transfer_order_line_id, return_request_line_id) <> 1'
    ] LOOP
        EXECUTE split_part(check_sql, '|', 2) INTO orphans;
        IF orphans > 0 THEN
            RAISE EXCEPTION 'C4: % row(s): %', orphans, split_part(check_sql, '|', 1);
        END IF;
    END LOOP;

    IF to_regclass('procurement.po_delivery_decision') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM procurement.po_delivery_decision c WHERE NOT EXISTS (SELECT 1 FROM procurement.purchase_orders p WHERE p.id = c.purchase_order_id)'
           INTO orphans;
        IF orphans > 0 THEN
            RAISE EXCEPTION 'C4: % po_delivery_decision row(s) point at no new purchase order', orphans;
        END IF;
    END IF;
    IF to_regclass('notification.po_delivery_control') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM notification.po_delivery_control c WHERE NOT EXISTS (SELECT 1 FROM procurement.purchase_orders p WHERE p.id = c.purchase_order_id)'
           INTO orphans;
        IF orphans > 0 THEN
            RAISE EXCEPTION 'C4: % po_delivery_control row(s) point at no new purchase order', orphans;
        END IF;
    END IF;
END $$;


-- Putaway knows exactly one source: a receipt line, a transfer line or a return line.
ALTER TABLE warehouse.putaway_task
    ADD CONSTRAINT fk_putaway_task_receipt FOREIGN KEY (goods_receipt_id)
        REFERENCES procurement.goods_receipts (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_putaway_task_receipt_line FOREIGN KEY (goods_receipt_line_id)
        REFERENCES procurement.goods_receipt_lines (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_putaway_task_source
        CHECK (num_nonnulls(goods_receipt_line_id, transfer_order_line_id, return_request_line_id) = 1),
    ADD CONSTRAINT ck_putaway_task_receipt_pair
        CHECK ((goods_receipt_id IS NULL) = (goods_receipt_line_id IS NULL));


-- PR #36 tables, if present: follow the purchase order to its new table.
DO $$
DECLARE
    fk TEXT;
BEGIN
    IF to_regclass('procurement.po_delivery_decision') IS NOT NULL THEN
        FOR fk IN SELECT conname FROM pg_constraint
                   WHERE conrelid = 'procurement.po_delivery_decision'::regclass AND contype = 'f'
                     AND confrelid = 'procurement.purchase_order'::regclass LOOP
            EXECUTE format('ALTER TABLE procurement.po_delivery_decision DROP CONSTRAINT %I', fk);
        END LOOP;
        ALTER TABLE procurement.po_delivery_decision
            ADD CONSTRAINT fk_po_delivery_decision_po FOREIGN KEY (purchase_order_id)
                REFERENCES procurement.purchase_orders (id) ON DELETE CASCADE;
    END IF;
    IF to_regclass('notification.po_delivery_control') IS NOT NULL THEN
        ALTER TABLE notification.po_delivery_control
            ADD CONSTRAINT fk_po_delivery_control_po FOREIGN KEY (purchase_order_id)
                REFERENCES procurement.purchase_orders (id) ON DELETE CASCADE;
    END IF;
END $$;


-- The old model. Children first, no CASCADE (an unexpected dependant must stop this, not vanish).
DROP TABLE procurement.qc_result;
DROP TABLE procurement.supplier_invoice;
DROP TABLE procurement.goods_receipt;
DROP TABLE procurement.po_line;
DROP TABLE procurement.purchase_order;
DROP TABLE procurement.supplier;
