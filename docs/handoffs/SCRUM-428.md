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
  `CUSTOMER_PRICE_BELOW_MOQ` (open question P4).
- **Checkout**: `OrderServiceImpl.place` prices account orders with `CatalogService.checkoutPrices(customerId,
  quantities)` — tier chosen by the order's **total** of each SKU (P5); the client's price is still only what it
  expects (`CHECKOUT_PRICE_CHANGED`), the server price is locked into the order line (14 BR-03). Products are locked
  first, as before; customer-price edits take the same product lock, so an order is priced wholly before or after.
- **Quotes** (SCRUM-448 integration): a quote line created **without tiers** starts from the customer's schedule for
  the blank (14 step 2), copied into the quote — a later price-list change does not move it; typed tiers win. No
  discount rule (P2 pending).
- **Portal display**: the logged-in ACTIVE customer's own/group/base prices with tiers and MOQ (13 BR-02, BR-05),
  published SKUs only.

## API for FE (`ApiResponse` envelope)

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

Writes are audited (`customer-price` CREATE/UPDATE/DELETE).

## Acceptance → code → test

| Requirement (Jira 428 + kltn-docs) | Code | Test | Result |
|---|---|---|---|
| Own price per customer, SKU and quantity tier (428; 13 §3; D14) | migration 000510, `CustomerPrice`, `CustomerPriceService`, `CustomerPriceController` | `CustomerPriceTest` (4), IT `onlyTheEcommerceAdminEdits…`, `CustomerPriceMigrationUpgradeTest`, QA `10_customer_pricing.sql` P1–P11 | PASS |
| E-commerce Admin configures; others read (13 §2, 14 §2) | grants + `@RequiresPermission` | IT: anonymous 401, Sales/warehouse/customer write 403, warehouse/customer read 403, Sales read 200; upgrade test grant list; QA P12 | PASS (P1 open: may Sales edit?) |
| No own price → group price, else base (13 §3) | `PriceResolver` | IT portal: CUSTOMER / SEGMENT / BASE | PASS |
| Portal shows the logged-in customer's price, tiers, MOQ (172; 13 BR-02/05) | `StorefrontPriceService`, `GET /catalog/products/prices` | IT incl. BLOCKED 403, staff 403, anonymous 401 | PASS |
| Portal ordering at the customer's price (190); MOQ (BR-05); locked into the order (14 BR-03); changed price confirmed (14 edge case) | `checkoutPrices(customerId, …)`, `OrderServiceImpl.place` | IT `checkoutCharges…`: below MOQ 409, stale expected price 409, two lines summed to a tier, later price change leaves the order, delete → base | PASS |
| Edits vs checkout / concurrent editors | product lock, CAS on version, unique key | IT `concurrentEditorsAndCheckoutAreSerialised`: 2 creates → 1 + CONFLICT, 2 replaces → 1 + OPTIMISTIC_LOCK, edit waits for a held product lock | PASS |
| Quote price defaults from the customer's list (14 step 2), quote keeps it (BR-03) | `QuoteService.defaultTiers` | IT `aQuoteStartsFromTheCustomersPriceAndKeepsIt` (real quote with a confirmed design) | PASS |
| Discount within a limit or approved (14 BR-10) | — | — | **NOT DONE — decision P2 pending** |
| Sales units (13/14 BR-11) | — | — | **NOT DONE — P6 (no sales-unit model exists)** |

## Gaps

P1–P7 in the plan. The quote default's `PRICE_NOT_AVAILABLE` → 400 branch is covered by IT
`aQuoteLineWithoutTiersForAnUnpricedBlankAsksSalesToEnterThem`. Segment rules have no management API (P7).

## Verification (2026-10-12, Java 21.0.9, Maven 3.9.9 offline, Docker 28.5.1; no full suite - lane A runs one)

| Run | Result |
|---|---|
| `CustomerPricingIntegrationTest` (security on, real PostgreSQL) | 5/5 |
| `CustomerPriceTest`, `CustomerPriceMigrationUpgradeTest` (from `V20261014000500` with demo data and an existing customer), `CustomerStatusHistoryMigrationUpgradeTest` | 4 + 1 + 1 |
| Regression: `InventoryCatalogMigrationUpgradeTest` (fresh DB + every `tools/db/qa` script + orphan check), `DemoCatalogPriceSeedTest`, `QuoteIntegrationTest`, `QuoteSecurityIntegrationTest`, `QuoteMigrationUpgradeTest`, `PlaceOrderIntegrationTest`, `CreditCheckoutIntegrationTest`, `InventoryCatalog(Security)IntegrationTest`, `ProductionGrantsMigrationUpgradeTest`, B2B onboarding/access, availability, invitation | 111 run, 1 failure → fixed (below), rerun green |
| identity + common.security tests, `CatalogProjectionStartupIntegrationTest`, `ProcurementMigrationUpgradeTest` | 122/122 |
| `ArchitectureTest`, `ModularityTest`, `MessageBundleTest`, `tools/verify.py` | pass |
| `tools/db/qa/run.sh` on a lane database migrated by the real jar (local profile, demo) | every script PASS, `10_customer_pricing` 16 checks |
| real jar, security on: admin creates, Sales 403 on create, Sales reads schedule, portal MOQ, below MOQ 409, base price refused 409, tier price 201, order line holds 10,000,000 | 8/8 behaviours (the script's last string compare expected `.00`, the column has scale 4 - value correct) |

`QuoteMigrationUpgradeTest` upgraded to the newest migration and checked role versions moved by exactly one; the
new `V20261014000510` moves the same roles. It now upgrades exactly to the migration it tests (`V20261014000300`), the
same fix SCRUM-448 applied to `ProductionGrantsMigrationUpgradeTest`. `schema.dbml` regenerated (+3 tables).
