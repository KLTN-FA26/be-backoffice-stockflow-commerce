-- =============================================================================
-- E-invoices: one sender per invoice across instances (review WF05-einv, finding H2).
--
--   * einvoice_sending.holder / held_until - a lease on talking to the provider about an invoice: who holds it, and
--     until when. Taken before the provider is asked anything, renewed before a send, given up afterwards.
-- =============================================================================

-- Two senders of one transaction at once is how Viettel issues two invoices for it. The reconciliation job runs on one
-- instance at a time (ShedLock), but the accountant's "check again" and the send that follows "issue" run anywhere,
-- and the per-instance guard that kept them apart did not reach across instances. The lease does: whoever holds it is
-- the only one asking about, or sending, the invoice. It expires on its own, so an instance that dies holding it holds
-- up nobody for longer than the lease.
--
-- A lease can be taken before the invoice was ever sent, so the send columns become optional - together.
ALTER TABLE invoicing.einvoice_sending
    ADD COLUMN holder     UUID,
    ADD COLUMN held_until TIMESTAMPTZ,
    ALTER COLUMN transaction_id DROP NOT NULL,
    ALTER COLUMN last_sent_at DROP NOT NULL,
    ADD CONSTRAINT ck_einvoice_sending_sent CHECK ((transaction_id IS NULL) = (last_sent_at IS NULL)),
    ADD CONSTRAINT ck_einvoice_sending_held CHECK (holder IS NULL OR held_until IS NOT NULL);

COMMENT ON COLUMN invoicing.einvoice_sending.holder IS
    'Who is talking to the provider about the invoice right now; null when nobody is.';
COMMENT ON COLUMN invoicing.einvoice_sending.held_until IS
    'When the holder''s lease ends unless renewed; once given up, when it was given up.';

-- A row taken only for its lease records no send: nothing to check.
CREATE OR REPLACE FUNCTION invoicing.einvoice_sending_check() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    invoice invoicing.einvoice%ROWTYPE;
BEGIN
    IF NEW.last_sent_at IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT * INTO invoice FROM invoicing.einvoice WHERE id = NEW.einvoice_id;
    IF invoice.status IS DISTINCT FROM 'SUBMITTING' OR invoice.transaction_id IS DISTINCT FROM NEW.transaction_id THEN
        RAISE EXCEPTION 'E-invoice % is not at the provider under transaction %', NEW.einvoice_id, NEW.transaction_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.last_sent_at < invoice.submitted_at THEN
        RAISE EXCEPTION 'E-invoice % cannot have been sent before it was submitted', NEW.einvoice_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
