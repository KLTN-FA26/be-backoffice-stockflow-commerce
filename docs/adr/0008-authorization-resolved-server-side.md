# ADR-0008: Permissions resolved on the server, cached by role version

Status: accepted (2026-09-30). Supersedes the "Token strategy" section of
[ADR-0004](0004-permission-model.md) and the last consequence of [ADR-0006](0006-session-revocation.md).
Every other part of both stands.

## Context

A token carried the user's roles, **every permission** those roles granted, and a `scope_level`.
Three things went wrong with that:

- **Size.** A role with 134 of 382 permissions produced a 3.8-4.8 KB token. The backoffice frontend
  keeps the token in a cookie as well as sending it as `Authorization`, so every same-origin API call
  carried it twice; together with ordinary browser headers that passed Tomcat's 8 KB header limit and
  every call answered `400 Request header is too large`. It was found testing the purchase-order and
  supplier screens against the real backend (30/9) and blocks every screen, not one.
- **Staleness.** A token is a snapshot. Unticking a permission reached nobody until their token
  expired - up to 8 hours with the ADR-0006 lifetime.
- **No way to edit.** The matrix screen (SCRUM-376/49) was meant to save role grants at runtime
  (ADR-0004: "roles become named bundles of permissions, editable at runtime"). There was no save
  endpoint, and adding one without solving staleness would have produced a screen whose ticks do
  nothing for a working day.

ADR-0004 planned `roles` plus a `perm_ver` claim with changes published over Kafka. That was written
for microservices; this is a modular monolith with one database and one Redis, and the
`permission_version` column that section refers to was never created.

## Decision

### The token authenticates; it does not authorise

The token carries `sub`, `sid`, `jti`, `iat`, `exp`, `preferred_username` and `roles`. No
`permissions`, no `scope_level`. `roles` stays because it is small and cannot go stale: a role
assignment or revocation already ends every session of that user (ADR-0006). A token issued before
this change still has a `permissions` claim; it is ignored, not merged - trusting it would keep a
revoked permission alive for the rest of that token's life.

### Permissions are resolved per request

