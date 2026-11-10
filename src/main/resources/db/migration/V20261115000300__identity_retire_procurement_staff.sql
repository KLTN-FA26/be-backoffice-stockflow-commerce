-- =============================================================================
-- The role PROCUREMENT_STAFF goes (team plan 5-8; kltn-docs 00 §2 decision 2026-11-04; QD-13; cleanup §8.1).
--
-- Purchasing from suppliers is gone (V20261113000200, team plan 4A-8/4A-9): goods come from producers against
-- production orders, received by the warehouse (kltn-docs 03). What the role still held after that migration - the
-- goods receipts, read and written, the inventory item list, the warehouse list, the inbox - is the warehouse's own
-- work, held by Warehouse Staff and the warehouse manager already. So nothing is handed on:
--
--   1. the holders: an account with no other role is disabled with a reason (QD-13: muahang1, muahang2), as
--      V20261108000100 did for PRODUCTION_STAFF - a staff account is not left active without a role (00 §2.5.1);
--      one with another role keeps it and only loses this one;
--   2. their live sessions end: ACCOUNT_DISABLED for a disabled account, ROLE_CHANGED for the others (00 §2.5.1
--      rule 5) - a signed-in holder would otherwise go on with a token naming a role that no longer exists;
--   3. every grant of the role - generically, every row: grants added to it by migrations merged before this one go
--      too - advancing its version (ADR-0008), then its user_role rows, then the role.
--
-- Nothing is archived (owner's decision QD-1, 2026-11-10: the database is rebuilt on deploy). Nothing here names a
-- table purchasing dropped (procurement.approval_limits went with V20261113000200).
-- =============================================================================


-- ---------------------------------------------------------------------------------------------
-- 1. The holders
-- ---------------------------------------------------------------------------------------------
CREATE TEMPORARY TABLE procurement_staff ON COMMIT DROP AS
SELECT ur.user_id,
       NOT EXISTS (SELECT 1 FROM identity.user_role other
                    WHERE other.user_id = ur.user_id AND other.role_id <> ur.role_id) AS only_role
  FROM identity.user_role ur
  JOIN identity.app_role r ON r.id = ur.role_id
 WHERE r.code = 'PROCUREMENT_STAFF';

INSERT INTO identity.user_status_change (id, user_id, action, from_status, to_status, reason, actor_id, actor_username,
                                         changed_at, version, created_at, created_by)
SELECT gen_random_uuid(), u.id, 'DISABLE', u.status, 'DISABLED',
       'Purchasing from suppliers was removed: the role PROCUREMENT_STAFF no longer exists', NULL, 'flyway', NOW(), 0,
       NOW(), 'flyway'
  FROM identity.app_user u
  JOIN procurement_staff ps ON ps.user_id = u.id
 WHERE ps.only_role AND u.status <> 'DISABLED';

UPDATE identity.app_user u
   SET status = 'DISABLED', version = u.version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
  FROM procurement_staff ps
 WHERE ps.user_id = u.id AND ps.only_role AND u.status <> 'DISABLED';


-- ---------------------------------------------------------------------------------------------
-- 2. Their live sessions end
-- ---------------------------------------------------------------------------------------------
UPDATE identity.user_session s
   SET revoked_at       = NOW(),
       revoked_reason   = CASE WHEN ps.only_role THEN 'ACCOUNT_DISABLED' ELSE 'ROLE_CHANGED' END,
       version          = s.version + 1,
       last_modified_at = NOW(),
       last_modified_by = 'flyway'
  FROM procurement_staff ps
 WHERE ps.user_id = s.user_id AND s.revoked_at IS NULL AND s.expires_at > NOW();


-- ---------------------------------------------------------------------------------------------
-- 3. The role: every grant, then who holds it, then the row
-- ---------------------------------------------------------------------------------------------
WITH lost AS (
    DELETE FROM identity.role_permission rp
     USING identity.app_role r
     WHERE r.id = rp.role_id
       AND r.code = 'PROCUREMENT_STAFF'
    RETURNING rp.role_id
)
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE id IN (SELECT DISTINCT role_id FROM lost);

DELETE FROM identity.user_role ur
 USING identity.app_role r
 WHERE r.id = ur.role_id
   AND r.code = 'PROCUREMENT_STAFF';

DELETE FROM identity.app_role WHERE code = 'PROCUREMENT_STAFF';
