# Legacy demo SKUs removal Implementation Plan (part 1 of 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the furniture demo data (`SOFA-3S-GREY`, `TABLE-OAK-160`, products `SOFA-3S`, `TABLE-OAK`) and give every test and QA script that leaned on it an equivalent made of printed cups and paper boxes, so part 2 can require `units_per_carton > 1`.

**Architecture:** One demo migration adds the replacement data first (same bins, same carton counts, same LPN numbers and lots as the furniture had, so most assertions only change SKU strings and unit counts), then deletes the furniture. Tests and QA files move in groups, each group green before the next.

**Tech Stack:** Flyway demo migrations (PostgreSQL 16), JUnit 5 + Testcontainers, `tools/db/qa/*.sql` via `RepositorySqlQaTest`.

**Spec:** `docs/superpowers/specs/2026-11-19-one-carton-spec-per-sku-design.md` (Decided: legacy demo SKUs; Data migration step 1). Part 2: `docs/superpowers/plans/2026-11-19-one-carton-spec-per-sku.md`.

## Global Constraints

- Never edit a merged migration or demo migration; add a new one. Version newer than every migration on every pushed branch: this plan uses `V20261206000100`; renumber before merging if something newer has landed (`tools/verify.py` catches duplicates).
- **Never `git commit` or `git push` without the repository owner's go-ahead** (CLAUDE.md). Every "Commit" step below means: show the diff summary, ask, then commit.
- Fixtures come from `support/` (`DemoData`, `ReferenceRows`, `CustomerSkuRows`, `CartonRows`), never invented ids.
- Demo text in Vietnamese is fine (demo data); code and javadoc in English.
- No "(demo)" or "DEMO" wording in new display names (owner, 2026-11-19); codes may keep `DEMO`.
- `tools/db/seed/seed_test_data.sql` is out of reach of this plan's author: the implementer checks whether it names the furniture SKUs and, if so, regenerates it in Task 1.

## Review Focus

1. A test that only passed because a sofa carton held **one** unit now gets 1,000 per carton: unit counts in assertions must be multiplied, not the carton counts. Every moved test asserts cartons where it can.
2. The replacement stock must be **free** (no hold): `LY-GIAY-DEMO-12OZ-XANH`'s order `SO-20261120-900001` is PENDING_CONTRACT and holds nothing; a test checking ATP must still see 33 cartons free after the migration.
3. Quarantine stock (30 cartons in HCM-QC01) must stay out of ATP, as the table's did.
4. Truck-fit maths (`CartonStationSupport.giveSofaCartonsAWeight`) depended on a 2.15 m, heavy carton: the fixture sets the replacement carton's size and weight explicitly, so the maths is unchanged.
5. `CatalogProjectionStartupIntegrationTest` counts published products: after this plan there are exactly 2 (`LY-GIAY-DEMO`, `HOP-GIAY-DEMO`).

---

### Task 1: Replacement demo data, furniture removed

**Files:**
- Create: `src/main/resources/db/demo/V20261206000100__demo_cups_replace_furniture.sql`
- Modify: `src/test/java/com/stockflow/support/DemoData.java`, `src/test/java/com/stockflow/support/DemoDataIntegrationTest.java`
- Test: `src/test/java/com/stockflow/support/DemoDataIntegrationTest.java`

**Interfaces:**
- Produces (demo data, keys hashed with `md5('<key>')::uuid` as the wf02 file does):

