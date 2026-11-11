-- =============================================================================
-- Receiving QC leaves the system (kltn-docs 03 BR-07, decided 2026-11-04): the producer checks quality, the warehouse
-- only counts cartons, and a confirmed receipt goes straight from the RECEIVING area to putaway. The QC steps (move to
-- the QC area, inspect, QC photos), the item's "needs QC" flag and the QC_STAFF role are gone from the code; this
-- removes what the database kept for them. Every row dropped, and every value of a column dropped, is archived whole in
-- platform.legacy_archive first.
--
-- Kept on purpose:
--   * the QUALITY_CONTROL area type (warehouse.area, ck_area_type): returns still receive into one until the return
--     receipt replaces them, and the stock standing in one stays where it is;
--   * the QC_INSPECTION ledger reference and the BLOCKED / QUARANTINE stock statuses: history rows carry them;
--   * the photos QC took (stage QC of procurement.goods_receipt_photos, append-only evidence): no new one is accepted.
--
-- Accepted consequences, stated so nobody is surprised:
--   * supplier invoice matching bills every unit received: units QC rejected or quarantined on an old receipt are
--     billable if its invoice is matched again (the quantities are kept in the archive only). Supplier invoices go
--     with purchase orders;
--   * a design draft whose reviewer was a QC user keeps that reviewer, who can no longer approve it: a Sales Manager
--     assigns a new one (PUT /api/v1/designs/{id}/assignment).
--
-- Order, so no step meets a reference a later step removes:
--   1. archive;
--   2. receipts that were in QC move on, as if every unit not yet decided had been accepted: their putaway tasks are
--      opened as PutawayTaskCreator opens them, and the receipt goes to putaway. A receipt part of whose goods QC
--      quarantined or rejected is closed: those units are never put away from it, so it would never close on its own.
--      Receipts of SUBCONTRACT orders, closed at confirmation while in-house production took their goods over, get
--      putaway tasks for what of their goods is still INBOUND: nothing takes them over any more;
--   3. move tasks opened to carry received goods to the QC area;
--   4. the SQL functions that read QC columns are rewritten, the QC triggers and their functions dropped;
--   5. procurement.qc_inspections;
--   6. the QC columns, and the CHECKs that listed IN_QC or RECEIPT_QC;
--   7. permissions and the QC_STAFF role: design approval moves to the Sales Manager (kltn-docs 12), the QC task
--      permissions go, QC staff become warehouse staff, advancing app_role.version of every role whose grants change.
-- No event publication needs cleaning: no event class or listener went with QC.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. Archive
-- ---------------------------------------------------------------------------------------------
INSERT INTO platform.legacy_archive (source_table, row_id, row_data)
SELECT 'procurement.qc_inspections', t.id, to_jsonb(t) FROM procurement.qc_inspections t
-- The QC columns step 6 drops, of the lines that used them.
UNION ALL SELECT 'procurement.goods_receipt_lines.qc', t.id,
                 jsonb_build_object('id', t.id, 'receipt_id', t.receipt_id, 'qc_required', t.qc_required,
                                    'qc_location_id', t.qc_location_id, 'moved_to_qc_at', t.moved_to_qc_at,
                                    'moved_to_qc_by', t.moved_to_qc_by)
          FROM procurement.goods_receipt_lines t
          WHERE t.qc_required OR t.qc_location_id IS NOT NULL
UNION ALL SELECT 'inventory.inventory_items.qc_required', t.id,
                 jsonb_build_object('id', t.id, 'sku', t.sku, 'qc_required', t.qc_required)
          FROM inventory.inventory_items t
          WHERE t.qc_required
-- Receipts step 2 moves on, as they were.
UNION ALL SELECT 'procurement.goods_receipts', t.id, to_jsonb(t) FROM procurement.goods_receipts t
          WHERE t.status = 'IN_QC'
             OR (t.status = 'IN_PUTAWAY' AND EXISTS (
                    SELECT 1 FROM procurement.qc_inspections q
                    JOIN procurement.goods_receipt_lines l ON l.id = q.receipt_line_id
                    WHERE l.receipt_id = t.id AND q.outcome <> 'ACCEPTED'))
