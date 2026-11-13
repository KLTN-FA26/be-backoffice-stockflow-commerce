-- Owner decision WF4-CLOSE-REST (2026-11-13), kltn-docs 17 §4.8 step 33: when Sales closes the rest of a partly
-- fulfilled order, the cartons already checked and labelled stay on it and are delivered; a carton of the closed part
-- that the check station holds unlabelled (TO_CHECK or CHECKED) is removed from the order and goes back onto a bin
-- through the put-back queue. It is removed for its own reason, REMAINDER_CLOSED, next to DAMAGED (a worker, to
-- quarantine) and ORDER_CANCELLED (the system, the whole order cancelled). The status edges of tg_package_carton already
-- allow TO_CHECK / CHECKED -> REMOVED.

ALTER TABLE fulfillment.package
    DROP CONSTRAINT ck_package_removed;

ALTER TABLE fulfillment.package
    ADD CONSTRAINT ck_package_removed CHECK ((status = 'REMOVED') = (removed_at IS NOT NULL)
        AND (removed_at IS NULL) = (removal_reason IS NULL)
        AND (removal_reason IS NULL OR removal_reason IN ('DAMAGED', 'ORDER_CANCELLED', 'REMAINDER_CLOSED'))
        AND (removal_reason IS DISTINCT FROM 'DAMAGED' OR removed_by IS NOT NULL)
        AND (removal_note IS NULL OR btrim(removal_note) <> ''));
