-- =============================================================================
-- Subcontracting stock (SCRUM-441; decisions Q7, Q8, Q9 of 2026-10-12; BR-PRD-15..17; 04c F-PROD G5-G9).
--
-- 1. Q7: blanks sent to a print subcontractor are tracked per SUBCONTRACTOR and per SENDING WAREHOUSE
--    (subcontractor A holds 1,000 cups of HCM, subcontractor B 500). Each pair has one storage location of kind
--    SUBCONTRACTOR, code <warehouse>-SUB-<supplier code>, owned by a warehouse.subcontractor_site row instead of
--    a bin or an area: not on the map, not pickable, not a putaway target. Created by the system the first time
--    the warehouse sends that subcontractor blanks; nobody draws it.
-- 2. Q9: blanks that came back as finished goods leave the subcontractor's location as a CONSUMPTION ledger
--    line in the receipt's own transaction (they are in the goods receipt now - counting both would double the
--    stock); blanks spoilt there leave through an approved adjustment, reason SUBCONTRACT_LOSS (G8, the existing
--    four-eyes approval); surplus blanks come back free.
-- 3. Q8: each subcontract receipt line is held for the child on receipt (production_subcontract_hold), in the
--    receipt's own transaction, and handed to the print shop for QC, so finished goods are never free while they
--    wait for QC and a failed cup is scrapped as a cup, never put back as a blank. Q9: the warehouse reconciles
--    each receipt once - spoilt and returned blanks; whatever is left stays at the subcontractor, never assumed
--    lost. used_blanks is written with the receipt (SUPPLIED_BLANKS only: equal to what was received).
-- =============================================================================

-- 1. Subcontractor locations ------------------------------------------------------------------------------
ALTER TABLE warehouse.storage_location ALTER COLUMN kind TYPE VARCHAR(16);
ALTER TABLE warehouse.storage_location DROP CONSTRAINT ck_storage_location_kind;
ALTER TABLE warehouse.storage_location
    ADD CONSTRAINT ck_storage_location_kind CHECK (kind IN ('BIN', 'AREA', 'SUBCONTRACTOR'));
ALTER TABLE warehouse.storage_location DROP CONSTRAINT ck_storage_location_code;
ALTER TABLE warehouse.storage_location ADD CONSTRAINT ck_storage_location_code CHECK (
    (kind = 'BIN'  AND location_code ~ '^[A-Z0-9]{1,10}-[A-Z0-9]{1,20}-[1-9][0-9]?-[A-Z0-9]{1,20}$')
    OR (kind = 'AREA' AND location_code ~ '^[A-Z0-9]{1,10}-[A-Z0-9]{1,20}$')
    OR (kind = 'SUBCONTRACTOR' AND location_code ~ '^[A-Z0-9]{1,10}-SUB-[A-Z0-9]{1,20}$'
        AND NOT is_pickable AND NOT is_putaway_target));

CREATE TABLE warehouse.subcontractor_site
(
    id           UUID          NOT NULL,
    warehouse_id UUID          NOT NULL,
    supplier_id  UUID          NOT NULL,
    location_id  UUID          NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by   VARCHAR(100),

    CONSTRAINT pk_subcontractor_site PRIMARY KEY (id),
    CONSTRAINT uk_subcontractor_site UNIQUE (warehouse_id, supplier_id),
    CONSTRAINT uk_subcontractor_site_location UNIQUE (location_id),
    CONSTRAINT fk_subcontractor_site_warehouse FOREIGN KEY (warehouse_id)
        REFERENCES warehouse.warehouse (id) ON DELETE RESTRICT,
    CONSTRAINT fk_subcontractor_site_supplier FOREIGN KEY (supplier_id)
        REFERENCES procurement.suppliers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_subcontractor_site_location FOREIGN KEY (location_id)
        REFERENCES warehouse.storage_location (id) ON DELETE RESTRICT
);

-- As for a bin and an area: the site's location is a SUBCONTRACTOR location of the site's own warehouse.
CREATE FUNCTION warehouse.check_subcontractor_site_location()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    loc warehouse.storage_location%ROWTYPE;
BEGIN
    SELECT * INTO loc FROM warehouse.storage_location WHERE id = NEW.location_id;
    IF loc.kind IS DISTINCT FROM 'SUBCONTRACTOR' THEN
        RAISE EXCEPTION 'subcontractor site must point at a storage_location of kind SUBCONTRACTOR, got %', loc.kind
            USING ERRCODE = 'check_violation';
    END IF;
    IF loc.warehouse_id IS DISTINCT FROM NEW.warehouse_id THEN
        RAISE EXCEPTION 'subcontractor site and its storage_location are in different warehouses'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_subcontractor_site_location BEFORE INSERT OR UPDATE ON warehouse.subcontractor_site
    FOR EACH ROW EXECUTE FUNCTION warehouse.check_subcontractor_site_location();
CREATE TRIGGER tg_subcontractor_site_immutable BEFORE UPDATE ON warehouse.subcontractor_site
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('warehouse_id', 'supplier_id', 'location_id');

CREATE OR REPLACE FUNCTION warehouse.check_storage_location_owned()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM warehouse.storage_location WHERE id = NEW.id)
       AND NOT EXISTS (SELECT 1 FROM warehouse.bin WHERE location_id = NEW.id)
       AND NOT EXISTS (SELECT 1 FROM warehouse.area WHERE location_id = NEW.id)
       AND NOT EXISTS (SELECT 1 FROM warehouse.subcontractor_site WHERE location_id = NEW.id) THEN
        RAISE EXCEPTION 'storage_location % is owned by no bin, no area and no subcontractor site', NEW.location_code
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