-- Move tasks to the QC area (step 3).
UNION ALL SELECT 'warehouse.move_task', t.id, to_jsonb(t) FROM warehouse.move_task t WHERE t.origin = 'RECEIPT_QC'
-- Permissions, grants, the role and who held it (step 7).
UNION ALL SELECT 'identity.permission', t.id, to_jsonb(t) FROM identity.permission t
          WHERE t.code LIKE 'procurement-qc-tasks:%'
UNION ALL SELECT 'identity.role_permission', t.id, to_jsonb(t) FROM identity.role_permission t
          WHERE t.permission_id IN (SELECT id FROM identity.permission WHERE code LIKE 'procurement-qc-tasks:%')
             OR t.role_id IN (SELECT id FROM identity.app_role WHERE code = 'QC_STAFF')
UNION ALL SELECT 'identity.app_role', t.id, to_jsonb(t) FROM identity.app_role t WHERE t.code = 'QC_STAFF'
UNION ALL SELECT 'identity.user_role', t.id, to_jsonb(t) FROM identity.user_role t
          WHERE t.role_id IN (SELECT id FROM identity.app_role WHERE code = 'QC_STAFF')
UNION ALL SELECT 'procurement.approval_limits', t.id, to_jsonb(t) FROM procurement.approval_limits t
          WHERE t.role_id IN (SELECT id FROM identity.app_role WHERE code = 'QC_STAFF');

COMMENT ON TABLE platform.legacy_archive IS
    'Rows of tables dropped by contracts C1-C4 (V20261011000200), by the removal of in-house production '
        '(V20261108000100) and of receiving QC (V20261108000200), as they were. Read-only history.';


-- ---------------------------------------------------------------------------------------------
-- 2. Receipts that were in QC
-- ---------------------------------------------------------------------------------------------
-- The lines QC had not decided on yet, of receipts still in QC. Their goods are INBOUND, at the receiving area or at
-- the QC area they were moved to; they are accepted as they stand. A line that already has a putaway task was
-- processed and is skipped, as PutawayTaskCreator skips it.
CREATE TEMPORARY TABLE qc_undecided_lines ON COMMIT DROP AS
SELECT l.id, l.receipt_id, l.received_qty::int AS received_qty, i.sku
FROM procurement.goods_receipt_lines l
JOIN procurement.goods_receipts r ON r.id = l.receipt_id
JOIN inventory.inventory_items i ON i.id = l.inventory_item_id
WHERE r.status = 'IN_QC'
  AND l.qc_required
  AND NOT EXISTS (SELECT 1 FROM procurement.qc_inspections q WHERE q.receipt_line_id = l.id)
  AND NOT EXISTS (SELECT 1 FROM warehouse.putaway_task t WHERE t.goods_receipt_line_id = l.id);

-- One task per carton of the line that is IN_STOCK with INBOUND units, wherever it stands, sized by those units...
CREATE TEMPORARY TABLE qc_undecided_cartons ON COMMIT DROP AS
SELECT u.id AS line_id, u.receipt_id, u.sku, p.id AS stock_package_id, SUM(s.on_hand)::int AS units
FROM qc_undecided_lines u
JOIN inventory.stock_package p ON p.goods_receipt_line_id = u.id AND p.status = 'IN_STOCK'
JOIN inventory.stock_item s ON s.stock_package_id = p.id AND s.status = 'INBOUND'
GROUP BY u.id, u.receipt_id, u.sku, p.id
HAVING SUM(s.on_hand) > 0;

INSERT INTO warehouse.putaway_task (id, goods_receipt_id, goods_receipt_line_id, sku, quantity, stock_package_id,
                                    status, version, created_at, created_by)
SELECT gen_random_uuid(), c.receipt_id, c.line_id, c.sku, c.units, c.stock_package_id, 'PENDING', 0, NOW(), 'flyway'
FROM qc_undecided_cartons c;

-- ...plus one loose task for what the line has beyond those cartons.
INSERT INTO warehouse.putaway_task (id, goods_receipt_id, goods_receipt_line_id, sku, quantity, stock_package_id,
                                    status, version, created_at, created_by)
SELECT gen_random_uuid(), u.receipt_id, u.id, u.sku,
       u.received_qty - COALESCE((SELECT SUM(c.units) FROM qc_undecided_cartons c WHERE c.line_id = u.id), 0),
       NULL, 'PENDING', 0, NOW(), 'flyway'
