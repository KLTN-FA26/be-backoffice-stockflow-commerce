-- WF-05 audit round 3, RAD-6 (TT-3; Luật Kế toán 2015 Điều 16 - a document records the transaction as it happened):
-- the refund confirmation ("Phiếu hoàn tiền") printed the customer's bank account as their legal profile names it
-- today. A refund paid to account A, reprinted after the customer changed their profile to B (after a bounced
-- transfer, say), then said it was transferred to B. The account the money went to is kept with the refund, taken from
-- the profile when the refund is recorded, and printed from here; a refund recorded before this migration names none,
-- and its confirmation says the transaction reference identifies the transfer instead of guessing.

ALTER TABLE payment.refund_record
    ADD COLUMN payee_account_number VARCHAR(64),
    ADD COLUMN payee_bank_name      VARCHAR(255),
    ADD COLUMN payee_account_holder VARCHAR(255);

-- RefundRecord.PayeeAccount: a number and a bank, or no account at all; a holder only beside them.
ALTER TABLE payment.refund_record
    ADD CONSTRAINT ck_refund_record_payee_account CHECK (
        (payee_account_number IS NULL) = (payee_bank_name IS NULL)
        AND (payee_account_number IS NULL OR (btrim(payee_account_number) <> '' AND btrim(payee_bank_name) <> ''))
        AND (payee_account_holder IS NULL OR payee_account_number IS NOT NULL));

COMMENT ON COLUMN payment.refund_record.payee_account_number IS 'WF-05 audit r3 RAD-6: the customer''s account the refund was paid to, as their profile named it when the refund was recorded; null for records kept before. Printed on the refund confirmation, never read back from the profile.';
