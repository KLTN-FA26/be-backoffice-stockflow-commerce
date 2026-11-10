-- 4B-10: orders, quotes and customers lose their own payment terms; the code stops mapping those columns.
--
-- Why. Since 4B-6 a customer pays by the installments of a signed sales contract (kltn-docs 15 BR-01, 06/11 "phiếu thu
-- theo đợt thay sổ cái"); 4B-10 removes the money ledger and the per-order terms from the code (prepaid / deposit /
-- credit, the deposit share and balance policy). The columns stay until 4B-11 drops them. Unmapped, a column takes its
-- default on every new row, so this migration makes sure every default still passes the table's rules:
--
--   1. ordering.quote.payment_term is NOT NULL without a default: it gets 'PREPAID', the neutral value
--      ck_quote_payment_term and ck_quote_deposit accept (deposit_percent stays NULL).
--   2. customer.credit_profile: the credit is the limit and the days to pay alone. ck_credit_profile_credit tied them
--      to allow_credit, which now stays at its default (false) - it is restated on the limit and the days only. The
--      other term CHECKs hold on the defaults (allow_prepaid true, default_payment_term 'PREPAID', no deposit).
--   3. customer.check_default_payment_schedule() and ordering.check_sales_contract_terms() asked for
--      "allow_credit AND credit_limit > 0" before an installment after delivery: they ask for the limit only, as the
--      application does (CreditCheck.approvedCredit, SalesContractService).
--
-- customer_order needs nothing: payment_term defaults to 'PREPAID', the money columns to 0 and the deposit, balance and
-- credit-days columns to NULL, which every ck_order_* accepts; an order is confirmed through its contract
-- (ck_order_confirmed_basis: contract_signed_at). Kept on purpose: the columns themselves and their data (4B-11).

-- 1. The quote's payment term ------------------------------------------------------------------------------------------
ALTER TABLE ordering.quote ALTER COLUMN payment_term SET DEFAULT 'PREPAID';

-- 2. Credit is the limit and the days to pay ---------------------------------------------------------------------------
ALTER TABLE customer.credit_profile DROP CONSTRAINT ck_credit_profile_credit;
ALTER TABLE customer.credit_profile ADD CONSTRAINT ck_credit_profile_credit CHECK (
    (credit_limit IS NULL) = (credit_term_days IS NULL)
    AND (credit_limit IS NULL OR credit_limit >= 0)
    AND (credit_term_days IS NULL OR credit_term_days BETWEEN 0 AND 365));

-- 3. Credit approved = a limit above zero ------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION customer.check_default_payment_schedule()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
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
            SELECT bool_or(credit_limit > 0) INTO credit_ok
              FROM (SELECT credit_limit FROM customer.credit_profile
                     WHERE customer_id = the_customer FOR SHARE) AS terms;
            IF NOT coalesce(credit_ok, false) THEN
                RAISE EXCEPTION 'The default payment schedule of customer % pays after delivery without approved credit',
                    the_customer USING ERRCODE = 'check_violation';
            END IF;
        END IF;
    END LOOP;
    RETURN NULL;
END;
$function$;

CREATE OR REPLACE FUNCTION ordering.check_sales_contract_terms()
 RETURNS trigger
 LANGUAGE plpgsql
AS $function$
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
        SELECT bool_or(cp.credit_limit > 0) INTO credit_ok
          FROM (SELECT p.credit_limit FROM customer.credit_profile p
                  JOIN ordering.customer_order o ON o.customer_id = p.customer_id
                 WHERE o.id = contract.order_id FOR SHARE OF p) AS cp;
        IF NOT coalesce(credit_ok, false) THEN
            RAISE EXCEPTION 'sales contract %: an installment after delivery needs credit accounting approved (15 BR-12)',
                contract.contract_number USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NULL;
END
$function$;
