-- =============================================================================
-- Decisions of 2026-10-17 on customer records.
--
-- 1. customer.customer_change_history - every change of who the customer is on paper: its company name (FULL_NAME),
--    which the customer's own contact may change through the portal, and its tax code (TAX_CODE), which Sales may
--    correct. Value before, value after, who, when. Append-only, like customer_status_history.
-- 2. Lifting a block (BLOCKED -> ACTIVE) needs a reason, as reopening a closed customer does; the CHECK says it again.
--    customer_status_history exists only since V20261017000500 (this branch) and no code wrote such a row without a
--    reason before it reached a deployed database, so no row can fail it.
--
-- Staff read the history (GET /customers/{id}/change-history, customers:READ, Sales/e-commerce admin only); the
-- values are whole there, as on the staff detail. Nothing of it is logged.
-- =============================================================================

CREATE TABLE customer.customer_change_history
(
    id          UUID          NOT NULL,
    customer_id UUID          NOT NULL,
    field       VARCHAR(16)   NOT NULL,
    old_value   VARCHAR(200),
    new_value   VARCHAR(200),
    changed_by  UUID,
    changed_at  TIMESTAMPTZ   NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_customer_change_history PRIMARY KEY (id),
    CONSTRAINT fk_customer_change_history_customer FOREIGN KEY (customer_id)
        REFERENCES customer.customer (id) ON DELETE CASCADE,
    CONSTRAINT fk_customer_change_history_by FOREIGN KEY (changed_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_customer_change_history_field CHECK (field IN ('FULL_NAME', 'TAX_CODE')),
    CONSTRAINT ck_customer_change_history_change CHECK (old_value IS DISTINCT FROM new_value)
);

CREATE INDEX ix_customer_change_history_customer ON customer.customer_change_history (customer_id, changed_at);

COMMENT ON TABLE customer.customer_change_history IS
    'Every change of a customer''s company name and tax code: before, after, who, when (decision 2026-10-17).';

ALTER TABLE customer.customer_status_history
    ADD CONSTRAINT ck_customer_status_history_unblock_reason CHECK (from_status IS DISTINCT FROM 'BLOCKED'
        OR to_status <> 'ACTIVE' OR (reason IS NOT NULL AND btrim(reason) <> ''));
