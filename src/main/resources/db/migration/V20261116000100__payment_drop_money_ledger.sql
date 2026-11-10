-- =============================================================================
-- 4B-11: drop the money ledger and the per-order payment terms.
--
-- Why. A customer pays by the installments of a signed sales contract, recorded as payment receipts against them
-- (kltn-docs 15 BR-01, BR-07; 4B-6). The money ledger it replaced - customer transfers allocated to orders, payments,
-- refunds of payments, receivables of credit orders, carrier COD remittances, the order's own record of money in - and
-- the payment terms an order, a quote or a customer carried of its own (prepaid / deposit / credit, the deposit share,
-- the balance policy) left the code in 4B-10 (V20261108003200 kept every CHECK true on the unmapped columns' defaults).
-- This drops what the database kept for them.
--
-- Owner's decision QD-1 (10/11): the database is rebuilt on deploy and no data of these tables is kept, so nothing is
-- archived to platform.legacy_archive and no step refuses to run on rows still in progress: leftover rows go with
-- their tables, and the few rows elsewhere that point at them are cleared first.
--
-- Kept on purpose:
--   * orders CONFIRMED on the old terms, without a contract, stay as history: no installment is made up for them. The
--     restated ck_order_confirmed_basis is NOT VALID, so they are not checked; any order confirmed from now on is, and
--     so is any later write that leaves such an order CONFIRMED;
--   * customer.credit_profile's limit and days to pay (credit_limit, credit_term_days): the credit of 15 BR-12;
--   * customer_order.cancellation_retained_amount: still mapped and written as 0 since 4B-10 (the fee a cancellation
--     keeps is its settlement's, 4B-9); its CHECK loses only the bound by the dropped paid_amount;
--   * ordering.return_request.credit_transfer_id / withdrawal_credit_transfer_id: still mapped (always NULL since
--     4B-10, which books nothing for a credit note); only their foreign keys and the CHECKs tying them to the credited
--     amounts go - a credit note is settled outside the system (15 BR-05, BR-06). 4D-4 reworks the old returns;
--   * the permission payment-refunds: 4B-9 reuses it for the refund records of cancellations and closures;
--   * the event records OrderCancelled, OrderDelivered, OrderRemainderClosed keep their money fields (always 0 or
--     null), so their publications still read.
--
-- Data:
--   * every row of payment.refund, cod_remittance_line, cod_remittance, transfer_allocation, receivable, payment,
--     customer_transfer and ordering.order_payment goes with its table (none on the demo data);
--   * return requests stop naming a customer transfer (the ids are cleared before the transfers go);
--   * event publications of the removed events (PaymentCaptured, PaymentReversed) and of the removed listeners
--     (payment's OrderCancelledListener, MoneyLedgerService, ReceivableService; order's PaymentEventListener methods for
--     those events go with the event types) are deleted, completed or not: a pending one of a removed class fails on
--     every restart (republish-outstanding-events-on-restart);
--   * the permissions only the ledger used go with every grant of them; every role that loses a grant moves its
--     version in the same statement (ADR-0008).
--
-- Order, so no step meets a reference a later step removes:
--   1. return requests: clear the transfer ids, drop the FKs and the CHECKs that name them;
--   2. the ledger tables, children first, then the function only payment.refund's trigger used;
--   3. orders: the CHECKs that read the dropped columns, the columns, the CHECKs restated;
--   4. order shipments, quotes, credit profiles: CHECKs, then columns;
--   5. event publications; 6. permissions.
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. Return requests no longer name a customer transfer
-- ---------------------------------------------------------------------------------------------
-- Every step here is conditional: cluster 1's rework of the old returns (4D-4, an earlier version number) may already
-- have dropped these constraints or the columns they name. Whatever is still there is brought in line; nothing is
-- assumed.
ALTER TABLE ordering.return_request DROP CONSTRAINT IF EXISTS fk_return_request_credit_transfer;
ALTER TABLE ordering.return_request DROP CONSTRAINT IF EXISTS fk_return_request_withdrawal_transfer;
-- Tied a credit or an offset to the transfer that booked it; nothing books one any more (4B-10).
ALTER TABLE ordering.return_request DROP CONSTRAINT IF EXISTS ck_return_request_credit_transfer;
ALTER TABLE ordering.return_request DROP CONSTRAINT IF EXISTS ck_return_request_withdrawal;

DO $$
DECLARE
    has_credit_transfer     BOOLEAN;
    has_withdrawal_transfer BOOLEAN;
    has_withdrawal          BOOLEAN;
BEGIN
    SELECT bool_or(column_name = 'credit_transfer_id'),
           bool_or(column_name = 'withdrawal_credit_transfer_id'),
           count(*) FILTER (WHERE column_name IN ('withdrawal_status', 'replacement_order_id', 'withdrawal_reason',
               'withdrawal_proposed_by', 'withdrawal_proposed_at', 'withdrawal_decided_by', 'withdrawal_decided_at',
               'withdrawal_decision_note', 'withdrawal_amount', 'withdrawal_offset_amount',
               'withdrawal_credited_amount')) = 11
      INTO has_credit_transfer, has_withdrawal_transfer, has_withdrawal
      FROM information_schema.columns
     WHERE table_schema = 'ordering' AND table_name = 'return_request';
    IF has_credit_transfer THEN
        UPDATE ordering.return_request SET credit_transfer_id = NULL WHERE credit_transfer_id IS NOT NULL;
    END IF;
    IF has_withdrawal_transfer THEN
        UPDATE ordering.return_request SET withdrawal_credit_transfer_id = NULL
         WHERE withdrawal_credit_transfer_id IS NOT NULL;
    END IF;
    -- The withdrawal's rules as before, without its last clause (the withdrawal's credit booked as a transfer); only
    -- while the withdrawal is still there to state them on.
    IF has_withdrawal THEN
        ALTER TABLE ordering.return_request ADD CONSTRAINT ck_return_request_withdrawal CHECK (
            (withdrawal_status IS NULL OR withdrawal_status IN ('PENDING', 'APPROVED', 'REJECTED'))
            AND (withdrawal_status IS NULL
                 OR (status = 'RESOLVED' AND replacement_order_id IS NOT NULL AND withdrawal_reason IS NOT NULL
                     AND withdrawal_proposed_by IS NOT NULL AND withdrawal_proposed_at IS NOT NULL))
            AND ((withdrawal_status IN ('APPROVED', 'REJECTED'))
                 = (withdrawal_decided_by IS NOT NULL AND withdrawal_decided_at IS NOT NULL))
            AND (withdrawal_decided_by IS NULL OR withdrawal_decided_by <> withdrawal_proposed_by)
            AND (withdrawal_status IS DISTINCT FROM 'REJECTED' OR withdrawal_decision_note IS NOT NULL)
            AND withdrawal_amount >= 0
            AND withdrawal_offset_amount >= 0
            AND withdrawal_credited_amount >= 0
            AND (withdrawal_status = 'APPROVED' OR (withdrawal_offset_amount = 0 AND withdrawal_credited_amount = 0))
            AND (withdrawal_status IS DISTINCT FROM 'APPROVED'
                 OR withdrawal_offset_amount + withdrawal_credited_amount = withdrawal_amount));
    END IF;
END $$;


-- ---------------------------------------------------------------------------------------------
-- 2. The ledger tables, each after every table that references it; no CASCADE
-- ---------------------------------------------------------------------------------------------
DROP TABLE payment.refund;                 -- -> payment, transfer_allocation, customer_transfer, return_request, order
DROP TABLE payment.cod_remittance_line;    -- -> payment, cod_remittance, fulfillment.shipment
DROP TABLE payment.cod_remittance;
DROP TABLE payment.transfer_allocation;    -- -> receivable, customer_transfer, itself, order
DROP TABLE payment.receivable;
DROP TABLE payment.payment;
DROP TABLE payment.customer_transfer;
DROP TABLE ordering.order_payment;

-- Only payment.refund's tg_credit_refund_outcome called it; the trigger went with the table.
DROP FUNCTION payment.credit_refund_outcome();


-- ---------------------------------------------------------------------------------------------
-- 3. Orders: no payment terms, no money of their own
-- ---------------------------------------------------------------------------------------------
-- Named, rather than left to DROP COLUMN, which drops a CHECK on a dropped column without a word - including the
-- three restated below, whose other rules must survive.
ALTER TABLE ordering.customer_order
    DROP CONSTRAINT ck_order_balance_policy,
    DROP CONSTRAINT ck_order_billing,
    DROP CONSTRAINT ck_order_credit_term_days,
    DROP CONSTRAINT ck_order_deposit,
    DROP CONSTRAINT ck_order_deposit_before_production,
    DROP CONSTRAINT ck_order_paid_amount,
    DROP CONSTRAINT ck_order_paid_in_full,
    DROP CONSTRAINT ck_order_payment_term,
    DROP CONSTRAINT ck_order_remainder_fee,
    DROP CONSTRAINT ck_order_cancellation_fee,
    DROP CONSTRAINT ck_order_cancellation_retained,
    DROP CONSTRAINT ck_order_confirmed_basis;

ALTER TABLE ordering.customer_order
    DROP COLUMN payment_term,
    DROP COLUMN deposit_required,
    DROP COLUMN deposit_received_at,
    DROP COLUMN balance_policy,
    DROP COLUMN credit_term_days,
    DROP COLUMN paid_amount,
    DROP COLUMN paid_in_full_at,
    DROP COLUMN billed_amount,
    DROP COLUMN receivable_paid_amount,
    DROP COLUMN remainder_refund_amount,
    DROP COLUMN remainder_fee_amount,
    DROP COLUMN remainder_fee_retained_amount,
    DROP COLUMN remainder_fee_paid_amount;

-- The agreed fee as before (reason, detail and amount together; only on a cancelled order, above zero), without the
-- share of the money received it was kept from: that is the cancellation's settlement now (4B-9).
ALTER TABLE ordering.customer_order ADD CONSTRAINT ck_order_cancellation_fee CHECK (
    (cancellation_fee_reason IS NULL) = (cancellation_fee_detail IS NULL)
    AND (cancellation_fee_reason IS NULL) = (cancellation_fee_amount IS NULL)
    AND (cancellation_fee_amount IS NULL OR (status = 'CANCELLED' AND cancellation_fee_amount > 0)));

ALTER TABLE ordering.customer_order ADD CONSTRAINT ck_order_cancellation_retained CHECK (
    cancellation_retained_amount IS NULL
    OR (status = 'CANCELLED' AND cancellation_retained_amount >= 0));

-- CONFIRMED by its signed contract (17 §4.1); the old terms are gone, and so are free replacements of a return
-- (V20261114000200, 4D-4, dropped replaces_return_request_id and recorded a confirmed replacement as paid in full).
-- NOT VALID: orders confirmed on those terms before 4B-10 stay as they are (QD-1, see the header).
ALTER TABLE ordering.customer_order ADD CONSTRAINT ck_order_confirmed_basis CHECK (
    status <> 'CONFIRMED'
    OR contract_signed_at IS NOT NULL) NOT VALID;


-- ---------------------------------------------------------------------------------------------
-- 4. Shipments bill nothing; quotes and customers carry no payment terms
-- ---------------------------------------------------------------------------------------------
ALTER TABLE ordering.order_shipment
    DROP CONSTRAINT ck_order_shipment_billed,
    DROP CONSTRAINT ck_order_shipment_amounts;
ALTER TABLE ordering.order_shipment DROP COLUMN billed_amount;
ALTER TABLE ordering.order_shipment ADD CONSTRAINT ck_order_shipment_amounts CHECK (amount > 0);

ALTER TABLE ordering.quote
    DROP CONSTRAINT ck_quote_deposit,
    DROP CONSTRAINT ck_quote_payment_term;
ALTER TABLE ordering.quote
    DROP COLUMN payment_term,
    DROP COLUMN deposit_percent;

-- ck_credit_profile_credit (the limit and the days, both or neither) stays.
ALTER TABLE customer.credit_profile
    DROP CONSTRAINT ck_credit_profile_balance_policy,
    DROP CONSTRAINT ck_credit_profile_default_term,
    DROP CONSTRAINT ck_credit_profile_deposit,
    DROP CONSTRAINT ck_credit_profile_some_term;
ALTER TABLE customer.credit_profile
    DROP COLUMN allow_prepaid,
    DROP COLUMN allow_deposit,
    DROP COLUMN allow_credit,
    DROP COLUMN default_payment_term,
    DROP COLUMN deposit_percent,
    DROP COLUMN deposit_balance_policy;


-- ---------------------------------------------------------------------------------------------
-- 5. Event publications only the removed code could handle
-- ---------------------------------------------------------------------------------------------
DELETE FROM event_publication e
WHERE e.event_type IN ('com.stockflow.contracts.PaymentCaptured', 'com.stockflow.contracts.PaymentReversed')
   OR e.listener_id LIKE 'com.stockflow.payment.internal.service.OrderCancelledListener.%'
   OR e.listener_id LIKE 'com.stockflow.payment.internal.service.MoneyLedgerService.%'
   OR e.listener_id LIKE 'com.stockflow.payment.internal.service.ReceivableService.%';


-- ---------------------------------------------------------------------------------------------
-- 6. Permissions only the ledger used
-- ---------------------------------------------------------------------------------------------
-- role_permission first, moving the version of every role that loses a grant in the same statement: deleting the
-- permission first would take its grants with it (ON DELETE CASCADE) without moving any version.
WITH lost AS (
    DELETE FROM identity.role_permission rp
    USING identity.permission p
    WHERE rp.permission_id = p.id
      AND p.resource IN ('payment-customer-transfers', 'payment-cod-remittances', 'payment-payments',
                         'payment-settlements', 'payment-receivables', 'payment-order-payments')
    RETURNING rp.role_id
)
UPDATE identity.app_role r SET version = version + 1
WHERE r.id IN (SELECT DISTINCT role_id FROM lost);

DELETE FROM identity.permission
WHERE resource IN ('payment-customer-transfers', 'payment-cod-remittances', 'payment-payments',
                   'payment-settlements', 'payment-receivables', 'payment-order-payments');
