# SCRUM-70 / SCRUM-71 — backend contract

## Ownership and scope

- Product owns SKU inventory configuration, product master data, approval and approved galleries.
- Inventory owns execution of FIFO/FEFO, physical stock and a synchronously applied SKU policy.
  Product calls Inventory's API in the same transaction; no cross-schema join or foreign key.
- Catalog owns the editable canonical product slug and SEO in `catalog.product_listing`.
  `catalog.catalog_entry` is the per-SKU read projection, not a second editable SEO source.
- Catalog owns selling prices through the existing `catalog.pricing_rule`; this story provides
  an editable base rule, not a second pricing engine. Existing higher-priority rules still win.
- Catalog orchestrates the existing product publication URLs. Dependency direction is
  Catalog -> Product -> Inventory, plus Catalog -> Inventory for storefront stock bands.
  Product never calls Catalog back.

The approved extension also implements quantity cycle counts and persistent low-stock alerts with
SMTP delivery/retry (SCRUM-147 integration). It does not implement frontend, automated purchasing,
receipt authoring/serial-history UI, financial inventory valuation, promotions, or Elasticsearch search.
SKU/variant creation remains the product/variant stories' responsibility; these APIs operate on an
existing SKU belonging to the addressed product. The application does not manufacture SKUs.

## Inventory policy

Endpoint: `GET/PUT /api/v1/products/{productId}/skus/{skuId}/inventory-control`.
Permissions: `product-products:READ` / `product-products:UPDATE`.

PUT is a full replacement, with the current SKU `version`:

```json
{
  "version": 0,
  "reorderPoint": 20,
  "safetyStock": 5,
  "removalStrategy": "FEFO",
  "trackingMode": "LOT",
  "expiryTracked": true,
  "maxShelfLifeDays": 365
}
```

- Thresholds use the SKU's existing integer stock unit. Negative values are invalid.
- Null means unconfigured, not zero. Both thresholds may be independently null.
- If both exist, safety stock must not exceed reorder point.
- Reorder evaluation: usable on-hand <= reorder point. Safety evaluation: usable on-hand < safety stock.
- Usable on-hand excludes quarantine, damage and expired stock, but does not subtract reservations.
  It is deliberately different from ATP. An unconfigured evaluation returns null, not false.
- A threshold above current stock is allowed and the response reports the evaluated low-stock state.
- A successful update emits `SkuInventoryControlChanged`. The alert consumer evaluates after commit,
  re-reads latest policy/stock and creates/resolves persistent alert episodes. A scheduled reconciliation
  scan also covers direct receipt updates, missed events and stock becoming expired at midnight.
- Policy saving, inventory compatibility checks and the runtime copy commit/roll back together.
  Version mismatch is 409; a SKU belonging to another product is 404.
- Configuration changes are blocked while master approval is pending or after discontinuation.
- NONE / LOT / SERIAL are mutually exclusive. The existing `lot_tracked` column is retained;
  serial and expiry are separate flags, not overloaded meanings of that boolean.
- Expiry tracking requires LOT or SERIAL and FEFO. Shelf-life days are optional, 1..36500 when set.
  They cannot be set with expiry tracking off.
- Changing policy while current stock violates the new policy is rejected (409).
  This includes removing tracking, removing expiry dates, enabling serial with non-serial stock,
  or enabling FIFO with stock whose receipt time is unknown.
- Date boundaries use the Vietnam business calendar. A date expires after that entire local date,
  not at UTC midnight. ATP, allocation, threshold evaluation and consumption exclude expired stock.

### FIFO, serial and receiving integration

FIFO sorts by the real receipt instant; FEFO sorts by expiry then receipt instant. Remaining quantity,
location and stock id provide deterministic tie breaks. Existing holds are not silently reallocated.

Stock now supports receipt layers: identical SKU/location/lot may have different receipt instants.
Stock ingress must pass the actual receipt instant to the new `StockItem.receive` overload.
Never derive it from a database audit timestamp. FIFO restocking must create a new layer instead
of adding newer units into the old layer. Legacy null-time rows keep their original uniqueness.

