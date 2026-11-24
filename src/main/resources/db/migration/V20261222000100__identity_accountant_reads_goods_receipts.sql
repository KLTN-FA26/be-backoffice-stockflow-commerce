-- =============================================================================
-- The Accountant views and prints the stock-in voucher of a return receipt (owner decision, 2026-11-23).
--
-- The voucher is Mẫu số 01 - VT (Phiếu nhập kho, Thông tư 99/2025/TT-BTC): the chief accountant is one of its signers,
-- and accounting books the goods sold and returned from it. Its staff download, GET
-- /api/v1/goods-receipts/returns/{receiptId}/voucher, takes procurement-goods-receipts:READ, which until now only the
-- warehouse roles held - so the paper the accountant signs was printed by the warehouse.
--
-- Granted: VIEW_PAGE (the goods-receipt screen, where the voucher is printed from) and READ (list, detail, photos,
-- voucher), as V20261209000200 granted the stock adjustments. Both are read-only: no CREATE, no UPDATE (scan, count,
-- confirm, cancel stay the warehouse's), no procurement-return-receipt-approvals:APPROVE. The Accountant's scope is
-- ALL, so the warehouse scope (BR-SEC-002) does not narrow what it reads, as for the stock adjustments.
--
-- The role that gained a grant advances app_role.version in the same transaction (ADR-0008, CLAUDE.md §5), so no
-- holder keeps a cached matrix without it.
-- =============================================================================

WITH granted AS (
    INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
    SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
      FROM identity.app_role role
     CROSS JOIN identity.permission permission
     WHERE role.code = 'ACCOUNTANT'
       AND permission.code IN ('procurement-goods-receipts:VIEW_PAGE', 'procurement-goods-receipts:READ')
    ON CONFLICT (role_id, permission_id) DO NOTHING
    RETURNING role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM granted);
