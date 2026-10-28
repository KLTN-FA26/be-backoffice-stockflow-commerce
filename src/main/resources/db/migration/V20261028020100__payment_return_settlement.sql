-- =============================================================================
-- SCRUM-260 (GitHub #192): the money of a resolved return (RMA), booked by payment in the transaction that resolves it.
-- Module: payment (schema payment). Docs: 03-state-machines §12 and §9 (REFUNDED "or the receivable reduced in
-- full", kltn-docs 15 §4.4), 04c F-REF, 05 BR-PAY-003 / BR-PAY-008 / BR-PAY-009.
--
-- A return credits the customer the value of the accepted units it does not replace. Payment books that credit in
-- two ways, and only these two:
--
-- 1. A RETURN_CREDIT entry in the ledger of the customer's money (payment.customer_transfer, channel RETURN_CREDIT,
--    reference = the RMA number). Accounting-wise a return of goods sold on credit is a credit to the customer's
--    receivable account (Nợ 5212 / Có 131): it settles what they owe exactly as a transfer does, without money
--    moving. So the entry pays the order's own open receivables first (allocations, as a transfer's do), and what is
--    left stays as the customer's credit - which pays their next receivable as it opens, or is refunded on request
--    through the existing credit refund (source CUSTOMER_CREDIT). It is never a bank statement line: the channel says
--    so, and it is not a carrier's either.
-- 2. PENDING refunds of source RETURN, for the part the customer asked to be paid back in cash (resolution REFUND):
--    placed on the money the order actually received (its captured payments and the allocations of real transfers),
--    newest first, never more of one than is left of it after its refunds (15 BR-05) - so a return never refunds more
--    than was paid. Each names the return (return_request_id), once per money row and return. The accountant pays them
--    out as any refund (SCRUM-220); nothing is paid out here.
--
-- Schema owner (Tú): please review - written in the range reserved for this branch (V20261028020000-029900).
-- =============================================================================

-- ---- 1. the credit entry of a return --------------------------------------------------------------------------------
ALTER TABLE payment.customer_transfer DROP CONSTRAINT ck_customer_transfer_channel;
ALTER TABLE payment.customer_transfer
    ADD CONSTRAINT ck_customer_transfer_channel CHECK (channel IN ('BANK_TRANSFER', 'CARRIER_REMITTANCE',
        'RETURN_CREDIT') AND (channel = 'CARRIER_REMITTANCE') = (carrier_name IS NOT NULL));

COMMENT ON COLUMN payment.customer_transfer.channel IS
    'BANK_TRANSFER, CARRIER_REMITTANCE (money that arrived), or RETURN_CREDIT: the credit of a resolved return '
    '(reference = RMA number), which settles receivables without money moving.';

-- ---- 2. refunds of a return ---------------------------------------------------------------------------------------------
ALTER TABLE payment.refund
    ADD COLUMN return_request_id UUID,
    ADD CONSTRAINT fk_refund_return_request FOREIGN KEY (return_request_id)
        REFERENCES ordering.return_request (id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_refund_return CHECK ((source IS NOT DISTINCT FROM 'RETURN') = (return_request_id IS NOT NULL)
        AND (return_request_id IS NULL OR order_id IS NOT NULL));

-- Once per money row and return: a resolution replayed asks nothing twice.
CREATE UNIQUE INDEX uk_refund_return_payment ON payment.refund (payment_id, return_request_id)
    WHERE source = 'RETURN' AND payment_id IS NOT NULL;
CREATE UNIQUE INDEX uk_refund_return_allocation ON payment.refund (allocation_id, return_request_id)
    WHERE source = 'RETURN' AND allocation_id IS NOT NULL;
CREATE INDEX ix_refund_return_request ON payment.refund (return_request_id) WHERE return_request_id IS NOT NULL;
