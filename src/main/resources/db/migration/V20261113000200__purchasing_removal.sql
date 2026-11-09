-- =============================================================================
-- Purchasing leaves the system (kltn-docs 00 of 2026-11-04 "bỏ PO", glossary §8): finished goods are printed by
-- producers outside it and received against a Production Order (4A-2 .. 4A-5), so there is no purchase order,
-- supplier, supplier catalogue, supplier invoice, reorder proposal or subcontracting any more. 4A-8 removed the code;
-- this removes what the database kept for it. Nothing is discarded: every row dropped, and every value of a column
-- dropped or filled in, is archived whole in platform.legacy_archive first.
--
-- Kept on purpose:
--   * procurement.goods_receipts, goods_receipt_lines and goods_receipt_photos: receiving against production orders.
--     The schema keeps its name (renaming it is 4A-10);
--   * the PURCHASE receipt type and the purchase receipts there are, put away or not: their goods, cartons, lots and
--     ledger lines name them. No new one is started (procurement.check_goods_receipt);
--   * the procurement-goods-receipts permissions VIEW_PAGE, READ, CREATE and UPDATE (QD-15: the code keeps its prefix);
--   * the SUBCONTRACTOR kind of warehouse.storage_location and the locations of that kind: ledger lines name them. No
--     new one is created (warehouse.check_storage_location_owned);
--   * inventory.stock_damage_disposition.supplier_id and supplier_name, and the damage report's supplier shipments:
--     damage reporting goes as a whole with 5-6; only the foreign key into the suppliers goes here;
--   * inventory.stock_lot.purchase_order_id, purchase_order_number, supplier_id and supplier_name: a snapshot, taken
--     when the lot was opened, of the purchase order and supplier its goods came from. Nothing writes them any more
--     (a production order lot names neither, ck_stock_lot_production_order); the lot trace still shows them for old
--     lots, and damage reporting's return to supplier reads them until 5-6 removes it. Only their foreign keys go here,
--     so the ids are archive references from now on (the rows they named are archived in step 2);
--   * inventory.uom_conversions.default_purchase: the item's unit model, not purchasing;
--   * the PROCUREMENT_STAFF role, with the goods-receipt grants it keeps: it goes with 5-8.
--
-- Accepted consequences, stated so nobody is surprised:
--   * a line of an old purchase receipt counted in base units, or without a lot, now names the item's base unit and,
--     as its lot, the receipt's number (the stock lot it went into is the receipt's lot: inventory.stock_lot keeps
--     goods_receipt_number). The stock lots themselves are not renamed: their ledger lines name them as they were;
--   * the QC photos of old receipts leave the receipt (archived); their files stay in storage, claimed by
--     ArchivedPurchasingFileReferences so the orphaned-file sweep keeps them, as it keeps the purchase order
--     attachments archived here;
--   * the stock-damage notification that went to whoever could raise a reorder proposal reaches nobody until 5-6
--     removes it (StockDamageNotificationListener named that permission).
--
-- Order, so no step meets a reference a later step removes:
--   1. what must be settled first: submitted purchase orders still open, purchase receipts not yet counted in,
--      subcontract settlements waiting for approval. The previous version of the application settles them; nothing
--      here guesses;
--   2. archive;
--   3. event publications that can no longer be delivered (PurchaseOrderSent, its notification listener);
--   4. the SQL functions that read purchasing tables or columns are rewritten (Postgres does not track a function
--      body's tables: a DROP would pass and the function fail at its next call), the over-receipt tolerance trigger
--      that read suppliers.over_receipt_tolerance goes;
--   5. the QC photos;
--   6. foreign keys into purchasing, then the CHECKs that name purchase columns, then those columns, then the tables -
--      children first, never CASCADE - and the functions only their triggers used;
--   7. receipt lines get their unit and lot, both NOT NULL from now on; purchase-only statuses and photo stages leave
--      their CHECKs (no multi-column CHECK of a kept table loses a column it still needs: those of goods_receipts that
--      named purchase columns are about purchase receipts only);
--   8. permissions: grants first, advancing app_role.version of exactly the roles that lost one (ADR-0008), then the
--      permission rows.
--
-- Written so that it survives the sibling migrations merged before it: no function here names
-- catalog.pricing_rule (dropped by V20261110000100) or an expiry_date column (dropped by V20261111000300); where the
-- expiry still matters it is read through to_jsonb, which does not fail once the column is gone.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. What must be settled first
-- ---------------------------------------------------------------------------------------------
DO $$
DECLARE
    open_orders      TEXT;
    open_receipts    TEXT;
    open_settlements TEXT;
    no_base_unit     TEXT;
BEGIN
    -- A purchase order submitted and not settled - waiting for approval, sent, or expecting goods: a supplier may still
    -- deliver against it, and nothing could receive it once it is gone. A DRAFT was never submitted or sent: it is a
    -- worksheet nobody outside saw, archived and dropped like the rest.
    SELECT count(*) || ' (' || string_agg(po_number || ' ' || status, ', ' ORDER BY po_number) || ')'
      INTO open_orders
      FROM procurement.purchase_orders
     WHERE status NOT IN ('DRAFT', 'RECEIVED', 'CLOSED', 'CANCELLED');
    -- A purchase receipt still being counted or approved: its PO, supplier and approval columns go below.
    SELECT count(*) || ' (' || string_agg(receipt_number || ' ' || status, ', ' ORDER BY receipt_number) || ')'
      INTO open_receipts
      FROM procurement.goods_receipts
     WHERE receipt_type = 'PURCHASE' AND status IN ('DRAFT', 'PENDING_APPROVAL', 'CONFIRMED');
    -- A subcontract settlement whose excess loss waits for approval: an amount owed nobody has decided on.
    SELECT count(*) || ' (' || string_agg(p.po_number, ', ' ORDER BY p.po_number) || ')'
      INTO open_settlements
      FROM procurement.subcontract_settlement s
      JOIN procurement.purchase_orders p ON p.id = s.po_id
     WHERE s.status = 'AWAITING_APPROVAL';
    -- A receipt line counted in base units names the item's base unit from now on (step 7): the item must have one.
    SELECT count(*) || ' (' || string_agg(DISTINCT i.sku, ', ') || ')'
      INTO no_base_unit
      FROM procurement.goods_receipt_lines l
      JOIN inventory.inventory_items i ON i.id = l.inventory_item_id
     WHERE l.uom_conversion_id IS NULL
       AND NOT EXISTS (SELECT 1 FROM inventory.uom_conversions u
                        WHERE u.inventory_item_id = l.inventory_item_id AND u.is_base);

    IF open_orders IS NOT NULL OR open_receipts IS NOT NULL OR open_settlements IS NOT NULL
       OR no_base_unit IS NOT NULL THEN
        RAISE EXCEPTION 'purchasing cannot be removed yet - settle these with the version before 4A-8 first: '
                        'procurement.purchase_orders submitted and not received, closed or cancelled: %; '
                        'procurement.goods_receipts of type PURCHASE not yet counted in: %; '
                        'procurement.subcontract_settlement awaiting approval: %; '
                        'procurement.goods_receipt_lines in base units of an item without a base unit: %',
            COALESCE(open_orders, 'none'), COALESCE(open_receipts, 'none'), COALESCE(open_settlements, 'none'),
            COALESCE(no_base_unit, 'none')
            USING ERRCODE = 'check_violation';
    END IF;
END
$$;


-- ---------------------------------------------------------------------------------------------
-- 2. Archive
-- ---------------------------------------------------------------------------------------------
-- The tables step 6 drops, whole. row_id is the row's key where that key is one uuid, NULL otherwise.
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'procurement.suppliers', t.id, to_jsonb(t) FROM procurement.suppliers t
UNION ALL SELECT 'procurement.supplier_contacts', t.id, to_jsonb(t) FROM procurement.supplier_contacts t
UNION ALL SELECT 'procurement.supplier_addresses', t.id, to_jsonb(t) FROM procurement.supplier_addresses t
UNION ALL SELECT 'procurement.supplier_items', t.id, to_jsonb(t) FROM procurement.supplier_items t
UNION ALL SELECT 'procurement.supplier_item_prices', t.id, to_jsonb(t) FROM procurement.supplier_item_prices t
UNION ALL SELECT 'procurement.approval_limits', t.id, to_jsonb(t) FROM procurement.approval_limits t
UNION ALL SELECT 'procurement.replenishment_proposals', t.id, to_jsonb(t) FROM procurement.replenishment_proposals t
UNION ALL SELECT 'procurement.replenishment_proposal_lines', t.id, to_jsonb(t)
          FROM procurement.replenishment_proposal_lines t
UNION ALL SELECT 'procurement.purchase_orders', t.id, to_jsonb(t) FROM procurement.purchase_orders t
UNION ALL SELECT 'procurement.purchase_order_revisions', t.id, to_jsonb(t) FROM procurement.purchase_order_revisions t
UNION ALL SELECT 'procurement.purchase_order_lines', t.id, to_jsonb(t) FROM procurement.purchase_order_lines t
UNION ALL SELECT 'procurement.purchase_order_line_revisions', t.id, to_jsonb(t)
          FROM procurement.purchase_order_line_revisions t
UNION ALL SELECT 'procurement.purchase_order_approvals', t.id, to_jsonb(t) FROM procurement.purchase_order_approvals t
UNION ALL SELECT 'procurement.purchase_order_events', t.id, to_jsonb(t) FROM procurement.purchase_order_events t
-- ArchivedPurchasingFileReferences reads file_url from these rows: the documents stay in storage.
UNION ALL SELECT 'procurement.purchase_order_attachments', t.id, to_jsonb(t)
          FROM procurement.purchase_order_attachments t
UNION ALL SELECT 'procurement.po_delivery_decision', t.id, to_jsonb(t) FROM procurement.po_delivery_decision t
UNION ALL SELECT 'procurement.po_number_sequence', NULL, to_jsonb(t) FROM procurement.po_number_sequence t
UNION ALL SELECT 'procurement.subcontract_settlement', t.po_id, to_jsonb(t) FROM procurement.subcontract_settlement t
UNION ALL SELECT 'procurement.supplier_invoices', t.id, to_jsonb(t) FROM procurement.supplier_invoices t
UNION ALL SELECT 'procurement.supplier_invoice_lines', t.id, to_jsonb(t) FROM procurement.supplier_invoice_lines t
UNION ALL SELECT 'procurement.supplier_invoice_variances', t.id, to_jsonb(t)
          FROM procurement.supplier_invoice_variances t
UNION ALL SELECT 'notification.po_delivery_control', t.purchase_order_id, to_jsonb(t)
          FROM notification.po_delivery_control t
UNION ALL SELECT 'notification.po_retry_cursor', NULL, to_jsonb(t) FROM notification.po_retry_cursor t
UNION ALL SELECT 'warehouse.subcontractor_site', t.id, to_jsonb(t) FROM warehouse.subcontractor_site t
-- Purchase receipts and their lines, whole: they lose their PO, supplier, blind-receipt and approval columns, and a
-- line gets a unit and a lot where it had none (step 7). A production receipt has none of those values
-- (ck_goods_receipts_production, ck_goods_receipt_lines_source) and loses nothing.
UNION ALL SELECT 'procurement.goods_receipts.purchase', t.id, to_jsonb(t) FROM procurement.goods_receipts t
          WHERE t.receipt_type = 'PURCHASE'
UNION ALL SELECT 'procurement.goods_receipt_lines.purchase', t.id, to_jsonb(t) FROM procurement.goods_receipt_lines t
          WHERE t.receipt_id IN (SELECT id FROM procurement.goods_receipts WHERE receipt_type = 'PURCHASE')
-- The photos QC took (step 5), whole. ArchivedPurchasingFileReferences reads storage_key from these rows.
UNION ALL SELECT 'procurement.goods_receipt_photos', t.id, to_jsonb(t) FROM procurement.goods_receipt_photos t
          WHERE t.stage = 'QC'
-- The purchasing columns of items that step 6 drops, of the rows that used them.
UNION ALL SELECT 'inventory.inventory_items.purchasing', t.id,
                 jsonb_build_object('id', t.id, 'sku', t.sku, 'default_supplier_id', t.default_supplier_id,
                                    'reference_purchase_price', t.reference_purchase_price,
                                    'reference_purchase_currency', t.reference_purchase_currency,
                                    'lead_time_days', t.lead_time_days, 'moq', t.moq)
          FROM inventory.inventory_items t
          WHERE t.default_supplier_id IS NOT NULL OR t.reference_purchase_price IS NOT NULL
             OR t.reference_purchase_currency IS NOT NULL OR t.lead_time_days IS NOT NULL OR t.moq IS NOT NULL
-- Permissions and their grants (step 8).
UNION ALL SELECT 'identity.permission', t.id, to_jsonb(t) FROM identity.permission t
          WHERE t.code LIKE 'procurement-%'
            AND t.code NOT IN ('procurement-goods-receipts:VIEW_PAGE', 'procurement-goods-receipts:READ',
                               'procurement-goods-receipts:CREATE', 'procurement-goods-receipts:UPDATE')
UNION ALL SELECT 'identity.role_permission', t.id, to_jsonb(t) FROM identity.role_permission t
          WHERE t.permission_id IN (
                SELECT id FROM identity.permission
                 WHERE code LIKE 'procurement-%'
                   AND code NOT IN ('procurement-goods-receipts:VIEW_PAGE', 'procurement-goods-receipts:READ',
                                    'procurement-goods-receipts:CREATE', 'procurement-goods-receipts:UPDATE'));

COMMENT ON TABLE platform.legacy_archive IS
    'Rows of tables dropped by contracts C1-C4 (V20261011000200), by the removal of in-house production '
        '(V20261108000100), of receiving QC (V20261108000200), of transfers between warehouses (V20261108000300), '
        'of parcel shipping, the delivery-mode choice and COD (V20261108000400) and of the design check at packing '
        '(V20261108000500), the customization templates and print areas as they were before templates got a model '
        '(V20261108000600), design statuses renamed when customers began designing (V20261108000800), the staff '
        'design workflow''s assignments, its designs left waiting for confirmation and its permission '
        '(V20261108001000), the parcel carriers'' status before they were retired (V20261108001300), and '
        'purchasing - purchase orders, suppliers, supplier invoices, reorder proposals, subcontracting, the '
        'purchase columns of receipts and items, and the QC photos of receipts (V20261113000200). Read-only '
        'history.';


-- ---------------------------------------------------------------------------------------------
-- 3. Event publications
-- ---------------------------------------------------------------------------------------------
-- With republish-outstanding-events-on-restart, an unfinished publication of an event class or listener that is gone
-- fails at every start. Unfinished ones only: a completed publication is never read again. GoodsReceived and QcResult
-- had no publisher (02-procurement-receiving §2.3); they are named so none can be left behind.
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'public.event_publication', e.id, to_jsonb(e)
FROM event_publication e
WHERE e.completion_date IS NULL
  AND (e.event_type IN ('com.stockflow.contracts.PurchaseOrderSent',
                        'com.stockflow.contracts.GoodsReceived',
                        'com.stockflow.contracts.QcResult')
       OR e.listener_id LIKE 'com.stockflow.notification.internal.service.PurchaseOrderNotificationListener.%');

DELETE FROM event_publication e
WHERE e.completion_date IS NULL
  AND (e.event_type IN ('com.stockflow.contracts.PurchaseOrderSent',
                        'com.stockflow.contracts.GoodsReceived',
                        'com.stockflow.contracts.QcResult')
       OR e.listener_id LIKE 'com.stockflow.notification.internal.service.PurchaseOrderNotificationListener.%');


-- ---------------------------------------------------------------------------------------------
-- 4. Functions and triggers
-- ---------------------------------------------------------------------------------------------
-- "Does a document use this inventory item?" for the SKU rename guard (inventory.guard_item_sku_rename, which reads
-- nothing else of purchasing and is unchanged) and the DRAFT product delete. As V20261108002000 defined it, less the
-- supplier catalogue, reorder proposals and purchase order lines, plus the production order lines that replaced them
-- (a production order names its SKU, ordering.production_order_line.sku). catalog.pricing_rule is not read:
-- V20261110000100 drops it, and a SQL function naming a missing table cannot be created.
CREATE OR REPLACE FUNCTION inventory.item_in_use(p_item_id uuid, p_sku character varying)
    RETURNS boolean
    LANGUAGE sql
    STABLE
AS $function$
    SELECT EXISTS (SELECT 1 FROM inventory.stock_item WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_movement WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_adjustment WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.cycle_count_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_package WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM warehouse.putaway_task WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM warehouse.move_task WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM fulfillment.pick_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM procurement.goods_receipt_lines WHERE inventory_item_id = p_item_id)
        OR EXISTS (SELECT 1 FROM ordering.production_order_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM catalog.customer_price WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM ordering.quote_line WHERE blank_sku = p_sku OR sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_reservation WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_lot WHERE sku = p_sku)
$function$;

-- "Does a document use this unit?" - the factor lock (V20261020000600) - less the purchase order lines.
CREATE OR REPLACE FUNCTION inventory.unit_in_use(p_uom_conversion_id uuid)
    RETURNS boolean
    LANGUAGE sql
    STABLE
AS $function$
    SELECT EXISTS (SELECT 1 FROM inventory.stock_package WHERE uom_conversion_id = p_uom_conversion_id)
        OR EXISTS (SELECT 1 FROM procurement.goods_receipt_lines WHERE uom_conversion_id = p_uom_conversion_id)
        OR EXISTS (SELECT 1 FROM ordering.quote_line WHERE uom_conversion_id = p_uom_conversion_id)
        OR EXISTS (SELECT 1 FROM ordering.order_line WHERE uom_conversion_id = p_uom_conversion_id)
$function$;

-- A storage location is a bin or an area. Subcontractor sites go below: the SUBCONTRACTOR locations there are stay
-- (ledger lines name them), and a new one is refused because nothing owns it. Runs at the commit of every bin and area
-- created, so it must read only tables that exist.
CREATE OR REPLACE FUNCTION warehouse.check_storage_location_owned()
    RETURNS trigger
    LANGUAGE plpgsql
AS $function$
BEGIN
    IF EXISTS (SELECT 1 FROM warehouse.storage_location WHERE id = NEW.id)
       AND NOT EXISTS (SELECT 1 FROM warehouse.bin WHERE location_id = NEW.id)
       AND NOT EXISTS (SELECT 1 FROM warehouse.area WHERE location_id = NEW.id) THEN
        RAISE EXCEPTION 'storage_location % is owned by no bin and no area', NEW.location_code
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$function$;

-- What a receipt is against never changes, and no purchase receipt is started any more (kltn-docs 03 §1). A production
-- receipt is into the warehouse its production order is received at, and is started only while the production order
-- can receive goods (03 BR-01, BR-15): IN_PRODUCTION or PARTIALLY_RECEIVED; SENT or ACCEPTED when no print sample is
-- to be approved first. As V20261108002800 had it, less the purchase order checks.
CREATE OR REPLACE FUNCTION procurement.check_goods_receipt()
    RETURNS trigger
    LANGUAGE plpgsql
AS $function$
DECLARE
    production RECORD;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.receipt_type IS DISTINCT FROM OLD.receipt_type
            OR NEW.production_order_id IS DISTINCT FROM OLD.production_order_id THEN
            RAISE EXCEPTION 'receipt %: what it is against does not change', OLD.receipt_number
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF TG_OP = 'INSERT' AND NEW.receipt_type = 'PURCHASE' THEN
        RAISE EXCEPTION 'receipt %: purchasing was removed, goods are received against a production order',
            NEW.receipt_number USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.production_order_id IS NOT NULL THEN
        SELECT p.code, p.status, p.sample_approval_required, p.receiving_warehouse_id INTO production
          FROM ordering.production_order p WHERE p.id = NEW.production_order_id;
        IF NOT FOUND THEN
            RETURN NEW; -- fk_goods_receipts_production_order reports it
        END IF;
        IF NEW.warehouse_id IS DISTINCT FROM production.receiving_warehouse_id THEN
            RAISE EXCEPTION 'receipt % is into another warehouse than production order % is received at (03 BR-01)',
                NEW.receipt_number, production.code USING ERRCODE = 'check_violation';
        END IF;
        IF TG_OP = 'INSERT' AND NOT (production.status IN ('IN_PRODUCTION', 'PARTIALLY_RECEIVED')
            OR (production.status IN ('SENT', 'ACCEPTED') AND NOT production.sample_approval_required)) THEN
            RAISE EXCEPTION 'production order % is %: goods are not received against it (03 BR-01, BR-15)',
                production.code, production.status USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$function$;

-- Its column list named po_id, po_revision_id, supplier_id and referenced_po_id, which step 6 drops.
DROP TRIGGER tg_goods_receipts_check ON procurement.goods_receipts;
CREATE TRIGGER tg_goods_receipts_check
    BEFORE INSERT OR UPDATE OF warehouse_id, receipt_type, production_order_id
    ON procurement.goods_receipts
    FOR EACH ROW EXECUTE FUNCTION procurement.check_goods_receipt();

-- A line names a production order line exactly when its receipt is against a production order; such a line is of that
-- production order, of its SKU, in its lot with no expiry date (BR-03, BR-06, BR-11), in whole cartons of the line's
-- standard count in a packing unit holding exactly that many (BR-12, BR-13). The production order line is locked: two
-- receipts counting it are decided one after the other, so the deferred BR-02 check of the second sees what the first
-- committed. As V20261108002800 had it, less the purchase order line; the expiry date is read through to_jsonb because
-- V20261111000300 drops the column.
CREATE OR REPLACE FUNCTION procurement.check_goods_receipt_line()
    RETURNS trigger
    LANGUAGE plpgsql
AS $function$
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
                        WHERE u.id = NEW.uom_conversion_id AND NOT u.is_base
                          AND u.factor = production.units_per_carton) THEN
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
$function$;

