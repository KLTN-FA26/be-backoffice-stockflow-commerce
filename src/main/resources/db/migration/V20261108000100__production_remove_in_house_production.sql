-- =============================================================================
-- In-house production leaves the system (kltn-docs 07/11, glossary §8 "Thuật ngữ không còn áp dụng"): goods are
-- printed and packed by producers outside it, received into stock in cartons and picked like any other stock. The
-- production module, its events and its permissions are gone from the code; this removes what the database kept for
-- them. Nothing is discarded: every row dropped, and every value of a column dropped, is archived whole in
-- platform.legacy_archive first (as V20261011000200 did for contracts C1-C4).
--
-- Kept on purpose:
--   * the IN_PRODUCTION values of ordering.customer_order.status, inventory.stock_item.status and the ledger's
--     CHECKs, and the PRODUCTION_ORDER ledger reference: history rows carry them (append-only tables);
--   * procurement's subcontracting (SUBCONTRACT purchase orders, procurement.subcontract_settlement, the print
--     subcontractor flag on suppliers) and warehouse.subcontractor_site: they are part of purchasing, which goes as a
--     whole once the Production Order replaces the purchase order. Only their foreign keys into the production schema
--     go here, and the purchase-order check no longer reads a production order;
--   * printing_started_at and its CHECK on ordering.customer_order: the Production Order sets it when it reaches
--     In Production (kltn-docs 17 BR-11).
--
-- Order, so no step meets a reference a later step removes:
--   1. archive;
--   2. orders still printed for move on: IN_PRODUCTION -> READY_TO_FULFILL, or PICKING when their task is open (edges
--      ordering.order_status_transition has); held ones resume there; the holds of their print lines are released;
--   3. production's own holds are archived and deleted, stock_item.reserved follows what is still HELD;
--   4. the SQL functions that read production tables are rewritten;
--   5. foreign keys into production, then the production tables (children first), then the schema;
--   6. columns only production wrote, and the CHECKs Postgres drops with them are recreated where still needed;
--   7. permissions and the PRODUCTION_STAFF role, advancing app_role.version of every role that changed (ADR-0008),
--      revoking the sessions of the people who held it;
--   8. event publications that can no longer be delivered (their class or listener is gone) - with
--      republish-outstanding-events-on-restart they would fail at every start.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. Archive
-- ---------------------------------------------------------------------------------------------
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'production.sample_request', t.id, to_jsonb(t) FROM production.sample_request t
UNION ALL SELECT 'production.production_order', t.id, to_jsonb(t) FROM production.production_order t
UNION ALL SELECT 'production.production_order_status_history', t.id, to_jsonb(t)
          FROM production.production_order_status_history t
UNION ALL SELECT 'production.production_material_issue', t.id, to_jsonb(t) FROM production.production_material_issue t
UNION ALL SELECT 'production.production_print_run', t.id, to_jsonb(t) FROM production.production_print_run t
UNION ALL SELECT 'production.production_qc_result', t.id, to_jsonb(t) FROM production.production_qc_result t
UNION ALL SELECT 'production.production_scrap', t.id, to_jsonb(t) FROM production.production_scrap t
UNION ALL SELECT 'production.production_subcontract_receipt', t.receipt_id, to_jsonb(t)
          FROM production.production_subcontract_receipt t
UNION ALL SELECT 'production.production_subcontract_hold', t.reservation_id, to_jsonb(t)
          FROM production.production_subcontract_hold t
UNION ALL SELECT 'production.production_subcontract_recall', t.id, to_jsonb(t)
          FROM production.production_subcontract_recall t
UNION ALL SELECT 'ordering.approved_sample', t.sample_request_id, to_jsonb(t) FROM ordering.approved_sample t
UNION ALL SELECT 'ordering.order_line_production', t.production_order_id, to_jsonb(t)
          FROM ordering.order_line_production t
-- Production's own holds, deleted in step 3.
UNION ALL SELECT 'inventory.stock_reservation', t.id, to_jsonb(t) FROM inventory.stock_reservation t
          WHERE t.production_order_id IS NOT NULL
-- Orders still printed for, as they were before step 2 moves them on.
UNION ALL SELECT 'ordering.customer_order', t.id, to_jsonb(t) FROM ordering.customer_order t
          WHERE t.status = 'IN_PRODUCTION' OR t.status_before_hold = 'IN_PRODUCTION' OR t.awaiting_production
