# SCRUM-428 — customer-specific prices by SKU and quantity tier (backend) + quote integration

Lane B of Sprint 2. Plan, sources and open questions: `docs/handoffs/SPRINT2-PRICING-PLAN.md`.
Branch `SCRUM-428-customer-pricing` from the integration checkpoint `4a389a6`. Local commits only; not
pushed, not merged, no Jira change. The SCRUM-429 follow-up is its own commit on this branch (see the end).

## What was built

- **Migration `V20261014000510__catalog_customer_prices.sql`** (lane range `…000500–000599`):
  `catalog.customer_price` (customer + SKU, unique, FK to `customer.customer` cascade and `product.variants`,
  VND only, versioned) and `catalog.customer_price_tier` (`min_quantity ≥ 1`, whole positive VND, unique per
  price). Permission resource `catalog-customer-prices`; ECOMMERCE_ADMIN all, SALES_STAFF VIEW_PAGE+READ,
  ORDER_COORDINATOR READ, SYSTEM_ADMIN all; those role versions advanced.
- **Domain** `CustomerPrice` (tiers sorted, first tier = MOQ, last tier reached applies, ≤ 20 tiers, stale editor
  refused). No order between tier prices is imposed (the documents give none).
- **Resolution** `PriceResolver`: own tier → active rule of the customer's segment (`pricing_rule.segment_id`,
  existing, previously unread) → base price `BASE:<sku>`; group/base apply from 1 unit. Below the first own tier:
  `CUSTOMER_PRICE_BELOW_MOQ` "ask Sales for a quote" — no fall back (P4, decided: Sales quotes it).
- **Checkout**: `OrderServiceImpl.place` prices account orders with `CatalogService.checkoutPrices(customerId,
  quantities)` — tier chosen by the order's **total** of each SKU (P5, decided: every design together, never across SKUs); the client's price is still only what it
  expects (`CHECKOUT_PRICE_CHANGED`), the server price is locked into the order line (14 BR-03). Products are locked
  first, as before; customer-price edits take the same product lock, so an order is priced wholly before or after.
- **Quotes** (SCRUM-448 integration): a quote line created **without tiers** starts from the customer's schedule for
  the blank (14 step 2), copied into the quote — a later price-list change does not move it; typed tiers win.
  **No discount limit and no internal approval** (user decision 2026-10-12, overriding kltn-docs 14 BR-10): Sales
  negotiates and sends any valid price (whole VND > 0, validated on the server); the customer still has to accept.
  A deal is **never saved as the customer's price** (user decision P1, 2026-10-12).
- **Reorder of a negotiated order** (P1 decision): `POST /api/v1/orders/{orderId}/reorder` creates a new DRAFT quote
  with the order's blanks, designs, print specifications and quantities, priced from **today's** price list — not
  the old deal. Nothing is payable until Sales sends it and the customer accepts; conversion stays Sales' explicit
  step. An approved design needs no new sample. The draft's history says which order it reorders.
  A line the list cannot price (no list price, or the blank's total below the list's first tier) is left
  **explicitly unpriced** (R1): `priced: false`, no tiers, MOQ and totals null; `QUOTE_NOT_PRICED` refuses sending
  until Sales prices it, so it cannot be accepted or converted either. **Payment term (R1b, 2026-10-13):** the old
  term is kept while the customer may still use it; otherwise the request is still created, on the customer's
  current default term with no deposit share carried over - a withdrawn credit or deposit is never restored, and the
  draft's history says so. Send, acceptance and conversion check the term again; the old order keeps its terms.