FROM qc_undecided_lines u
WHERE u.received_qty - COALESCE((SELECT SUM(c.units) FROM qc_undecided_cartons c WHERE c.line_id = u.id), 0) > 0;

-- A receipt is put away when every unit it received is (GoodsReceipt.closeIfPutAway). Units QC quarantined or rejected
-- are not put away from it - they wait in a quarantine area for inventory control - so a receipt holding some would
-- never close: it is closed now. Its open putaway tasks are still worked as usual; finishing them changes nothing on a
-- closed receipt.
UPDATE procurement.goods_receipts r
SET status           = 'CLOSED',
    closed_at        = NOW(),
    version          = r.version + 1,
    last_modified_at = NOW(),
    last_modified_by = 'flyway'
WHERE r.status IN ('IN_QC', 'IN_PUTAWAY')
  AND EXISTS (SELECT 1 FROM procurement.qc_inspections q
              JOIN procurement.goods_receipt_lines l ON l.id = q.receipt_line_id
              WHERE l.receipt_id = r.id AND q.outcome <> 'ACCEPTED');

-- The rest goes to putaway; it closes when its last task is done.
UPDATE procurement.goods_receipts r
SET status           = 'IN_PUTAWAY',
    version          = r.version + 1,
    last_modified_at = NOW(),
    last_modified_by = 'flyway'
WHERE r.status = 'IN_QC';

-- Receipts of SUBCONTRACT orders were CLOSED as they were confirmed: in-house production held their goods (its holds
-- went with V20261108000100) and no putaway task was ever opened. What of them is still INBOUND would never be put away
-- and never become sellable, so its tasks are opened now, as PutawayTaskCreator opens them, but sized by what is
-- actually still INBOUND (production may have moved part of it): a task per carton of the line with INBOUND units, and
-- one loose task for the loose INBOUND units of the line's lot - the lot its RECEIPT ledger line names, the one
-- putaway takes loose goods from - at most what the line received beyond its cartons. The receipt stays CLOSED, as for
-- the receipts above.
CREATE TEMPORARY TABLE subcontract_lines ON COMMIT DROP AS
SELECT l.id, l.receipt_id, l.received_qty::int AS received_qty, i.sku,
       (SELECT m.lot_id FROM inventory.stock_movement m
         WHERE m.movement_type = 'RECEIPT' AND m.reference_type = 'GOODS_RECEIPT_LINE' AND m.reference_id = l.id
           AND m.from_stock_package_id IS NULL AND m.to_stock_package_id IS NULL AND m.lot_id IS NOT NULL
         ORDER BY m.occurred_at, m.id
         LIMIT 1) AS lot_id
FROM procurement.goods_receipt_lines l
JOIN procurement.goods_receipts r ON r.id = l.receipt_id
JOIN procurement.purchase_orders p ON p.id = r.po_id
JOIN inventory.inventory_items i ON i.id = l.inventory_item_id
WHERE r.status = 'CLOSED'
  AND p.po_type = 'SUBCONTRACT'
  AND NOT EXISTS (SELECT 1 FROM warehouse.putaway_task t WHERE t.goods_receipt_line_id = l.id);

CREATE TEMPORARY TABLE subcontract_cartons ON COMMIT DROP AS
SELECT u.id AS line_id, u.receipt_id, u.sku, p.id AS stock_package_id, SUM(s.on_hand)::int AS units
FROM subcontract_lines u
JOIN inventory.stock_package p ON p.goods_receipt_line_id = u.id AND p.status = 'IN_STOCK'
JOIN inventory.stock_item s ON s.stock_package_id = p.id AND s.status = 'INBOUND'
GROUP BY u.id, u.receipt_id, u.sku, p.id
HAVING SUM(s.on_hand) > 0;

INSERT INTO warehouse.putaway_task (id, goods_receipt_id, goods_receipt_line_id, sku, quantity, stock_package_id,
                                    status, version, created_at, created_by)
SELECT gen_random_uuid(), c.receipt_id, c.line_id, c.sku, c.units, c.stock_package_id, 'PENDING', 0, NOW(), 'flyway'
FROM subcontract_cartons c;

INSERT INTO warehouse.putaway_task (id, goods_receipt_id, goods_receipt_line_id, sku, quantity, stock_package_id,
                                    status, version, created_at, created_by)