-- The production-shortfall agreements on order lines (review 2026-10-25 of S6), whose columns step 6 drops. The
-- units closed under one stay in cancelled_quantity, which they were always part of.
UNION ALL SELECT 'ordering.order_line.shortfall', t.id,
                 jsonb_build_object('id', t.id, 'order_id', t.order_id,
                                    'shortfall_agreed_quantity', t.shortfall_agreed_quantity,
                                    'shortfall_agreed_note', t.shortfall_agreed_note,
                                    'shortfall_agreed_by', t.shortfall_agreed_by,
                                    'shortfall_agreed_at', t.shortfall_agreed_at,
                                    'shortfall_closed_quantity', t.shortfall_closed_quantity)
          FROM ordering.order_line t
          WHERE t.shortfall_agreed_quantity IS NOT NULL OR t.shortfall_closed_quantity > 0
-- Permissions, grants, the role and who held it (step 7).
UNION ALL SELECT 'identity.permission', t.id, to_jsonb(t) FROM identity.permission t WHERE t.code LIKE 'production-%'
UNION ALL SELECT 'identity.role_permission', t.id, to_jsonb(t) FROM identity.role_permission t
          WHERE t.permission_id IN (SELECT id FROM identity.permission WHERE code LIKE 'production-%')
             OR t.role_id IN (SELECT id FROM identity.app_role WHERE code = 'PRODUCTION_STAFF')
UNION ALL SELECT 'identity.app_role', t.id, to_jsonb(t) FROM identity.app_role t WHERE t.code = 'PRODUCTION_STAFF'
UNION ALL SELECT 'identity.user_role', t.id, to_jsonb(t) FROM identity.user_role t
          WHERE t.role_id IN (SELECT id FROM identity.app_role WHERE code = 'PRODUCTION_STAFF')
UNION ALL SELECT 'procurement.approval_limits', t.id, to_jsonb(t) FROM procurement.approval_limits t
          WHERE t.role_id IN (SELECT id FROM identity.app_role WHERE code = 'PRODUCTION_STAFF');

COMMENT ON TABLE platform.legacy_archive IS
    'Rows of tables dropped by contracts C1-C4 (V20261011000200) and by the removal of in-house production '
        '(V20261108000100), as they were. Read-only history.';


-- ---------------------------------------------------------------------------------------------
-- 2. Orders still printed for
-- ---------------------------------------------------------------------------------------------
-- An order still printed for: IN_PRODUCTION, held from there, or partly shipped and waiting for its prints
-- (awaiting_production). Its print lines will get no printed goods from in-house production now.
CREATE TEMPORARY TABLE production_orders_affected ON COMMIT DROP AS
SELECT o.id
FROM ordering.customer_order o
WHERE o.status = 'IN_PRODUCTION' OR (o.status = 'ON_HOLD' AND o.status_before_hold = 'IN_PRODUCTION')
   OR o.awaiting_production;

-- The holds those print lines still have: the blanks allocated for them on a shelf, or issued to the print shop.
-- Picking them would ship plain blanks, so they are released; goods production had already delivered for a line are a
-- CONSUMED hold, which stays (packing still counts it). Holds on goods issued to the print shop (stock rows
-- IN_PRODUCTION) are released whoever's they are; the rows stay, IN_PRODUCTION and unsellable, for an adjustment to
-- write off.
CREATE TEMPORARY TABLE production_released_holds ON COMMIT DROP AS
SELECT r.id, r.stock_item_id
FROM inventory.stock_reservation r
WHERE r.status = 'HELD' AND r.production_order_id IS NULL
  AND (r.id IN (SELECT olr.reservation_id
                FROM ordering.order_line_reservation olr
                JOIN ordering.order_line ol ON ol.id = olr.order_line_id
                WHERE ol.design_snapshot_id IS NOT NULL
                  AND ol.order_id IN (SELECT id FROM production_orders_affected))
       OR r.stock_item_id IN (SELECT id FROM inventory.stock_item WHERE status = 'IN_PRODUCTION'));

-- The stock rows whose holds steps 2 and 3 change: their reserved is recomputed at the end of step 3.
CREATE TEMPORARY TABLE production_held_rows ON COMMIT DROP AS
SELECT stock_item_id AS id FROM production_released_holds WHERE stock_item_id IS NOT NULL
UNION
SELECT stock_item_id FROM inventory.stock_reservation
WHERE production_order_id IS NOT NULL AND stock_item_id IS NOT NULL;