-- 2. Ledger and adjustment reasons ------------------------------------------------------------------------
ALTER TABLE inventory.stock_movement DROP CONSTRAINT ck_stock_movement_type;
ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_type CHECK (movement_type IN ('RECEIPT',
    'PUTAWAY', 'PICK', 'PUT_BACK', 'MOVE', 'TRANSFER_OUT', 'TRANSFER_IN', 'ADJUSTMENT', 'COUNT_CORRECTION',
    'RETURN_IN', 'STATUS_CHANGE', 'SCRAP', 'CONSUMPTION'));
-- A consumption leaves a location and goes nowhere, as a pick or a scrap does. Without this branch the CASE
-- answers NULL for it, and a CHECK passes on NULL.
-- Every other branch is the one V20261014001000 (SCRUM-267) left, unchanged - in particular PUT_BACK keeps its
-- own branch: goods deducted when picked go back onto a bin from nowhere. Re-creating the constraint from the
-- older V20260928005000 shape would refuse those rows, and fail this migration on a database that has them.
ALTER TABLE inventory.stock_movement DROP CONSTRAINT ck_stock_movement_shape;
ALTER TABLE inventory.stock_movement ADD CONSTRAINT ck_stock_movement_shape CHECK (CASE
    WHEN movement_type IN ('RECEIPT', 'TRANSFER_IN', 'RETURN_IN')
        THEN from_location_code IS NULL AND to_location_code IS NOT NULL
    WHEN movement_type IN ('PICK', 'TRANSFER_OUT', 'SCRAP', 'CONSUMPTION')
        THEN from_location_code IS NOT NULL AND to_location_code IS NULL
    WHEN movement_type IN ('PUTAWAY', 'MOVE')
        THEN from_location_code IS NOT NULL AND to_location_code IS NOT NULL
             AND from_location_code <> to_location_code
    -- deducted when picked, so back onto a bin from nowhere; or moved back from a staging location
    WHEN movement_type = 'PUT_BACK'
        THEN to_location_code IS NOT NULL
             AND ((reference_type = 'PICK_LINE' AND from_location_code IS NULL)
                  OR (from_location_code IS NOT NULL AND from_location_code <> to_location_code))
    WHEN movement_type IN ('ADJUSTMENT', 'COUNT_CORRECTION')
        THEN num_nonnulls(from_location_code, to_location_code) = 1
    WHEN movement_type = 'STATUS_CHANGE'
        THEN from_location_code = to_location_code
             AND from_status IS NOT NULL AND to_status IS NOT NULL AND from_status <> to_status
    END);

ALTER TABLE inventory.stock_adjustment DROP CONSTRAINT ck_stock_adjustment_reason;
ALTER TABLE inventory.stock_adjustment ADD CONSTRAINT ck_stock_adjustment_reason CHECK (reason_code IN
    ('DAMAGED', 'LOST', 'FOUND', 'COUNT_VARIANCE', 'EXPIRED', 'DATA_CORRECTION', 'SCRAP', 'SAMPLE', 'OTHER',
     'SUBCONTRACT_LOSS'));

-- 3. Receipts of subcontracted goods ----------------------------------------------------------------------
ALTER TABLE production.production_subcontract_receipt
    ADD COLUMN used_blanks      INTEGER,
    ADD COLUMN spoilt_blanks    INTEGER,
    ADD COLUMN returned_blanks  INTEGER,
    ADD COLUMN reconciled_at    TIMESTAMPTZ,
    ADD COLUMN reconciled_by    UUID,
    ADD CONSTRAINT fk_production_subcontract_receipt_reconciled_by FOREIGN KEY (reconciled_by)
        REFERENCES identity.app_user (id),
    ADD CONSTRAINT ck_production_subcontract_receipt_used CHECK (used_blanks IS NULL OR used_blanks = quantity),
    ADD CONSTRAINT ck_production_subcontract_receipt_reconciled CHECK (
        (reconciled_at IS NULL) = (reconciled_by IS NULL)
        AND (reconciled_at IS NULL) = (spoilt_blanks IS NULL)
        AND (reconciled_at IS NULL) = (returned_blanks IS NULL)
        AND (reconciled_at IS NULL OR used_blanks IS NOT NULL)
        AND (spoilt_blanks IS NULL OR (spoilt_blanks >= 0 AND returned_blanks >= 0)));

CREATE TABLE production.production_subcontract_hold
(
    reservation_id      UUID NOT NULL,
    receipt_id          UUID NOT NULL,
    receipt_line_id     UUID NOT NULL,
    production_order_id UUID NOT NULL,

    CONSTRAINT pk_production_subcontract_hold PRIMARY KEY (reservation_id),
    CONSTRAINT uk_production_subcontract_hold_line UNIQUE (receipt_line_id),
    CONSTRAINT fk_production_subcontract_hold_reservation FOREIGN KEY (reservation_id)
        REFERENCES inventory.stock_reservation (id) ON DELETE RESTRICT,
    CONSTRAINT fk_production_subcontract_hold_receipt FOREIGN KEY (receipt_id)
        REFERENCES production.production_subcontract_receipt (receipt_id) ON DELETE CASCADE,
    CONSTRAINT fk_production_subcontract_hold_order FOREIGN KEY (production_order_id)
        REFERENCES production.production_order (id) ON DELETE CASCADE
);

CREATE INDEX ix_production_subcontract_hold_order ON production.production_subcontract_hold (production_order_id);
