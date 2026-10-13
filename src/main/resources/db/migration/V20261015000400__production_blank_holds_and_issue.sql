-- =============================================================================
-- Production: blanks held for a production order, issued to the print shop, never resold (SCRUM-397).
-- Docs: 19-production §4.3 steps 1-2, Jira 397 (issue blanks; BR-PRD-03), decisions of 2026-10-12:
--   Q1  a sample's blanks are held from the moment its request is submitted; no other order may take them;
--   Q3  goods printed for an order stay allocated to that order and are never sold to anyone else.
--
-- 1. A stock hold belongs to an order OR to a production order (never an invented order id). A production
--    hold does not expire: there is no payment to wait for.
-- 2. Stock status IN_PRODUCTION: issued blanks and the cups printed from them, in a PRODUCTION area. Not
--    reservable, so not in available-to-promise, whatever happens to the hold: an order cancelled after the
--    issue releases its holds in its own transaction, and the units must not become blanks for sale in the
--    moment before production reacts. Available-to-promise stays "AVAILABLE stock minus holds".
-- 3. production_material_issue: one row per hold issued to the print shop - the scan's idempotency key, the
--    hold, where it came from (to put unprinted blanks back), the ledger line.
-- 4. Permission resource production-material-issues: the warehouse issues blanks (Jira 397 actor Warehouse
--    Staff), without production-orders:UPDATE, which would also let it pass prepress or record output.
-- =============================================================================

-- 1. Owner of a hold ---------------------------------------------------------------------------------------
ALTER TABLE inventory.stock_reservation ALTER COLUMN order_id DROP NOT NULL;
ALTER TABLE inventory.stock_reservation ADD COLUMN production_order_id UUID;
ALTER TABLE inventory.stock_reservation
    ADD CONSTRAINT fk_stock_reservation_production_order FOREIGN KEY (production_order_id)
        REFERENCES production.production_order (id) ON DELETE RESTRICT;
ALTER TABLE inventory.stock_reservation
    ADD CONSTRAINT ck_stock_reservation_owner CHECK ((order_id IS NULL) <> (production_order_id IS NULL));
ALTER TABLE inventory.stock_reservation
    ADD CONSTRAINT ck_stock_reservation_production_pinned CHECK (production_order_id IS NULL OR expires_at IS NULL);
CREATE INDEX ix_stock_reservation_production_order ON inventory.stock_reservation (production_order_id)
    WHERE production_order_id IS NOT NULL;

-- 2. IN_PRODUCTION -----------------------------------------------------------------------------------------
ALTER TABLE inventory.stock_item DROP CONSTRAINT ck_stock_item_status;
ALTER TABLE inventory.stock_item ADD CONSTRAINT ck_stock_item_status CHECK (status IN
    ('INBOUND', 'AVAILABLE', 'QUARANTINE', 'BLOCKED', 'DAMAGED', 'EXPIRED', 'IN_PRODUCTION'));

ALTER TABLE inventory.stock_movement DROP CONSTRAINT ck_stock_movement_status;
ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_status CHECK (
    (from_status IS NULL OR from_status IN
        ('INBOUND', 'AVAILABLE', 'QUARANTINE', 'BLOCKED', 'DAMAGED', 'EXPIRED', 'IN_PRODUCTION'))
    AND (to_status IS NULL OR to_status IN
        ('INBOUND', 'AVAILABLE', 'QUARANTINE', 'BLOCKED', 'DAMAGED', 'EXPIRED', 'IN_PRODUCTION')));

-- 3. Blanks issued to the print shop -------------------------------------------------------------------------
CREATE TABLE production.production_material_issue
(
    id                  UUID          NOT NULL,
    production_order_id UUID          NOT NULL,
    -- The scan's idempotency key: a retried scan finds its row and issues nothing twice.
    request_id          UUID          NOT NULL,
    -- The hold issued, whole. It keeps its id, so the order line that stored it still finds it.
    reservation_id      UUID          NOT NULL,
    sku                 VARCHAR(64)   NOT NULL,
    lot_number          VARCHAR(64),
    -- Where the blanks came from, to put the unprinted ones back if the order is stopped (SCRUM-401).
    from_location       VARCHAR(64)   NOT NULL,
    to_location         VARCHAR(64)   NOT NULL,
    quantity            INTEGER       NOT NULL,
    stock_movement_id   UUID,
    issued_at           TIMESTAMPTZ   NOT NULL,
    issued_by           UUID          NOT NULL,

    CONSTRAINT pk_production_material_issue PRIMARY KEY (id),
    CONSTRAINT uk_production_material_issue_request UNIQUE (request_id),
    -- A hold goes to the print shop once.
    CONSTRAINT uk_production_material_issue_reservation UNIQUE (reservation_id),
    CONSTRAINT fk_production_material_issue_order FOREIGN KEY (production_order_id)
        REFERENCES production.production_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_production_material_issue_reservation FOREIGN KEY (reservation_id)
        REFERENCES inventory.stock_reservation (id) ON DELETE RESTRICT,
    CONSTRAINT fk_production_material_issue_movement FOREIGN KEY (stock_movement_id)
        REFERENCES inventory.stock_movement (id) ON DELETE RESTRICT,
    CONSTRAINT fk_production_material_issue_by FOREIGN KEY (issued_by)
        REFERENCES identity.app_user (id),
    CONSTRAINT ck_production_material_issue_quantity CHECK (quantity > 0),
    CONSTRAINT ck_production_material_issue_locations CHECK (from_location <> to_location)
);

CREATE INDEX ix_production_material_issue_order ON production.production_material_issue (production_order_id);

-- 4. Who issues ---------------------------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), 'production-material-issues:' || a.action, 'production-material-issues', a.action, 0,
       NOW(), 'flyway'
FROM unnest(ARRAY['READ', 'CREATE']) AS a(action)
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          ('WAREHOUSE_STAFF',   'READ,CREATE'),
          ('WAREHOUSE_MANAGER', 'READ,CREATE'),
          ('PRODUCTION_STAFF',  'READ'),
          ('SYSTEM_ADMIN',      'READ,CREATE')
     ) AS g(role_code, actions)
JOIN identity.app_role role ON role.code = g.role_code
JOIN identity.permission permission
  ON permission.resource = 'production-material-issues'
 AND permission.action = ANY (string_to_array(g.actions, ','))
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Grants are cached per role version (ADR-0008).
UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'PRODUCTION_STAFF', 'SYSTEM_ADMIN');
