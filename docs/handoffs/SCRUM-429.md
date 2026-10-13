# SCRUM-429 / SCRUM-450 backend handoff

Status: **backend acceptance of SCRUM-450 implemented and tested, including the schema and sign-in suspension of blocked/closed customers.** Not Done
as a story: FE SCRUM-451, independent QA, CI on a pushed branch and the business owner's confirmation
of the defaults listed under "Decisions" are outstanding. Nothing pushed, merged, deployed or changed in Jira.

> **Superseded by later decisions (2026-10-12/13) - read these first; the sections below keep the original record:**
> - **Tax code is optional** (household businesses / small shops): no tax identifier is needed to create,
>   activate or order; it can be added later. When given, uniqueness and format still apply.
> - **Format:** 10 digits, 10-3, **or the 12-digit personal identification number** of a household business's
>   representative (Thông tư 86/2024/TT-BTC art. 5(5), art. 38(2); Luật Căn cước 26/2023/QH15 art. 12(1); official
>   texts read 2026-10-12) - `V20261014000540`, `TaxCode`.
> - **A closed (INACTIVE) customer may be reopened** by the E-commerce Admin with a mandatory reason, kept in
>   `customer.customer_status_history` (`V20261014000500`); old sessions stay revoked; a contact that never activated
>   reopens to PENDING_ACTIVATION.
> - **Payment terms at onboarding** (B1, 2026-10-13): Sales may choose PREPAID or DEPOSIT (+ deposit %) in
>   `POST /api/v1/customers` (`paymentTerm`, `depositPercent`); CREDIT stays with the credit approver.
> - **Customer groups** (P7): `/api/v1/customer-segments` and `PUT /api/v1/customers/{id}/segment`, resource
>   `customer-segments` (`V20261014000600`). Details: `docs/handoffs/SCRUM-428.md`.

## Resume checkpoint (2026-10-12)

- Branch `SCRUM-429-b2b-onboarding`, this worktree only. The production 394/395 worktree was read
  (migration numbers) and never modified.
- `origin/develop` integrated: **`74d624d2c99e527550f66d0b6e5a58cadfcb76d0`** (merge `60da3e8`; the earlier
  `1de8f42` merge is `d15ff67`). Newest develop migration then: `V20261013000600`; production worktree had
  nothing newer. This branch adds **`V20261014000100`** (B2B suffix `00100`; production keeps `00200`+).
  At commit time the production worktree holds `V20261014000200__production_sample_answer_grants.sql`
  (uncommitted there). **Merge order matters:** if that reaches develop first, rename this file above it
  before merging - Flyway does not run out of order (CLAUDE.md §7).
- Jira read live through the Atlassian connector on 2026-10-12: SCRUM-429 and SCRUM-450 unchanged since
  2026-10-08, 0 comments, no separate acceptance field. Acceptance below = Jira description + kltn-docs
  `ecommerce/18-customer-information` (main `84b8b54`, 2026-10-10) §4.1, §5, BR-01, BR-01a, BR-02.
- The repository owner authorised this branch to write its own migration and local commits (2026-10-12).
- Implementation commit follows the merge; recover with `git log -3 --format='%H %s'`.

## Schema - `V20261014000100__customer_b2b_onboarding.sql`

| Change | Detail |
|---|---|
| `customer.customer.tax_code VARCHAR(14)` | NULL for every pre-existing customer: no invented or back-filled codes. ~~Required for new customers~~ optional since 2026-10-12. `ck_customer_tax_code` `^[0-9]{10}(-[0-9]{3})?$`, widened to also accept 12 digits by `V20261014000540`. |
| `uk_customer_tax_code_live` | unique over `PENDING_ACTIVATION` + `ACTIVE` only. BR-01 forbids two Active customers per code; §4.1 checks duplicates at creation, when the customer is pending, so pending is included to stop two creates racing. INACTIVE (closed) and BLOCKED rows are not constrained - the documents do not ask for it. |
| `ck_customer_status` + `PENDING_ACTIVATION`; `ck_customer_pending_has_account` | Pending always has the account its contact activates. |
| `identity.app_user.password_hash` nullable | NULL = invited, no password yet. |
| `identity.portal_invitation` | one row per invitation e-mail, SHA-256 digest only; consumed XOR revoked; lifetime/consumption/delivery CHECKs; `uk_portal_invitation_open` (one open per account); `uk_portal_invitation_digest`; FK to the account `DEFERRABLE INITIALLY DEFERRED` (Hibernate flush order). |
| carry-over | branch-era `{portal-invitation}<issued>:<expires>:<digest>` credentials become `portal_invitation` rows with the same digest and dates (links already e-mailed keep working); only their customers move INACTIVE → PENDING_ACTIVATION; every other INACTIVE customer is untouched. |
| `customer-customers:CREATE` | granted to SALES_STAFF, ECOMMERCE_ADMIN, SYSTEM_ADMIN; those three `app_role.version` + 1 in the same migration. |

