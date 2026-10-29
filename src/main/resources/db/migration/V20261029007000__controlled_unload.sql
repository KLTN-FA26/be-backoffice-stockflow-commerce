-- =============================================================================
-- Controlled cancel after loading started (SCRUM-474 item 1, owner decision UNLOAD-DESIGN 29/10/2026; kltn-docs 09 BR-14).
-- Module: fulfillment
--
-- Once a vehicle of a confirmed plan starts loading, the plan can be neither reopened nor cancelled (BR-14). The owner
-- decided how a broken or wrong truck is handled: the plan - or ONE vehicle - is CANCELLED at once, its parcels stay locked
-- until each is scanned off (a warehouse manager may confirm one without a scan when its label is damaged), and the
-- reason is a code plus a note. No reopening, no new status: LOADING -> CANCELLED, plus a new PLANNED load of the same
-- plan vehicle when only that vehicle is called off.
--
--   dispatch_load           + cancel_reason_code   the code of a controlled cancel (the note is cancel_reason; who and
--                                                  when are cancelled_by / cancelled_at).
--   load_plan               + cancel_reason_code   the same, for a whole plan.
--   dispatch_load_unload    + reason_code          the code, for the unloads a controlled cancel opens (null: a cancelled
--                                                  order's parcel, SCRUM-392).
--                           + overridden, override_note   confirmed off by a warehouse manager without a scan
--                                                  (unloaded_by / unloaded_at say who and when).
--                           + transfer_order_id    a TRANSFER plan's vehicle carries a transfer order, not a shipment:
--                                                  shipment_id becomes nullable and exactly one of the two is set (as on
--                                                  dispatch_load_package since V20261023000400).
--
-- The lock: a parcel with a pending unload (unloaded_at IS NULL) is not put on any load and not claimed by any plan.
-- The service says 409 PACKAGE_UNLOAD_PENDING first; the triggers below are the net.
--
-- The permission to call a plan or a vehicle off this way is V20261029007100.
-- =============================================================================


-- ----------------------------------------------------------------------------- the reason codes
ALTER TABLE fulfillment.dispatch_load
    ADD COLUMN cancel_reason_code VARCHAR(20),
    ADD CONSTRAINT ck_dispatch_load_cancel_reason_code CHECK (cancel_reason_code IS NULL
        OR (status = 'CANCELLED' AND cancel_reason_code IN ('XE_HONG', 'SAI_XE', 'KIEN_KHONG_VUA', 'KHAC')));

ALTER TABLE fulfillment.load_plan
    ADD COLUMN cancel_reason_code VARCHAR(20),
    ADD CONSTRAINT ck_load_plan_cancel_reason_code CHECK (cancel_reason_code IS NULL
        OR (status = 'CANCELLED' AND cancel_reason_code IN ('XE_HONG', 'SAI_XE', 'KIEN_KHONG_VUA', 'KHAC')));

COMMENT ON COLUMN fulfillment.dispatch_load.cancel_reason_code IS
    'XE_HONG | SAI_XE | KIEN_KHONG_VUA | KHAC: set only when the vehicle was called off after loading started (controlled cancel, SCRUM-474); cancel_reason is then the note.';
COMMENT ON COLUMN fulfillment.load_plan.cancel_reason_code IS
    'XE_HONG | SAI_XE | KIEN_KHONG_VUA | KHAC: set only when the plan was called off after loading started (controlled cancel, SCRUM-474); cancel_reason is then the note.';


-- ----------------------------------------------------------------------------- dispatch_load_unload
ALTER TABLE fulfillment.dispatch_load_unload
    ALTER COLUMN shipment_id DROP NOT NULL,
    ADD COLUMN transfer_order_id UUID,
    ADD COLUMN reason_code       VARCHAR(20),
    ADD COLUMN overridden        BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN override_note     VARCHAR(500),
    ADD CONSTRAINT fk_dispatch_load_unload_transfer FOREIGN KEY (transfer_order_id)
        REFERENCES inventory.transfer_order (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_dispatch_load_unload_source CHECK ((shipment_id IS NULL) <> (transfer_order_id IS NULL)),
    ADD CONSTRAINT ck_dispatch_load_unload_reason_code CHECK (reason_code IS NULL
        OR reason_code IN ('XE_HONG', 'SAI_XE', 'KIEN_KHONG_VUA', 'KHAC')),
    -- Without a scan only after the fact: who and when are unloaded_by / unloaded_at.
    ADD CONSTRAINT ck_dispatch_load_unload_override CHECK (NOT overridden OR unloaded_at IS NOT NULL),
    ADD CONSTRAINT ck_dispatch_load_unload_override_note CHECK (override_note IS NULL OR overridden);

-- The lock's lookup: is this parcel still to be scanned off somewhere?
CREATE INDEX ix_dispatch_load_unload_package_pending ON fulfillment.dispatch_load_unload (package_id)
    WHERE unloaded_at IS NULL;


-- ----------------------------------------------------------------------------- the lock, a second time
-- A parcel still on a vehicle that was called off is neither loaded onto a vehicle nor planned until it is scanned off.
CREATE FUNCTION fulfillment.check_package_unloaded()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.active AND (TG_OP = 'INSERT' OR NOT OLD.active)
        AND EXISTS (SELECT 1 FROM fulfillment.dispatch_load_unload u
                     WHERE u.package_id = NEW.package_id AND u.unloaded_at IS NULL) THEN
        RAISE EXCEPTION 'tg_package_awaiting_unload: package % is still to be scanned off a vehicle', NEW.package_id
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_package_awaiting_unload';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_dispatch_load_package_awaiting_unload BEFORE INSERT OR UPDATE OF active
    ON fulfillment.dispatch_load_package
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_package_unloaded();

CREATE TRIGGER tg_load_plan_package_awaiting_unload BEFORE INSERT OR UPDATE OF active
    ON fulfillment.load_plan_package
    FOR EACH ROW EXECUTE FUNCTION fulfillment.check_package_unloaded();
