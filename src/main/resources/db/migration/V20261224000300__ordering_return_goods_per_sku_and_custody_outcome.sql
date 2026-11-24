-- WF-05 audit round 3, returns (decision Q-4, Q-11; Luật Thương mại 2005 Điều 62; TT 91/2026/TT-BTC Điều 10 khoản 5c):
-- the database half of the rules ReturnRequest and CartonReturnService state for goods coming back.
--
--  1. RAD-1: whose a carton is after its return's refund is decided per SKU, not per return. "Không có thoả thuận trả
--     hàng thì hàng vẫn là của khách" is about goods: a return that refunded the 12oz line made the 16oz carton it
--     refused the company's, sold again with no refund, no adjustment invoice and no custody exit. Of a SKU, the units
--     refunded cover first what came back outside a sealed carton (opened cartons, loose units: never stocked, Q-18),
--     then the sealed cartons in label (LPN) order; a carton they reach is the company's whole (20 BR-14), the others
--     are the customer's, their stock-in reversed. check_return_request_goods_settled computes exactly that split,
--     as ReturnedCartonSettlement does.
--  2. RAD-1: the customer's choice for held cartons (SEND_BACK / DESTROY_ON_BEHALF) is recorded on a RESOLVED return
--     whenever one of its cartons is held for the customer - a return that refunded units too. The CHECK no longer asks
--     for "no unit refunded"; a deferred trigger asks for a held carton instead.
--  3. RAD-3 (Q-11 hỏi thêm): goods kept on a custody note end as the customer chose - handed back, destroyed on their
--     behalf (their written consent), or taken on a supplementary return - and the warehouse records the ending. The
--     note is append-only; its outcome is a row of its own, chosen (and chosen again) while the goods are kept, ended
--     once and then never changed.

-- ---------------------------------------------------------------------------------------------------------------- 1

CREATE OR REPLACE FUNCTION ordering.check_return_request_goods_settled() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    unsettled TEXT;
BEGIN
    -- Per SKU of the return: what came back, what was refunded; per carton of it that came back sealed on this return,
    -- in label order, the units of the cartons before it. A carton is the company's exactly when the refund, after
    -- what came back outside a sealed carton, still reaches it (ReturnedCartonSettlement.split).
    WITH lines AS (
        SELECT x.sku, SUM(x.refund_quantity) AS refunded, SUM(COALESCE(x.received_quantity, 0)) AS back
          FROM ordering.return_request_line x
         WHERE x.return_request_id = NEW.id
         GROUP BY x.sku
    ), cartons AS (
        SELECT p.lpn, p.return_ownership, i.sku,
               COALESCE(SUM(COALESCE(p.received_qty, 0)) OVER (PARTITION BY i.sku ORDER BY p.lpn
                            ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS before,
               SUM(COALESCE(p.received_qty, 0)) OVER (PARTITION BY i.sku) AS sealed
          FROM inventory.stock_package p
          JOIN inventory.inventory_items i ON i.id = p.inventory_item_id
         WHERE p.return_request_id = NEW.id AND p.return_ownership IS NOT NULL
    )
    SELECT string_agg(c.lpn || ' ' || c.return_ownership, ', ' ORDER BY c.lpn) INTO unsettled
      FROM cartons c
      LEFT JOIN lines l ON l.sku = c.sku
     WHERE c.return_ownership = 'PENDING'
        OR (c.return_ownership = 'COMPANY')
           <> (c.before < GREATEST(0, COALESCE(l.refunded, 0) - GREATEST(0, COALESCE(l.back, 0) - c.sealed)));
    IF unsettled IS NOT NULL THEN
        RAISE EXCEPTION 'return % is RESOLVED but its carton(s) % do not follow the refund of their SKU (WF-05 Q-4)',
            NEW.rma_number, unsettled USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

COMMENT ON FUNCTION ordering.check_return_request_goods_settled() IS 'WF-05 Q-4 (audit r3 RAD-1): once RESOLVED, no carton of the return waits, and each is the company''s exactly when the refund of its SKU reaches it - after what came back outside a sealed carton, in label order - and the customer''s otherwise.';

-- ---------------------------------------------------------------------------------------------------------------- 2

ALTER TABLE ordering.return_request DROP CONSTRAINT ck_return_request_custody_choice;
ALTER TABLE ordering.return_request
    ADD CONSTRAINT ck_return_request_custody_choice CHECK (
        custody_choice IS NULL
        OR (custody_choice IN ('SEND_BACK', 'DESTROY_ON_BEHALF') AND status = 'RESOLVED'
            AND custody_choice_by IS NOT NULL AND custody_choice_at IS NOT NULL
            AND (custody_choice <> 'DESTROY_ON_BEHALF' OR custody_choice_note IS NOT NULL)));

CREATE FUNCTION ordering.check_return_request_custody_held() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM inventory.stock_package p
                    WHERE p.return_request_id = NEW.id AND p.return_ownership LIKE 'CUSTOMER\_%') THEN
        RAISE EXCEPTION 'return %: no carton of it is held for the customer, so there is no custody choice to record '
            '(WF-05 Q-4)', NEW.rma_number USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_return_request_custody_held AFTER UPDATE OF custody_choice ON ordering.return_request
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.custody_choice IS NOT NULL AND NEW.custody_choice IS DISTINCT FROM OLD.custody_choice)
    EXECUTE FUNCTION ordering.check_return_request_custody_held();

