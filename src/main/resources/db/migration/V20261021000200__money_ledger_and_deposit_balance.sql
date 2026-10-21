-- =============================================================================
-- G2-3 (SCRUM-218): one ledger for incoming money, and the balance of deposit orders.
-- Docs: kltn-docs 15 §4.1-4.5, BR-02, BR-04..BR-09, edge cases; 09 §1-2, §4.3 step 4, BR-04, edge "tiền thu hộ";
-- 17 §4.3 step 9. Decisions G3-Q3 = A (one ledger: the customer-transfer ledger of SCRUM-431 pays orders too, no
-- separate payment.payment path) and G3-Q4 = A (the balance is enforced at the handover, warned at packing).
--
-- 1. payment.transfer_allocation pays an ORDER (prepaid, deposit, balance before delivery) as well as a receivable:
--    every allocation names its order (backfilled from its receivable), receivable_id is NULL for money paid to the
--    order itself. Allocations stay append-only (15 BR-06): taking money back is a new row with a negative amount
--    that names the allocation it reverses, once, with the accountant's reason. uk_transfer_allocation goes: one
--    transfer may pay one receivable in several steps, and pay it again after a reversal.
-- 2. payment.customer_transfer: the channel the money came by (a customer's bank transfer, or a carrier remitting
--    what it collected on delivery, 09 §4.3 step 4) and the money of it refunded as the customer's credit (15 edge
--    "khách chuyển thừa ... hoàn lại theo yêu cầu", "tiền về sau khi đơn đã huỷ").
-- 3. payment.refund is placed on a payment (legacy rows), on an allocation (money the ledger paid to an order that
--    was then cancelled or partly closed) or on a transfer's credit; exactly one of them.
-- 4. Deposit balance policy (15 §4.2 step 3, "theo chính sách của khách"): the customer's terms say how the balance
--    of a deposit order is collected - BEFORE_DELIVERY (paid by transfer before the handover) or ON_DELIVERY (the
--    carrier collects it, 09 §2) - and the order keeps the policy it was sold on. Existing deposit customers and
--    orders get BEFORE_DELIVERY, the policy that never lets unpaid goods leave. The third policy of 15 §4.2 (the
--    balance becomes a receivable) waits for the credit-limit decision (G3 Q5) and is not accepted yet.
-- 5. ON_DELIVERY: the amount the carrier collects is fixed at the handover - per shipment (order_shipment.cod_amount)
--    or for a whole-order handover (customer_order.cod_amount) - and billed at delivery as a DEPOSIT_BALANCE
--    receivable the CARRIER collects; the carrier's remittance pays it. A carrier-collected receivable is never the
--    customer's debt: it is not in their credit position and their transfers never pay it.
-- 6. ordering.order_payment counts reversals too (a negative row, once per reversal id).
-- 7. Grants: the accountant allocates, reverses and refunds credit (payment-customer-transfers:UPDATE). An order's
--    payment history is its own resource, payment-order-payments:READ, for the accountant, Sales and the coordinator:
--    they read one order's money, never the list of every customer's transfers.
-- 8. A refund of a customer's credit that FAILED gives the money back to the transfer's credit (and takes it again if
--    it is retried): payment.customer_transfer.refunded_amount always equals its CUSTOMER_CREDIT refunds not FAILED.
-- =============================================================================

-- ---- 4. balance policy: the customer's terms and the order's snapshot ----------------------------------------------
ALTER TABLE customer.credit_profile ADD COLUMN deposit_balance_policy VARCHAR(16);
UPDATE customer.credit_profile SET deposit_balance_policy = 'BEFORE_DELIVERY' WHERE allow_deposit;
ALTER TABLE customer.credit_profile
    ADD CONSTRAINT ck_credit_profile_balance_policy CHECK ((allow_deposit) = (deposit_balance_policy IS NOT NULL)
        AND (deposit_balance_policy IS NULL OR deposit_balance_policy IN ('BEFORE_DELIVERY', 'ON_DELIVERY')));

ALTER TABLE ordering.customer_order
    ADD COLUMN balance_policy VARCHAR(16),
    ADD COLUMN cod_amount     NUMERIC(19, 4) NOT NULL DEFAULT 0;
UPDATE ordering.customer_order SET balance_policy = 'BEFORE_DELIVERY' WHERE payment_term = 'DEPOSIT';
ALTER TABLE ordering.customer_order
    ADD CONSTRAINT ck_order_balance_policy CHECK ((payment_term = 'DEPOSIT') = (balance_policy IS NOT NULL)
        AND (balance_policy IS NULL OR balance_policy IN ('BEFORE_DELIVERY', 'ON_DELIVERY'))),
    -- What the carrier collects on a whole-order handover: only an ON_DELIVERY order, never above its total.
    ADD CONSTRAINT ck_order_cod_amount CHECK (cod_amount >= 0 AND cod_amount <= total_amount
        AND (cod_amount = 0 OR balance_policy = 'ON_DELIVERY'));

