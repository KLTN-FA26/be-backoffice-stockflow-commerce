-- Read-only. Run against the intended database before deploying the unmerged PR migration rename.
SELECT current_database(), current_user;
SELECT version, description, script, checksum, success FROM public.flyway_schema_history
WHERE version IN ('20260918000100','20260923000100','20260923000200',
                  '20260925000100','20260925000200','20260925000300') ORDER BY installed_rank;
SELECT lower(code) AS duplicate_code, count(*) FROM procurement.supplier
GROUP BY lower(code) HAVING count(*) > 1;
SELECT tax_code, count(*) FROM procurement.supplier WHERE tax_code IS NOT NULL
GROUP BY tax_code HAVING count(*) > 1;
SELECT id, code FROM procurement.supplier WHERE email IS NULL OR btrim(email) = ''
   OR code !~ '^[A-Za-z0-9._-]{1,64}$' OR btrim(name) = ''
   OR (tax_code IS NOT NULL AND (tax_code !~ '^[0-9A-Za-z][0-9A-Za-z-]{6,30}[0-9A-Za-z]$' OR tax_code !~ '[0-9]'));
SELECT conrelid::regclass AS relation, conname FROM pg_constraint
WHERE connamespace = 'procurement'::regnamespace AND NOT convalidated;