-- A posted receipt's lines keep what was counted (BR-05). Only a DRAFT is counted now: PENDING_APPROVAL went with
-- the purchase receipts' tolerance (step 7). The expiry date is compared through to_jsonb (V20261111000300 drops it).
CREATE OR REPLACE FUNCTION procurement.check_goods_receipt_line_frozen()
    RETURNS trigger
    LANGUAGE plpgsql
AS $function$
DECLARE
    receipt_status VARCHAR(16);
BEGIN
    SELECT status INTO receipt_status FROM procurement.goods_receipts
     WHERE id = COALESCE(NEW.receipt_id, OLD.receipt_id);
    IF receipt_status IS NULL OR receipt_status = 'DRAFT' THEN
        RETURN COALESCE(NEW, OLD);
    END IF;
    IF TG_OP = 'UPDATE'
       AND NEW.receipt_id = OLD.receipt_id
       AND NEW.production_order_line_id IS NOT DISTINCT FROM OLD.production_order_line_id
       AND NEW.inventory_item_id = OLD.inventory_item_id AND NEW.location_id = OLD.location_id
       AND NEW.received_qty = OLD.received_qty
       AND NEW.lot_number IS NOT DISTINCT FROM OLD.lot_number
       AND (to_jsonb(NEW) -> 'expiry_date') IS NOT DISTINCT FROM (to_jsonb(OLD) -> 'expiry_date')
       AND NEW.uom_conversion_id IS NOT DISTINCT FROM OLD.uom_conversion_id THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'receipt is %: its lines keep what was counted (BR-05)', receipt_status
        USING ERRCODE = 'check_violation';
