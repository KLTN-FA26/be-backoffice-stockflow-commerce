# Inventory Item API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the inventory item's logistics and stock-policy API out of the product module into
`/api/v1/inventory/items`, keyed by the item's own UUID, and delete the product routes.

**Architecture:** A new internal `InventoryItems` service in the inventory module owns reads, search
and the two writes; a new `InventoryItemController` exposes it under the seeded
`inventory-inventory-items` permission. Product loses `SkuInventoryControlService` and its two
controllers, and gains only `inventoryItemId` on `VariantResponse` via one
`InventoryService.itemIdsOf` call. The write methods then leave `InventoryControlService` (api).

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Modulith 1.4, JdbcTemplate, JUnit 5, MockMvc,
Testcontainers (Postgres).

**Spec:** `docs/superpowers/specs/2026-10-16-inventory-item-api-design.md`

## Global Constraints

- Branch `fix/inventory-item-api`, stacked on `feature/SCRUM-78-po-catalog-terms`.
- No file is added to `src/main/resources/db/migration`; no migration of any kind.
- Permission resource code `inventory-inventory-items`, actions declared exactly as seeded:
  `VIEW_PAGE, READ, CREATE, UPDATE, EXPORT`. Reads use `READ`, both PUTs use `UPDATE`.
- Audit: `@Auditable(action = AuditAction.UPDATE, resourceType = "inventory-item", resourceId = "#id")`
  — the item id, never a variant id.
- Nothing writes `ProductChangeType.LOGISTICS` / `INVENTORY_CONTROL` any more; both enum values stay.
- Error codes on the new endpoints: unknown id `INVENTORY_ITEM_NOT_FOUND`; stale version
  `OPTIMISTIC_LOCK` (after Task 5); unit-of-measure or stock-incompatible policy
  `INVENTORY_POLICY_STOCK_CONFLICT`; duplicate barcode `CONFLICT`; active count `COUNT_POLICY_CONFLICT`.
- Every handler and every proxied method is `public` (`ArchitectureTest.proxiedMethodsArePublic`).
- English identifiers and javadoc; javadoc explains why.
- Run `python3 tools/verify.py` and `mvn test -Dtest=ModularityTest+ArchitectureTest` after any change
  to packages or `api`. One full `mvn verify` at the end of the branch (Task 6), not per task.
- **Never `git commit` without the repository owner's go-ahead.** Each task's commit step means: show
  the staged diff summary and ask, unless the owner has given a standing go-ahead for this plan.
  Commit messages follow `.claude/skills/git-commits` (no tool attribution, no trailer).

## Review Focus

- Two edits read the same `version`, PUT logistics then PUT policy with that stale version → the second
  is 409 `OPTIMISTIC_LOCK`, because both groups share one item version (test in Task 5).
- `q` containing `%` or `_` matches those characters literally, not as LIKE wildcards (test in Task 2).
- A DRAFT variant whose SKU is renamed keeps the same `inventoryItemId` (test in Task 3).
- A non-UUID `{id}` in the path is 400, not 500 (test in Task 2).
- `itemIdsOf` with an empty collection returns an empty map without SQL — `IN ()` is a syntax error,
  and a product with no variants lists an empty page (test in Task 3).

---

### Task 1: `InventoryItems` service — read and write an item by id

**Files:**
- Create: `src/main/java/com/stockflow/inventory/internal/service/InventoryItems.java`
- Create: `src/main/java/com/stockflow/inventory/internal/service/InventoryItemsImpl.java`
- Modify: `src/main/java/com/stockflow/inventory/internal/repository/InventoryPolicyRepository.java` (add `findSku`)
- Test: `src/test/java/com/stockflow/inventory/internal/service/InventoryItemsIntegrationTest.java`

**Interfaces:**
- Consumes: `InventoryControlService.configure/describe/evaluate/item/logistics` (by `Sku`), unchanged for now.
- Produces (public interface, later tasks rely on these exact names):

```java
public interface InventoryItems {
    record Detail(UUID id, String sku, long version, ItemLogistics logistics,
                  InventoryPolicy policy, StockThresholdEvaluation evaluation) {}
    Detail get(UUID id);                                                   // INVENTORY_ITEM_NOT_FOUND
    Detail describe(UUID id, long expectedVersion, ItemLogistics logistics);
    Detail configure(UUID id, long expectedVersion, InventoryPolicy policy);
}
```
- `InventoryPolicyRepository.findSku(UUID id): Optional<String>`.

