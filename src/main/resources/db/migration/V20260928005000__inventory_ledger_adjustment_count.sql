-- =============================================================================
-- Inventory: stock movement ledger, stock adjustments, cycle counts. Document numbering.
-- Module: inventory (+ platform)   Plan: docs/business-design/db-design/2026-09-28-migration-plan.md, phase G
--
-- Tables the business flows need and DBML v9 did not have yet ("gap" tables, plan §G). They are
-- new and empty, so every foreign key is added immediately. The permission resources for them
-- already exist (inventory-stock-movements, -stock-adjustments, -cycle-counts, V20260903000100).
--
--   stock_movement   docs 11 BR-06: "every movement leaves a movement record (source, target,
--                    SKU, lot, quantity, person, time, reason)". Receipts, putaway, picks,
--                    transfers and adjustments write here too, so on-hand at any past moment can
--                    be rebuilt and every change has an author. Append-only.
--   stock_adjustment docs 07/08/11: damaged or missing stock is corrected "with approval".
--   cycle_count      docs 07/11: a short pick or a move discrepancy "creates a count request".
--
-- Inventory documents key the item by sku, like inventory.stock_item; purchasing documents key it
-- by inventory_item_id (decision D3). Both point at inventory.inventory_items.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 0. Document numbers. ordering and procurement each grew their own per-day counter table; every
--    new document type (receipt, transfer, adjustment, count, move, RMA, remittance) shares this
--    one instead of adding a table each: SELECT ... FOR UPDATE on (type, day), increment, format.
-- -----------------------------------------------------------------------------
CREATE TABLE platform.document_sequence
(
    document_type VARCHAR(16) NOT NULL,
    sequence_date DATE        NOT NULL,
    last_value    BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT pk_document_sequence PRIMARY KEY (document_type, sequence_date),
    CONSTRAINT ck_document_sequence_type CHECK (document_type ~ '^[A-Z]{2,16}$'),
    CONSTRAINT ck_document_sequence_value CHECK (last_value >= 0)
);