COMMENT ON COLUMN ordering.return_request.custody_choice IS 'WF-05 Q-4: the cartons of the return its refund did not take back (per SKU, audit r3 RAD-1) stay the customer''s; Sales ask them and record SEND_BACK (at their cost) or DESTROY_ON_BEHALF (their written consent, named in custody_choice_note). The warehouse manager carries it out on the cartons.';

-- ---------------------------------------------------------------------------------------------------------------- 3

CREATE TABLE ordering.return_custody_outcome (
    evidence_id         UUID          NOT NULL,
    return_request_id   UUID          NOT NULL,
    choice              VARCHAR(24)   NOT NULL,
    choice_note         VARCHAR(500),
    taken_by_return_id  UUID,
    chosen_by           UUID          NOT NULL,
    chosen_at           TIMESTAMPTZ   NOT NULL,
    ended_at            TIMESTAMPTZ,
    ended_by            UUID,
    ended_note          VARCHAR(500),
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    last_modified_at    TIMESTAMPTZ,
    CONSTRAINT pk_return_custody_outcome PRIMARY KEY (evidence_id),
    CONSTRAINT fk_return_custody_outcome_evidence FOREIGN KEY (evidence_id)
        REFERENCES ordering.return_request_evidence (id) ON DELETE RESTRICT,
    CONSTRAINT fk_return_custody_outcome_return FOREIGN KEY (return_request_id)
        REFERENCES ordering.return_request (id) ON DELETE RESTRICT,
    CONSTRAINT fk_return_custody_outcome_taken_by FOREIGN KEY (taken_by_return_id)
        REFERENCES ordering.return_request (id) ON DELETE RESTRICT,
    CONSTRAINT fk_return_custody_outcome_chosen_by FOREIGN KEY (chosen_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_return_custody_outcome_ended_by FOREIGN KEY (ended_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_return_custody_outcome_choice CHECK (choice IN ('SEND_BACK', 'DESTROY_ON_BEHALF', 'TAKEN_BY_RETURN')),
    -- A supplementary return is named exactly when it takes the goods, and it is another return.
    CONSTRAINT ck_return_custody_outcome_taken_by CHECK (
        (choice = 'TAKEN_BY_RETURN') = (taken_by_return_id IS NOT NULL)
        AND taken_by_return_id IS DISTINCT FROM return_request_id),
    -- A destruction on the customer's behalf rests on their written consent, which the note names.
    CONSTRAINT ck_return_custody_outcome_consent CHECK (
        choice <> 'DESTROY_ON_BEHALF' OR (choice_note IS NOT NULL AND btrim(choice_note) <> '')),
    -- Ended by someone, at a time, with the record of it - or not ended at all.
    CONSTRAINT ck_return_custody_outcome_ended CHECK (
        (ended_at IS NULL) = (ended_by IS NULL) AND (ended_at IS NULL) = (ended_note IS NULL)
        AND (ended_note IS NULL OR btrim(ended_note) <> ''))
);
CREATE INDEX ix_return_custody_outcome_return ON ordering.return_custody_outcome (return_request_id);
CREATE INDEX ix_return_custody_outcome_open ON ordering.return_custody_outcome (chosen_at) WHERE ended_at IS NULL;

-- The outcome is of a custody note of that return; a supplementary return is the same customer's and takes goods when
-- it is chosen; once ended, the row never changes again.
CREATE FUNCTION ordering.check_return_custody_outcome() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    taker RECORD;
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.ended_at IS NOT NULL THEN
        RAISE EXCEPTION 'custody note %: its goods left (%), its outcome is final (WF-05 Q-11)', OLD.evidence_id,
            OLD.choice USING ERRCODE = 'check_violation';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM ordering.return_request_evidence e
                    WHERE e.id = NEW.evidence_id AND e.return_request_id = NEW.return_request_id
                      AND e.stage = 'CUSTODY') THEN
        RAISE EXCEPTION 'evidence % is not a custody note of return %', NEW.evidence_id, NEW.return_request_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.taken_by_return_id IS NOT NULL
       AND (TG_OP = 'INSERT' OR NEW.taken_by_return_id IS DISTINCT FROM OLD.taken_by_return_id) THEN
        SELECT t.customer_id = r.customer_id AS same_customer, t.status INTO taker
          FROM ordering.return_request t JOIN ordering.return_request r ON r.id = NEW.return_request_id
         WHERE t.id = NEW.taken_by_return_id;
        IF NOT taker.same_customer OR taker.status IN ('REJECTED', 'CANCELLED', 'EXPIRED') THEN
            RAISE EXCEPTION 'custody note %: return % is another customer''s or takes no goods (%)', NEW.evidence_id,
                NEW.taken_by_return_id, taker.status USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_return_custody_outcome BEFORE INSERT OR UPDATE ON ordering.return_custody_outcome
    FOR EACH ROW EXECUTE FUNCTION ordering.check_return_custody_outcome();

CREATE TRIGGER tg_return_custody_outcome_no_delete BEFORE DELETE ON ordering.return_custody_outcome
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('ordering.return_request', 'return_request_id');

COMMENT ON TABLE ordering.return_custody_outcome IS 'WF-05 Q-11 hỏi thêm (audit r3 RAD-3): how the goods of a custody note end - SEND_BACK, DESTROY_ON_BEHALF (choice_note names the written consent) or TAKEN_BY_RETURN (taken_by_return_id, a supplementary return of the same customer) - as Sales recorded the customer''s choice; ended_* is the warehouse carrying it out, with the record of it. Final once ended.';
