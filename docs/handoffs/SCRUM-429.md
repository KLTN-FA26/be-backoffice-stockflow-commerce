# SCRUM-429 / SCRUM-450 backend handoff

Status: **partial backend implementation for independent review; onboarding is blocked, not Done and not ready for release**.

Lane: giangle (`giangle20102005@gmail.com`). QA: Phuongvy, in `SPRINT2-QA-integration`.
Only this worktree was edited. No production394/395, SCRUM-426/428, frontend, Jira changes, push, merge or deploy.
The lane account is the allocation identity; the repository's existing Git author configuration is preserved.

## Snapshot and sources

- Branch: `SCRUM-429-b2b-onboarding`.
- Base: `276567d34972457eecf3d0a87ada9f0f80b301d8`, verified by `git fetch origin develop` on 2026-10-08.
- **Final tested code SHA: `896deb5a8531bc4aa2a173501d8d44c81cfa6013`**. The following handoff/evidence commit changes documentation only; its full SHA is supplied in the delivery response and can be recovered with `git log -1 --format=%H -- docs/handoffs/SCRUM-429.md` at the delivered tip.
- Original tested implementation: `b95bf0397f85a49589e701f2ae83ed54c2667f1c`; original documentation HEAD: `35865934d2c357c8cb29bafb5c5c5f18ccd99cd3`. The self-review below supersedes this snapshot with one bounded security fix.
- Jira [SCRUM-429](https://minh7n3.atlassian.net/browse/SCRUM-429) and [SCRUM-450](https://minh7n3.atlassian.net/browse/SCRUM-450) were initially To Do when read. The live self-review read at **2026-10-08 15:36:12 +07** confirms **both In Progress**. SCRUM-451 is the separate FE subtask. The description's old "owner not decided" wording does not override the approved allocation.
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
2. Persist the agreed pre-activation customer state and invitation atomically. A non-active, unlinked profile is a proposal, not an approved mapping to the existing INACTIVE status; resolve the lifecycle policy with the business/DB owner first. Reuse platform HTTP idempotency for create/resend, bind the key to caller and payload, and keep durable uniqueness as the backstop after the HTTP retention window. A conflicting duplicate must not change the original recipient or terms.
3. Customer orchestration calls identity only through a narrow `identity :: api` invite/redemption contract. Credentials and password policy stay in identity. At redemption, create the CUSTOMER identity/session and link/activate the bound customer in the same transaction as consuming the invite; rollback all on failure. An activation request must not choose its own customer, email, role, terms or credit limit.
4. Serialize create/resend/consume on a stable row for that customer, including first issuance; refresh after lock acquisition. Use version/CAS at save. Two consumes: one success. Resend versus consume: one serialized outcome, no two usable links. No check-then-write without DB enforcement.
5. Apply the shared rate limiter to Sales issuance/resend and public activation; persist the per-customer resend cooldown. TTL and cooldown configuration remain to be wired (tests use 24 hours and 60 seconds as examples, not approved deployment defaults).
6. Add notification delivery using a trusted configured portal origin and an after-commit/retryable delivery strategy. A mail failure must be visible and recoverable by resend; do not email before commit. Never return raw secrets to Sales/API callers, place them in audit/event payloads, or send test email outside Mailpit. Activation should POST the token; avoid referrer/query logging leaks.
7. Add actual PostgreSQL duplicate/concurrency/rollback tests, expired/reused/replaced-link HTTP tests, staff/customer authorization tests for create/resend, and Mailpit assertions. These are pending, not claimed as passing here.

No create/resend/activation API is published yet. FE SCRUM-451 must not build against an invented endpoint. Pricing (428), quotation (426), credit accounting, order release and production remain outside this patch.

## Original snapshot validation and reproduction

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

Local evidence (ignored, not committed): `baseline-scrum429.log`, `final-scrum429.log`, `baseline-verify-scrum429.log`, `verify-final-scrum429.log`. Current `target/surefire-reports` now belongs to the self-review run below. Logs contain only synthetic local fixtures; reproduce in QA rather than treating copied output as an independent QA pass.

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

## One-time self-review requested through the coordinator, 2026-10-08

This responds once to the giangle request in QA's `docs/qa/SPRINT2-SUPERVISOR-PROMPTS.md`, dedup key `giangle:35865934d2c357c8cb29bafb5c5c5f18ccd99cd3:handoff-ac-v1`. The user forwarded it directly; no automation/message to another session was used. Reviewed QA's `SPRINT2-REVIEW.md` and its superseding `SPRINT2-SUPERVISOR.md` read-only. No account/model configuration was changed; this report does not claim independent verification of the runtime model configuration.

Final implementation snapshot: **`896deb5a8531bc4aa2a173501d8d44c81cfa6013`** on the same branch/base. A read-only `git ls-remote origin refs/heads/develop` recheck still returned `276567d34972457eecf3d0a87ada9f0f80b301d8`. Against develop, the implementation diff under `src` is **29 files, 760 insertions, 388 deletions**; documentation adds this handoff and the Jira JSON (31 changed files total). The review fix relative to `35865934d2c357c8cb29bafb5c5c5f18ccd99cd3` is exactly **2 code/test files, 63 insertions, 4 deletions**. No migrations, entities, contracts, production394/395 files or other worktrees were modified. Review the full snapshot; cherry-picking only the new fix requires the original B2B implementation first.

### Live acceptance evidence

The connector worked in this lane. [Timestamped Jira evidence](evidence/SCRUM-429-450-jira-20261008T083612Z.json) preserves the returned descriptions, custom fields, statuses, update times and complete comment responses. Issue fields were read at 08:35:59Z; comment reads completed at **08:36:12Z / 15:36:12 +07**. Both issues have **0 comments**, `isLast=true`; no separate acceptance-criteria field was returned. There is consequently no new comment resolving schema, lifecycle or activation policy. This supersedes QA's inability to read Jira and the earlier temporal status discrepancy; it does not resolve runtime blockers.

The following J rows decompose the actual descriptions of 429/450, using approved plan D1 to select removal rather than pending public registration. They are traceability labels, not Jira AC identifiers or an approved percentage denominator.

| Criterion from 429/450 | Code / test evidence | Result and remaining acceptance |
|---|---|---|
| J1 Sales creates company, MST, contacts and allowed payment terms | `customer/internal/domain/BusinessCustomerProfile.java`; `BusinessCustomerProfileTest` (2) checks required values, allowed enum and immutable nonempty terms. Existing contact entity is unchanged. | **MISSING / BLOCKED runtime.** No Sales create API, grant, service orchestration or persisted business fields. Round-trip, validation/normalization, duplicate and permission tests await agreed schema/grants and implementation. Domain pass does not satisfy J1. |
| J2 Send portal activation invite | `identity/internal/domain/PortalInvitationToken.java`, `PortalInvitation.java`, `PortalInvitationRepository.java`; `PortalInvitationTest` (7). | **PASS domain only; MISSING / BLOCKED runtime.** No adapter, create/resend/activate endpoint, identity linkage transaction, configured portal URL or mail delivery. No actual invitation activation/DB race/mail-sink acceptance. |
| J3 Remove public self-registration (D1) | Removed `CustomerRegistrationController`; both security configurations deny retired routes; `CustomerServiceImpl.register` fails before repository access. `B2bAccessIntegrationTest` retired-route/cached-success methods, `B2bUnsecuredAccessIntegrationTest`, `CustomerServiceImplTest`. | **PASS tested closure paths**, including the security-off cached-success defect fixed below. No new public account can be created through the retired service/handler. This does not supply the missing replacement Sales flow. |
| J4 Disable guest checkout | Removed `GuestCheckoutController`, obsolete guest toggle; `OrderServiceImpl.placeGuestOrder` fails before order/stock/events. Same HTTP tests plus `GuestCheckoutServiceTest`. | **PASS tested closure paths**, including an externally supplied old toggle and cached success; normal account checkout is preserved. |
| J5 Availability requires customer login | `catalog/internal/controller/CatalogController.java:62`, `StorefrontAvailabilityService.java:50`; `B2bAccessIntegrationTest.availabilityRequiresAnActiveLinkedCustomerAndHasNoPublicAlias`, `StorefrontAvailabilityServiceTest` (5). | **PASS login, subject linkage and response privacy.** Actual route is `/api/v1/catalog/products/availability`, already on develop; the old `/public/` alias is denied. ACTIVE-only eligibility is tested implementation behavior, with policy acceptance still **NOT VERIFIED (C02)** below. |

Main-code paths in these tables are relative to `src/main/java/com/stockflow/`; matching test classes are below `src/test/java/com/stockflow/` with the same module path, except root integration tests. The earlier API/file table remains applicable; this review adds `common/security/PermissiveSecurityConfig.java` and root `B2bUnsecuredAccessIntegrationTest.java` only.

### Security and acceptance detail requested by QA

These B identifiers refer to **the original REVIEW B01-B10 matrix**, not the shorter SUPERVISOR B01-B07 matrix. The QA matrix and user hardening requirements go beyond literal Jira description text.

| QA criterion | Code / concrete tests | Pass / missing / blocked |
|---|---|---|
| B01 Sales CREATE RBAC, denied callers and revoked permission | No create controller/service; existing `customer-customers:CREATE` grant is absent. Existing Sales list test is read access only. | **MISSING / BLOCKED**, not proved by list access. Owner-approved Sales/admin grants required; CUSTOMER's existing UPDATE grant is insufficient. |
| B02 Customer ownership and staff scope | `CustomerServiceImpl`, existing profile/address controllers; `B2bAccessIntegrationTest.customerCannotReadOrChangeAnotherCustomersProfileOrAddresses` and `staffBackofficeAccessAndExistingLoginRemainAvailable`. | **PASS existing profile/address isolation and sampled backoffice paths**. New invitation ownership has no HTTP implementation. Sample/design scope is the production lane's separate acceptance; no blanket claim about all list/export paths. |
| B03 Durable business fields and input validation | `BusinessCustomerProfileTest` (2), value object only. | **PASS domain required values/terms; MISSING runtime validation and persisted round-trip.** No invented MST format, maximum lengths, credit limit or uniqueness policy. |
| B04 Invite delivery after commit and correct bound activation | `PortalInvitationTest.redemptionConsumesExactlyOnceAndKeepsTheBoundCustomerAndRecipient`, `exactExpiryFutureIssueAndMalformedOrWrongSecretsCannotConsume`, token format/redaction test. | **PASS object rules; MISSING HTTP, after-commit delivery and linkage.** No independent revocation operation exists; rotation invalidates the old token in the object only. |
| B05 Single-use under concurrent activation, rollback/retry | Same consume tests; repository port documents stable row locking/refresh/CAS. | **BLOCKED runtime.** No actual PostgreSQL lock, unique constraint, rollback/retry or concurrent redemption test. Loaded aggregate version is not a database lock. |
| B06 Authorized resend, cooldown, old-link invalidation and races | `PortalInvitationTest.resendThrottlesThenRotatesAndInvalidatesThePreviousLink`, `anExpiredInvitationCanBeReissuedButAnActivatedOneCannot`, `staleCommandsCannotConsumeOrReplaceTheCurrentInvitation`. | **PASS domain only; MISSING HTTP authorization/rate limits; BLOCKED DB races.** Unit TTL/cooldown values are examples, not approved deployment defaults. |
| B07 Create duplicates/idempotency, existing identities/legacy customers | No Sales create orchestration. Existing platform idempotency does not implement durable email/MST uniqueness or safe identity linkage. | **MISSING / BLOCKED.** Same/different-payload retries, case-normalized concurrent duplicates and no-takeover tests remain required. |
| B08 B2C closure across profiles/call paths | Both security configurations; removed handlers/toggles; root secured (5) and unsecured (1) integration tests, customer/guest service tests. | **PASS tested POST/replay/internal paths**, security on/off and old toggle true. Matchers are verb-independent and include descendants by source inspection; not every verb/encoding/profile combination was exercised over HTTP. |
| B09 Existing login/session/backoffice | `B2bAccessIntegrationTest.staffBackofficeAccessAndExistingLoginRemainAvailable`; existing `IdentitySessionServiceTest`, `SessionGuardTest`, `SessionValidatorTest`, JWT converter tests in the full suite. | **PASS executed login/backoffice and existing suite regressions**. Not a new exhaustive HTTP login/logout/refresh/CSRF matrix or deployment smoke test. |
| B10 Logged-in availability, no data leak | `CatalogController`, `StorefrontAvailabilityService`; root HTTP test covers ACTIVE, BLOCKED, unlinked, staff, anonymous and injected customerId; service test includes INACTIVE. | **PASS implemented guards and private stock-indicator response**; no quantity/price returned. **Policy C02 still open**; this is not approval of all status-based restrictions. |
| Shared boundaries / contracts | `catalog/package-info.java` depends only on `customer :: api`; `ArchitectureTest` (18), `ModularityTest` (3), `MessageBundleTest` (5). No `contracts` edits or cross-module internal imports. | **PASS existing automated boundary checks.** Future onboarding API/transaction contracts remain to be implemented after the agreed mapping. |

**No completion percentage is reported.** The returned Jira descriptions plus provisional QA hardening rows are not an approved, exhaustive backend denominator; grouping them into a fraction would misrepresent readiness. FE 451 and whole-story end-to-end completion remain outside this backend snapshot and incomplete.

### C02: what policy is and is not established

- Approved plan [D1/D3 and the SCRUM-158 change](../business-design/2026-10-06-b2b-production-plan.md) establishes prior onboarding, no guest/self-registration, per-customer allowed payment methods and customer login for availability.
- [BR-ORD-007](../business-design/05-business-rules.md) explicitly requires ACTIVE B2B customers for **quotes/orders**. It does not establish a blanket ACTIVE-only policy for sample read/create/approval or explicitly settle availability eligibility for a linked but BLOCKED/INACTIVE customer.
- Current availability deliberately checks ACTIVE. This review records that behavior and its tests without extending it to production or presenting the extra restriction as an approved cross-module rule. `identity.app_user.status`, `customer.status` and a missing `user_id` link are separate facts; the current customer enum has ACTIVE/INACTIVE/BLOCKED, no agreed PENDING_ACTIVATION lifecycle.
- **Decision owner:** business owner with Tu and the two lane API owners. Confirm pre-activation state, BLOCKED/INACTIVE rights per action, and activation/link transition. Then giangle tests the agreed availability and activation behavior; minhthu tests sample actions. QA should cover ACTIVE, BLOCKED, INACTIVE and unlinked cases for each affected API. `CustomerService.findById/findByUserId` remain unchanged read contracts; no implicit global status filter was added for production consumers.

### Findings: baseline versus this lane

| Finding | Classification / disposition |
|---|---|
| QA F02, no B2B profile/invitation storage/grants | **Baseline dependency still unresolved**; owner Tu. No substitute migration or schema was created. Missing Sales/invite implementation remains giangle's responsibility after the dependency, not work silently assigned to Tu. |
| QA F05/F06, original public registration/guest entry points | **Baseline business gaps addressed by this lane** for the tested paths. |
| SR01, old successful registration/guest response can replay with security disabled | **Residual defect in handed-off lane snapshot**, found and reproduced during this self-review. `B2bUnsecuredAccessIntegrationTest` failed with expected 403 / actual **201** before the fix. The permissive security chain previously allowed idempotency to reply before handler lookup. It now denies the same retired routes as the secured chain; no new account/order mutation was needed to expose the stale success. |
| QA F07, native Windows static checker | **Unchanged baseline tooling failure**: 36 normalized findings, no new findings. Script/rules unchanged. |
| C02 and pending HTTP/DB/mail behavior | **Unresolved policy / missing acceptance**, not evidence that unit-tested token rules fail and not a claim that runtime is complete. |

The review is bounded: the reproduced SR01 is fixed; no other new reproducible feature defect was established. Absence of another finding does not establish full-scope correctness.

### Self-review verification actually executed

| Execution / evidence | Result and scope |
|---|---|
| Before SR01 fix: `mvn -o test -Dtest=B2bUnsecuredAccessIntegrationTest` with loopback SMTP overrides | **1 test, 1 expected regression failure, 0 errors/skips** at 15:43:15 +07; expected 403, received 201. `review-local-replay-before-scrum429.log`. This used isolated Testcontainers and a stopped local-only SMTP target; it did not send mail. |
| After SR01 fix: `mvn -o clean test` with SMTP `127.0.0.1:56444`, SMTP auth/TLS disabled, storage `target/scrum429-storage` | **474 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**, 3m38s, finished **2026-10-08 15:47:22 +07**. Counts cross-checked from 95 fresh Surefire XML reports. `review-final-scrum429.log`; same command shape as reproduction above. |
| New `B2bUnsecuredAccessIntegrationTest` plus existing `B2bAccessIntegrationTest` | **1 + 5 passed**, included in 474. New test seeds historical 201 responses for both registration and guest checkout, then proves 403 and no replay header with security off and the obsolete guest toggle true. |
| `PYTHONUTF8=1 python tools/verify.py` | **Exit 1, 36 baseline findings, 36 current findings, 0 normalized differences**. `review-verify-scrum429.log`; compared with archived baseline log after removing only its snapshot prefix. |
| Local Mailpit `/api/v1/messages` after full suite | **total=0, unread=0**. Only lane-labelled `scrum429-mailpit` was restarted; Docker allocated SMTP `127.0.0.1:56444`, UI `127.0.0.1:56445` this time. Stopped again after verification. No shared DB/port reset or real email. |
| `git diff --check` and staged diff check | Passed. |
| `gh run list --commit <SHA> --json databaseId,status,conclusion,headSha,url` for original code and handoff SHAs | Both returned `[]`. The new fix is local/unpushed, with no CI evidence. No other branch's CI is substituted. |
| QA's combined source snapshot `35865934d2c357c8cb29bafb5c5c5f18ccd99cd3` + `cf5d647fdaf9e0c76c1e8747188e68618eeceac0` | **114 targeted tests passed independently**, as recorded by phuongvy at 15:30:13 +07. This is QA's earlier run, not a run by this lane and not validation of the new SR01 fix or full onboarding end-to-end. |

The new run uses the same Java/Maven and isolated PostgreSQL/Redis/MockMvc approach documented above. To reproduce SR01 alone, substitute `-Dtest=B2bUnsecuredAccessIntegrationTest` in the local-sink command. The failing pre-fix evidence can be reproduced on the original snapshot with only that test added. The positive run is on the final code SHA; no tests were skipped or weakened. Existing full-suite invalid-session/authorization tests and module checks passed as part of the 474, without claiming additional unimplemented Sales/invite HTTP coverage.

### Ownership and readiness

| Remaining work | Owner / dependency |
|---|---|
| Approve business-field and invitation storage/constraints, CREATE seeds and role-version updates | **Tu**, following the mapping above; no migration created by giangle. |
| Decide MST/email collision policy where not already specified, pre-activation lifecycle, per-action customer eligibility, invite TTL/cooldown and approved activation origin | **Business owner + Tu + affected API owners**; no new policy inferred here. |
| Add DTOs/controllers, Sales authorization, customer/identity API orchestration, adapter mappings, atomic linking/consumption, resend and error handling | **giangle**, after owner schema/permission/contract decisions. These are missing code, not supplied by a migration alone. |
| Add mail delivery/recovery and production-safe token handling; wire shared idempotency/rate limiting; prove duplicate/race/rollback behavior over HTTP/PostgreSQL/Mailpit | **giangle**, then **phuongvy independent QA**. Domain tests cannot substitute for these checks. |
| Re-run QA against the new fix SHA and run required CI/current-base checks | **phuongvy / repository review process**. The earlier 114-test combined run excludes SR01's new fix/test. |

- **Enough for a partial draft PR: YES**, with explicit closure/domain-only scope and these blockers. No PR was created or pushed.
- **Enough for technical review of the delivered subset: YES. Enough for full SCRUM-450 acceptance/approval: NO.**
- **Enough to merge the full SCRUM-429/450 delivery: NO.** Sales onboarding, invitation runtime, owner schema/grants, policy agreement, DB/mail/HTTP end-to-end evidence and lane CI remain missing. Closing public registration while the replacement Sales flow is unavailable is not a complete onboarding release.

This completes the single requested self-review/handoff. Stop for QA review of the concrete snapshot; do not start another task, contact another person, change Jira, push, merge or deploy.