- [ ] **Step 1: Write the failing tests** (`@IntegrationTest @Import(PostgresContainer.class)`; create a
  product and variant through `ProductFixtures.draft` + `ProductVariantService`, read the item id with
  `select id from inventory.inventory_items where sku = ?`)

```java
@Test void getReturnsTheThreeGroups()        // d.sku()==variant sku; d.logistics().unitOfMeasure()=="EACH";
                                             // d.policy().removalStrategy()==FEFO; d.evaluation().usableOnHand()==0
@Test void unknownIdIsInventoryItemNotFound() // get(randomUUID) → BusinessException code INVENTORY_ITEM_NOT_FOUND
@Test void describeBumpsTheVersion()          // describe(id, v, weightKg 0.120) → version v+1, weightKg 0.120
@Test void configureSavesThePolicy()          // configure(id, v, reorderPoint 500) → policy().reorderPoint()==500
@Test void writesAuditTheItemIdNotTheVariant() // after describe: platform.audit_log has a row with
                                               // resource_type='inventory-item' and resource_id=id.toString()
                                               // (use support/Await — the audit row is written after commit)
@Test void writesNoProductHistory()           // count(product.product_change_log where change_type in
                                              // ('LOGISTICS','INVENTORY_CONTROL') and product_id=?) == 0
@Test void editsAreNotRefusedForAProductUnderReview() // submit the product (PENDING_APPROVAL), describe succeeds
@Test void unitChangeWhileStockIsHeldIs409()  // receive stock for the SKU; describe with unitOfMeasure "BOX"
                                              // → INVENTORY_POLICY_STOCK_CONFLICT
@Test void duplicateBarcodeIs409()            // two items; describe the second with the first's barcode → CONFLICT
```

  The policy-against-stock and active-count refusals are already pinned by
  `InventoryCatalogIntegrationTest` (`serialFlagIsEnforcedAtStockIngressAndCannotBeDisabledWithStock` and the
  count tests); Task 4 ports them onto `InventoryItems`, so they are not duplicated here.

- [ ] **Step 2: Run** `mvn test -Dtest=InventoryItemsIntegrationTest` — Expected: compilation failure, `InventoryItems` not found.

- [ ] **Step 3: Implement.** `InventoryItemsImpl` is `@Service @Transactional`, package-private class,
  public methods. Each method resolves `findSku(id)` (absent → `BusinessException(INVENTORY_ITEM_NOT_FOUND)`),
  then delegates to `InventoryControlService` with `new Sku(sku)` and assembles `Detail` from
  `item()`, `logistics()` and `evaluate()`. `describe` and `configure` carry the `@Auditable` from
  Global Constraints; `get` is `@Transactional(readOnly = true)`.

- [ ] **Step 4: Run** `mvn test -Dtest=InventoryItemsIntegrationTest` — Expected: 9 tests PASS.

- [ ] **Step 5: Commit** (after go-ahead) — `feat(inventory): read and edit an inventory item by its own id`

---

### Task 2: HTTP API — list, detail, two PUTs

**Files:**
- Create: `inventory/internal/repository/InventoryItemSearch.java` (interface) and `InventoryItemSearchAdapter.java` (JdbcTemplate)
- Create: `inventory/internal/controller/InventoryItemController.java`, `InventoryItemWebMapper.java`
- Create: `inventory/internal/controller/dto/InventoryItemResponse.java`, `InventoryItemSummaryResponse.java`,
  `UpdateItemLogisticsRequest.java`, `UpdateItemPolicyRequest.java`
- Modify: `InventoryItems.java` / `InventoryItemsImpl.java` (add `search`), `InventoryResources.java` (add constant)
- Test: `src/test/java/com/stockflow/inventory/InventoryItemHttpIntegrationTest.java`,
  `src/test/java/com/stockflow/inventory/InventoryItemSecurityIntegrationTest.java`

(All main paths under `src/main/java/com/stockflow/`.)

**Interfaces:**
- Consumes: `InventoryItems.get/describe/configure` (Task 1).
- Produces:

