-- =============================================================================
-- A customer's default payment schedule, the one a new sales contract starts from (kltn-docs 18 §3, §4 steps 2 and 5,
-- BR-03; 14 §4.3 step 12, BR-13; 15 BR-12; team plan 4B-1).
--
--   * customer.default_payment_schedule - one per customer: who set it and when, and - for a schedule with an
--     AFTER_DELIVERY instalment - which accountant approved it and when (18 BR-03: only accounting grants credit).
--   * customer.default_payment_installment - its instalments in order: a name, a share of the order's total with tax
--     (a percentage, two decimals at most), a milestone (ON_SIGNING / BEFORE_DELIVERY / AFTER_DELIVERY) and N days
--     after it. The shares add up to 100.
--
-- Checked when the transaction commits (18 BR-03 held by the database too; the service checks first):
-- a schedule has at least one instalment and its shares add up to 100; an AFTER_DELIVERY instalment needs an
-- accountant's approval and the customer's approved credit (allow_credit with a limit above 0) - also when the credit
-- terms change afterwards (a limit lowered to 0 while the default schedule still pays after delivery is refused).
-- Existing customers get no schedule: they keep their credit profile, and a contract drafted for one starts from a
-- single ON_SIGNING instalment until Sales sets one.
--
-- New permission customer-payment-schedules: READ and UPDATE for Sales and accounting, APPROVE (an AFTER_DELIVERY
-- instalment) for accounting.
-- =============================================================================

