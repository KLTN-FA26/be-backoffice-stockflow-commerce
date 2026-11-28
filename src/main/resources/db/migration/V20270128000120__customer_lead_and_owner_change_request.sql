-- =============================================================================
-- Leads from the public "open a business account" form, and a customer's request to change their salesperson
-- (sales-owner plan §10.3, §10.5, §10.7; API contract). Module: customer. Tables only: the services and endpoints
-- come with the endpoints of the plan.
--
-- customer.lead: NEW -> ASSIGNED -> CONTACTED -> CONVERTED, or DISQUALIFIED (with a reason) from any open status.
--   A lead has a Sales from ASSIGNED on; CONVERTED names the customer it became (once); DISQUALIFIED says why.
--
-- customer.sales_owner_change_request: PENDING -> APPROVED (a manager names the new owner) / REJECTED (with a note) /
--   WITHDRAWN. At most one PENDING per customer (a partial unique index, so two submissions at once cannot both win).
--   The 30-day wait after a decision (D12) is the service's: it reads decided_at.
-- =============================================================================

CREATE TABLE customer.lead (
    id                    UUID          NOT NULL,
    company_name          VARCHAR(200)  NOT NULL,
    tax_code              VARCHAR(14),
    contact_name          VARCHAR(200)  NOT NULL,
    contact_title         VARCHAR(100),
    phone                 VARCHAR(32),
    email                 VARCHAR(320),
    province              VARCHAR(100),
    -- What the business is interested in (cups, boxes, bags, bowls...), as the form's codes.
    interests             VARCHAR(32)[] NOT NULL DEFAULT '{}',
    monthly_volume        VARCHAR(100),
    note                  VARCHAR(2000),
    -- Consent to personal data processing (Decree 13/2023): a lead exists only with it.
    consent_at            TIMESTAMPTZ   NOT NULL,
    status                VARCHAR(32)   NOT NULL DEFAULT 'NEW',
    submitted_at          TIMESTAMPTZ   NOT NULL,
    assigned_to           UUID,
    assigned_by           UUID,
    assigned_at           TIMESTAMPTZ,
    contacted_at          TIMESTAMPTZ,
    contact_note          VARCHAR(2000),
    converted_customer_id UUID,
    converted_at          TIMESTAMPTZ,
    disqualified_reason   VARCHAR(1000),
    disqualified_by       UUID,
    disqualified_at       TIMESTAMPTZ,
    version               BIGINT        NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by            VARCHAR(100),
    last_modified_at      TIMESTAMPTZ,
    last_modified_by      VARCHAR(100),

    CONSTRAINT pk_lead PRIMARY KEY (id),
    CONSTRAINT uk_lead_converted_customer UNIQUE (converted_customer_id),
    CONSTRAINT fk_lead_assigned_to FOREIGN KEY (assigned_to) REFERENCES identity.app_user (id),
    CONSTRAINT fk_lead_assigned_by FOREIGN KEY (assigned_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_lead_disqualified_by FOREIGN KEY (disqualified_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_lead_converted_customer FOREIGN KEY (converted_customer_id) REFERENCES customer.customer (id),
    CONSTRAINT ck_lead_status CHECK (status IN ('NEW', 'ASSIGNED', 'CONTACTED', 'CONVERTED', 'DISQUALIFIED')),
    CONSTRAINT ck_lead_tax_code CHECK (tax_code IS NULL OR tax_code ~ '^([0-9]{10}(-[0-9]{3})?|[0-9]{12})$'),
    CONSTRAINT ck_lead_reachable CHECK (phone IS NOT NULL OR email IS NOT NULL),
    -- A Sales from ASSIGNED on; none while NEW.
    CONSTRAINT ck_lead_assignee CHECK (
        (status = 'NEW' AND assigned_to IS NULL)
        OR (status IN ('ASSIGNED', 'CONTACTED', 'CONVERTED') AND assigned_to IS NOT NULL AND assigned_at IS NOT NULL)
        OR status = 'DISQUALIFIED'),
    CONSTRAINT ck_lead_contacted CHECK (status <> 'CONTACTED' OR contacted_at IS NOT NULL),
    CONSTRAINT ck_lead_converted CHECK (
        (status = 'CONVERTED') = (converted_customer_id IS NOT NULL)
        AND (status <> 'CONVERTED' OR converted_at IS NOT NULL)),
    CONSTRAINT ck_lead_disqualified CHECK (
        (status = 'DISQUALIFIED') = (disqualified_reason IS NOT NULL)
        AND (disqualified_reason IS NULL OR (btrim(disqualified_reason) <> '' AND disqualified_at IS NOT NULL)))
);

COMMENT ON TABLE customer.lead IS
    'A business that asked to open an account through the public form (sales-owner plan §10.3 channel B): a Sales '
    'Manager assigns it, the Sales contacts it and converts it into a customer, or it is disqualified with a reason.';

CREATE INDEX ix_lead_status ON customer.lead (status, submitted_at DESC);
CREATE INDEX ix_lead_assigned_to ON customer.lead (assigned_to, status) WHERE assigned_to IS NOT NULL;
-- The 24-hour duplicate check of the public form (same tax code, same e-mail).
CREATE INDEX ix_lead_tax_code ON customer.lead (tax_code, submitted_at) WHERE tax_code IS NOT NULL;
CREATE INDEX ix_lead_email ON customer.lead (lower(email), submitted_at) WHERE email IS NOT NULL;

CREATE TABLE customer.sales_owner_change_request (
    id                      UUID          NOT NULL,
    customer_id             UUID          NOT NULL,
    -- The portal account that asked.
    requested_by            UUID,
    requested_at            TIMESTAMPTZ   NOT NULL,
    -- The owner when the request was made (it may change before the decision).
    current_owner_id        UUID,
    reason                  VARCHAR(32)   NOT NULL,
    note                    VARCHAR(1000),
    preferred_sales_user_id UUID,
    status                  VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    decided_by              UUID,
    decided_at              TIMESTAMPTZ,
    decision_note           VARCHAR(1000),
    new_sales_owner_id      UUID,
    version                 BIGINT        NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by              VARCHAR(100),
    last_modified_at        TIMESTAMPTZ,
    last_modified_by        VARCHAR(100),

    CONSTRAINT pk_sales_owner_change_request PRIMARY KEY (id),
    CONSTRAINT fk_sales_owner_change_request_customer FOREIGN KEY (customer_id)
        REFERENCES customer.customer (id) ON DELETE CASCADE,
    CONSTRAINT fk_sales_owner_change_request_requested_by FOREIGN KEY (requested_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_sales_owner_change_request_current FOREIGN KEY (current_owner_id) REFERENCES identity.app_user (id),
    CONSTRAINT fk_sales_owner_change_request_preferred FOREIGN KEY (preferred_sales_user_id)
        REFERENCES identity.app_user (id),
    CONSTRAINT fk_sales_owner_change_request_decided_by FOREIGN KEY (decided_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_sales_owner_change_request_new_owner FOREIGN KEY (new_sales_owner_id)
        REFERENCES identity.app_user (id),
    CONSTRAINT ck_sales_owner_change_request_reason CHECK (reason IN ('SLOW_RESPONSE', 'NOT_SATISFIED',
                                                                      'PREFER_KNOWN_CONTACT', 'OTHER')),
    CONSTRAINT ck_sales_owner_change_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT ck_sales_owner_change_request_decision CHECK (
        (status = 'PENDING' AND decided_at IS NULL AND decided_by IS NULL AND new_sales_owner_id IS NULL)
        OR (status = 'APPROVED' AND decided_at IS NOT NULL AND decided_by IS NOT NULL AND new_sales_owner_id IS NOT NULL)
        OR (status = 'REJECTED' AND decided_at IS NOT NULL AND decided_by IS NOT NULL AND new_sales_owner_id IS NULL
            AND decision_note IS NOT NULL AND btrim(decision_note) <> '')
        OR (status = 'WITHDRAWN' AND decided_at IS NOT NULL AND new_sales_owner_id IS NULL))
);

COMMENT ON TABLE customer.sales_owner_change_request IS
    'A customer''s request to be served by another salesperson (sales-owner plan §10.5): the Sales Manager decides; '
    'one pending request per customer, 30 days between a decision and the next request.';

CREATE UNIQUE INDEX uk_sales_owner_change_request_pending
    ON customer.sales_owner_change_request (customer_id) WHERE status = 'PENDING';
CREATE INDEX ix_sales_owner_change_request_customer
    ON customer.sales_owner_change_request (customer_id, requested_at DESC);
CREATE INDEX ix_sales_owner_change_request_status
    ON customer.sales_owner_change_request (status, requested_at DESC);
