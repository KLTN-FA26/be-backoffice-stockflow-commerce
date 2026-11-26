-- =============================================================================
-- Transfers recorded from the bank feed (credit plan of 2026-11-26, part C; kltn-docs 15 BR-02, §4.2).
--
-- Until now every transfer waited for the Accountant to record a receipt by hand (PaymentReceiptService.record), and an
-- order whose installment on signing was not recorded stood still in PENDING_PAYMENT; the money was typed twice, once
-- in the accounting software that already reads the bank and once here. Now SePay reads the company's account and calls
-- this system for each incoming transfer: both systems read the same bank, nobody types it again.
--
-- payment.bank_transaction keeps every incoming transfer SePay reported - by its webhook, or by the nightly catch-up
-- through SePay's transaction API - before anything is done with it (as fulfillment.carrier_event keeps a carrier's
-- call). A transfer whose content names exactly one installment, for no more than it still owes, becomes a receipt
-- recorded by the bank feed's system user (APPLIED); anything else waits for the Accountant (NEEDS_REVIEW), who assigns
-- it to installments - which records the receipt - or ignores it with a reason (IGNORED). A receipt voided later puts its
-- transfer back in the queue: the money came in, and is assigned again or ignored.
--
-- One transfer is kept once: by SePay's id (uk_bank_transaction_provider_id), and by the bank's reference - or, without
-- one, by what the transfer is (uk_bank_transaction_dedupe): the webhook's id and the API's id are not the same number
-- for one transfer, so a transfer the catch-up finds is told apart from one the webhook delivered by the bank's own
-- reference. A receipt's reference is that bank reference too, so 15 BR-03 refuses to record the transfer twice.
--
-- The bank feed's system user records the automatic receipts (payment_receipt.recorded_by is a user): DISABLED, without
-- a password or a role, so no one signs in as it. payment-bank-transactions is the Accountant's and the System Admin's;
-- every role that gained a grant advances app_role.version in the same transaction (ADR-0008).
-- =============================================================================

