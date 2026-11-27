-- =============================================================================
-- A production order carries no money (owner, 2026-11-19): unit_price and currency are dropped.
--
-- Why. The processing price was an optional field of the production order (kltn-docs 17 §4.3 step 6, "Tuỳ chọn") that
-- nothing read: there is no payable to producers, no money ledger, no production cost in any report (purchasing,
-- supplier invoices and subcontract settlements went with 4A-8 / 4A-9, the money ledger with 4B-10). The slip and the
-- producer's e-mail stopped printing it on 2026-11-19 (#378). One price for a production order of several SKUs did not
-- say what it was the price of either. What the business agrees with a producer is agreed outside the system.
--
-- What this does:
--   1. drops ordering.production_order.unit_price (with ck_production_order_price) and .currency;
--   2. rewrites ordering.check_production_order(), whose "not edited once submitted" list named unit_price: Postgres
--      does not track columns used in a function body, so the old body would fail at the first UPDATE.
--
-- Nothing is archived (QD-1): the prices recorded so far are demo data.
-- =============================================================================

-- 1. The columns --------------------------------------------------------------------------------------------------------
ALTER TABLE ordering.production_order DROP COLUMN unit_price;
ALTER TABLE ordering.production_order DROP COLUMN currency;

-- 2. The trigger function, as in V20261111000000 without unit_price ------------------------------------------------------
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
        -- What the coordinator edits is settled once it is submitted (B33; the warehouse moves with the order's
        -- shipping warehouse while it is not yet sent).
        IF OLD.status NOT IN ('DRAFT') AND (NEW.producer_id IS DISTINCT FROM OLD.producer_id
            OR NEW.need_by_date IS DISTINCT FROM OLD.need_by_date
            OR NEW.sample_approval_required IS DISTINCT FROM OLD.sample_approval_required
            OR NEW.sample_waiver_reason IS DISTINCT FROM OLD.sample_waiver_reason
            OR NEW.note IS DISTINCT FROM OLD.note) THEN
            RAISE EXCEPTION 'production order %: a % production order is not edited', OLD.code, OLD.status
                USING ERRCODE = 'check_violation';
        END IF;
        -- Who approved and sent it, and when, is written once (BR-23).
        IF OLD.approved_at IS NOT NULL AND (NEW.approved_by IS DISTINCT FROM OLD.approved_by
            OR NEW.approved_at IS DISTINCT FROM OLD.approved_at OR NEW.sent_at IS DISTINCT FROM OLD.sent_at
            OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at) THEN
            RAISE EXCEPTION 'production order %: its approval is kept as written', OLD.code
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
