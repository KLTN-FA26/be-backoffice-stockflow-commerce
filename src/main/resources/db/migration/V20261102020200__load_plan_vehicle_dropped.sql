-- =============================================================================
-- Drop a vehicle from its plan (SCRUM-474 item 1, owner decision UNLOAD-DROP 02/11/2026; kltn-docs 09 BR-14, BR-15).
-- Module: fulfillment
--
-- A vehicle still PLANNED (no truck booked, nothing loaded) of a confirmed plan whose other vehicles already left can be
-- dropped from the plan: its load is CANCELLED (dispatch_load.cancel_reason_code + the note, as at a controlled cancel),
-- the vehicle is retired, and the parcels placed on it are released from the plan with the new reason VEHICLE_DROPPED -
-- they then wait for a new plan like a parcel not planned yet (no new status).
--
--   load_plan_package.release_reason   + VEHICLE_DROPPED
-- =============================================================================

ALTER TABLE fulfillment.load_plan_package
    DROP CONSTRAINT ck_load_plan_package_release,
    ADD CONSTRAINT ck_load_plan_package_release CHECK (release_reason IS NULL OR release_reason IN
        ('INPUTS_CHANGED', 'LEFT_BEHIND', 'PLAN_CANCELLED', 'ORDER_CANCELLED', 'VEHICLE_DROPPED'));