UPDATE inventory.stock_reservation r
SET status         = 'RELEASED',
    release_reason = 'PRODUCTION_CANCELLED',
    closed_at      = NOW()
WHERE r.id IN (SELECT id FROM production_released_holds);

-- IN_PRODUCTION moves on through an edge ordering.order_status_transition has: READY_TO_FULFILL, or PICKING when its
-- task was already open - its stock lines were picked while it was printed (PACK-EARLY), and a task is never opened
-- twice. An order READY_TO_FULFILL with a task open (production completed after its stock was picked) is PICKING too:
-- from there its goods are handed over, the rest closed or the order cancelled, as any order's. A print line with
-- nothing left to pick is closed by Sales once the rest has shipped, or the order is cancelled.
INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason, actor_id, occurred_at,
                                           version, created_at, created_by)
SELECT gen_random_uuid(), o.id, o.status,
       CASE WHEN o.fulfilment_started_at IS NOT NULL THEN 'PICKING' ELSE 'READY_TO_FULFILL' END,
       'IN_HOUSE_PRODUCTION_REMOVED', NULL, NOW(), 0, NOW(), 'flyway'
FROM ordering.customer_order o
WHERE o.status = 'IN_PRODUCTION'
   OR (o.status = 'READY_TO_FULFILL' AND o.fulfilment_started_at IS NOT NULL);

UPDATE ordering.customer_order
SET status           = 'READY_TO_FULFILL',
    version          = version + 1,
    last_modified_at = NOW(),
    last_modified_by = 'flyway'
WHERE status = 'IN_PRODUCTION';

UPDATE ordering.customer_order
SET status           = 'PICKING',
    version          = version + 1,
    last_modified_at = NOW(),
    last_modified_by = 'flyway'
WHERE status = 'READY_TO_FULFILL' AND fulfilment_started_at IS NOT NULL;

UPDATE ordering.customer_order
SET status_before_hold = CASE WHEN fulfilment_started_at IS NOT NULL THEN 'PICKING' ELSE 'READY_TO_FULFILL' END,
    version            = version + 1,
    last_modified_at   = NOW(),
    last_modified_by   = 'flyway'
WHERE status = 'ON_HOLD'
  AND (status_before_hold = 'IN_PRODUCTION'
       OR (status_before_hold = 'READY_TO_FULFILL' AND fulfilment_started_at IS NOT NULL));


-- ---------------------------------------------------------------------------------------------
-- 3. Production's own holds
-- ---------------------------------------------------------------------------------------------
-- A write-off production asked for may name one (ck_stock_adjustment_reservation_scrap): it keeps its quantity and
-- reason, and no longer names the hold - which link is archived first.
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'inventory.stock_adjustment.reservation', a.id, jsonb_build_object('id', a.id, 'reservation_id', a.reservation_id)
FROM inventory.stock_adjustment a
JOIN inventory.stock_reservation r ON r.id = a.reservation_id
WHERE r.production_order_id IS NOT NULL;

UPDATE inventory.stock_adjustment a
SET reservation_id   = NULL,
    version          = a.version + 1,
    last_modified_at = NOW(),
    last_modified_by = 'flyway'
FROM inventory.stock_reservation r
WHERE r.id = a.reservation_id AND r.production_order_id IS NOT NULL;

-- The production tables reference these holds; they go first (step 5 drops them, so only the references matter).
ALTER TABLE production.production_material_issue DROP CONSTRAINT fk_production_material_issue_reservation;
ALTER TABLE production.production_subcontract_hold DROP CONSTRAINT fk_production_subcontract_hold_reservation;

DELETE FROM inventory.stock_reservation WHERE production_order_id IS NOT NULL;

-- reserved is the denormalised sum of the row's HELD holds (V20261023000100).
UPDATE inventory.stock_item si
SET reserved         = COALESCE((SELECT SUM(r.quantity) FROM inventory.stock_reservation r
                                 WHERE r.stock_item_id = si.id AND r.status = 'HELD'), 0),
    version          = si.version + 1,
    last_modified_at = NOW(),
    last_modified_by = 'flyway'
WHERE si.id IN (SELECT id FROM production_held_rows);


