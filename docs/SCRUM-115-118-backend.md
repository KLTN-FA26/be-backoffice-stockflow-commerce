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
| GET `/api/v1/notifications/purchase-orders/{id}/deliveries` | procurement-purchase-orders:READ | Paginated delivery attempts, channel, attempted/sent times and sanitized failure category |

Existing PO list/detail endpoints also return supplier response fields. An empty delivery history means no recorded attempt; inspect the PO status to distinguish not sent from awaiting its first attempt. SMTP acceptance and API 2xx are transport success, never supplier business confirmation.

## Performance definitions

- Total counts all supplier POs. Fulfilled counts CLOSED only; CLOSED_SHORT is not full fulfillment.
- On-time/late are evaluated only for fully received POs with a due date and actual completion evidence, using Vietnam calendar dates. New completion timestamps are captured at the first transition to CLOSED and preserved on later updates. Legacy rows use the latest COMPLETED goods receipt; an unknown date remains unknown.
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
- The generic API contract is POST of the `PurchaseOrderSent` JSON snapshot with `Idempotency-Key: purchase-order:{UUID}`. A supplier requiring bearer tokens, HMAC or a different payload needs its actual API contract and credentials before a provider-specific adapter can be verified. Do not put secrets in the endpoint URL.
- A supplier API must honor the idempotency key for crash-safe deduplication. SMTP cannot guarantee exactly-once delivery if it accepts a message and the process dies before recording success. The local success ledger and worker lock prevent ordinary duplicates, but this uncertain-outcome window requires reconciliation.
- Historical queued events from the old implementation have no line snapshot. They fail explicitly for manual reconciliation instead of sending an incomplete purchasing instruction. Historical delivery failures without a PO correlation reference are not retroactively attributed to a PO.
- Cancellation/amendment communication is a separate purchasing workflow. Do not treat an internal cancellation as evidence that a previously emailed supplier has been notified.
- Local profile disables permission enforcement. Verify the role matrix with security enabled before production UAT.

## Database upgrade

`V20260923000100__supplier_po_integrity.sql` is additive. It preserves historical rows and adds receipt-completion evidence, delivery correlation and new-write checks using NOT VALID where legacy profiles may be incomplete. It does not rewrite already-applied migration files. Inspect legacy records before validating those checks globally.

`V20260923000200__po_retry_cursor.sql` adds one scheduler-position row for fair retry across restarts. It does not store event payloads or duplicate the Modulith queue.

## Verification

Regression tests cover real PostgreSQL migrations, simultaneous send/deactivation/create requests, unique-tax races, snapshots, rejection/receipt rules, successful and failed async delivery, durable retry, HTTP idempotent replay and KPI stability. Notification transport tests verify email contents, API allowlist, idempotency header and redirect rejection. Frontend is excluded by the repository owner's instruction.
