-- =============================================================================
-- A customer asks the sales staff for a quote on their design (kltn-docs 12 §4.8, 16 §4.2 step 5, 13; B40; Design
-- Studio plan 2F-8).
--
--   * design.design_quote_request - one request: the design, the cartons the customer would like (a suggestion, at
--     least one), the delivery date they would like (if any; not before the day they asked, in Vietnam), who asked,
--     and whether it is still open. A request is made on an approved design, or together with a draft's submission.
--     A design returned after that keeps its request open and the request follows it (B40); deleting the draft closes
--     it. At most one request per design is open at a time (uk_design_quote_request_open).
--   * design-quote-requests - who asks (CUSTOMER: CREATE), and who reads the requests and, while one is open, the
--     design it names when that design waits for review or is approved (SALES_STAFF, SALES_MANAGER: VIEW_PAGE, READ;
--     plan D7). SYSTEM_ADMIN holds all three, as every permission. Every role whose grants change moves its version
--     (ADR-0008).
--
-- The chat card that is to carry a request (16 §4.2) belongs to the chat module, which is not built yet; until it is,
-- the sales staff are notified of each request and list them.
-- =============================================================================

CREATE TABLE design.design_quote_request
(
    id                    UUID         NOT NULL,
    draft_id              UUID         NOT NULL,
    requested_by          UUID         NOT NULL,
    cartons               INTEGER      NOT NULL,
    desired_delivery_date DATE,
    status                VARCHAR(20)  NOT NULL,
    closed_at             TIMESTAMPTZ,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL,
    created_by            VARCHAR(100),
    last_modified_at      TIMESTAMPTZ,
    last_modified_by      VARCHAR(100),

    CONSTRAINT pk_design_quote_request PRIMARY KEY (id),
    CONSTRAINT fk_design_quote_request_draft FOREIGN KEY (draft_id) REFERENCES design.design_draft (id),
    CONSTRAINT fk_design_quote_request_requested_by FOREIGN KEY (requested_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_design_quote_request_cartons CHECK (cartons >= 1),
    CONSTRAINT ck_design_quote_request_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_design_quote_request_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    -- The day it was asked, in Vietnam: timezone(text, timestamptz) is immutable, so the rule holds for the row's life.
    CONSTRAINT ck_design_quote_request_delivery_date CHECK (desired_delivery_date IS NULL
        OR desired_delivery_date >= (created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)
);

-- One open request per design: the sales staff answer a design once at a time.
CREATE UNIQUE INDEX uk_design_quote_request_open ON design.design_quote_request (draft_id) WHERE status = 'OPEN';
-- A design's requests, newest first.
CREATE INDEX ix_design_quote_request_draft ON design.design_quote_request (draft_id, created_at DESC, id DESC);
-- The sales staff's list, newest first, by status.
CREATE INDEX ix_design_quote_request_list ON design.design_quote_request (status, created_at DESC, id DESC);
CREATE INDEX ix_design_quote_request_requested_by ON design.design_quote_request (requested_by);


INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), 'design-quote-requests:' || a.action, 'design-quote-requests', a.action, 0, NOW(), 'flyway'
FROM unnest(ARRAY['VIEW_PAGE', 'READ', 'CREATE']) AS a(action)
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          ('CUSTOMER',      'CREATE'),
          ('SALES_STAFF',   'VIEW_PAGE,READ'),
          ('SALES_MANAGER', 'VIEW_PAGE,READ'),
          ('SYSTEM_ADMIN',  'VIEW_PAGE,READ,CREATE')
     ) AS g(role_code, actions)
JOIN identity.app_role role ON role.code = g.role_code
JOIN identity.permission permission
  ON permission.resource = 'design-quote-requests'
 AND permission.action = ANY (string_to_array(g.actions, ','))
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('CUSTOMER', 'SALES_STAFF', 'SALES_MANAGER', 'SYSTEM_ADMIN');
