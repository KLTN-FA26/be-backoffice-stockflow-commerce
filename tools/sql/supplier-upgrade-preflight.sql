-- SCOPE: a database that has NOT yet run the legacy-tables removal (V20261011000300-0600, contracts
-- C1-C4). Those drop the old tables this script reads; on a database past them, or a fresh one, it
-- stops at the first missing table. See README.md here.
-- Read-only. Classify history before deployment; see docs/SCRUM-115-118-backend.md for recovery.
SELECT current_database(), current_user;
SELECT version, description, script, checksum, success FROM public.flyway_schema_history
WHERE version IN ('20260918000100','20260923000100','20260923000200',
                  '20260925000100','20260925000200','20260925000300','20260926000100',
                  '20260929000100','20260929000200',
                  '20260930000100','20260930000200','20260930000300','20260930000400',
                  '20260930000500','20260930001000','20260930001100',
                  '20261001000100','20261008000100') ORDER BY installed_rank;
-- Remote 9fbb90f can have the four PO versions but be missing these earlier develop prerequisites.
SELECT required.version AS missing_develop_prerequisite
FROM (VALUES ('20260929000100'), ('20260929000200')) required(version)
WHERE EXISTS (SELECT 1 FROM public.flyway_schema_history
              WHERE version = '20260930000400' AND success)
  AND NOT EXISTS (SELECT 1 FROM public.flyway_schema_history h
                  WHERE h.version = required.version AND h.success);
-- This local-only alias must not be confused with #38's canonical migration at the same number.
SELECT version, script, 'UNPUBLISHED_PO_REVIEW_ALIAS_REQUIRES_RECONCILIATION' AS blocker
FROM public.flyway_schema_history
WHERE version = '20261008000100' AND script LIKE '%po_review_permissions_and_attempt_numbers%';
SELECT lower(code) AS duplicate_code, count(*) FROM procurement.supplier
GROUP BY lower(code) HAVING count(*) > 1;
SELECT tax_code, count(*) FROM procurement.supplier WHERE tax_code IS NOT NULL
GROUP BY tax_code HAVING count(*) > 1;
-- These require operator review; migration does not invent contacts or supplier codes.
SELECT id, code FROM procurement.supplier WHERE email IS NULL OR btrim(email) = ''
   OR code !~ '^[A-Za-z0-9._-]{1,64}$' OR btrim(name) = '';
-- The published V20260930000200 archives these values to legacy_tax_code and clears tax_code.
SELECT id, code, tax_code AS tax_identifier_to_archive FROM procurement.supplier
WHERE tax_code IS NOT NULL AND (tax_code !~ '^[0-9A-Za-z][0-9A-Za-z-]{6,30}[0-9A-Za-z]$' OR tax_code !~ '[0-9]');
SELECT conrelid::regclass AS relation, conname FROM pg_constraint
WHERE connamespace = 'procurement'::regnamespace AND NOT convalidated;