- **Sales notice**: a customer's acceptance, change request or reorder publishes `CustomerActedOnQuote` naming the
  Sales account that sent the quote (for a reorder, the order's quote); the notification module e-mails it after
  commit through the configured SMTP server. That account gone, locked or without an e-mail → every active Sales
  account, marked "[Cần tiếp nhận]", each address once, so one of them takes the customer on (user decision N1,
  2026-10-13; access to the quote unchanged); no active Sales account at all → a WARN log.
- **Group prices** (P7): `V20261014000530` (one price per group and SKU, positive whole VND, grants);
  `SegmentPrice`, `SegmentPriceService`, `/api/v1/catalog/segment-prices`; product lock, CAS version, audited
  `segment-price`. Resolution unchanged.
- **Customer groups** (P7 completed 2026-10-13, branch `SPRINT2-finish-lane-b`): `V20261014000600` (resource
  `customer-segments`; `ck_segment_code` upper-case code, `ck_segment_name`, both NOT VALID so older rows are never
  rewritten); `CustomerSegment`, `CustomerSegmentService`, `CustomerSegmentController`. E-commerce Admin and System
  Admin create / rename groups and assign or clear a customer's group (customer version read, row lock, audited
  `customer-segment`, `customer-segment-assignment`); Sales and the Order Coordinator read. The code is fixed once
  created. `GET /api/v1/customers?segmentId=` lists a group's customers.
- **Household identifier** (T1, from official texts): the 12-digit personal ID is accepted next to 10 and 10-3
  (`TaxCode`, `V20261014000540` widens `ck_customer_tax_code`).
- **Portal display**: the logged-in ACTIVE customer's own/group/base prices with tiers and MOQ (13 BR-02, BR-05),
  published SKUs only.

## API for FE (`ApiResponse` envelope)

Units (P6, decided): every `quantity` / `minQuantity` is **pieces (cái)**, a whole number ≥ 1; every `unitPrice` is
**whole VND per piece**. No carton or conversion in this batch.

Quote form fields (SCRUM-448 schema, unchanged): `blankSku`, `designSnapshotId`, `printSpec`, `quantity`,
`tiers[{minQuantity, unitPrice}]` (optional: from the list), `paymentTerm`, `depositPercent`, `validUntil`, `terms`.
Totals come from the server: `lines[].proposedUnitPrice`, `lines[].proposedLineTotal`, `proposedTotal`, and after
acceptance `acceptedUnitPrice`, `acceptedLineTotal`, `acceptedTotal`. Delivery is chosen at conversion
(`shippingAddressId`, `billingAddressId`); the quote has no delivery fields. The FE form is not built in this batch.