CREATE TABLE payment.bank_transaction (
    id uuid NOT NULL,
    provider character varying(16) NOT NULL,
    provider_transaction_id character varying(64) NOT NULL,
    source character varying(16) NOT NULL,
    dedupe_key character varying(128) NOT NULL,
    account_number character varying(32),
    amount numeric(19,4) NOT NULL,
    content character varying(500),
    bank_reference character varying(100),
    payment_code character varying(64),
    transaction_at timestamp with time zone NOT NULL,
    received_at timestamp with time zone NOT NULL,
    raw jsonb NOT NULL,
    status character varying(16) NOT NULL,
    review_reason character varying(32),
    review_note character varying(500),
    customer_id uuid,
    installment_id uuid,
    receipt_id uuid,
    decided_at timestamp with time zone,
    reviewed_by uuid,
    reviewed_at timestamp with time zone,
    ignore_reason character varying(500),
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    created_by character varying(100),
    last_modified_at timestamp with time zone,
    last_modified_by character varying(100),
    CONSTRAINT pk_bank_transaction PRIMARY KEY (id),
    CONSTRAINT fk_bank_transaction_customer FOREIGN KEY (customer_id) REFERENCES customer.customer (id),
    CONSTRAINT fk_bank_transaction_installment FOREIGN KEY (installment_id) REFERENCES payment.installment (id),
    CONSTRAINT fk_bank_transaction_receipt FOREIGN KEY (receipt_id) REFERENCES payment.payment_receipt (id),
    CONSTRAINT fk_bank_transaction_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_bank_transaction_provider CHECK (provider IN ('SEPAY')),
    CONSTRAINT ck_bank_transaction_source CHECK (source IN ('WEBHOOK', 'CATCH_UP')),
    CONSTRAINT ck_bank_transaction_ids CHECK (btrim(provider_transaction_id) <> '' AND btrim(dedupe_key) <> ''),
    -- Incoming transfers only (SePay's transferType "in"): money going out is no customer's payment.
    CONSTRAINT ck_bank_transaction_amount CHECK (amount > 0 AND amount = trunc(amount)),
    CONSTRAINT ck_bank_transaction_status CHECK (status IN ('RECEIVED', 'APPLIED', 'NEEDS_REVIEW', 'IGNORED')),
    -- APPLIED is a receipt recorded from it, and only APPLIED names one.
    CONSTRAINT ck_bank_transaction_applied CHECK ((status = 'APPLIED') = (receipt_id IS NOT NULL)),
    -- Decided once it left RECEIVED.
    CONSTRAINT ck_bank_transaction_decided CHECK ((status = 'RECEIVED') = (decided_at IS NULL)),
    -- NEEDS_REVIEW says why: a reason the back office can translate, and the detail.
    CONSTRAINT ck_bank_transaction_review_reason
        CHECK (review_reason IN ('NO_INSTALLMENT_NAMED', 'SEVERAL_INSTALLMENTS', 'INSTALLMENT_NOT_FOUND',
                                 'NOT_PAYABLE', 'OVERPAYMENT', 'OTHER_ACCOUNT', 'REFUSED', 'RECEIPT_VOIDED')),
    CONSTRAINT ck_bank_transaction_review_note
        CHECK ((status = 'NEEDS_REVIEW') = (review_reason IS NOT NULL)
            AND (status <> 'NEEDS_REVIEW' OR review_note IS NOT NULL)
            AND (review_note IS NULL OR btrim(review_note) <> '')),
    -- IGNORED says why, and only IGNORED has an ignore reason.
    CONSTRAINT ck_bank_transaction_ignored
        CHECK ((status = 'IGNORED') = (ignore_reason IS NOT NULL)
            AND (ignore_reason IS NULL OR btrim(ignore_reason) <> '')),
    -- Who reviewed it: the Accountant who assigned or ignored it; nobody for one still open or applied automatically.
    CONSTRAINT ck_bank_transaction_reviewed
        CHECK ((reviewed_by IS NULL) = (reviewed_at IS NULL)
            AND (status <> 'IGNORED' OR reviewed_by IS NOT NULL)
            AND (status NOT IN ('RECEIVED', 'NEEDS_REVIEW') OR reviewed_by IS NULL))
);

COMMENT ON TABLE payment.bank_transaction IS
    'An incoming transfer the bank feed (SePay) reported, kept before it is matched (15 BR-02): APPLIED as a receipt, '
    'NEEDS_REVIEW for the Accountant, or IGNORED with a reason.';
COMMENT ON COLUMN payment.bank_transaction.dedupe_key IS
    'The bank''s reference, upper case without spaces; without one, a digest of account, amount, time and content: one '
    'transfer reported by the webhook and found again by the catch-up is kept once.';
COMMENT ON COLUMN payment.bank_transaction.customer_id IS
    'The customer whose installment the transfer''s content names, when it names one: a hint for the Accountant.';
COMMENT ON COLUMN payment.bank_transaction.installment_id IS
    'The one installment the transfer''s content names, when it names exactly one: a hint for the Accountant.';

CREATE UNIQUE INDEX uk_bank_transaction_provider_id ON payment.bank_transaction (provider, provider_transaction_id);
CREATE UNIQUE INDEX uk_bank_transaction_dedupe ON payment.bank_transaction (provider, dedupe_key);
CREATE UNIQUE INDEX uk_bank_transaction_receipt ON payment.bank_transaction (receipt_id) WHERE receipt_id IS NOT NULL;
CREATE INDEX ix_bank_transaction_status ON payment.bank_transaction (status, transaction_at);

-- The bank feed's system user: what payment_receipt.recorded_by names for a receipt recorded from the bank. A fixed id,
-- read by BankFeedUser; DISABLED and without a password or a role, so it signs in nowhere.
INSERT INTO identity.app_user (id, username, email, password_hash, full_name, status, version, created_at, created_by)
VALUES ('0193c000-0000-7000-8000-00000000bf01', 'system.bank-feed', 'bank-feed@system.stockflow.invalid', NULL,
        'Bank feed (SePay)', 'DISABLED', 0, NOW(), 'flyway')
ON CONFLICT (id) DO NOTHING;

INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'payment-bank-transactions:VIEW_PAGE', 'payment-bank-transactions', 'VIEW_PAGE',
        'Open the bank transfers page', 0, NOW(), 'flyway'),
       (gen_random_uuid(), 'payment-bank-transactions:READ', 'payment-bank-transactions', 'READ',
        'Read the transfers the bank feed reported', 0, NOW(), 'flyway'),
       (gen_random_uuid(), 'payment-bank-transactions:UPDATE', 'payment-bank-transactions', 'UPDATE',
        'Assign a transfer to installments (records the receipt), or ignore it with a reason', 0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH wanted (role_code, permission_code) AS (
    VALUES ('ACCOUNTANT', 'payment-bank-transactions:VIEW_PAGE'),
           ('ACCOUNTANT', 'payment-bank-transactions:READ'),
           ('ACCOUNTANT', 'payment-bank-transactions:UPDATE'),
           ('SYSTEM_ADMIN', 'payment-bank-transactions:VIEW_PAGE'),
           ('SYSTEM_ADMIN', 'payment-bank-transactions:READ'),
           ('SYSTEM_ADMIN', 'payment-bank-transactions:UPDATE')
), granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM wanted
      JOIN identity.app_role role ON role.code = wanted.role_code
      JOIN identity.permission permission ON permission.code = wanted.permission_code
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
