-- =============================================================================
-- Receivables of credit orders, customer transfers and their allocation (SCRUM-431).
-- Docs: kltn-docs 15 §4.3 steps 2-4, §5.3, BR-04, BR-06, BR-07, BR-09; 17 §4.3; 09 §4.3.
--
-- payment.receivable — what a customer owes for one delivered credit order: opened when the order is
--   delivered, due on the delivery day + the order's days to pay (15 §4.3 step 2). OPEN → PARTIALLY_PAID
--   → PAID, or OVERDUE once the due date has passed; an overdue receivable stays OVERDUE until paid in
--   full (plan G2-2, question 5).
-- payment.customer_transfer — money a customer sent to pay what they owe, recorded by the accountant
--   from the bank statement (15 BR-04), once per statement reference (BR-07). What is not allocated is
--   kept as the customer's credit and applied to their next receivable.
-- payment.transfer_allocation — which receivables a transfer paid, earliest due first unless the
--   customer named them (BR-09). Immutable (BR-06).
--
-- ordering.order_credit_check gains outcome OVERDUE: a credit order placed while the customer has an
-- overdue receivable waits for the credit approver (15 §4.3 step 4).
-- =============================================================================

CREATE TABLE payment.receivable
(
    id               UUID           NOT NULL,
    order_id         UUID           NOT NULL,
    customer_id      UUID           NOT NULL,
    kind             VARCHAR(16)    NOT NULL DEFAULT 'CREDIT_ORDER',
    amount           NUMERIC(19, 4) NOT NULL,
    paid_amount      NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency         VARCHAR(3)     NOT NULL,
    issued_at        TIMESTAMPTZ    NOT NULL,
    due_date         DATE           NOT NULL,
    status           VARCHAR(16)    NOT NULL DEFAULT 'OPEN',
    settled_at       TIMESTAMPTZ,

    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_receivable PRIMARY KEY (id),
    CONSTRAINT uk_receivable_order UNIQUE (order_id),
    CONSTRAINT fk_receivable_order FOREIGN KEY (order_id) REFERENCES ordering.customer_order (id) ON DELETE RESTRICT,
    CONSTRAINT fk_receivable_customer FOREIGN KEY (customer_id) REFERENCES customer.customer (id) ON DELETE RESTRICT,
    CONSTRAINT ck_receivable_kind CHECK (kind IN ('CREDIT_ORDER')),
    CONSTRAINT ck_receivable_amounts CHECK (amount > 0 AND paid_amount >= 0 AND paid_amount <= amount),
    CONSTRAINT ck_receivable_status CHECK (status IN ('OPEN', 'PARTIALLY_PAID', 'OVERDUE', 'PAID')),
    -- The status says what the money says (15 §5.3).
    CONSTRAINT ck_receivable_status_amounts CHECK (
        (status = 'OPEN' AND paid_amount = 0 AND settled_at IS NULL)
     OR (status = 'PARTIALLY_PAID' AND paid_amount > 0 AND paid_amount < amount AND settled_at IS NULL)
     OR (status = 'OVERDUE' AND paid_amount < amount AND settled_at IS NULL)
     OR (status = 'PAID' AND paid_amount = amount AND settled_at IS NOT NULL))
);

CREATE INDEX ix_receivable_customer ON payment.receivable (customer_id, status);
CREATE INDEX ix_receivable_due ON payment.receivable (due_date) WHERE status IN ('OPEN', 'PARTIALLY_PAID');

