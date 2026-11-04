-- =============================================================================
-- Warehouse: intra-warehouse move tasks - replenishment, re-slotting, manual moves (docs 11).
-- Module: warehouse   Plan: docs/business-design/db-design/2026-09-28-migration-plan.md, phase G
--
-- A move changes where stock sits, never how much the warehouse holds (BR-02), so it is a task in
-- the warehouse queue next to putaway, and every completed move writes one MOVE row to
-- inventory.stock_movement (BR-06). Permission resource: warehouse-replenishment (existing).
-- =============================================================================


CREATE TABLE warehouse.move_task
(
    id               UUID         NOT NULL,
    task_number      VARCHAR(30)  NOT NULL,
    warehouse_id     UUID         NOT NULL,
    origin           VARCHAR(16)  NOT NULL,
    reason           VARCHAR(16),
    sku              VARCHAR(64)  NOT NULL,
    lot_number       VARCHAR(64),
    from_location_id UUID         NOT NULL,
    to_location_id   UUID         NOT NULL,
    requested_qty    INTEGER      NOT NULL,
    picked_qty       INTEGER,
    placed_qty       INTEGER,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    priority         INTEGER      NOT NULL DEFAULT 0,
    assigned_user_id UUID,
    approved_by      UUID,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    note             VARCHAR(1000),

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_move_task PRIMARY KEY (id),
    CONSTRAINT uk_move_task_number UNIQUE (task_number),
    CONSTRAINT fk_move_task_warehouse FOREIGN KEY (warehouse_id)
        REFERENCES warehouse.warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_move_task_item FOREIGN KEY (sku)
        REFERENCES inventory.inventory_items (sku) ON DELETE RESTRICT,
    CONSTRAINT fk_move_task_from FOREIGN KEY (from_location_id)
        REFERENCES warehouse.storage_location (id) ON DELETE RESTRICT,
    CONSTRAINT fk_move_task_to FOREIGN KEY (to_location_id)
        REFERENCES warehouse.storage_location (id) ON DELETE RESTRICT,
    CONSTRAINT fk_move_task_assigned FOREIGN KEY (assigned_user_id) REFERENCES identity.app_user (id),
    CONSTRAINT fk_move_task_approved_by FOREIGN KEY (approved_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_move_task_origin CHECK (origin IN ('REPLENISHMENT', 'RESLOTTING', 'MANUAL')),
    CONSTRAINT ck_move_task_reason CHECK (reason IS NULL
        OR reason IN ('REPLENISHMENT', 'RESLOTTING', 'CONSOLIDATION', 'FIX', 'UNBLOCK', 'OTHER')),
    CONSTRAINT ck_move_task_locations CHECK (from_location_id <> to_location_id),
    CONSTRAINT ck_move_task_qty CHECK (requested_qty > 0
        AND (picked_qty IS NULL OR picked_qty BETWEEN 0 AND requested_qty)
        AND (placed_qty IS NULL OR placed_qty >= 0)),
    CONSTRAINT ck_move_task_status CHECK (status IN ('SUGGESTED', 'PENDING', 'ASSIGNED',
        'IN_PROGRESS', 'ON_HOLD', 'DISCREPANCY', 'COMPLETED', 'CANCELLED', 'REJECTED')),
    -- Only a re-slotting suggestion waits for a manager (docs 11 §5).
    CONSTRAINT ck_move_task_suggested CHECK (status <> 'SUGGESTED' OR origin = 'RESLOTTING'),
    CONSTRAINT ck_move_task_approved CHECK (origin <> 'RESLOTTING'
        OR status IN ('SUGGESTED', 'REJECTED') OR approved_by IS NOT NULL),
    CONSTRAINT ck_move_task_assigned CHECK (status NOT IN ('ASSIGNED', 'IN_PROGRESS')
        OR assigned_user_id IS NOT NULL),
    CONSTRAINT ck_move_task_completed CHECK ((status = 'COMPLETED')
        = (completed_at IS NOT NULL)),
    CONSTRAINT ck_move_task_done_qty CHECK (status <> 'COMPLETED'
        OR (picked_qty IS NOT NULL AND placed_qty IS NOT NULL)),
    CONSTRAINT ck_move_task_discrepancy CHECK (status <> 'DISCREPANCY'
        OR (picked_qty IS NOT NULL AND placed_qty IS NOT NULL AND picked_qty <> placed_qty))
);

CREATE INDEX ix_move_task_queue ON warehouse.move_task (warehouse_id, status, priority DESC);
CREATE INDEX ix_move_task_assigned ON warehouse.move_task (assigned_user_id, status);
CREATE INDEX ix_move_task_item ON warehouse.move_task (sku);


-- BR-01: source and target are both in the task's warehouse.
CREATE FUNCTION warehouse.check_move_task_locations()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF (SELECT count(*) FROM warehouse.storage_location s
         WHERE s.id IN (NEW.from_location_id, NEW.to_location_id)
           AND s.warehouse_id = NEW.warehouse_id) <> 2 THEN
        RAISE EXCEPTION 'move task % must move between two locations of its own warehouse', NEW.task_number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_move_task_locations
    BEFORE INSERT OR UPDATE OF warehouse_id, from_location_id, to_location_id ON warehouse.move_task
    FOR EACH ROW EXECUTE FUNCTION warehouse.check_move_task_locations();