```java
// InventoryItems
record Summary(UUID id, String sku, String unitOfMeasure, String barcode, StorageClass storageClass,
               TrackingMode trackingMode, boolean policyConfigured) {}
record ItemQuery(String sku, String q, StorageClass storageClass, Boolean policyConfigured, int page, int size) {}
PageResponse<Summary> search(ItemQuery query);

// InventoryItemSearch (repository; rows and criteria are its own types, as in StockBrowseSearch)
record Row(UUID id, String sku, String unitOfMeasure, String barcode, StorageClass storageClass,
           boolean lotTracked, boolean serialTracked, boolean policyConfigured) {}
record Criteria(String sku, String q, StorageClass storageClass, Boolean policyConfigured) {}
Page<Row> items(Criteria criteria, Pageable pageable);

// InventoryResources
public static final String INVENTORY_ITEMS = "inventory-inventory-items";
```

- DTOs: `InventoryItemResponse(UUID id, String sku, long version, Logistics logistics, Policy policy,
  Evaluation evaluation)` with nested records mirroring `ItemLogistics`, `InventoryPolicy`
  (enums as `String`), `StockThresholdEvaluation`. `UpdateItemLogisticsRequest` copies every field and
  validation annotation of `product/internal/controller/dto/SkuLogisticsRequest`;
  `UpdateItemPolicyRequest` copies `UpdateInventoryControlRequest`. Mapper copies
  `ProductAdminWebMapper.toLogistics` (null `packageCount`/`packSize` → 1) and
  `InventoryControlWebMapper.toPolicy`.

- [ ] **Step 1: Write the failing HTTP tests** (`@IntegrationTest @Import(PostgresContainer.class) @AutoConfigureMockMvc`, security off)

```java
@Test void detailHasLogisticsPolicyAndEvaluation()   // GET /items/{id} → $.data.id, $.data.logistics.unitOfMeasure "EACH",
                                                      // $.data.policy.removalStrategy "FEFO", $.data.evaluation.usableOnHand 0
@Test void putLogisticsReturnsTheWholeItem()         // PUT /items/{id}/logistics {version, ..., weightKg 0.120}
                                                      // → 200, $.data.version == v+1, $.data.policy present
@Test void putPolicyReturnsTheWholeItem()            // PUT /items/{id}/policy {version, reorderPoint 500, ...} → $.data.policy.reorderPoint 500
@Test void negativeReorderPointIs400()               // reorderPoint -1 → 400
@Test void unknownIdIs404()                           // GET /items/{random} → 404, $.code "INVENTORY_ITEM_NOT_FOUND"
@Test void nonUuidIdIs400()                           // GET /items/not-a-uuid → 400
@Test void listFiltersBySkuExactly()                  // ?sku=<sku> → totalElements 1, content[0].id == id
@Test void listFiltersByQOnSkuOrBarcode()             // ?q=<middle of sku> and ?q=<middle of barcode> both find it, case-insensitive
@Test void qWildcardsMatchLiterally()                 // items "W%X-…" and "WAX-…": ?q=W%X finds only the first
@Test void listFiltersByStorageClassAndPolicyConfigured() // COLD item found by storageClass=COLD, not by NORMAL;
                                                      // after PUT policy, policyConfigured=true finds it
@Test void listPagesInSkuOrder()                      // three items with a shared prefix, size=2 → 2 then 1, ordered by sku
```

- [ ] **Step 2: Write the failing security tests** (`@SecuredIntegrationTest`, `SecuredMvc.asRole`)

```java
@Test void warehouseStaffReadsButCannotWrite()   // GET 200; PUT logistics 403
@Test void plannerAndManagerWrite()              // INVENTORY_PLANNER and WAREHOUSE_MANAGER: PUT policy 200
@Test void productUpdateAloneIsNotEnough()       // role granted only product-products:UPDATE (pattern of
                                                 // InventoryCatalogSecurityIntegrationTest.token) → PUT 403
```

- [ ] **Step 3: Run** `mvn test -Dtest='InventoryItem*IntegrationTest'` — Expected: compilation failure.

