-- =============================================================================
-- Damaged stock (SCRUM-475 / SCRUM-277; docs 03 §5 AVAILABLE -> DAMAGED -> written off, BR-STK-003).
-- Module: inventory. Schema owner review: Tu.
--
-- stock_damage_report       one damage record: units of ONE stock row found damaged, by whom, why, with
--                           at least one photo (BR-STK-003). The units leave AVAILABLE in the same transaction:
--                           they move, as DAMAGED, to a QUARANTINE area of the same warehouse - a row of one
--                           receipt layer cannot be AVAILABLE and DAMAGED at one location
--                           (uk_stock_item_sku_location_lot has no status), and damaged goods are kept apart
--                           from good ones on the floor as well as on the books. One MOVE ledger line,
--                           reference DAMAGE_REPORT, AVAILABLE -> DAMAGED.
--                           origin STOCK_CHECK: anyone of the warehouse found them on a shelf; only free
--                           (unheld) units can be reported that way. origin PICKING: a picker found them among
--                           the units an order's allocation holds (fulfillment, SCRUM-277); reservation_id is
--                           that allocation, and impaired_reservation_id the share of it left on the damaged
--                           units when no other stock could replace them (the order is ON_HOLD meanwhile).
-- stock_damage_photo        the evidence, stored through FileStorage (category qc-evidence). Append-only.
--                           At least one per report, checked when the transaction commits.
-- stock_damage_disposition  what a warehouse manager decided for some of the units, never the reporter (four
--                           eyes): WRITE_OFF (a posted DAMAGED stock adjustment; record_number numbers the
--                           destruction record local accounting asks for), RETURN_TO_SUPPLIER (intent only:
--                           the units stay DAMAGED in quarantine until procurement's return flow ships them)
--                           or RESTORE (re-inspected, fine: back onto a bin as AVAILABLE). Never more units
--                           than the report has.
--                           Append-only: a decision is corrected by another document, never edited.
--
-- stock_movement accepts the reference type DAMAGE_REPORT.
--
-- Data: new tables, nothing to migrate.
-- =============================================================================

CREATE TABLE inventory.stock_damage_report
(
    id                      UUID          NOT NULL,
    report_number           VARCHAR(30)   NOT NULL,
    -- The caller's idempotency key: a retried report finds the first one.
    request_id              UUID          NOT NULL,
    origin                  VARCHAR(16)   NOT NULL,
    warehouse_id            UUID          NOT NULL,
    -- The damaged goods, as the source row knew them.
    sku                     VARCHAR(64)   NOT NULL,
    lot_number              VARCHAR(64),
    serial_number           VARCHAR(100),
    received_at             TIMESTAMPTZ,
    lot_id                  UUID,
    source_stock_item_id    UUID          NOT NULL,
    source_location_code    VARCHAR(64)   NOT NULL,
    source_stock_package_id UUID,
    -- Where they went: a DAMAGED row in a QUARANTINE area.
    damaged_stock_item_id   UUID          NOT NULL,
    damage_location_code    VARCHAR(64)   NOT NULL,
    quantity                INTEGER       NOT NULL,
    reason_code             VARCHAR(24)   NOT NULL,
    note                    VARCHAR(1000),
    -- PICKING only.
    order_id                UUID,
    pick_line_id            UUID,
    reservation_id          UUID,
    impaired_reservation_id UUID,
    reported_by             UUID          NOT NULL,
    reported_at             TIMESTAMPTZ   NOT NULL,
    version                 BIGINT        NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by              VARCHAR(100),
    last_modified_at        TIMESTAMPTZ,
    last_modified_by        VARCHAR(100),

    CONSTRAINT pk_stock_damage_report PRIMARY KEY (id),
    CONSTRAINT uk_stock_damage_report_number UNIQUE (report_number),
    CONSTRAINT uk_stock_damage_report_request UNIQUE (request_id),
    CONSTRAINT fk_stock_damage_report_warehouse FOREIGN KEY (warehouse_id)
        REFERENCES warehouse.warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_damage_report_item FOREIGN KEY (sku)
        REFERENCES inventory.inventory_items (sku) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_damage_report_lot FOREIGN KEY (lot_id)
        REFERENCES inventory.stock_lot (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_damage_report_source FOREIGN KEY (source_stock_item_id)
        REFERENCES inventory.stock_item (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_damage_report_source_location FOREIGN KEY (source_location_code)
        REFERENCES warehouse.storage_location (location_code) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_damage_report_source_package FOREIGN KEY (source_stock_package_id)
        REFERENCES inventory.stock_package (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_damage_report_damaged FOREIGN KEY (damaged_stock_item_id)
        REFERENCES inventory.stock_item (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_damage_report_damage_location FOREIGN KEY (damage_location_code)
        REFERENCES warehouse.storage_location (location_code) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_damage_report_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_damage_report_pick_line FOREIGN KEY (pick_line_id)
        REFERENCES fulfillment.pick_line (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_damage_report_reservation FOREIGN KEY (reservation_id)
        REFERENCES inventory.stock_reservation (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_damage_report_impaired FOREIGN KEY (impaired_reservation_id)
        REFERENCES inventory.stock_reservation (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_damage_report_reported_by FOREIGN KEY (reported_by)
        REFERENCES identity.app_user (id),
    CONSTRAINT ck_stock_damage_report_quantity CHECK (quantity > 0),
    -- One serial number is one unit (ck_stock_serial_quantity).
    CONSTRAINT ck_stock_damage_report_serial CHECK (serial_number IS NULL OR quantity = 1),
    CONSTRAINT ck_stock_damage_report_origin CHECK (origin IN ('STOCK_CHECK', 'PICKING')),
    CONSTRAINT ck_stock_damage_report_reason CHECK (reason_code IN
        ('BROKEN', 'DEFORMED', 'WET_OR_MOULDY', 'DIRTY_OR_MISPRINTED', 'OTHER')),
    -- "Other" with no explanation is not a reason (the same rule as stock_adjustment).
    CONSTRAINT ck_stock_damage_report_other_note CHECK (
        reason_code <> 'OTHER' OR (note IS NOT NULL AND length(trim(note)) > 0)),
    -- Damaged goods leave the location they were found at.
    CONSTRAINT ck_stock_damage_report_moved CHECK (source_location_code <> damage_location_code
                                                   AND source_stock_item_id <> damaged_stock_item_id),
    -- A picker's report names the pick line, the order and the allocation; a stock check names none of them.
    CONSTRAINT ck_stock_damage_report_picking CHECK (
        (origin = 'PICKING') = (pick_line_id IS NOT NULL)
        AND (origin = 'PICKING') = (order_id IS NOT NULL)
        AND (origin = 'PICKING') = (reservation_id IS NOT NULL)
        AND (impaired_reservation_id IS NULL OR origin = 'PICKING'))
);

CREATE INDEX ix_stock_damage_report_warehouse ON inventory.stock_damage_report (warehouse_id, reported_at);
CREATE INDEX ix_stock_damage_report_sku ON inventory.stock_damage_report (sku, reported_at);
CREATE INDEX ix_stock_damage_report_damaged ON inventory.stock_damage_report (damaged_stock_item_id);
CREATE INDEX ix_stock_damage_report_pick_line ON inventory.stock_damage_report (pick_line_id)
    WHERE pick_line_id IS NOT NULL;

-- What was reported is a record: nothing about the damaged goods or who saw them changes afterwards.
CREATE TRIGGER tg_stock_damage_report_immutable BEFORE UPDATE ON inventory.stock_damage_report
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('report_number', 'request_id', 'origin',
        'warehouse_id', 'sku', 'lot_number', 'serial_number', 'received_at', 'source_stock_item_id',
        'source_location_code', 'damaged_stock_item_id', 'damage_location_code', 'quantity', 'reason_code', 'note',
        'order_id', 'pick_line_id', 'reservation_id', 'impaired_reservation_id', 'reported_by', 'reported_at');

COMMENT ON TABLE inventory.stock_damage_report IS
    'Units of one stock row found damaged (docs 03 §5, BR-STK-003): moved, DAMAGED, to a QUARANTINE area with one MOVE ledger line (reference DAMAGE_REPORT); decided by stock_damage_disposition.';
COMMENT ON COLUMN inventory.stock_damage_report.origin IS
    'STOCK_CHECK: found on a shelf, free units only. PICKING: found by a picker among an allocation''s units (SCRUM-277).';
COMMENT ON COLUMN inventory.stock_damage_report.impaired_reservation_id IS
    'PICKING with no other stock to replace the damaged units: the order''s allocation of them, left on the DAMAGED row until the coordinator reallocates the short pick line.';


CREATE TABLE inventory.stock_damage_photo
(
    id               UUID          NOT NULL,
    damage_report_id UUID          NOT NULL,
    storage_key      VARCHAR(255)  NOT NULL,
    original_name    VARCHAR(255)  NOT NULL,
    content_type     VARCHAR(100)  NOT NULL,
    size_bytes       BIGINT        NOT NULL,
    checksum         VARCHAR(64)   NOT NULL,
    sort_order       INTEGER       NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_stock_damage_photo PRIMARY KEY (id),
    CONSTRAINT fk_stock_damage_photo_report FOREIGN KEY (damage_report_id)
        REFERENCES inventory.stock_damage_report (id) ON DELETE RESTRICT,
    CONSTRAINT uk_stock_damage_photo_key UNIQUE (storage_key),
    CONSTRAINT uk_stock_damage_photo_order UNIQUE (damage_report_id, sort_order),
    -- FileCategory.QC_EVIDENCE: photographs, checked by their first bytes before they were stored.
    CONSTRAINT ck_stock_damage_photo_key CHECK (storage_key LIKE 'qc-evidence/%'),
    CONSTRAINT ck_stock_damage_photo_type CHECK (content_type IN ('image/jpeg', 'image/png')),
    CONSTRAINT ck_stock_damage_photo_size CHECK (size_bytes > 0),
    CONSTRAINT ck_stock_damage_photo_checksum CHECK (checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_stock_damage_photo_sort CHECK (sort_order BETWEEN 1 AND 5)
);

CREATE TRIGGER tg_stock_damage_photo_append_only BEFORE UPDATE OR DELETE ON inventory.stock_damage_photo
    FOR EACH ROW EXECUTE FUNCTION platform.append_only();

-- BR-STK-003, stated a second time: a report without a photo does not commit.
CREATE FUNCTION inventory.check_stock_damage_report_photo()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM inventory.stock_damage_photo WHERE damage_report_id = NEW.id) THEN
        RAISE EXCEPTION 'damage report % has no photo (BR-STK-003)', NEW.report_number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_stock_damage_report_photo
    AFTER INSERT ON inventory.stock_damage_report
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_damage_report_photo();


CREATE TABLE inventory.stock_damage_disposition
(
    id                     UUID          NOT NULL,
    damage_report_id       UUID          NOT NULL,
    -- The caller's idempotency key: a retried decision finds the first one.
    request_id             UUID          NOT NULL,
    disposition_type       VARCHAR(24)   NOT NULL,
    quantity               INTEGER       NOT NULL,
    note                   VARCHAR(1000),
    -- WRITE_OFF: the number of the destruction record, and the adjustment it posted.
    record_number          VARCHAR(30),
    stock_adjustment_id    UUID,
    -- RESTORE: the bin the goods went back onto, and the row that took them.
    restore_location_code  VARCHAR(64),
    restored_stock_item_id UUID,
    -- RETURN_TO_SUPPLIER: who the goods go back to, from the lot when it is known.
    supplier_id            UUID,
    supplier_name          VARCHAR(200),
    decided_by             UUID          NOT NULL,
    decided_at             TIMESTAMPTZ   NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_stock_damage_disposition PRIMARY KEY (id),
    CONSTRAINT uk_stock_damage_disposition_request UNIQUE (request_id),
    CONSTRAINT uk_stock_damage_disposition_record UNIQUE (record_number),
    CONSTRAINT fk_stock_damage_disposition_report FOREIGN KEY (damage_report_id)
        REFERENCES inventory.stock_damage_report (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_damage_disposition_adjustment FOREIGN KEY (stock_adjustment_id)
        REFERENCES inventory.stock_adjustment (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_damage_disposition_location FOREIGN KEY (restore_location_code)
        REFERENCES warehouse.storage_location (location_code) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_damage_disposition_restored FOREIGN KEY (restored_stock_item_id)
        REFERENCES inventory.stock_item (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_damage_disposition_supplier FOREIGN KEY (supplier_id)
        REFERENCES procurement.suppliers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_stock_damage_disposition_decided_by FOREIGN KEY (decided_by)
        REFERENCES identity.app_user (id),
    CONSTRAINT ck_stock_damage_disposition_type CHECK (disposition_type IN
        ('WRITE_OFF', 'RETURN_TO_SUPPLIER', 'RESTORE')),
    CONSTRAINT ck_stock_damage_disposition_quantity CHECK (quantity > 0),
    CONSTRAINT ck_stock_damage_disposition_write_off CHECK (
        (disposition_type = 'WRITE_OFF') = (record_number IS NOT NULL AND stock_adjustment_id IS NOT NULL)),
    CONSTRAINT ck_stock_damage_disposition_restore CHECK (
        (disposition_type = 'RESTORE') = (restore_location_code IS NOT NULL AND restored_stock_item_id IS NOT NULL)),
    CONSTRAINT ck_stock_damage_disposition_supplier CHECK (
        disposition_type = 'RETURN_TO_SUPPLIER' OR (supplier_id IS NULL AND supplier_name IS NULL)),
    -- Putting goods back on sale, or sending them back, says why; a write-off is the report's own reason.
    CONSTRAINT ck_stock_damage_disposition_note CHECK (
        disposition_type = 'WRITE_OFF' OR (note IS NOT NULL AND length(trim(note)) > 0))
);

CREATE INDEX ix_stock_damage_disposition_report ON inventory.stock_damage_disposition (damage_report_id);

CREATE TRIGGER tg_stock_damage_disposition_append_only BEFORE UPDATE OR DELETE ON inventory.stock_damage_disposition
    FOR EACH ROW EXECUTE FUNCTION platform.append_only();

-- Four eyes, and never more units than were reported, stated a second time. The report row is locked first, so two
-- decisions of one report are checked one after the other (StockDamageReport#dispose says it first).
CREATE FUNCTION inventory.check_stock_damage_disposition()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    report  inventory.stock_damage_report%ROWTYPE;
    decided BIGINT;
BEGIN
    SELECT * INTO report FROM inventory.stock_damage_report WHERE id = NEW.damage_report_id FOR UPDATE;
    IF NEW.decided_by = report.reported_by THEN
        RAISE EXCEPTION 'damage report % cannot be decided by the person who reported it', report.report_number
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT COALESCE(SUM(quantity), 0) INTO decided
      FROM inventory.stock_damage_disposition WHERE damage_report_id = NEW.damage_report_id;
    IF decided + NEW.quantity > report.quantity THEN
        RAISE EXCEPTION 'damage report % has % unit(s); % decided already, % more asked', report.report_number,
            report.quantity, decided, NEW.quantity USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_stock_damage_disposition_check BEFORE INSERT ON inventory.stock_damage_disposition
    FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_damage_disposition();

COMMENT ON TABLE inventory.stock_damage_disposition IS
    'A warehouse manager''s decision on some units of a damage report (four eyes): WRITE_OFF (posted adjustment, record_number = the destruction record), RETURN_TO_SUPPLIER (intent; procurement ships them) or RESTORE (back onto a bin, AVAILABLE).';


-- ---- the ledger names the damage report ---------------------------------------------------------------
ALTER TABLE inventory.stock_movement DROP CONSTRAINT ck_stock_movement_reference;
ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_reference CHECK (reference_type IN
    ('GOODS_RECEIPT_LINE', 'QC_INSPECTION', 'PUTAWAY_TASK', 'PICK_LINE', 'PACKAGE', 'MOVE_TASK',
     'TRANSFER_ORDER_LINE', 'STOCK_ADJUSTMENT', 'CYCLE_COUNT_LINE', 'RETURN_REQUEST_LINE', 'ORDER',
     'PRODUCTION_ORDER', 'EXPIRY_SWEEP', 'DAMAGE_REPORT'));