END
$function$;

-- A line's unit is a unit of the received item, and a retired unit takes nothing new. The exception for goods a
-- purchase order line had ordered in a retired unit went with purchase orders.
CREATE OR REPLACE FUNCTION procurement.check_goods_receipt_line_unit()
    RETURNS trigger
    LANGUAGE plpgsql
AS $function$
DECLARE
    unit     inventory.uom_conversions%ROWTYPE;
    new_unit BOOLEAN;
BEGIN
    PERFORM pg_advisory_xact_lock_shared(inventory.unit_lock_key(NEW.uom_conversion_id));
    SELECT * INTO unit FROM inventory.uom_conversions WHERE id = NEW.uom_conversion_id;
    IF NOT FOUND THEN
        RETURN NEW; -- the foreign key reports it
    END IF;
    IF unit.inventory_item_id <> NEW.inventory_item_id THEN
        RAISE EXCEPTION 'receipt line: the unit is not a unit of the received item'
            USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' THEN
        new_unit := TRUE;
    ELSE
        new_unit := NEW.uom_conversion_id IS DISTINCT FROM OLD.uom_conversion_id;
    END IF;
    IF new_unit AND NOT unit.active THEN
        RAISE EXCEPTION 'receipt line: unit % is retired; nothing new is received in it', unit.unit_of_measure
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$function$;