`StockflowJwtAuthenticationConverter` (common) asks `RoleAuthorizationLookup` (a port declared in
common, implemented by identity's `RoleAuthorizationCache`, the same pattern as `ActiveSessionCheck`)
for the union of what the token's roles grant, and builds the authorities from that. Every existing
consumer - `@RequiresPermission`, `PermissionChecker`, `CurrentUserProvider` - keeps reading
authorities exactly as before. The data scope travels on `StockflowAuthenticationToken`;
`CurrentUserProvider` reads it there, because a scope read from the now-absent claim would fall back
to `OWN` and narrow every scoped query.

### Cache layout: role + version, never deleted

```
stockflow:{cacheVersion}:authz:role-versions        HASH  roleCode -> version        TTL 60 s
stockflow:{cacheVersion}:authz:role:{code}:v{ver}   SET   resource:ACTION, plus "~"  TTL 1 h
```

- **The version is `app_role.version`**, the column `BaseEntity` already maps. No migration.
- **A grant change advances it** with a compare-and-set in the same transaction
  (`UPDATE app_role SET version = version + 1 WHERE id = ? AND version = ?`). Writing only
  `role_permission` rows would never touch the role's own version.
- **The pointer only moves forward**: a Lua compare-and-set, run after commit. A late callback cannot
  put back an older version.
- **Grants are cached under the version read with them in one SQL statement**, never under the version
  the pointer claimed. A lagging pointer costs a cache miss, not a wrong answer.
- **Nothing is deleted.** A delete-on-change cache loses a race: a reader loads the old grants, the
  edit commits and deletes, the reader writes the old grants back. With the version in the key, the
  late write lands under a version nobody asks for.
- `~` is always a member, because Redis cannot store an empty set and a role granting nothing must be a
  cached answer, not a permanent miss.

Two Redis round-trips per request: `HMGET` on the pointer, then one pipelined `SMEMBERS` per role
(usually one role).

### Failure behaviour

| What is down | Behaviour (measured against real containers, 2026-10-01) |
|---|---|
| Redis | Lookups read Postgres directly. The first request after the failure waits out the 2 s Redis timeout; the instance then skips Redis for 30 s (`REDIS_BACKOFF`), so the rest answer in ~0.1 s. Without the back-off every request was 2 s slower. |
| Redis, right after an edit commits | The pointer is not advanced; holders keep the old grants until the pointer expires - at most 60 s. That is why the pointer's TTL is a minute. |
| Redis and the grants query | **503 `AUTHORIZATION_UNAVAILABLE`** in the platform envelope, with a correlation id (tested by locking `role_permission` past the 10 s statement timeout). Never a 401, never a grant. |
| Postgres | The session check (ADR-0006) cannot run, and answers **503 `AUTHORIZATION_UNAVAILABLE`** too. It used to escape the filter chain, be forwarded to `/error`, and come back as an anonymous **401** - a database blip signed every user out. `SessionValidator` now rethrows a store failure as a plain `JwtException`. |

Spring Security 6 rethrows `AuthenticationServiceException` from the bearer-token filter by default,
which ends in a container 500 page outside the envelope. `ResourceServerSecurityConfig` turns that off
for the bearer filter so the exception reaches `ApiAuthenticationEntryPoint`, which answers 503. The
same applies to an unreachable key set, and to the session store now that `SessionValidator` reports
its failure as a `JwtException` instead of letting it escape.

## API

All under `/api/v1/identity`, all wrapped in `ApiResponse`.

| Method and path | Guard | Purpose |
|---|---|---|
| `GET /roles/{roleCode}/permissions` | `identity-rbac:READ` | The matrix, now with `version` and `editable` |
| `PUT /roles/{roleCode}/permissions` | `identity-rbac:APPROVE` | Replace the role's grants |
| `GET /me/permissions` | signed in | The caller's `roles`, `permissions` (sorted `resource:ACTION`) and `dataScope` |
| `GET /me` | signed in | The caller's `userId`, `username`, `email`, `fullName`, `status`, `roles`, `lastLoginAt` - the login response carries only a token |

`PUT` body - the complete set, not a diff, plus the version the editor loaded:

```json
{ "version": 7, "permissions": ["procurement-purchase-orders:READ", "procurement-purchase-orders:APPROVE"] }
```

With security switched off (`local`, `test`) no token is read and every endpoint is open, so
`GET /me/permissions` answers with every declared permission instead of a 401 that would make the
frontend hide every screen. `GET /me` still needs a signed-in user.

It answers the saved matrix (with the new `version`), or:

| Code | Status | When |
|---|---|---|
| `ROLE_NOT_FOUND` | 404 | No such role |
| `UNKNOWN_PERMISSION` | 400 | A code is malformed, not declared by any `@PermissionResource`, or declared but not seeded; the message lists them. Nothing is written. |
| `ROLE_PERMISSIONS_CHANGED` | 409 | Someone saved after this editor loaded. Not retryable as-is: reload, then save. |
| `ROLE_NOT_EDITABLE` | 409 | `CUSTOMER` - its grants are the storefront's self-service surface and stay in migrations |
| `RBAC_LOCKOUT` | 409 | The change would leave no role holding `identity-rbac:APPROVE` |

The guard is `identity-rbac:APPROVE` rather than a new `UPDATE` action: deciding who may do what is
the most sensitive action in the system, APPROVE is already excluded from "select all", it is already
seeded (ECOMMERCE_ADMIN), and it needs no catalog change or migration.

Every save takes one transaction-scoped advisory lock, so two administrators each removing the RBAC
right from a different role cannot both pass the lockout check. The save is audited
(`AuditAction.GRANT`, resource `role`).

## Alternatives considered

- **Keep permissions in the token and raise Tomcat's header limit.** Fixes the 400, keeps the
  staleness, and the next role to grow breaks it again.
- **Cache key `userId + updatedAt`.** A tick changes `role_permission`, not the user row, so the user's
  `updatedAt` never moves and the cache never notices. Grants belong to roles; so does the key.
- **A timestamp as the version.** Two edits in one millisecond collide, and clocks differ between
  instances. A counter does neither.
- **A version counter kept only in Redis.** After a flush it restarts at 1 and can match a stale key
  that survived the flush. `app_role.version` is durable.
- **One global epoch** bumped on any change. Simpler (one pointer), but every edit invalidates every
  role. With ten roles either works; per-role was chosen because it is what the grants actually hang on.
- **Drop `roles` from the token too** (a per-user version as well). Would let a role assignment take
  effect without signing the user out. Not needed yet; the per-role design leaves room for it.

## Consequences

- **Tokens shrink to well under 1 KB** and the header error disappears without any frontend change.
- **A tick reaches the next request** of every holder; no one signs in again. Role *assignment* still
  ends the user's sessions (ADR-0006), because `roles` is in the token.
- **Any write to `role_permission` must advance `app_role.version` in the same transaction.** The
  service does. A future migration that changes seeded grants must do it too
  (`UPDATE identity.app_role SET version = version + 1 WHERE code IN (...)`), or holders keep the old
  grants until the cached set expires (one hour).
- **The frontend learns permissions from `GET /me/permissions`**, not from the token, and should
  refetch it after a 403. It should also stop storing the whole token in a cookie (SCRUM-384): a flag
  is enough for the route guard.
- `CacheNames.USER_PERMISSIONS` is left declared for now; nothing uses it, and this design does not use
  Spring's cache abstraction (versioned keys are dynamic, and plain strings avoid the serializer's
  default-typing trap).