- [ ] **Step 4: Implement.**
  - `InventoryItemSearchAdapter`: one `SELECT … FROM inventory.inventory_items` built from the criteria
    plus a `count(*)` with the same `WHERE`; `ORDER BY sku`; `LIMIT/OFFSET` from the `Pageable`; returns
    `PageImpl`. `q` is matched as `(sku ILIKE ? ESCAPE '\' OR barcode ILIKE ? ESCAPE '\')` with `\`, `%`, `_`
    escaped in the value before wrapping it in `%…%`.
  - `InventoryItemsImpl.search`: `Pages.toResponse(search.items(criteria, Pages.of(page, size)).map(…))`,
    `trackingMode = TrackingMode.of(lotTracked, serialTracked)`, as `StockBrowsingImpl` does.
  - `InventoryItemController`: `@RequestMapping("/api/v1/inventory/items")`, `@Tag(name = "Inventory items")`,
    `@PermissionResource(code = INVENTORY_ITEMS, group = "Inventory", label = "Inventory items",
    route = "/inventory/items", apiPath = "/api/v1/inventory/items", actions = {VIEW_PAGE, READ, CREATE, UPDATE, EXPORT})`.
    List takes `page` default 0 and `size` default `Pages.DEFAULT_PAGE_SIZE`. Each handler has an `@Operation` summary.
  - If `nonUuidIdIs400` fails with 500, add the missing framework exception type to
    `GlobalExceptionHandler` (CLAUDE.md §5 HTTP) rather than catching it in the controller.

- [ ] **Step 5: Run** `mvn test -Dtest='InventoryItem*IntegrationTest+PermissionMatrixTest'` — Expected: all PASS.

- [ ] **Step 6: Commit** (after go-ahead) — `feat(inventory): inventory item endpoints under /api/v1/inventory/items`

---

### Task 3: `inventoryItemId` on `VariantResponse`

**Files:**
- Modify: `inventory/api/InventoryService.java`, `inventory/internal/service/InventoryServiceImpl.java`,
  `inventory/internal/domain/InventoryItemDirectory.java`, `inventory/internal/repository/InventoryItemDirectoryAdapter.java`
- Modify: `product/internal/service/ProductVariantService.java`, `product/internal/controller/ProductVariantController.java`,
  `product/internal/controller/ProductAdminWebMapper.java`, `product/internal/controller/dto/VariantResponse.java`
- Test: `src/test/java/com/stockflow/product/internal/service/VariantInventoryItemIdIntegrationTest.java`;
  modify `src/test/java/com/stockflow/product/internal/controller/ProductVariantControllerHttpTest.java`

**Interfaces:**
- Produces:
  - `InventoryService.itemIdsOf(Collection<String> skus): Map<String, UUID>` — one query; empty input →
    `Map.of()` with no SQL. Same on `InventoryItemDirectory`.
  - `ProductVariantService.inventoryItemIds(Collection<VariantView> variants): Map<String, UUID>`
    (`@Transactional(readOnly = true)`, delegates to `InventoryService.itemIdsOf` on the SKUs).
  - `VariantResponse` gains a last component `UUID inventoryItemId`;
    `ProductAdminWebMapper.toResponse(VariantView v, UUID inventoryItemId)`;
    `toResponse(Generated g, Map<String, UUID> ids)`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void everyListedVariantCarriesItsItemId()  // GET /products/{p}/variants → each content[i].inventoryItemId ==
                                                 // (select id from inventory.inventory_items where sku = content[i].sku)
@Test void listingCostsOneInventoryQuery()       // 5 variants; count statements touching inventory.inventory_items
                                                 // during the list call == 1 (measure as .claude/skills/jpa-orm-review says)
@Test void renamedDraftSkuKeepsTheSameItemId()   // id before; PUT variant {sku: new}; GET → same inventoryItemId
@Test void emptyProductListsAnEmptyPage()        // product with no variants → 200, content []
@Test void itemIdsOfEmptyIsEmpty()               // inventory.itemIdsOf(List.of()) → empty map
```

  In `ProductVariantControllerHttpTest`, stub `service.inventoryItemIds(any())` and assert
  `$.data.inventoryItemId` on one GET.

- [ ] **Step 2: Run** `mvn test -Dtest='VariantInventoryItemIdIntegrationTest+ProductVariantControllerHttpTest'` — Expected: FAIL (no `inventoryItemId`).