-- A photo of a line is of a line of its receipt. Only the RECEIPT stage is left (ck_goods_receipt_photos_stage).
CREATE OR REPLACE FUNCTION procurement.check_goods_receipt_photo_line()
    RETURNS trigger
    LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.receipt_line_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM procurement.goods_receipt_lines
             WHERE id = NEW.receipt_line_id AND receipt_id = NEW.receipt_id) THEN
        RAISE EXCEPTION 'receipt line % is not a line of receipt %', NEW.receipt_line_id, NEW.receipt_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$function$;

-- The over-receipt tolerance read suppliers.over_receipt_tolerance and purchase order lines. A production receipt has
-- no tolerance: tg_goods_receipt_lines_production_cartons refuses anything over (03 BR-02).
DROP TRIGGER tg_goods_receipts_tolerance ON procurement.goods_receipts;
DROP TRIGGER tg_goods_receipts_tolerance_insert ON procurement.goods_receipts;
DROP FUNCTION procurement.check_goods_receipt_tolerance();


-- ---------------------------------------------------------------------------------------------
-- 5. QC photos
-- ---------------------------------------------------------------------------------------------
-- Taken by receiving QC, which V20261108000200 removed; archived in step 2. The table is append-only, so its guard
-- steps aside for this one statement.
ALTER TABLE procurement.goods_receipt_photos DISABLE TRIGGER tg_goods_receipt_photos_append_only;
DELETE FROM procurement.goods_receipt_photos WHERE stage = 'QC';
ALTER TABLE procurement.goods_receipt_photos ENABLE TRIGGER tg_goods_receipt_photos_append_only;


