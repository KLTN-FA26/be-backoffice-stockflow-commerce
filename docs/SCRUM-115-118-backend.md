# SCRUM-115 / SCRUM-118 backend contract

## Business decisions

- Supplier codes are immutable, normalized to uppercase and case-insensitively unique. Tax identifiers are optional and unique when present. Tax validation checks identifier syntax, not government registration or tax status; the model has no country-specific tax registry integration.
- A profile has one primary contact (name, email, phone). Multiple contact persons are not modeled by this API.
- INACTIVE is blocked while any DRAFT, APPROVED, SENT or PARTIALLY_RECEIVED PO exists. PO creation and supplier changes lock the same supplier row so concurrent requests cannot bypass this rule. Closed/cancelled purchasing history is retained.
- Omitted payment terms and lead time default to 30 and 7 calendar days. Explicit zero is valid. PO creation snapshots these values; later supplier edits cannot rewrite existing POs. A supplied expected delivery date overrides the lead-time default.
- PO numbering dates and default delivery dates use `Asia/Ho_Chi_Minh`, consistently with delivery KPIs. The shared clock and timestamp persistence remain UTC; existing PO dates are not rewritten.
- A new sending request is valid only for APPROVED. It commits SENT and an immutable delivery event containing the recipient, channel, order lines, total, currency, payment terms and expected date. SENT means queued for communication, not proof of delivery or supplier acceptance.
- Repeat an HTTP request with the same `Idempotency-Key` to replay the original response. A different key or a new request without a key on SENT receives 409. Concurrent requests cannot create two delivery events for the PO.
- Supplier response starts PENDING. A purchasing employee with UPDATE permission records CONFIRMED or REJECTED based on the supplier's actual reply. This is not a public supplier authentication/callback endpoint. Existing audit infrastructure records the actor; the PO stores time, reference and note. Identical replays are accepted; altered or contradictory responses conflict. Rejection requires a reason, is forbidden after receipt, and blocks further receipt. Cancel a rejected PO and create a replacement for revised terms.
- Confirmation recorded after receipt is allowed for delayed administrative entry; it cannot rewrite receipt completion evidence.
- Cancellation and final supplier responses suppress outstanding PO delivery in the same transaction. Dispatch holds the same notification control-row lock through transport. If dispatch already started, cancellation waits; successful transport remains visible and must be reconciled with the supplier. Suppression cannot recall an externally accepted message.
- BR-06: initial sending requires a delivery date, but a past date is allowed. Responses expose `warnings: ["DELIVERY_DATE_IN_PAST"]` for open overdue POs, using the Vietnam business calendar. The optional sending body can provide `expectedAt` and a nonblank `reason` to revise the date before the first snapshot. No automatic date shift occurs. Recovery cannot change a sent PO's date.
- Recovery requires APPROVE permission, SENT/PENDING, terminal failed transport, a reason and `reconciled=true`. An overdue/unknown original date additionally requires `acknowledgePastDue=true`. It uses the current validated supplier contact but preserves the PO number, first send time, commercial terms and date. Pending retries, successful delivery, final supplier responses, receipt and cancellation cannot be recovered. Each recovery appends a generation; old publications cannot dispatch into the new generation.

## API groups and permissions

All responses use the existing API envelope; collection responses are paginated.

| Endpoint | Permission | Result |
|---|---|---|
| POST `/api/v1/suppliers` | procurement-suppliers:CREATE | Profile and defaults, HTTP 201 |
| GET `/api/v1/suppliers` | procurement-suppliers:READ | Search/status/sort and paginated profiles |
| GET `/api/v1/suppliers/{id}` | procurement-suppliers:READ | Profile, contact, defaults, channel |
| PUT `/api/v1/suppliers/{id}` | procurement-suppliers:UPDATE | Full replacement of editable profile fields |
| DELETE `/api/v1/suppliers/{id}` | procurement-suppliers:DELETE | Deactivate without deleting history, HTTP 204 |
| GET `/api/v1/suppliers/{id}/performance` | procurement-suppliers:READ | Delivery/quality aggregates and calculation timestamp |
| POST `/api/v1/purchase-orders/{id}/sending` | procurement-purchase-orders:UPDATE | SENT PO with pending supplier response |
| POST `/api/v1/purchase-orders/{id}/supplier-confirmation` | procurement-purchase-orders:UPDATE | Updated response status, time, reference and note |
| GET `/api/v1/purchase-orders/{id}/deliveries` | procurement-purchase-orders:READ | Paginated delivery attempts, channel, attempted/sent times and sanitized failure category |
| POST `/api/v1/purchase-orders/{id}/delivery-recovery` | procurement-purchase-orders:APPROVE | Queue one new generation after terminal failure and reconciliation |
| GET `/api/v1/purchase-orders/{id}/delivery-decisions` | procurement-purchase-orders:READ | Paginated requester, reason, recipient, generation and old/new date evidence |

