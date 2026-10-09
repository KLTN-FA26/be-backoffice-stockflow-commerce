-- Follows the four published PO migrations and precedes PR #38's V20260930001000/1100.
-- Published migration checksums are preserved; this review migration was not released.
-- Remove the unintended supplier APPROVE seed without changing staff's other permissions.
WITH removed AS (
    DELETE FROM identity.role_permission rp
    USING identity.app_role r, identity.permission p
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
      AND r.code = 'PROCUREMENT_STAFF' AND p.code = 'procurement-suppliers:APPROVE'
    RETURNING rp.role_id
)
UPDATE identity.app_role SET version = version + 1 WHERE id IN (SELECT role_id FROM removed);

-- Invalidate cached SYSTEM_ADMIN grants in the same transaction.
INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), r.id, p.id, 0, NOW(), 'flyway'
FROM identity.app_role r CROSS JOIN identity.permission p
WHERE r.code = 'SYSTEM_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

UPDATE identity.app_role SET version = version + 1 WHERE code = 'SYSTEM_ADMIN';

-- Generations identify manual recovery decisions; attempts count actual transport calls.
ALTER TABLE notification.delivery_log ADD COLUMN attempt_number INTEGER NOT NULL DEFAULT 1;
WITH numbered AS (
    SELECT id, row_number() OVER (
        PARTITION BY operation_reference, delivery_generation ORDER BY created_at, id
    ) AS attempt_number
    FROM notification.delivery_log WHERE operation_reference IS NOT NULL
)
UPDATE notification.delivery_log log SET attempt_number = numbered.attempt_number
FROM numbered WHERE log.id = numbered.id;
ALTER TABLE notification.delivery_log ADD CONSTRAINT ck_delivery_attempt_number CHECK (attempt_number > 0);

-- Setting a missing date is not changing an agreed date. Manual recovery still needs a reason
-- through the separate generation/reconciled check, including orders without an earlier date.
ALTER TABLE procurement.po_delivery_decision DROP CONSTRAINT po_delivery_decision_check1;
ALTER TABLE procurement.po_delivery_decision ADD CONSTRAINT ck_po_delivery_date_reason CHECK (
    previous_expected_at IS NULL OR previous_expected_at IS NOT DISTINCT FROM expected_at
    OR NULLIF(btrim(reason), '') IS NOT NULL
);
