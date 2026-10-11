# db/pending — contract migrations written ahead, not applied

Flyway reads `db/migration` (and `db/demo` in local/test). This directory is in neither location, so
nothing here runs. Each file is the **contract** half of an expand/contract change from
`docs/business-design/db-design/2026-09-28-migration-plan.md`: it removes the old tables and adds the foreign keys
that could not exist while the old code was still writing the old tables.

| File | Run after | Does |
|---|---|---|
| `C0__validate_foreign_keys.sql` | see its header | validates the foreign keys added `NOT VALID` |

C1–C4 were activated by the legacy-tables removal as `V20261011000300`–`V20261011000600`, after
`V20261011000200` carried the legacy rows to the new tables and archived them in
`platform.legacy_archive`.

## Activating one

1. Check its "Run when" header against the code on `develop`.
2. Rename it `V<yyyyMMddHHmm00>__<same description>.sql`, with a version newer than the newest file
   in `db/migration` at that moment (Flyway does not run out of order).
3. Move it into `db/migration`, delete it from here.
4. Run the per-PR checklist in the plan §4: fresh database, database with data, `verify.py`,
   `ModularityTest`/`ArchitectureTest`, boot, curl the affected flows.

Every file starts with an orphan check that stops with the table and row count instead of failing
half way. If it stops, fix the data (or the code that wrote it); never delete the check.
