-- =============================================================================
-- WF-05 audit r3 EINV-1: a delivery corrected to a failure keeps the time of the delivery it undid, so that the
-- correction back to delivered is dated by that delivery and not by the moment of the correction.
--
-- Why. A shipment delivered on D1 is invoiced dated D1. Corrected to a failure, its invoice is reduced to nothing
-- (HĐ-3). Found delivered after all on D2, it is corrected back - and with no time given that correction was dated D2.
-- Invoicing tells a correction back (the same delivery: the reduction is voided and the D1 invoice stands again) from a
-- redelivery on another day (a new delivery: the reduction stays, a new original is drawn up) by the delivery's day
-- (EInvoiceDrafts.redeliveredOnAnotherDay, audit r2 M-3), so D2 made one delivery carry three invoices - the D1 one, its
-- reduction and a D2 original dated a day ownership did not pass (NĐ 254/2026 Điều 9 khoản 1) - and moved the payment and
-- return windows to D2. ShipmentService.correctOutcome now dates a correction back with no time by the delivery the
-- failure undid, and the screen fills the field with it (ShipmentDetail.undoneDeliveredAt).
--
-- What this does:
--   1. delivery_outcome_correction.undone_delivered_at: on a correction from DELIVERED to DELIVERY_FAILED, the
--      shipment's outcome time before it. Written once (the row is append-only).
--   2. Rows from before: the shipment's latest DELIVERED step at or before the correction (shipment_event, which every
--      outcome - recorded, reported or corrected - writes with the delivery's own time). A row whose timeline shows none
--      stays NULL: its correction back is dated now, as before.
--   3. ck_delivery_outcome_correction_undone: only a correction of a delivery names one, and never after it was made.
--      ck_delivery_outcome_correction_undone_kept (NOT VALID: rows from before may lack it): every new correction of a
--      delivery names it - the aggregate's rule stated again.
-- =============================================================================

ALTER TABLE fulfillment.delivery_outcome_correction ADD COLUMN undone_delivered_at TIMESTAMPTZ;

UPDATE fulfillment.delivery_outcome_correction c
   SET undone_delivered_at = (SELECT max(e.occurred_at) FROM fulfillment.shipment_event e
                               WHERE e.shipment_id = c.shipment_id AND e.status = 'DELIVERED'
                                 AND e.occurred_at <= c.corrected_at)
 WHERE c.from_status = 'DELIVERED' AND c.to_status = 'DELIVERY_FAILED';

ALTER TABLE fulfillment.delivery_outcome_correction
    ADD CONSTRAINT ck_delivery_outcome_correction_undone CHECK (
        undone_delivered_at IS NULL
        OR (from_status = 'DELIVERED' AND to_status = 'DELIVERY_FAILED' AND undone_delivered_at <= corrected_at));

ALTER TABLE fulfillment.delivery_outcome_correction
    ADD CONSTRAINT ck_delivery_outcome_correction_undone_kept CHECK (
        from_status <> 'DELIVERED' OR undone_delivered_at IS NOT NULL) NOT VALID;

COMMENT ON COLUMN fulfillment.delivery_outcome_correction.undone_delivered_at IS
    'WF-05 audit r3 EINV-1: on a correction of a delivery to a failure, the time of the delivery it undid; a correction back to delivered with no time given is dated by it, so the invoice of that delivery stands for it again instead of a second original dated the day of the correction.';
