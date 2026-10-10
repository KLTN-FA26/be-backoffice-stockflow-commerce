# tools/sql — operator scripts for upgrading old databases

These run by hand (`psql -X -v ON_ERROR_STOP=1 -f <script>`), never by Flyway. Each one is for a
database that is still **before** the legacy-tables removal, i.e. has not yet run
`V20261011000300`–`V20261011000600` (contracts C1–C4). They read the old tables, so on a database past
those — or a fresh one — they stop at the first missing table. That is expected, not a defect.

| Script | Run when | Does |
|---|---|---|
| `supplier-upgrade-preflight.sql` | before deploying a release that contains `V20260930000100` | read-only: classifies migration history and duplicate / invalid supplier rows; `V20260930000100` refuses to run and names this script while duplicates exist |
| `validate-supplier-constraints.sql` | after the preflight findings are reconciled, with a backup | validates the supplier / PO checks added `NOT VALID`; atomic |
| `inventory-commerce-upgrade-preflight.sql` | before deploying the combined #36/#38 release | read-only: migration history and SKU rows that release rejects |

Recovery steps: `docs/SCRUM-115-118-backend.md` (suppliers) and `docs/SCRUM-70-71-backend.md`
(inventory / commerce). Once every environment is past C1–C4 these scripts can be deleted, together with
those two sections.
