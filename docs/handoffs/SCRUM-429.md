# SCRUM-429 / SCRUM-450 backend handoff

Status: **onboarding runtime implemented and tested end to end on the current develop schema;
tax code, the PENDING_ACTIVATION status and a CREATE grant remain schema-owner items.** Not Done:
FE SCRUM-451, owner review and CI are outstanding. Nothing pushed, merged, deployed or changed in Jira.

## Resume checkpoint (2026-10-12)

- Branch `SCRUM-429-b2b-onboarding`, worktree `orca/workspaces/.../SCRUM-429-b2b-onboarding` only.
- `origin/develop` used: **`1de8f42612be534539d98bbcf105d8b39c6d74a3`** (fetched 2026-10-12), merged by
  merge commit `d15ff67` (conflicts resolved by intent; see its message). The implementation commit
  follows it; recover with `git log -2 --format=%H`.
- Previous lane snapshot: `896deb5` (code) / `f4a9a6e` (docs); its closure work is preserved.
- Business source: kltn-docs `ecommerce/18-customer-information` on `main` (`84b8b54`, merged
  2026-10-10) - §4.1, §5, BR-01, BR-01a, BR-02. Jira: snapshot `SPRINT2-JIRA-SNAPSHOT.md` (2026-10-08)
  and `JIRA-BE-FE-SNAPSHOT-2026-10-09.json` (issues last updated 2026-10-08, no comments, no separate AC
  field). The Atlassian connector was **not loaded in this session**, so Jira was not re-read live.

## What develop gave us, and what it did not

Checked in every migration on `1de8f42` and every remote branch:

| Needed | On develop | Used as |
|---|---|---|
| Company + contact | `customer.customer` (full_name, email, phone, user_id), `identity.app_user.full_name` | company name on the customer, contact name on the account |
| Allowed payment terms | `customer.credit_profile` + `customer-credit-profiles:APPROVE` (SCRUM-427) | untouched by onboarding: BR-02 says only the credit approver sets terms; no row = prepaid only |
| Invitation storage | **none** | one-time credential in `app_user.password_hash` (below) |
| Pending Activation | **not in `ck_customer_status`** | `INACTIVE` + account with an open invitation |
| Tax code | **no column** | not accepted by the API (blocker) |
| `customer-customers:CREATE` | **not seeded** | `UPDATE` + Sales/e-commerce-admin role check, the existing staff-edit pattern |

The schema request for Tu is `docs/business-design/db-design/SCRUM-429-schema-change-request.md`.
No migration, pending migration or parallel table was added.

## API

| Endpoint | Who | Behaviour |
|---|---|---|
| `POST /api/v1/customers` | `customer-customers:UPDATE` + SALES_STAFF / ECOMMERCE_ADMIN (SYSTEM_ADMIN passes every role check) | Body `companyName, contactName, email, phone?`. Creates an `INACTIVE` customer and a CUSTOMER account that has no password, commits, then e-mails the invitation. **201** `{customer, invitation:{issuedAt, expiresAt, delivery: SENT|FAILED}}`. Never returns the link. 409 `CUSTOMER_EMAIL_ALREADY_EXISTS` / `USER_EMAIL_ALREADY_EXISTS` (case-insensitive; never attaches to an existing account). 503 `PORTAL_INVITATION_UNAVAILABLE` if no activation URL is configured, before any write. Rate limit 30/min/user. Audited `CREATE customer`. |
| `POST /api/v1/customers/{id}/portal-invitation` | same | New link, old one dead on commit. 200 same shape. 404, 409 `CONFLICT` (no account / BLOCKED), 409 `PORTAL_ACCOUNT_ALREADY_ACTIVE`, 429 `RATE_LIMITED` within the cooldown. 10/min/user. Audited. |
| `GET /api/v1/customers/{id}/portal-invitation` | `READ` + same roles | `{state: PENDING|EXPIRED|ACTIVATED|NO_ACCOUNT, issuedAt, expiresAt}` |
| `POST /api/v1/customers/portal-activation` | anonymous, POST only | Body `{token, password}`. Sets the password and makes the customer `ACTIVE` in one transaction. 200 `{email}` (then sign in normally). 400 `PORTAL_INVITATION_INVALID` for malformed/unknown/replaced/expired/used links and BLOCKED customers alike; 400 `VALIDATION_FAILED` for a weak password (link stays usable). 10/min/IP. Audited. |