-- -----------------------------------------------------------------------------
-- 1. Stock movement ledger.
--
--    The shape of a movement follows from its type: stock arriving has only a target, stock
--    leaving only a source, stock moving both (and they differ), a status change stays in place.
--    An adjustment or count correction has exactly one side: target = found, source = lost.
-- -----------------------------------------------------------------------------
CREATE TABLE inventory.stock_movement
(
    id                 UUID         NOT NULL,
    movement_type      VARCHAR(24)  NOT NULL,
    sku                VARCHAR(64)  NOT NULL,
    lot_number         VARCHAR(64),
    from_location_code VARCHAR(64),
    to_location_code   VARCHAR(64),
    quantity           INTEGER      NOT NULL,
    from_status        VARCHAR(32),
    to_status          VARCHAR(32),
    -- What caused it. Polymorphic on purpose (a receipt line, a pick line, a transfer line...):
    -- the ledger must accept every source without a column per document type.
    reference_type     VARCHAR(32)  NOT NULL,
    reference_id       UUID         NOT NULL,
    reason             VARCHAR(500),
    actor_id           UUID,
    occurred_at        TIMESTAMPTZ  NOT NULL,

    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(100),
    last_modified_at   TIMESTAMPTZ,
    last_modified_by   VARCHAR(100),

    CONSTRAINT pk_stock_movement PRIMARY KEY (id),
    CONSTRAINT fk_stock_movement_item FOREIGN KEY (sku)
        REFERENCES inventory.inventory_items (sku) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_movement_from FOREIGN KEY (from_location_code)
        REFERENCES warehouse.storage_location (location_code) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_movement_to FOREIGN KEY (to_location_code)
        REFERENCES warehouse.storage_location (location_code) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_movement_actor FOREIGN KEY (actor_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_stock_movement_type CHECK (movement_type IN ('RECEIPT', 'PUTAWAY', 'PICK',
        'PUT_BACK', 'MOVE', 'TRANSFER_OUT', 'TRANSFER_IN', 'ADJUSTMENT', 'COUNT_CORRECTION',
        'RETURN_IN', 'STATUS_CHANGE', 'SCRAP')),
    CONSTRAINT ck_stock_movement_qty CHECK (quantity > 0),
    CONSTRAINT ck_stock_movement_status CHECK (
        (from_status IS NULL OR from_status IN ('AVAILABLE', 'QUARANTINE', 'DAMAGED', 'EXPIRED'))
        AND (to_status IS NULL OR to_status IN ('AVAILABLE', 'QUARANTINE', 'DAMAGED', 'EXPIRED'))),
    CONSTRAINT ck_stock_movement_reference CHECK (reference_type IN ('GOODS_RECEIPT_LINE',
        'QC_INSPECTION', 'PUTAWAY_TASK', 'PICK_LINE', 'PACKAGE', 'MOVE_TASK', 'TRANSFER_ORDER_LINE',
        'STOCK_ADJUSTMENT', 'CYCLE_COUNT_LINE', 'RETURN_REQUEST_LINE', 'ORDER')),
    CONSTRAINT ck_stock_movement_shape CHECK (CASE
        WHEN movement_type IN ('RECEIPT', 'TRANSFER_IN', 'RETURN_IN')
            THEN from_location_code IS NULL AND to_location_code IS NOT NULL
        WHEN movement_type IN ('PICK', 'TRANSFER_OUT', 'SCRAP')
            THEN from_location_code IS NOT NULL AND to_location_code IS NULL
        WHEN movement_type IN ('PUTAWAY', 'MOVE', 'PUT_BACK')
            THEN from_location_code IS NOT NULL AND to_location_code IS NOT NULL
                 AND from_location_code <> to_location_code
        WHEN movement_type IN ('ADJUSTMENT', 'COUNT_CORRECTION')
            THEN num_nonnulls(from_location_code, to_location_code) = 1
        WHEN movement_type = 'STATUS_CHANGE'
            THEN from_location_code = to_location_code
                 AND from_status IS NOT NULL AND to_status IS NOT NULL AND from_status <> to_status
        END)
);

CREATE INDEX ix_stock_movement_sku_time ON inventory.stock_movement (sku, occurred_at);
CREATE INDEX ix_stock_movement_reference ON inventory.stock_movement (reference_type, reference_id);
CREATE INDEX ix_stock_movement_from ON inventory.stock_movement (from_location_code, occurred_at);
CREATE INDEX ix_stock_movement_to ON inventory.stock_movement (to_location_code, occurred_at);

-- No parent: a ledger row is never updated or deleted. A wrong movement is corrected by a new one.
CREATE TRIGGER tg_stock_movement_append_only BEFORE UPDATE OR DELETE ON inventory.stock_movement
    FOR EACH ROW EXECUTE FUNCTION platform.append_only();


-- -----------------------------------------------------------------------------
-- 2. Cycle count: count what is physically in a set of locations and compare with the books.
--    Blind by default: the counter does not see expected_qty, or the count only confirms it.
-- -----------------------------------------------------------------------------
CREATE TABLE inventory.cycle_count
(
    id               UUID         NOT NULL,
    count_number     VARCHAR(30)  NOT NULL,
    warehouse_id     UUID         NOT NULL,
    trigger_type     VARCHAR(24)  NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PLANNED',
    is_blind         BOOLEAN      NOT NULL DEFAULT TRUE,
    assigned_user_id UUID,
    planned_date     DATE,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    note             VARCHAR(1000),

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_cycle_count PRIMARY KEY (id),
    CONSTRAINT uk_cycle_count_number UNIQUE (count_number),
    CONSTRAINT fk_cycle_count_warehouse FOREIGN KEY (warehouse_id)
        REFERENCES warehouse.warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cycle_count_assigned FOREIGN KEY (assigned_user_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_cycle_count_trigger CHECK (trigger_type IN
        ('SCHEDULED', 'SHORT_PICK', 'MOVE_DISCREPANCY', 'TRANSFER_DISCREPANCY', 'MANUAL')),
    CONSTRAINT ck_cycle_count_status CHECK (status IN ('PLANNED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_cycle_count_started
        CHECK (status NOT IN ('IN_PROGRESS', 'COMPLETED') OR started_at IS NOT NULL),
    CONSTRAINT ck_cycle_count_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE INDEX ix_cycle_count_warehouse_status ON inventory.cycle_count (warehouse_id, status);


CREATE TABLE inventory.cycle_count_line
(
    id               UUID         NOT NULL,
    cycle_count_id   UUID         NOT NULL,
    location_code    VARCHAR(64)  NOT NULL,
    sku              VARCHAR(64)  NOT NULL,
    lot_number       VARCHAR(64),
    expected_qty     INTEGER      NOT NULL,
    counted_qty      INTEGER,
    counted_by       UUID,
    counted_at       TIMESTAMPTZ,
    recount_required BOOLEAN      NOT NULL DEFAULT FALSE,

    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_cycle_count_line PRIMARY KEY (id),
    CONSTRAINT fk_cycle_count_line_count FOREIGN KEY (cycle_count_id)
        REFERENCES inventory.cycle_count (id) ON DELETE CASCADE,
    CONSTRAINT fk_cycle_count_line_location FOREIGN KEY (location_code)
        REFERENCES warehouse.storage_location (location_code) ON DELETE RESTRICT,
    CONSTRAINT fk_cycle_count_line_item FOREIGN KEY (sku)
        REFERENCES inventory.inventory_items (sku) ON DELETE RESTRICT,
    CONSTRAINT fk_cycle_count_line_counted_by FOREIGN KEY (counted_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_cycle_count_line_qty CHECK (expected_qty >= 0 AND (counted_qty IS NULL OR counted_qty >= 0)),
    -- A count is who, when and how many, together, or not yet.
    CONSTRAINT ck_cycle_count_line_counted CHECK (
        (counted_qty IS NULL AND counted_by IS NULL AND counted_at IS NULL)
        OR (counted_qty IS NOT NULL AND counted_by IS NOT NULL AND counted_at IS NOT NULL))
);

CREATE UNIQUE INDEX uk_cycle_count_line_slot ON inventory.cycle_count_line
    (cycle_count_id, location_code, sku, COALESCE(lot_number, ''));


-- -----------------------------------------------------------------------------
-- 3. Stock adjustment: a change to on-hand that no flow explains (damage, loss, found stock, a
--    count variance). Requested by one person, decided by another, then posted to stock_item and
--    the ledger.
-- -----------------------------------------------------------------------------
CREATE TABLE inventory.stock_adjustment
(
    id                  UUID          NOT NULL,
    adjustment_number   VARCHAR(30)   NOT NULL,
    location_code       VARCHAR(64)   NOT NULL,
    sku                 VARCHAR(64)   NOT NULL,
    lot_number          VARCHAR(64),
    quantity_delta      INTEGER       NOT NULL,
    reason_code         VARCHAR(24)   NOT NULL,
    note                VARCHAR(1000),
    status              VARCHAR(20)   NOT NULL DEFAULT 'PENDING_APPROVAL',
    cycle_count_line_id UUID,
    requested_by        UUID          NOT NULL,
    decided_by          UUID,
    decided_at          TIMESTAMPTZ,
    rejection_reason    VARCHAR(500),
    posted_at           TIMESTAMPTZ,

    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(100),
    last_modified_at    TIMESTAMPTZ,
    last_modified_by    VARCHAR(100),

    CONSTRAINT pk_stock_adjustment PRIMARY KEY (id),
    CONSTRAINT uk_stock_adjustment_number UNIQUE (adjustment_number),
    CONSTRAINT fk_stock_adjustment_location FOREIGN KEY (location_code)
        REFERENCES warehouse.storage_location (location_code) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_adjustment_item FOREIGN KEY (sku)
        REFERENCES inventory.inventory_items (sku) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_adjustment_count_line FOREIGN KEY (cycle_count_line_id)
        REFERENCES inventory.cycle_count_line (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_adjustment_requested_by FOREIGN KEY (requested_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_stock_adjustment_decided_by FOREIGN KEY (decided_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_stock_adjustment_delta CHECK (quantity_delta <> 0),
    CONSTRAINT ck_stock_adjustment_reason CHECK (reason_code IN ('DAMAGED', 'LOST', 'FOUND',
        'COUNT_VARIANCE', 'EXPIRED', 'DATA_CORRECTION', 'OTHER')),
    CONSTRAINT ck_stock_adjustment_status
        CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'POSTED')),
    CONSTRAINT ck_stock_adjustment_four_eyes CHECK (decided_by IS NULL OR decided_by <> requested_by),
    CONSTRAINT ck_stock_adjustment_decided CHECK ((status = 'PENDING_APPROVAL')
        = (decided_by IS NULL AND decided_at IS NULL)),
    CONSTRAINT ck_stock_adjustment_rejected CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL)),
    CONSTRAINT ck_stock_adjustment_posted CHECK ((status = 'POSTED') = (posted_at IS NOT NULL)),
    CONSTRAINT ck_stock_adjustment_count_variance
        CHECK (reason_code <> 'COUNT_VARIANCE' OR cycle_count_line_id IS NOT NULL)
);

CREATE INDEX ix_stock_adjustment_status ON inventory.stock_adjustment (status);
CREATE INDEX ix_stock_adjustment_item ON inventory.stock_adjustment (sku);
CREATE INDEX ix_stock_adjustment_count_line ON inventory.stock_adjustment (cycle_count_line_id);


-- A counted location is in the counted warehouse.
CREATE FUNCTION inventory.check_cycle_count_line()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM inventory.cycle_count c
          JOIN warehouse.storage_location s ON s.warehouse_id = c.warehouse_id
         WHERE c.id = NEW.cycle_count_id AND s.location_code = NEW.location_code) THEN
        RAISE EXCEPTION 'location % is not in the warehouse being counted', NEW.location_code
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_cycle_count_line_check BEFORE INSERT OR UPDATE OF cycle_count_id, location_code
    ON inventory.cycle_count_line
    FOR EACH ROW EXECUTE FUNCTION inventory.check_cycle_count_line();
