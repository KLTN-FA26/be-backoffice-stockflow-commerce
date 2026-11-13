-- =============================================================================
-- E-invoices: when an invoice was last sent to the provider (review WF05-einv, finding H1).
--
--   * invoicing.einvoice_sending - per invoice, the transaction id it was last sent under and when. One row per
--     invoice, overwritten by every send.
-- =============================================================================

-- A provider may still be working on a submission that a look-up does not show yet, so a transaction it says it does
-- not know is sent again only once a quiet period has passed. That period runs from the LAST time the transaction was
-- sent, not from einvoice.submitted_at (the first): measured from the first, every reconciliation after the first
-- quiet period sent the invoice again, each racing the one before.
--
-- Not a column of invoicing.einvoice: a send is not a change of the invoice. Written there, it would advance the
-- invoice's version under the accountant's feet (whose "send as new" names the version they read), and it would
-- be one more column for einvoice_guard to reason about.
--
-- Written in its own short transaction BEFORE the provider is called, with the invoice locked and SUBMITTING under the
-- transaction: no row means nothing was ever sent under the invoice's current transaction id.
CREATE TABLE invoicing.einvoice_sending
(
    einvoice_id    UUID        NOT NULL,
    transaction_id VARCHAR(64) NOT NULL,
    last_sent_at   TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_einvoice_sending PRIMARY KEY (einvoice_id),
    CONSTRAINT fk_einvoice_sending_einvoice FOREIGN KEY (einvoice_id) REFERENCES invoicing.einvoice (id),
    CONSTRAINT ck_einvoice_sending_transaction CHECK (transaction_id <> '')
);

COMMENT ON TABLE invoicing.einvoice_sending IS
    'When an invoice was last sent to the provider, and under which transaction id. The quiet period before a '
        'transaction the provider does not know is sent again runs from last_sent_at.';

-- A send is recorded only for an invoice at the provider under that very transaction, and never before the invoice
-- went there (EInvoiceIssuanceSteps.aboutToSend says the same, under the invoice's lock).
CREATE OR REPLACE FUNCTION invoicing.einvoice_sending_check() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    invoice invoicing.einvoice%ROWTYPE;
BEGIN
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

CREATE TRIGGER tg_einvoice_sending_check
    BEFORE INSERT OR UPDATE OF transaction_id, last_sent_at ON invoicing.einvoice_sending
    FOR EACH ROW
EXECUTE FUNCTION invoicing.einvoice_sending_check();

-- An invoice already at the provider is taken to have been sent when it was submitted, which is what its quiet period
-- was measured from until now: nothing that was waiting is sent sooner because of this migration.
INSERT INTO invoicing.einvoice_sending (einvoice_id, transaction_id, last_sent_at)
SELECT id, transaction_id, submitted_at
  FROM invoicing.einvoice
 WHERE status = 'SUBMITTING';
