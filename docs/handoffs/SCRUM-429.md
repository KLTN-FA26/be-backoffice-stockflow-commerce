# SCRUM-429 / SCRUM-450 backend handoff

Status: **partial backend implementation for independent review; onboarding is blocked, not Done and not ready for release**.

Lane: giangle (`giangle20102005@gmail.com`). QA: Phuongvy, in `SPRINT2-QA-integration`.
Only this worktree was edited. No production394/395, SCRUM-426/428, frontend, Jira changes, push, merge or deploy.
The lane account is the allocation identity; the repository's existing Git author configuration is preserved.

## Snapshot and sources

- Branch: `SCRUM-429-b2b-onboarding`.
- Base: `276567d34972457eecf3d0a87ada9f0f80b301d8`, verified by `git fetch origin develop` on 2026-10-08.
- Tested implementation head: `b95bf0397f85a49589e701f2ae83ed54c2667f1c` (`feat(b2b): close public onboarding paths and prepare invitation domain (SCRUM-450)`).
- This handoff is the following documentation-only commit. QA can inspect the implementation SHA directly or use the branch tip including this report; runtime code is identical.
- Jira [SCRUM-429](https://minh7n3.atlassian.net/browse/SCRUM-429) and [SCRUM-450](https://minh7n3.atlassian.net/browse/SCRUM-450) were read directly through the Atlassian connector on 2026-10-08. Both were To Do, assigned to Hoang Minh Vo; SCRUM-451 is the separate FE subtask. The description's old "owner not decided" wording does not override the approved allocation.
- GitHub read-only checks: PR #36 OPEN at `c2fa904658c031cdaaae39af429b65416eba6750`; PR #38 OPEN at `a919c75ec47571ea0a42419537e460eabb14f7c1`; neither merged. Neither was imported or assumed present.
- Read `SPRINT2-ALLOCATION.md`, repo `CLAUDE.md`, `docs/business-design/2026-10-06-b2b-production-plan.md`, `docs/business-design/db-design/README.md` and migration-plan section 8. No applicable AGENTS.md was found in this worktree or its ancestors.
- User explicitly authorized local commits, overriding CLAUDE's default ask-before-commit rule. The schema remains owned by Tu; no migration, pending migration, entity mapping or fake test schema was added.

## Implemented behavior and API

| Surface | Behavior in this commit | Permission / ownership |
|---|---|---|
| `POST /api/v1/customers/registrations` | Controller removed. Security denies the retired path and descendants: anonymous 401, authenticated 403. | No user, including staff/admin, can reactivate this path. |
| `CustomerService.register` | Compatibility signature retained; always throws `CUSTOMER_REGISTRATION_DISABLED` (403), without reading or creating an account. | Prevents an in-process caller bypassing the HTTP retirement. |
| `POST /api/v1/orders/guest-checkout` | Controller removed; retired path and descendants denied. The old `stockflow.checkout.guest-enabled` setting has no effect, even when explicitly true. | No anonymous checkout, including replay of a previously cached 201. |
| `OrderService.placeGuestOrder` | Compatibility signature retained; always throws `GUEST_CHECKOUT_DISABLED` (403) before reading an order, reserving stock or publishing events. | Normal account checkout, historical guest data and existing order contracts remain available. |
| `GET /api/v1/catalog/products/availability?sku=...` | Actual endpoint already on the base. Requires a CUSTOMER identity linked to an ACTIVE customer. | Customer is resolved from authenticated `userId`, never a supplied customer id. Missing/inactive/blocked profile or staff-only identity: 403. Anonymous: 401. |
| `/api/v1/public/products/availability` | Explicitly denied ahead of the existing public product-media wildcard; no public alias added. | Public product media is outside this change. |
| Login / backoffice / customer profile-address APIs | Existing paths preserved and covered by regression tests. | Sales can list customers; warehouse staff cannot. Customers cannot read or mutate another customer's profile/address book. Staff retain the permission-guarded inventory API. |

Availability returns only the existing stock indicator, with private 10-second caching, no quantities or prices.
SYSTEM_ADMIN is not treated as a CUSTOMER for this portal-specific endpoint; its inventory/backoffice access remains intact.
The development security-off profile still does not create public registration/guest handlers; availability's required `@AuthenticatedUser` also refuses anonymous access there. Production authorization requires security enabled as before.

## Independent domain work (not wired to production endpoints)

- `customer.internal.domain.BusinessCustomerProfile`: required company name, tax code and immutable, nonempty allowed terms (`DEPOSIT`, `CREDIT`, `PREPAID`). Existing customer fields remain the contact name/email/phone. CREDIT selection does not create a credit limit or implement order credit decisions.
- `identity.internal.domain.PortalInvitationToken`: 256 bits from `SecureRandom`, canonical base64url, SHA-256 digest for storage, redacted `toString`. Raw secrets are not persisted or logged by this code.
- `identity.internal.domain.PortalInvitation`: fixed customer/recipient binding; expiry at the exact boundary, single consumption, resend rotation invalidating the previous secret, resend cooldown and stale-version checks. Activated invitations cannot be resent. Invalid/malformed/expired/used tokens share `PORTAL_INVITATION_INVALID` with EN/VI messages.
- `PortalInvitationRepository`: persistence port documenting transaction, stable-row lock, refresh, uniqueness and version requirements. **No adapter, bean, fake in-memory fallback or HTTP activation endpoint exists.** The aggregate is not itself a database concurrency lock.
- No event was added to `contracts`. Catalog's only new module dependency is `customer :: api`; identity/customer/order internals are not imported across modules.

## Blocking dependency: DB owner and subsequent integration

The migrated base has `customer.customer` contact fields and an optional unique `user_id`, but no company, tax-code or allowed-payment-term persistence. Identity has users/roles/sessions, but no activation-invitation persistence. `customer-customers:CREATE` is not seeded or declared. Existing `UPDATE` grants also belong to CUSTOMER and must not be reused as the sole gate for Sales onboarding.

Required mapping for Tu to review (a specification, **not an applied or approved migration**):

| Owner / storage | Required data and constraints |
|---|---|
| Customer business profile, alongside `customer.customer` | Company name and tax code separate from contact `full_name`; nonempty allowed-term set restricted to DEPOSIT/CREDIT/PREPAID. Tax-code normalization and business uniqueness need an owner-approved constraint. Keep existing normalized email uniqueness and unique user linkage. |
| Identity portal invitation (physical table name pending owner) | UUIDv7 id, customer UUID FK, immutable normalized recipient email, unique SHA-256 digest, issued/expires/consumed timestamps, version and normal audit columns. One current invitation per customer; expiry after issue; consumption timestamp within lifetime. No raw token. |
| Identity permission seeds | `customer-customers:CREATE` for Sales and approved admins only; advance affected `app_role.version` in the same migration. Resend can use existing UPDATE only with an explicit Sales/admin guard, never just the shared CUSTOMER grant. |

Do not repurpose `identity.user_session`, audit logs, addresses, Redis cache or order tables as invitation/business-profile storage. There is no tenant dimension in the current schema: ownership is the existing customer-to-user link; no tenant schema was invented.

After owner-approved persistence is available:

1. Wire Sales create through the customer transaction boundary. Validate company/contact/terms, check normalized identity username/email collisions as well as customer email/tax code, and handle unique-constraint races as a conflict. Never attach an invitation to an existing staff identity or silently reuse another customer's account.
2. Persist a non-active, unlinked profile and invitation atomically. Reuse platform HTTP idempotency for create/resend, bind the key to caller and payload, and keep durable uniqueness as the backstop after the HTTP retention window. A conflicting duplicate must not change the original recipient or terms.
3. Customer orchestration calls identity only through a narrow `identity :: api` invite/redemption contract. Credentials and password policy stay in identity. At redemption, create the CUSTOMER identity/session and link/activate the bound customer in the same transaction as consuming the invite; rollback all on failure. An activation request must not choose its own customer, email, role, terms or credit limit.
4. Serialize create/resend/consume on a stable row for that customer, including first issuance; refresh after lock acquisition. Use version/CAS at save. Two consumes: one success. Resend versus consume: one serialized outcome, no two usable links. No check-then-write without DB enforcement.
5. Apply the shared rate limiter to Sales issuance/resend and public activation; persist the per-customer resend cooldown. TTL and cooldown configuration remain to be wired (tests use 24 hours and 60 seconds as examples, not approved deployment defaults).
6. Add notification delivery using a trusted configured portal origin and an after-commit/retryable delivery strategy. A mail failure must be visible and recoverable by resend; do not email before commit. Never return raw secrets to Sales/API callers, place them in audit/event payloads, or send test email outside Mailpit. Activation should POST the token; avoid referrer/query logging leaks.
7. Add actual PostgreSQL duplicate/concurrency/rollback tests, expired/reused/replaced-link HTTP tests, staff/customer authorization tests for create/resend, and Mailpit assertions. These are pending, not claimed as passing here.

No create/resend/activation API is published yet. FE SCRUM-451 must not build against an invented endpoint. Pricing (428), quotation (426), credit accounting, order release and production remain outside this patch.

## Validation and reproduction

| Check actually run | Result |
|---|---|
| Unmodified base, `mvn -o test` | **462 tests, 0 failures, 0 errors, 0 skipped**, 3m26s; completed 2026-10-08 14:57 +07. |
| Final implementation, `mvn -o clean test` | **473 tests, 0 failures, 0 errors, 0 skipped**, 3m17s; completed 2026-10-08 15:13 +07. |
| Included B2B HTTP integration | **5 passed**: retired routes/toggle, old cached 201 replay, active customer availability, cross-customer ownership, login/backoffice. |
| Included domain/service coverage | Invitation **7**, business profile **2**, customer service **3**, catalog service **5**, account/disabled guest service **2**, all passed. These do not assert persistence concurrency for the pending invitation adapter. |
| Included architecture / module / message bundles | Architecture **18**, Modularity **3**, MessageBundle **5**, all passed. |
| `PYTHONUTF8=1 python tools/verify.py` | Exit 1 on Windows; **same 36 findings as archived base, zero new findings**, explained below. |
| `git diff --check`, staged diff check | Passed. No migration/entity or contracts changes. |
| Mailpit `/api/v1/messages` | **0 messages** after the full run; no real recipient contacted. |

Intermediate test-development failures were corrected: ambiguous MVC handler-mapping injection and JDBC fixture inserts needing an explicit transaction because Hikari auto-commit is disabled. Final results above include those corrected tests; none were disabled or skipped.

Local evidence (ignored, not committed): `baseline-scrum429.log`, `final-scrum429.log`, `baseline-verify-scrum429.log`, `verify-final-scrum429.log`, and `target/surefire-reports`. Logs contain only synthetic local fixtures; reproduce in QA rather than treating copied output as an independent QA pass.

Environment: Java 21.0.9, Maven 3.9.9, Docker Desktop. Each Testcontainers context gets fresh PostgreSQL 16 / Redis 7 containers and dynamically assigned ports. No shared database was reset. MockMvc has no application listening port; the B2B HTTP test verifies real RSA signatures, expiry, persisted sessions and server-side permissions with a test-local decoder using the application's public key (no external JWKS request).

Mail sink for this run: container `scrum429-mailpit`, SMTP `127.0.0.1:55904`, UI `http://127.0.0.1:55905`; all new B2B fixture emails use `example.test`. The sink is stopped after verification. This run does not implement or claim invitation email delivery. Local test artifacts use `target/scrum429-storage`.

PowerShell reproduction (use available Maven 3.9.9 or the equivalent `mvn` on PATH):

```powershell
docker run --detach --name scrum429-qa-mailpit --publish 127.0.0.1::1025 --publish 127.0.0.1::8025 axllent/mailpit:latest
docker port scrum429-qa-mailpit
# Substitute its printed SMTP host port below. Never use a production SMTP configuration.
$maven429 = 'C:\Users\VO HOANG MINH\.m2\wrapper\dists\apache-maven-3.9.9-bin\4nf9hui3q3djbarqar9g711ggc\apache-maven-3.9.9\bin\mvn.cmd'
& $maven429 -o clean test '-Dspring.mail.host=127.0.0.1' '-Dspring.mail.port=<SMTP_PORT>' '-Dspring.mail.properties.mail.smtp.auth=false' '-Dspring.mail.properties.mail.smtp.starttls.enable=false' '-Dstockflow.storage.local-directory=target/scrum429-storage'
$env:PYTHONUTF8 = '1'
python tools/verify.py
git diff --check
```

To focus on the B2B HTTP path use `-Dtest=B2bAccessIntegrationTest`; domain coverage uses `BusinessCustomerProfileTest,PortalInvitationTest`, and existing service tests cover disabled registration/guest plus normal account checkout.

`verify.py` on this Windows environment returns 36 pre-existing false findings because its test-path exclusion uses `/src/test/` against backslash paths. An archived, unmodified base has exactly the same 36 normalized findings; no new finding. The first invocation without `PYTHONUTF8=1` also fails reading UTF-8 under cp1252. The script/rules were not weakened; Maven architecture and module checks are the authoritative checks.

## Main files and QA focus

- `common/security/ResourceServerSecurityConfig.java`: retired-route matcher ordering, especially before the public-media wildcard and idempotency replay.
- `catalog/.../CatalogController.java`, `StorefrontAvailabilityService.java`, `catalog/package-info.java`: strict portal role and active-profile linkage via module API.
- Removed `CustomerRegistrationController` and `GuestCheckoutController`; retained fail-closed service signatures in customer/order. Removed obsolete guest configuration.
- New customer business-profile and identity invitation domain files described above; `common/error/ErrorCode.java`, both i18n bundles.
- `src/test/java/com/stockflow/B2bAccessIntegrationTest.java`: real security/DB checks and cached-success retirement regression; related customer/catalog/order domain and service tests.
- `docs/handoffs/SCRUM-429.md`: this report.

QA should review the concrete implementation SHA recorded above in its own integration worktree. Do not edit the dev branch, merge develop, mark the story Done, or infer that domain-only invite tests prove database race safety. The missing migration/permissions, adapter, orchestration, email delivery and end-to-end invite tests remain release blockers.
