-- =============================================================================
-- The sales contract of an order, its delivery schedule and its draft payment plan (kltn-docs 14 §4.3 steps 11-17,
-- §5.2, BR-12, BR-13, BR-14, BR-18, BR-23; 18 §3, BR-05; QD-14; team plan 4B-4).
--
--   * customer.customer_legal_profile - what a contract names the buyer by beyond its name and tax code (18 §3): the
--     registered business address, the legal representative and their title. Required before a contract is drawn
--     up (18 BR-05); the contract copies it, so a later change leaves contracts already drawn up as they were.
--   * ordering.sales_contract - one live contract per order (BR-12: every status but CANCELLED), drawn up by Sales for
--     a DRAFT order. Its goods, prices, fees, taxes and totals are the order's and are never stored twice; the
--     parties are copied when it is drawn up. DRAFT -> SENT -> SIGNED -> COMPLETED, DRAFT / SENT -> CANCELLED (with a
--     reason). SENT keeps the PDF the customer was sent; SIGNED needs the signed file and the day it was signed
--     (BR-14). Only a DRAFT changes (BR-14).
--   * ordering.delivery_batch / delivery_batch_line - the delivery schedule (BR-23): batches numbered 1..n, each a
--     delivery window (from <= to; null on a draft until Sales enters it) and cartons of the order's lines. For every
--     line the cartons over the batches add up to the line; a batch starts no earlier than the one before. All go to
--     the order's one address (D38).
--   * ordering.contract_payment_installment - the payment plan (BR-13, 15 BR-18): installments numbered 1..n, each an
--     amount in whole VND (the order's total is whole dong) and one milestone, adding up to the order's total with tax.
--     A share Sales enters is rounded to the dong and the last installment takes what is left. BEFORE_DELIVERY and
--     AFTER_DELIVERY may name one delivery batch; ON_SIGNING never does. AFTER_DELIVERY needs credit accounting
--     approved (15 BR-12). 4B-6 turns the plan of a signed contract into payment installments to collect.
--
-- The schedule and plan rules are checked when the transaction commits: the application replaces a draft's batches,
-- lines and installments together, and a half-written schedule is never seen by anyone. Every row written queues a
-- check of its whole contract, so a contract has at most 20 batches and 20 installments: a draft rewritten whole is
-- then a few dozen small checks at commit.
-- =============================================================================

-- The buyer's legal profile -----------------------------------------------------------------------------------------
CREATE TABLE customer.customer_legal_profile
(
    id                   UUID         NOT NULL,
    customer_id          UUID         NOT NULL,
    registered_address   VARCHAR(500) NOT NULL,
    representative_name  VARCHAR(200) NOT NULL,
    representative_title VARCHAR(100) NOT NULL,

    version              BIGINT       NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by           VARCHAR(100),
    last_modified_at     TIMESTAMPTZ,
    last_modified_by     VARCHAR(100),

    CONSTRAINT pk_customer_legal_profile PRIMARY KEY (id),
    CONSTRAINT uk_customer_legal_profile_customer UNIQUE (customer_id),
    CONSTRAINT fk_customer_legal_profile_customer FOREIGN KEY (customer_id) REFERENCES customer.customer (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_customer_legal_profile_filled CHECK (btrim(registered_address) <> '' AND btrim(representative_name) <> ''
        AND btrim(representative_title) <> '')
);

COMMENT ON TABLE customer.customer_legal_profile IS
    '4B-4 (18 §3, BR-05; QD-14): registered business address and legal representative, required before a sales contract.';

-- 18 §6: a change of the legal representative (or the address, or the title) is kept like a change of the company
-- name: before, after, who, when. An address is longer than a name.
ALTER TABLE customer.customer_change_history
    ALTER COLUMN field TYPE VARCHAR(32),
    ALTER COLUMN old_value TYPE VARCHAR(500),
    ALTER COLUMN new_value TYPE VARCHAR(500),
    DROP CONSTRAINT ck_customer_change_history_field,
    ADD CONSTRAINT ck_customer_change_history_field CHECK (field IN ('FULL_NAME', 'TAX_CODE', 'REGISTERED_ADDRESS',
                                                                     'REPRESENTATIVE', 'REPRESENTATIVE_TITLE'));

COMMENT ON TABLE customer.customer_change_history IS
    'Every change of a customer''s company name, tax code and legal profile: before, after, who, when (decision '
    '2026-10-17; 18 §6).';

-- The contract ------------------------------------------------------------------------------------------------------
CREATE TABLE ordering.sales_contract
(
    id                         UUID         NOT NULL,
    contract_number            VARCHAR(40)  NOT NULL,
    order_id                   UUID         NOT NULL,
    status                     VARCHAR(16)  NOT NULL,

    -- The parties as they were when it was drawn up (14 §4.3 step 11).
    seller_name                VARCHAR(200) NOT NULL,
    seller_tax_code            VARCHAR(14)  NOT NULL,
    seller_address             VARCHAR(500) NOT NULL,
    seller_representative      VARCHAR(200) NOT NULL,
    seller_representative_title VARCHAR(100) NOT NULL,
    buyer_name                 VARCHAR(200) NOT NULL,
    buyer_tax_code             VARCHAR(14),
    buyer_address              VARCHAR(500) NOT NULL,
    buyer_representative       VARCHAR(200) NOT NULL,
    buyer_representative_title VARCHAR(100) NOT NULL,

    -- BR-18: print-sample rounds without charge.
    free_sample_rounds         INTEGER      NOT NULL,

    sent_at                    TIMESTAMPTZ,
    sent_by                    UUID,
    -- The PDF the customer was sent (FileCategory.DOCUMENT).
    sent_document_key          VARCHAR(512),

    signed_on                  DATE,
    signed_file_key            VARCHAR(512),
    signed_file_name           VARCHAR(255),
    signed_at                  TIMESTAMPTZ,
    signed_by                  UUID,

    cancelled_at               TIMESTAMPTZ,
    cancelled_by               UUID,
    cancellation_reason        VARCHAR(500),

    completed_at               TIMESTAMPTZ,

    version                    BIGINT       NOT NULL DEFAULT 0,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by                 VARCHAR(100),
    last_modified_at           TIMESTAMPTZ,
    last_modified_by           VARCHAR(100),

    CONSTRAINT pk_sales_contract PRIMARY KEY (id),
    CONSTRAINT uk_sales_contract_number UNIQUE (contract_number),
    CONSTRAINT fk_sales_contract_order FOREIGN KEY (order_id) REFERENCES ordering.customer_order (id),
    CONSTRAINT fk_sales_contract_sent_by FOREIGN KEY (sent_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_sales_contract_signed_by FOREIGN KEY (signed_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_sales_contract_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_sales_contract_status CHECK (status IN ('DRAFT', 'SENT', 'SIGNED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_sales_contract_free_sample_rounds CHECK (free_sample_rounds BETWEEN 0 AND 100),
    -- Sent: who, when and the PDF. A draft was never sent; a cancelled contract may have been.
    CONSTRAINT ck_sales_contract_sent CHECK ((sent_at IS NULL) = (sent_by IS NULL)
        AND (sent_at IS NULL) = (sent_document_key IS NULL)
        AND (status <> 'DRAFT' OR sent_at IS NULL)
        AND (status NOT IN ('SENT', 'SIGNED', 'COMPLETED') OR sent_at IS NOT NULL)),
    -- BR-14: signed with the signed file and the day it was signed, not later than when it was recorded; a signed
    -- contract is never cancelled.
    CONSTRAINT ck_sales_contract_signed CHECK ((status IN ('SIGNED', 'COMPLETED')) = (signed_at IS NOT NULL)
        AND (signed_at IS NULL) = (signed_by IS NULL)
        AND (signed_at IS NULL) = (signed_on IS NULL)
        AND (signed_at IS NULL) = (signed_file_key IS NULL)
        AND (signed_at IS NULL) = (signed_file_name IS NULL)
        AND (signed_on IS NULL OR signed_on <= (signed_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date)),
    CONSTRAINT ck_sales_contract_cancelled CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)
        AND (cancelled_at IS NULL) = (cancelled_by IS NULL)
        AND (cancelled_at IS NULL) = (cancellation_reason IS NULL)
        AND (cancellation_reason IS NULL OR btrim(cancellation_reason) <> '')),
    CONSTRAINT ck_sales_contract_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

-- BR-12: one live contract per order; a cancelled one is replaced by a new one for the same order.
CREATE UNIQUE INDEX uk_sales_contract_order_live ON ordering.sales_contract (order_id) WHERE status <> 'CANCELLED';
CREATE INDEX ix_sales_contract_order ON ordering.sales_contract (order_id);
CREATE INDEX ix_sales_contract_sent_by ON ordering.sales_contract (sent_by) WHERE sent_by IS NOT NULL;
CREATE INDEX ix_sales_contract_signed_by ON ordering.sales_contract (signed_by) WHERE signed_by IS NOT NULL;
CREATE INDEX ix_sales_contract_cancelled_by ON ordering.sales_contract (cancelled_by) WHERE cancelled_by IS NOT NULL;

COMMENT ON TABLE ordering.sales_contract IS
    '4B-4 (14 §4.3, §5.2, BR-12, BR-14, BR-18): the sales contract of an order; goods, prices and totals are the order''s.';

-- 14 §5.2: the contract's state machine; BR-14: what a contract past DRAFT keeps, whatever the statement also does -
-- a transition included. Each transition sets only its own columns: SENT the sending, SIGNED the signature, CANCELLED
-- the cancellation, COMPLETED its day.
CREATE FUNCTION ordering.check_sales_contract_transition()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.order_id IS DISTINCT FROM OLD.order_id OR NEW.contract_number IS DISTINCT FROM OLD.contract_number THEN
        RAISE EXCEPTION 'sales contract %: a contract keeps its number and stays with its order', OLD.contract_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status <> 'DRAFT' AND (NEW.seller_name, NEW.seller_tax_code, NEW.seller_address, NEW.seller_representative,
            NEW.seller_representative_title, NEW.buyer_name, NEW.buyer_tax_code, NEW.buyer_address,
            NEW.buyer_representative, NEW.buyer_representative_title, NEW.free_sample_rounds, NEW.sent_at,
            NEW.sent_by, NEW.sent_document_key)
        IS DISTINCT FROM (OLD.seller_name, OLD.seller_tax_code, OLD.seller_address, OLD.seller_representative,
            OLD.seller_representative_title, OLD.buyer_name, OLD.buyer_tax_code, OLD.buyer_address,
            OLD.buyer_representative, OLD.buyer_representative_title, OLD.free_sample_rounds, OLD.sent_at,
            OLD.sent_by, OLD.sent_document_key)
    THEN
        RAISE EXCEPTION 'sales contract %: a % contract does not change (14 BR-14)', OLD.contract_number,
            OLD.status USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status IN ('SIGNED', 'COMPLETED') AND (NEW.signed_on, NEW.signed_file_key, NEW.signed_file_name,
            NEW.signed_at, NEW.signed_by)
        IS DISTINCT FROM (OLD.signed_on, OLD.signed_file_key, OLD.signed_file_name, OLD.signed_at, OLD.signed_by)
    THEN
        RAISE EXCEPTION 'sales contract %: a % contract does not change (14 BR-14)', OLD.contract_number,
            OLD.status USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status IN ('CANCELLED', 'COMPLETED') AND (NEW.cancelled_at, NEW.cancelled_by, NEW.cancellation_reason,
            NEW.completed_at)
        IS DISTINCT FROM (OLD.cancelled_at, OLD.cancelled_by, OLD.cancellation_reason, OLD.completed_at)
    THEN
        RAISE EXCEPTION 'sales contract %: a % contract does not change', OLD.contract_number, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    IF NOT ((OLD.status = 'DRAFT' AND NEW.status IN ('SENT', 'CANCELLED'))
         OR (OLD.status = 'SENT' AND NEW.status IN ('SIGNED', 'CANCELLED'))
         OR (OLD.status = 'SIGNED' AND NEW.status = 'COMPLETED')) THEN
        RAISE EXCEPTION 'sales contract %: % -> % is not a transition (14 §5.2)', OLD.contract_number, OLD.status,
            NEW.status USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_sales_contract_transition
    BEFORE UPDATE ON ordering.sales_contract
    FOR EACH ROW EXECUTE FUNCTION ordering.check_sales_contract_transition();

-- The delivery schedule --------------------------------------------------------------------------------------------
CREATE TABLE ordering.delivery_batch
(
    id           UUID     NOT NULL,
    contract_id  UUID     NOT NULL,
    seq          SMALLINT NOT NULL,
    window_start DATE,
    window_end   DATE,

    CONSTRAINT pk_delivery_batch PRIMARY KEY (id),
    CONSTRAINT uk_delivery_batch_seq UNIQUE (contract_id, seq),
    CONSTRAINT fk_delivery_batch_contract FOREIGN KEY (contract_id) REFERENCES ordering.sales_contract (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_delivery_batch_seq CHECK (seq BETWEEN 1 AND 20),
    CONSTRAINT ck_delivery_batch_window CHECK ((window_start IS NULL) = (window_end IS NULL)
        AND (window_start IS NULL OR window_start <= window_end))
);

COMMENT ON TABLE ordering.delivery_batch IS
    '4B-4 (14 BR-23): a delivery batch of a contract''s schedule - its window and, in delivery_batch_line, its cartons.';

CREATE TABLE ordering.delivery_batch_line
(
    id                UUID    NOT NULL,
    delivery_batch_id UUID    NOT NULL,
    order_line_id     UUID    NOT NULL,
    cartons           INTEGER NOT NULL,

    CONSTRAINT pk_delivery_batch_line PRIMARY KEY (id),
    CONSTRAINT uk_delivery_batch_line UNIQUE (delivery_batch_id, order_line_id),
    CONSTRAINT fk_delivery_batch_line_batch FOREIGN KEY (delivery_batch_id) REFERENCES ordering.delivery_batch (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_delivery_batch_line_order_line FOREIGN KEY (order_line_id) REFERENCES ordering.order_line (id),
    CONSTRAINT ck_delivery_batch_line_cartons CHECK (cartons BETWEEN 1 AND 10000000)
);

CREATE INDEX ix_delivery_batch_line_order_line ON ordering.delivery_batch_line (order_line_id);

-- The payment plan -------------------------------------------------------------------------------------------------
CREATE TABLE ordering.contract_payment_installment
(
    id                UUID           NOT NULL,
    contract_id       UUID           NOT NULL,
    seq               SMALLINT       NOT NULL,
    name              VARCHAR(100)   NOT NULL,
    milestone         VARCHAR(16)    NOT NULL,
    -- The share Sales entered, when entered as a share; the amount is what counts.
    percent           NUMERIC(5, 2),
    amount            NUMERIC(19, 4) NOT NULL,
    -- Days after the milestone it falls due; BEFORE_DELIVERY has no due day: it blocks the handover until paid.
    due_days          INTEGER,
    delivery_batch_id UUID,

    CONSTRAINT pk_contract_payment_installment PRIMARY KEY (id),
    CONSTRAINT uk_contract_payment_installment_seq UNIQUE (contract_id, seq),
    CONSTRAINT fk_contract_payment_installment_contract FOREIGN KEY (contract_id)
        REFERENCES ordering.sales_contract (id) ON DELETE CASCADE,
    CONSTRAINT fk_contract_payment_installment_batch FOREIGN KEY (delivery_batch_id)
        REFERENCES ordering.delivery_batch (id) ON DELETE CASCADE,
    CONSTRAINT ck_contract_payment_installment_seq CHECK (seq BETWEEN 1 AND 20),
    CONSTRAINT ck_contract_payment_installment_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_contract_payment_installment_milestone CHECK (milestone IN ('ON_SIGNING', 'BEFORE_DELIVERY',
                                                                            'AFTER_DELIVERY')),
    CONSTRAINT ck_contract_payment_installment_amount CHECK (amount > 0 AND amount = TRUNC(amount)),
    CONSTRAINT ck_contract_payment_installment_percent CHECK (percent IS NULL OR (percent > 0 AND percent <= 100)),
    CONSTRAINT ck_contract_payment_installment_due CHECK ((milestone = 'BEFORE_DELIVERY') = (due_days IS NULL)
        AND (due_days IS NULL OR due_days BETWEEN 0 AND 365)),
    CONSTRAINT ck_contract_payment_installment_batch CHECK (milestone <> 'ON_SIGNING' OR delivery_batch_id IS NULL)
);

CREATE INDEX ix_contract_payment_installment_batch ON ordering.contract_payment_installment (delivery_batch_id)
    WHERE delivery_batch_id IS NOT NULL;

COMMENT ON TABLE ordering.contract_payment_installment IS
    '4B-4 (14 BR-13, 15 BR-18): the payment plan of a contract, adding up to the order''s total with tax.';

-- The rules over a whole contract, at commit ----------------------------------------------------------------------
CREATE FUNCTION ordering.check_sales_contract_terms()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    the_contract UUID;
    contract     RECORD;
    order_total  NUMERIC;
    planned      NUMERIC;
    batches      INTEGER;
    installments INTEGER;
    bad          TEXT;
    credit_ok    BOOLEAN;
    sending      BOOLEAN := false;
BEGIN
    IF TG_TABLE_NAME = 'sales_contract' THEN
        the_contract := NEW.id;
        -- Only the contract's own row has a status: PL/pgSQL does not stop at the first false operand.
        IF TG_OP = 'UPDATE' THEN
            sending := OLD.status = 'DRAFT' AND NEW.status = 'SENT';
        END IF;
    ELSIF TG_TABLE_NAME = 'delivery_batch_line' THEN
        SELECT b.contract_id INTO the_contract FROM ordering.delivery_batch b
         WHERE b.id = CASE WHEN TG_OP = 'DELETE' THEN OLD.delivery_batch_id ELSE NEW.delivery_batch_id END;
    ELSE
        the_contract := CASE WHEN TG_OP = 'DELETE' THEN OLD.contract_id ELSE NEW.contract_id END;
    END IF;
    SELECT c.id, c.contract_number, c.order_id, c.status INTO contract
      FROM ordering.sales_contract c WHERE c.id = the_contract;
    IF NOT FOUND OR contract.status = 'CANCELLED' THEN
        -- Gone with its contract, or cancelled: a cancelled contract binds nobody.
        RETURN NULL;
    END IF;

    -- BR-23: batches 1..n, each with cartons; the cartons of every order line over the batches make the line; a batch
    -- starts no earlier than the one before.
    SELECT count(*) INTO batches FROM ordering.delivery_batch b WHERE b.contract_id = the_contract;
    IF batches = 0 OR batches <> (SELECT max(seq) FROM ordering.delivery_batch WHERE contract_id = the_contract) THEN
        RAISE EXCEPTION 'sales contract %: the delivery batches are numbered 1 to n, at least one (14 BR-23)',
            contract.contract_number USING ERRCODE = 'check_violation';
    END IF;
    SELECT string_agg(b.seq::text, ', ') INTO bad FROM ordering.delivery_batch b
     WHERE b.contract_id = the_contract
       AND NOT EXISTS (SELECT 1 FROM ordering.delivery_batch_line l WHERE l.delivery_batch_id = b.id);
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION 'sales contract %: delivery batch % carries no cartons (14 BR-23)', contract.contract_number,
            bad USING ERRCODE = 'check_violation';
    END IF;
    SELECT string_agg(ol.sku, ', ') INTO bad
      FROM ordering.order_line ol
      LEFT JOIN (SELECT l.order_line_id, sum(l.cartons) AS cartons
                   FROM ordering.delivery_batch_line l
                   JOIN ordering.delivery_batch b ON b.id = l.delivery_batch_id
                  WHERE b.contract_id = the_contract
                  GROUP BY l.order_line_id) s ON s.order_line_id = ol.id
     WHERE ol.order_id = contract.order_id
       AND ol.cartons IS DISTINCT FROM s.cartons;
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION 'sales contract %: the cartons over the batches are not the order''s for % (14 BR-23)',
            contract.contract_number, bad USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM ordering.delivery_batch_line l
                 JOIN ordering.delivery_batch b ON b.id = l.delivery_batch_id
                 JOIN ordering.order_line ol ON ol.id = l.order_line_id
                WHERE b.contract_id = the_contract AND ol.order_id <> contract.order_id) THEN
        RAISE EXCEPTION 'sales contract %: a delivery batch names a line of another order', contract.contract_number
            USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM ordering.delivery_batch b
                 JOIN ordering.delivery_batch p ON p.contract_id = b.contract_id AND p.seq = b.seq - 1
                WHERE b.contract_id = the_contract AND b.window_start < p.window_start) THEN
        RAISE EXCEPTION 'sales contract %: a delivery batch starts before the one before it (14 BR-23)',
            contract.contract_number USING ERRCODE = 'check_violation';
    END IF;
    IF contract.status <> 'DRAFT' AND EXISTS (SELECT 1 FROM ordering.delivery_batch b
                                                WHERE b.contract_id = the_contract AND b.window_start IS NULL) THEN
        RAISE EXCEPTION 'sales contract %: every delivery batch has its window once sent (14 BR-23)',
            contract.contract_number USING ERRCODE = 'check_violation';
    END IF;

    -- BR-13: installments 1..n adding up to the order's total with tax; a batch named is one of this contract's.
    SELECT count(*), coalesce(sum(i.amount), 0) INTO installments, planned
      FROM ordering.contract_payment_installment i WHERE i.contract_id = the_contract;
    SELECT o.total_amount INTO order_total FROM ordering.customer_order o WHERE o.id = contract.order_id;
    IF installments = 0
        OR installments <> (SELECT max(seq) FROM ordering.contract_payment_installment WHERE contract_id = the_contract)
    THEN
        RAISE EXCEPTION 'sales contract %: the installments are numbered 1 to n, at least one (14 BR-13)',
            contract.contract_number USING ERRCODE = 'check_violation';
    END IF;
    IF planned <> order_total THEN
        RAISE EXCEPTION 'sales contract %: the installments add up to %, the order''s total with tax is % (14 BR-13)',
            contract.contract_number, planned, order_total USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (SELECT 1 FROM ordering.contract_payment_installment i
                 JOIN ordering.delivery_batch b ON b.id = i.delivery_batch_id
                WHERE i.contract_id = the_contract AND b.contract_id <> the_contract) THEN
        RAISE EXCEPTION 'sales contract %: an installment names a delivery batch of another contract',
            contract.contract_number USING ERRCODE = 'check_violation';
    END IF;
    -- 15 BR-12: paying after delivery is credit, and only accounting grants credit. Checked while the plan can change,
    -- and when the contract is sent: the customer is offered no credit accounting took back meanwhile.
    IF (contract.status = 'DRAFT' OR sending)
        AND EXISTS (SELECT 1 FROM ordering.contract_payment_installment i
                     WHERE i.contract_id = the_contract AND i.milestone = 'AFTER_DELIVERY')
    THEN
        SELECT bool_or(cp.allow_credit AND cp.credit_limit > 0) INTO credit_ok
          FROM (SELECT p.allow_credit, p.credit_limit FROM customer.credit_profile p
                  JOIN ordering.customer_order o ON o.customer_id = p.customer_id
                 WHERE o.id = contract.order_id FOR SHARE OF p) AS cp;
        IF NOT coalesce(credit_ok, false) THEN
            RAISE EXCEPTION 'sales contract %: an installment after delivery needs credit accounting approved (15 BR-12)',
                contract.contract_number USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_sales_contract_terms
    AFTER INSERT OR UPDATE ON ordering.sales_contract
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ordering.check_sales_contract_terms();

CREATE CONSTRAINT TRIGGER tg_delivery_batch_terms
    AFTER INSERT OR UPDATE OR DELETE ON ordering.delivery_batch
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ordering.check_sales_contract_terms();

CREATE CONSTRAINT TRIGGER tg_delivery_batch_line_terms
    AFTER INSERT OR UPDATE OR DELETE ON ordering.delivery_batch_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ordering.check_sales_contract_terms();

CREATE CONSTRAINT TRIGGER tg_contract_payment_installment_terms
    AFTER INSERT OR UPDATE OR DELETE ON ordering.contract_payment_installment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ordering.check_sales_contract_terms();

-- BR-14: a contract's schedule and plan change only while it is a draft.
CREATE FUNCTION ordering.check_sales_contract_draft()
    RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    the_contract UUID;
    the_status   VARCHAR(16);
    the_number   VARCHAR(40);
BEGIN
    IF TG_TABLE_NAME = 'delivery_batch_line' THEN
        SELECT b.contract_id INTO the_contract FROM ordering.delivery_batch b
         WHERE b.id = CASE WHEN TG_OP = 'DELETE' THEN OLD.delivery_batch_id ELSE NEW.delivery_batch_id END;
    ELSE
        the_contract := CASE WHEN TG_OP = 'DELETE' THEN OLD.contract_id ELSE NEW.contract_id END;
    END IF;
    SELECT c.status, c.contract_number INTO the_status, the_number
      FROM ordering.sales_contract c WHERE c.id = the_contract;
    IF FOUND AND the_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'sales contract %: the schedule and payment plan of a % contract do not change (14 BR-14)',
            the_number, the_status USING ERRCODE = 'check_violation';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$$;

CREATE TRIGGER tg_delivery_batch_draft
    BEFORE INSERT OR UPDATE OR DELETE ON ordering.delivery_batch
    FOR EACH ROW EXECUTE FUNCTION ordering.check_sales_contract_draft();

CREATE TRIGGER tg_delivery_batch_line_draft
    BEFORE INSERT OR UPDATE OR DELETE ON ordering.delivery_batch_line
    FOR EACH ROW EXECUTE FUNCTION ordering.check_sales_contract_draft();

CREATE TRIGGER tg_contract_payment_installment_draft
    BEFORE INSERT OR UPDATE OR DELETE ON ordering.contract_payment_installment
    FOR EACH ROW EXECUTE FUNCTION ordering.check_sales_contract_draft();

-- Who works on contracts ----------------------------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
VALUES (gen_random_uuid(), 'sales-contracts:READ', 'sales-contracts', 'READ', 0, NOW(), 'flyway'),
       (gen_random_uuid(), 'sales-contracts:CREATE', 'sales-contracts', 'CREATE', 0, NOW(), 'flyway'),
       (gen_random_uuid(), 'sales-contracts:UPDATE', 'sales-contracts', 'UPDATE', 0, NOW(), 'flyway'),
       (gen_random_uuid(), 'sales-contracts:APPROVE', 'sales-contracts', 'APPROVE', 0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH wanted (role_code, permission_code) AS (
    VALUES ('SALES_STAFF', 'sales-contracts:READ'),
           ('SALES_STAFF', 'sales-contracts:CREATE'),
           ('SALES_STAFF', 'sales-contracts:UPDATE'),
           ('SALES_STAFF', 'sales-contracts:APPROVE'),
           ('SALES_MANAGER', 'sales-contracts:READ'),
           ('ACCOUNTANT', 'sales-contracts:READ'),
           ('SYSTEM_ADMIN', 'sales-contracts:READ'),
           ('SYSTEM_ADMIN', 'sales-contracts:CREATE'),
           ('SYSTEM_ADMIN', 'sales-contracts:UPDATE'),
           ('SYSTEM_ADMIN', 'sales-contracts:APPROVE')
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
