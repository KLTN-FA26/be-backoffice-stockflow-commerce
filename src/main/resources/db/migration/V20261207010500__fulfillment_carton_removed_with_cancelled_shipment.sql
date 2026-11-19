-- Owner decision RTM-RTDCANCEL (2026-11-19), kltn-docs 09 §5.1 "Cancelled", 08 §5.1: the warehouse manager cancels a
-- shipment waiting for dispatch, with a reason, and marks the cartons that are damaged. Every carton of it leaves the
-- shipment and the order: a damaged one is removed DAMAGED and goes into quarantine (as at the check station, 08 §4.2
-- step 4); a good one is removed for a reason of its own, SHIPMENT_CANCELLED, and goes back onto a bin through the
-- put-back queue. Its order line lacks it until a replacement is allocated, picked and checked. A person always cancels
-- the shipment, so the carton names who removed it, as a DAMAGED one does.
--
-- The column is widened: SHIPMENT_CANCELLED is 18 characters. The status edges of tg_package_carton already allow
-- LABELED -> REMOVED, and ck_package_shipped already wants a REMOVED carton in no shipment.

ALTER TABLE fulfillment.package
    DROP CONSTRAINT ck_package_removed;

ALTER TABLE fulfillment.package
    ALTER COLUMN removal_reason TYPE character varying(32);

ALTER TABLE fulfillment.package
    ADD CONSTRAINT ck_package_removed CHECK ((status = 'REMOVED') = (removed_at IS NOT NULL)
        AND (removed_at IS NULL) = (removal_reason IS NULL)
        AND (removal_reason IS NULL
             OR removal_reason IN ('DAMAGED', 'ORDER_CANCELLED', 'REMAINDER_CLOSED', 'SHIPMENT_CANCELLED'))
        AND (removal_reason IS DISTINCT FROM 'DAMAGED' OR removed_by IS NOT NULL)
        AND (removal_reason IS DISTINCT FROM 'SHIPMENT_CANCELLED' OR removed_by IS NOT NULL)
        AND (removal_note IS NULL OR btrim(removal_note) <> ''));
