-- =============================================================================
-- The return flow of the QC era goes (team plan 4D-4; kltn-docs 17 §4.10, BR-18; 03 §4.2; 15 BR-05; owner's decision
-- QD-1 of 10/11: the data is not kept, nothing is archived, nothing stops the upgrade).
--
-- Since 4D-3 a return's goods come back on a return receipt and its refund is settled against the installments; the
-- QC inspection, the counted parcels, the free replacement orders and the credit kept for the customer have no code.
-- This migration cleans up what they left, drops their columns and narrows the sets they widened:
--
--   1. Returns of the QC era still open with goods counted in by parcel (RECEIVED, INSPECTED, SETTLEMENT_PENDING with
--      no return receipt) cannot go on: they are CANCELLED, their proposal cleared, with a status history line. Closed
--      ones stay as history: a credit is folded into the amount set off, a requested resolution or money mode other
--      than REFUND reads REFUND. A RESOLVED return keeps its claim on the order lines: its accepted units become its
--      refunded units (refund_quantity, at most approved and received), and its settlement is spread over those lines
--      by value (refund_amount, the last line taking the rounding), so check_return_request_line, which counts a
--      resolved line by refund_quantity, still counts them and the same units cannot be returned and refunded twice.
--   2. ordering.return_request loses the QC, parcel, credit, replacement and withdrawal columns; return_request_line
--      the received SKU and lot, the inspection, the disposition, the stock write-offs, the replacement and the credit.
--      ck_return_request_amounts, ck_return_request_vat (vat_adjustment_required recomputed first: a replacement
--      withdrawal no longer counts) and ck_return_request_line_received (dropped with received_sku) are restated on
--      what is left; ck_return_request_status (no
--      INSPECTED), ck_return_request_resolution and ck_return_request_money_mode (REFUND only) narrowed;
--      check_return_request_line counts a resolved line by its refunded units. ck_return_request_goods_back is
--      validated; ck_return_request_goods_required and ck_return_request_evidence_claim_only stay NOT VALID - a return
--      settled on photos alone, and the warehouse's and QC's photos, are history that may not hold them.
--   3. ordering.customer_order.replaces_return_request_id goes with the free replacement orders. A replacement order
--      CONFIRMED is recorded as paid in full at nothing, so ck_order_confirmed_basis is restated without the
--      replacement (team plan 4B-10's version names it; dropping the column would otherwise drop that CHECK silently).
--      CancellationReasonCode REPLACEMENT_WITHDRAWN stays allowed: cancelled orders name it.
--   4. Area type QUALITY_CONTROL: such areas become QUARANTINE areas - INACTIVE when they hold no stock - keeping their
--      code and location (the ledger names them); ck_area_type narrowed. The demo's HCM-QCA01 (V20261010000110) is one.
--   5. Permissions sales-rma-inspections and sales-rma-receipts: their grants and rows are deleted, and every role that
--      held one has its version advanced (ADR-0008).
--   6. Unfinished event publications of contracts.ReturnReplacementWithdrawn are deleted: the record and its listeners
--      are gone.
--
-- Dropped on ordering.return_request (cluster 3's V20261116000100 touches some of these: every drop is IF EXISTS):
--   columns inspected_by, inspected_at, inspection_note, receipt_note, parcel_count, credited_amount,
--   credit_transfer_id, replacement_order_id, withdrawal_status, withdrawal_reason, withdrawal_amount,
--   withdrawal_proposed_by, withdrawal_proposed_at, withdrawal_decided_by, withdrawal_decided_at,
--   withdrawal_decision_note, withdrawal_offset_amount, withdrawal_credited_amount, withdrawal_credit_transfer_id;
--   with them fk_return_request_inspected_by, fk_return_request_credit_transfer, fk_return_request_replacement_order,
--   fk_return_request_withdrawal_proposed_by, fk_return_request_withdrawal_decided_by,
--   fk_return_request_withdrawal_transfer, uk_return_request_replacement_order, ck_return_request_withdrawal,
--   ck_return_request_amounts and ck_return_request_vat (both restated). ck_return_request_credit_transfer went in
--   V20261114000100.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. QC-era returns
-- ---------------------------------------------------------------------------------------------
WITH stuck AS (
    UPDATE ordering.return_request r
       SET status              = 'CANCELLED',
           cancelled_by        = COALESCE(r.decided_by, r.requested_by),
           cancellation_reason = 'Closed by V20261114000200: its goods were counted in before return receipts '
                                     || '(team plan 4D-4)',
           closed_at           = NOW(),
           money_mode          = NULL,
           settlement_amount   = 0,
           proposed_by         = NULL,
           proposed_at         = NULL,
           resolution_note     = NULL,
           last_modified_at    = NOW(),
           last_modified_by    = 'flyway'
     WHERE r.status IN ('RECEIVED', 'INSPECTED', 'SETTLEMENT_PENDING') AND r.goods_receipt_id IS NULL
    RETURNING r.id, (SELECT h.to_status FROM ordering.return_request_status_history h
                      WHERE h.return_request_id = r.id ORDER BY h.occurred_at DESC, h.created_at DESC LIMIT 1)
                     AS was, r.cancelled_by
)
INSERT INTO ordering.return_request_status_history (id, return_request_id, from_status, to_status, reason, actor_id,
                                                    occurred_at, created_at, created_by)
SELECT gen_random_uuid(), stuck.id, stuck.was, 'CANCELLED', 'QC_ERA_RETURN_CLOSED_BY_MIGRATION', NULL, NOW(), NOW(),
       'flyway'
  FROM stuck;

UPDATE ordering.return_request_line l
   SET refund_quantity = 0, refund_amount = 0
  FROM ordering.return_request r
 WHERE r.id = l.return_request_id AND r.status = 'CANCELLED' AND (l.refund_quantity <> 0 OR l.refund_amount <> 0);
-- The refund columns' constraint triggers (tg_return_request_line_refund) are deferred to the commit; events still
-- pending would make the ALTER TABLE of return_request_line below refuse to run (55006, "pending trigger events").
-- Checked here and now instead.
SET CONSTRAINTS ALL IMMEDIATE;

-- A RESOLVED return of the QC era keeps its claim (review M2): its accepted units become its refunded units and its
-- settlement is spread over them by value, the last line (by id) taking what rounding left, so the lines add up to it
-- exactly (check_return_request_refund, deferred to the commit). Returns resolved since 4D-3 name refunded units
-- already and are left alone.
WITH legacy AS (
    SELECT l.id, l.return_request_id, r.settlement_amount, l.unit_price,
           LEAST(l.accepted_quantity, COALESCE(l.approved_quantity, 0), COALESCE(l.received_quantity, 0)) AS units
      FROM ordering.return_request_line l
      JOIN ordering.return_request r ON r.id = l.return_request_id
     WHERE r.status = 'RESOLVED' AND COALESCE(l.accepted_quantity, 0) > 0
       AND NOT EXISTS (SELECT 1 FROM ordering.return_request_line x
                        WHERE x.return_request_id = r.id AND x.refund_quantity > 0)
), weighted AS (
    SELECT id, return_request_id, settlement_amount, units,
           CASE WHEN SUM(units * unit_price) OVER w > 0 THEN units * unit_price ELSE units END AS weight,
           CASE WHEN SUM(units * unit_price) OVER w > 0 THEN SUM(units * unit_price) OVER w
                ELSE SUM(units) OVER w END AS total,
           ROW_NUMBER() OVER (w ORDER BY id DESC) AS from_last
      FROM legacy
     WHERE units > 0
    WINDOW w AS (PARTITION BY return_request_id)
), shared AS (
    SELECT id, return_request_id, settlement_amount, units, from_last,
           trunc(settlement_amount * weight / total, 4) AS share
      FROM weighted
)
UPDATE ordering.return_request_line l
   SET refund_quantity = s.units,
       refund_amount   = CASE WHEN s.from_last = 1
                              THEN s.settlement_amount - (SELECT COALESCE(SUM(o.share), 0) FROM shared o
                                                           WHERE o.return_request_id = s.return_request_id
                                                             AND o.from_last > 1)
                              ELSE s.share END
  FROM shared s
 WHERE l.id = s.id;
-- As above: the back-fill's deferred refund checks run now, so the columns can be dropped in 2 (tester's bug 3).
SET CONSTRAINTS ALL IMMEDIATE;

UPDATE ordering.return_request
   SET offset_amount = offset_amount + credited_amount, credited_amount = 0
 WHERE credited_amount <> 0;
UPDATE ordering.return_request SET requested_resolution = 'REFUND' WHERE requested_resolution <> 'REFUND';
UPDATE ordering.return_request SET money_mode = 'REFUND' WHERE money_mode IS NOT NULL AND money_mode <> 'REFUND';


-- ---------------------------------------------------------------------------------------------
-- 2. Columns of the QC era
-- ---------------------------------------------------------------------------------------------
ALTER TABLE ordering.return_request
    DROP COLUMN IF EXISTS inspected_by,
    DROP COLUMN IF EXISTS inspected_at,
    DROP COLUMN IF EXISTS inspection_note,
    DROP COLUMN IF EXISTS receipt_note,
    DROP COLUMN IF EXISTS parcel_count,
    DROP COLUMN IF EXISTS credited_amount,
    DROP COLUMN IF EXISTS credit_transfer_id,
    DROP COLUMN IF EXISTS replacement_order_id,
    DROP COLUMN IF EXISTS withdrawal_status,
    DROP COLUMN IF EXISTS withdrawal_reason,
    DROP COLUMN IF EXISTS withdrawal_amount,
    DROP COLUMN IF EXISTS withdrawal_proposed_by,
    DROP COLUMN IF EXISTS withdrawal_proposed_at,
    DROP COLUMN IF EXISTS withdrawal_decided_by,
    DROP COLUMN IF EXISTS withdrawal_decided_at,
    DROP COLUMN IF EXISTS withdrawal_decision_note,
    DROP COLUMN IF EXISTS withdrawal_offset_amount,
    DROP COLUMN IF EXISTS withdrawal_credited_amount,
    DROP COLUMN IF EXISTS withdrawal_credit_transfer_id;

-- A replacement whose withdrawal was approved with an amount raised the VAT flag on a return settled at nothing; the
-- withdrawal is gone (and the old ck_return_request_vat with its columns), so the flag is what the settlement says
-- (review M1), or the restated ck_return_request_vat below refuses the row.
UPDATE ordering.return_request
   SET vat_adjustment_required = (status = 'RESOLVED' AND settlement_amount > 0)
 WHERE vat_adjustment_required IS DISTINCT FROM (status = 'RESOLVED' AND settlement_amount > 0);

ALTER TABLE ordering.return_request
    DROP CONSTRAINT IF EXISTS ck_return_request_amounts,
    ADD CONSTRAINT ck_return_request_amounts CHECK (
        settlement_amount >= 0 AND refunded_amount >= 0 AND offset_amount >= 0
        AND (status <> 'RESOLVED' OR refunded_amount + offset_amount = settlement_amount)
        AND (status = 'RESOLVED' OR (refunded_amount = 0 AND offset_amount = 0))),
    DROP CONSTRAINT IF EXISTS ck_return_request_vat,
    ADD CONSTRAINT ck_return_request_vat CHECK (
        vat_adjustment_required = (status = 'RESOLVED' AND settlement_amount > 0)),
    DROP CONSTRAINT ck_return_request_status,
    ADD CONSTRAINT ck_return_request_status CHECK (status IN ('REQUESTED', 'AWAITING_RETURN', 'RECEIVED',
        'SETTLEMENT_PENDING', 'RESOLVED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    DROP CONSTRAINT ck_return_request_resolution,
    ADD CONSTRAINT ck_return_request_resolution CHECK (requested_resolution = 'REFUND'),
    DROP CONSTRAINT ck_return_request_money_mode,
    ADD CONSTRAINT ck_return_request_money_mode CHECK (
        (money_mode IS NULL OR money_mode = 'REFUND') AND (settlement_amount > 0) = (money_mode IS NOT NULL));

ALTER TABLE ordering.return_request ALTER COLUMN requested_resolution SET DEFAULT 'REFUND';
ALTER TABLE ordering.return_request VALIDATE CONSTRAINT ck_return_request_goods_back;

ALTER TABLE ordering.return_request_line
    DROP COLUMN IF EXISTS received_sku,
    DROP COLUMN IF EXISTS lot_number,
    DROP COLUMN IF EXISTS accepted_quantity,
    DROP COLUMN IF EXISTS rejected_quantity,
    DROP COLUMN IF EXISTS item_condition,
    DROP COLUMN IF EXISTS inspection_note,
    DROP COLUMN IF EXISTS restock_quantity,
    DROP COLUMN IF EXISTS scrap_quantity,
    DROP COLUMN IF EXISTS customer_return_quantity,
    DROP COLUMN IF EXISTS restock_location_code,
    DROP COLUMN IF EXISTS received_plain,
    DROP COLUMN IF EXISTS disposal_consent_note,
    DROP COLUMN IF EXISTS scrap_adjustment_id,
    DROP COLUMN IF EXISTS customer_return_adjustment_id,
    DROP COLUMN IF EXISTS replacement_quantity,
    DROP COLUMN IF EXISTS credit_quantity,
    DROP COLUMN IF EXISTS credit_amount,
    DROP COLUMN IF EXISTS replacement_order_line_id;

-- Dropping received_sku took ck_return_request_line_received with it (review L2): its own half, restated. ReturnLine.receive.
ALTER TABLE ordering.return_request_line DROP CONSTRAINT IF EXISTS ck_return_request_line_received;
ALTER TABLE ordering.return_request_line ADD CONSTRAINT ck_return_request_line_received
    CHECK (received_quantity IS NULL OR received_quantity >= 0);

COMMENT ON COLUMN ordering.return_request.offset_amount IS
    'A resolved return: what its refund took off what the order''s installments still owed (team plan 4D-3); for a '
    'return of the QC era, what was set off against receivables or kept as credit.';
COMMENT ON COLUMN ordering.return_request.refunded_amount IS
    'A resolved return: what was paid already and is to be refunded (payment.order_settlement GOODS_RETURNED).';

CREATE OR REPLACE FUNCTION ordering.check_return_request_line() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    deliverable    INTEGER;
    claimed        INTEGER;
    open_elsewhere BOOLEAN;
BEGIN
    SELECT l.quantity - l.cancelled_quantity INTO deliverable
      FROM ordering.order_line l
      JOIN ordering.return_request r ON r.order_id = l.order_id
     WHERE l.id = NEW.order_line_id AND r.id = NEW.return_request_id
       FOR UPDATE OF l;
    IF deliverable IS NULL THEN
        RAISE EXCEPTION 'return line must be a line of the returned order' USING ERRCODE = 'check_violation';
    END IF;
    -- ReturnLine.claimedUnits: asked for, approved while open, refunded once resolved.
    SELECT COALESCE(SUM(CASE
                            WHEN r.status = 'REQUESTED' THEN x.quantity
                            WHEN r.status IN ('AWAITING_RETURN', 'RECEIVED', 'SETTLEMENT_PENDING')
                                THEN x.approved_quantity
                            WHEN r.status = 'RESOLVED' THEN x.refund_quantity
                            ELSE 0 END), 0),
           COALESCE(bool_or(r.status IN ('REQUESTED', 'AWAITING_RETURN', 'RECEIVED', 'SETTLEMENT_PENDING')), FALSE)
      INTO claimed, open_elsewhere
      FROM ordering.return_request_line x
      JOIN ordering.return_request r ON r.id = x.return_request_id
     WHERE x.order_line_id = NEW.order_line_id AND x.return_request_id <> NEW.return_request_id;
    IF TG_OP = 'INSERT' AND open_elsewhere THEN
        RAISE EXCEPTION 'order line % already has an open return', NEW.order_line_id
            USING ERRCODE = 'check_violation';
    END IF;
    -- BR-RMA-002: goods printed to the customer's design come back for a named defect only (ReturnReason.isNamedFault).
    IF TG_OP = 'INSERT' AND NEW.custom_printed AND EXISTS (SELECT 1 FROM ordering.return_request r
                                                           WHERE r.id = NEW.return_request_id
                                                             AND r.reason_code IN ('CHANGED_MIND', 'OTHER')) THEN
        RAISE EXCEPTION 'printed goods come back for a named defect only (BR-RMA-002)' USING ERRCODE = 'check_violation';
    END IF;
    IF claimed + COALESCE(NEW.approved_quantity, NEW.quantity) > deliverable THEN
        RAISE EXCEPTION 'would claim % of the % unit(s) delivered',
            claimed + COALESCE(NEW.approved_quantity, NEW.quantity), deliverable USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;


-- ---------------------------------------------------------------------------------------------
-- 3. Free replacement orders
-- ---------------------------------------------------------------------------------------------
UPDATE ordering.customer_order
   SET payment_term = 'PREPAID', paid_in_full_at = COALESCE(paid_in_full_at, NOW())
 WHERE replaces_return_request_id IS NOT NULL AND status = 'CONFIRMED' AND contract_signed_at IS NULL
   AND (payment_term IS DISTINCT FROM 'PREPAID' OR paid_in_full_at IS NULL);

ALTER TABLE ordering.customer_order DROP CONSTRAINT IF EXISTS ck_order_confirmed_basis;
ALTER TABLE ordering.customer_order ADD CONSTRAINT ck_order_confirmed_basis CHECK (
    status <> 'CONFIRMED'
    OR contract_signed_at IS NOT NULL
    OR (payment_term = 'PREPAID' AND paid_in_full_at IS NOT NULL)
    OR (payment_term = 'DEPOSIT' AND deposit_received_at IS NOT NULL)
    OR payment_term = 'CREDIT');

ALTER TABLE ordering.customer_order DROP COLUMN IF EXISTS replaces_return_request_id;


-- ---------------------------------------------------------------------------------------------
-- 4. Area type QUALITY_CONTROL
-- ---------------------------------------------------------------------------------------------
UPDATE warehouse.area a
   SET type             = 'QUARANTINE',
       status           = CASE WHEN EXISTS (SELECT 1 FROM warehouse.storage_location s
                                              JOIN inventory.stock_item i ON i.location_code = s.location_code
                                             WHERE s.id = a.location_id AND i.on_hand > 0)
                               THEN a.status ELSE 'INACTIVE' END,
       last_modified_at = NOW(),
       last_modified_by = 'flyway'
 WHERE a.type = 'QUALITY_CONTROL';

ALTER TABLE warehouse.area DROP CONSTRAINT ck_area_type;
ALTER TABLE warehouse.area ADD CONSTRAINT ck_area_type CHECK (type IN
    ('RECEIVING', 'QUARANTINE', 'DISPATCH', 'STAGING', 'OVERFLOW', 'PRODUCTION', 'NON_STORAGE'));


-- ---------------------------------------------------------------------------------------------
-- 5. Permissions of the QC era
-- ---------------------------------------------------------------------------------------------
WITH revoked AS (
    DELETE FROM identity.role_permission rp
     USING identity.permission p
     WHERE p.id = rp.permission_id AND p.resource IN ('sales-rma-inspections', 'sales-rma-receipts')
    RETURNING rp.role_id
)
-- Grants are cached per role version (ADR-0008): advance every role whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT role_id FROM revoked);

DELETE FROM identity.permission WHERE resource IN ('sales-rma-inspections', 'sales-rma-receipts');


-- ---------------------------------------------------------------------------------------------
-- 6. Event publications of a removed contract
-- ---------------------------------------------------------------------------------------------
DELETE FROM event_publication
 WHERE completion_date IS NULL AND event_type = 'com.stockflow.contracts.ReturnReplacementWithdrawn';