CREATE TABLE customer.default_payment_schedule
(
    id               UUID          NOT NULL,
    customer_id      UUID          NOT NULL,
    set_by           UUID          NOT NULL,
    set_at           TIMESTAMPTZ   NOT NULL,
    approved_by      UUID,
    approved_at      TIMESTAMPTZ,
    note             VARCHAR(1000),

    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_default_payment_schedule PRIMARY KEY (id),
    CONSTRAINT uk_default_payment_schedule_customer UNIQUE (customer_id),
    CONSTRAINT fk_default_payment_schedule_customer FOREIGN KEY (customer_id) REFERENCES customer.customer (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_default_payment_schedule_set_by FOREIGN KEY (set_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_default_payment_schedule_approved_by FOREIGN KEY (approved_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_default_payment_schedule_approval CHECK ((approved_by IS NULL) = (approved_at IS NULL))
);

CREATE INDEX ix_default_payment_schedule_set_by ON customer.default_payment_schedule (set_by);
CREATE INDEX ix_default_payment_schedule_approved_by ON customer.default_payment_schedule (approved_by);

CREATE TABLE customer.default_payment_installment
(
    schedule_id UUID          NOT NULL,
    seq         INTEGER       NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    percent     NUMERIC(5, 2) NOT NULL,
    milestone   VARCHAR(16)   NOT NULL,
    due_days    INTEGER       NOT NULL DEFAULT 0,

    CONSTRAINT pk_default_payment_installment PRIMARY KEY (schedule_id, seq),
    CONSTRAINT fk_default_payment_installment_schedule FOREIGN KEY (schedule_id)
        REFERENCES customer.default_payment_schedule (id) ON DELETE CASCADE,
    CONSTRAINT ck_default_payment_installment_seq CHECK (seq BETWEEN 1 AND 12),
    CONSTRAINT ck_default_payment_installment_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_default_payment_installment_percent CHECK (percent > 0 AND percent <= 100),
    CONSTRAINT ck_default_payment_installment_milestone CHECK (milestone IN ('ON_SIGNING', 'BEFORE_DELIVERY',
        'AFTER_DELIVERY')),
    CONSTRAINT ck_default_payment_installment_due_days CHECK (due_days BETWEEN 0 AND 365)
);

-- The rules across the schedule, its instalments and the customer's credit, checked at commit.
CREATE FUNCTION customer.check_default_payment_schedule()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    the_customer UUID;
    the_schedule customer.default_payment_schedule%ROWTYPE;
    total        NUMERIC;
    lines        INTEGER;
    after_delivery BOOLEAN;
    credit_ok    BOOLEAN;
BEGIN
    IF TG_TABLE_NAME = 'credit_profile' THEN
        the_customer := NEW.customer_id;
    ELSIF TG_TABLE_NAME = 'default_payment_schedule' THEN
        the_customer := CASE WHEN TG_OP = 'DELETE' THEN OLD.customer_id ELSE NEW.customer_id END;
    ELSE
        SELECT s.customer_id INTO the_customer FROM customer.default_payment_schedule s
         WHERE s.id = CASE WHEN TG_OP = 'DELETE' THEN OLD.schedule_id ELSE NEW.schedule_id END;
    END IF;
    SELECT * INTO the_schedule FROM customer.default_payment_schedule WHERE customer_id = the_customer;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    SELECT coalesce(sum(percent), 0), count(*), coalesce(bool_or(milestone = 'AFTER_DELIVERY'), false)
      INTO total, lines, after_delivery
      FROM customer.default_payment_installment WHERE schedule_id = the_schedule.id;
    IF lines = 0 OR total <> 100 THEN
        RAISE EXCEPTION 'The default payment schedule of customer % has % instalment(s) adding up to %, not 100',
            the_customer, lines, total USING ERRCODE = 'check_violation';
    END IF;
    IF after_delivery THEN
        SELECT coalesce(bool_or(allow_credit AND credit_limit > 0), false) INTO credit_ok
          FROM customer.credit_profile WHERE customer_id = the_customer;
        IF the_schedule.approved_by IS NULL OR NOT coalesce(credit_ok, false) THEN
            RAISE EXCEPTION 'The default payment schedule of customer % pays after delivery without approved credit',
                the_customer USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_default_payment_installment_check
    AFTER INSERT OR UPDATE OR DELETE ON customer.default_payment_installment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION customer.check_default_payment_schedule();

CREATE CONSTRAINT TRIGGER trg_default_payment_schedule_check
    AFTER INSERT OR UPDATE ON customer.default_payment_schedule
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION customer.check_default_payment_schedule();

CREATE CONSTRAINT TRIGGER trg_credit_profile_payment_schedule_check
    AFTER UPDATE ON customer.credit_profile
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION customer.check_default_payment_schedule();

-- Who sets and approves it ------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
VALUES (gen_random_uuid(), 'customer-payment-schedules:READ', 'customer-payment-schedules', 'READ', 0, NOW(),
        'flyway'),
       (gen_random_uuid(), 'customer-payment-schedules:UPDATE', 'customer-payment-schedules', 'UPDATE', 0, NOW(),
        'flyway'),
       (gen_random_uuid(), 'customer-payment-schedules:APPROVE', 'customer-payment-schedules', 'APPROVE', 0, NOW(),
        'flyway')
ON CONFLICT (code) DO NOTHING;

WITH wanted (role_code, permission_code) AS (
    VALUES ('SALES_STAFF', 'customer-payment-schedules:READ'),
           ('SALES_STAFF', 'customer-payment-schedules:UPDATE'),
           ('SALES_MANAGER', 'customer-payment-schedules:READ'),
           ('SALES_MANAGER', 'customer-payment-schedules:UPDATE'),
           ('ACCOUNTANT', 'customer-payment-schedules:READ'),
           ('ACCOUNTANT', 'customer-payment-schedules:UPDATE'),
           ('ACCOUNTANT', 'customer-payment-schedules:APPROVE'),
           ('SYSTEM_ADMIN', 'customer-payment-schedules:READ'),
           ('SYSTEM_ADMIN', 'customer-payment-schedules:UPDATE'),
           ('SYSTEM_ADMIN', 'customer-payment-schedules:APPROVE')
), granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM wanted
      JOIN identity.app_role role ON role.code = wanted.role_code
      JOIN identity.permission permission ON permission.code = wanted.permission_code
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
-- Grants are cached per role version (ADR-0008): advance only the roles whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