-- ---------------------------------------------------------------------------------------------
-- 4. SQL functions that read production tables (Postgres does not track what a function body reads)
-- ---------------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION inventory.item_in_use(p_item_id UUID, p_sku VARCHAR) RETURNS BOOLEAN
    LANGUAGE sql STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM inventory.stock_item WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_movement WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_adjustment WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.cycle_count_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.transfer_order_line WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_package WHERE inventory_item_id = p_item_id)
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
        OR EXISTS (SELECT 1 FROM inventory.stock_reservation WHERE sku = p_sku)
        OR EXISTS (SELECT 1 FROM inventory.stock_lot WHERE sku = p_sku)
$$;

-- A SUBCONTRACT purchase order still goes to a print subcontractor; there is no production order to check it against.
CREATE OR REPLACE FUNCTION procurement.check_subcontract_po()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.po_type <> 'SUBCONTRACT' THEN
        RETURN NEW;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM procurement.suppliers s
                    WHERE s.id = NEW.supplier_id AND s.is_print_subcontractor) THEN
        RAISE EXCEPTION 'SUBCONTRACT PO % must go to a print subcontractor', NEW.po_number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;


-- ---------------------------------------------------------------------------------------------
-- 5. Foreign keys into production, then its tables (children first), then the schema
-- ---------------------------------------------------------------------------------------------
ALTER TABLE inventory.stock_reservation DROP CONSTRAINT fk_stock_reservation_production_order;
ALTER TABLE procurement.purchase_orders DROP CONSTRAINT fk_purchase_orders_production_order;
ALTER TABLE procurement.subcontract_settlement DROP CONSTRAINT fk_subcontract_settlement_production_order;

DROP TABLE ordering.approved_sample;
DROP TABLE ordering.order_line_production;

DROP TABLE production.production_subcontract_hold;
DROP TABLE production.production_subcontract_receipt;
DROP TABLE production.production_subcontract_recall;
DROP TABLE production.production_material_issue;
DROP TABLE production.production_print_run;
DROP TABLE production.production_qc_result;
DROP TABLE production.production_scrap;
DROP TABLE production.production_order_status_history;
DROP TABLE production.production_order;
DROP TABLE production.sample_request;
DROP SCHEMA production;


-- ---------------------------------------------------------------------------------------------
-- 6. Columns only production wrote
-- ---------------------------------------------------------------------------------------------
-- Dropping production_order_id drops every CHECK naming it: ck_stock_reservation_production_pinned is not wanted any
-- more, ck_stock_reservation_owner and ck_stock_reservation_kind are, without it.
ALTER TABLE inventory.stock_reservation DROP COLUMN production_order_id;
ALTER TABLE inventory.stock_reservation
    ADD CONSTRAINT ck_stock_reservation_owner CHECK (num_nonnulls(order_id, transfer_order_id) = 1);
ALTER TABLE inventory.stock_reservation
    ADD CONSTRAINT ck_stock_reservation_kind CHECK (
        (stock_item_id IS NOT NULL AND status <> 'ALLOCATED')
        OR (stock_item_id IS NULL AND order_id IS NOT NULL AND transfer_order_id IS NULL
            AND allocated_from_id IS NULL AND status <> 'CONSUMED'));
COMMENT ON COLUMN inventory.stock_item.reserved IS
    'Denormalised sum of the HELD stock_reservation rows on this stock item (allocations and transfer holds); SKU '
        'reservations are not on any row.';

-- PACK-EARLY's wait for production (drops ck_order_awaiting_production with it).
ALTER TABLE ordering.customer_order DROP COLUMN awaiting_production;

-- The production-shortfall agreement (S6, archived in step 1; drops ck_order_line_shortfall_agreement,
-- ck_order_line_shortfall_closed and fk_order_line_shortfall_agreed_by with them).
ALTER TABLE ordering.order_line
    DROP COLUMN shortfall_agreed_quantity,
    DROP COLUMN shortfall_agreed_note,
    DROP COLUMN shortfall_agreed_by,
    DROP COLUMN shortfall_agreed_at,
    DROP COLUMN shortfall_closed_quantity;


-- ---------------------------------------------------------------------------------------------
-- 7. Permissions and the PRODUCTION_STAFF role
-- ---------------------------------------------------------------------------------------------
-- role_permission first, advancing the version of every role that loses a grant in the same statement: deleting the
-- permission rows first would cascade the grants away without moving any version, and holders would keep the
-- production grants cached for up to an hour.
WITH lost AS (
    DELETE FROM identity.role_permission rp
    USING identity.permission p
    WHERE p.id = rp.permission_id AND p.code LIKE 'production-%'
    RETURNING rp.role_id)
