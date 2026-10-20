-- =============================================================================
-- Low-stock and expiry alerts (SCRUM-147, 165, 166; plan G5a-3 S1b).
--
-- stock_alert        one row per EPISODE of one condition: a SKU at or below its reorder point
--                    (REORDER), below its safety stock (SAFETY), a lot of a warehouse about to expire
--                    (EXPIRING) or already past its expiry date (EXPIRED). The same condition cannot be
--                    open twice (uk_stock_alert_open); when it clears the row is RESOLVED and a later
--                    return opens a new row, so the history of episodes is kept.
-- stock_alert_dirty  a queue of SKUs whose stock changed, fed by triggers on inventory.stock_item and
--                    drained by AlertDirtyDrainJob. A trigger catches EVERY write to stock (receipts,
--                    moves, adjustments, transfers, pick confirmation, production issue) without each
--                    writer having to remember to publish an event.
--
-- The dirty queue is append-only, one row per change, not one row per SKU with ON CONFLICT DO UPDATE as
-- the plan first sketched: a per-SKU row would be locked by the first writer until it commits, so two
-- transactions that touch the stock of several SKUs in different orders could deadlock on it (stock rows
-- are locked in id order, SKU rows would not be). An INSERT into a table without a unique key never waits.
-- The drain collapses duplicates.
--
-- The trigger fires only when on_hand, status or expiry_date really change: Hibernate writes every column
-- on every UPDATE, so "UPDATE OF" alone would fire on every reservation. Reservations change `reserved`,
-- which the thresholds do not use (docs: evaluated on usable on-hand, not ATP).
--
-- stock_movement gets the reference type EXPIRY_SWEEP: the nightly job that moves an expired lot to
-- EXPIRED writes a STATUS_CHANGE line for it (BR-STK-004), referencing the stock row.
--
-- Permission resource inventory-alerts (VIEW_PAGE, READ, UPDATE): the warehouse manager and the inventory
-- planner read and acknowledge, warehouse staff read, SYSTEM_ADMIN everything.
-- =============================================================================