SELECT gen_random_uuid(), loose.receipt_id, loose.id, loose.sku, loose.units, NULL, 'PENDING', 0, NOW(), 'flyway'
FROM (SELECT u.id, u.receipt_id, u.sku,
             LEAST(u.received_qty
                       - COALESCE((SELECT SUM(c.units) FROM subcontract_cartons c WHERE c.line_id = u.id), 0),
                   COALESCE((SELECT SUM(s.on_hand) FROM inventory.stock_item s
                              WHERE s.lot_id = u.lot_id AND s.sku = u.sku AND s.stock_package_id IS NULL
                                AND s.status = 'INBOUND'), 0))::int AS units
      FROM subcontract_lines u
      WHERE u.lot_id IS NOT NULL) loose
WHERE loose.units > 0;


-- ---------------------------------------------------------------------------------------------
-- 3. Move tasks to the QC area
-- ---------------------------------------------------------------------------------------------
-- Opened to carry received goods to the QC area (no code has used them since receiving moved goods itself); archived
-- in step 1.
DELETE FROM warehouse.move_task WHERE origin = 'RECEIPT_QC';


-- ---------------------------------------------------------------------------------------------
-- 4. Functions and triggers
-- ---------------------------------------------------------------------------------------------
-- A posted receipt's lines keep what was counted (BR-05); the same check without the QC flag step 6 drops.
CREATE OR REPLACE FUNCTION procurement.check_goods_receipt_line_frozen()
    RETURNS trigger
    LANGUAGE plpgsql
AS $function$
DECLARE
    receipt_status VARCHAR(16);
BEGIN
    SELECT status INTO receipt_status FROM procurement.goods_receipts
     WHERE id = COALESCE(NEW.receipt_id, OLD.receipt_id);
    IF receipt_status IS NULL OR receipt_status IN ('DRAFT', 'PENDING_APPROVAL') THEN
        RETURN COALESCE(NEW, OLD);
    END IF;
    IF TG_OP = 'UPDATE'
       AND NEW.receipt_id = OLD.receipt_id AND NEW.po_line_id IS NOT DISTINCT FROM OLD.po_line_id
       AND NEW.inventory_item_id = OLD.inventory_item_id AND NEW.location_id = OLD.location_id
       AND NEW.received_qty = OLD.received_qty
       AND NEW.lot_number IS NOT DISTINCT FROM OLD.lot_number
       AND NEW.expiry_date IS NOT DISTINCT FROM OLD.expiry_date
       AND NEW.uom_conversion_id IS NOT DISTINCT FROM OLD.uom_conversion_id THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'receipt is %: its lines keep what was counted (BR-05)', receipt_status
        USING ERRCODE = 'check_violation';
END
$function$;

-- A photo of a line is of a line of its receipt; no step takes a QC photo any more (the ones taken stay).
CREATE OR REPLACE FUNCTION procurement.check_goods_receipt_photo_line()
    RETURNS trigger
    LANGUAGE plpgsql
AS $function$
BEGIN
    IF NEW.stage = 'QC' THEN
        RAISE EXCEPTION 'receiving QC was removed: no QC photo is taken any more'
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.receipt_line_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM procurement.goods_receipt_lines
             WHERE id = NEW.receipt_line_id AND receipt_id = NEW.receipt_id) THEN
        RAISE EXCEPTION 'receipt line % is not a line of receipt %', NEW.receipt_line_id, NEW.receipt_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$function$;

DROP TRIGGER tg_goods_receipt_lines_qc_move ON procurement.goods_receipt_lines;
DROP FUNCTION procurement.check_goods_receipt_line_qc_move();
DROP TRIGGER tg_move_task_receipt_qc ON warehouse.move_task;
DROP FUNCTION warehouse.check_move_task_receipt_qc();


-- ---------------------------------------------------------------------------------------------
-- 5. QC inspections
-- ---------------------------------------------------------------------------------------------
-- Its triggers go with it; their functions are dropped after.
DROP TABLE procurement.qc_inspections;
DROP FUNCTION procurement.check_qc_inspection();
DROP FUNCTION procurement.check_qc_inspection_moved();