-- ---------------------------------------------------------------------------------------------
-- 6. Foreign keys, CHECKs, columns, tables
-- ---------------------------------------------------------------------------------------------
-- Foreign keys from what stays into what goes.
ALTER TABLE procurement.goods_receipts
    DROP CONSTRAINT fk_goods_receipts_po,
    DROP CONSTRAINT fk_goods_receipts_revision,
    DROP CONSTRAINT fk_goods_receipts_referenced_po,
    DROP CONSTRAINT fk_goods_receipts_supplier,
    DROP CONSTRAINT fk_goods_receipts_approval_requested_by,
    DROP CONSTRAINT fk_goods_receipts_approval_decided_by;
ALTER TABLE procurement.goods_receipt_lines DROP CONSTRAINT fk_goods_receipt_lines_po_line;
ALTER TABLE inventory.inventory_items DROP CONSTRAINT fk_inventory_items_default_supplier;
-- The lot keeps its snapshot of where its goods came from (see the header); only the references go.
ALTER TABLE inventory.stock_lot
    DROP CONSTRAINT fk_stock_lot_purchase_order,
    DROP CONSTRAINT fk_stock_lot_supplier;
-- The column stays until 5-6 removes damage reporting (see the header); IF EXISTS in case 5-6 ran first.
ALTER TABLE IF EXISTS inventory.stock_damage_disposition DROP CONSTRAINT IF EXISTS fk_stock_damage_disposition_supplier;