Unchanged: login, retired registration/guest routes (denied, also with security off and for
cached replays), availability (`ACTIVE` linked customer only - now backed by BR-01a), profile/address
ownership, credit-profile API.

Configuration: `PORTAL_ACTIVATION_URL` (blank = invitations off, no startup failure; `local` profile
defaults to `http://localhost:3000/portal/activate`), `PORTAL_INVITATION_LIFETIME` (PT72H),
`PORTAL_INVITATION_RESEND_COOLDOWN` (PT1M). The token travels as a URL fragment (`#token=`), which
browsers do not send to servers or in `Referer`. **The FE route and the TTL/cooldown values are
proposals, not owner-approved.**

## Design decisions to review

1. **Invitation as a credential on the account.** `{portal-invitation}<issued>:<expires>:<sha256(token)>`
   in `password_hash`. The token is `base64url(userId ‖ 256 random bits)`; only its digest is stored.
   Activation writes a bcrypt hash (link consumed), resend writes a new value (old link dead), all under
   the account row lock. Login treats such an account like an unknown user (same bcrypt cost, 401, no
   lockout counting); `changePassword` refuses it. `PortalInvitation`'s javadoc explains why; §2.4 of the
   schema request describes the table alternative, which would only change the adapter.
2. **E-mail after commit, not via an event.** The mail carries the only copy of the secret; an event
   payload is persisted in `event_publication`. So the service commits, then calls
   `NotificationService.sendPortalInvitation` (SMTP transport of the notification module) outside any
   transaction, and reports `SENT`/`FAILED`. Recovery is a Sales resend (new secret). Durable automatic
   retry is deliberately not implemented.