UPDATE identity.app_role r
SET version          = r.version + 1,
    last_modified_at = NOW(),
    last_modified_by = 'flyway'
WHERE r.id IN (SELECT role_id FROM lost);

DELETE FROM identity.permission WHERE code LIKE 'production-%';

-- Who held PRODUCTION_STAFF. The print shop is gone: an account with no other role is disabled (a staff account
-- without a role is not allowed, kltn-docs 00 §2.5.1); one with another role keeps it.
CREATE TEMPORARY TABLE production_staff ON COMMIT DROP AS
SELECT ur.user_id,
       NOT EXISTS (SELECT 1 FROM identity.user_role other
                   WHERE other.user_id = ur.user_id AND other.role_id <> ur.role_id) AS only_role
FROM identity.user_role ur
JOIN identity.app_role r ON r.id = ur.role_id
WHERE r.code = 'PRODUCTION_STAFF';

INSERT INTO identity.user_status_change (id, user_id, action, from_status, to_status, reason, actor_id, actor_username,
                                         changed_at, version, created_at, created_by)
SELECT gen_random_uuid(), u.id, 'DISABLE', u.status, 'DISABLED',
       'In-house production was removed: the role PRODUCTION_STAFF no longer exists', NULL, 'flyway', NOW(), 0, NOW(),
       'flyway'
FROM identity.app_user u
JOIN production_staff ps ON ps.user_id = u.id
WHERE ps.only_role AND u.status <> 'DISABLED';

UPDATE identity.app_user u
SET status           = 'DISABLED',
    version          = u.version + 1,
    last_modified_at = NOW(),
    last_modified_by = 'flyway'
FROM production_staff ps
WHERE ps.user_id = u.id AND ps.only_role AND u.status <> 'DISABLED';

-- Their tokens name a role that no longer exists: signing in again (if still allowed) issues a token without it.
UPDATE identity.user_session s
SET revoked_at       = NOW(),
    revoked_reason   = CASE WHEN ps.only_role THEN 'ACCOUNT_DISABLED' ELSE 'ROLE_CHANGED' END,
    version          = s.version + 1,
    last_modified_at = NOW(),
    last_modified_by = 'flyway'
FROM production_staff ps
WHERE ps.user_id = s.user_id AND s.revoked_at IS NULL AND s.expires_at > NOW();

-- Its grants, user_role rows and any approval limit go with it (ON DELETE CASCADE; all archived in step 1).
DELETE FROM identity.app_role WHERE code = 'PRODUCTION_STAFF';


-- ---------------------------------------------------------------------------------------------
-- 8. Event publications that can no longer be delivered
-- ---------------------------------------------------------------------------------------------
-- Unfinished ones only: a completed publication is never read again. Archived like everything else.
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'public.event_publication', e.id, to_jsonb(e)
FROM event_publication e
WHERE e.completion_date IS NULL
  AND (e.event_type IN ('com.stockflow.contracts.OrderLinesReleasedForProduction',
                        'com.stockflow.contracts.ProductionCompleted',
                        'com.stockflow.contracts.ProductionOrderHeld',
                        'com.stockflow.contracts.SampleApproved')
       OR e.listener_id LIKE 'com.stockflow.production.%'
       OR e.listener_id LIKE 'com.stockflow.order.internal.service.ProductionEventListener.%'
       OR e.listener_id LIKE 'com.stockflow.notification.internal.service.ProductionNotificationListener.%'
       OR e.listener_id LIKE 'com.stockflow.reporting.internal.service.SalesRollupListener.on(com.stockflow.contracts.OrderRemainderClosed)%');

DELETE FROM event_publication e
WHERE e.completion_date IS NULL
  AND (e.event_type IN ('com.stockflow.contracts.OrderLinesReleasedForProduction',
                        'com.stockflow.contracts.ProductionCompleted',
                        'com.stockflow.contracts.ProductionOrderHeld',
                        'com.stockflow.contracts.SampleApproved')
       OR e.listener_id LIKE 'com.stockflow.production.%'
       OR e.listener_id LIKE 'com.stockflow.order.internal.service.ProductionEventListener.%'
       OR e.listener_id LIKE 'com.stockflow.notification.internal.service.ProductionNotificationListener.%'
       OR e.listener_id LIKE 'com.stockflow.reporting.internal.service.SalesRollupListener.on(com.stockflow.contracts.OrderRemainderClosed)%');