CREATE TABLE inventory.stock_alert
(
    id                   UUID         NOT NULL,
    kind                 VARCHAR(16)  NOT NULL,
    sku                  VARCHAR(64)  NOT NULL,
    -- NULL for REORDER and SAFETY: those thresholds are per SKU for the whole system.
    warehouse_id         UUID,
    lot_number           VARCHAR(64),
    expiry_date          DATE,
    observed_qty         INTEGER      NOT NULL,
    threshold_qty        INTEGER,
    -- An EXPIRED lot still holds a customer's reservation: the lot cannot be blocked until it is released.
    reservation_impaired BOOLEAN      NOT NULL DEFAULT FALSE,
    status               VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    opened_at            TIMESTAMPTZ  NOT NULL,
    last_observed_at     TIMESTAMPTZ  NOT NULL,
    acknowledged_by      UUID,
    acknowledged_at      TIMESTAMPTZ,
    ack_note             VARCHAR(500),
    resolved_at          TIMESTAMPTZ,
    resolution           VARCHAR(24),
    version              BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT pk_stock_alert PRIMARY KEY (id),
    CONSTRAINT fk_stock_alert_item FOREIGN KEY (sku) REFERENCES inventory.inventory_items (sku),
    CONSTRAINT fk_stock_alert_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse.warehouse (id),
    CONSTRAINT fk_stock_alert_ack_by FOREIGN KEY (acknowledged_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_stock_alert_kind CHECK (kind IN ('REORDER', 'SAFETY', 'EXPIRING', 'EXPIRED')),
    CONSTRAINT ck_stock_alert_status CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    CONSTRAINT ck_stock_alert_resolution CHECK (resolution IS NULL
        OR resolution IN ('RECOVERED', 'UNCONFIGURED', 'STOCK_GONE', 'EXPIRED_OUT')),
    -- How an episode can end depends on what it is: a stock level recovers or loses its threshold; a lot
    -- that was about to expire is used up, or has expired; an expired lot is used up or written off.
    CONSTRAINT ck_stock_alert_resolution_kind CHECK (resolution IS NULL OR CASE
        WHEN kind IN ('REORDER', 'SAFETY') THEN resolution IN ('RECOVERED', 'UNCONFIGURED')
        WHEN kind = 'EXPIRING' THEN resolution IN ('STOCK_GONE', 'EXPIRED_OUT')
        ELSE resolution = 'STOCK_GONE'
        END),
    CONSTRAINT ck_stock_alert_observed CHECK (observed_qty >= 0 AND (threshold_qty IS NULL OR threshold_qty >= 0)),
    -- RESOLVED says when and how; every other status says neither.
    CONSTRAINT ck_stock_alert_resolved CHECK (
        (status = 'RESOLVED' AND resolved_at IS NOT NULL AND resolution IS NOT NULL)
        OR (status <> 'RESOLVED' AND resolved_at IS NULL AND resolution IS NULL)),
    CONSTRAINT ck_stock_alert_ack CHECK (
        (status = 'ACKNOWLEDGED' AND acknowledged_by IS NOT NULL AND acknowledged_at IS NOT NULL)
        OR status <> 'ACKNOWLEDGED'),
    CONSTRAINT ck_stock_alert_open_unacked CHECK (status <> 'OPEN' OR (acknowledged_by IS NULL AND acknowledged_at IS NULL)),
    -- The shape of each kind: a stock threshold is system-wide and names a threshold; an expiry alert is about
    -- one lot of one warehouse and names its expiry date.
    CONSTRAINT ck_stock_alert_shape CHECK (CASE
        WHEN kind IN ('REORDER', 'SAFETY')
            THEN warehouse_id IS NULL AND lot_number IS NULL AND expiry_date IS NULL AND threshold_qty IS NOT NULL
        ELSE warehouse_id IS NOT NULL AND expiry_date IS NOT NULL
        END),
    CONSTRAINT ck_stock_alert_impaired CHECK (NOT reservation_impaired OR kind = 'EXPIRED'),
    CONSTRAINT ck_stock_alert_opened CHECK (last_observed_at >= opened_at AND (resolved_at IS NULL OR resolved_at >= opened_at))
);

-- One open episode per condition. COALESCE: in SQL NULL <> NULL, so a plain UNIQUE would accept the same
-- condition twice whenever the warehouse, lot or expiry date is NULL.
CREATE UNIQUE INDEX uk_stock_alert_open ON inventory.stock_alert
    (kind, sku, COALESCE(warehouse_id, '00000000-0000-0000-0000-000000000000'::uuid),
     COALESCE(lot_number, ''), COALESCE(expiry_date, DATE '1900-01-01'))
    WHERE status <> 'RESOLVED';
CREATE INDEX ix_stock_alert_status_kind ON inventory.stock_alert (status, kind, opened_at DESC);
CREATE INDEX ix_stock_alert_warehouse ON inventory.stock_alert (warehouse_id, status);
CREATE INDEX ix_stock_alert_sku ON inventory.stock_alert (sku, kind);
-- Hysteresis: "when did the previous episode of this condition end?"
CREATE INDEX ix_stock_alert_resolved ON inventory.stock_alert (kind, sku, resolved_at DESC) WHERE status = 'RESOLVED';

COMMENT ON TABLE inventory.stock_alert IS
    'One episode of a low-stock or expiry condition. At most one open row per condition (uk_stock_alert_open).';

-- ---- the dirty queue ------------------------------------------------------------
CREATE TABLE inventory.stock_alert_dirty
(
    id        BIGINT GENERATED ALWAYS AS IDENTITY,
    sku       VARCHAR(64)  NOT NULL,
    marked_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_stock_alert_dirty PRIMARY KEY (id)
);

COMMENT ON TABLE inventory.stock_alert_dirty IS
    'SKUs whose stock changed, appended by triggers on stock_item, drained by AlertDirtyDrainJob. No foreign key: it must never block a stock write.';

CREATE FUNCTION inventory.mark_stock_alert_dirty() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        INSERT INTO inventory.stock_alert_dirty (sku) VALUES (OLD.sku);
        RETURN OLD;
    END IF;
    INSERT INTO inventory.stock_alert_dirty (sku) VALUES (NEW.sku);
    RETURN NEW;
END $$;

CREATE TRIGGER trg_stock_item_dirty_insert AFTER INSERT ON inventory.stock_item
    FOR EACH ROW EXECUTE FUNCTION inventory.mark_stock_alert_dirty();
CREATE TRIGGER trg_stock_item_dirty_update AFTER UPDATE ON inventory.stock_item
    FOR EACH ROW
    WHEN (OLD.on_hand IS DISTINCT FROM NEW.on_hand OR OLD.status IS DISTINCT FROM NEW.status
          OR OLD.expiry_date IS DISTINCT FROM NEW.expiry_date)
    EXECUTE FUNCTION inventory.mark_stock_alert_dirty();
CREATE TRIGGER trg_stock_item_dirty_delete AFTER DELETE ON inventory.stock_item
    FOR EACH ROW EXECUTE FUNCTION inventory.mark_stock_alert_dirty();

-- Stock that exists before this migration is evaluated once by the first drain.
INSERT INTO inventory.stock_alert_dirty (sku) SELECT DISTINCT sku FROM inventory.stock_item;

-- ---- the ledger: the expiry sweep's reference type -------------------------------
ALTER TABLE inventory.stock_movement DROP CONSTRAINT ck_stock_movement_reference;
ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_reference CHECK (reference_type IN
    ('GOODS_RECEIPT_LINE', 'QC_INSPECTION', 'PUTAWAY_TASK', 'PICK_LINE', 'PACKAGE', 'MOVE_TASK',
     'TRANSFER_ORDER_LINE', 'STOCK_ADJUSTMENT', 'CYCLE_COUNT_LINE', 'RETURN_REQUEST_LINE', 'ORDER',
     'PRODUCTION_ORDER', 'EXPIRY_SWEEP'));

-- ---- permissions ---------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), r.resource || ':' || a.action, r.resource, a.action, 0, NOW(), 'flyway'
FROM (VALUES ('inventory-alerts', 'VIEW_PAGE,READ,UPDATE')) AS r(resource, actions)
CROSS JOIN LATERAL unnest(string_to_array(r.actions, ',')) AS a(action)
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          -- docs F-EXP: the warehouse manager is told about expired lots; docs 00 2: the inventory planner
          -- follows stock levels across warehouses. Both decide what to do about an alert.
          ('WAREHOUSE_MANAGER', 'inventory-alerts', 'VIEW_PAGE,READ,UPDATE'),
          ('INVENTORY_PLANNER', 'inventory-alerts', 'VIEW_PAGE,READ,UPDATE'),
          -- Floor staff see what is on the list; they do not acknowledge it.
          ('WAREHOUSE_STAFF',   'inventory-alerts', 'VIEW_PAGE,READ')
     ) AS g(role_code, resource, actions)
JOIN identity.app_role role ON role.code = g.role_code
JOIN identity.permission permission
  ON permission.resource = g.resource
 AND permission.action = ANY (string_to_array(g.actions, ','))
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
CROSS JOIN identity.permission permission
WHERE role.code = 'SYSTEM_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('WAREHOUSE_MANAGER', 'INVENTORY_PLANNER', 'WAREHOUSE_STAFF', 'SYSTEM_ADMIN');