3. **Lock order** customer row → account row for resend and activation (activation reads the account id
   from the token's shape first), so they cannot deadlock; races resolve to one outcome.
4. **Duplicate races** end at the unique indexes (`uk_customer_email_normalized`,
   `uk_app_user_email_normalized`/`username`) and are translated to 409.
5. Removed the superseded, never-wired `BusinessCustomerProfile` (terms now belong to `credit_profile`)
   and the `PortalInvitationRepository` port.

## Acceptance mapping

| Criterion (Jira 429/450 + kltn-docs 18) | Code / API | Tests | Result |
|---|---|---|---|
| Sales creates the business customer: company, contacts | `CustomerOnboardingController.create`, `CustomerOnboardingService.create`, `Customer.onboard` | `B2bOnboardingIntegrationTest.salesCreates…`, `onlySales…`, `anExisting…` | **PASS** |
| … tax code | - | - | **MISSING - schema** (§2.1 of request) |
| … allowed payment terms | existing `PUT /customers/{id}/credit-profile` (approver only, BR-02) | existing SCRUM-427 tests | **PASS by existing API**, not part of the create call by design |
| Duplicate check (email; MST) | service check + unique indexes | duplicate + 4-thread race tests | **PASS email**; MST blocked |
| Send portal activation invite | `PortalAccountService.invite`, `NotificationSender.sendPortalInvitation` | Mailpit end-to-end (link read from the delivered mail) | **PASS** |
| Resend invalidates old link, cooldown | `User.reissueInvitation` | `resendWaits…`, `PortalInvitationTest` | **PASS** |
| Token secure, expires, single use | `PortalInvitationToken`, `PortalInvitation`, `User.activate` | `PortalInvitationTest` (8), `anExpiredLink…`, `concurrentActivations…` (4 threads, exactly one) | **PASS** |
| Activation → Active, atomic | `CustomerOnboardingService.activate` | end-to-end + rollback test, BLOCKED test, resend-vs-activate race | **PASS** (Active represented as ACTIVE; Pending as INACTIVE - see §2.2) |
| Mail only after commit, failure visible/recoverable | `deliver` after `TransactionTemplate` | `aMailFailure…` (Mailpit paused → FAILED, customer kept, resend → SENT) | **PASS** |
| No token in logs/responses/audit/idempotency store | redacted `toString`s, log statements | asserted against `platform.audit_log` and `platform.idempotency_record`; full log grep `#token=` = 0 | **PASS** (Hibernate logs the *digest* in the failed INSERT of a duplicate race) |
| Sales-only RBAC | `requireSales` + `UPDATE` | anonymous 401, warehouse 403, customer 403, sales/e-com admin 201 | **PASS**; dedicated CREATE grant pending |
| Remove public self-registration | security deny + fail-closed service | `B2bAccessIntegrationTest`, `B2bUnsecuredAccessIntegrationTest` | **PASS** (regression re-run after merge) |
| Disable guest checkout | same; develop's re-added controller kept deleted | same + adapted `InventoryCatalogIntegrationTest` | **PASS** |
| Availability needs customer login | unchanged | `B2bAccessIntegrationTest`, adapted `InventoryCatalogSecurityIntegrationTest` | **PASS** |

## Merge notes

- develop re-modified `GuestCheckoutController` (server prices) → kept deleted; `placeGuestOrder` still
  refuses, but the shared `agreedPrice` helper that account checkout uses was kept.
- Two develop tests encoded B2C behaviour and were adapted, not deleted:
  `InventoryCatalogIntegrationTest.guestCheckout…` now proves the same price/replay guarantees through
  account checkout (with `snapshotCustomer=true`, the HTTP path) and that guest checkout reserves
  nothing; `InventoryCatalogSecurityIntegrationTest` now expects 403 for a non-customer on availability.
- `B2bAccessIntegrationTest` seeded staff by converting a customer account; SCRUM-453 forbids that, so
  staff are now seeded directly into the tables.

## Verification (2026-10-12, Java 21.0.9, Maven 3.9.9 offline, Docker Desktop 28.5.1)

| Run | Result |
|---|---|
| `mvn -o clean test` (SMTP → local `scrum450-mailpit`, storage `target/scrum429-storage`) | **921 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**, 9 m 30 s, finished 2026-10-12 02:56 +07 (`final-scrum450.log`) |
| of which `B2bOnboardingIntegrationTest` / `B2bAccessIntegrationTest` / `B2bUnsecuredAccessIntegrationTest` | 10 / 5 / 1 passed |
| of which `PortalInvitationTest`, `ArchitectureTest`, `ModularityTest`, adapted `InventoryCatalog*` | 8, 18, 3, 32 + 9 passed |
| first full run before adapting the two develop tests | 921 tests, 2 failures - exactly those B2C expectations (`full-scrum450.log`) |
| `PYTHONUTF8=1 python tools/verify.py` | all static checks passed (the earlier 36 Windows false positives are gone on develop) |
| `git diff --check` | clean |
| lane Mailpit after the full run | 0 messages; onboarding mail went only to its own Testcontainers Mailpit |

Logs are ignored and not committed (`*.log`).

- Integration tests use fresh Testcontainers PostgreSQL 16 / Redis 7 and, for onboarding, a throwaway
  `axllent/mailpit` container. Every recipient is `@example.test`. The rest of the suite pointed SMTP at a
  lane-labelled local Mailpit (`scrum450-mailpit`). No real mail, no shared database, no production.
- `ArchitectureTest`, `ModularityTest` (customer now also depends on `notification :: api`),
  `MessageBundleTest`, `tools/verify.py` are part of the run.
- Not done: CI (nothing pushed), HTTP tests through a real servlet container (MockMvc is used), load or
  multi-instance tests. On this machine `localhost` takes ~2 s to resolve; the test uses `127.0.0.1`.

## Open items and owners

| Item | Owner |
|---|---|
| `customer.customer.tax_code` + uniqueness rule; `PENDING_ACTIVATION` status; `customer-customers:CREATE` seed | **Tu** (request doc) |
| Confirm TTL 72 h / cooldown 1 min, portal activation route, Suspended/Closed mapping | business owner + Tu |
| Add `taxCode` to the create API and switch to `Action.CREATE` once the schema lands | this lane |
| FE: back-office create/resend screens, portal activation page reading `#token` | SCRUM-451 |
| Independent QA and CI on the pushed branch | QA / repository process |