| Role (was) | Now |
|---|---|
| `SOFA-3S-GREY`, 25 cartons HCM-A01-2-B + 8 HCM-A02-1-A, lot `UNTRACED`, AVAILABLE, LPN-20261111-000001…033 | `LY-GIAY-DEMO-12OZ-XANH` (owner `DemoData.CUSTOMER_ID`, THUNG ×1000, 60×40×50 cm, 8.6 kg) — same bins, counts, lot, LPNs |
| `TABLE-OAK-160`, 12 cartons HCM-B01-1-A lot `LOT-2026-03` AVAILABLE (…034…045) + 30 HCM-QC01 lot `LOT-2026-04` QUARANTINE (…046…075), not rotatable | new customer SKU `LY-GIAY-DEMO-12OZ-DO` (owner `DemoData.CUSTOMER_ID`, its own design snapshot made like wf02's, THUNG ×500, 70×45×55 cm, 9.5 kg, `rotatable = false`, barcode `8930000000011`) — same bins, counts, lots, statuses, LPNs |
| product `SOFA-3S` (published, axes COLOR/MATERIAL) as a second catalogue product and attribute user | new product `HOP-GIAY-DEMO` "Hộp giấy in logo", PUBLISHED, CUSTOMIZABLE, category `HOP_GIAY` (new, beside `LY_GIAY`), axes COLOR (existing values GREY, BLUE) and MATERIAL (new value PAPER); template variants `HOP-GIAY-DEMO-GREY-PAPER`, `HOP-GIAY-DEMO-BLUE-PAPER` (no stock, no carton unit) |
| `DemoData.PRODUCT_SOFA` | `DemoData.PRODUCT_CUP = demoId-compatible id of md5('demo:wf02:product:LY-GIAY-DEMO')`, `DemoData.PRODUCT_BOX`, `DemoData.SKU_STOCKED = "LY-GIAY-DEMO-12OZ-XANH"`, `DemoData.SKU_QUARANTINED = "LY-GIAY-DEMO-12OZ-DO"`, `DemoData.BLANK_CUP = "LY-GIAY-DEMO-12OZ"` |

- Stock rows the furniture had empty (`11111111-…`, `22222222-…`, `33333333-…`, `44444444-…`) are recreated for the new SKUs at the same locations with new ids; opening `RECEIPT` ledger lines mirror `V20261128000100` for the new cartons; `stock_item` totals match the cartons.

- [ ] **Step 1: Write the failing test** — in `DemoDataIntegrationTest`, replace the furniture assertions with `demoStockMatchesTheFurnitureItReplaced()`: cartons of `SKU_STOCKED` per location = {HCM-A01-2-B: 25, HCM-A02-1-A: 8}, of `SKU_QUARANTINED` = {HCM-B01-1-A: 12 AVAILABLE, HCM-QC01: 30 QUARANTINE}; every carton's `nominal_qty` equals its SKU's carton factor; the ledger balance of each SKU equals `stock_item.on_hand`; no row anywhere names `SOFA-3S%` or `TABLE-OAK%` (query `inventory.inventory_items`, `product.variants`, `product.products`, `inventory.stock_movement`); exactly 2 PUBLISHED products.

- [ ] **Step 2: Run it to verify it fails**
Run: `mvn test -Dtest=DemoDataIntegrationTest`
Expected: FAIL (furniture still present, new SKUs absent)

- [ ] **Step 3: Write the demo migration** — insert first, delete last. Deleting the furniture deletes, in FK order: ledger lines, stock rows, cartons, lots, units, items, media, variant attribute values, variants, product attributes and options, products, then categories `LIVING`, `DINING`, `SOFA`, `TABLE` and dictionary values used only by them (`OAK`, `FABRIC`, `160`), product change-log rows of those products. Append-only triggers are disabled for the delete and enabled again in the same file, as `V20261121000200` does for `tg_stock_movement_append_only`. Ends with an assertion block (`DO $$ … RAISE EXCEPTION`) that no furniture row is left.

- [ ] **Step 4: Run the test** — `mvn test -Dtest=DemoDataIntegrationTest` → PASS. Then `PYTHONUTF8=1 python -X utf8 tools/verify.py` → no findings.

- [ ] **Step 5: Commit** (after the owner's go-ahead) — `feat(demo): printed cups and paper boxes replace the furniture demo data`

### Task 2: Support classes on the cups

**Files:**
- Modify: `src/test/java/com/stockflow/support/ConfirmedDesigns.java`, `CustomerSkuRows.java`, `ReceiptCartons.java`, `CartonRows.java`, `ShippedCartons.java`, `PermissionMatrixTest.java`; `src/test/java/com/stockflow/fulfillment/CartonStationSupport.java`

**Interfaces:**
- Consumes: Task 1's `DemoData` constants.
- Produces: `ConfirmedDesigns.BLANK = DemoData.BLANK_CUP`; `confirmed()` / `confirmedWithPreview()` on `PRODUCT_CUP`; `CustomerSkuRows.like(…)` callers pass `SKU_STOCKED`; `CartonStationSupport.giveCartonsASize` / `giveHeavyCartons(String sku, BigDecimal kg)` (renamed from `giveSofaCartonsAWeight`) set the replacement SKUs' carton unit to 215×95×90 cm and the weight the truck maths used. `ReceiptCartons.DEMO_UNIT` and `demo(units)` (no callers) are deleted; `ConfirmedDesigns.stockBlanks` (no callers) is deleted.

- [ ] **Step 1:** Make the changes above; rename the "sofa" wording in javadoc and comments.
- [ ] **Step 2:** Run `mvn test -Dtest='PermissionMatrixTest,DemoDataIntegrationTest'` → PASS.
- [ ] **Step 3:** Commit (owner's go-ahead) — `test: support fixtures on the cup demo data`

### Task 3: Integration tests that needed the furniture's stock or carton

Groups from the builder check (paths under `src/test/java/com/stockflow/`):
- stock in bins: `inventory/LooseStockToCartonsIntegrationTest`, `inventory/InventoryModuleTest`, `fulfillment/PickConfirmationIntegrationTest`, `fulfillment/StagingIntegrationTest`, `fulfillment/CartonCheckStationIntegrationTest`, `catalog/CatalogSearchIntegrationTest`
- handling template / truck maths: `fulfillment/ControlledUnloadIntegrationTest`, `CountVarianceOfShortCartonIntegrationTest`, `DispatchHandoverIntegrationTest`, `FtlDispatchIntegrationTest`, `FulfillmentTaskContextIntegrationTest`, `LoadPlanIntegrationTest`, `order/OrderCancellationIntegrationTest`

**Interfaces:** Consumes Task 1 and Task 2.

- [ ] **Step 1:** Replace SKU strings with `DemoData.SKU_STOCKED` / `SKU_QUARANTINED`; multiply unit quantities by the SKU's factor (1,000 / 500) where the old test meant "one sofa"; keep carton counts. Kit assertions (`package_count 2`) are deleted, not moved: kits are gone in part 2 and nothing in part 1 needs them.
- [ ] **Step 2:** Run `mvn test -Dtest='LooseStockToCartonsIntegrationTest,InventoryModuleTest,PickConfirmationIntegrationTest,StagingIntegrationTest,CartonCheckStationIntegrationTest,CatalogSearchIntegrationTest,ControlledUnloadIntegrationTest,CountVarianceOfShortCartonIntegrationTest,DispatchHandoverIntegrationTest,FtlDispatchIntegrationTest,FulfillmentTaskContextIntegrationTest,LoadPlanIntegrationTest,OrderCancellationIntegrationTest'` → PASS.
- [ ] **Step 3:** Commit (owner's go-ahead) — `test: stock and truckload tests on the cup demo stock`

### Task 4: Product, catalogue, design and "any SKU" tests

Groups: design template (`order/AwaitingStockIntegrationTest`, `order/CustomerOrderPortalIntegrationTest`, `order/OrderReleaseIntegrationTest`, `chat/ChatCardsIntegrationTest`, `fulfillment/TaskCandidatesIntegrationTest`); product structure (`product/internal/repository/ProductAttributeSetRepositoryAdapterIntegrationTest`, `product/internal/service/ProductAttributeIntegrationTest`, `product/ProductAttributeSecurityIntegrationTest`, `product/ProductModuleTest`, `product/ProductHistoryAndRemovalIntegrationTest`, `CatalogProjectionStartupIntegrationTest`, `product/internal/repository/AttributeRepositoryAdapterIntegrationTest`, `product/internal/service/AttributeDictionaryIntegrationTest`); any existing SKU (the 16 group-c files: `B2bAccessIntegrationTest`, `B2bOnboardingIntegrationTest`, `InventoryCatalogSecurityIntegrationTest`, `catalog/CustomerPricingIntegrationTest`, `customer/CustomerSegmentIntegrationTest`, `order/QuoteIntegrationTest`, `order/QuoteSecurityIntegrationTest`, `order/ReturnRequestHttpTest`, `order/OrderWarehouseVisibilityHttpTest`, `procurement/ReturnReceiptIntegrationTest`, `inventory/SaveMergedErrorIntegrationTest`, `inventory/SkuReservationAllocationIntegrationTest`, `inventory/StaffAdjustmentAccessIntegrationTest`, `inventory/StockMovementShapeSchemaTest`, `inventory/StockPackageSchemaMigrationTest`, `product/InventoryItemMasterDataIntegrationTest`); the 19 unit tests where a SKU is only a string (change only where the string reads as furniture).

- [ ] **Step 1:** Design-template tests use `PRODUCT_CUP` / `BLANK_CUP` (template STD, print area BODY). Attribute tests use `HOP-GIAY-DEMO` (COLOR GREY/BLUE in use, MATERIAL PAPER); `CatalogProjectionStartupIntegrationTest` expects 2 products and the slugs of `LY-GIAY-DEMO` and `HOP-GIAY-DEMO`. `StockPackageSchemaMigrationTest` uses the two cup SKUs' items. `ReturnReceiptIntegrationTest` counts in cartons of 1,000.
- [ ] **Step 2:** Run the listed classes with `mvn test -Dtest='<comma list>'` → PASS.
- [ ] **Step 3:** Commit (owner's go-ahead) — `test: product, catalogue and design tests on the cup and box demo products`

### Task 5: QA SQL scripts

**Files:** the 31 files under `tools/db/qa/` naming the furniture: exact seed rows (`03`, `04`, `10`, `19`, `40`, `81`, `99`), a seeded 1-unit carton (`54`, `63`, `64`, `65`, `80`), product structure (`01`, `11`, `28`, `97`), an item to hang fixtures on (`06`, `12`, `13`, `15_sku_reservation_and_lots`, `15_stock_packages`, `41`, `42`, `50`, `51`, `52`, `53`, `61`, `62`, `70`, `90_delivery_failure_cases`).

- [ ] **Step 1:** Point each at the replacement SKUs, items, LPNs and lots (same LPN numbers and lots, so `81`, `99` mostly change SKU strings); a check that inserted a 1-unit carton inserts one carton of the SKU's factor. `50` keeps its rotatable contrast with `SKU_QUARANTINED` (`rotatable = false`).
- [ ] **Step 2:** Run `mvn test -Dtest=RepositorySqlQaTest` → PASS (every script).
- [ ] **Step 3:** Run the whole suite in the background: `mvn test` → only the known flaky `SalesRollupIntegrationTest.replayedEventsAreIgnored` may fail; rerun it alone to confirm.
- [ ] **Step 4:** Commit (owner's go-ahead) — `test(qa): adversarial SQL on the cup demo data`
