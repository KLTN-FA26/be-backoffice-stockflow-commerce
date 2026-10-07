-- Preserve deployed feature migration checksums and invalidate cached SYSTEM_ADMIN grants.
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