Serial stock has at most one unit and a nonblank serial number; live serials are unique per SKU.
This does not claim a complete serial lifecycle (returns, repair history and transfer UI).
Receiving must capture the serial and persist it through the receipt-aware stock overload.

A PostgreSQL trigger enforces configured tracking/expiry rules on ingress, including missing lot,
missing serial, missing expiry, expired arrival, future receipt time and excessive shelf life.
It shares a SKU advisory lock with policy updates so concurrent ingress cannot bypass a policy change.
Multi-SKU ingress transactions must acquire SKU locks in a consistent SKU order.
Reservation/consumption updates do not acquire this extra lock, preserving the allocator's row-lock order.

Unconfigured legacy SKUs retain their old execution default (FEFO); configure the SKU through this
endpoint to activate the explicit runtime policy. Existing legacy stock is not rewritten on upgrade.
Receipt authoring is a separate story and must surface field-level validation before persistence;
the database rule is the last line of defence, not a replacement for its form validation.

## Catalog and publication

All management APIs reuse `product-products` permissions; no separate role/grant system is added.

| Endpoint | Permission | Result |
|---|---|---|
| GET /api/v1/products/{id}/ecommerce | READ | Editable slug/SEO, revision, projection revision and pending flag |
| PUT /api/v1/products/{id}/ecommerce | UPDATE | Updated source configuration |
| GET /api/v1/products/{id}/skus/{sku}/base-price | READ | Configured base price, effective public price and current revision; null means not configured |
| PUT /api/v1/products/{id}/skus/{sku}/base-price | UPDATE | Updated listing revision after changing base price |
| POST /api/v1/products/{id}/publication | APPROVE | Publish intent committed; projection may still be pending |
| POST /api/v1/products/{id}/unpublication | APPROVE | Product and catalog source hidden in the same transaction |
| GET /api/v1/public/catalog/products?page=0&size=20 | Anonymous | Standard paginated product cards |
| GET /api/v1/public/catalog/products/{slug} | Anonymous | Published content, SKU prices, stock bands, gallery API link |

Editable SEO:

```json
{"revision":0,"slug":"ban-go-soi","seoTitle":"Oak table","seoDescription":"Oak dining table"}
```

Base selling price (use the revision returned by the preceding operation):

```json
{"revision":1,"price":2500000,"currency":"VND"}
```

Price rules: VND only, positive whole dong, up to 16 integer digits. Trailing decimal zeros are
accepted; fractional dong and foreign currencies are rejected, never silently rounded. Domain
validation reuses Money after validating the amount; a database check also protects BASE rules.
The editable base rule is named `BASE:<SKU>`, priority zero, no customer segment.
Anonymous publication pricing uses active non-segment rules for the SKU or global rules; highest
priority wins. Equal highest priorities are a conflict, never an arbitrary database ordering.
Customer-specific quotes and discounts remain separate concerns. Standard HTTP checkout and guest
checkout obtain published prices from Catalog; the submitted amount is only the expected price.
Changing a base price does not override a higher-priority rule.

Publishing requires APPROVED/PUBLISHED master, valid category, SKU(s), valid selling prices for all
SKUs and the approved nonempty gallery. Repeated publish/unpublish is idempotent in its settled state.
Draft SEO can be prepared, but it does not itself publish a product.
No API publishes a DRAFT, PENDING_APPROVAL or DISCONTINUED product.

Slugs are lower-case, accent-folded, hyphen-separated and at most 140 characters.
Service checks plus a unique database constraint protect against simultaneous claims.
After the first publication the slug stays reserved and immutable even when unpublished.
This deliberately avoids broken links without introducing a redirect subsystem.
SEO-only edits on a published product are allowed to users with UPDATE and are projected asynchronously;
they do not modify or bypass product-master approval.

### Consistency and retry

- Source changes publish a Modulith event; the consumer re-reads current data under the product lock.
  Repeated/out-of-order events cannot restore old content or resurrect an unpublished product.
