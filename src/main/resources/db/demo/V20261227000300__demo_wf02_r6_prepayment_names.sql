-- =============================================================================
-- Demo: WF-02 fix round 6, owner question 5 (2026-11-25). Demo only (db/demo).
--
-- The contract PDF now says that what the customer pays before delivery is a prepayment, not a deposit
-- (contract.pdf.clause.payment.cancel). The seeds named the first installment of their contracts "Đặt cọc khi ký"
-- (V20261124000600, V20261128000200, V20261203000100, V20261222000500, and db/demo-wf05 V20261224900200 and
-- V20261224900500 where that location is on), so the PDF printed "not a deposit" next to an installment called a
-- deposit. The owner chose to rename them "Thanh toán trước khi ký".
--
-- 1. The contracts' payment plans (ordering.contract_payment_installment).
-- 2. The installments drawn from them (payment.installment copies the plan's name).
-- 3. The chat cards that carry the plan or an installment in their payload: the DS-02 contract card built from the
--    plan, and the showcase conversation of V20261124000500, whose cards say "Đợt 1 - đặt cọc".
--
-- Left as they are, on purpose: payment receipts (their note is what the accountant recorded, and
-- tg_payment_receipt_immutable keeps it, 15 BR-04), and chat text messages (what a person wrote).
--
-- Every row is matched by its old name, so a database where a seed did not run (db/demo-wf05 off) changes nothing
-- for it, and a second run finds nothing to change.
-- =============================================================================

-- ---------------------------------------------------------------------------------------------------------- 1

-- tg_contract_payment_installment_draft refuses any change to the plan of a contract that is no longer a draft
-- (14 BR-14). The rule is about plans people sent and signed; it is lifted for this one statement over rows a seed
-- made, as V20261227000200 did for the contract, and put back at once.
ALTER TABLE ordering.contract_payment_installment DISABLE TRIGGER tg_contract_payment_installment_draft;

UPDATE ordering.contract_payment_installment
SET name = 'Thanh toán trước khi ký'
WHERE name = 'Đặt cọc khi ký';

-- The deferred constraint triggers queued for this update (tg_contract_payment_installment_terms) run now, so
-- nothing is pending on the table when its trigger is put back (PostgreSQL refuses ALTER TABLE otherwise).
SET CONSTRAINTS ALL IMMEDIATE;

ALTER TABLE ordering.contract_payment_installment ENABLE TRIGGER tg_contract_payment_installment_draft;

-- ---------------------------------------------------------------------------------------------------------- 2

-- tg_installment_transition keeps what an installment is, its name included (14 BR-14); lifted the same way.
ALTER TABLE payment.installment DISABLE TRIGGER tg_installment_transition;

UPDATE payment.installment
SET name             = 'Thanh toán trước khi ký',
    version          = version + 1,
    last_modified_at = NOW(),
    last_modified_by = 'flyway'
WHERE name = 'Đặt cọc khi ký';

SET CONSTRAINTS ALL IMMEDIATE;

ALTER TABLE payment.installment ENABLE TRIGGER tg_installment_transition;

-- ---------------------------------------------------------------------------------------------------------- 3

-- tg_message_immutable lets a card's payload change unless the message was deleted (V20261227000200 relies on it
-- too). The names are JSON string values with no quote or backslash in them, so replacing the text keeps the payload
-- valid JSON.
UPDATE chat.message
SET card_payload = replace(replace(card_payload::text, '"Đặt cọc khi ký"', '"Thanh toán trước khi ký"'),
                           '"Đợt 1 - đặt cọc"', '"Đợt 1 - thanh toán trước"')::jsonb,
    version      = version + 1
WHERE card_payload IS NOT NULL
  AND deleted_at IS NULL
  AND (card_payload::text LIKE '%"Đặt cọc khi ký"%' OR card_payload::text LIKE '%"Đợt 1 - đặt cọc"%');
