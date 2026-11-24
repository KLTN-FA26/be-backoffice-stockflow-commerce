-- =============================================================================
-- Replacement production orders (SCRUM-790 / SCRUM-839; kltn-docs 17 §4.13 steps 52-54, BR-31; open-questions B45).
--
-- A carton of a production order's goods found faulty after it was received is written off as DAMAGED, the
-- write-off approved and posted (20 §4.8). The Production Coordinator then draws up a production order of the kind
-- REPLACEMENT for the sales order left short by it: it names the production order it replaces and the write-offs it
-- makes up, it is for the same sales order, and it makes no more cartons of a SKU than the write-offs it names wrote
-- off of that SKU (and never more than its delivery batch lacks - BR-05, held as before). Sent and received as any
-- other (BR-23); the goods come in under its own code, which names the production order it replaces.
--
--   1. production_order.kind (STANDARD | REPLACEMENT, every existing row STANDARD) and replaces_production_order_id,
--      present exactly for a REPLACEMENT, never itself; both written once (check_production_order). The production
--      order replaced is of the same sales order. A replacement of a replacement is allowed: its write-offs are of the
--      replacement's own lot.
--   2. production_order_write_off: the write-offs a REPLACEMENT makes up, one row each, written with the draft and
--      kept. Each is a posted DAMAGED write-off of a whole carton (one carton) of a lot the production order replaced
--      opened, of the SKU the row names; a write-off is made up by one live replacement at a time - a CANCELLED one
--      gives its write-offs back. The application checks all of it first, under the sales order's lock
--      (ProductionOrderService.drawUpReplacement); the trigger is the second statement of the rule, and reads
--      inventory's rows because the reference is to them (ADR-0007).
--   3. A deferred check: a REPLACEMENT names at least one write-off, and each of its lines makes no more cartons than
--      it names write-offs of the line's SKU - after every change of lines (a draft edited) or of write-offs.
-- =============================================================================

-- 1. The kind and the production order replaced ------------------------------------------------------------------
ALTER TABLE ordering.production_order
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'STANDARD',
    ADD COLUMN replaces_production_order_id UUID;

ALTER TABLE ordering.production_order
    ADD CONSTRAINT ck_production_order_kind CHECK (kind IN ('STANDARD', 'REPLACEMENT')),
    ADD CONSTRAINT ck_production_order_replaces CHECK (
        (kind = 'REPLACEMENT') = (replaces_production_order_id IS NOT NULL)
        AND replaces_production_order_id IS DISTINCT FROM id),
    ADD CONSTRAINT fk_production_order_replaces FOREIGN KEY (replaces_production_order_id)
        REFERENCES ordering.production_order (id);

CREATE INDEX ix_production_order_replaces ON ordering.production_order (replaces_production_order_id)
    WHERE replaces_production_order_id IS NOT NULL;

COMMENT ON COLUMN ordering.production_order.kind IS
    'STANDARD: for what a delivery batch lacks (17 BR-05). REPLACEMENT: for cartons of its lot written off as damaged '
    'after they were received (17 §4.13, BR-31).';
COMMENT ON COLUMN ordering.production_order.replaces_production_order_id IS
    'The production order whose damaged cartons a REPLACEMENT makes up; of the same sales order (17 BR-31).';

-- 2. The write-offs a replacement makes up ---------------------------------------------------------------------------
CREATE TABLE ordering.production_order_write_off (
    id                  UUID        NOT NULL,
    production_order_id UUID        NOT NULL,
    stock_adjustment_id UUID        NOT NULL,
    sku                 VARCHAR(64) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_production_order_write_off PRIMARY KEY (id),
    CONSTRAINT uk_production_order_write_off UNIQUE (production_order_id, stock_adjustment_id),
    CONSTRAINT fk_production_order_write_off_order FOREIGN KEY (production_order_id)
        REFERENCES ordering.production_order (id),
    CONSTRAINT fk_production_order_write_off_adjustment FOREIGN KEY (stock_adjustment_id)
        REFERENCES inventory.stock_adjustment (id)
);

CREATE INDEX ix_production_order_write_off_adjustment ON ordering.production_order_write_off (stock_adjustment_id);

COMMENT ON TABLE ordering.production_order_write_off IS
    'The posted DAMAGED write-offs (one whole carton each) a REPLACEMENT production order makes up (17 §4.13, BR-31). '
    'Written with the draft and kept; a write-off is made up by one live replacement at a time.';