- Source/projected revisions form a durable reconciliation condition. Projection failures are logged;
  the source transaction remains committed and pending work is retried every 30 seconds by default.
- Retry visits at most 50 sources, oldest-attempted first, so persistently failing sources cannot
  indefinitely block later sources. Enabled products are also reconciled against live master status.
- Projection rebuilding is available through the internal `CatalogProjectionService`, not an
  unauthenticated administrative endpoint.
- Public reads check live Product status. No fallback exposes draft source fields.
  Initial publication can briefly return 404 until its first published projection is ready.
- Public catalog responses use no-store. SEO may briefly lag an edit; live public prices and stock
  bands are fetched from their authoritative modules. Availability uses a batch aggregate query,
  not stock aggregates with reservation history. Each calculation captures one Vietnam business date.
  Read availability does not authorize checkout/reservation.
- Pagination totals are projection totals and can briefly lag a discontinuation. Hidden products
  are removed from the returned page by the live status guard, even during that window.
- Unpublication does not recall an image already downloaded or purge pre-existing CDN asset caches.
  Original/design private-file access rules are unchanged.

## Deployment / legacy preflight

Only new migrations are added:
`V20260930001000__sku_inventory_control.sql`, `V20260930001100__catalog_publication.sql`, and
`V20260930001200` through `V20260930001600` (counts, approved corrections, alerts, permissions and
checkout quotes). Renumbered on 29/9 from `V20260927*`/`V20260929*` so they run after develop's
`V20260928006000` and after the supplier/PO migrations `V20260930000100..000400` this branch stacks on.
No applied migration was edited; no local production/developer database was migrated by the tests.

Before enabling FIFO for legacy stock:

```sql
SELECT sku, location_code, lot_number, on_hand
FROM inventory.stock_item WHERE on_hand > 0 AND received_at IS NULL;

SELECT code FROM product.sku WHERE code <> upper(trim(code));
```

Reconcile real receipt records first. Do not invent receipt dates, normalize live SKU references in
one table only, delete stock, or use Flyway repair to hide mismatches.
Noncanonical legacy SKU codes are rejected by configuration updates to avoid applying the policy
to a different normalized inventory key.

Deployment acceptance still requires UAT using representative warehouse data and deployed roles.
Security integration tests now exercise the real filter chain, permission converter and aspects
with security enabled; token decoding is stubbed, so signing/session validation is not their scope.
Local integration tests use Testcontainers, never the developer's normal database.

## Checkout and remaining integration boundaries

- Catalog resolves all standard basket SKU prices under consistently ordered product locks in the
  order transaction. Unpublished, missing or unprojected SKUs cannot be ordered through that path.
- A different expected price returns CHECKOUT_PRICE_CHANGED (409), without creating an order or
  holding stock. The client refreshes the basket and obtains customer confirmation before retrying.
- A successful guest request replay returns the agreed order even if catalog prices later change.
  Reusing that request id with a different basket/address remains rejected.
- HTTP account orders use the same price check for standard lines. Existing custom-design lines
  and trusted internal legacy commands retain their separate agreed-price path; this work does
  not implement a custom-design quotation/approval engine.
- Guest checkout remains behind its existing rollout switch; this change does not enable production.
- Inventory adjustment is distinct from receipt authoring. FIFO receipts still require a new layer.
  The approved count path below corrects an existing layer without changing its receipt age.
- Low-stock alerts do not place purchase orders and are not a forecast-based reorder engine.

## Approved extension: physical cycle counts

Base URL: `/api/v1/inventory/cycle-counts`. Resource: `inventory-cycle-counts`.

