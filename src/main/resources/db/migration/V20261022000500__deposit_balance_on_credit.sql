-- =============================================================================
-- BALCREDIT (lead's decision 2026-10-21): the balance of a deposit order may go on credit. Module: customer, ordering.
-- Docs: kltn-docs 15 §4.2 step 3 ("hoặc chuyển thành công nợ nếu khách được phép"), §4.3, BR-03; 18 (allowed terms,
-- limit, days to pay). The docs leave when the limit is checked open (D13): the lead's decision rules - on the remaining
-- balance, when the order is placed; over the limit the order is refused (409), never held.
--
-- 1. customer.credit_profile.deposit_balance_policy takes ON_CREDIT, only for a customer allowed credit.
-- 2. ordering.customer_order.balance_policy takes ON_CREDIT; such an order keeps the customer's days to pay
--    (credit_term_days), as a credit order does: its deliveries bill the balance as the customer's receivables
--    (payment.receivable kind DEPOSIT_BALANCE, collector CUSTOMER - no change to that table), due after those days.
-- No grant changes, no data changes: no row holds ON_CREDIT yet.
-- =============================================================================

ALTER TABLE customer.credit_profile DROP CONSTRAINT ck_credit_profile_balance_policy;
ALTER TABLE customer.credit_profile
    ADD CONSTRAINT ck_credit_profile_balance_policy CHECK ((allow_deposit) = (deposit_balance_policy IS NOT NULL)
        AND (deposit_balance_policy IS NULL OR deposit_balance_policy IN ('BEFORE_DELIVERY', 'ON_DELIVERY', 'ON_CREDIT'))
        AND (deposit_balance_policy IS DISTINCT FROM 'ON_CREDIT' OR allow_credit));

ALTER TABLE ordering.customer_order DROP CONSTRAINT ck_order_balance_policy;
ALTER TABLE ordering.customer_order DROP CONSTRAINT ck_order_credit_term_days;
ALTER TABLE ordering.customer_order
    ADD CONSTRAINT ck_order_balance_policy CHECK ((payment_term = 'DEPOSIT') = (balance_policy IS NOT NULL)
        AND (balance_policy IS NULL OR balance_policy IN ('BEFORE_DELIVERY', 'ON_DELIVERY', 'ON_CREDIT'))),
    ADD CONSTRAINT ck_order_credit_term_days CHECK (
        (payment_term = 'CREDIT' OR balance_policy IS NOT DISTINCT FROM 'ON_CREDIT') = (credit_term_days IS NOT NULL)
        AND (credit_term_days IS NULL OR credit_term_days BETWEEN 0 AND 365));