- [ ] **Step 3: Implement.** Every `ProductVariantController` handler that returns variants calls
  `variants.inventoryItemIds(...)` once on the views it is about to map, then maps with the id for each
  view's SKU. The lookup runs after the write's own transaction has committed, so a just-created variant's
  item (database trigger) is visible.

- [ ] **Step 4: Run** the Step 2 command — Expected: PASS.

- [ ] **Step 5: Commit** (after go-ahead) — `feat(product): variants carry the id of their inventory item`

---

### Task 4: Delete the product routes and port their tests

**Files:**
- Delete: `product/internal/controller/InventoryControlController.java`, `InventoryControlWebMapper.java`,
  `SkuLogisticsController.java`; `product/internal/service/SkuInventoryControlService.java`, `RecordDiff.java`;
  `product/internal/controller/dto/InventoryControlResponse.java`, `UpdateInventoryControlRequest.java`,
  `SkuLogisticsRequest.java`, `SkuLogisticsResponse.java`
- Modify: `product/internal/controller/ProductAdminWebMapper.java` (drop the two logistics methods),
  `product/internal/controller/ProductVariantController.java` (javadoc naming the removed routes),
  `product/internal/domain/ProductChangeType.java` (javadoc: the two values are only read now),
  `docs/SCRUM-70-71-backend.md` (API contract row)
- Modify tests: `InventoryCatalogIntegrationTest`, `InventoryCatalogSecurityIntegrationTest`,
  `procurement/ProductEndOfLifePurchasingIntegrationTest`, `product/internal/service/ProductVariantOptionsIntegrationTest`,
  `product/ProductPimIntegrationTest`, `product/ProductHistoryAndRemovalIntegrationTest`

**Interfaces:**
- Consumes: `InventoryItems` (Task 1), the HTTP API (Task 2), `InventoryService.itemIdsOf` (Task 3).

- [ ] **Step 1: Port the tests before deleting anything.**
  - Replace `SkuInventoryControlService` calls with `InventoryItems`: `get(product, variantId)` →
    `items.get(itemId)`; `update(product, variantId, v, policy)` → `items.configure(itemId, v, policy)`;
    `logistics/describe` likewise. `InventoryCatalogIntegrationTest.Fixture` gains `UUID itemId`.
  - HTTP calls on `/products/{p}/skus/{s}/inventory-control` move to `/api/v1/inventory/items/{itemId}/policy`
    (GET to `/items/{itemId}`, reading `$.data.policy.*`).
  - Delete assertions that only pinned product-owned behaviour that no longer exists — "SKU must belong to the
    product" and "refused while the product is under review / discontinued" — and leave a one-line comment
    naming the spec decision D1 where a test is removed.
  - `ProductHistoryAndRemovalIntegrationTest`: drop the logistics/policy edits from the scenario and the
    `INVENTORY_CONTROL`, `LOGISTICS` entries from both `containsExactly` lists; add

```java
@Test void aLogisticsRowWrittenBeforeTheSplitIsStillReadable()
// insert into product.product_change_log(id, product_id, change_type, subject_type, subject_id, changed_fields)
//   values (?, ?, 'LOGISTICS', 'LOGISTICS', ?, '{weightKg}')
// GET /products/{id}/versions → an entry with changeType "LOGISTICS", subjectType "LOGISTICS"
```

  - Add `@Test void theOldRoutesAreGone()` to `InventoryItemHttpIntegrationTest`: GET and PUT on both
    `/api/v1/products/{p}/skus/{variantId}/logistics` and `/inventory-control` → 404.

- [ ] **Step 2: Run** `mvn test -Dtest='InventoryItemHttpIntegrationTest#theOldRoutesAreGone'` — Expected: FAIL (routes still answer).

- [ ] **Step 3: Delete the files listed above** and fix what no longer compiles.

- [ ] **Step 4: Run** `mvn test -Dtest='InventoryCatalog*Test+ProductEndOfLifePurchasingIntegrationTest+ProductVariantOptionsIntegrationTest+ProductPimIntegrationTest+ProductHistoryAndRemovalIntegrationTest+InventoryItemHttpIntegrationTest+ModularityTest+ArchitectureTest'` — Expected: all PASS. Then `python3 tools/verify.py` — Expected: no findings.

