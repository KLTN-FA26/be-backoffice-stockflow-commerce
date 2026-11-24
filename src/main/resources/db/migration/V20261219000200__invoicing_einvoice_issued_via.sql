-- =============================================================================
-- WF-05 BE-08, BE-10 (decision Q-8): an invoice can be recorded as issued from the provider's portal.
--
-- Why. Some states had no way out: an invoice SePay holds with a number but no tax authority code, a Viettel entry the
-- tax authority refused, an invoice at a provider that can no longer be called - each stayed SUBMITTING for good,
-- held back every later-dated invoice of its series and kept its order from completing. And a reduction that could
-- not be sent (the original issued at a provider no longer usable, or a replacement SePay will not adjust) could be
-- neither issued nor voided. Q-8: the accountant settles it on the provider's portal and records the result here -
-- one command for every case SePay's API cannot do.
--
-- What this does:
--   1. issued_via: PROVIDER (the provider's answer recorded it) or PORTAL_RECORD (the accountant recorded what the
--      provider's portal shows, checked against a fresh look-up and the invoice's own XML). PORTAL_RECORD may sit on a
--      draft only as the reduction about to be recorded in the same transaction (EInvoiceIssuance.recordExternal-
--      Reduction), so the draft-first rules below hold for it too. Null on rows from before, read as PROVIDER.
--   2. portal_recorded_by / portal_recorded_at: who recorded it, and when; together, and only with PORTAL_RECORD.
--   3. einvoice_stuck_notice.result_overdue_at: when the accountants were told a submission is overdue for the
--      provider's result (EInvoiceNeedsAttention PROVIDER_RESULT_OVERDUE), once per submission; series_mismatch_at
--      likewise for BE-30 (the provider numbered it under another series, SERIES_MISMATCH). A row may now be written
--      for either before the stuck notice itself, so notified_at is when the row was first written.
-- No row is touched.
-- =============================================================================

ALTER TABLE invoicing.einvoice ADD COLUMN issued_via VARCHAR(16);
ALTER TABLE invoicing.einvoice ADD COLUMN portal_recorded_by UUID;
ALTER TABLE invoicing.einvoice ADD COLUMN portal_recorded_at TIMESTAMPTZ;

ALTER TABLE invoicing.einvoice
    ADD CONSTRAINT fk_einvoice_portal_recorded_by FOREIGN KEY (portal_recorded_by) REFERENCES identity.app_user (id),
    ADD CONSTRAINT ck_einvoice_issued_via CHECK (
        issued_via IS NULL
        OR (issued_via = 'PROVIDER' AND status IN ('ISSUED', 'REPLACED'))
        OR issued_via = 'PORTAL_RECORD'),
    -- Recorded from the portal names who did it once it is issued; nothing else carries a recorder.
    ADD CONSTRAINT ck_einvoice_portal_recorded CHECK (
        (portal_recorded_at IS NULL AND portal_recorded_by IS NULL)
        OR (issued_via = 'PORTAL_RECORD' AND portal_recorded_at IS NOT NULL AND portal_recorded_by IS NOT NULL
            AND status IN ('ISSUED', 'REPLACED'))),
    ADD CONSTRAINT ck_einvoice_portal_recorded_issued CHECK (
        issued_via IS DISTINCT FROM 'PORTAL_RECORD' OR status NOT IN ('ISSUED', 'REPLACED')
        OR portal_recorded_at IS NOT NULL);

ALTER TABLE invoicing.einvoice_stuck_notice ADD COLUMN result_overdue_at TIMESTAMPTZ;
ALTER TABLE invoicing.einvoice_stuck_notice ADD COLUMN series_mismatch_at TIMESTAMPTZ;

COMMENT ON COLUMN invoicing.einvoice.issued_via IS
    'WF-05 Q-8: PROVIDER when the provider''s answer recorded the invoice issued, PORTAL_RECORD when the accountant recorded what the provider''s portal shows (BE-08) or a reduction made there (BE-10, Q-9). Null on rows from before, read as PROVIDER.';
COMMENT ON COLUMN invoicing.einvoice_stuck_notice.result_overdue_at IS
    'WF-05 BE-08: when the accountants were told this submission is overdue for the provider''s result (PROVIDER_RESULT_OVERDUE): it can then be recorded from the portal.';
COMMENT ON COLUMN invoicing.einvoice_stuck_notice.series_mismatch_at IS
    'WF-05 BE-30: when the accountants were told the provider numbered this submission under another series than it was sent under; it is not recorded ISSUED.';