| Method / suffix | Permission | Behavior |
|---|---|---|
| GET collection / GET `/{id}` | READ | Scoped paginated plans / full count details |
| POST collection | CREATE | Plan 1..200 distinct existing stock rows in one warehouse; idempotent requestId |
| POST `/{id}/start` | UPDATE | Assigned counter starts and captures stock baselines |
| PUT `/{id}/lines/{stockId}` | UPDATE | Assigned counter records quantity and variance reason |
| POST `/{id}/submit` | UPDATE | No variance: POSTED; variance: VARIANCE_REVIEW |
| POST `/{id}/approval` | APPROVE | Independent approval; counter cannot approve own measurements |
| POST `/{id}/rejection` | APPROVE | Return review to COUNTING, clear measurements and capture a fresh baseline |
| POST `/{id}/posting` | APPROVE | Atomically apply approved differences, append ledger and mark POSTED |
| POST `/{id}/recount` | UPDATE | Assigned counter refreshes baseline; clears measurements and approval |
| PUT `/{id}/assignment` | APPROVE | Replace unavailable counter; clear old measurements and approval |
| POST `/{id}/cancellation` | UPDATE | Cancel PLANNED only, with reason |

Creation body: `requestId`, `warehouse` (e.g. HCM), `assignedTo`, `stockIds`, optional `note`.
Mutations carry the returned `version`; recording also carries `quantity` and optional `reason`
(mandatory for a difference). Rejection/recount/cancellation/assignment require a reason.
Assignment additionally carries `assignedTo`. Eligible counters are active warehouse staff, warehouse
managers or inventory planners; warehouse/owner scope still limits their actual access.

- One active count per stock row prevents overlapping adjustments. No long-lived database lock
  is held while a human counts. Movements remain possible, but changed quantity/version forces recount.
- A shortage below reserved stock is recorded truthfully but cannot be submitted/posted. Reconcile
  affected orders through their existing cancellation/reallocation workflow, then recount. Counting
  never discards a hold or silently changes a customer's order.
- All differences require an independent approver. The current workflow is quantity-based: there
  is no invented inventory cost, monetary approval threshold or accounting posting.
- Positive FIFO corrections are permitted only with matching approved adjustment evidence in the
  same transaction. Normal replenishment cannot use that exception. Serial quantity remains 0..1;
  tracking metadata and receipt age stay unchanged. Expired stock can be counted but is never ATP.
- Posted adjustments are append-only and contain before/after quantity, reason, counter, approver,
  stock/count references and posting time. Details expose creation, approval and posting timestamps.
  Repeated/concurrent posting does not apply a difference twice. Posted counts cannot be reopened;
  correct a later discrepancy with a new independently approved count.
- These are counts of existing stock layers, not a receiving endpoint: unknown goods/unknown receipt
  dates must be reconciled against receiving records first, never invented to bypass FIFO.
- Warehouse staff receive READ/UPDATE; managers can plan/approve; planners can plan/record subject to
  existing grants. Assignment does not grant cross-warehouse access. All mutations use shared audit.

## Approved extension: low-stock alert inbox and email

Base URL: `/api/v1/inventory/alerts`. Resource: `inventory-alerts`.

| Method / suffix | Permission | Behavior |
|---|---|---|
| GET collection | READ | Page alerts; optional status OPEN/RESOLVED |
| GET `/{id}` | READ | Quantity, threshold, type, observation/recovery times and acknowledgement |
| POST `/{id}/acknowledgement` | UPDATE | Record who acknowledged it; does not pretend stock recovered |
| GET `/{id}/deliveries` | READ | Recipient, phase, delivery status, attempts, retry time, safe error |
| POST `/{id}/delivery-retry` | UPDATE | Requeue failed/config-blocked deliveries after correcting configuration |

Thresholds are currently per SKU company-wide, not per warehouse. Alert APIs therefore require ALL
data scope as well as the permission; warehouse-only users cannot see totals for other warehouses.
Warehouse managers and inventory planners receive grants, but the administrator must deliberately
assign appropriate data scope. Email recipients must be trusted company-wide operations staff.

- One OPEN episode per SKU/type; repeated checks update it without sending repeat emails.
- Recovery or clearing the threshold closes the episode; a later shortage creates a new episode.
- Policy changes, stock deductions and count postings trigger evaluation after commit. Reconciliation
  checks up to 50 least-recently-attempted SKU policies per 30-second run. Lag grows with SKU count;
  this is eventual notification, not a real-time stock promise. Failed evaluations do not starve later SKUs.