-- The CHECKs that name a purchase column. Each would go with its column; dropped by name so the list is read here.
ALTER TABLE procurement.goods_receipts
    DROP CONSTRAINT ck_goods_receipts_po_pair,
    DROP CONSTRAINT ck_goods_receipts_blind,
    DROP CONSTRAINT ck_goods_receipts_blind_approved,
    DROP CONSTRAINT ck_goods_receipts_excess_rejected,
    DROP CONSTRAINT ck_goods_receipts_rejected,
    DROP CONSTRAINT ck_goods_receipts_four_eyes,
    DROP CONSTRAINT ck_goods_receipts_approval_requested,
    DROP CONSTRAINT ck_goods_receipts_approval_pending,
    DROP CONSTRAINT ck_goods_receipts_approval_decision,
    DROP CONSTRAINT ck_goods_receipts_approval_decided,
    DROP CONSTRAINT ck_goods_receipts_production;
ALTER TABLE procurement.goods_receipt_lines DROP CONSTRAINT ck_goods_receipt_lines_source;
ALTER TABLE inventory.inventory_items
    DROP CONSTRAINT ck_inventory_items_reference_price,
    DROP CONSTRAINT ck_inventory_items_lead_time,
    DROP CONSTRAINT ck_inventory_items_moq;

-- The columns; their indexes (ix_goods_receipts_po, _referenced_po, _supplier, ix_goods_receipt_lines_po_line,
-- uk_goods_receipt_lines_lot, uk_goods_receipt_lines_blind_lot, ix_inventory_items_default_supplier) go with them.
ALTER TABLE procurement.goods_receipts
    DROP COLUMN po_id,
    DROP COLUMN po_revision_id,
    DROP COLUMN supplier_id,
    DROP COLUMN blind_reason,
    DROP COLUMN referenced_po_id,
    DROP COLUMN approval_requested_at,
    DROP COLUMN approval_requested_by,
    DROP COLUMN approval_decision,
    DROP COLUMN approval_decided_at,
    DROP COLUMN approval_decided_by,
    DROP COLUMN approval_reason;
