-- =============================================================================
-- Contract e-signing: the evidence of each signature, the verification code and the PDF to sign outside (contract
-- e-signing plan 2026-11-28, HOP-DONG-API.md "Signature view" / "Public verification"). Module: order.
--
--   ordering.sales_contract_signature   one row per party and revision of a contract: who signed, how (OTP, an uploaded
--       file, the system's certificate, or staff recording a paper), when, from which address and browser, the SHA-256 of
--       what they signed, the stored file when there is one, and what inspecting that file found. APPEND-ONLY: evidence
--       that could be edited would prove nothing (a trigger refuses UPDATE and DELETE; rows leave only with their
--       contract). One row per (contract, revision, party): a revision is signed once by each side.
--   ordering.sales_contract.verification_code           the code printed on the certificate page and looked up on the
--       public verification page; set once, when the company signs, and unique.
--   ordering.sales_contract.outside_document_*          the PDF the company's signer downloads to sign with their own
--       tool (the sent PDF followed by the certificate page): kept once so the file that comes back can be checked
--       against exactly these bytes.
-- =============================================================================

CREATE TABLE ordering.sales_contract_signature (
    id                 UUID         NOT NULL,
    contract_id        UUID         NOT NULL,
    revision           INT          NOT NULL,
    party              VARCHAR(16)  NOT NULL,
    method             VARCHAR(20)  NOT NULL,
    signer_name        VARCHAR(200) NOT NULL,
    signer_title       VARCHAR(200),
    email              VARCHAR(320),
    account_user_id    UUID         NOT NULL,
    signed_at          TIMESTAMPTZ  NOT NULL,
    ip                 VARCHAR(45),
    user_agent         VARCHAR(300),
    document_sha256    VARCHAR(64)  NOT NULL,
    file_key           VARCHAR(512),
    inspection         JSONB,
    certificate_subject VARCHAR(500),
    certificate_issuer  VARCHAR(500),
    certificate_serial  VARCHAR(100),
    certificate_page_key VARCHAR(512),
    test_certificate   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(100),

    CONSTRAINT pk_sales_contract_signature PRIMARY KEY (id),
    CONSTRAINT uk_sales_contract_signature_party UNIQUE (contract_id, revision, party),
    CONSTRAINT fk_sales_contract_signature_contract FOREIGN KEY (contract_id) REFERENCES ordering.sales_contract (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_sales_contract_signature_account FOREIGN KEY (account_user_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_sales_contract_signature_revision CHECK (revision >= 1),
    CONSTRAINT ck_sales_contract_signature_party CHECK (
        (party = 'CUSTOMER' AND method IN ('OTP', 'UPLOAD', 'STAFF_RECORDED'))
        OR (party = 'COMPANY' AND method IN ('SYSTEM', 'UPLOAD', 'STAFF_RECORDED'))),
    CONSTRAINT ck_sales_contract_signature_signer CHECK (char_length(btrim(signer_name)) > 0),
    CONSTRAINT ck_sales_contract_signature_sha CHECK (document_sha256 ~ '^[0-9a-f]{64}$'),
    -- A code signature proves the mailbox: the address it went to is recorded; nothing else is kept as a file.
    CONSTRAINT ck_sales_contract_signature_otp CHECK (
        method <> 'OTP' OR (email IS NOT NULL AND file_key IS NULL AND inspection IS NULL)),
    -- A signed file is kept with what inspecting it found, and the system's own signature has a certificate and no
    -- inspection (it is the system that made the signature, not a file that came in).
    CONSTRAINT ck_sales_contract_signature_file CHECK (
        method NOT IN ('UPLOAD', 'STAFF_RECORDED') OR (file_key IS NOT NULL AND inspection IS NOT NULL)),
    CONSTRAINT ck_sales_contract_signature_system CHECK (
        method <> 'SYSTEM' OR (file_key IS NOT NULL AND inspection IS NULL AND certificate_subject IS NOT NULL
                               AND certificate_issuer IS NOT NULL AND certificate_serial IS NOT NULL)),
    CONSTRAINT ck_sales_contract_signature_test_certificate CHECK (NOT test_certificate OR method = 'SYSTEM'),
    CONSTRAINT ck_sales_contract_signature_inspection CHECK (
        inspection IS NULL OR (jsonb_typeof(inspection) = 'object' AND inspection ? 'kind'))
);

COMMENT ON TABLE ordering.sales_contract_signature IS
    'Append-only: the evidence of each party''s signature of a contract revision. document_sha256 is the sent PDF for the '
    'customer and the signed file for the company.';
COMMENT ON COLUMN ordering.sales_contract_signature.certificate_page_key IS
    'The certificate page as a one-page PDF, for the company''s signature made with the system''s certificate or signed '
    'outside; null when staff recorded a paper.';
COMMENT ON COLUMN ordering.sales_contract_signature.account_user_id IS
    'The account that signed or recorded it: the customer''s portal account, or the staff member.';

CREATE INDEX ix_sales_contract_signature_account ON ordering.sales_contract_signature (account_user_id);

CREATE TRIGGER tg_sales_contract_signature_append_only
    BEFORE UPDATE OR DELETE ON ordering.sales_contract_signature
    FOR EACH ROW EXECUTE FUNCTION platform.append_only('ordering.sales_contract', 'contract_id');

ALTER TABLE ordering.sales_contract
    ADD COLUMN verification_code VARCHAR(12),
    ADD COLUMN outside_document_key VARCHAR(512),
    ADD COLUMN outside_document_sha256 VARCHAR(64),
    ADD COLUMN outside_document_length BIGINT,
    ADD COLUMN outside_page_key VARCHAR(512),
    ADD CONSTRAINT uk_sales_contract_verification_code UNIQUE (verification_code),
    ADD CONSTRAINT ck_sales_contract_verification_code CHECK (
        verification_code IS NULL OR verification_code ~ '^[A-HJKMNP-Z2-9]{12}$'),
    ADD CONSTRAINT ck_sales_contract_outside_document CHECK (
        (outside_document_key IS NULL) = (outside_document_sha256 IS NULL)
        AND (outside_document_key IS NULL) = (outside_document_length IS NULL)
        AND (outside_document_key IS NULL) = (outside_page_key IS NULL)
        AND (outside_document_sha256 IS NULL OR outside_document_sha256 ~ '^[0-9a-f]{64}$')
        AND (outside_document_length IS NULL OR outside_document_length > 0));

-- Set once, never moved: a code printed on a certificate page and a PDF a signer is handed stay what they were.
CREATE TRIGGER tg_sales_contract_signing_kept BEFORE UPDATE ON ordering.sales_contract
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update_of('verification_code', 'outside_document_key',
        'outside_document_sha256', 'outside_document_length', 'outside_page_key');

COMMENT ON COLUMN ordering.sales_contract.verification_code IS
    'The code on the certificate page, looked up on the public verification page; set once, when the contract is signed.';
COMMENT ON COLUMN ordering.sales_contract.outside_document_key IS
    'The PDF the company''s signer downloads to sign outside the system (sent PDF + certificate page); kept so the signed '
    'file that comes back is checked against exactly these bytes.';
