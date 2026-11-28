-- =============================================================================
-- Two new permissions of the contract e-signing plan (HOP-DONG-API.md, QĐ-2 and QĐ-4):
--
--   sales-contract-terms:APPROVE       a Sales Manager approves or rejects the non-standard terms of a draft contract
--                                      before it is sent (POST /orders/{id}/contract/terms-review/approval|rejection).
--   sales-contract-signatures:APPROVE  signs for the company: the authorised signer's step after the customer signed
--                                      (POST /orders/{id}/contract/company-signature*). Built with the signing machinery;
--                                      granted here so that only one migration moves the roles.
--
-- Both are the Sales Manager's and the System Admin's. Every role that gained a grant advances app_role.version in the
-- same transaction (ADR-0008, CLAUDE.md §5).
-- =============================================================================

INSERT INTO identity.permission (id, code, resource, action, description, version, created_at, created_by)
VALUES (gen_random_uuid(), 'sales-contract-terms:APPROVE', 'sales-contract-terms', 'APPROVE',
        'Approve or reject the non-standard terms of a draft sales contract before it is sent (QĐ-4)', 0, NOW(),
        'flyway'),
       (gen_random_uuid(), 'sales-contract-signatures:APPROVE', 'sales-contract-signatures', 'APPROVE',
        'Sign a sales contract for the company once the customer has signed (QĐ-2)', 0, NOW(), 'flyway')
ON CONFLICT (code) DO NOTHING;

WITH grantee (role_code) AS (
    VALUES ('SYSTEM_ADMIN'), ('SALES_MANAGER')
),
granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), r.id, p.id, 0, NOW(), 'flyway'
      FROM grantee g
      JOIN identity.app_role r ON r.code = g.role_code
      JOIN identity.permission p ON p.code IN ('sales-contract-terms:APPROVE', 'sales-contract-signatures:APPROVE')
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
