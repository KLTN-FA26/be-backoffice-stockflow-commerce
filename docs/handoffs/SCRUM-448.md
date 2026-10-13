# SCRUM-448 — B2B quotes: tiers, acceptance and conversion to an order (backend)

Status 2026-10-12: **READY_FOR_INTEGRATION**, integrated locally into `SCRUM-394-395-production-foundation` (`ab02d7a`, see `SCRUM-394-395.md` phase C) — implemented, tested against real PostgreSQL with security
on and off, committed locally; not pushed, not merged.

## Where

- Branch `SCRUM-448-b2b-quotation`, worktree
  `C:/Users/VO HOANG MINH/orca/workspaces/be-backoffice-stockflow-commerce/SCRUM-448-b2b-quotation`.
- Based on the 394/395 foundation commit `30a5329` (branch `SCRUM-394-395-production-foundation`, which
  contains develop `74d624d`). Not based on bare develop: acceptance needs the sample flow.
- Jira read live 2026-10-12: SCRUM-448 and parent SCRUM-426 To Do, 0 comments. Description used as the
  acceptance list below; docs: `03-state-machines.md` §18, `04c-all-flows.md` F-CART 01–07,
  `05-business-rules.md` BR-ORD-007/008, plan 2026-10-06 §2 (D2, D4).

## PR #38 `DesignQuote`, checked and reused

The code is in history at `01bcf6e` (PR head `8e7c3aa` no longer carries it) and was never in develop's
tree. Read in full before deciding. Carried over as rules, rewritten in the module's style:

| PR #38 | Here |
|---|---|
| issuance separate from customer assent; customer-only acceptance | `send` by Sales; `accept` / `change-request` need APPROVE (CUSTOMER only) and a portal customer in the service |
| accepted terms immutable; revision counter | ACCEPTED is final; CHANGES_REQUESTED → DRAFT is revision + 1; every sent offer kept as JSON on its history row |
| request-id idempotent creation under an advisory lock | same (`quoteRepository.lockRequest`, `uk_quote_request`) |
| pessimistic lock + version check on every change | same; lock taken *before* loading (see "Defect found") |
| whole positive VND price, terms ≤ 2000, validity in the future | same (`PriceTier.of`, `Quote.Offer`) |
| derived expiry at read | same (`statusAt`), plus the SENT → EXPIRED sweep |

Not carried over (unrelated or replaced): the single-line `design_quote` / `design_quote_offer` /
`design_quote_issue` tables and their migration `V20260929000500` (would be out of order), the checkout
path that consumed a quote per cart line, `checkout_request`.

## What was built

