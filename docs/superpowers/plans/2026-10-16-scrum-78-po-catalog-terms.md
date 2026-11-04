# SCRUM-78 PO lines follow the supplier catalog — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A STANDARD purchase order line is accepted only on the supplier's catalog terms (listed SKU, MOQ, pack size), takes its price and lead time from the catalog when the buyer leaves them out, and every SKU gets an order-up-to level `maxQty`.

**Architecture:** `ProcurementServiceImpl.checkLines` is the single choke point for STANDARD line writes (create and draft edit); it reads the supplier's catalog rows for the request's items in one `FOR SHARE` query through the PR1 port and collects every line's violation into one refusal. `maxQty` is a new component of `InventoryPolicy` (`inventory :: api`), validated in `StockPolicy` and surfaced by the product module's inventory-control endpoint.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Modulith 1.4, Spring Data JPA / JdbcTemplate, PostgreSQL 16, JUnit 5 + Testcontainers, MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-16-scrum-69-procurement-data-design.md` §4 and the PR2 list in §5. PR1 (branch `feature/SCRUM-77-supplier-catalog`, PR #117) built the catalog this plan consumes.

## Global Constraints

- Branch `feature/SCRUM-78-po-catalog-terms`, cut from `feature/SCRUM-77-supplier-catalog`; the PR targets that branch (stacked, merge commit, retargeted to `develop` by hand after #117 merges). Commit or push only with the owner's explicit yes.
- No new files in `src/main/resources/db/migration`. Demo data: **no new demo script** — the demo has two inventory items (SOFA-3S-GREY, TABLE-OAK-160), both already listed for GOVIET in `V20260928009000`; INNHANH is the print subcontractor, whose POs are exempt.
- All identifiers, code and javadoc in English; `_vi` bundle in Vietnamese with diacritics. Javadoc explains why; when behaviour changes, change the javadoc that justified the old one (the `createPurchaseOrder` javadoc says MOQ "is not enforced").
- Every new error code gets `error.<CODE>` in `messages.properties` and `messages_vi.properties` (`MessageBundleTest`). A `FieldError` always carries its own rule's code, never `VALIDATION_FAILED` (PR1 lesson: `GlobalExceptionHandler` translates the field message by the field code).
- Field paths use the request's property names: `lines[i].sku`, `lines[i].quantityOrdered`, `lines[i].unitPrice` (the spec's `lines[i].quantity` is a slip; the request field is `quantityOrdered`).
- Existing POs are never re-validated. A catalog edit never touches a written PO. SUBCONTRACT POs never reach `checkLines`.
- No cross-schema JOIN. `procurement` reads inventory only through `inventory :: api`.
- Per task: focused tests, `ModularityTest`, `ArchitectureTest`, `python -X utf8 tools/verify.py`. One full `mvn verify` in the last task.

## Review Focus

1. **A SKU listed only for another supplier** must be `SUPPLIER_ITEM_NOT_LISTED` for this PO's supplier, never accepted because "some supplier sells it". → Task 3, `aSkuListedOnlyForAnotherSupplierIsNotListed`.
2. **An unlisted line reports one error, not three:** no MOQ, pack or price errors for a line whose SKU is not listed — there are no terms to check against. → Task 3, `anUnlistedLineReportsOnlyNotListed`.
3. **No lead time anywhere** (item null, supplier null): the PO lead time falls back to `CommercialTerms.LEAD_DAYS`, and `expectedAt` defaults from it. → Task 3, `noLeadTimeAnywhereFallsBackToTheDefault`.
4. **`unitPrice` omitted in the JSON body** of POST and PUT must reach the service (bean validation no longer rejects it) and be prefilled. → Task 3, `aLineWithoutUnitPriceIsAcceptedOverHttp`.
5. **A draft edit re-checks every line, unchanged ones included,** against the catalog as it is now (the draft has not been sent; it must meet current terms). → Task 3, `draftEditRechecksUnchangedLinesAgainstTheCurrentCatalog`.

---

### Task 1: Order-up-to level `maxQty`

**Files:**
- Modify: `src/main/java/com/stockflow/inventory/api/InventoryPolicy.java`
- Modify: `src/main/java/com/stockflow/inventory/internal/domain/StockPolicy.java`
- Modify: `src/main/java/com/stockflow/inventory/internal/repository/InventoryPolicyRepository.java` (read and write `max_qty`)
- Modify: every `new InventoryPolicy(` call site — `inventory/internal/service/InventoryControlServiceImpl.java`, `product/internal/controller/InventoryControlWebMapper.java`, and the tests `inventory/internal/domain/StockPolicyTest.java`, `inventory/internal/service/StockAlertIntegrationTest.java`, `InventoryCatalogIntegrationTest.java`, `product/ProductHistoryAndRemovalIntegrationTest.java`
- Modify: `src/main/java/com/stockflow/product/internal/controller/dto/UpdateInventoryControlRequest.java`, `dto/InventoryControlResponse.java`
- Modify: `common/error/ErrorCode.java`, both i18n bundles
- Test: `StockPolicyTest`, `InventoryCatalogIntegrationTest`

**Interfaces:**
- Produces: `record InventoryPolicy(Integer reorderPoint, Integer safetyStock, Integer maxQty, RemovalStrategy removalStrategy, TrackingMode trackingMode, boolean expiryTracked, Integer maxShelfLifeDays)` — `maxQty` inserted after `safetyStock`. Javadoc: `maxQty` is the order-up-to level a replenishment brings the SKU back to; `inventory_items.min_qty` is deliberately not mapped — superseded by `safetyStock` / `reorderPoint`.
- Produces: field code `INVENTORY_MAX_QTY_BELOW_THRESHOLD("The order-up-to level must not be below the reorder point or the safety stock", 400)`.

Rules (in `StockPolicy.validate`, which already guards every policy write):
- `maxQty` null, or `≥ 0`; when `reorderPoint` is set, `maxQty ≥ reorderPoint`; when `safetyStock` is set, `maxQty ≥ safetyStock` (an order-up-to level below the safety stock refills to a shortage).
- A `maxQty` violation throws `BusinessException(INVENTORY_POLICY_INVALID, msg, List.of(new FieldError("maxQty", msg, "INVENTORY_MAX_QTY_BELOW_THRESHOLD")))` — the endpoint's existing envelope code (400), with the field named. A negative `maxQty` is caught by `@Min(0)` on the request first.

- [ ] **Step 1: Failing tests**

```java
// StockPolicyTest
@Test void maxQtyMayNotSitBelowTheReorderPointOrTheSafetyStock() {
    // (reorder 10, safety 5, max 9)  → BusinessException INVENTORY_POLICY_INVALID, fieldErrors [maxQty / INVENTORY_MAX_QTY_BELOW_THRESHOLD]
    // (reorder null, safety 5, max 4) → same
    // (reorder 10, safety 5, max 10), (all null), (reorder null, safety null, max 0) → valid
}
// InventoryCatalogIntegrationTest (the inventory-control endpoint)
@Test void maxQtyRoundTripsThroughInventoryControl()          // PUT maxQty 40 → GET returns 40; max_qty column is 40
@Test void maxQtyBelowTheReorderPointIs400OnTheField()         // reorderPoint 10, maxQty 5 → 400, fieldErrors[0].field "maxQty", code INVENTORY_MAX_QTY_BELOW_THRESHOLD
```

- [ ] **Step 2: Run** `mvn -q test -Dtest=StockPolicyTest,InventoryCatalogIntegrationTest` — expected: compilation failure.
- [ ] **Step 3: Implement** the record change, validation, repository mapping (`max_qty` in the SELECT mapping and the UPDATE), request (`@Min(0) Integer maxQty`) / response / web mapper, error code and bundle lines; fix every other call site with `null` for `maxQty` where the test does not care.
- [ ] **Step 4: Run** `mvn -q test -Dtest=StockPolicyTest,InventoryCatalogIntegrationTest,StockAlertIntegrationTest,ProductHistoryAndRemovalIntegrationTest,InventoryCatalogSecurityIntegrationTest,MessageBundleTest,ModularityTest,ArchitectureTest` and `python -X utf8 tools/verify.py` — expected: PASS.
- [ ] **Step 5: Commit** (after the owner's yes): `feat(inventory): order-up-to level maxQty on the inventory policy (SCRUM-78)`.

---

### Task 2: Catalog lookup for PO lines, one price rule

**Files:**
- Modify: `src/main/java/com/stockflow/procurement/internal/domain/SupplierItemRepository.java`
- Modify: `src/main/java/com/stockflow/procurement/internal/repository/SupplierItemRepositoryAdapter.java`
- Create: `src/main/java/com/stockflow/procurement/internal/domain/PurchasePrices.java`
- Modify: `src/main/java/com/stockflow/procurement/internal/service/SupplierItemServiceImpl.java` (use `PurchasePrices`), `internal/controller/dto/ProcurementFieldsValidator.java` (use `PurchasePrices.MAX`)
- Modify: `common/error/ErrorCode.java`, both bundles
- Test: `SupplierItemRepositoryAdapterIntegrationTest`, `src/test/java/com/stockflow/procurement/internal/domain/PurchasePricesTest.java`

**Interfaces:**
- Produces on the port: `Map<UUID, SupplierItem> listedForShare(UUID supplierId, Collection<UUID> inventoryItemIds)` — one native query over `procurement.supplier_items` `WHERE supplier_id = ? AND inventory_item_id = ANY(?) FOR SHARE`, keyed by inventory item id, built through the PR1 shared row mapper (`SupplierItemTerms.stored`). Empty collection → empty map without a query. `FOR SHARE` makes a concurrent catalog delete wait for the PO transaction, so it cannot strand a draft line.
- Produces: `final class PurchasePrices` (pure, no Spring):
  - `static final BigDecimal MAX = new BigDecimal("9999999999999999.99")`
  - `static boolean acceptable(BigDecimal amount, Currency currency)` — non-null, `> 0`, `≤ MAX`, `stripTrailingZeros().scale() ≤ Math.min(2, currency.getDefaultFractionDigits())`.
- Produces error codes (409 group, each with both bundle lines):
  - `SUPPLIER_TERMS_VIOLATED("One or more lines do not meet the supplier's catalog terms", 409)` — envelope
  - `SUPPLIER_ITEM_NOT_LISTED("This supplier does not list this SKU", 409)` — field
  - `PO_LINE_BELOW_MOQ("The quantity is below the supplier's minimum order quantity", 409)` — field
  - `PO_LINE_NOT_PACK_MULTIPLE("The quantity must be a multiple of the supplier's pack size", 409)` — field
  - `PO_LINE_PRICE_REQUIRED("A unit price is required: the catalog price is in another currency", 409)` — field

- [ ] **Step 1: Failing tests**

```java
// SupplierItemRepositoryAdapterIntegrationTest
@Test void listedForShareReturnsOnlyThisSuppliersRowsKeyedByItem()  // supplier A lists items 1,2; B lists 3 → listedForShare(A, [1,2,3]) has keys {1,2}
@Test void listedForShareOfNoItemsIsEmpty()
// PurchasePricesTest
@Test void acceptable() {
    // VND: 1500 ok, 1500.50 no, 0 no, -1 no, null no, 10^16 no
    // USD: 1.23 ok, 1.234 no;  KWD (3 digits): 1.23 ok, 1.234 no (2-decimal column cap)
}
```

- [ ] **Step 2: Run** `mvn -q test -Dtest=SupplierItemRepositoryAdapterIntegrationTest,PurchasePricesTest` — expected: compilation failure.
- [ ] **Step 3: Implement** the port method and adapter query, `PurchasePrices`, the five codes; replace the raw-price checks in `SupplierItemServiceImpl` with `PurchasePrices.acceptable` (keep its `SUPPLIER_ITEM_PRICE_INVALID` field code) and the literal in `ProcurementFieldsValidator` with `PurchasePrices.MAX`. `checkLines` is converted in Task 3.
- [ ] **Step 4: Run** `mvn -q test -Dtest='SupplierItem*Test,SupplierItem*IntegrationTest,PurchasePricesTest,MessageBundleTest,ModularityTest,ArchitectureTest'` and `python -X utf8 tools/verify.py` — expected: PASS.
- [ ] **Step 5: Commit** (after the owner's yes): `feat(procurement): catalog lookup for purchase order lines and one purchase price rule (SCRUM-78)`.

---

### Task 3: `checkLines` enforces the catalog; price and lead-time prefill

**Files:**
- Modify: `src/main/java/com/stockflow/procurement/internal/service/ProcurementServiceImpl.java` (`createPurchaseOrder`, `reviseDraft`, `toLines`, `checkLines`, javadoc; new constructor dependency `SupplierItemRepository`)
- Modify: `src/main/java/com/stockflow/procurement/internal/domain/PurchaseOrder.java` (`leadTimeDays` no longer final; `reviseDraft(..., int leadTimeDays)`)
- Modify: `src/main/java/com/stockflow/procurement/internal/controller/dto/CreatePOLineRequest.java`, `UpdatePOLineRequest.java` (`unitPrice` without `@NotNull`; schema says "omit to take the catalog price")
- Modify: `docs/business-design/05-business-rules.md` (BR-PO-001 enforcement column)
- Create: `src/test/java/com/stockflow/support/SupplierItemFixtures.java`
- Create: `src/test/java/com/stockflow/procurement/PurchaseOrderCatalogTermsIntegrationTest.java`
- Modify: `procurement/internal/service/ProcurementPurchasabilityTest.java`, `ProcurementBusinessDateTest.java`, `procurement/internal/domain/PurchaseOrderTest.java` (constructor / signature changes only)

**Interfaces:**
- Consumes: Task 2 `listedForShare`, `PurchasePrices`, the five codes; PR1 `SupplierItem.orderQuantityViolations(int)`, `SupplierItem.effectiveLeadTimeDays(Integer)`, `SupplierItem.terms().price()`.
- Produces: `SupplierItemFixtures.list(SupplierItemService catalog, UUID supplierId, String sku)` → `SupplierItemSummary` (price 100000, moq 1, packSize 1, leadTimeDays null, not preferred) and an overload `list(catalog, supplierId, sku, BigDecimal price, int moq, int packSize, Integer leadTimeDays)`. It goes through the real `SupplierItemService` so a seeded row obeys the catalog rules.

`checkLines(List<LineInput>, Currency, UUID supplierId, Integer supplierLeadTimeDays)` returns `CheckedLines(List<CheckedLine> lines, int leadTimeDays)`; `LineInput.unitPrice` may be null. Order (spec §4.2):
1. Per line, unchanged: inventory item, duplicate SKU, description.
2. `listedForShare(supplierId, all item ids)` — one query.
3. Per line `i`, collecting into one list:
   - not listed → `lines[i].sku` / `SUPPLIER_ITEM_NOT_LISTED`, and nothing else for that line;
   - each `orderQuantityViolations` entry → `lines[i].quantityOrdered` / `PO_LINE_BELOW_MOQ` or `PO_LINE_NOT_PACK_MULTIPLE`;
   - price: typed wins; null and `item.terms().price().currency().equals(currency)` → the catalog amount; null otherwise → `lines[i].unitPrice` / `PO_LINE_PRICE_REQUIRED`.
   Any error → `BusinessException(SUPPLIER_TERMS_VIOLATED, "<n> line(s) do not meet the supplier's catalog terms", errors)`.
4. Amount checks on the resolved prices (quantity range, `PurchasePrices.acceptable`, tax rate, total ≤ `PurchasePrices.MAX`) — the existing `VALIDATION_FAILED` behaviour, moved after step 3.
5. `requirePurchasable` — unchanged.
6. `leadTimeDays` = max over lines of `item.effectiveLeadTimeDays(supplierLeadTimeDays)`.

Callers:
- `createPurchaseOrder`: `expectedAt` default = business today + `CheckedLines.leadTimeDays`; `PurchaseOrder.draft(..., leadTimeDays)` gets it instead of the supplier's.
- `reviseDraft`: loads the order's supplier for its lead time (`suppliers.findById(order.supplierId())`) and passes the recomputed `leadTimeDays` to `order.reviseDraft`. `expectedAt` stays the request's — the edit request requires it (`UpdatePurchaseOrderRequest.expectedAt @NotNull`).
- The `createPurchaseOrder` javadoc drops "BR-PO-001 (MOQ) is not enforced" and states the catalog rule; `05-business-rules.md` BR-PO-001 enforcement → `SupplierItem.orderQuantityViolations` via `ProcurementServiceImpl.checkLines`.

- [ ] **Step 1: Failing tests** in `PurchaseOrderCatalogTermsIntegrationTest` (`@IntegrationTest`, real Postgres; supplier, warehouse and current user set up as in `PurchaseOrderDraftEditIntegrationTest`; catalog through `SupplierItemFixtures`):

```java
@Test void anUnlistedSkuIsRefused()                         // 409 SUPPLIER_TERMS_VIOLATED, fieldErrors [lines[0].sku / SUPPLIER_ITEM_NOT_LISTED]
@Test void aSkuListedOnlyForAnotherSupplierIsNotListed()
@Test void belowMoqIsRefused()                              // moq 10 pack 5, qty 5 → lines[0].quantityOrdered / PO_LINE_BELOW_MOQ
@Test void notAPackMultipleIsRefused()                      // moq 10 pack 5, qty 12 → PO_LINE_NOT_PACK_MULTIPLE
@Test void severalBadLinesGiveOneRefusalWithOneErrorPerField() // line0 unlisted, line1 qty 7 on moq 10/pack 5 → [lines[0].sku, lines[1].quantityOrdered ×2]
@Test void anUnlistedLineReportsOnlyNotListed()             // unlisted + null price → exactly one error
@Test void aNullPriceIsPrefilledAndATypedPriceKept()        // catalog 120000; line A null → 120000; line B 99000 → 99000
@Test void anotherCurrencyNeedsATypedPrice()                // VND catalog, USD PO: null → lines[0].unitPrice / PO_LINE_PRICE_REQUIRED; 12.50 typed → accepted
@Test void leadTimeIsTheLargestLineLeadTime()               // item lead 3, item lead null with supplier 9 → PO leadTimeDays 9
@Test void noExpectedAtDefaultsToTodayPlusThatLeadTime()     // expectedAt == BusinessCalendar.date(now) + 9
@Test void noLeadTimeAnywhereFallsBackToTheDefault()        // supplier lead null, item null → CommercialTerms.LEAD_DAYS
@Test void aSubcontractOrderIsUntouched()                   // SubcontractPurchasing for a print subcontractor and a SKU nobody lists → created (setup as in SubcontractPurchasingIntegrationTest)
@Test void draftEditToBelowMoqIsRefused()
@Test void draftEditRechecksUnchangedLinesAgainstTheCurrentCatalog() // raise MOQ after creating the draft; edit only the note → PO_LINE_BELOW_MOQ on the untouched line
@Test void draftEditRecomputesTheLeadTime()                 // add a line with lead 20 → PO leadTimeDays 20
@Test void aLineWithoutUnitPriceIsAcceptedOverHttp()        // POST /api/v1/purchase-orders with no unitPrice in JSON → 201, line price = catalog price; same for PUT draft edit
```

- [ ] **Step 2: Run** `mvn -q test -Dtest=PurchaseOrderCatalogTermsIntegrationTest` — expected: FAIL (unlisted SKUs accepted, null price refused).
- [ ] **Step 3: Implement** as above. Update the three mock/unit tests for the new constructor and `reviseDraft` signature (mock `SupplierItemRepository.listedForShare` to list every requested item with moq 1, pack 1).
- [ ] **Step 4: Run** `mvn -q test -Dtest=PurchaseOrderCatalogTermsIntegrationTest,ProcurementPurchasabilityTest,ProcurementBusinessDateTest,PurchaseOrderTest,ModularityTest,ArchitectureTest` and `python -X utf8 tools/verify.py` — expected: PASS. Other PO integration tests fail until Task 4; do not touch them here.
- [ ] **Step 5: Commit** (after the owner's yes): `feat(procurement): purchase order lines follow the supplier catalog (SCRUM-78, BR-PO-001)`.

---

### Task 4: Existing tests seed the catalog; full build

**Files:**
- Modify: every test that raises a STANDARD PO through `ProcurementService` or `/api/v1/purchase-orders` — at least `notification/internal/service/SupplierProcurementIntegrationTest.java`, `procurement/PurchaseOrderDraftEditIntegrationTest.java`, `procurement/PurchaseOrderWarehouseScopeIntegrationTest.java`, `procurement/SupplierSpendByCurrencyIntegrationTest.java`, `procurement/ProductEndOfLifePurchasingIntegrationTest.java`, `product/ProductLifecycleIntegrationTest.java`, `common/idempotency/IdempotencyFilterTest.java`, the GoodsReceipt tests if they raise POs through the service, and any other the build turns up.
- Modify: `docs/superpowers/specs/2026-10-16-scrum-69-procurement-data-design.md` §4.6 (no demo script — both demo SKUs are already listed) and §4.2 (field path `lines[i].quantityOrdered`); §4.5 (envelope `INVENTORY_POLICY_INVALID` with field code `INVENTORY_MAX_QTY_BELOW_THRESHOLD`, and the safety-stock bound).

**Interfaces:**
- Consumes: `SupplierItemFixtures.list(...)` (Task 3).

Seeding rule: list each SKU a test orders for the test's supplier, with `moq 1`, `packSize 1` unless the test is about quantities, before the PO is raised. Where a test sends no price today it keeps sending one — prefill is covered by Task 3. Do not weaken an assertion to make a test pass; a test whose meaning changes because of the catalog rule is reported.

- [ ] **Step 1: Run** `mvn -q test -Dtest='*PurchaseOrder*,*Procurement*,*Supplier*,ProductLifecycleIntegrationTest,ProductEndOfLifePurchasingIntegrationTest,IdempotencyFilterTest,GoodsReceipt*'` — expected: the failures Task 3 caused (`SUPPLIER_TERMS_VIOLATED`). Record the list.
- [ ] **Step 2: Seed** the catalog in each failing test through `SupplierItemFixtures`.
- [ ] **Step 3: Run** the same command — expected: PASS.
- [ ] **Step 4: Update** the spec paragraphs listed above.
- [ ] **Step 5: Full build** `mvn verify` in the background, log to a file — expected: BUILD SUCCESS. Report any failure with its output; never disable a test.
- [ ] **Step 6: Commit** (after the owner's yes): `test(procurement): seed the supplier catalog before raising purchase orders (SCRUM-78)`.