| Method / path | Permission | Body / params → result |
|---|---|---|
| `GET /api/v1/catalog/customer-prices?customerId=&sku=…` | `catalog-customer-prices:READ` | the customer's own prices (all, or these SKUs) |
| `GET /api/v1/catalog/customer-prices/{customerId}/{sku}` | READ | `{customerId, sku, moq, tiers:[{minQuantity, unitPrice}], note, version}`; 404 `CUSTOMER_PRICE_NOT_FOUND` |
| `GET …/{customerId}/{sku}/schedule` | READ | what the customer pays now: `{sku, source: CUSTOMER\|SEGMENT\|BASE, moq, currency, tiers}` — the quote line default |
| `POST …/{customerId}/{sku}` | CREATE | `{tiers:[{minQuantity, unitPrice}], note?}` → 201; 409 `CONFLICT` if one exists; 404 `CUSTOMER_NOT_FOUND` / `VARIANT_NOT_FOUND`; 400 `VALIDATION_FAILED` |
| `PUT …/{customerId}/{sku}` | UPDATE | `{tiers, note?, version}` → replaced, version + 1; 409 `OPTIMISTIC_LOCK` for a stale version |
| `DELETE …/{customerId}/{sku}?version=` | DELETE | removed; the customer falls back to group/base price |
| `GET /api/v1/catalog/products/prices?sku=…` (≤ 50) | CUSTOMER role, ACTIVE linked customer | `[{sku, source, moq, currency, tiers}]` for published, priced SKUs; 403 otherwise, 401 anonymous |
| `POST /api/v1/orders` (existing) | unchanged | lines priced by the customer's tier; 409 `CUSTOMER_PRICE_BELOW_MOQ`, `CHECKOUT_PRICE_CHANGED` |
| `POST /api/v1/quotes` (existing) | unchanged | a line's `tiers` may now be omitted → copied from `…/schedule` |
| `POST /api/v1/orders/{orderId}/reorder` | `sales-orders:CREATE` (the verb that places an order: CUSTOMER, SALES_STAFF, …) | `{requestId}` → 201, a DRAFT quote (`QuoteResponse`); same `requestId` → same quote. Portal: own orders only (404 otherwise), and the draft is visible to the customer once Sales sends it. Unpriced lines where today's list cannot price them (R1). 409 `ORDER_NOT_QUOTED` for an order bought at the price list (place it again with `POST /orders`); `CUSTOMER_NOT_ACTIVE`. A withdrawn payment term no longer refuses: the draft takes an allowed term (R1b) |
| `POST /api/v1/quotes/{id}/sending` (existing) | unchanged | 409 `QUOTE_NOT_PRICED` while a line has no price |
| Quote responses (existing) | — | new: `priced` (quote and line), `moq` nullable for an unpriced line |
| `GET /api/v1/customer-segments` | `customer-segments:READ` (E-commerce Admin, System Admin, Sales, Order Coordinator) | `[{id, code, name, description, customerCount, version}]` by code |
| `GET /api/v1/customer-segments/{id}` | READ | one group; 404 `SEGMENT_NOT_FOUND` |
| `POST /api/v1/customer-segments` | CREATE (E-commerce Admin, System Admin) | `{code, name, description?}` → 201, code trimmed + upper-cased; 409 `CONFLICT` for a code in use; 400 for a code outside `[A-Z0-9][A-Z0-9_-]{0,63}` or a blank name |
| `PUT /api/v1/customer-segments/{id}` | UPDATE (same) | `{name, description?, version}` → version + 1; the code never changes; 409 `OPTIMISTIC_LOCK`; 404 |
| `PUT /api/v1/customers/{customerId}/segment` | `customer-segments:UPDATE` (same) | `{segmentId \| null, version}` (the customer's version) → `CustomerResponse`; 404 `CUSTOMER_NOT_FOUND` / `SEGMENT_NOT_FOUND`; 409 `OPTIMISTIC_LOCK`. Later prices only: quotes and orders keep theirs |
| `GET /api/v1/customers?segmentId=` (existing) | `customer-customers:READ` | new filter: the group's customers |
| `GET /api/v1/catalog/segment-prices?segmentId=&sku=…` | `catalog-segment-prices:READ` (E-commerce Admin, System Admin, Sales, Order Coordinator) | the group's prices |
| `GET …/segment-prices/{segmentId}/{sku}` | READ | `{segmentId, sku, price, currency, version}`; 404 `SEGMENT_PRICE_NOT_FOUND` |
| `POST …/segment-prices/{segmentId}/{sku}` | CREATE (E-commerce Admin, System Admin) | `{price}` → 201; 409 `CONFLICT`; 404 `SEGMENT_NOT_FOUND` / `VARIANT_NOT_FOUND`; 400 for 0, fractions, negatives, missing |
| `PUT …/segment-prices/{segmentId}/{sku}` | UPDATE (same) | `{price, version}` → version + 1; 409 `OPTIMISTIC_LOCK`; 400 without version |
| `DELETE …/segment-prices/{segmentId}/{sku}?version=` | DELETE (same) | removed: the group's customers fall back to their own or the base price |

Writes are audited (`customer-price`, `segment-price` CREATE/UPDATE/DELETE; `quote` on every quote step).

## Acceptance → code → test

| Requirement (Jira 428 + kltn-docs) | Code | Test | Result |
|---|---|---|---|
| Own price per customer, SKU and quantity tier (428; 13 §3; D14) | migration 000510, `CustomerPrice`, `CustomerPriceService`, `CustomerPriceController` | `CustomerPriceTest` (4), IT `onlyTheEcommerceAdminEdits…`, `CustomerPriceMigrationUpgradeTest`, QA `10_customer_pricing.sql` P1–P11 | PASS |
| E-commerce Admin configures; others read (13 §2, 14 §2) | grants + `@RequiresPermission` | IT: anonymous 401, Sales/warehouse/customer write 403, warehouse/customer read 403, Sales read 200; upgrade test grant list; QA P12 | PASS (P1 decided: admin edits, Sales reads, deals are not saved) |
| No own price → group price, else base (13 §3) | `PriceResolver` | IT portal: CUSTOMER / SEGMENT / BASE | PASS |
| Portal shows the logged-in customer's price, tiers, MOQ (172; 13 BR-02/05) | `StorefrontPriceService`, `GET /catalog/products/prices` | IT incl. BLOCKED 403, staff 403, anonymous 401 | PASS |
| Portal ordering at the customer's price (190); MOQ (BR-05); locked into the order (14 BR-03); changed price confirmed (14 edge case) | `checkoutPrices(customerId, …)`, `OrderServiceImpl.place` | IT `checkoutCharges…`: below MOQ 409, stale expected price 409, two lines summed to a tier, later price change leaves the order, delete → base | PASS |
| Edits vs checkout / concurrent editors | product lock, CAS on version, unique key | IT `concurrentEditorsAndCheckoutAreSerialised`: 2 creates → 1 + CONFLICT, 2 replaces → 1 + OPTIMISTIC_LOCK, edit waits for a held product lock | PASS |
| Quote price defaults from the customer's list (14 step 2), quote keeps it (BR-03) | `QuoteService.defaultTiers` | IT `aQuoteStartsFromTheCustomersPriceAndKeepsIt` (real quote with a confirmed design) | PASS |
| Discount within a limit or approved (14 BR-10) | **overridden by the user 2026-10-12**: no limit, no approval step, no new role | — | not applicable |
| Sales sends a negotiated price (> 5 % below the list) without approval; invalid price refused on the server; warehouse cannot send; Sales cannot accept; another customer gets 404 on read/accept; the customer sees the price SENT; quote audited; a later list edit does not move the quote | existing `QuoteService` validation, `QuoteController` permissions, quote price snapshot | IT `salesSendsANegotiatedPriceWithoutApprovalAndOnlyTheCustomerAccepts` (list 10,000,000 → sent 8,000,000; 0 / 10.5 / -5 → `VALIDATION_FAILED`) | PASS |
| A placed order is not changed by a later price-list edit (14 BR-03) | order line snapshot | IT `checkoutCharges…` (edit and delete after placing) | PASS |
| Reorder of a negotiated order: same blank, design, print spec and quantity; today's list price, not the deal; no order created; retried click → same draft; Sales renegotiates and sends; only the customer's acceptance fixes the new price, and it is not converted on its own; the approved design needs no new sample; the first order keeps its price after a list change | `QuoteService.reorder`, `OrderController.reorder` | IT `QuoteIntegrationTest.reorderStartsANewQuoteAtTodaysPrice` (list 3,000/2,800, deal 2,500, renegotiated 2,700) | PASS |
| P4: below the list's first tier, direct checkout 409 `CUSTOMER_PRICE_BELOW_MOQ`; a quote line left to the list refused ("enter the negotiated price"), never priced at the higher tier; Sales quotes the actual quantity at the agreed price; Sales cannot accept (403/FORBIDDEN), another customer 404; a revision makes the customer accept again and the revision-1 version is refused (`OPTIMISTIC_LOCK`); converted once at the accepted quantity and price; prepaid and unpaid → `PENDING_PAYMENT`, release refused, no production event | `QuoteService.defaultTiers`, `ErrorCode.CUSTOMER_PRICE_BELOW_MOQ`, existing release gate | IT `QuoteIntegrationTest.belowTheListTierSalesQuotesTheActualQuantity` | PASS |
| P5: two designs on one blank pick the break together (3 + 2 = 5 → 2,500 for both); MOQ checked on the blank's total; other SKUs never added in; the stored acceptance is not recomputed | `Quote.Offer`, `Quote.accept`, `QuoteLine` | `QuoteTest.designsOnTheSameBlankAreCountedTogetherOtherBlanksAreNot`, `sendThenAccept…` (now 1,800 for both lines), IT `designsOnOneBlankPickTheBreakTogether`; checkout's sum by SKU: IT `checkoutCharges…` | PASS |
| Server totals for the FE form; a line without `tiers` over HTTP (was a 500 in the mapper) | `QuoteWebMapper`, `QuoteResponse` | `QuoteSecurityIntegrationTest.whoMayDoWhat` | PASS |
| Reorder access: another customer 404 (service and HTTP), anonymous 401, warehouse 403, missing `requestId` 400; a price-list order 409 `ORDER_NOT_QUOTED` for the customer and for Sales; an unknown order 404 | same | IT `QuoteIntegrationTest.aPriceListOrderIsNotRequoted`, `QuoteSecurityIntegrationTest.whoMayReorder` | PASS |
| Sales units (13/14 BR-11) | pieces only (P6, decided by the user: no carton / conversion in this batch) | — | scope chosen |
| Sales is told when a customer accepts, asks for changes or reorders; nobody guessed without an active Sales account; no real mail in tests | `CustomerActedOnQuote`, `QuoteService.notifySales`, `QuoteNotificationListener`, `NotificationSender.sendQuoteNotice` | `QuoteNotificationListenerTest` (4); IT: events with the right Sales e-mail for CHANGES_REQUESTED / ACCEPTED / REORDER_REQUESTED (once per click), mail captured by a mocked `JavaMailSenderImpl`, disabled Sales → `salesEmail` null | PASS |
| R1: a reorder the list cannot price reaches Sales unpriced; cannot be sent / accepted / converted until priced; then the normal flow | `QuoteLine.priced`, `Quote.send`, `QuoteService.listTiers` | `QuoteTest.anUnpricedLineIsExplicitAndCannotBeSent`, `QuoteServiceTest.aReorderOfABlankWithoutAListPrice…` (no list price), IT `aReorderTheListCannotPriceWaitsForSales` (below the 1,000 tier) | PASS |
| P7: group prices by the admins, read by Sales / coordinator; validation, version, concurrency, audit; resolution own → group → base | `SegmentPriceController`, `SegmentPriceService`, `SegmentPriceRepositoryAdapter`, `V20261014000530` | IT `groupPricesAreTheAdminsAndResolveBetweenOwnAndBase` (401/403/400/404/409, System Admin edits, 2 audit rows, own wins, delete → BASE, 2 racing creates / edits); QA G1–G6 | PASS |
| T1: 12-digit household identifier accepted, company rule unchanged | `TaxCode`, `V20261014000540` | `CustomerOnboardingTest`, QA B5a/B5b, onboarding ITs | PASS |

## Gaps

No business question is open in lane B. N1 (hand the notice to the active Sales team) and R1b (reorder on a term
allowed today, checked again at each step) were decided on 2026-10-13.

- **E-mail delivery needs external configuration**: `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`,
  `MAIL_FROM`, `MAIL_SMTP_AUTH`, `MAIL_SMTP_STARTTLS` of a real SMTP relay. The repository has only `localhost:1025`
  (Mailpit, which captures and never delivers); the prod profile adds nothing. No delivery has been observed.
- ~~Customer groups have no API~~ - built 2026-10-13 (see "What was built"); no group delete (a group in use by
  prices and customers is renamed, not removed - not asked for).
- Group prices are one price from the first piece, as `pricing_rule` holds; kltn-docs 13 §54 mentions tiers for
  groups too - not asked for in P7 ("theo catalog hiện có"), so not built.
- FE is not part of this batch. The quote default's `PRICE_NOT_AVAILABLE` → 400 branch is covered by IT
  `aQuoteLineWithoutTiersForAnUnpricedBlankAsksSalesToEnterThem`.

## Also in lane B: production holds are e-mailed (SCRUM-396 / 398 / 401, 2026-10-13)

`ProductionNotificationListener` turned the hold notice from a log line into e-mail: a rejected print file or a wait
for the customer → every ACTIVE `SALES_STAFF` account (Jira 396 "Sales is notified"); blanks short → every ACTIVE
`PROCUREMENT_STAFF` account (398 "alert purchasing"); machine down, subcontractor late, other → every ACTIVE
`WAREHOUSE_MANAGER` account (401 "Warehouse Manager resolves"). Addresses from `identity :: api` (`StaffDirectory`),
the notification module's only dependency; after commit, retried from the publication registry when the mail
server refuses; nobody to write to → WARN log. It consumes the existing `ProductionOrderHeld` unchanged. Should one
Sales person rather than the team receive it, `ProductionOrderHeld` would need the responsible user (a nullable
field lane A fills) - not required by Jira. Tests: `ProductionNotificationListenerTest` (7, mocked transport).

## Verification (2026-10-12, Java 21.0.9, Maven 3.9.9 offline, Docker 28.5.1; targeted tests only - the full suite runs at final integration)

| Run | Result |
|---|---|
| T1: `CustomerOnboardingTest` 5, `B2bOnboardingIntegrationTest` 17, `B2bOnboardingMigrationUpgradeTest` 1, `CustomerStatusHistoryMigrationUpgradeTest` 1, `InventoryCatalogMigrationUpgradeTest` 5 (every QA script incl. B5a/B5b) | 29/29 |
| Sales notice + R1: `QuoteTest` 11, `QuoteServiceTest` 6, `QuoteNotificationListenerTest` 4, `QuoteIntegrationTest` 13, `QuoteSecurityIntegrationTest` 2 | 36/36 |
| P7: `CustomerPricingIntegrationTest` 8, `ArchitectureTest` 18, `ModularityTest` 3, `MessageBundleTest` 5; then `InventoryCatalogMigrationUpgradeTest` 5 (fresh DB + every `tools/db/qa` script incl. G1–G6), `CustomerPriceMigrationUpgradeTest`, `CustomerStatusHistoryMigrationUpgradeTest`, `QuoteMigrationUpgradeTest`, `ProductionGrantsMigrationUpgradeTest`; `tools/verify.py` | 34/34, 9/9 (first attempt hit a Docker `ryuk` start failure - environment, rerun green), pass |
| `CustomerPricingIntegrationTest` (security on, real PostgreSQL) | 7/7 (after the P2 override) |
| After P4/P5: `QuoteTest` 10, `QuoteIntegrationTest` 11, `QuoteSecurityIntegrationTest` 2, `CustomerPricingIntegrationTest` 7, `CustomerPriceTest` 4, `PlaceOrderIntegrationTest` 7, `CreditCheckoutIntegrationTest` 4, `QuoteMigrationUpgradeTest` 1, `InventoryCatalogMigrationUpgradeTest` 5 (every `tools/db/qa` script), `ArchitectureTest`, `ModularityTest`, `MessageBundleTest`; `tools/verify.py` | 77/77, pass |
| After the reorder: `QuoteIntegrationTest` 9/9, `QuoteSecurityIntegrationTest` 2/2; `ArchitectureTest`, `ModularityTest`, `MessageBundleTest`, `CustomerPricingIntegrationTest`, `PlaceOrderIntegrationTest`, `CreditCheckoutIntegrationTest`, `QuoteMigrationUpgradeTest` 45/45; `tools/verify.py` | pass |
| After the override: `QuoteIntegrationTest`, `QuoteSecurityIntegrationTest`, `ArchitectureTest`, `ModularityTest`, `tools/verify.py` | 29/29, static checks pass |
| `CustomerPriceTest`, `CustomerPriceMigrationUpgradeTest` (from `V20261014000500` with demo data and an existing customer), `CustomerStatusHistoryMigrationUpgradeTest` | 4 + 1 + 1 |
| Regression: `InventoryCatalogMigrationUpgradeTest` (fresh DB + every `tools/db/qa` script + orphan check), `DemoCatalogPriceSeedTest`, `QuoteIntegrationTest`, `QuoteSecurityIntegrationTest`, `QuoteMigrationUpgradeTest`, `PlaceOrderIntegrationTest`, `CreditCheckoutIntegrationTest`, `InventoryCatalog(Security)IntegrationTest`, `ProductionGrantsMigrationUpgradeTest`, B2B onboarding/access, availability, invitation | 111 run, 1 failure → fixed (below), rerun green |
| identity + common.security tests, `CatalogProjectionStartupIntegrationTest`, `ProcurementMigrationUpgradeTest` | 122/122 |
| `ArchitectureTest`, `ModularityTest`, `MessageBundleTest`, `tools/verify.py` | pass |
| `tools/db/qa/run.sh` on a lane database migrated by the real jar (local profile, demo) | every script PASS, `10_customer_pricing` 16 checks |
| real jar, security on: admin creates, Sales 403 on create, Sales reads schedule, portal MOQ, below MOQ 409, base price refused 409, tier price 201, order line holds 10,000,000 | 8/8 behaviours (the script's last string compare expected `.00`, the column has scale 4 - value correct) |

`QuoteMigrationUpgradeTest` upgraded to the newest migration and checked role versions moved by exactly one; the
new `V20261014000510` moves the same roles. It now upgrades exactly to the migration it tests (`V20261014000300`), the
same fix SCRUM-448 applied to `ProductionGrantsMigrationUpgradeTest`. `schema.dbml` regenerated (+3 tables).