PO responses include `deliveryStatus`: NOT_SENT, QUEUED, UNKNOWN (legacy without delivery evidence), RETRYING, FAILED (terminal), SUPPRESSED (pending work stopped), or DELIVERED. A previously successful delivery remains DELIVERED after cancellation. List enrichment uses one bulk lookup. SMTP acceptance and API 2xx are transport success, never supplier business confirmation. An idempotency replay retains its original response; refresh detail for current status. Delivery attempts include `generation` and `recipient`; delivery-decisions records why each generation was authorized.

PROCUREMENT_STAFF receives VIEW_PAGE/READ/CREATE/UPDATE, not DELETE. The DELETE endpoint remains available only to explicitly authorized roles. Supplier updates and deactivation use the existing audit trail. PUT is a full replacement and requires explicit paymentTermDays/leadTimeDays (missing values return 400); POST alone applies 30/7 defaults when omitted. No PATCH status endpoint or frontend aliases are introduced without a coordinated API contract.

## Performance definitions

- Total counts all supplier POs. Fulfilled counts CLOSED only; CLOSED_SHORT is not full fulfillment.
- On-time/late are evaluated only for fully received POs with a due date and actual completion evidence, using Vietnam calendar dates. New completion timestamps come from the injected Clock and are recorded by the aggregate at the first transition to CLOSED; DB guards prevent later changes. Legacy rows use the latest COMPLETED goods receipt; an unknown date remains unknown.
- Average lead time is fractional elapsed days between SENT and complete receipt. Rows without trustworthy timestamps are excluded.
- Quality uses inspected QC results belonging to COMPLETED receipts only; pending/cancelled receipts do not affect the rate.
- No observations means `null` rates/average (possibly omitted by the configured JSON null policy), not a fabricated 0% score. Quantities remain zero.
- Receipt/QC authoring belongs to the existing receiving/QC workflow. These tickets read its evidence; they do not implement a second warehouse/QC system.

## Delivery and operations

The existing NotificationSender owns transport. The existing Spring Modulith event registry persists delivery requests atomically with the PO. Its AFTER_COMMIT async listener records failures in a separate transaction; a mail/API outage never rolls back SENT. The retry job resubmits at most 50 eligible PO publications every five minutes after their initial five-minute grace period, guarded by ShedLock. Selection rotates in stable publication-ID order using a database cursor, so permanently failing old events cannot monopolize every batch. The cursor survives application restarts and node changes; it is advanced before dispatch, and an interrupted batch is revisited on a later circuit. The registry remains the sole event queue. PostgreSQL advisory transaction locks serialize workers and successful delivery references are unique.

- Local IDE: SMTP `MAIL_HOST=localhost`, `MAIL_PORT=1025`, with Mailpit running.
- Compose app: `MAIL_HOST` is passed from `DOCKER_MAIL_HOST` (default `mailpit`). Start the `mailpit` service along with `app`. SMTP has finite connection/read/write timeouts.
- Production email: supply MAIL_HOST (or DOCKER_MAIL_HOST for this Compose), MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD, MAIL_FROM and TLS/auth settings for your mail provider. Delivery to a real mailbox still requires testing that provider and sender domain.
- API: configure `SUPPLIER_API_ALLOWED_HOSTS` with exact trusted supplier DNS hosts; blank disables API delivery. Only HTTPS port 443, no user-info, URL query credentials or fragments. The transport does not follow redirects and accepts only 2xx. Operators must control allowed hosts and outbound network access; do not allow arbitrary customer-managed domains.
- The same endpoint policy runs on supplier save, before SENT/recovery commits, and at transport time. Invalid configuration/payload failures are terminal. Transient failures stop after five attempts per generation. After reconciliation, an authorized operator uses delivery-recovery; never reset PO state or delete logs. Terminal handling completes the publication as processed, not delivered. Supplier API idempotency keys remain PO-based; internal generation numbers are not sent in the supplier payload.
- The generic API contract is POST of the `PurchaseOrderSent` JSON snapshot with `Idempotency-Key: purchase-order:{UUID}`. A supplier requiring bearer tokens, HMAC or a different payload needs its actual API contract and credentials before a provider-specific adapter can be verified. Do not put secrets in the endpoint URL.
- A supplier API must honor the idempotency key for crash-safe deduplication. SMTP cannot guarantee exactly-once delivery if it accepts a message and the process dies before recording success. The local success ledger and worker lock prevent ordinary duplicates, but this uncertain-outcome window requires reconciliation.
- Historical queued events from the old implementation have no line snapshot. They fail explicitly for manual reconciliation instead of sending an incomplete purchasing instruction. Historical delivery failures without a PO correlation reference are not retroactively attributed to a PO.
- Cancelling a SENT PO atomically queues a cancellation notice using the same durable listener/retry infrastructure. Draft/approved cancellations do not notify. The notice uses the last snapshotted dispatch destination, not a subsequently edited supplier email. For legacy POs without snapshots it uses the current contact. `cancellationDeliveryStatus` is independent of the original `deliveryStatus`; a delivered original PO does not imply a delivered cancellation. `templateCode` distinguishes both notice types in delivery history. Transport failures do not roll back CANCELLED; transient failures retry up to five attempts, then FAILED requires operator reconciliation. A supplier's acknowledgement of cancellation is not recorded by the original PO confirmation endpoint. Commercial amendments remain outside this change.
- Local profile disables permission enforcement. Verify the role matrix with security enabled before production UAT.