Why the invitation left `password_hash` (the previous commit's design): the repository rule "state every
invariant twice - aggregate and CHECK" cannot hold for a string in a password column, the e-mail outcome
has to be stored so a failure is visible, and the credential column should hold only passwords.
`schema.dbml` regenerated with `tools/db/gen_dbml.py` from a fully migrated database (it also catches up
tables develop had added without regenerating: SCRUM-427/431/453/457/460). New QA script
`tools/db/qa/09_customer_onboarding.sql`.

## API

| Endpoint | Authorisation | Behaviour |
|---|---|---|
| `POST /api/v1/customers` | `customer-customers:CREATE`; CUSTOMER role holders refused | Body `companyName, taxCode, contactName, email, phone?`. Normalises the tax code; creates a `PENDING_ACTIVATION` customer and a passwordless CUSTOMER account, commits, then e-mails the link. 201 `{customer{…taxCode, status}, invitation{issuedAt, expiresAt, delivery SENT\|FAILED}}`, never the link. 409 `CUSTOMER_TAX_CODE_ALREADY_EXISTS` / `CUSTOMER_EMAIL_ALREADY_EXISTS` / `USER_EMAIL_ALREADY_EXISTS`; 400 `VALIDATION_FAILED`; 503 `PORTAL_INVITATION_UNAVAILABLE` (no activation URL) before any write. 30/min/user. Audited. |
| `POST /api/v1/customers/{id}/portal-invitation` | same | Revokes the open invitation, issues a new one, e-mails it after commit. 409 `PORTAL_ACCOUNT_ALREADY_ACTIVE`, 409 `CONFLICT` (not pending: blocked/closed/legacy), 429 `RATE_LIMITED` within the cooldown (an expired invitation is replaced at once), 404. Audited. |
| `GET /api/v1/customers/{id}/portal-invitation` | `READ` + Sales/e-commerce admin | `{state PENDING\|EXPIRED\|ACTIVATED\|NO_ACCOUNT, issuedAt, expiresAt, delivery}` |
| `POST /api/v1/customers/portal-activation` | anonymous, POST only | `{token, password}` → password set, invitation consumed, customer ACTIVE, one transaction. 200 `{email}`. 400 `PORTAL_INVITATION_INVALID` alike for malformed/unknown/replaced/expired/used links and for customers no longer pending; 400 `VALIDATION_FAILED` for a weak password (link stays usable). 10/min/IP. Audited. |
| `PUT /api/v1/customers/{id}` (staff) | unchanged | now also `taxCode` (normalised, uniqueness-checked); `PUT /customers/me` ignores it. |
| `POST /api/v1/customers/{id}/status` (e-commerce admin) | unchanged | lifecycle enforced: nobody sets PENDING_ACTIVATION; pending → only INACTIVE; reviving a closed customer whose code another live customer holds → 409 `CUSTOMER_TAX_CODE_ALREADY_EXISTS`; others → 409 `INVALID_CUSTOMER_STATUS_TRANSITION`. **Sign-in follows the status** (next section). |

## Suspended and closed customers cannot sign in

kltn-docs 18 §5 (main `84b8b54`): Suspended "Tạm khoá — không đăng nhập/mua", → Active or Closed;
Closed "Khách yêu cầu đóng tài khoản", → Anonymized only; edge case "nghi bị chiếm → thu hồi phiên, tạm
Suspended". Before this change a BLOCKED customer could still sign in (only ordering/availability were refused).

- `CustomerServiceImpl.changeStatus` calls `PortalAccountService.suspend` (BLOCKED, INACTIVE) or `reinstate`
  (ACTIVE) in the same transaction, after locking the customer row - the lock order resend/activation use.
  customer → identity is the existing allowed dependency; `ModularityTest` passes (no cycle).
- `suspend`: ACTIVE account → DISABLED, and **every live session revoked** (`ACCOUNT_DISABLED`). Sessions are
  checked per request without a cache (`SessionGuard`), so the token already issued gets 401 on its next request.
  There is no refresh token in this system: a new token needs a login, which answers 403 `ACCOUNT_NOT_ACTIVE`.
- `reinstate`: DISABLED → ACTIVE, the same password signs in again; old sessions stay ended. A LOCKED account is
  never disabled, so reinstating can never lift someone else's lock.
- Only accounts holding **CUSTOMER and nothing else** are touched; a staff login linked to a customer profile is
  kept (and customer accounts are already outside user management, `STAFF_ACCOUNT_REQUIRED`).
- Migration `V20261014000100` brings existing rows in line: CUSTOMER-only accounts of BLOCKED/INACTIVE customers
  → DISABLED, their live sessions revoked. Edited in place because the file was never pushed and only ever ran
  in throwaway test containers.
- Closed: the current staff rule still allows INACTIVE → ACTIVE (§5 has no such transition, Closed ends at
  Anonymized). This branch did not remove that existing rule; while it exists the account follows it. Whether to
  forbid reopening is the business owner's call.

Unchanged and regression-tested: login, retired registration/guest checkout (denied with security on/off
and for cached replays), availability (ACTIVE linked customer only), profile/address ownership,
credit-profile API (terms are set only by the approver, BR-02; no row = prepaid only). **Superseded in part
by decision B1 (2026-10-13):** at onboarding Sales may choose `paymentTerm` PREPAID or DEPOSIT with the agreed
`depositPercent` (above 0, below 100, two decimals); a credit profile is then recorded with credit off and the
Sales account as the one who set it. CREDIT and the credit limit stay with the approver (`PUT …/credit-profile`,
APPROVE); CREDIT at onboarding is refused 400 before anything is written. Omitted: nothing recorded, prepaid only.

## Decisions (defaults chosen here, not approved by anyone else)

| Topic | Default | Basis |
|---|---|---|
| Lifecycle mapping | Pending Activation=PENDING_ACTIVATION, Active=ACTIVE, Suspended=BLOCKED, Closed=INACTIVE | kltn-docs 18 §5 vs existing statuses |
| Tax code format | 10 digits (enterprise) or 10-3 (dependent unit); no check-digit validation; ~~12-digit personal IDs (household businesses) refused~~ **accepted since 2026-10-12 (official texts, see top)** | kltn-docs 18 says the customers are enterprises. Law 38/2019/QH14 art. 30(2) and Circular 86/2024/TT-BTC art. 5 (in force 2025-02-06, replaces 105/2020, which the previous commit wrongly cited) as quoted by secondary sources (luatvietnam.vn; thuvienphapluat.vn returned 403, caselaw.vn showed only the table of contents) - **official text not retrieved**. If household businesses are to be customers, the business owner must say so and the format must accept their 12-digit number. |
| Tax code uniqueness | live = pending + active | BR-01 + §4.1 duplicate check |
| Invitation lifetime / resend cooldown | 72 h / 1 min (`PORTAL_INVITATION_LIFETIME`, `PORTAL_INVITATION_RESEND_COOLDOWN`) | none in the documents |
| Activation URL | blank = invitations off (503); `local` profile `http://localhost:3000/portal/activate`; token as `#token=` fragment | FE route not built (SCRUM-451) |
| E-mail retry | after-commit send, outcome stored; recovery by resend (new secret), no automatic retry | the mail holds the only copy of the secret |

Also added: `mail.from` and optional `MAIL_SMTP_LOCALHOST` mail properties. Measured on the real jar:
201 for create went from ~10 s to 0.66 s on this laptop, whose reverse DNS takes 9.6 s
(`InetAddress.getCanonicalHostName`). Blank keeps JavaMail's default EHLO behaviour.

## Acceptance checklist

| # | Criterion | Code | Tests (all executed) | Result |
|---|---|---|---|---|
| A1 | Sales creates company customer: company, tax code, contact | `CustomerOnboardingController.create`, `CustomerOnboardingService.create`, `Customer.onboard`, `TaxCode` | IT `salesCreates…`, `onlySales…`; `CustomerOnboardingTest`; HTTP smoke | PASS |
| A2 | Allowed payment terms | existing credit-profile API (approver, BR-02) | SCRUM-427 suite | PASS (by design not in the create call) |
| A3 | Duplicate check MST/email, normalised, races | pre-check + `uk_customer_tax_code_live`, email indexes, `CustomerConstraints` | IT `aTaxCodeBelongs…` (spellings, 10-3, closed reuse, revive 409, 4-thread race), `anExisting…`, `concurrentCreates…`; QA B1–B8 | PASS |
| A4 | Legacy customers without MST | nullable column, no backfill, staff `PUT` sets it | upgrade test; `CustomerOnboardingTest`; IT lifecycle | PASS |
| A5 | PENDING_ACTIVATION, sensible data move | migration + `Customer.changeStatus/activatePortal` | upgrade test (only branch-invited INACTIVE moved); IT lifecycle | PASS |
| A6 | CREATE grant, version bump, RBAC | migration + `Action.CREATE` | IT (anonymous 401, warehouse/customer 403, sales/e-com/system admin 201, grant rows), upgrade test (versions), QA B18, HTTP smoke | PASS |
| A7 | Invitation sent after commit, secure token, single use, expiry | `PortalInvitationToken`, `PortalInvitation`, `PortalAccountServiceImpl` | `PortalInvitationTest` (6); IT end to end through Mailpit, expired, 4-thread activation; QA B9–B17; HTTP smoke | PASS |
| A8 | Resend invalidates old link, cooldown, races | `revokeForResend` + open index | IT `resendWaits…`, resend-vs-activation race | PASS |
| A9 | Mail failure visible and recoverable | `deliver` + `recordDelivery` | IT `aMailFailure…` (paused SMTP → FAILED stored, resend → SENT) | PASS |
| A10 | No token leak | digest only; redacted `toString`s | IT asserts DB, audit log, idempotency store; log grep `#token=` 0 (tests and real jar) | PASS |
| A11 | Pending / blocked / closed access | login 401 without password; activation only from pending; availability/checkout ACTIVE only | IT lifecycle + blocked; `StorefrontAvailabilityServiceTest` (pending/inactive/blocked) | PASS |
| A12 | Self-registration removed, guest checkout disabled, availability needs login | security deny + fail-closed services | `B2bAccessIntegrationTest` (5), `B2bUnsecuredAccessIntegrationTest` (1), HTTP smoke | PASS |
| A14 | Suspended/closed customers cannot sign in; existing sessions end; unblocking restores sign-in; staff untouched | `CustomerServiceImpl.syncPortalAccess`, `PortalAccountService.suspend/reinstate`, migration sync | IT `blockingEndsTheSession…` (token before block → 401, login → 403, unblock → login again, close → 401), `aStaffLoginIsNeverTakenAway…`; upgrade test (pre-existing blocked account disabled + session revoked, linked staff kept); real jar smoke 18/18 | PASS |
| A13 | Migration fresh + upgrade with data | `V20261014000100` | every integration test (fresh), `B2bOnboardingMigrationUpgradeTest`, real jar boot on a fresh DB | PASS |

## Verification (2026-10-12; Java 21.0.9, Maven 3.9.9 offline, Docker 28.5.1, PostgreSQL 16 / Redis 7 / Mailpit)

**Latest, after the suspension change: `mvn -o clean test` 946 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS** (10 min 36 s, finished 2026-10-12 13:31 +07, `final4-scrum450.log`); `tools/verify.py` all checks passed; real jar smoke for onboarding + block/unblock/close 18/18 (`http-smoke2-scrum450.log`, script kept outside the repo); no `#token=` in the server log.

Previous round (before suspension): 944 tests, 0 failures (`final3-scrum450.log`). Also executed in that round:
- unit + `ArchitectureTest` + `ModularityTest` + `MessageBundleTest` + related: 52 passed;
- B2B integration (`B2bOnboardingIntegrationTest` 12, `B2bAccessIntegrationTest` 5, `B2bUnsecured…` 1): passed;
- `B2bOnboardingMigrationUpgradeTest`: passed;
- real jar (`java -jar`, `local` profile, **security on**, idempotency on) against lane-only Postgres/Redis/Mailpit containers on port 18450: 105 migrations applied; `target/scrum450-http-smoke.sh` 23/23 checks twice (`http-smoke-scrum450.log`);
- `tools/db/qa/run.sh` on that database: all scripts pass, 09 = 18 checks (`qa-scrum450.log`);
- `tools/verify.py`: all static checks passed.
- A first full run failed 2 tests on the login rate limit (5/min per IP, all MockMvc calls from 127.0.0.1); the test now gives each login/activation call its own `X-Forwarded-For`, the limit itself unchanged.

Not done: CI (nothing pushed), FE, load or multi-instance tests. Only `@example.test` recipients, all mail into
throwaway Mailpit containers; containers stopped afterwards; no shared or production database touched.

## Open items

| Item | Owner |
|---|---|
| Confirm the remaining defaults in "Decisions" (lifecycle mapping, invitation lifetime/cooldown, activation URL) | business owner (+ Tu for schema conventions) |
| FE: back-office create/resend/status screens, portal page reading `#token` and posting it | SCRUM-451 |
| ~~Whether a Closed (INACTIVE) customer may be reopened~~ | **decided 2026-10-12**: yes, by the E-commerce Admin, with a reason |
| ~~Whether household businesses (12-digit personal ID as tax code) are customers~~ | **decided 2026-10-12**: yes; tax code optional; 12 digits accepted |
| Independent QA and CI on the pushed branch | QA / repository process |
