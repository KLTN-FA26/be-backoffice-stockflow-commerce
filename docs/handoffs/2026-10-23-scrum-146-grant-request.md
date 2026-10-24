# SCRUM-146 — grant request for Tú: warehouse staff enter cycle counts

*2026-10-23 · from Phương · for Tú (schema owner) · design spec
`docs/superpowers/specs/2026-10-23-scrum-146-cycle-count-design.md` §8*

## What is asked

One migration, no table changes: give `WAREHOUSE_STAFF` the `inventory-cycle-counts:UPDATE` permission.
Version number is yours — it must be newer than the newest migration on `develop` when it merges.

```sql
-- =============================================================================
-- inventory-cycle-counts:UPDATE for WAREHOUSE_STAFF - the counter enters counted quantities (SCRUM-146).
-- Module: identity (grants) for inventory
--
-- docs 04c F-CNT step 03: the warehouse staff count on mobile. V20261023000300 gave them VIEW_PAGE and READ
-- and said entering counts is UPDATE and comes with the cycle-count story; this is that grant.
-- =============================================================================

INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at, created_by)
SELECT gen_random_uuid(), role.id, permission.id, 0, NOW(), 'flyway'
  FROM identity.app_role role
  JOIN identity.permission permission
    ON permission.code = 'inventory-cycle-counts:UPDATE'
 WHERE role.code = 'WAREHOUSE_STAFF'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- Grants are cached per role version (ADR-0008): advance the role whose grants changed.
UPDATE identity.app_role
   SET version = version + 1, last_modified_at = NOW(), last_modified_by = 'flyway'
 WHERE code = 'WAREHOUSE_STAFF';
```

It is the same shape as `V20261023000300__inventory_cycle_count_counter_grant.sql`. The permission row
`inventory-cycle-counts:UPDATE` already exists (seeded by `V20260903000100`), so only the grant is new.

## Why

- `UPDATE` on `inventory-cycle-counts` guards the counter's three endpoints:
  `PUT /api/v1/inventory/cycle-counts/{id}/lines/{lineId}/count`, `POST /{id}/lines` (a found line) and
  `POST /{id}/submission`. The counter is the warehouse staff (docs 04c F-CNT step 03); today only
  `WAREHOUSE_MANAGER` and `INVENTORY_PLANNER` hold `UPDATE`, so the people a blind count is made for get 403.
- Granting `UPDATE` does not show staff the book quantity on the count screen: every cycle-count answer
  (`GET`, and what counting, adding a line and submitting return) hides `expected_qty`, the note and the
  adjustments of a blind count from whoever lacks `APPROVE`, and the list carries no lines. On an assigned
  count only the assignee (or an `APPROVE` holder) may count; anyone else gets `403 CYCLE_COUNT_NOT_ASSIGNEE`.
- **Limitation, not changed by this grant:** the count screen is blind, the rest of the system is not.
  Staff who hold `READ` on stock items, stock adjustments or the stock ledger can still see quantities there.
  Whether to restrict those screens while a blind count is open is an owner decision; nothing in the code
  does it today.
- Submission only raises `COUNT_VARIANCE` adjustments `PENDING_APPROVAL`; nothing touches stock until a
  manager approves (four-eyes, BR-STK-005). Staff get no new power over stock.
- **The version bump is required** (ADR-0008, CLAUDE.md §5): without it, staff keep their cached grants for
  up to an hour after the migration runs.

Until it lands, only managers and planners can count; no code waits on it.

## The test that changes in the same commit

`src/test/java/com/stockflow/inventory/SkuReservationAllocationIntegrationTest.java`,
`shortPickOpensOneBlindRecount`, pins the staff's cycle-count grants to exactly `VIEW_PAGE, READ`:

```java
.as("docs 04c F-CNT: the warehouse staff count").containsExactlyInAnyOrder(
        "inventory-cycle-counts:VIEW_PAGE", "inventory-cycle-counts:READ");
```

With the migration it fails. In the same commit, add `"inventory-cycle-counts:UPDATE"` to that list (and
nothing more — `CREATE` and `APPROVE` must stay out: staff neither plan, cancel, nor send counts for
recount). Do not create the migration on this branch; it is yours to write.
