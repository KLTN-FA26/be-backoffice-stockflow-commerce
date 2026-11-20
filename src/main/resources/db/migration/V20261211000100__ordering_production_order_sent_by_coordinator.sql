-- =============================================================================
-- The Production Coordinator sends a production order, with no approval (owner, 2026-11-20; design
-- docs/superpowers/specs/2026-11-20-coordinator-owns-production-design.md §2-§3; kltn-docs 17 §4.3 step 7 and BR-23
-- rewritten, §5.3).
--
-- Why. The Sales Manager's approval was a second person reading back what the coordinator had drawn up; the owner
-- dropped it: the coordinator sends a DRAFT straight to SENT, and who sent it is recorded in a column of its own, since
-- approved_by no longer exists on new rows. The producer's acceptance takes an optional note (e.g. who confirmed it,
-- and over which channel), written by the acceptance and kept as written.
--
-- What this does, in this order:
--   1. adds sent_by (who sent it; the approver for the orders sent so far, so they keep a sender) and acceptance_note;
--   2. moves the production orders still waiting for approval back to DRAFT, under the edge PENDING_APPROVAL -> DRAFT
--      that still exists here, with the returned_reason ck_production_order_returned asks for and a status-history row
--      by no one;
--   3. the transitions: DRAFT -> SENT in; DRAFT -> PENDING_APPROVAL and every edge out of PENDING_APPROVAL out, so the
--      status is historical only (the constant stays, as removed statuses do);
--   4. ck_production_order_approval: approved_* both set or both null, never the drafter, and only on an order that
--      was submitted - nothing about sending. Nothing submits any more, so the last clause is what refuses an
--      approval written onto a production order drawn up after 2026-11-20;
--   5. ck_production_order_sent: sent_at and sent_by together; null in DRAFT / PENDING_APPROVAL, set in every later
--      status but CANCELLED, which may be either (cancelled as a draft, or after sending);
--   6. ck_production_order_acceptance_note: non-blank, and only from ACCEPTED on (CANCELLED from SENT has none, from
--      ACCEPTED may have one);
--   7. rewrites ordering.check_production_order() from its V20261207000100 body: the sending (sent_by, sent_at) is
--      written once, the historical approval (approved_by, approved_at, submitted_at) is kept as written, and so is
--      the acceptance note once set. Postgres does not track columns used in a function body.
--
-- Left unchanged: ordering.check_production_order_slip_sent() reads only p.sent_at IS NOT NULL, which still means
-- "sent". The permission production-orders:APPROVE is not touched here.
-- =============================================================================

-- 1. The columns --------------------------------------------------------------------------------------------------------
ALTER TABLE ordering.production_order ADD COLUMN sent_by uuid;
ALTER TABLE ordering.production_order ADD COLUMN acceptance_note varchar(500);
ALTER TABLE ordering.production_order
    ADD CONSTRAINT fk_production_order_sent_by FOREIGN KEY (sent_by) REFERENCES identity.app_user(id);

UPDATE ordering.production_order SET sent_by = approved_by WHERE sent_at IS NOT NULL;

COMMENT ON COLUMN ordering.production_order.sent_by IS 'Who sent it to the producer (17 §4.3 step 7); set exactly when sent_at is. For the orders sent before 2026-11-20, the Sales Manager who approved it.';
COMMENT ON COLUMN ordering.production_order.acceptance_note IS 'The producer''s acceptance as the coordinator noted it (optional, 17 §4.3 step 8), from ACCEPTED on; written once.';
COMMENT ON TABLE ordering.production_order IS 'What a producer makes for one delivery batch of a confirmed order of a contract (kltn-docs 17 §4.3, §5.3): drawn up and sent by the Production Coordinator, received into the order''s shipping warehouse. approved_*, submitted_at and PENDING_APPROVAL are history from before 2026-11-20.';

-- 2. The production orders waiting for approval go back to DRAFT ---------------------------------------------------------
WITH returned AS (
    UPDATE ordering.production_order
       SET status = 'DRAFT', returned_reason = 'Approval step removed 2026-11-20'
     WHERE status = 'PENDING_APPROVAL'
    RETURNING id
)
INSERT INTO ordering.production_order_status_history (id, production_order_id, from_status, to_status, reason,
                                                      changed_by, changed_at)
SELECT gen_random_uuid(), r.id, 'PENDING_APPROVAL', 'DRAFT', 'Approval step removed 2026-11-20', NULL, NOW()
  FROM returned r;

-- 3. The transitions ----------------------------------------------------------------------------------------------------
INSERT INTO ordering.production_order_status_transition (from_status, to_status) VALUES ('DRAFT', 'SENT');
DELETE FROM ordering.production_order_status_transition
 WHERE (from_status = 'DRAFT' AND to_status = 'PENDING_APPROVAL') OR from_status = 'PENDING_APPROVAL';

-- 4. An approval, where there is one, is still by someone else, of an order once submitted -----------------------------
ALTER TABLE ordering.production_order DROP CONSTRAINT ck_production_order_approval;
ALTER TABLE ordering.production_order ADD CONSTRAINT ck_production_order_approval
    CHECK ((approved_by IS NULL) = (approved_at IS NULL) AND (approved_by IS NULL OR approved_by <> drawn_up_by)
           AND (approved_at IS NULL OR submitted_at IS NOT NULL));

