-- =============================================================================
-- Customer commercial terms and the credit check at order placement (SCRUM-427, SCRUM-193).
-- Docs: kltn-docs 15 §4.3 and BR-01, BR-03; 18 §2-3 and BR-02, BR-06; 14 BR-06; 17 §5.
--
-- customer.credit_profile — the terms of one customer, set by whoever approves credit (18 BR-02):
--   the payment terms allowed, the default deposit percentage, the credit limit and the days to pay.
--   No row means the customer may only prepay; nothing is created for existing customers.
--
-- ordering.customer_order.credit_term_days — a credit order keeps the days to pay it was sold on.
-- ordering.order_credit_check — every credit decision on an order: the limit, the exposure and the
--   order amount when it was checked, and the outcome; an approval or a refusal over the limit names
--   who decided and why.
--
-- A credit order skips PENDING_PAYMENT (17 §5): within the limit it is CONFIRMED, over it ON_HOLD
-- (hold reason CREDIT) until approved or refused.
-- =============================================================================

CREATE TABLE customer.credit_profile
(
    id                   UUID          NOT NULL,
    customer_id          UUID          NOT NULL,
    allow_prepaid        BOOLEAN       NOT NULL DEFAULT TRUE,
    allow_deposit        BOOLEAN       NOT NULL DEFAULT FALSE,
    allow_credit         BOOLEAN       NOT NULL DEFAULT FALSE,
    default_payment_term VARCHAR(16)   NOT NULL DEFAULT 'PREPAID',
    deposit_percent      NUMERIC(5, 2),
    credit_limit         NUMERIC(19, 4),
    credit_term_days     INTEGER,
    currency             VARCHAR(3)    NOT NULL DEFAULT 'VND',
    approved_by          UUID          NOT NULL,
    approved_at          TIMESTAMPTZ   NOT NULL,
    note                 VARCHAR(1000),

    version              BIGINT        NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by           VARCHAR(100),
    last_modified_at     TIMESTAMPTZ,
    last_modified_by     VARCHAR(100),

    CONSTRAINT pk_credit_profile PRIMARY KEY (id),
    CONSTRAINT uk_credit_profile_customer UNIQUE (customer_id),
    CONSTRAINT fk_credit_profile_customer FOREIGN KEY (customer_id)
        REFERENCES customer.customer (id) ON DELETE CASCADE,
    CONSTRAINT fk_credit_profile_approved_by FOREIGN KEY (approved_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_credit_profile_some_term CHECK (allow_prepaid OR allow_deposit OR allow_credit),
    CONSTRAINT ck_credit_profile_default_term CHECK (
        (default_payment_term = 'PREPAID' AND allow_prepaid)
     OR (default_payment_term = 'DEPOSIT' AND allow_deposit)
     OR (default_payment_term = 'CREDIT' AND allow_credit)),
    -- A deposit is a share of the order, never all of it or nothing.
    CONSTRAINT ck_credit_profile_deposit CHECK ((allow_deposit) = (deposit_percent IS NOT NULL)
        AND (deposit_percent IS NULL OR (deposit_percent > 0 AND deposit_percent < 100))),
    CONSTRAINT ck_credit_profile_credit CHECK ((allow_credit) = (credit_limit IS NOT NULL AND credit_term_days IS NOT NULL)
        AND (credit_limit IS NULL OR credit_limit >= 0)
        AND (credit_term_days IS NULL OR credit_term_days BETWEEN 0 AND 365))
);

ALTER TABLE ordering.customer_order
    ADD COLUMN credit_term_days INTEGER,
    ADD CONSTRAINT ck_order_credit_term_days CHECK ((payment_term = 'CREDIT') = (credit_term_days IS NOT NULL)
        AND (credit_term_days IS NULL OR credit_term_days BETWEEN 0 AND 365));

CREATE TABLE ordering.order_credit_check
(
    id            UUID           NOT NULL,
    order_id      UUID           NOT NULL,
    checked_at    TIMESTAMPTZ    NOT NULL,
    credit_limit  NUMERIC(19, 4) NOT NULL,
    exposure      NUMERIC(19, 4) NOT NULL,
    order_amount  NUMERIC(19, 4) NOT NULL,
    outcome       VARCHAR(16)    NOT NULL,
    decided_by    UUID,
    decided_at    TIMESTAMPTZ,
    note          VARCHAR(1000),

    version          BIGINT      NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_order_credit_check PRIMARY KEY (id),
    CONSTRAINT fk_order_credit_check_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_credit_check_decided_by FOREIGN KEY (decided_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_order_credit_check_amounts CHECK (credit_limit >= 0 AND exposure >= 0 AND order_amount > 0),
    CONSTRAINT ck_order_credit_check_outcome CHECK (outcome IN ('WITHIN_LIMIT', 'OVER_LIMIT', 'APPROVED', 'REJECTED')),
    -- A person decides an order over the limit, and says why (kltn-docs 15 BR-03).
    CONSTRAINT ck_order_credit_check_decided CHECK (
        (outcome IN ('APPROVED', 'REJECTED') AND decided_by IS NOT NULL AND decided_at IS NOT NULL AND note IS NOT NULL)
     OR (outcome IN ('WITHIN_LIMIT', 'OVER_LIMIT') AND decided_by IS NULL AND decided_at IS NULL)),
    CONSTRAINT ck_order_credit_check_within CHECK (outcome <> 'WITHIN_LIMIT' OR exposure + order_amount <= credit_limit)
);

CREATE INDEX ix_order_credit_check_order ON ordering.order_credit_check (order_id, checked_at);

-- ---- permissions ---------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), r.resource || ':' || a.action, r.resource, a.action, 0, NOW(), 'flyway'
FROM (VALUES
          ('customer-credit-profiles', 'VIEW_PAGE,READ,APPROVE'),
          ('sales-order-credit-holds', 'VIEW_PAGE,READ,APPROVE')
     ) AS r(resource, actions)
CROSS JOIN LATERAL unnest(string_to_array(r.actions, ',')) AS a(action)
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          -- 18 BR-02: whoever approves credit sets the terms and decides orders over the limit.
          ('ACCOUNTANT',        'customer-credit-profiles', 'VIEW_PAGE,READ,APPROVE'),
          ('ACCOUNTANT',        'sales-order-credit-holds', 'VIEW_PAGE,READ,APPROVE'),
          -- Sales proposes terms and follows held orders; it does not approve them.
          ('SALES_STAFF',       'customer-credit-profiles', 'VIEW_PAGE,READ'),
          ('SALES_STAFF',       'sales-order-credit-holds', 'VIEW_PAGE,READ'),
          ('ORDER_COORDINATOR', 'customer-credit-profiles', 'READ'),
          ('ORDER_COORDINATOR', 'sales-order-credit-holds', 'VIEW_PAGE,READ')
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
WHERE code IN ('ACCOUNTANT', 'SALES_STAFF', 'ORDER_COORDINATOR', 'SYSTEM_ADMIN');