- Alert state and notification intent commit together. SMTP is called later outside that transaction.
  Delivery uses leases, bounded batches, exponential backoff and a terminal FAILED state after ten
  failed attempts. The manager can retry; permanent failures do not block newer messages.
- Recovery supersedes unsent shortage notices. An email already in flight cannot be recalled;
  each message labels its observation time and directs staff to current alert state.
- SMTP delivery is at-least-once: a process crash after SMTP acceptance but before recording success
  can duplicate an email. The inbox remains deduplicated; no unsupported exactly-once claim is made.
- Set `INVENTORY_ALERT_RECIPIENTS=planner@example.com,warehouse-manager@example.com` plus existing
  SMTP variables. Blank recipients keep inbox alerts working and show BLOCKED_CONFIG, not false SENT.
  Local Mailpit can be used to inspect delivery without contacting real recipients.

### Acceptance walkthrough

1. Set reorder=5/safety=3 on a SKU with two usable units: observe two OPEN episodes, not repeated copies.
2. Acknowledge one; it remains OPEN. Restore stock to ten; both resolve. Clear thresholds; no new alerts.
3. With SMTP unavailable, stock/count operations still commit; delivery becomes retryable and later FAILED.
4. Plan a count for an assigned counter. Record a difference with reason, submit, independently approve,
   post twice: one ledger adjustment only. Confirm receipt timestamp and other stock identities are unchanged.
5. Repeat with a concurrent reservation/receipt: posting must reject stale evidence and require recount.
6. Check wrong-warehouse access, self-approval, missing reason, reserved-stock shortage and serial quantity >1.
7. Run deployment UAT with actual users, warehouse scopes, representative data and the chosen SMTP provider.

## Verification

- `StockPolicyTest`: null/zero/negative thresholds, contradictory tracking, FIFO/FEFO and expiry.
- `ListingTest`: Vietnamese slug normalization, stale revisions and immutable published URLs.
- `InventoryCatalogIntegrationTest`: real PostgreSQL constraints, transactional policy application,
  FIFO reservations, serial ingress, expiry, publication prerequisites, duplicate events, slug races,
  concurrent SKU updates and ingress/configuration races.
- Existing module, architecture, migration, inventory/order, supplier/PO and media tests remain enabled.
- SellingPriceTest covers whole VND, foreign currencies, fractional dong and amount bounds.
- InventoryCatalogSecurityIntegrationTest covers anonymous access, READ/UPDATE/APPROVE separation,
  private draft data and publication prerequisites with security enabled.
- CycleCountTest / InventoryOperationsIntegrationTest cover count transitions, independent approval,
  scopes, reservations, concurrent posting, FIFO preservation, immutable ledger and alert episodes.
- InventoryAlertDeliveryIntegrationTest covers missing configuration, retries, lease takeover,
  stale workers, recovery suppression and terminal failures; sender/job tests cover the transport boundary.
- `tools/verify.py` has the existing 15 test-only dependency findings; this feature adds no new finding.

Baseline before the checkout/quote hardening below, on 2026-09-29: `mvn verify` completed successfully with 467 tests, zero failures/errors/skips,
including PostgreSQL, MinIO and ClamAV integration tests. `git diff --check` passed. SMTP account
credentials and delivery to actual staff mailboxes still require deployment verification; transport
unit tests use a mocked mail sender. Build log: `target/scrum-70-71-counts-alerts-verify.log`.

## Follow-up business hardening: quotes, replay and concurrent operations

Custom-design checkout now requires a company-issued quotation, not a price supplied by the customer.
The new `/api/v1/design-quotes` APIs use resource `sales-quotes`:

| Method / suffix | Permission | Behavior |
|---|---|---|
| GET collection / `/{id}` / `/{id}/revisions` | READ | Scoped, paginated list/history; customer sees only issued revisions |
| POST collection | CREATE, ALL scope | Create draft for an active linked customer and confirmed design snapshot/SKU |
| POST `/{id}/revisions` | APPROVE | Append commercial revision before acceptance |
| POST `/{id}/issuance` | APPROVE | Publish the current draft to the customer |
| POST `/{id}/change-requests` | UPDATE, OWN | Customer requests changes with a reason |
| POST `/{id}/acceptance` | UPDATE, OWN | Only the linked customer accepts the exact current version |
| POST `/{id}/cancellation` | APPROVE | Withdraw a quote before acceptance, with a reason |