ALTER TABLE ordering.order_shipment
    ADD COLUMN cod_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_order_shipment_cod_amount CHECK (cod_amount >= 0 AND cod_amount <= amount);

-- ---- 6. the order counts reversals ----------------------------------------------------------------------------------
ALTER TABLE ordering.order_payment DROP CONSTRAINT ck_order_payment_amount;
ALTER TABLE ordering.order_payment ADD CONSTRAINT ck_order_payment_amount CHECK (amount <> 0);

-- ---- 5. carrier-collected balances ------------------------------------------------------------------------------------
ALTER TABLE payment.receivable DROP CONSTRAINT ck_receivable_kind;
ALTER TABLE payment.receivable
    ADD COLUMN collector VARCHAR(16) NOT NULL DEFAULT 'CUSTOMER',
    ADD CONSTRAINT ck_receivable_kind CHECK (kind IN ('CREDIT_ORDER', 'CANCELLATION_FEE', 'DEPOSIT_BALANCE')),
    ADD CONSTRAINT ck_receivable_collector CHECK (collector IN ('CUSTOMER', 'CARRIER')
        AND (collector <> 'CARRIER' OR kind = 'DEPOSIT_BALANCE'));
CREATE INDEX ix_receivable_customer_collector ON payment.receivable (customer_id, collector, status);
CREATE INDEX ix_receivable_order ON payment.receivable (order_id);

-- ---- 2. transfers: channel and credit refunded --------------------------------------------------------------------
ALTER TABLE payment.customer_transfer
    ADD COLUMN channel         VARCHAR(24)    NOT NULL DEFAULT 'BANK_TRANSFER',
    ADD COLUMN carrier_name    VARCHAR(120),
    ADD COLUMN refunded_amount NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_customer_transfer_channel CHECK (channel IN ('BANK_TRANSFER', 'CARRIER_REMITTANCE')
        AND (channel = 'CARRIER_REMITTANCE') = (carrier_name IS NOT NULL)),
    -- 15 BR-05: what is refunded of a transfer's credit never exceeds the transfer.
    ADD CONSTRAINT ck_customer_transfer_refunded CHECK (refunded_amount >= 0
        AND unallocated_amount + refunded_amount <= amount);

-- ---- 1. allocations pay orders, and are reversed by new rows ----------------------------------------------------------
ALTER TABLE payment.transfer_allocation
    ADD COLUMN order_id               UUID,
    ADD COLUMN reverses_allocation_id UUID,
    ADD COLUMN note                   VARCHAR(500);

-- The append-only trigger refuses this UPDATE; the backfill runs with it disabled, inside this migration only.
ALTER TABLE payment.transfer_allocation DISABLE TRIGGER tg_transfer_allocation_append_only;
UPDATE payment.transfer_allocation a SET order_id = r.order_id
  FROM payment.receivable r WHERE r.id = a.receivable_id;
ALTER TABLE payment.transfer_allocation ENABLE TRIGGER tg_transfer_allocation_append_only;