CREATE TABLE payment.customer_transfer
(
    id                 UUID           NOT NULL,
    customer_id        UUID           NOT NULL,
    reference          VARCHAR(100)   NOT NULL,
    amount             NUMERIC(19, 4) NOT NULL,
    unallocated_amount NUMERIC(19, 4) NOT NULL,
    currency           VARCHAR(3)     NOT NULL,
    received_on        DATE           NOT NULL,
    recorded_by        UUID           NOT NULL,
    recorded_at        TIMESTAMPTZ    NOT NULL,
    note               VARCHAR(1000),

    version            BIGINT         NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(100),
    last_modified_at   TIMESTAMPTZ,
    last_modified_by   VARCHAR(100),

    CONSTRAINT pk_customer_transfer PRIMARY KEY (id),
    -- 15 BR-07: one statement line, recorded once.
    CONSTRAINT uk_customer_transfer_reference UNIQUE (reference),
    CONSTRAINT fk_customer_transfer_customer FOREIGN KEY (customer_id)
        REFERENCES customer.customer (id) ON DELETE RESTRICT,
    CONSTRAINT fk_customer_transfer_recorded_by FOREIGN KEY (recorded_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_customer_transfer_amounts CHECK (amount > 0 AND unallocated_amount >= 0
        AND unallocated_amount <= amount),
    CONSTRAINT ck_customer_transfer_reference CHECK (length(trim(reference)) > 0)
);

CREATE INDEX ix_customer_transfer_customer ON payment.customer_transfer (customer_id, received_on);
CREATE INDEX ix_customer_transfer_credit ON payment.customer_transfer (customer_id)
    WHERE unallocated_amount > 0;

CREATE TABLE payment.transfer_allocation
(
    id            UUID           NOT NULL,
    transfer_id   UUID           NOT NULL,
    receivable_id UUID           NOT NULL,
    amount        NUMERIC(19, 4) NOT NULL,
    allocated_at  TIMESTAMPTZ    NOT NULL,
    allocated_by  UUID,

    CONSTRAINT pk_transfer_allocation PRIMARY KEY (id),
    CONSTRAINT uk_transfer_allocation UNIQUE (transfer_id, receivable_id),
    CONSTRAINT fk_transfer_allocation_transfer FOREIGN KEY (transfer_id)
        REFERENCES payment.customer_transfer (id) ON DELETE RESTRICT,
    CONSTRAINT fk_transfer_allocation_receivable FOREIGN KEY (receivable_id)
        REFERENCES payment.receivable (id) ON DELETE RESTRICT,
    CONSTRAINT fk_transfer_allocation_by FOREIGN KEY (allocated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_transfer_allocation_amount CHECK (amount > 0)
);

CREATE INDEX ix_transfer_allocation_receivable ON payment.transfer_allocation (receivable_id);

-- 15 BR-06: money movements are never edited or deleted, only added to.
CREATE TRIGGER tg_transfer_allocation_append_only
    BEFORE UPDATE OR DELETE ON payment.transfer_allocation
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('payment.customer_transfer', 'transfer_id');

-- ---- credit at the order: an overdue customer waits for the approver ------------------
ALTER TABLE ordering.order_credit_check DROP CONSTRAINT ck_order_credit_check_outcome;
ALTER TABLE ordering.order_credit_check ADD CONSTRAINT ck_order_credit_check_outcome
    CHECK (outcome IN ('WITHIN_LIMIT', 'OVER_LIMIT', 'OVERDUE', 'APPROVED', 'REJECTED'));
ALTER TABLE ordering.order_credit_check DROP CONSTRAINT ck_order_credit_check_decided;
ALTER TABLE ordering.order_credit_check ADD CONSTRAINT ck_order_credit_check_decided CHECK (
        (outcome IN ('APPROVED', 'REJECTED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND note IS NOT NULL)
     OR (outcome IN ('WITHIN_LIMIT', 'OVER_LIMIT', 'OVERDUE') AND decided_by IS NULL AND decided_at IS NULL));

-- ---- permissions ---------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), r.resource || ':' || a.action, r.resource, a.action, 0, NOW(), 'flyway'
FROM (VALUES
          ('payment-receivables',        'VIEW_PAGE,READ,EXPORT'),
          ('payment-customer-transfers', 'VIEW_PAGE,READ,CREATE')
     ) AS r(resource, actions)
CROSS JOIN LATERAL unnest(string_to_array(r.actions, ',')) AS a(action)
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          -- 15 §2: the accountant matches transfers to the statement and follows receivables.
          ('ACCOUNTANT',        'payment-receivables',        'VIEW_PAGE,READ,EXPORT'),
          ('ACCOUNTANT',        'payment-customer-transfers', 'VIEW_PAGE,READ,CREATE'),
          -- Sales reminds customers of what they owe (16); the coordinator sees it before releasing.
          ('SALES_STAFF',       'payment-receivables',        'VIEW_PAGE,READ'),
          ('ORDER_COORDINATOR', 'payment-receivables',        'READ'),
          -- 18 §2: a customer sees what they owe (scope OWN on the endpoint).
          ('CUSTOMER',          'payment-receivables',        'READ')
     ) AS g(role_code, resource, actions)
JOIN identity.app_role role ON role.code = g.role_code
JOIN identity.permission permission
  ON permission.resource = g.resource
 AND permission.action = ANY (string_to_array(g.actions, ','))
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
CROSS JOIN identity.permission permission
WHERE role.code = 'SYSTEM_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('ACCOUNTANT', 'SALES_STAFF', 'ORDER_COORDINATOR', 'CUSTOMER', 'SYSTEM_ADMIN');
