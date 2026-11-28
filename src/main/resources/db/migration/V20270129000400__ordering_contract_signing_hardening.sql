-- =============================================================================
-- Contract e-signing, after review (2026-11-28). Module: order.
--
--   ordering.contract_sign_otp.forgiven_at   wrong codes count against a revision over every code issued for it: at
--       ContractSignOtps.MAX_WRONG_TRIES (20) the revision is locked (CONTRACT_OTP_LOCKED) until Sales extend the signing
--       deadline, which stamps the revision's rows forgiven so they count no more; a new revision starts from nothing.
--       Without a lifetime cap the hourly limit alone lets a guesser try 25 codes an hour for as long as a contract waits.
--   Comments corrected: the verification code is set when the PDF to sign outside is prepared or when the company signs,
--       whichever comes first; the PDF to sign outside is the sent PDF with the certificate page added as an incremental
--       update, so its first bytes are exactly the sent PDF (V20270129000300 said "set when the company signs" and
--       "sent PDF + certificate page", and is not edited: migrations are only added).
-- =============================================================================

ALTER TABLE ordering.contract_sign_otp
    ADD COLUMN forgiven_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_contract_sign_otp_forgiven CHECK (forgiven_at IS NULL OR forgiven_at >= created_at);

-- The lifetime count reads every code of one revision.
CREATE INDEX ix_contract_sign_otp_revision ON ordering.contract_sign_otp (contract_id, revision)
    WHERE forgiven_at IS NULL;

COMMENT ON COLUMN ordering.contract_sign_otp.forgiven_at IS
    'When Sales extended the signing deadline after this code: its wrong tries no longer count towards the revision''s '
    'lifetime cap of wrong codes (ContractSignOtps.MAX_WRONG_TRIES).';

COMMENT ON COLUMN ordering.sales_contract.verification_code IS
    'The code on the certificate page, looked up on the public verification page; set once, when the PDF to sign '
    'outside is prepared or when the company signs, whichever comes first.';
COMMENT ON COLUMN ordering.sales_contract.outside_document_key IS
    'The PDF the company''s signer downloads to sign outside the system: the sent PDF with the certificate page added as '
    'an incremental update (its first bytes are exactly the sent PDF); kept so the signed file that comes back is checked '
    'against exactly these bytes. Its page names no signer, account or time: it is prepared before anyone signs.';