## Database upgrade

PR-local migrations are now ordered `V20260930000100`, `V20260930000200`, `V20260930000300`, above develop's `V20260928006000` (renamed again from `V20260925*`/`V20260926000100` when develop gained the full-schema migrations). Run `mvn clean` before building to remove old resource filenames. Do not enable Flyway out-of-order globally to hide this problem.

`V20260930000400` follows these migrations and adds dispatch control, append-only decision history and sent-date protection. It preserves unknown legacy send evidence rather than presenting historical orders without a queued event as QUEUED.

The upgrade preserves historical sent/received POs as PENDING without fabricating sent_at; a migration-only legacy marker permits recording responses when historical send time is unknown. Their delivery state remains UNKNOWN without delivery evidence.

IMPORTANT: a database that already ran the old unmerged PR versions (20260918000100 / 20260923000100 / 20260923000200) needs a separately reviewed reconciliation before this build. These filenames were renamed for develop compatibility. Do not run blind Flyway repair, edit history, or delete business data. Back up first; disposable local databases may be recreated only with the owner's approval. This task does not modify any existing local database or its Flyway history.

Before upgrade, inspect duplicates with `SELECT lower(code), count(*) FROM procurement.supplier GROUP BY lower(code) HAVING count(*) > 1` and `SELECT tax_code, count(*) FROM procurement.supplier WHERE tax_code IS NOT NULL GROUP BY tax_code HAVING count(*) > 1`. Resolve real duplicates with the data owner; no automatic deletion/merging is performed. Unique indexes deliberately fail rather than discard data. Invalid historical tax identifiers are copied to `supplier.legacy_tax_code`, then cleared from `tax_code`; `ck_supplier_profile` is validated during migration. No valid replacement identifier is invented. Other invalid legacy profile fields require operator reconciliation before migration; other NOT VALID checks still protect all new writes.

The receipt trigger only guards immutable evidence and requires a timestamp for a new CLOSED transition; it no longer calculates time. BusinessCalendar supplies the shared Vietnam date zone to PO numbering/default dates and KPI queries. CommercialTerms supplies application defaults; SQL defaults remain historical snapshots pinned by upgrade tests. Retry selection reads at most 50 matching registry rows per page; see docs/adr/bounded-po-retry.md. The cursor stores one scheduler position, never duplicate payloads.

## Verification

Full `mvn test` completed on 2026-10-02 after the review fixes: **420 tests, zero failures, zero errors, zero skipped**. This includes 28 supplier/PO integration cases, PostgreSQL upgrade with legacy invalid tax data, role grants/version bumps, overdue response warnings, cancellation/dispatch locking, cancellation failure/retry using the original recipient, snapshot-preserving recovery, HTTP idempotency, bounded retry, architecture and module boundaries. An initial targeted `mvn clean test` also removed old migration resources. This does not certify an existing deployment's data or a real supplier's provider credentials.

Regression tests cover real PostgreSQL migrations, simultaneous send/deactivation/create requests, unique-tax races, snapshots, rejection/receipt rules, successful and failed async delivery, durable retry, HTTP idempotent replay and KPI stability. Notification transport tests verify email contents, API allowlist, idempotency header and redirect rejection. Frontend is excluded by the repository owner's instruction.