ALTER TABLE procurement.goods_receipt_lines DROP COLUMN po_line_id;
ALTER TABLE inventory.inventory_items
    DROP COLUMN default_supplier_id,
    DROP COLUMN reference_purchase_price,
    DROP COLUMN reference_purchase_currency,
    DROP COLUMN lead_time_days,
    DROP COLUMN moq;

-- The tables, children first. Three foreign keys close cycles (a purchase order and its revisions point at each other;
-- an order names the proposal it came from, whose lines name the order): they go first.
ALTER TABLE procurement.purchase_orders
    DROP CONSTRAINT fk_purchase_orders_active_revision,
    DROP CONSTRAINT fk_purchase_orders_pending_revision,
    DROP CONSTRAINT fk_purchase_orders_proposal;

DROP TABLE procurement.supplier_invoice_variances;
DROP TABLE procurement.supplier_invoice_lines;
DROP TABLE procurement.supplier_invoices;
DROP TABLE procurement.replenishment_proposal_lines;
DROP TABLE procurement.replenishment_proposals;
DROP TABLE procurement.purchase_order_line_revisions;
DROP TABLE procurement.purchase_order_approvals;
DROP TABLE procurement.purchase_order_events;
DROP TABLE procurement.purchase_order_attachments;
DROP TABLE procurement.purchase_order_lines;
DROP TABLE procurement.purchase_order_revisions;
DROP TABLE procurement.po_delivery_decision;
DROP TABLE notification.po_delivery_control;
DROP TABLE procurement.subcontract_settlement;
DROP TABLE procurement.purchase_orders;
DROP TABLE procurement.supplier_item_prices;
DROP TABLE procurement.supplier_items;
DROP TABLE procurement.supplier_addresses;
DROP TABLE procurement.supplier_contacts;
DROP TABLE warehouse.subcontractor_site;
DROP TABLE procurement.approval_limits;
DROP TABLE procurement.po_number_sequence;
DROP TABLE procurement.suppliers;
DROP TABLE notification.po_retry_cursor;

-- The functions only the dropped tables' triggers used.
DROP FUNCTION procurement.check_po_revision_pointers();
DROP FUNCTION procurement.check_po_line_revision();
DROP FUNCTION procurement.check_po_line_revision_pair();
DROP FUNCTION procurement.check_po_line_unit();
DROP FUNCTION procurement.check_subcontract_po();
DROP FUNCTION procurement.check_subcontract_po_single_line();
DROP FUNCTION procurement.guard_po_warehouse_change();
DROP FUNCTION procurement.guard_po_line_content_change();
DROP FUNCTION procurement.check_supplier_invoice();
DROP FUNCTION procurement.check_supplier_invoice_line();
DROP FUNCTION procurement.check_supplier_invoice_match();
DROP FUNCTION warehouse.check_subcontractor_site_location();