-- ---------------------------------------------------------------------------------------------
-- 6. Columns and CHECKs
-- ---------------------------------------------------------------------------------------------
-- Their foreign keys, ck_goods_receipt_lines_qc_move, ck_move_task_receipt_qc and the indexes go with them.
ALTER TABLE procurement.goods_receipt_lines
    DROP COLUMN qc_location_id,
    DROP COLUMN moved_to_qc_at,
    DROP COLUMN moved_to_qc_by,
    DROP COLUMN qc_required;

ALTER TABLE inventory.inventory_items DROP COLUMN qc_required;

ALTER TABLE warehouse.move_task DROP COLUMN receipt_line_id;

ALTER TABLE warehouse.move_task DROP CONSTRAINT ck_move_task_origin;
ALTER TABLE warehouse.move_task
    ADD CONSTRAINT ck_move_task_origin CHECK (origin IN ('REPLENISHMENT', 'RESLOTTING', 'MANUAL'));

ALTER TABLE procurement.goods_receipts DROP CONSTRAINT ck_goods_receipts_status;
ALTER TABLE procurement.goods_receipts
    ADD CONSTRAINT ck_goods_receipts_status
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'CONFIRMED', 'IN_PUTAWAY', 'CLOSED', 'CANCELLED'));


-- ---------------------------------------------------------------------------------------------
-- 7. Permissions and the QC_STAFF role
-- ---------------------------------------------------------------------------------------------
-- The Sales Manager approves designs now (kltn-docs 12, the reviewer rule in DesignWorkflowService).
WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), r.id, p.id, 0, NOW(), 'flyway'
    FROM identity.app_role r
    JOIN identity.permission p ON p.code = 'design-designs:APPROVE'
    WHERE r.code = 'SALES_MANAGER'
      AND NOT EXISTS (SELECT 1 FROM identity.role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id)
    RETURNING role_id)
UPDATE identity.app_role r
SET version          = r.version + 1,
    last_modified_at = NOW(),
    last_modified_by = 'flyway'
WHERE r.id IN (SELECT role_id FROM granted);

-- role_permission first, advancing the version of every role that loses a grant in the same statement: deleting the
-- permission rows first would cascade the grants away without moving any version.
WITH lost AS (
    DELETE FROM identity.role_permission rp
    USING identity.permission p
    WHERE p.id = rp.permission_id AND p.code LIKE 'procurement-qc-tasks:%'
    RETURNING rp.role_id)
UPDATE identity.app_role r
SET version          = r.version + 1,
    last_modified_at = NOW(),
    last_modified_by = 'flyway'
WHERE r.id IN (SELECT role_id FROM lost);

DELETE FROM identity.permission WHERE code LIKE 'procurement-qc-tasks:%';

-- QC staff are warehouse staff now (kltn-docs 00 §2): they keep their warehouses (user_warehouse), and both roles are
-- warehouse-scoped. One who is also a warehouse manager needs nothing more. Returns are inspected by the warehouse
-- manager (kltn-docs 03 §4.2), so that grant does not follow.
CREATE TEMPORARY TABLE qc_staff ON COMMIT DROP AS
SELECT ur.user_id
FROM identity.user_role ur
JOIN identity.app_role r ON r.id = ur.role_id
WHERE r.code = 'QC_STAFF';

INSERT INTO identity.user_role (id, user_id, role_id, version, created_at, created_by)
SELECT gen_random_uuid(), q.user_id, r.id, 0, NOW(), 'flyway'
FROM qc_staff q
JOIN identity.app_role r ON r.code = 'WAREHOUSE_STAFF'
WHERE NOT EXISTS (SELECT 1 FROM identity.user_role ur
                  JOIN identity.app_role held ON held.id = ur.role_id
                  WHERE ur.user_id = q.user_id AND held.code IN ('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER'));

-- Their tokens name a role that no longer exists: signing in again issues one with WAREHOUSE_STAFF.
UPDATE identity.user_session s
SET revoked_at       = NOW(),
    revoked_reason   = 'ROLE_CHANGED',
    version          = s.version + 1,
    last_modified_at = NOW(),
    last_modified_by = 'flyway'
FROM qc_staff q
WHERE q.user_id = s.user_id AND s.revoked_at IS NULL AND s.expires_at > NOW();

-- Its grants, user_role rows and any approval limit go with it (ON DELETE CASCADE; all archived in step 1).
DELETE FROM identity.app_role WHERE code = 'QC_STAFF';