CREATE FUNCTION ordering.check_production_order_write_off() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    po         RECORD;
    adjustment RECORD;
    taken_by   TEXT;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'production order write-off %: the write-offs a replacement makes up are kept as written',
            OLD.stock_adjustment_id USING ERRCODE = 'check_violation';
    END IF;
    SELECT p.code, p.kind, p.status, p.replaces_production_order_id INTO po
      FROM ordering.production_order p WHERE p.id = NEW.production_order_id;
    IF po.kind IS DISTINCT FROM 'REPLACEMENT' OR po.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'production order %: only a replacement names write-offs, while it is a draft (17 BR-31)',
            po.code USING ERRCODE = 'check_violation';
    END IF;
    SELECT a.reason_code::TEXT AS reason, a.status::TEXT AS status, a.quantity_delta, a.stock_package_id, a.sku,
           a.adjustment_number,
           EXISTS (SELECT 1 FROM inventory.stock_lot l
                    WHERE l.production_order_id = po.replaces_production_order_id AND l.sku = a.sku
                      AND l.lot_number = a.lot_number) AS of_replaced_lot
      INTO adjustment
      FROM inventory.stock_adjustment a WHERE a.id = NEW.stock_adjustment_id;
    IF adjustment.reason IS DISTINCT FROM 'DAMAGED' OR adjustment.status IS DISTINCT FROM 'POSTED'
        OR adjustment.quantity_delta >= 0 OR adjustment.stock_package_id IS NULL
        OR adjustment.sku IS DISTINCT FROM NEW.sku OR NOT adjustment.of_replaced_lot THEN
        RAISE EXCEPTION 'production order %: % is not a posted DAMAGED write-off of a carton of %''s lot (17 BR-31)',
            po.code, coalesce(adjustment.adjustment_number, NEW.stock_adjustment_id::TEXT), NEW.sku
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT p.code INTO taken_by
      FROM ordering.production_order_write_off w
      JOIN ordering.production_order p ON p.id = w.production_order_id
     WHERE w.stock_adjustment_id = NEW.stock_adjustment_id AND w.production_order_id <> NEW.production_order_id
       AND p.status <> 'CANCELLED'
     LIMIT 1;
    IF taken_by IS NOT NULL THEN
        RAISE EXCEPTION 'production order %: write-off % is already made up by %', po.code,
            adjustment.adjustment_number, taken_by USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_production_order_write_off BEFORE INSERT OR UPDATE OR DELETE
    ON ordering.production_order_write_off FOR EACH ROW EXECUTE FUNCTION ordering.check_production_order_write_off();

-- 3. What a replacement makes, against what it makes up -------------------------------------------------------------
CREATE FUNCTION ordering.check_replacement_cartons() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    the_order UUID;
    po        RECORD;
    over      TEXT;
BEGIN
    -- Separate statements: each table's row is read only by the branch for that table (as in
    -- check_production_order_has_lines).
    IF TG_TABLE_NAME = 'production_order' THEN
        the_order := NEW.id;
    ELSIF TG_OP = 'DELETE' THEN
        the_order := OLD.production_order_id;
    ELSE
        the_order := NEW.production_order_id;
    END IF;
    SELECT p.code, p.kind INTO po FROM ordering.production_order p WHERE p.id = the_order;
    IF po.kind IS DISTINCT FROM 'REPLACEMENT' THEN
        RETURN NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ordering.production_order_write_off w WHERE w.production_order_id = the_order) THEN
        RAISE EXCEPTION 'production order %: a replacement names the write-offs it makes up (17 BR-31)', po.code
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT string_agg(format('%s: %s carton(s), %s written off', l.sku, l.cartons,
                             (SELECT count(*) FROM ordering.production_order_write_off w
                               WHERE w.production_order_id = the_order AND w.sku = l.sku)), '; ')
      INTO over
      FROM ordering.production_order_line l
     WHERE l.production_order_id = the_order
       AND l.cartons > (SELECT count(*) FROM ordering.production_order_write_off w
                         WHERE w.production_order_id = the_order AND w.sku = l.sku);
    IF over IS NOT NULL THEN
        RAISE EXCEPTION 'production order %: a replacement makes no more cartons than were written off (17 BR-31): %',
            po.code, over USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER tg_production_order_replacement_cartons AFTER INSERT ON ordering.production_order
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ordering.check_replacement_cartons();
CREATE CONSTRAINT TRIGGER tg_production_order_line_replacement_cartons
    AFTER INSERT OR UPDATE ON ordering.production_order_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ordering.check_replacement_cartons();
CREATE CONSTRAINT TRIGGER tg_production_order_write_off_replacement_cartons
    AFTER INSERT ON ordering.production_order_write_off
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ordering.check_replacement_cartons();

-- 4. check_production_order: as in V20261213000100, with the kind and the production order replaced written once,
--    and the one replaced of the same sales order ----------------------------------------------------------------------
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
            OR NEW.customer_id IS DISTINCT FROM OLD.customer_id OR NEW.drawn_up_by IS DISTINCT FROM OLD.drawn_up_by
            OR NEW.kind IS DISTINCT FROM OLD.kind
            OR NEW.replaces_production_order_id IS DISTINCT FROM OLD.replaces_production_order_id THEN
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
    -- A replacement makes up the damaged cartons of a production order of the same sales order (17 BR-31).
    IF TG_OP = 'INSERT' AND NEW.replaces_production_order_id IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM ordering.production_order r
                         WHERE r.id = NEW.replaces_production_order_id AND r.sales_order_id = NEW.sales_order_id) THEN
        RAISE EXCEPTION 'production order %: it replaces a production order of the same sales order (17 BR-31)',
            NEW.code USING ERRCODE = 'check_violation';
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
