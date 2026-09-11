# ADR-0004: Action-per-resource permissions, not hard-coded roles

- **Status:** Accepted
- **Date:** 2026-09-07
- **Supersedes:** the role-only checks that were in the first draft of `common-security`

## Context

The first version guarded endpoints with `@PreAuthorize("hasAnyAuthority('WAREHOUSE_STAFF')")`.
That model has a fixed cost the BRD cannot absorb: every change to who may do what is a code
change, a rebuild and a deploy. With ten roles across fourteen services, the first month of real
use would have produced a dozen such deploys.

The BRD also asks for finer control than a role name can express: BRD 3.2.2 wants approval
thresholds, 3.19.1 wants a permission management screen, and warehouse staff must be limited to
their own site.

## Decision

A permission is **(resource, action)**, for example `stock-items:CREATE`.

- **Resources** are declared in code with `@PermissionResource` on the controller that serves them,
  carrying a stable code, a group, a label, the frontend route and the API path.
- **Actions** are a closed enum: `VIEW_PAGE`, `READ`, `CREATE`, `UPDATE`, `DELETE`, `APPROVE`,
  `EXPORT`. `DELETE`, `APPROVE` and `EXPORT` are marked sensitive and are excluded from any
  "grant all" gesture.
- **Endpoints** are guarded with `@RequiresPermission(resource = ..., action = Action.CREATE)`.
- **Roles** become nothing more than named bundles of permissions, editable at runtime.
- **`PermissionCatalogValidator` fails startup** when an endpoint requires a permission no resource
  declares, or an action a resource does not list.

## Why the catalog is generated from code

In a database-only permission model the catalog drifts the first time someone ships a screen and
forgets the seed script: the endpoint exists, nothing guards it, and nobody finds out until an
audit. Generating the catalog from annotations means the matrix is derived from the truth. It also
makes "382 permissions" a computed number rather than a list a person has to remember to update.

## Why VIEW_PAGE and READ are separate

They answer different questions. `VIEW_PAGE` controls the menu entry and the route guard;
`READ` controls the data behind it. A dashboard widget may need the data without the page, and a
landing page may open with an empty state before any data is fetched. Collapsing them into one
permission means you can never express either case.

## What this model does NOT solve

Worth stating plainly, because an action-per-screen matrix is often assumed to cover more than it
does:

1. **Row visibility.** "May create an adjustment" is not "may see warehouse B". Handled by the
   separate `DataScope` dimension (OWN / TEAM / WAREHOUSE / ALL) stored on the role and applied as
   a mandatory filter in the query layer. **The filter is not implemented yet** - it is the largest
   remaining gap in this design.
2. **Field visibility.** Payroll is the obvious case: a user may open the payroll screen and still
   have no business seeing individual salary figures. Needs field-level rules or a redacted
   projection; neither exists today.
3. **Approval limits.** BRD 3.2.2 and 3.15 ask for approval "within limits". A boolean `APPROVE`
   cannot express "up to 50 million VND". That is a policy, evaluated in the domain, and it belongs
   in the aggregate rather than in the permission table.
4. **Delegation and temporary grants.** No support for "acting manager while she is on leave".

## Token strategy, and its unfinished part

Today a token carries a `permissions` claim. That is fine for a skeleton and wrong for production
for two reasons: a role holding 134 of 382 permissions produces roughly 3 KB of claim, and a token
is a snapshot, so revoking a permission does not reach a token already issued.

The intended shape, and the reason `permission_version` exists in the identity schema: the token
carries only `roles` plus a `perm_ver` counter; identity-service publishes role-to-permission
changes over Kafka; each service keeps a local cache and rejects any token whose `perm_ver` is
behind the version it has seen. Revocation then takes effect on the next request instead of at
token expiry.

## Consequences

- Adding a screen means adding one `@PermissionResource`, not a migration and a deploy.
- An administrator can build a new role without a developer.
- The cost is one more thing that can be misconfigured at runtime; the catalog validator and the
  named permission in the 403 message exist to keep that debuggable.
