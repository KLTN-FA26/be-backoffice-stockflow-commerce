-- 4A-1 (kltn-docs 17 §4.3; 00 §2.1, decision 2026-11-07): the Production Coordinator role, and the catalogue of the
-- producers that make the customers' printed goods - the company's own workshop or a partner, handled alike, differing
-- only in how an order reaches them (a PDF sheet or e-mail now; an API later). The coordinator keeps the catalogue and
-- each producer's standard lead time (default 14 days, open question B44).
--
-- The team plan gave the catalogue to the Sales Manager; the business decision of 2026-11-07 moved production work to
-- the new Production Coordinator role (00 §2.1 lines on "Điều phối sản xuất"), which this migration adds.

-- The role -----------------------------------------------------------------------------------------------------------
INSERT INTO identity.app_role (id, code, name, description, version, created_at, created_by, is_system, data_scope)
VALUES ('a0000001-0000-4000-8000-00000000000d', 'PRODUCTION_COORDINATOR', 'Production Coordinator',
        'Production orders for what a confirmed order lacks: producers, lead times, print samples, progress',
        0, NOW(), 'flyway', TRUE, 'ALL')
ON CONFLICT (code) DO NOTHING;

-- The producers ------------------------------------------------------------------------------------------------------
CREATE TABLE ordering.producer
(
    id                 UUID         NOT NULL,
    code               VARCHAR(32)  NOT NULL,
    name               VARCHAR(200) NOT NULL,
    -- Who receives the orders (17 §4.3: "liên hệ nhận đơn").
    contact_name       VARCHAR(200),
    contact_phone      VARCHAR(32),
    email              VARCHAR(254),
    -- How a production order reaches them: EMAIL (sent by the system) or PDF (the coordinator hands the sheet over).
    channel            VARCHAR(16)  NOT NULL,
    -- Days from sending an order to the goods arriving at the warehouse (17 §4.12 warnings).
    standard_lead_days INTEGER      NOT NULL DEFAULT 14,
    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    note               VARCHAR(500),

    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(100),
    last_modified_at   TIMESTAMPTZ,
    last_modified_by   VARCHAR(100),

    CONSTRAINT pk_producer PRIMARY KEY (id),
    CONSTRAINT uk_producer_code UNIQUE (code),
    CONSTRAINT ck_producer_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{1,31}$'),
    CONSTRAINT ck_producer_name CHECK (btrim(name) <> '' AND name = btrim(name)),
    CONSTRAINT ck_producer_channel CHECK (channel IN ('EMAIL', 'PDF')),
    -- An order by e-mail needs an address to go to.
    CONSTRAINT ck_producer_email CHECK (channel <> 'EMAIL' OR email IS NOT NULL),
    CONSTRAINT ck_producer_email_format CHECK (email IS NULL OR email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'),
    CONSTRAINT ck_producer_lead_days CHECK (standard_lead_days BETWEEN 1 AND 365),
    CONSTRAINT ck_producer_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_producer_note CHECK (note IS NULL OR btrim(note) <> '')
);

CREATE INDEX ix_producer_status ON ordering.producer (status, name);

COMMENT ON TABLE ordering.producer IS
    'Who makes the customers'' printed goods (kltn-docs 17 §4.3): the company''s workshop or a partner, alike; kept by '
    'the Production Coordinator. A production order names one; an INACTIVE one takes no new order.';

-- Permissions --------------------------------------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), 'sales-producers:' || a.action, 'sales-producers', a.action, 0, NOW(), 'flyway'
  FROM unnest(ARRAY['VIEW_PAGE', 'READ', 'CREATE', 'UPDATE']) AS a(action)
ON CONFLICT (code) DO NOTHING;

WITH wanted (role_code, actions) AS (
    VALUES
        -- 00 §2.1 (2026-11-07): the coordinator keeps the catalogue and the standard lead times.
        ('PRODUCTION_COORDINATOR', 'VIEW_PAGE,READ,CREATE,UPDATE'),
        -- The Sales Manager approves production orders; Sales and the warehouse manager see who makes the goods.
        ('SALES_MANAGER',          'VIEW_PAGE,READ'),
        ('SALES_STAFF',            'READ'),
        ('WAREHOUSE_MANAGER',      'READ'),
        ('SYSTEM_ADMIN',           'VIEW_PAGE,READ,CREATE,UPDATE')
), granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM wanted
      JOIN identity.app_role role ON role.code = wanted.role_code
      JOIN identity.permission permission
        ON permission.resource = 'sales-producers'
       AND permission.action = ANY (string_to_array(wanted.actions, ','))
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
-- Grants are cached per role version (ADR-0008): advance only the roles whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT role_id FROM granted);