ALTER TABLE payment.transfer_allocation DROP CONSTRAINT uk_transfer_allocation;
ALTER TABLE payment.transfer_allocation DROP CONSTRAINT ck_transfer_allocation_amount;
ALTER TABLE payment.transfer_allocation
    ALTER COLUMN order_id SET NOT NULL,
    ALTER COLUMN receivable_id DROP NOT NULL,
    ADD CONSTRAINT fk_transfer_allocation_order FOREIGN KEY (order_id)
        REFERENCES ordering.customer_order (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_transfer_allocation_reverses FOREIGN KEY (reverses_allocation_id)
        REFERENCES payment.transfer_allocation (id) ON DELETE RESTRICT,
    -- An allocation brings money; its reversal takes it back, with the accountant's reason (15 BR-06).
    ADD CONSTRAINT ck_transfer_allocation_amount CHECK (
        (reverses_allocation_id IS NULL AND amount > 0)
     OR (reverses_allocation_id IS NOT NULL AND amount < 0 AND note IS NOT NULL));
-- Reversed once.
CREATE UNIQUE INDEX uk_transfer_allocation_reversal ON payment.transfer_allocation (reverses_allocation_id)
    WHERE reverses_allocation_id IS NOT NULL;
CREATE INDEX ix_transfer_allocation_order ON payment.transfer_allocation (order_id);
CREATE INDEX ix_transfer_allocation_transfer ON payment.transfer_allocation (transfer_id);

-- ---- 3. refunds from the ledger ----------------------------------------------------------------------------------------
ALTER TABLE payment.refund DROP CONSTRAINT ck_refund_source;
ALTER TABLE payment.refund
    ALTER COLUMN payment_id DROP NOT NULL,
    ADD COLUMN allocation_id UUID,
    ADD COLUMN transfer_id   UUID,
    ADD CONSTRAINT fk_refund_allocation FOREIGN KEY (allocation_id)
        REFERENCES payment.transfer_allocation (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_refund_transfer FOREIGN KEY (transfer_id)
        REFERENCES payment.customer_transfer (id) ON DELETE RESTRICT,
    -- Money goes back from exactly one place: a payment, an allocation to an order, or a transfer's credit.
    ADD CONSTRAINT ck_refund_money_from CHECK (num_nonnulls(payment_id, allocation_id, transfer_id) = 1),
    ADD CONSTRAINT ck_refund_source CHECK (source IS NULL OR source IN
        ('ORDER_CANCELLED', 'ORDER_REMAINDER_CLOSED', 'RETURN', 'MANUAL', 'CUSTOMER_CREDIT')),
    ADD CONSTRAINT ck_refund_customer_credit CHECK ((source IS NOT DISTINCT FROM 'CUSTOMER_CREDIT')
        = (transfer_id IS NOT NULL));
CREATE UNIQUE INDEX uk_refund_order_cancelled_allocation ON payment.refund (allocation_id)
    WHERE source = 'ORDER_CANCELLED';
CREATE UNIQUE INDEX uk_refund_order_remainder_closed_allocation ON payment.refund (allocation_id, remainder_closure_id)
    WHERE source = 'ORDER_REMAINDER_CLOSED';
CREATE INDEX ix_refund_allocation ON payment.refund (allocation_id) WHERE allocation_id IS NOT NULL;
CREATE INDEX ix_refund_transfer ON payment.refund (transfer_id) WHERE transfer_id IS NOT NULL;

-- ---- 8. a failed credit refund is credit again ---------------------------------------------------------------------------
CREATE FUNCTION payment.credit_refund_outcome() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.transfer_id IS NULL OR NEW.status IS NOT DISTINCT FROM OLD.status THEN
        RETURN NEW;
    END IF;
    IF NEW.status = 'FAILED' THEN
        UPDATE payment.customer_transfer
           SET refunded_amount = refunded_amount - NEW.amount, unallocated_amount = unallocated_amount + NEW.amount,
               version = version + 1, last_modified_at = NOW(), last_modified_by = 'credit-refund-failed'
         WHERE id = NEW.transfer_id;
    ELSIF OLD.status = 'FAILED' THEN
        -- Retried: the money leaves the credit again; ck_customer_transfer_amounts refuses it when it was spent since.
        UPDATE payment.customer_transfer
           SET refunded_amount = refunded_amount + NEW.amount, unallocated_amount = unallocated_amount - NEW.amount,
               version = version + 1, last_modified_at = NOW(), last_modified_by = 'credit-refund-retried'
         WHERE id = NEW.transfer_id;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_credit_refund_outcome
    AFTER UPDATE OF status ON payment.refund
    FOR EACH ROW EXECUTE FUNCTION payment.credit_refund_outcome();

-- ---- 7. permissions ----------------------------------------------------------------------------------------------------
INSERT INTO identity.permission (id, code, resource, action, version, created_at, created_by)
VALUES (gen_random_uuid(), 'payment-customer-transfers:UPDATE', 'payment-customer-transfers', 'UPDATE', 0, NOW(),
        'flyway'),
       (gen_random_uuid(), 'payment-order-payments:READ', 'payment-order-payments', 'READ', 0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM (VALUES
          -- 15 §2, BR-04: only the accountant moves money between orders, receivables and the customer's credit.
          ('ACCOUNTANT',        'payment-customer-transfers', 'UPDATE'),
          ('ACCOUNTANT',        'payment-order-payments',     'READ'),
          -- 15 §2: Sales reminds the customer to pay; the coordinator releases and hands over only paid orders.
          ('SALES_STAFF',       'payment-order-payments',     'READ'),
          ('ORDER_COORDINATOR', 'payment-order-payments',     'READ')
     ) AS g(role_code, resource, actions)
JOIN identity.app_role role ON role.code = g.role_code
JOIN identity.permission permission
  ON permission.resource = g.resource
 AND permission.action = ANY (string_to_array(g.actions, ','))
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
FROM identity.app_role role
CROSS JOIN identity.permission permission
WHERE role.code = 'SYSTEM_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE identity.app_role
SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
WHERE code IN ('ACCOUNTANT', 'SALES_STAFF', 'ORDER_COORDINATOR', 'SYSTEM_ADMIN');