-- ---------------------------------------------------------------------------------------------
-- 7. Units and lots, statuses and stages
-- ---------------------------------------------------------------------------------------------
-- Every receipt line has a unit and a lot (kltn-docs 03 BR-03, BR-12): a production receipt's always had them
-- (check_goods_receipt_line). An old purchase line counted in base units names the item's base unit; one without a lot
-- takes its receipt's number, the identity of the stock lot it went into (header). Archived whole in step 2. The
-- line triggers step aside for this one statement: the receipts are posted (frozen), and an old item may lack what
-- receiving asks of a new line today (inventory.check_receivable_item).
ALTER TABLE procurement.goods_receipt_lines
    DISABLE TRIGGER tg_goods_receipt_lines_frozen,
    DISABLE TRIGGER tg_goods_receipt_lines_check,
    DISABLE TRIGGER tg_goods_receipt_lines_item,
    DISABLE TRIGGER tg_goods_receipt_lines_unit;

UPDATE procurement.goods_receipt_lines l
SET uom_conversion_id = COALESCE(l.uom_conversion_id,
                                 (SELECT u.id FROM inventory.uom_conversions u
                                   WHERE u.inventory_item_id = l.inventory_item_id AND u.is_base)),
    lot_number        = COALESCE(l.lot_number, r.receipt_number),
    version           = l.version + 1,
    last_modified_at  = NOW(),
    last_modified_by  = 'flyway'
FROM procurement.goods_receipts r
WHERE r.id = l.receipt_id
  AND (l.uom_conversion_id IS NULL OR l.lot_number IS NULL);

ALTER TABLE procurement.goods_receipt_lines
    ENABLE TRIGGER tg_goods_receipt_lines_frozen,
    ENABLE TRIGGER tg_goods_receipt_lines_check,
    ENABLE TRIGGER tg_goods_receipt_lines_item,
    ENABLE TRIGGER tg_goods_receipt_lines_unit;

ALTER TABLE procurement.goods_receipt_lines
    ALTER COLUMN uom_conversion_id SET NOT NULL,
    ALTER COLUMN lot_number SET NOT NULL;

-- PENDING_APPROVAL was the purchase receipts' over-tolerance wait; step 1 left none in it.
ALTER TABLE procurement.goods_receipts
    DROP CONSTRAINT ck_goods_receipts_status,
    DROP CONSTRAINT ck_goods_receipts_confirmed;
ALTER TABLE procurement.goods_receipts
    ADD CONSTRAINT ck_goods_receipts_status
        CHECK (status IN ('DRAFT', 'CONFIRMED', 'IN_PUTAWAY', 'CLOSED', 'CANCELLED')),
    ADD CONSTRAINT ck_goods_receipts_confirmed
        CHECK (status IN ('DRAFT', 'CANCELLED') OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL));

-- The default PURCHASE (V20261108002800) was the type of every receipt before production orders; it would now name a
-- type check_goods_receipt refuses. A writer says what its receipt is against.
ALTER TABLE procurement.goods_receipts ALTER COLUMN receipt_type DROP DEFAULT;

COMMENT ON COLUMN procurement.goods_receipts.receipt_type IS
    'PRODUCTION: against one production order (kltn-docs 03 §1). PURCHASE: a receipt of the purchasing there was '
        'before V20261113000200, kept as history; none is started any more.';

-- Only the warehouse's photos of what arrived are taken; the QC ones are archived (step 5).
ALTER TABLE procurement.goods_receipt_photos
    DROP CONSTRAINT ck_goods_receipt_photos_qc_line,
    DROP CONSTRAINT ck_goods_receipt_photos_stage;
ALTER TABLE procurement.goods_receipt_photos
    ADD CONSTRAINT ck_goods_receipt_photos_stage CHECK (stage = 'RECEIPT');


-- ---------------------------------------------------------------------------------------------
-- 8. Permissions
-- ---------------------------------------------------------------------------------------------
-- Purchase orders, suppliers and their catalogue, supplier invoices, reorder proposals, approval limits, and the
-- APPROVE action of goods receipts (the over-tolerance decision, gone with 4A-8). role_permission first, advancing the
-- version of every role that loses a grant in the same statement: deleting the permission rows first would cascade the
-- grants away without moving any version, and a signed-in holder would keep them in the cache for up to an hour.
WITH lost AS (
    DELETE FROM identity.role_permission rp
    USING identity.permission p
    WHERE p.id = rp.permission_id
      AND p.code LIKE 'procurement-%'
      AND p.code NOT IN ('procurement-goods-receipts:VIEW_PAGE', 'procurement-goods-receipts:READ',
                         'procurement-goods-receipts:CREATE', 'procurement-goods-receipts:UPDATE')
    RETURNING rp.role_id)
UPDATE identity.app_role r
SET version          = r.version + 1,
    last_modified_at = NOW(),
    last_modified_by = 'flyway'
WHERE r.id IN (SELECT role_id FROM lost);

DELETE FROM identity.permission
WHERE code LIKE 'procurement-%'
  AND code NOT IN ('procurement-goods-receipts:VIEW_PAGE', 'procurement-goods-receipts:READ',
                   'procurement-goods-receipts:CREATE', 'procurement-goods-receipts:UPDATE');