The static `tools/verify.py` check reports 19 dependency findings in integration-test sources (test support and cross-module fixtures), also reproduced on unmodified commit `79b2d75`; it is not a clean pass. This review fix introduces no additional findings. Production module boundaries are separately checked by ArchitectureTest and ModularityTest. Do not expand production dependencies to suppress test-only findings.

## PR 36 review scope

The earlier review covered migration ordering and legacy responses, invalid status mapping, field-targeted localized validation, API preflight and bounded terminal retry with response visibility, staff grants, and supplier auditing. The October follow-up adds overdue warnings, cancellation notices, Vietnamese buyer/warehouse email snapshots, an admin DELETE grant with role-version bumps, business error codes, legacy tax reconciliation, and the documented C4 mapping below.

Supplier now owns immutable code, profile validation and status transitions; SupplierRepository is its domain port, and SupplierRepositoryAdapter owns mapping, SQL, uniqueness and pessimistic refresh. SupplierJpaRepository is package-private. Purchasing owns the delivery-history endpoint and calls notification through its named API; the old /notifications route is removed, so clients must update that URL. Global UTC timestamp handling is unchanged. Backend contract details above are authoritative for frontend integration; UI code remains excluded as requested.

## Deployment checklist

1. Run tools/sql/supplier-upgrade-preflight.sql against the intended database (read-only). Review migration identities as well as duplicate/invalid supplier records.
2. Back up before any operator reconciliation. Do not blindly repair Flyway history, reassign duplicate supplier references, or recreate a non-disposable database. If old PR migrations were already applied, compare their exact scripts/checksums and reconcile that database separately before deployment.
3. Build with mvn clean, upgrade in staging, and run the API contract checks. No application data or local Flyway history is modified by this coding task.
4. Invalid legacy tax values are archived/cleared and the profile check is validated during migration. Other invalid historical checks produce an explicit warning and protect subsequent writes. After reconciliation, run tools/sql/validate-supplier-constraints.sql; that validation script never deletes or rewrites business records.
5. Test the actual mail/API provider, security-enabled role matrix, and frontend's updated delivery-history URL before production rollout.

## Frontend integration contract (documentation only)

### Sending and recovery payloads

POST `/api/v1/purchase-orders/{id}/sending` accepts no body when a stored date exists. An overdue date is allowed with a non-blocking `DELIVERY_DATE_IN_PAST` warning; a missing date returns 400 `PO_DELIVERY_DATE_REQUIRED`. To explicitly correct a date before first dispatch:

```json
{"expectedAt":"2026-10-15","reason":"Supplier agreed this date before dispatch"}
```

After a terminal transport failure, an APPROVE-authorized buyer calls POST `/api/v1/purchase-orders/{id}/delivery-recovery`, with an Idempotency-Key and:

```json
{"reason":"Provider restored; previous outcome checked with supplier","reconciled":true,"acknowledgePastDue":false}
```

`reconciled` is a human attestation, not an automatic check with an external mailbox. For an overdue/unknown original promised date, `acknowledgePastDue` must be true. This sends the unchanged PO, not a revised commercial order. If the supplier requires revised terms/date after the first send, use a separately agreed amendment/cancellation workflow instead of altering the historical snapshot. Recovery reads the current validated supplier contact; its address and the authorizing reason are retained in delivery-decisions. Its five-attempt budget is new, while previous failures remain readable.

Cancellation waits for already-running transport, then queues its own notice. Read `cancellationDeliveryStatus` to track that notice; the original `deliveryStatus=DELIVERED` still refers only to the order. `SUPPRESSED` means remaining order dispatch was stopped, not proof that an earlier uncertain SMTP attempt never reached the supplier. Historic cancelled POs without a queued notice return cancellation status UNKNOWN; the upgrade does not automatically send notices for old cancellations.

## Buyer identity and Vietnamese notices

Before sending new orders, configure `PO_BUYER_COMPANY_NAME`, `PO_BUYER_COMPANY_ADDRESS`,
`PO_BUYER_CONTACT_NAME`, `PO_BUYER_PHONE`, `PO_BUYER_EMAIL`, and `PO_RECEIVING_ADDRESS`.
They are shown in `.env.example` and forwarded by Compose. Missing buyer/warehouse information
returns 409 `PO_COMMUNICATION_NOT_CONFIGURED` without committing SENT. The current legacy model
has no PO warehouse id: this is one default receiving address, not multi-warehouse routing.
At C4, replace it with the address of the PO's actual `warehouse_id`.

