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
- Initial sending requires a non-overdue Vietnam delivery date. The optional sending body can provide `expectedAt` and a nonblank `reason` to revise the date before the first snapshot. No automatic date shift occurs. Recovery cannot change a sent PO's date.
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
- Cancellation/amendment communication is a separate purchasing workflow. Do not treat an internal cancellation as evidence that a previously emailed supplier has been notified.
- Local profile disables permission enforcement. Verify the role matrix with security enabled before production UAT.

## Database upgrade

PR-local migrations are now ordered `V20260925000100`, `V20260925000200`, `V20260925000300`, above develop's `V20260919001700`. Run `mvn clean` before building to remove old resource filenames. Do not enable Flyway out-of-order globally to hide this problem.

`V20260926000100` follows these migrations and adds dispatch control, append-only decision history and sent-date protection. It preserves unknown legacy send evidence rather than presenting historical orders without a queued event as QUEUED.

The upgrade preserves historical sent/received POs as PENDING without fabricating sent_at; a migration-only legacy marker permits recording responses when historical send time is unknown. Their delivery state remains UNKNOWN without delivery evidence.

IMPORTANT: a database that already ran the old unmerged PR versions (20260918000100 / 20260923000100 / 20260923000200) needs a separately reviewed reconciliation before this build. These filenames were renamed for develop compatibility. Do not run blind Flyway repair, edit history, or delete business data. Back up first; disposable local databases may be recreated only with the owner's approval. This task does not modify any existing local database or its Flyway history.

Before upgrade, inspect duplicates with `SELECT lower(code), count(*) FROM procurement.supplier GROUP BY lower(code) HAVING count(*) > 1` and `SELECT tax_code, count(*) FROM procurement.supplier WHERE tax_code IS NOT NULL GROUP BY tax_code HAVING count(*) > 1`. Resolve real duplicates with the data owner; no automatic deletion/merging is performed. Unique indexes deliberately fail rather than discard data. NOT VALID constraints still check new writes and updates of legacy rows; clean historical profiles before updating them, then explicitly VALIDATE constraints under an approved data-cleanup plan.

The receipt trigger only guards immutable evidence and requires a timestamp for a new CLOSED transition; it no longer calculates time. BusinessCalendar supplies the shared Vietnam date zone to PO numbering/default dates and KPI queries. CommercialTerms supplies application defaults; SQL defaults remain historical snapshots pinned by upgrade tests. Retry selection reads at most 50 matching registry rows per page; see docs/adr/bounded-po-retry.md. The cursor stores one scheduler position, never duplicate payloads.

## Verification

Full `mvn clean test` completed on 2026-09-26: 411 tests, zero failures, zero errors, zero skipped, including PostgreSQL upgrade, cancellation/dispatch locking, concurrent recovery, HTTP recovery replay and bounded retry. A subsequent focused ProcurementBusinessDateTest run passed all 5 cases after the final single-instant send adjustment, including the added Vietnam-midnight boundary case. This does not certify an existing deployment's data or a real supplier's provider credentials.

Regression tests cover real PostgreSQL migrations, simultaneous send/deactivation/create requests, unique-tax races, snapshots, rejection/receipt rules, successful and failed async delivery, durable retry, HTTP idempotent replay and KPI stability. Notification transport tests verify email contents, API allowlist, idempotency header and redirect rejection. Frontend is excluded by the repository owner's instruction.

The static `tools/verify.py` check still reports 15 dependency findings in integration-test sources (test support and cross-module fixtures); it is not a clean pass. Production module boundaries are separately checked by ArchitectureTest and ModularityTest. Do not expand production dependencies to suppress test-only findings.

## PR 36 review scope

The seven blocking/required findings are addressed: migration ordering and legacy responses, invalid status mapping, field-targeted localized validation, API preflight and bounded terminal retry with response visibility, staff grants, and supplier auditing. Additional fixes cover literal search, PUT commercial-value preservation, and stale notification documentation/import style.

Supplier now owns immutable code, profile validation and status transitions; SupplierRepository is its domain port, and SupplierRepositoryAdapter owns mapping, SQL, uniqueness and pessimistic refresh. SupplierJpaRepository is package-private. Purchasing owns the delivery-history endpoint and calls notification through its named API; the old /notifications route is removed, so clients must update that URL. Global UTC timestamp handling is unchanged. Backend contract details above are authoritative for frontend integration; UI code remains excluded as requested.

## Deployment checklist

1. Run tools/sql/supplier-upgrade-preflight.sql against the intended database (read-only). Review migration identities as well as duplicate/invalid supplier records.
2. Back up before any operator reconciliation. Do not blindly repair Flyway history, reassign duplicate supplier references, or recreate a non-disposable database. If old PR migrations were already applied, compare their exact scripts/checksums and reconcile that database separately before deployment.
3. Build with mvn clean, upgrade in staging, and run the API contract checks. No application data or local Flyway history is modified by this coding task.
4. Clean databases validate the new constraints during migration. Invalid historical rows produce an explicit warning and remain protected on subsequent writes. After reconciliation, run tools/sql/validate-supplier-constraints.sql; validation is atomic and never deletes or rewrites business records.
5. Test the actual mail/API provider, security-enabled role matrix, and frontend's updated delivery-history URL before production rollout.

## Frontend integration contract (documentation only)

### Sending and recovery payloads

The original POST `/api/v1/purchase-orders/{id}/sending` still accepts no body when the stored date is valid. An overdue or missing date returns 409 with no state change. To explicitly correct it before first dispatch:

```json
{"expectedAt":"2026-10-15","reason":"Supplier agreed this date before dispatch"}
```

After a terminal transport failure, an APPROVE-authorized buyer calls POST `/api/v1/purchase-orders/{id}/delivery-recovery`, with an Idempotency-Key and:

```json
{"reason":"Provider restored; previous outcome checked with supplier","reconciled":true,"acknowledgePastDue":false}
```

`reconciled` is a human attestation, not an automatic check with an external mailbox. For an overdue/unknown original promised date, `acknowledgePastDue` must be true. This sends the unchanged PO, not a revised commercial order. If the supplier requires revised terms/date after the first send, use a separately agreed amendment/cancellation workflow instead of altering the historical snapshot. Recovery reads the current validated supplier contact; its address and the authorizing reason are retained in delivery-decisions. Its five-attempt budget is new, while previous failures remain readable.

Cancellation waits for already-running transport. A cancelled PO with DELIVERED means the supplier must be contacted about cancellation; SUPPRESSED means remaining application dispatch was stopped, not proof that an earlier uncertain SMTP attempt never reached the supplier.

Use `code` (required, immutable), `name`, `contactName`, `email`, `phone`, `taxCode`, `status`, `paymentTermDays`, `leadTimeDays`, `communicationChannel`, and `apiEndpoint`. Do not send mock aliases `contactEmail`, `contactPhone` or free-text `paymentTerms`. Commercial terms are integer calendar days, not arbitrary strings. Currency belongs to each PO; this supplier API does not invent address/rating/currency profile fields. Performance is calculated at `/suppliers/{id}/performance`, not a manually editable rating.

Change supplier status through a complete PUT payload including current commercial terms. POST may omit terms and uses the application defaults; PUT must supply them. There is no PATCH status alias. DELETE is a privileged deactivation, not physical deletion. Delivery history is now `/api/v1/purchase-orders/{id}/deliveries`; update clients from the previous notification-owned URL. `deliveryStatus` and `supplierConfirmationStatus` describe different processes and must not be conflated. Collection responses retain the shared `data.items` envelope and pagination fields.