Creation takes `requestId`, `customerId`, `designSnapshotId`, `sku`, `quantity`, `unitPrice`,
`validUntil` (ISO timestamp with timezone), and `terms`. Mutations take returned `version`;
revisions add the commercial fields and change/cancellation requests add `reason`.
Responses include quote ID, customer/snapshot/SKU, status/version, revision, quantity, unit/total
VND price, validity/terms, acceptance evidence, consumed order ID and response note.

Lifecycle: DRAFT -> SENT -> ACCEPTED -> CONSUMED. A customer change request moves SENT to
CHANGES_REQUESTED; company revision creates a new DRAFT, which must be issued again.
Commercial revisions and issuance evidence are append-only. An unissued current draft is hidden
from customer list/detail, including during renegotiation and after withdrawing an unissued draft.
This API issues a quote for portal access; it does not claim to send a quote email or PDF.

- The agreed price is a positive, whole-VND final unit selling price for the quoted quantity and
  confirmed snapshot. Terms describe what that price includes. This does not introduce a separate
  tax, shipping-fee, discount-approval or accounting engine.
- Design order lines must send `quoteId` alongside `designSnapshotId`. The server checks customer,
  SKU, snapshot, exact quantity, acceptance and expiry, then uses the persisted quotation price.
  A changed expected price returns CHECKOUT_PRICE_CHANGED; a missing/mismatched/consumed quote
  returns DESIGN_QUOTE_REQUIRED. Expired quotes cannot be accepted or checked out.
- One quote authorizes one order, including under concurrent requests. Creating the order, holding
  stock and consuming the quote are atomic. A failed checkout leaves the accepted quote reusable.
  Cancelling a successfully placed order does not silently reactivate its quote; obtain a new quote.
- Accepted/consumed terms cannot be revised or withdrawn through these APIs. Negotiation before
  acceptance uses revisions; a new agreement after acceptance requires a new quote.
- Signed-in checkout stores a normalized request fingerprint atomically with the order. Exact
  retries return the original order; altered customer, address IDs, lines, quantity, price or quote
  with the same request ID returns IDEMPOTENCY_KEY_REUSED. Historical orders created without a
  fingerprint fail closed on replay; this migration does not fabricate their original requests.
- Operational SKU policy changes are blocked with COUNT_POLICY_CONFLICT while any active count
  references that SKU, even when recorded stock is zero. Threshold-only changes remain allowed.
  Count validation also checks required lot/serial/expiry/FIFO metadata before approval/posting.
- Checkout pre-locks all basket SKUs and stock rows in stable order; count posting uses the same
  SKU-before-stock ordering, preventing the reviewed reversed-basket/count lock cycle.
  Individual stock loads also acquire a scalar row lock before fetching/refreshing the entity,
  avoiding Hibernate follow-on locking against a stale version after a concurrent reservation.
- An expired SENDING shortage-email lease is checked against recovery before takeover. A recovered
  alert supersedes that old notice rather than resending it; already-in-flight SMTP remains at-least-once.

Regression tests added for quote negotiation/ownership/immutability, private drafts, price tampering,
changed replay, competing quote consumption, rollback on insufficient stock, active-count policy
changes, reversed-basket locking and recovery of expired email leases.

Verified on 2026-09-29 at 19:44 +07:00: full `mvn verify` completed successfully with **480 tests,
zero failures/errors/skips**, including the new PostgreSQL transaction/concurrency tests and the
existing media integrations. The executable JAR was packaged successfully. Log:
`target/business-fixes-verify.log`. `git diff --check` passed. The static helper still reports its
15 pre-existing test-only dependency findings; the executable architecture tests passed.
This is backend verification, not a claim of completed frontend integration or deployment UAT.