Each first send snapshots company/contact/receiving address and product line descriptions in the
existing decision record and durable publication. Missing line descriptions resolve through the
product module's public API; an unknown/ambiguous SKU without a description returns 400
`PO_LINE_DESCRIPTION_REQUIRED`. Recovery reuses the snapshot even if product names or buyer
configuration change. A legacy recovery with no snapshot must capture validated current details;
it cannot reconstruct evidence that was never recorded. SMTP notices are Vietnamese and set the
buyer email as Reply-To. Supplier API payloads add `buyer`, `type` (`PURCHASE_ORDER` or
`CANCELLATION`), and `cancellationReason`; cancellation uses the distinct idempotency key
`purchase-order:{id}:cancellation`. The internal event's historical name `PurchaseOrderSent`
is retained for compatibility with existing durable publications.

## C4 mapping: legacy PO versus target purchase_orders

This PR intentionally still runs on the legacy table, as allowed by db-design README §3.
It does not activate C4 or implement the new revision/approval workflow. The evidence-preserving
mapping below must be confirmed with the schema owner before cutover: the older state-machine
document includes SENT, whereas D4 removes it. In particular, do not assume a transport send is
supplier acceptance merely because the target enum contains CONFIRMED.

| Legacy state/evidence | Target state at cutover | Required handling |
|---|---|---|
| DRAFT | DRAFT | Preserve PO id and number |
| APPROVED, not dispatched | APPROVED | Reconcile required approved revision, actor and approval evidence |
| SENT + supplier response PENDING | APPROVED, dispatch evidence retained | Do not fabricate supplier confirmation; prevent a second initial send using existing dispatch evidence |
| SENT + supplier response CONFIRMED | CONFIRMED | Map actual supplier response time and authenticated recording actor, not SMTP delivery time |
| SENT + supplier response REJECTED | APPROVED with rejection evidence and send/receipt blocked, or CANCELLED after explicit cancellation | Never infer supplier acceptance or silently cancel historical orders |
| PARTIALLY_RECEIVED | PARTIALLY_RECEIVED | Reconcile missing confirmation and receipt evidence before enabling target constraints |
| CLOSED (legacy fully received) | RECEIVED | Full receipt is not financial closure; retain completion timestamp |
| CLOSED_SHORT | CLOSED + SHORT_CLOSE | Reconcile close reason/actor/time and open quantities |
| CANCELLED | CANCELLED | Preserve reason plus independent cancellation delivery evidence |

Preserve IDs, event publications, original send/response evidence, delivery generations,
`payload_snapshot`, cancellation control flags, and log references. Carry legacy tax values into
an approved audit/notes destination before the old supplier table is dropped. Keep supplier
response evidence distinct from target `confirmed_by/confirmed_at`; do not synthesize a user,
warehouse, revision or approval history merely to satisfy a constraint. Any unresolved actor,
revision, warehouse or historical confirmation blocks that row's cutover for reconciliation.
The target date check also rejects expected dates before order_date; such historical rows need
an explicit schema/data decision, not silent date rewriting. Coordinate with the schema owner
before C4; its pending SQL only switches keys/drops old tables, it does not do this reconciliation.

## October review migration coordination

`V20260930000100` grants supplier DELETE to ECOMMERCE_ADMIN (not PROCUREMENT_STAFF) and bumps
both affected `app_role.version` values in the same Flyway transaction. This is compatible with
ADR-0008 role-version cache invalidation. These migrations are edited in place only because PR
#36 is unmerged and the owner requested it. Databases that already applied them will have
checksum differences: do not run blind repair; use a reviewed, backed-up deployment plan.
PR #40's `V20261001000100` must follow this PR; if #40 lands first, rebase and renumber all four
PR-local migrations above develop's maximum before merge, including upgrade-test targets.

Use `code` (required, immutable), `name`, `contactName`, `email`, `phone`, `taxCode`, `status`, `paymentTermDays`, `leadTimeDays`, `communicationChannel`, and `apiEndpoint`. Do not send mock aliases `contactEmail`, `contactPhone` or free-text `paymentTerms`. Commercial terms are integer calendar days, not arbitrary strings. Currency belongs to each PO; this supplier API does not invent address/rating/currency profile fields. Performance is calculated at `/suppliers/{id}/performance`, not a manually editable rating.

Change supplier status through a complete PUT payload including current commercial terms. POST may omit terms and uses the application defaults; PUT must supply them. There is no PATCH status alias. DELETE is a privileged deactivation, not physical deletion. Delivery history is now `/api/v1/purchase-orders/{id}/deliveries`; update clients from the previous notification-owned URL. `deliveryStatus` and `supplierConfirmationStatus` describe different processes and must not be conflated. Collection responses retain the shared `data.items` envelope and pagination fields.