- [ ] **Step 5: Commit** (after go-ahead) — `refactor(product): drop the SKU logistics and inventory-control routes`

---

### Task 5: Move the writes into inventory and narrow the api

**Files:**
- Modify: `inventory/api/InventoryControlService.java` (remove `configure`, `describe`, `evaluate`; fix javadoc)
- Modify: `inventory/internal/service/InventoryControlServiceImpl.java` (keep `item`, `policy`, `logistics`, `expiryWarningDays`)
- Modify: `inventory/internal/service/InventoryItemsImpl.java` (own the three bodies)
- Modify: `inventory/internal/domain/InventoryItemDirectory.java` (javadoc "nothing in this module edits inventory items yet")
- Modify tests: `inventory/internal/service/StockAlertIntegrationTest.java`, `InventoryCatalogIntegrationTest.java`
  (`evaluate` calls), `inventory/internal/service/ExpiryWarningWindowTest.java` (constructor), `InventoryItemsIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1–4 complete; no caller of the three methods remains outside inventory.
- Produces: `InventoryControlService` = `item(Sku)`, `policy(Sku)`, `logistics(Sku)`, `expiryWarningDays()`.
  `InventoryItemsImpl` injects `InventoryPolicyRepository`, `Clock`, `CurrentUserProvider`,
  `ApplicationEventPublisher`, `InventoryControlService` (reads only).

- [ ] **Step 1: Write the failing tests** in `InventoryItemsIntegrationTest`

```java
@Test void staleVersionOnPolicyIsOptimisticLock()   // configure(id, v-1, …) → OPTIMISTIC_LOCK (today CONFLICT)
@Test void staleVersionOnLogisticsIsOptimisticLock() // describe(id, v-1, …) → OPTIMISTIC_LOCK
@Test void sharedVersionRefusesTheSecondGroup()     // v = get(id).version(); describe(id, v, …); configure(id, v, …) → OPTIMISTIC_LOCK
@Test void policySaveStillReevaluatesAlerts()       // stock below a new reorderPoint → an open alert for the SKU
                                                    // (AlertFixtures / Await, as StockAlertIntegrationTest does)
```

- [ ] **Step 2: Run** `mvn test -Dtest=InventoryItemsIntegrationTest` — Expected: the two stale-version tests FAIL on `CONFLICT`.

- [ ] **Step 3: Implement.** Move the bodies of `configure`, `describe`, `evaluate` from
  `InventoryControlServiceImpl` into `InventoryItemsImpl` as private methods taking the resolved SKU, unchanged
  except: a stale version is `OPTIMISTIC_LOCK` in both. Remove the three from the interface and the impl;
  drop the constructor parameters the impl no longer uses. `StockAlertIntegrationTest` and
  `InventoryCatalogIntegrationTest` switch to `InventoryItems` (`items.configure(id, …)`,
  `items.get(id).evaluation()`), resolving the id with `inventory.itemIdsOf(List.of(sku)).get(sku)`.
  Rewrite the `InventoryControlService` and `InventoryItemDirectory` javadoc so it states what is true now.

- [ ] **Step 4: Run** `mvn test -Dtest='InventoryItemsIntegrationTest+StockAlertIntegrationTest+InventoryCatalogIntegrationTest+ExpiryWarningWindowTest+ModularityTest+ArchitectureTest'` and `python3 tools/verify.py` — Expected: all PASS, no findings.

- [ ] **Step 5: Commit** (after go-ahead) — `refactor(inventory): only inventory edits an inventory item`

---

### Task 6: Whole-branch verification

- [ ] **Step 1: Run** `python3 tools/verify.py` — Expected: no findings.
- [ ] **Step 2: Run** `mvn verify` (Docker up for Testcontainers) — Expected: BUILD SUCCESS. Any failure is
  reported with its output, not worked around.
- [ ] **Step 3: Grep for leftovers** — `grep -rn "skus/{skuId}\|SkuInventoryControlService\|inventory-control" src docs/SCRUM-70-71-backend.md`
  — Expected: no hits outside `docs/superpowers/`.
- [ ] **Step 4: Report** to the owner what to tell FE, Võ Hoàng Minh and the role-grant owner (spec §7).
