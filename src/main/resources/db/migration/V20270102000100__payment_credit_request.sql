-- =============================================================================
-- A request for a credit limit, decided by the accountant (owner decision QĐ-A, 2026-11-26; kltn-docs 15 §4.3, 18 §4
-- step 5, BR-03).
--
-- Until now a customer's credit was set only by whoever approves credit, on the customer's credit profile, with nothing
-- before it: no way for the customer to ask, nothing to say Sales were consulted. Now a customer asks from the portal
-- (SUBMITTED, waiting for Sales' opinion), or Sales ask for them with their opinion (PENDING_APPROVAL); Sales give
-- their opinion on what a customer asked (PENDING_APPROVAL); the accountant approves - the limit and the days to pay,
-- the limit never above what was asked (owner decision 2026-11-26, plan §5 question 3) - or rejects with a reason;
-- whoever filed it may withdraw it while it is open. Approving writes the customer's credit profile in the same
-- transaction (customer.credit_profile, through CustomerService.saveCreditTerms): this table is how the credit was asked
-- for and why it was granted, the profile what it is now. The credit profile form stays, for the accountant to change
-- credit with no request (a customer paying late).
--
-- One request is open per customer at a time (uk_credit_request_open). Whoever decides is neither who filed it nor who
-- gave Sales' opinion (ck_credit_request_four_eyes); a withdrawal is no decision and has no four eyes. A withdrawal
-- records who withdrew and when in decided_by / decided_at, as a quote's withdrawn price review does.
--
-- Files kept with a request (the business registration, financial statements) are payment.credit_request_file, in
-- the documents category of the common storage.
--
-- customer-credit-requests: CREATE the customer (their own, from the portal) and Sales; UPDATE Sales (the opinion);
-- APPROVE the accountant; READ all of them; VIEW_PAGE the back office. Every role that gained a grant advances
-- app_role.version in the same transaction (ADR-0008, CLAUDE.md §5).
-- =============================================================================

CREATE TABLE payment.credit_request (
    id uuid NOT NULL,
    customer_id uuid NOT NULL,
    source character varying(16) NOT NULL,
    status character varying(20) NOT NULL,
    requested_limit numeric(19,4) NOT NULL,
    requested_term_days integer NOT NULL,
    currency character varying(3) DEFAULT 'VND' NOT NULL,
    reason character varying(1000),
    submitted_by uuid NOT NULL,
    submitted_at timestamp with time zone NOT NULL,
    sales_note character varying(1000),
    sales_recommended_limit numeric(19,4),
    recommended_by uuid,
    recommended_at timestamp with time zone,
    approved_limit numeric(19,4),
    approved_term_days integer,
    decided_by uuid,
    decided_at timestamp with time zone,
    decision_note character varying(1000),
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(100),
    last_modified_at timestamp with time zone,
    last_modified_by character varying(100),
    CONSTRAINT pk_credit_request PRIMARY KEY (id),
    CONSTRAINT fk_credit_request_customer FOREIGN KEY (customer_id) REFERENCES customer.customer (id),
    CONSTRAINT fk_credit_request_submitted_by FOREIGN KEY (submitted_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_credit_request_recommended_by FOREIGN KEY (recommended_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_credit_request_decided_by FOREIGN KEY (decided_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_credit_request_source CHECK (source IN ('PORTAL', 'SALES')),
    CONSTRAINT ck_credit_request_status
        CHECK (status IN ('SUBMITTED', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    -- What is asked: a limit above zero and the days to pay after delivery, as a credit profile holds them
    -- (ck_credit_profile_credit).
    CONSTRAINT ck_credit_request_requested
        CHECK (requested_limit > 0 AND requested_term_days BETWEEN 0 AND 365),
    CONSTRAINT ck_credit_request_texts
        CHECK ((reason IS NULL OR btrim(reason) <> '') AND (sales_note IS NULL OR btrim(sales_note) <> '')
            AND (decision_note IS NULL OR btrim(decision_note) <> '')),
    CONSTRAINT ck_credit_request_recommended_limit
        CHECK (sales_recommended_limit IS NULL OR sales_recommended_limit > 0),
    -- Sales' opinion is whole or absent: who, when and what they wrote; the limit they suggest is optional.
    CONSTRAINT ck_credit_request_recommendation
        CHECK ((recommended_by IS NULL) = (recommended_at IS NULL) AND (recommended_by IS NULL) = (sales_note IS NULL)
            AND (recommended_by IS NOT NULL OR sales_recommended_limit IS NULL)),
    -- Only a request from the portal waits for Sales' opinion; one Sales filed carries theirs from the start.
    CONSTRAINT ck_credit_request_sales_source
        CHECK (source = 'PORTAL' OR (status <> 'SUBMITTED' AND recommended_by = submitted_by)),
    -- Every status says what it holds: waiting for Sales, no opinion yet; past it, the opinion; decided or withdrawn,
    -- who and when; approved, the limit and days granted - the limit never above what was asked (plan §5 q3);
    -- rejected, the reason.
    CONSTRAINT ck_credit_request_status_facts
        CHECK ((status <> 'SUBMITTED' OR recommended_by IS NULL)
            AND (status NOT IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED') OR recommended_by IS NOT NULL)
            AND ((status IN ('SUBMITTED', 'PENDING_APPROVAL')) = (decided_at IS NULL))
            AND (decided_at IS NULL) = (decided_by IS NULL)
            AND ((status = 'APPROVED') = (approved_limit IS NOT NULL))
            AND (approved_limit IS NULL) = (approved_term_days IS NULL)
            AND (approved_limit IS NULL OR (approved_limit > 0 AND approved_limit <= requested_limit
                AND approved_term_days BETWEEN 0 AND 365))
            AND (status <> 'REJECTED' OR decision_note IS NOT NULL)),
    -- Four eyes (18 BR-02): whoever decides neither filed the request nor gave Sales' opinion on it.
    CONSTRAINT ck_credit_request_four_eyes
        CHECK (status NOT IN ('APPROVED', 'REJECTED')
            OR (decided_by <> submitted_by AND decided_by IS DISTINCT FROM recommended_by))
);

COMMENT ON TABLE payment.credit_request IS
    'A request for a credit limit (QĐ-A, 2026-11-26): asked by the customer from the portal or by Sales for them, given '
    'Sales'' opinion, decided by the accountant. Approved, it wrote customer.credit_profile in the same transaction.';
COMMENT ON COLUMN payment.credit_request.source IS
    'PORTAL: the customer asked, and it waits for Sales'' opinion (SUBMITTED); SALES: Sales asked for the customer with '
    'their opinion (PENDING_APPROVAL from the start).';
COMMENT ON COLUMN payment.credit_request.submitted_by IS
    'The account that filed it: the customer''s portal account, or a Sales account.';
COMMENT ON COLUMN payment.credit_request.decided_by IS
    'The accountant who approved or rejected it; for a WITHDRAWN request, whoever withdrew it.';
COMMENT ON COLUMN payment.credit_request.approved_limit IS
    'The limit the accountant granted: at most requested_limit (plan §5 question 3); a higher one is a new request.';

-- One request open per customer (QĐ-A).
CREATE UNIQUE INDEX uk_credit_request_open ON payment.credit_request (customer_id)
    WHERE status IN ('SUBMITTED', 'PENDING_APPROVAL');
CREATE INDEX ix_credit_request_customer ON payment.credit_request (customer_id, submitted_at);
CREATE INDEX ix_credit_request_status ON payment.credit_request (status, submitted_at);

CREATE TABLE payment.credit_request_file (
    id uuid NOT NULL,
    credit_request_id uuid NOT NULL,
    storage_key character varying(512) NOT NULL,
    file_name character varying(255) NOT NULL,
    content_type character varying(100) NOT NULL,
    size_bytes bigint NOT NULL,
    uploaded_by uuid NOT NULL,
    uploaded_at timestamp with time zone NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    created_by character varying(100),
    last_modified_at timestamp with time zone,
    last_modified_by character varying(100),
    CONSTRAINT pk_credit_request_file PRIMARY KEY (id),
    CONSTRAINT fk_credit_request_file_request FOREIGN KEY (credit_request_id)
        REFERENCES payment.credit_request (id) ON DELETE CASCADE,
    CONSTRAINT fk_credit_request_file_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES identity.app_user (id),
    CONSTRAINT uk_credit_request_file_key UNIQUE (storage_key),
    CONSTRAINT ck_credit_request_file_key CHECK (storage_key LIKE 'documents/%'),
    CONSTRAINT ck_credit_request_file_name CHECK (btrim(file_name) <> ''),
    CONSTRAINT ck_credit_request_file_size CHECK (size_bytes > 0)
);

COMMENT ON TABLE payment.credit_request_file IS
    'A document kept with a credit request - the business registration, financial statements - in the common storage''s '
    'documents category.';

CREATE INDEX ix_credit_request_file_request ON payment.credit_request_file (credit_request_id, uploaded_at);

INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'customer-credit-requests:VIEW_PAGE', 'customer-credit-requests', 'VIEW_PAGE',
        'Open the credit request screens', 0, NOW(), 'flyway'),
       (gen_random_uuid(), 'customer-credit-requests:READ', 'customer-credit-requests', 'READ',
        'Read credit requests (a customer: their own)', 0, NOW(), 'flyway'),
       (gen_random_uuid(), 'customer-credit-requests:CREATE', 'customer-credit-requests', 'CREATE',
        'Ask for a credit limit (a customer: for themselves), attach files, withdraw one''s own request', 0, NOW(),
        'flyway'),
       (gen_random_uuid(), 'customer-credit-requests:UPDATE', 'customer-credit-requests', 'UPDATE',
        'Give Sales'' opinion on a credit request', 0, NOW(), 'flyway'),
       (gen_random_uuid(), 'customer-credit-requests:APPROVE', 'customer-credit-requests', 'APPROVE',
        'Approve or reject a credit request (the accountant)', 0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH grantee (role_code, permission_code) AS (
    VALUES ('CUSTOMER', 'customer-credit-requests:READ'),
           ('CUSTOMER', 'customer-credit-requests:CREATE'),
           ('SALES_STAFF', 'customer-credit-requests:VIEW_PAGE'),
           ('SALES_STAFF', 'customer-credit-requests:READ'),
           ('SALES_STAFF', 'customer-credit-requests:CREATE'),
           ('SALES_STAFF', 'customer-credit-requests:UPDATE'),
           ('SALES_MANAGER', 'customer-credit-requests:VIEW_PAGE'),
           ('SALES_MANAGER', 'customer-credit-requests:READ'),
           ('SALES_MANAGER', 'customer-credit-requests:CREATE'),
           ('SALES_MANAGER', 'customer-credit-requests:UPDATE'),
           ('ACCOUNTANT', 'customer-credit-requests:VIEW_PAGE'),
           ('ACCOUNTANT', 'customer-credit-requests:READ'),
           ('ACCOUNTANT', 'customer-credit-requests:APPROVE'),
           ('SYSTEM_ADMIN', 'customer-credit-requests:VIEW_PAGE'),
           ('SYSTEM_ADMIN', 'customer-credit-requests:READ'),
           ('SYSTEM_ADMIN', 'customer-credit-requests:CREATE'),
           ('SYSTEM_ADMIN', 'customer-credit-requests:UPDATE'),
           ('SYSTEM_ADMIN', 'customer-credit-requests:APPROVE')
),
granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), r.id, p.id, 0, NOW(), 'flyway'
      FROM grantee g
      JOIN identity.app_role r ON r.code = g.role_code
      JOIN identity.permission p ON p.code = g.permission_code
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
