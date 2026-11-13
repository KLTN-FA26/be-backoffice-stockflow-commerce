-- =============================================================================
-- E-invoices reach the buyer, and the accountant hears of the ones that do not move (WF-05 E5; plan §1.4 "Nối với
-- luồng sẵn có": issued -> the buyer is e-mailed and reads it on the portal; refused or stuck -> the accountant is told).
--
--   * invoicing.einvoice_stuck_notice - "the accountants were told that this submission is stuck". An invoice that is
--     SUBMITTING is asked about every half minute; one still SUBMITTING long after it was sent is waiting on a provider
--     that does not answer, and the deadline of the working day after its date keeps running (NĐ 254 Phụ lục điểm 7).
--     One row per invoice and transaction id, written in the transaction that publishes the notice, so a submission is
--     announced once however often the sweep runs; an invoice refused, corrected and sent again has a new transaction
--     id and may be announced again. Not a column on the invoice: the invoice's own row is the legal record, and this
--     is bookkeeping about a message.
--   * The customer reads their own issued invoices on the portal: CUSTOMER gets einvoices:READ. The endpoints under
--     /api/v1/einvoices/me resolve the customer from the sign-in and show issued invoices only; every back-office
--     endpoint of the resource refuses a portal account (ScopeGuards.requireBackOffice), as payment-refunds does.
-- =============================================================================

CREATE TABLE invoicing.einvoice_stuck_notice
(
    einvoice_id    UUID        NOT NULL,
    transaction_id VARCHAR(64) NOT NULL,
    notified_at    TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_einvoice_stuck_notice PRIMARY KEY (einvoice_id, transaction_id),
    CONSTRAINT fk_einvoice_stuck_notice_einvoice FOREIGN KEY (einvoice_id) REFERENCES invoicing.einvoice (id)
);

COMMENT ON TABLE invoicing.einvoice_stuck_notice IS
    'WF-05 E5: the accountants were told that this submission of the e-invoice has waited too long for the provider - once per invoice and transaction id.';

-- Permissions --------------------------------------------------------------------------------------------------------
WITH granted AS (
    -- Plan §1.4 "Ai làm gì": the customer reads and downloads their own invoices on the portal (scope OWN on the
    -- endpoint; the customer is the sign-in's, never the request's).
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
      JOIN identity.permission permission ON permission.resource = 'einvoices' AND permission.action = 'READ'
     WHERE role.code = 'CUSTOMER'
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
-- Grants are cached per role version (ADR-0008): advance only the roles whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT role_id FROM granted);
