-- =============================================================================
-- What the public lead form does with a repeat (review findings S4 and P5 of the sales-owner work, 2026-11-28).
-- Module: customer.
--
-- customer.lead_history.kind  P5: the form makes one lead per tax code or e-mail per 24 hours (plan §10.7). The first
--     submission used to win and every later one was dropped, so whoever sent a business's tax code first - the
--     business, or someone posing as it - decided which contact Sales would call. A later submission now becomes an
--     entry of the open lead's history: kind RESUBMITTED, the lead's status and Sales unchanged on both sides, what the
--     later form said in the note, no actor (the public form). Every other row stays a CHANGE of status or Sales, as
--     ck_lead_history_change said.
-- customer.lead_form_existing_customer  S4: the form naming an existing customer's tax code makes no lead and tells
--     that customer's salesperson. Nothing limited how often: each submission was a new notice. One row per customer:
--     when its salesperson was last told; the next notice waits 24 hours, the window leads have.
-- =============================================================================

ALTER TABLE customer.lead_history
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'CHANGE';

COMMENT ON COLUMN customer.lead_history.kind IS
    'CHANGE: a change of the lead''s status or Sales. RESUBMITTED: the same business sent the form again within the '
    'duplicate window; nothing changed, the later form is in the note.';

ALTER TABLE customer.lead_history
    ADD CONSTRAINT ck_lead_history_kind CHECK (kind IN ('CHANGE', 'RESUBMITTED')),
    DROP CONSTRAINT ck_lead_history_change,
    -- A CHANGE records a change of status or of Sales; a RESUBMISSION changes neither, says what was sent, and has no
    -- actor (it comes from the public form).
    ADD CONSTRAINT ck_lead_history_change CHECK (
        (kind <> 'RESUBMITTED'
            AND (from_status IS DISTINCT FROM to_status OR from_user_id IS DISTINCT FROM to_user_id))
        OR (kind = 'RESUBMITTED' AND from_status = to_status AND from_user_id IS NOT DISTINCT FROM to_user_id
            AND note IS NOT NULL AND btrim(note) <> '' AND changed_by IS NULL));

CREATE INDEX ix_lead_history_resubmitted ON customer.lead_history (lead_id, changed_at) WHERE kind = 'RESUBMITTED';

CREATE TABLE customer.lead_form_existing_customer (
    customer_id UUID        NOT NULL,
    last_at     TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_lead_form_existing_customer PRIMARY KEY (customer_id),
    CONSTRAINT fk_lead_form_existing_customer_customer FOREIGN KEY (customer_id)
        REFERENCES customer.customer (id) ON DELETE CASCADE
);

COMMENT ON TABLE customer.lead_form_existing_customer IS
    'When the public lead form, naming a customer''s tax code, last made its salesperson be told: at most once per 24 '
    'hours (finding S4).';
