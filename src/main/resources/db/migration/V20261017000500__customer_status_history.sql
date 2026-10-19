-- =============================================================================
-- Customer status history (SCRUM-429 follow-up, decision of 2026-10-12).
--
-- Customers include household businesses and small shops: a tax code is optional (customer.tax_code
-- is already nullable; the application no longer requires it at creation). Nothing in the schema
-- changes for that.
--
-- customer.customer_status_history  every status a customer entered through the application: created
--   by Sales (from NULL), activated by its contact, blocked, closed, reopened. A manager (E-commerce
--   Admin, kltn-docs 18 §2 "Quản trị sàn") may reopen a closed (INACTIVE) customer, but only with a
--   reason - the CHECK below states it a second time. Rows are never updated. Customers that existed
--   before this migration simply have no history yet; nothing is invented for them.
-- =============================================================================

CREATE TABLE customer.customer_status_history
(
    id               UUID          NOT NULL,
    customer_id      UUID          NOT NULL,
    from_status      VARCHAR(32),
    to_status        VARCHAR(32)   NOT NULL,
    reason           VARCHAR(1000),
    -- The staff account that made the change, or the contact's own account for an activation.
    changed_by       UUID,
    changed_at       TIMESTAMPTZ   NOT NULL,

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_customer_status_history PRIMARY KEY (id),
    CONSTRAINT fk_customer_status_history_customer FOREIGN KEY (customer_id)
        REFERENCES customer.customer (id) ON DELETE CASCADE,
    CONSTRAINT fk_customer_status_history_changed_by FOREIGN KEY (changed_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_customer_status_history_from CHECK (from_status IS NULL
        OR from_status IN ('PENDING_ACTIVATION', 'ACTIVE', 'INACTIVE', 'BLOCKED')),
    CONSTRAINT ck_customer_status_history_to CHECK (to_status IN ('PENDING_ACTIVATION', 'ACTIVE', 'INACTIVE', 'BLOCKED')),
    CONSTRAINT ck_customer_status_history_change CHECK (from_status IS DISTINCT FROM to_status),
    -- Reopening a closed customer names why.
    CONSTRAINT ck_customer_status_history_reopen_reason CHECK (from_status IS DISTINCT FROM 'INACTIVE'
        OR (reason IS NOT NULL AND btrim(reason) <> ''))
);

CREATE INDEX ix_customer_status_history_customer ON customer.customer_status_history (customer_id, changed_at);

COMMENT ON TABLE customer.customer_status_history IS
    'Status changes of a customer made through the application, with who and why. Append-only.';