- **Migration `V20261015000300__order_quotes.sql`** (after develop's `V20261013000600`, the foundation's
  `V20261015000200` and SCRUM-429's planned `V20261015000100`): `ordering.quote`, `quote_line`,
  `quote_price_tier`, `quote_status_history` with CHECKs mirroring the aggregate; `fk_sample_request_quote`
  (NOT VALID); permission resource `sales-quotes` and grants; role versions advanced.
- **Domain** `Quote`, `QuoteLine`, `PriceTier`, `QuoteStatus`, port `QuoteRepository`.
- **Order core**: `OrderServiceImpl.place(command, quotePriced)` shared by `placeOrder` and the new
  internal `QuotedOrders.placeQuotedOrder` — same idempotency, customer snapshot, payment terms, credit
  check and all-or-nothing stock holds; only the price source differs. Not on `OrderService` (a client
  price never reaches it).
- **Sample rule**: `ProductionRecords.approvedSampleOf(customer, design, blank)` and
  `producedBeforeFor(customer, design, blank)` over `ordering.approved_sample` / `order_line_production`
  (fed by `SampleApproved` / `ProductionCompleted`). No dependency on production (it depends on order).
- **API for other modules**: `order.api.QuoteDirectory.findQuote(id)` — customer, status and the
  (design, blank) pairs of a quote, for production to check a sample's `quoteId` (integration step).
- **Expiry**: `QuoteExpirySweeper` (`@Scheduled` + `@SchedulerLock`, `stockflow.order.quote-expiry-interval`,
  default PT5M, batches of 100).

## API for FE SCRUM-449 (`ApiResponse` envelope; `Idempotency-Key` honoured on writes)

Resource `sales-quotes`. Grants: SALES_STAFF VIEW_PAGE, READ, CREATE, UPDATE · ORDER_COORDINATOR
VIEW_PAGE, READ, UPDATE · CUSTOMER VIEW_PAGE, READ, APPROVE · SYSTEM_ADMIN all.

| Method / path | Permission | Who | Body → effect |
|---|---|---|---|
| `POST /api/v1/quotes` | CREATE | back office | `{requestId, customerId, paymentTerm: PREPAID\|DEPOSIT\|CREDIT, depositPercent?, validUntil, terms?, lines:[{blankSku, designSnapshotId, printSpec?, quantity, tiers:[{minQuantity, unitPrice}]}]}` → DRAFT `QT-yyyyMMdd-nnnn`, 201. Same `requestId` again → same quote. |
| `GET /api/v1/quotes` | READ | back office; portal: own, sent only | `status[]`, `customerId`, `search` (number), `sort` (`createdAt,validUntil,quoteNumber,status`), `page`, `size` |
| `GET /api/v1/quotes/{id}` | READ | same | lines with `moq`, `tiers`, accepted quantity/price/total, `sample {satisfied, approvedSampleId, reorder}`, history, `convertedOrder`, `version` |
| `PUT /api/v1/quotes/{id}` | UPDATE | back office | `{version, …same as create minus requestId/customerId}` → replace the DRAFT offer |
| `POST …/{id}/sending` | UPDATE | back office | `{version}` DRAFT → SENT |
| `POST …/{id}/acceptance` | APPROVE | portal customer only | `{version, lines?:[{lineId, quantity}]}` → ACCEPTED at the tier price of each quantity (server-side) |
| `POST …/{id}/change-request` | APPROVE | portal customer only | `{version, feedback}` → CHANGES_REQUESTED |
| `POST …/{id}/revision` | UPDATE | back office | `{version}` CHANGES_REQUESTED → DRAFT, revision + 1 |
| `POST …/{id}/cancellation` | UPDATE | back office | `{version, reason}` DRAFT → CANCELLED |
| `POST …/{id}/conversion` | UPDATE | back office | `{version, shippingAddressId?, billingAddressId?}` ACCEPTED → CONVERTED + the order; a repeat returns the same order |
| `POST /api/v1/orders/{orderId}/reorder` | `sales-orders:CREATE` | portal customer (own orders), back office | `{requestId}` → a new DRAFT quote for the order's lines at today's price list, never the old deal (SCRUM-428 lane, user decision 2026-10-12); `ORDER_NOT_QUOTED` for a price-list order |

Pricing (user decisions 2026-10-12): a line's break and MOQ are read at the quote's total of its blank, every
design together (P5); quantities are pieces and prices VND per piece (P6); responses carry server-computed
`proposedUnitPrice`, `proposedLineTotal`, `proposedTotal`. Details: `SCRUM-428.md`.

Statuses: DRAFT, SENT, CHANGES_REQUESTED, ACCEPTED, EXPIRED, CANCELLED, CONVERTED (a SENT quote past
`validUntil` reads EXPIRED at once). The portal never sees DRAFT or CANCELLED quotes (also not while a
quote is being revised).

Errors: `CUSTOMER_NOT_ACTIVE`, `PAYMENT_TERM_NOT_ALLOWED`, `DESIGN_SAMPLE_NOT_APPROVED`, `QUOTE_EXPIRED`,
`QUOTE_BELOW_MOQ`, `INVALID_QUOTE_TRANSITION`, `OPTIMISTIC_LOCK` (stale `version`), `IDEMPOTENCY_KEY_REUSED`,
`NOT_FOUND` (incl. another customer's quote), `FORBIDDEN`, `VALIDATION_FAILED`; on conversion also the
order's own (`INSUFFICIENT_STOCK`, `SHIPPING_ADDRESS_REQUIRED`, credit holds as ON_HOLD orders).

## Acceptance → code → evidence

| Jira 448 / 426 / docs | Code | Evidence | Result |
|---|---|---|---|
| customer, blank SKU, print spec | `QuoteLine`, `offer()` verifies the customer's confirmed design for the blank's product | IT `quoteSampleOrder`, `creationRules` (another customer's design → 404) | PASS |
| quantity tiers / MOQ, price | `PriceTier`, `QuoteLine.priceFor/moq` | unit `tiersAre…`, IT accepts 5 units at the 2,500 tier, below MOQ refused | PASS |
| validity, EXPIRED | `statusAt`, `expire`, sweeper | unit; IT `expiry` (read EXPIRED, accept/changes refused, sweep writes it once) | PASS |
| payment term | `Offer.paymentTerm/depositPercent`, `requireTermAllowed` | IT DEPOSIT refused for a prepaid-only customer; unit conversion passes DEPOSIT 40% | PASS |
| SENT → ACCEPTED / CHANGES_REQUESTED / EXPIRED (+ revise, cancel) | `Quote` | unit edge matrix; IT `changesAndRevision` (both offers kept) | PASS |
| acceptance blocked for a new design without an approved sample | `accept` + `ProductionRecords.approvedSampleOf` (customer + design + blank) | IT refused before, accepted after the **real** sample flow (production services → `SampleApproved` → order listener); IT customer filter; unit refusal | PASS |
| reorder of an approved / already printed design | approved sample of the same customer, or `producedBeforeFor` | unit (real data cannot reach the produced-before branch without SCRUM-400) | PASS (unit) |
| accepted quote → sales order, once | `convert` → `QuotedOrders` | IT: order PENDING_PAYMENT, quote price not catalogue price, design + checksum, blanks held; repeat → same order; 2 concurrent → 1 order; stock shortfall → nothing, quote ACCEPTED | PASS |
| server-authoritative price | no price in accept/convert bodies; tiers resolved server-side; `placeQuotedOrder` internal | IT/unit | PASS |
| permissions staff / customer own | grants + `ScopeGuards` + portal checks | HTTP: 401, 403 (warehouse staff, customer create, Sales/coordinator accept, customer convert), 404 another customer, own list | PASS |
| BR-ORD-007 ACTIVE customer only | `requireNewWorkFor` | IT BLOCKED → create/send refused, draft still cancellable | PASS |
| DB upgrade | migration | `QuoteMigrationUpgradeTest` from V20261015000200 with earlier rows | PASS |

## Defect found and fixed during the work

Two concurrent conversions: the loser got `ObjectOptimisticLockingFailureException` instead of the
winner's order, because the adapter loaded the quote first and then locked it with
`refresh(PESSIMISTIC_WRITE)`. Fixed by locking the row with `SELECT … FOR UPDATE` before loading (the
`StockItemRepositoryAdapter` pattern); 3/3 reruns pass. The production foundation's
`SampleRequestRepositoryAdapter.findByIdForUpdate` uses load-then-lock too; a 10-round concurrent-submit
probe (temporary, not committed) gave the correct outcome every time (no fetch-joined collections there),
so it was **not** changed here — align it during the 394/395 integration step.

## Gaps and decisions

1. **Not Jira-448 acceptance, recorded as dependencies (checked live 2026-10-12):**
   - *Default price from the customer's price list* (F-CART 01) depends on **SCRUM-428 "Customer-specific
     Price List"** (To Do, 0 comments): `catalog.pricing_rule` is keyed by segment, not customer, and needs a
     per-customer list from the DB owner. Until then Sales enters every tier price.
   - *"Discount within the sales staff's limit, or approved"* (03 §18 send guard) has neither a reference
     price (that is SCRUM-428's list) nor any defined limit, approver or ticket. No rule was invented. Safe
     behaviour today: no discount concept; every send records who sent which offer (JSON on the SENT history
     row, plus the audit log), so a limit can be enforced on that data once it is decided.
2. The sample → quote link: wired in the 394/395 integration (`cd4ac69` on
   `SCRUM-394-395-production-foundation`): `SampleRequestService` checks `QuoteDirectory` (open quote, same
   customer, same design on the same blank) on create, edit and next round.
3. Blanks are held when the order is placed (conversion), not at acceptance (F-CART 05 order of steps is
   kept as "acceptance → order with holds"); an accepted quote does not reserve stock by itself.
4. Acceptance needs the customer on the portal (03 §18). Sales cannot accept on a customer's behalf; if the
   business wants that, grant APPROVE to SALES_STAFF and drop the portal check in `requireCustomerAnswer`.
5. Portal hides a quote while Sales revises it (DRAFT revision > 1); the FE may want "being revised".
6. Flyway: `V20261015000100` (SCRUM-429), `…000200` (foundation), `…000300` (here) must merge in that
   order, or be renumbered above whatever an environment has already applied.

## Tests run (2026-10-12, Java 21.0.9, Maven 3.9.9 offline, Docker 28.5.1, Testcontainers)

- `QuoteTest` 9, `QuoteServiceTest` 5, `ModularityTest` 3, `ArchitectureTest` 18, `MessageBundleTest` 5.
- `QuoteIntegrationTest` 7, `QuoteSecurityIntegrationTest` 1 (security on), `QuoteMigrationUpgradeTest` 1.
- Every `com.stockflow.order` test (incl. `PlaceOrderIntegrationTest`, `CreditCheckoutIntegrationTest`,
  `OrderReleaseIntegrationTest`) and `ProductionIntegrationTest` / `ProductionSecurityIntegrationTest` — pass.
- `tools/verify.py` — all static checks passed.
- Full `mvn -o test` of this branch (before the two fixes below): **1000 tests, 2 failures, 4 errors**.
  - `InventoryCatalogMigrationUpgradeTest.freshDatabasePassesRepositorySqlQaAndHasNoOrphans` — **caused here**:
    `fk_sample_request_quote` was added NOT VALID, and a fresh database must have no NOT VALID key. Fixed:
    the migration validates the key when no sample names a quote (always, on a real database) and
    `tools/db/orphan_check.sql` gains the `sample_request.quote_id -> ordering.quote` check.
  - `ProductionGrantsMigrationUpgradeTest` — **caused here**: it upgraded to the latest migration, which now
    includes V20261015000300 moving the same role versions. Fixed: it upgrades exactly to V20261015000200.
  - Both reran green, with `QuoteMigrationUpgradeTest`, `QuoteIntegrationTest` 7, `QuoteSecurityIntegrationTest`,
    `ProductionIntegrationTest` 10 and `tools/verify.py`.
  - 4 errors: `GoodsReceiptIntegrationTest` (3) and `SupplierProcurementIntegrationTest` (1) with
    `Invalid receipt/expiry date` from `inventory.enforce_sku_policy()` — the baseline clock-skew failure
    analysed in `SCRUM-394-395.md` (no procurement/inventory file changed here). Not fixed.
  - Not rerun in full after the fixes: the final full run is planned after the 394/395 integration.
