-- =============================================================================
-- A customer's default payment schedule, the one a new sales contract starts from (kltn-docs 18 §3, §4 steps 2 and 5,
-- BR-03, BR-04; 14 §4.3 step 12, BR-13; 15 BR-12; team plan 4B-1).
--
--   * customer.default_payment_schedule - one per customer: who set it and when, and - for a schedule with an
--     AFTER_DELIVERY installment - which accountant approved it and when (18 BR-03: only accounting grants credit).
--     Sales may propose one; until approved_by is set it awaits accounting, and no contract is drafted on it.
--   * customer.default_payment_installment - its installments in order: a name, a share of the order's total with tax
--     (a percentage, two decimals at most), a milestone (ON_SIGNING / BEFORE_DELIVERY / AFTER_DELIVERY) and N days
--     after it. The shares add up to 100.
--
-- Checked when the transaction commits (the service checks first), by customer.check_default_payment_schedule():
-- a schedule has at least one installment and its shares add up to 100; an AFTER_DELIVERY installment needs the
-- customer's approved credit (allow_credit with a limit above 0) - also when the credit terms change or go afterwards;
-- an approval is only on a schedule that pays after delivery. The credit is read FOR SHARE, so a transaction taking
-- the credit away and one adding an installment after delivery cannot both commit unseen by each other.
-- Who approved is the service's to decide (customer-payment-schedules:APPROVE); the database keeps who did.
-- Existing customers get no schedule: a contract drafted for one starts from their commercial terms until Sales sets
-- one (a deposit customer's deposit on signing and the rest before delivery, anyone else everything on signing).
--
-- New permission customer-payment-schedules: READ and UPDATE for Sales and accounting, APPROVE (an AFTER_DELIVERY
-- installment) for accounting.
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

-- The rules across the schedule, its installments and the customer's credit, checked at commit for every customer a
-- changed row touches - the old one too when a row moves to another.
CREATE FUNCTION customer.check_default_payment_schedule()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    touched        UUID[];
    the_customer   UUID;
    the_schedule   customer.default_payment_schedule%ROWTYPE;
    total          NUMERIC;
    lines          INTEGER;
    after_delivery BOOLEAN;
    credit_ok      BOOLEAN;
BEGIN
    IF TG_TABLE_NAME = 'default_payment_installment' THEN
        SELECT array_agg(s.customer_id) INTO touched FROM customer.default_payment_schedule s
         WHERE s.id IN (CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE NEW.schedule_id END,
                        CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.schedule_id END);
    ELSE
        touched := ARRAY[CASE WHEN TG_OP = 'DELETE' THEN NULL ELSE NEW.customer_id END,
                         CASE WHEN TG_OP = 'INSERT' THEN NULL ELSE OLD.customer_id END];
    END IF;
    FOR the_customer IN SELECT DISTINCT c FROM unnest(touched) AS c WHERE c IS NOT NULL LOOP
        SELECT * INTO the_schedule FROM customer.default_payment_schedule WHERE customer_id = the_customer;
        IF NOT FOUND THEN
            CONTINUE;
        END IF;
        SELECT coalesce(sum(percent), 0), count(*), coalesce(bool_or(milestone = 'AFTER_DELIVERY'), false)
          INTO total, lines, after_delivery
          FROM customer.default_payment_installment WHERE schedule_id = the_schedule.id;
        IF lines = 0 OR total <> 100 THEN
            RAISE EXCEPTION 'The default payment schedule of customer % has % installment(s) adding up to %, not 100',
                the_customer, lines, total USING ERRCODE = 'check_violation';
        END IF;
        IF the_schedule.approved_by IS NOT NULL AND NOT after_delivery THEN
            RAISE EXCEPTION 'The default payment schedule of customer % is approved but pays nothing after delivery',
                the_customer USING ERRCODE = 'check_violation';
        END IF;
        IF after_delivery THEN
            -- FOR SHARE: a concurrent change of the credit waits for this transaction, or this one waits for it and
            -- reads what it committed.
            SELECT bool_or(allow_credit AND credit_limit > 0) INTO credit_ok
              FROM (SELECT allow_credit, credit_limit FROM customer.credit_profile
                     WHERE customer_id = the_customer FOR SHARE) AS terms;
            IF NOT coalesce(credit_ok, false) THEN
                RAISE EXCEPTION 'The default payment schedule of customer % pays after delivery without approved credit',
                    the_customer USING ERRCODE = 'check_violation';
            END IF;
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER tg_default_payment_installment_check
    AFTER INSERT OR UPDATE OR DELETE ON customer.default_payment_installment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION customer.check_default_payment_schedule();

CREATE CONSTRAINT TRIGGER tg_default_payment_schedule_check
    AFTER INSERT OR UPDATE ON customer.default_payment_schedule
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION customer.check_default_payment_schedule();

CREATE CONSTRAINT TRIGGER tg_credit_profile_payment_schedule_check
    AFTER UPDATE OR DELETE ON customer.credit_profile
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
