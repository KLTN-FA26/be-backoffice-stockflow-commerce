-- =============================================================================
-- WF-05 confirmation audit CF-5 (RAD-3 follow-up): a custody note is ended "taken by return X" only once X received
-- its goods, and X is chosen only while it can still receive them.
--
-- Why. Sales could name a supplementary return that was REQUESTED, APPROVED - or already RESOLVED - and the warehouse
-- manager end the note at once, with any record text; the outcome is final. X then expired, was rejected, or (RESOLVED)
-- could take no receipt at all: the goods stayed in the warehouse with a note saying they left on a return, the
-- stranded custody RAD-3 closed. "Takes the goods" was judged by X's status, never by goods received on it.
--
-- What check_return_custody_outcome now says, as ReturnRequest.chooseForCustody / endCustody and
-- ReturnCustodyNotes.end say it:
--   1. choosing (insert, or a change of taker): X is the same customer's and can still receive goods - asked for
--      (REQUESTED) or approved and taking parcels (AWAITING_RETURN, RECEIVED, SETTLEMENT_PENDING), not RESOLVED,
--      INSPECTED, REJECTED, CANCELLED or EXPIRED;
--   2. ending a TAKEN_BY_RETURN note (ended_at set): X is not REJECTED, CANCELLED or EXPIRED, and the goods came back
--      on it - the carton the note names (custody_lpn) last came back on X (inventory.stock_package.return_request_id),
--      or, a note naming only a SKU, X's lines of that SKU received at least the note's units.
-- Rows already ended stay as they are: an ended row is never updated again (the first rule of the trigger).
-- =============================================================================

CREATE OR REPLACE FUNCTION ordering.check_return_custody_outcome() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    taker RECORD;
    note  RECORD;
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
        IF NOT taker.same_customer
           OR taker.status NOT IN ('REQUESTED', 'AWAITING_RETURN', 'RECEIVED', 'SETTLEMENT_PENDING') THEN
            RAISE EXCEPTION 'custody note %: return % is another customer''s or can receive no goods (%)',
                NEW.evidence_id, NEW.taken_by_return_id, taker.status USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF NEW.choice = 'TAKEN_BY_RETURN' AND NEW.ended_at IS NOT NULL
       AND (TG_OP = 'INSERT' OR OLD.ended_at IS NULL) THEN
        SELECT t.status INTO taker FROM ordering.return_request t WHERE t.id = NEW.taken_by_return_id;
        SELECT e.custody_sku, e.custody_lpn, e.custody_quantity INTO note
          FROM ordering.return_request_evidence e WHERE e.id = NEW.evidence_id;
        IF taker.status IN ('REJECTED', 'CANCELLED', 'EXPIRED')
           OR NOT (CASE WHEN note.custody_lpn IS NOT NULL
                       THEN EXISTS (SELECT 1 FROM inventory.stock_package p
                                     WHERE p.lpn = upper(btrim(note.custody_lpn))
                                       AND p.return_request_id = NEW.taken_by_return_id)
                       ELSE (SELECT COALESCE(SUM(l.received_quantity), 0) FROM ordering.return_request_line l
                              WHERE l.return_request_id = NEW.taken_by_return_id AND l.sku = note.custody_sku)
                            >= note.custody_quantity
                  END) THEN
            RAISE EXCEPTION 'custody note %: its goods have not come back on return % (%), so they did not leave on it '
                '(confirmation audit CF-5)', NEW.evidence_id, NEW.taken_by_return_id, taker.status
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
