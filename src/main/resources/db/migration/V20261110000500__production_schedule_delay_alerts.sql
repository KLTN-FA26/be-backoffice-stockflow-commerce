-- 4A-7 (kltn-docs 17 §4.12, BR-30, open question B44): the production schedule and its four delay alerts.
--
--   * ordering.production_order.expected_completion_date - the day the producer said the goods will be done, recorded by
--     the Production Coordinator when the producer accepts the order (17 §4.3 step 8) or later; 4A-4 writes it. AT_RISK
--     compares it with the need-by date.
--   * ordering.delay_alert - one row per episode of a delay condition on one subject: opened when the condition starts
--     to hold, closed (never deleted, never reopened) when it no longer does; it holding again opens a new row. At most
--     one open alert per type and subject (the unique partial index), so a nightly sweep and a recompute after an
--     operation racing each other open it once. Alerts never block anything: nothing reads them to refuse an operation.
--     The warehouse an alert concerns is not stored: it is read where it lives (the production order's receiving
--     warehouse, the order's shipping warehouse), which may still be chosen or changed while a PO_NEEDED is open.
--   * production-schedule (VIEW_PAGE, READ) - the schedule screen; delay-alerts (READ) - the delay flags of an order.

ALTER TABLE ordering.production_order
    ADD COLUMN expected_completion_date DATE;

-- 17 §4.3 step 8: the producer gives the day when it accepts the order - never before it was sent.
ALTER TABLE ordering.production_order
    ADD CONSTRAINT ck_production_order_expected_completion CHECK (expected_completion_date IS NULL
        OR status NOT IN ('DRAFT', 'PENDING_APPROVAL', 'SENT'));

COMMENT ON COLUMN ordering.production_order.expected_completion_date IS
    'The day the producer expects the goods done (17 §4.3 step 8), from ACCEPTED on; AT_RISK when after need_by_date.';

CREATE TABLE ordering.delay_alert
(
    id                  UUID        NOT NULL,
    -- 17 §4.12 step 48.
    type                VARCHAR(24) NOT NULL,
    -- The subject: a production order (AT_RISK, PRODUCTION_LATE) or a delivery batch (PO_NEEDED, DELIVERY_LATE).
    production_order_id UUID,
    delivery_batch_id   UUID,
    subject_id          UUID GENERATED ALWAYS AS (COALESCE(production_order_id, delivery_batch_id)) STORED,
    -- The sales order the subject belongs to, for the order page and the recompute of one order.
    sales_order_id      UUID        NOT NULL,
    opened_at           TIMESTAMPTZ NOT NULL,
    closed_at           TIMESTAMPTZ,

    CONSTRAINT pk_delay_alert PRIMARY KEY (id),
    CONSTRAINT fk_delay_alert_production_order FOREIGN KEY (production_order_id)
        REFERENCES ordering.production_order (id),
    -- A batch goes with its contract; its alerts go with it rather than hold anything up (alerts never block).
    CONSTRAINT fk_delay_alert_batch FOREIGN KEY (delivery_batch_id) REFERENCES ordering.delivery_batch (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_delay_alert_sales_order FOREIGN KEY (sales_order_id) REFERENCES ordering.customer_order (id),
    CONSTRAINT ck_delay_alert_type CHECK (type IN ('AT_RISK', 'PRODUCTION_LATE', 'PO_NEEDED', 'DELIVERY_LATE')),
    -- Each type has exactly one kind of subject.
    CONSTRAINT ck_delay_alert_subject CHECK (
        (type IN ('AT_RISK', 'PRODUCTION_LATE')) = (production_order_id IS NOT NULL)
        AND (type IN ('PO_NEEDED', 'DELIVERY_LATE')) = (delivery_batch_id IS NOT NULL)),
    CONSTRAINT ck_delay_alert_closed CHECK (closed_at IS NULL OR closed_at >= opened_at)
);

-- 17 §4.12: at most one open alert of a type on a subject.
CREATE UNIQUE INDEX uk_delay_alert_open ON ordering.delay_alert (type, subject_id) WHERE closed_at IS NULL;
CREATE INDEX ix_delay_alert_sales_order ON ordering.delay_alert (sales_order_id) WHERE closed_at IS NULL;
CREATE INDEX ix_delay_alert_production_order ON ordering.delay_alert (production_order_id)
    WHERE production_order_id IS NOT NULL;
CREATE INDEX ix_delay_alert_batch ON ordering.delay_alert (delivery_batch_id) WHERE delivery_batch_id IS NOT NULL;

COMMENT ON TABLE ordering.delay_alert IS
    'Delay alerts of the production schedule (kltn-docs 17 §4.12, BR-30): AT_RISK, PRODUCTION_LATE on a production '
    'order; PO_NEEDED, DELIVERY_LATE on a delivery batch. Opened and closed by the system; they never block anything.';

-- What an alert is about never changes, its subject belongs to its sales order, and it is closed once - the only change.
CREATE FUNCTION ordering.check_delay_alert() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    owner UUID;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.id IS DISTINCT FROM OLD.id OR NEW.type IS DISTINCT FROM OLD.type
            OR NEW.production_order_id IS DISTINCT FROM OLD.production_order_id
            OR NEW.delivery_batch_id IS DISTINCT FROM OLD.delivery_batch_id
            OR NEW.sales_order_id IS DISTINCT FROM OLD.sales_order_id OR NEW.opened_at IS DISTINCT FROM OLD.opened_at THEN
            RAISE EXCEPTION 'delay alert %: what it is about does not change', OLD.id USING ERRCODE = 'check_violation';
        END IF;
        IF OLD.closed_at IS NOT NULL AND NEW.closed_at IS DISTINCT FROM OLD.closed_at THEN
            RAISE EXCEPTION 'delay alert %: closed once, never reopened', OLD.id USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.production_order_id IS NOT NULL THEN
        SELECT p.sales_order_id INTO owner FROM ordering.production_order p WHERE p.id = NEW.production_order_id;
    ELSE
        SELECT c.order_id INTO owner
          FROM ordering.delivery_batch b JOIN ordering.sales_contract c ON c.id = b.contract_id
         WHERE b.id = NEW.delivery_batch_id;
    END IF;
    IF owner IS DISTINCT FROM NEW.sales_order_id THEN
        RAISE EXCEPTION 'delay alert %: its subject is of its sales order', NEW.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_delay_alert
    BEFORE INSERT OR UPDATE ON ordering.delay_alert
    FOR EACH ROW EXECUTE FUNCTION ordering.check_delay_alert();

-- Permissions --------------------------------------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), p.resource || ':' || p.action, p.resource, p.action, 0, NOW(), 'flyway'
  FROM (VALUES ('production-schedule', 'VIEW_PAGE'), ('production-schedule', 'READ'),
               ('delay-alerts', 'READ')) AS p(resource, action)
ON CONFLICT (code) DO NOTHING;

WITH wanted (role_code, resource, actions) AS (
    VALUES
        -- 17 §4.12 step 47: the coordinator, Sales and the Sales Manager follow the schedule; the warehouse manager sees
        -- the production orders coming to their warehouse (the service scopes it). Holders of production-schedule:READ
        -- whose scope is the whole business are told of AT_RISK, PRODUCTION_LATE and PO_NEEDED (BR-30).
        ('PRODUCTION_COORDINATOR', 'production-schedule', 'VIEW_PAGE,READ'),
        ('SALES_STAFF',            'production-schedule', 'VIEW_PAGE,READ'),
        ('SALES_MANAGER',          'production-schedule', 'VIEW_PAGE,READ'),
        ('WAREHOUSE_MANAGER',      'production-schedule', 'VIEW_PAGE,READ'),
        ('SYSTEM_ADMIN',           'production-schedule', 'VIEW_PAGE,READ'),
        -- 17 §4.12 step 49: the delay flags on the order page - and, at the order's shipping warehouse, who is told of
        -- DELIVERY_LATE: Sales, the Sales Manager and that warehouse's manager, not the coordinator (BR-30).
        ('SALES_STAFF',            'delay-alerts',        'READ'),
        ('SALES_MANAGER',          'delay-alerts',        'READ'),
        ('WAREHOUSE_MANAGER',      'delay-alerts',        'READ'),
        ('SYSTEM_ADMIN',           'delay-alerts',        'READ')
), granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM wanted
      JOIN identity.app_role role ON role.code = wanted.role_code
      JOIN identity.permission permission
        ON permission.resource = wanted.resource
       AND permission.action = ANY (string_to_array(wanted.actions, ','))
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
-- Grants are cached per role version (ADR-0008): advance only the roles whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT role_id FROM granted);
