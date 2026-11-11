-- 4B-6 (kltn-docs 15 §4.1-§4.4, §5.1-§5.3, BR-01..BR-13; 17 §4.5): a signed contract's payment plan becomes the
-- installments the customer pays, and the accountant records what the bank statement shows as payment receipts
-- against them. One receipt may pay several installments of one customer; a receipt is never edited, only voided.
--
-- The money ledger (customer_transfer, transfer_allocation, receivable) stays for checkout orders until 4B-10/4B-11.

-- The installments -------------------------------------------------------------------------------------------------
CREATE TABLE payment.installment
(
    id                      UUID           NOT NULL,
    contract_id             UUID           NOT NULL,
    -- The row of the contract's plan it was made from: amount, milestone and days are copied, never changed.
    contract_installment_id UUID           NOT NULL,
    order_id                UUID           NOT NULL,
    customer_id             UUID           NOT NULL,
    contract_number         VARCHAR(40)    NOT NULL,
    order_number            VARCHAR(32)    NOT NULL,
    seq                     SMALLINT       NOT NULL,
    name                    VARCHAR(100)   NOT NULL,
    milestone               VARCHAR(16)    NOT NULL,
    -- 17 §4.5, 15 D37: the delivery batch it is tied to, if any; never for ON_SIGNING.
    delivery_batch_id       UUID,
    delivery_batch_seq      SMALLINT,
    amount                  NUMERIC(19, 4) NOT NULL,
    paid_amount             NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency                VARCHAR(3)     NOT NULL,
    -- Days after the milestone it falls due; BEFORE_DELIVERY has none: it blocks the handover until paid (BR-08).
    due_days                INTEGER,
    -- When its milestone came (signed, released, delivered): from then on it is due (15 §4.1 step 2).
    reached_at              TIMESTAMPTZ,
    -- The milestone's day plus due_days; past it and not paid in full, the nightly sweep makes it OVERDUE.
    due_date                DATE,
    status                  VARCHAR(16)    NOT NULL,
    paid_at                 TIMESTAMPTZ,
    cancelled_at            TIMESTAMPTZ,

    version                 BIGINT         NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by              VARCHAR(100),
    last_modified_at        TIMESTAMPTZ,
    last_modified_by        VARCHAR(100),

    CONSTRAINT pk_installment PRIMARY KEY (id),
    CONSTRAINT uk_installment_seq UNIQUE (contract_id, seq),
    CONSTRAINT uk_installment_contract_installment UNIQUE (contract_installment_id),
    CONSTRAINT fk_installment_contract FOREIGN KEY (contract_id) REFERENCES ordering.sales_contract (id),
    CONSTRAINT fk_installment_contract_installment FOREIGN KEY (contract_installment_id)
        REFERENCES ordering.contract_payment_installment (id),
    CONSTRAINT fk_installment_order FOREIGN KEY (order_id) REFERENCES ordering.customer_order (id),
    CONSTRAINT fk_installment_customer FOREIGN KEY (customer_id) REFERENCES customer.customer (id),
    CONSTRAINT fk_installment_delivery_batch FOREIGN KEY (delivery_batch_id) REFERENCES ordering.delivery_batch (id),
    CONSTRAINT ck_installment_seq CHECK (seq BETWEEN 1 AND 20),
    CONSTRAINT ck_installment_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_installment_milestone CHECK (milestone IN ('ON_SIGNING', 'BEFORE_DELIVERY', 'AFTER_DELIVERY')),
    -- Whole dong (14 BR-21); never more paid than owed, and nothing beyond it (15 BR-05: no money left over).
    CONSTRAINT ck_installment_amounts CHECK (amount > 0 AND amount = TRUNC(amount)
        AND paid_amount >= 0 AND paid_amount <= amount AND paid_amount = TRUNC(paid_amount)),
    CONSTRAINT ck_installment_due_days CHECK ((milestone = 'BEFORE_DELIVERY') = (due_days IS NULL)
        AND (due_days IS NULL OR due_days BETWEEN 0 AND 365)),
    -- A due day exactly when an installment that has one has reached its milestone.
    CONSTRAINT ck_installment_due_date CHECK ((due_date IS NOT NULL)
        = (reached_at IS NOT NULL AND milestone <> 'BEFORE_DELIVERY')),
    CONSTRAINT ck_installment_batch CHECK ((milestone <> 'ON_SIGNING' OR delivery_batch_id IS NULL)
        AND (delivery_batch_id IS NULL) = (delivery_batch_seq IS NULL)
        AND (delivery_batch_seq IS NULL OR delivery_batch_seq BETWEEN 1 AND 20)),
    CONSTRAINT ck_installment_status CHECK (status IN ('NOT_DUE', 'DUE', 'PARTIALLY_PAID', 'PAID', 'OVERDUE',
                                                      'CANCELLED')),
    -- The status says what the money and the milestone say (15 §5.1). Paid early, an installment is PARTIALLY_PAID or
    -- PAID before its milestone and keeps that status when the milestone comes (§4.1 step 3).
    CONSTRAINT ck_installment_status_facts CHECK (
        ((status = 'NOT_DUE' AND reached_at IS NULL AND paid_amount = 0)
      OR (status = 'DUE' AND reached_at IS NOT NULL AND paid_amount = 0)
      OR (status = 'PARTIALLY_PAID' AND paid_amount > 0 AND paid_amount < amount)
      OR (status = 'OVERDUE' AND due_date IS NOT NULL AND paid_amount < amount)
      OR (status = 'PAID' AND paid_amount = amount)
      OR (status = 'CANCELLED' AND paid_amount < amount))
        AND (status = 'PAID') = (paid_at IS NOT NULL)
        AND (status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

CREATE INDEX ix_installment_order ON payment.installment (order_id, seq);
CREATE INDEX ix_installment_customer ON payment.installment (customer_id, status);
CREATE INDEX ix_installment_due ON payment.installment (due_date) WHERE status IN ('DUE', 'PARTIALLY_PAID');
CREATE INDEX ix_installment_delivery_batch ON payment.installment (delivery_batch_id)
    WHERE delivery_batch_id IS NOT NULL;

COMMENT ON TABLE payment.installment IS
    'A signed contract''s payment installments (kltn-docs 15 §4.1, §5.1): made when the contract is signed, due when '
    'its milestone comes, paid by payment receipts; unpaid ones of a cancelled order are CANCELLED.';
COMMENT ON COLUMN payment.installment.paid_amount IS
    'What RECORDED payment receipt lines pay it (check_installment_paid at commit).';

-- An installment is the contract's plan row it names, of a signed contract, for that contract's order and customer.
CREATE FUNCTION payment.check_installment_matches_plan() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    plan RECORD;
BEGIN
    SELECT p.contract_id, p.seq, p.name, p.milestone, p.amount, p.due_days, p.delivery_batch_id, b.seq AS batch_seq,
           c.contract_number, c.status AS contract_status, c.order_id, o.customer_id, o.order_number, o.currency
      INTO plan
      FROM ordering.contract_payment_installment p
      JOIN ordering.sales_contract c ON c.id = p.contract_id
      JOIN ordering.customer_order o ON o.id = c.order_id
      LEFT JOIN ordering.delivery_batch b ON b.id = p.delivery_batch_id
     WHERE p.id = NEW.contract_installment_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'installment %: no plan row %', NEW.id, NEW.contract_installment_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF plan.contract_status NOT IN ('SIGNED', 'COMPLETED') THEN
        RAISE EXCEPTION 'installment of contract %: the contract is %, installments come with its signature (15 §4.1)',
            plan.contract_number, plan.contract_status USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.contract_id IS DISTINCT FROM plan.contract_id OR NEW.order_id IS DISTINCT FROM plan.order_id
        OR NEW.customer_id IS DISTINCT FROM plan.customer_id OR NEW.contract_number IS DISTINCT FROM plan.contract_number
        OR NEW.order_number IS DISTINCT FROM plan.order_number OR NEW.currency IS DISTINCT FROM plan.currency
        OR NEW.seq IS DISTINCT FROM plan.seq OR NEW.name IS DISTINCT FROM plan.name
        OR NEW.milestone IS DISTINCT FROM plan.milestone OR NEW.amount IS DISTINCT FROM plan.amount
        OR NEW.due_days IS DISTINCT FROM plan.due_days OR NEW.delivery_batch_id IS DISTINCT FROM plan.delivery_batch_id
        OR NEW.delivery_batch_seq IS DISTINCT FROM plan.batch_seq THEN
        RAISE EXCEPTION 'installment % of contract %: it is the signed plan''s row, as signed (14 BR-14)', NEW.seq,
            plan.contract_number USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_installment_matches_plan
    BEFORE INSERT ON payment.installment
    FOR EACH ROW EXECUTE FUNCTION payment.check_installment_matches_plan();

-- What it is never changes; its milestone comes once; CANCELLED is the end; OVERDUE stays until paid in full (15 §5.1).
CREATE FUNCTION payment.check_installment_transition() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'installment % of contract %: installments are kept', OLD.seq, OLD.contract_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.contract_id IS DISTINCT FROM OLD.contract_id
        OR NEW.contract_installment_id IS DISTINCT FROM OLD.contract_installment_id
        OR NEW.order_id IS DISTINCT FROM OLD.order_id OR NEW.customer_id IS DISTINCT FROM OLD.customer_id
        OR NEW.contract_number IS DISTINCT FROM OLD.contract_number
        OR NEW.order_number IS DISTINCT FROM OLD.order_number OR NEW.seq IS DISTINCT FROM OLD.seq
        OR NEW.name IS DISTINCT FROM OLD.name OR NEW.milestone IS DISTINCT FROM OLD.milestone
        OR NEW.delivery_batch_id IS DISTINCT FROM OLD.delivery_batch_id
        OR NEW.delivery_batch_seq IS DISTINCT FROM OLD.delivery_batch_seq OR NEW.amount IS DISTINCT FROM OLD.amount
        OR NEW.currency IS DISTINCT FROM OLD.currency OR NEW.due_days IS DISTINCT FROM OLD.due_days THEN
        RAISE EXCEPTION 'installment % of contract %: what it is does not change (14 BR-14)', OLD.seq,
            OLD.contract_number USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.reached_at IS NOT NULL AND (NEW.reached_at IS DISTINCT FROM OLD.reached_at
                                       OR NEW.due_date IS DISTINCT FROM OLD.due_date) THEN
        RAISE EXCEPTION 'installment % of contract %: its milestone came on %; that does not change', OLD.seq,
            OLD.contract_number, OLD.reached_at USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status = 'CANCELLED' THEN
        RAISE EXCEPTION 'installment % of contract %: a CANCELLED installment does not change', OLD.seq,
            OLD.contract_number USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'NOT_DUE' AND NEW.status IN ('DUE', 'PARTIALLY_PAID', 'PAID', 'CANCELLED'))
        OR (OLD.status = 'DUE' AND NEW.status IN ('PARTIALLY_PAID', 'PAID', 'OVERDUE', 'CANCELLED'))
        -- Back to DUE / NOT_DUE only by a receipt voided (15 §4.4 step 12).
        OR (OLD.status = 'PARTIALLY_PAID' AND NEW.status IN ('PAID', 'OVERDUE', 'CANCELLED', 'DUE', 'NOT_DUE'))
        OR (OLD.status = 'OVERDUE' AND NEW.status IN ('PAID', 'CANCELLED'))
        OR (OLD.status = 'PAID' AND NEW.status IN ('PARTIALLY_PAID', 'DUE', 'NOT_DUE', 'OVERDUE'))) THEN
        RAISE EXCEPTION 'installment % of contract %: % -> % is not a transition (15 §5.1)', OLD.seq,
            OLD.contract_number, OLD.status, NEW.status USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_installment_transition
    BEFORE UPDATE OR DELETE ON payment.installment
    FOR EACH ROW EXECUTE FUNCTION payment.check_installment_transition();

-- The receipts -----------------------------------------------------------------------------------------------------
CREATE TABLE payment.payment_receipt
(
    id               UUID           NOT NULL,
    customer_id      UUID           NOT NULL,
    -- The bank statement line it records (15 BR-02, BR-03).
    reference        VARCHAR(100)   NOT NULL,
    received_on      DATE           NOT NULL,
    -- The sum of its lines (check_payment_receipt at commit).
    amount           NUMERIC(19, 4) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    note             VARCHAR(500),
    status           VARCHAR(16)    NOT NULL,
    recorded_by      UUID           NOT NULL,
    recorded_at      TIMESTAMPTZ    NOT NULL,
    voided_by        UUID,
    voided_at        TIMESTAMPTZ,
    void_reason      VARCHAR(500),

    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_by       VARCHAR(100),
    last_modified_at TIMESTAMPTZ,
    last_modified_by VARCHAR(100),

    CONSTRAINT pk_payment_receipt PRIMARY KEY (id),
    CONSTRAINT fk_payment_receipt_customer FOREIGN KEY (customer_id) REFERENCES customer.customer (id),
    CONSTRAINT fk_payment_receipt_recorded_by FOREIGN KEY (recorded_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_payment_receipt_voided_by FOREIGN KEY (voided_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_payment_receipt_reference CHECK (reference = btrim(reference) AND reference <> ''),
    CONSTRAINT ck_payment_receipt_amount CHECK (amount > 0 AND amount = TRUNC(amount)),
    CONSTRAINT ck_payment_receipt_note CHECK (note IS NULL OR btrim(note) <> ''),
    -- 15 §3: the day the money came, never after the day it was recorded.
    CONSTRAINT ck_payment_receipt_received CHECK (received_on <= (recorded_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::DATE),
    CONSTRAINT ck_payment_receipt_status CHECK (status IN ('RECORDED', 'VOIDED')),
    -- 15 BR-04: a voided receipt says who voided it, when and why.
    CONSTRAINT ck_payment_receipt_voided CHECK ((status = 'VOIDED') = (voided_at IS NOT NULL)
        AND (voided_at IS NULL) = (voided_by IS NULL) AND (voided_at IS NULL) = (void_reason IS NULL)
        AND (void_reason IS NULL OR btrim(void_reason) <> '') AND (voided_at IS NULL OR voided_at >= recorded_at))
);

-- 15 BR-03: one statement line, one live receipt; a voided receipt's reference is free for the corrected one.
CREATE UNIQUE INDEX uk_payment_receipt_reference_recorded ON payment.payment_receipt (reference)
    WHERE status = 'RECORDED';
CREATE INDEX ix_payment_receipt_customer ON payment.payment_receipt (customer_id, received_on DESC);

COMMENT ON TABLE payment.payment_receipt IS
    'Money the accountant matched to the bank statement and recorded against installments (kltn-docs 15 §4.2, §5.3): '
    'never edited; a wrong one is VOIDED with a reason and kept (BR-04).';

CREATE TABLE payment.payment_receipt_line
(
    id             UUID           NOT NULL,
    receipt_id     UUID           NOT NULL,
    installment_id UUID           NOT NULL,
    amount         NUMERIC(19, 4) NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_payment_receipt_line PRIMARY KEY (id),
    CONSTRAINT uk_payment_receipt_line UNIQUE (receipt_id, installment_id),
    CONSTRAINT fk_payment_receipt_line_receipt FOREIGN KEY (receipt_id) REFERENCES payment.payment_receipt (id),
    CONSTRAINT fk_payment_receipt_line_installment FOREIGN KEY (installment_id) REFERENCES payment.installment (id),
    CONSTRAINT ck_payment_receipt_line_amount CHECK (amount > 0 AND amount = TRUNC(amount))
);

CREATE INDEX ix_payment_receipt_line_installment ON payment.payment_receipt_line (installment_id);

-- BR-04: a receipt is never edited. Only RECORDED -> VOIDED, with who, when and why; nothing is deleted.
CREATE FUNCTION payment.check_payment_receipt_immutable() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'payment receipt %: receipts are kept (15 BR-04)', OLD.reference
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.customer_id IS DISTINCT FROM OLD.customer_id OR NEW.reference IS DISTINCT FROM OLD.reference
        OR NEW.received_on IS DISTINCT FROM OLD.received_on OR NEW.amount IS DISTINCT FROM OLD.amount
        OR NEW.currency IS DISTINCT FROM OLD.currency OR NEW.note IS DISTINCT FROM OLD.note
        OR NEW.recorded_by IS DISTINCT FROM OLD.recorded_by OR NEW.recorded_at IS DISTINCT FROM OLD.recorded_at THEN
        RAISE EXCEPTION 'payment receipt %: a receipt is not edited; void it and record the right one (15 BR-04)',
            OLD.reference USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status = 'VOIDED' AND (NEW.status IS DISTINCT FROM OLD.status OR NEW.voided_by IS DISTINCT FROM OLD.voided_by
                                  OR NEW.voided_at IS DISTINCT FROM OLD.voided_at
                                  OR NEW.void_reason IS DISTINCT FROM OLD.void_reason) THEN
        RAISE EXCEPTION 'payment receipt %: a voided receipt stays as it was voided', OLD.reference
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_payment_receipt_immutable
    BEFORE UPDATE OR DELETE ON payment.payment_receipt
    FOR EACH ROW EXECUTE FUNCTION payment.check_payment_receipt_immutable();

-- A line is written with its receipt and stays: for an installment of the receipt's customer, in its currency, that
-- is not CANCELLED (15 BR-05; edge "tiền về sau khi huỷ đơn").
CREATE FUNCTION payment.check_payment_receipt_line() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    receipt     RECORD;
    installment RECORD;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'payment receipt line %: receipt lines are kept as recorded (15 BR-04)', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    SELECT r.status, r.customer_id, r.currency, r.reference INTO receipt
      FROM payment.payment_receipt r WHERE r.id = NEW.receipt_id;
    SELECT i.status, i.customer_id, i.currency, i.seq, i.contract_number INTO installment
      FROM payment.installment i WHERE i.id = NEW.installment_id;
    IF receipt.status <> 'RECORDED' THEN
        RAISE EXCEPTION 'payment receipt %: a voided receipt takes no line', receipt.reference
            USING ERRCODE = 'check_violation';
    END IF;
    IF installment.status = 'CANCELLED' THEN
        RAISE EXCEPTION 'payment receipt %: installment % of contract % is CANCELLED (15 BR-05)', receipt.reference,
            installment.seq, installment.contract_number USING ERRCODE = 'check_violation';
    END IF;
    IF installment.customer_id <> receipt.customer_id OR installment.currency <> receipt.currency THEN
        RAISE EXCEPTION 'payment receipt %: installment % of contract % is another customer''s, or in another currency '
            '(15 BR-05)', receipt.reference, installment.seq, installment.contract_number
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tg_payment_receipt_line
    BEFORE INSERT OR UPDATE OR DELETE ON payment.payment_receipt_line
    FOR EACH ROW EXECUTE FUNCTION payment.check_payment_receipt_line();

-- At commit: a receipt has lines and is their sum.
CREATE FUNCTION payment.assert_payment_receipt(receipt UUID) RETURNS VOID
    LANGUAGE plpgsql AS
$$
DECLARE
    total     NUMERIC;
    lines     BIGINT;
    recorded  NUMERIC;
    reference VARCHAR;
BEGIN
    SELECT r.amount, r.reference INTO recorded, reference FROM payment.payment_receipt r WHERE r.id = receipt;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    SELECT COALESCE(SUM(l.amount), 0), COUNT(*) INTO total, lines
      FROM payment.payment_receipt_line l WHERE l.receipt_id = receipt;
    IF lines = 0 OR total <> recorded THEN
        RAISE EXCEPTION 'payment receipt %: % recorded, its % line(s) add up to % (15 §3)', reference, recorded, lines,
            total USING ERRCODE = 'check_violation';
    END IF;
END;
$$;

-- At commit: an installment's paid amount is what its lines on RECORDED receipts pay.
CREATE FUNCTION payment.assert_installment_paid(installment UUID) RETURNS VOID
    LANGUAGE plpgsql AS
$$
DECLARE
    paid   NUMERIC;
    held   NUMERIC;
    seq    SMALLINT;
    number VARCHAR;
BEGIN
    SELECT i.paid_amount, i.seq, i.contract_number INTO held, seq, number FROM payment.installment i
     WHERE i.id = installment;
    IF NOT FOUND THEN
        RETURN;
    END IF;
    SELECT COALESCE(SUM(l.amount), 0) INTO paid
      FROM payment.payment_receipt_line l
      JOIN payment.payment_receipt r ON r.id = l.receipt_id
     WHERE l.installment_id = installment AND r.status = 'RECORDED';
    IF paid <> held THEN
        RAISE EXCEPTION 'installment % of contract %: paid % on record, its recorded receipts pay % (15 §4.2 step 7)',
            seq, number, held, paid USING ERRCODE = 'check_violation';
    END IF;
END;
$$;

CREATE FUNCTION payment.check_installment_paid() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    PERFORM payment.assert_installment_paid(NEW.id);
    RETURN NULL;
END;
$$;

CREATE FUNCTION payment.check_payment_receipt() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
DECLARE
    line RECORD;
BEGIN
    PERFORM payment.assert_payment_receipt(NEW.id);
    -- Voided, its lines no longer pay their installments: each must have been put back.
    FOR line IN SELECT l.installment_id FROM payment.payment_receipt_line l WHERE l.receipt_id = NEW.id LOOP
        PERFORM payment.assert_installment_paid(line.installment_id);
    END LOOP;
    RETURN NULL;
END;
$$;

CREATE FUNCTION payment.check_payment_receipt_line_totals() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    PERFORM payment.assert_payment_receipt(NEW.receipt_id);
    PERFORM payment.assert_installment_paid(NEW.installment_id);
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER tg_installment_paid
    AFTER INSERT OR UPDATE OF paid_amount ON payment.installment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payment.check_installment_paid();

CREATE CONSTRAINT TRIGGER tg_payment_receipt_totals
    AFTER INSERT OR UPDATE OF status ON payment.payment_receipt
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payment.check_payment_receipt();

CREATE CONSTRAINT TRIGGER tg_payment_receipt_line_totals
    AFTER INSERT ON payment.payment_receipt_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION payment.check_payment_receipt_line_totals();

-- Contracts signed before this migration (4B-5) get their installments, as a signature makes them now: the one on
-- signing is due from the signature; the others wait for their milestone; a cancelled order's are CANCELLED. None was
-- paid: no receipt existed.
INSERT INTO payment.installment (id, contract_id, contract_installment_id, order_id, customer_id, contract_number,
                                 order_number, seq, name, milestone, delivery_batch_id, delivery_batch_seq, amount,
                                 paid_amount, currency, due_days, reached_at, due_date, status, paid_at, cancelled_at,
                                 version, created_at, created_by)
SELECT gen_random_uuid(), c.id, p.id, o.id, o.customer_id, c.contract_number, o.order_number, p.seq, p.name,
       p.milestone, p.delivery_batch_id, b.seq, p.amount, 0, o.currency, p.due_days,
       CASE WHEN p.milestone = 'ON_SIGNING' THEN c.signed_at END,
       CASE WHEN p.milestone = 'ON_SIGNING' THEN c.signed_on + p.due_days END,
       CASE WHEN o.status = 'CANCELLED' THEN 'CANCELLED'
            WHEN p.milestone = 'ON_SIGNING' THEN 'DUE'
            ELSE 'NOT_DUE' END,
       NULL,
       CASE WHEN o.status = 'CANCELLED' THEN COALESCE(o.last_modified_at, NOW()) END,
       0, NOW(), 'flyway'
  FROM ordering.sales_contract c
  JOIN ordering.contract_payment_installment p ON p.contract_id = c.id
  JOIN ordering.customer_order o ON o.id = c.order_id
  LEFT JOIN ordering.delivery_batch b ON b.id = p.delivery_batch_id
 WHERE c.status IN ('SIGNED', 'COMPLETED');

-- Permissions ------------------------------------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
SELECT gen_random_uuid(), r.resource || ':' || a.action, r.resource, a.action, 0, NOW(), 'flyway'
FROM (VALUES
          ('payment-installments', 'VIEW_PAGE,READ'),
          ('payment-receipts',     'VIEW_PAGE,READ,CREATE,UPDATE')
     ) AS r(resource, actions)
CROSS JOIN LATERAL unnest(string_to_array(r.actions, ',')) AS a(action)
ON CONFLICT (code) DO NOTHING;

WITH wanted (role_code, resource, actions) AS (
    VALUES
        -- 15 §2: the accountant records and voids receipts against the installments, and follows what is owed.
        ('ACCOUNTANT',        'payment-installments', 'VIEW_PAGE,READ'),
        ('ACCOUNTANT',        'payment-receipts',     'VIEW_PAGE,READ,CREATE,UPDATE'),
        -- Sales sends each installment's transfer details and reminds the customer (15 §2, 16).
        ('SALES_STAFF',       'payment-installments', 'VIEW_PAGE,READ'),
        ('SALES_MANAGER',     'payment-installments', 'VIEW_PAGE,READ'),
        ('ORDER_COORDINATOR', 'payment-installments', 'READ'),
        -- 15 §2: a customer sees their installments and how to pay them (scope OWN on the endpoint).
        ('CUSTOMER',          'payment-installments', 'READ'),
        ('SYSTEM_ADMIN',      'payment-installments', 'VIEW_PAGE,READ'),
        ('SYSTEM_ADMIN',      'payment-receipts',     'VIEW_PAGE,READ,CREATE,UPDATE')
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