-- 5. Sent: when and by whom, together --------------------------------------------------------------------------------
ALTER TABLE ordering.production_order DROP CONSTRAINT ck_production_order_sent;
ALTER TABLE ordering.production_order ADD CONSTRAINT ck_production_order_sent
    CHECK ((sent_at IS NULL) = (sent_by IS NULL)
           AND ((status IN ('DRAFT', 'PENDING_APPROVAL')) = (sent_at IS NULL) OR status = 'CANCELLED'));

-- 6. The acceptance note ------------------------------------------------------------------------------------------------
ALTER TABLE ordering.production_order ADD CONSTRAINT ck_production_order_acceptance_note
    CHECK (acceptance_note IS NULL
           OR (btrim(acceptance_note) <> '' AND status NOT IN ('DRAFT', 'PENDING_APPROVAL', 'SENT')));

-- 7. The trigger function, as in V20261207000100 with the sending and the acceptance note written once ----------------
CREATE OR REPLACE FUNCTION ordering.check_production_order() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    sales_order RECORD;
    batch_order UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'production order %: production orders are kept', OLD.code USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'production order %: drawn up as a draft (17 §5.3)', NEW.code USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.code IS DISTINCT FROM OLD.code OR NEW.sales_order_id IS DISTINCT FROM OLD.sales_order_id
            OR NEW.delivery_batch_id IS DISTINCT FROM OLD.delivery_batch_id
            OR NEW.customer_id IS DISTINCT FROM OLD.customer_id OR NEW.drawn_up_by IS DISTINCT FROM OLD.drawn_up_by THEN
            RAISE EXCEPTION 'production order %: what it is for does not change', OLD.code
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.status <> OLD.status AND NOT EXISTS (SELECT 1 FROM ordering.production_order_status_transition t
                                                     WHERE t.from_status = OLD.status AND t.to_status = NEW.status) THEN
            RAISE EXCEPTION 'production order %: % -> % is not a transition (17 §5.3)', OLD.code, OLD.status,
                NEW.status USING ERRCODE = 'check_violation';
        END IF;
        -- What the coordinator edits is settled once it is sent (B33; the warehouse moves with the order's
        -- shipping warehouse while it is not yet sent).
        IF OLD.status NOT IN ('DRAFT') AND (NEW.producer_id IS DISTINCT FROM OLD.producer_id
            OR NEW.need_by_date IS DISTINCT FROM OLD.need_by_date
            OR NEW.sample_approval_required IS DISTINCT FROM OLD.sample_approval_required
            OR NEW.sample_waiver_reason IS DISTINCT FROM OLD.sample_waiver_reason
            OR NEW.note IS DISTINCT FROM OLD.note) THEN
            RAISE EXCEPTION 'production order %: a % production order is not edited', OLD.code, OLD.status
                USING ERRCODE = 'check_violation';
        END IF;
        -- Who sent it, and when, is written once (BR-23).
        IF OLD.sent_at IS NOT NULL AND (NEW.sent_by IS DISTINCT FROM OLD.sent_by
            OR NEW.sent_at IS DISTINCT FROM OLD.sent_at) THEN
            RAISE EXCEPTION 'production order %: its sending is kept as written', OLD.code
                USING ERRCODE = 'check_violation';
        END IF;
        -- An approval from before 2026-11-20 is history, kept as written.
        IF OLD.approved_at IS NOT NULL AND (NEW.approved_by IS DISTINCT FROM OLD.approved_by
            OR NEW.approved_at IS DISTINCT FROM OLD.approved_at
            OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at) THEN
            RAISE EXCEPTION 'production order %: its approval is kept as written', OLD.code
                USING ERRCODE = 'check_violation';
        END IF;
        -- The producer's acceptance, as noted, is written once.
        IF OLD.acceptance_note IS NOT NULL AND NEW.acceptance_note IS DISTINCT FROM OLD.acceptance_note THEN
            RAISE EXCEPTION 'production order %: its acceptance note is kept as written', OLD.code
                USING ERRCODE = 'check_violation';
        END IF;
        IF OLD.status NOT IN ('DRAFT', 'PENDING_APPROVAL')
            AND NEW.receiving_warehouse_id IS DISTINCT FROM OLD.receiving_warehouse_id THEN
            RAISE EXCEPTION 'production order %: sent, it is received where it was sent to', OLD.code
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    SELECT o.customer_id, o.shipping_warehouse_id, o.contract_signed_at INTO sales_order
      FROM ordering.customer_order o WHERE o.id = NEW.sales_order_id;
    SELECT c.order_id INTO batch_order
      FROM ordering.delivery_batch b JOIN ordering.sales_contract c ON c.id = b.contract_id
     WHERE b.id = NEW.delivery_batch_id;
    IF sales_order.contract_signed_at IS NULL OR batch_order IS DISTINCT FROM NEW.sales_order_id THEN
        RAISE EXCEPTION 'production order %: for a delivery batch of a signed order''s contract (17 BR-05)', NEW.code
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.customer_id IS DISTINCT FROM sales_order.customer_id THEN
        RAISE EXCEPTION 'production order %: for its order''s customer', NEW.code USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status IN ('DRAFT', 'PENDING_APPROVAL')
        AND NEW.receiving_warehouse_id IS DISTINCT FROM sales_order.shipping_warehouse_id THEN
        RAISE EXCEPTION 'production order %: received at its order''s shipping warehouse (17 BR-22)', NEW.code
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
