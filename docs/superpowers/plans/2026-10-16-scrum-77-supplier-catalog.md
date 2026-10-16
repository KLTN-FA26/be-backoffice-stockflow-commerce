# SCRUM-77 Supplier catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Maintain `procurement.supplier_items` — which supplier sells which SKU, at what price, MOQ, pack size and lead time, and which supplier is preferred per SKU — with price history, through a REST API.

**Architecture:** A new `SupplierItem` aggregate in `procurement/internal/domain` with its own port and adapter (not a child of `Supplier`). `SupplierItemService` in `procurement :: api` is the one entry point; PR2 (SCRUM-78) will read the same port from `checkLines`. The preferred switch is serialised by a transaction-scoped advisory lock keyed by the inventory item.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Modulith 1.4, Spring Data JPA / Hibernate, PostgreSQL 16, JUnit 5 + Testcontainers, MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-16-scrum-69-procurement-data-design.md` (§3 and the PR1 list in §5). Read it before Task 1.

## Global Constraints

- Branch `feature/SCRUM-77-supplier-catalog` from `develop`. **Never `git commit` or `git push` without the owner's explicit yes** (CLAUDE.md §7); every "Commit" step below means "propose the commit and wait".
- No new files in `src/main/resources/db/migration` — the schema is Tú's. `supplier_items` and `supplier_item_prices` already exist (`V20260928002000__proc_supplier_master.sql`).
- All identifiers, code and javadoc in English; the `_vi` bundle in Vietnamese. Javadoc explains why.
- Primary keys `Identifiers.newId()`; enums `EnumType.STRING`; entities extend `BaseEntity`; no entity leaves the adapter; nothing from `internal` in `api`.
- No cross-schema JOIN. Inventory data (SKU ↔ inventory item) comes only through `InventoryService` (`inventory :: api`); `supplier_items ⨝ suppliers` is the same schema and is allowed.
- Every handler `public` with `@RequiresPermission` on the method.
- **Permissions:** use the seeded resource `SupplierResources.SUPPLIER_ITEMS` (`procurement-supplier-items`, V20260928006000; PROCUREMENT_STAFF holds all of it). GETs need `Action.READ`, POST `CREATE`, PUT `UPDATE`, DELETE `DELETE`. No migration and no seed change. (This replaced the earlier decision to reuse `procurement-suppliers`, which assumed a new resource would need a migration; the resource was already seeded.)
- Field limits copied from the schema: `supplier_sku_code` ≤ 50; price `NUMERIC(18,2)`, > 0, ≤ 2 decimals; `moq ≥ 1`; `pack_size ≥ 1`; `lead_time_days` null or 0–365 (365 is our cap, DB only says ≥ 0); `moq % pack_size == 0`.
- During tasks run only the focused tests plus `ModularityTest`, `ArchitectureTest` and `python -X utf8 tools/verify.py`. One full `mvn verify` in the last task.

## Review Focus

1. **Two price changes in the same instant** (or create then reprice in one clock tick): closing the open period at `now` would make `effective_to = effective_from` and trip `ck_supplier_item_prices_period` as a 500. Expected: when the open period started at `now`, its `unit_price` is overwritten in place. → Task 2, `repricingAtTheSameInstantOverwritesTheOpenPeriod`.
2. **Malformed SKU** in the body or in `/procurement/skus/{sku}/suppliers` (`new Sku(..)` throws `IllegalArgumentException`): expected 400 `VALIDATION_FAILED`, never 500. A lowercase or padded SKU is normalised and found. → Task 3 and Task 4.
3. **Item id of another supplier** in `PUT/DELETE /suppliers/{A}/items/{itemOfB}`: expected 404 `SUPPLIER_ITEM_NOT_FOUND`, never an edit across suppliers. → Task 3, `anItemIsOnlyReachableThroughItsOwnSupplier`.
4. **Preferred toggles that are not switches:** re-saving an already-preferred row with `preferred=true` must not clear itself; saving it with `preferred=false` leaves the item with no preferred supplier. → Task 3, `resavingThePreferredRowKeepsIt` and `unpreferringLeavesNoPreferredSupplier`.
5. **Listing an unknown supplier:** `GET /suppliers/{unknown}/items` → 404 `SUPPLIER_NOT_FOUND`, not an empty page; a stale `version` on PUT or DELETE → 409 `OPTIMISTIC_LOCK`. → Task 3.

---

### Task 1: `SupplierItem` domain and port

**Files:**
- Create: `src/main/java/com/stockflow/procurement/internal/domain/SupplierItem.java`
- Create: `src/main/java/com/stockflow/procurement/internal/domain/SupplierItemTerms.java`
- Create: `src/main/java/com/stockflow/procurement/internal/domain/PriceChange.java`
- Create: `src/main/java/com/stockflow/procurement/internal/domain/OrderQuantityViolation.java`
- Create: `src/main/java/com/stockflow/procurement/internal/domain/CatalogSupplier.java`
- Create: `src/main/java/com/stockflow/procurement/internal/domain/SupplierItemRepository.java`
- Test: `src/test/java/com/stockflow/procurement/internal/domain/SupplierItemTest.java`

**Interfaces:**
- Produces:
  - `record SupplierItemTerms(String supplierSkuCode, Money price, int moq, int packSize, Integer leadTimeDays)` — compact constructor enforces the Global Constraints limits; violation → `BusinessException(VALIDATION_FAILED, msg, List.of(new FieldError(field, msg, "VALIDATION_FAILED")))` with field `price` / `moq` / `packSize` / `leadTimeDays` / `supplierSkuCode`. A blank `supplierSkuCode` becomes null; it is stripped.
  - `final class SupplierItem extends AggregateRoot`:
    - `static SupplierItem create(UUID supplierId, UUID inventoryItemId, SupplierItemTerms terms, boolean preferred)` — id from `Identifiers.newId()`, version 0.
    - reconstitution constructor `SupplierItem(UUID id, UUID supplierId, UUID inventoryItemId, SupplierItemTerms terms, boolean preferred, long version, Instant createdAt, Instant lastModifiedAt)`.
    - `Optional<PriceChange> update(SupplierItemTerms terms, boolean preferred)` — replaces terms and flag; returns a `PriceChange` only when the price amount differs (`compareTo != 0`). Currency must equal the current one, else `IllegalStateException` (the service always passes the supplier's).
    - `List<OrderQuantityViolation> orderQuantityViolations(int quantity)` — `BELOW_MOQ` when `quantity < moq`, `NOT_PACK_MULTIPLE` when `quantity % packSize != 0`; both may be returned; never throws. (Used by PR2.)
    - `int effectiveLeadTimeDays(Integer supplierLeadTimeDays)` — item's, else supplier's, else `CommercialTerms.LEAD_DAYS`.
    - accessors `id() supplierId() inventoryItemId() terms() preferred() version() createdAt() lastModifiedAt()`.
  - `record PriceChange(Money from, Money to)`.
  - `enum OrderQuantityViolation { BELOW_MOQ, NOT_PACK_MULTIPLE }`.
  - `record CatalogSupplier(UUID id, String code, String name, SupplierStatus status, Currency currency, Integer leadTimeDays)`.
  - `interface SupplierItemRepository` (port, no JPA types):
    - `Optional<CatalogSupplier> supplierForShare(UUID supplierId)` — reads the supplier `FOR SHARE`, so a concurrent deactivation waits for this transaction.
    - `Optional<SupplierItem> findById(UUID supplierItemId)`
    - `Optional<SupplierItem> findByIdForUpdate(UUID supplierItemId)`
    - `void lockCatalogOf(UUID inventoryItemId)` — transaction-scoped advisory lock.
    - `Optional<SupplierItem> findPreferred(UUID inventoryItemId)`
    - `SupplierItem save(SupplierItem item, Optional<PriceChange> priceChange, Instant now)` — inserts or updates; opens the first price period on insert; applies `priceChange` otherwise.
    - `void delete(SupplierItem item)`
    - `boolean usedByOpenPurchaseOrder(UUID supplierId, UUID inventoryItemId)`
    - `PageResponse<SupplierItem> listBySupplier(UUID supplierId, int page, int size, String sort)`
    - `List<SkuSupplierRow> suppliersOf(UUID inventoryItemId)` with nested `record SkuSupplierRow(SupplierItem item, CatalogSupplier supplier)`, ordered preferred first, then supplier name.

- [ ] **Step 1: Write the failing tests in `SupplierItemTest`**

```java
@Test void moqMustBeAMultipleOfThePackSize() {
    assertThatThrownBy(() -> terms(new BigDecimal("10.00"), 5, 2, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).fieldErrors()).extracting(FieldError::field).containsExactly("moq"));
}
@Test void priceMustBePositiveWithAtMostTwoDecimals() { /* 0, -1, 1.005 → field "price" */ }
@Test void leadTimeIsNullOrZeroTo365() { /* -1 and 366 refused; null, 0, 365 accepted */ }
@Test void blankSupplierSkuCodeBecomesNull() { assertThat(terms("  ", ...).supplierSkuCode()).isNull(); }
@Test void orderQuantityViolations() {
    var item = SupplierItem.create(SUPPLIER, ITEM, terms(new BigDecimal("10"), 12, 6, null), false);
    assertThat(item.orderQuantityViolations(12)).isEmpty();
    assertThat(item.orderQuantityViolations(18)).isEmpty();
    assertThat(item.orderQuantityViolations(6)).containsExactly(BELOW_MOQ);
    assertThat(item.orderQuantityViolations(13)).containsExactly(NOT_PACK_MULTIPLE);
    assertThat(item.orderQuantityViolations(7)).containsExactlyInAnyOrder(BELOW_MOQ, NOT_PACK_MULTIPLE);
}
@Test void updateReportsAPriceChangeOnlyWhenTheAmountDiffers() {
    // 10.00 → 10.0 : empty;  10.00 → 12.50 : PriceChange(10.00 VND, 12.50 VND)
}
@Test void effectiveLeadTimeFallsBackToTheSupplierThenTheDefault() {
    // item 3 → 3; item null, supplier 9 → 9; both null → CommercialTerms.LEAD_DAYS
}
```

(`fieldErrors()` — use whatever accessor `BusinessException` exposes for its field errors.)

- [ ] **Step 2: Run** `mvn -q test -Dtest=SupplierItemTest` — expected: compilation failure (types missing).
- [ ] **Step 3: Implement the five domain types and the port** in the files above, mirroring `Supplier.java` (extends `AggregateRoot`, no Spring/JPA imports).
- [ ] **Step 4: Run** `mvn -q test -Dtest=SupplierItemTest` — expected: PASS. Then `python -X utf8 tools/verify.py` — expected: no findings.
- [ ] **Step 5: Commit** (after the owner's yes): `feat(procurement): supplier item aggregate and catalog port (SCRUM-77)`.

---

### Task 2: Persistence adapter

**Files:**
- Create: `src/main/java/com/stockflow/procurement/internal/entity/SupplierItemJpaEntity.java` (`procurement.supplier_items`)
- Create: `src/main/java/com/stockflow/procurement/internal/entity/SupplierItemPriceJpaEntity.java` (`procurement.supplier_item_prices`)
- Create: `src/main/java/com/stockflow/procurement/internal/repository/SupplierItemJpaRepository.java`
- Create: `src/main/java/com/stockflow/procurement/internal/repository/SupplierItemPriceJpaRepository.java`
- Create: `src/main/java/com/stockflow/procurement/internal/repository/SupplierItemRepositoryAdapter.java`
- Modify: `src/main/java/com/stockflow/common/error/ErrorCode.java`, `src/main/resources/i18n/messages.properties`, `src/main/resources/i18n/messages_vi.properties`
- Test: `src/test/java/com/stockflow/procurement/internal/repository/SupplierItemRepositoryAdapterIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 port and domain types.
- Produces: the `@Repository` bean implementing `SupplierItemRepository`; new error codes
  `SUPPLIER_ITEM_NOT_FOUND("Supplier item not found", 404)`,
  `SUPPLIER_ITEM_DUPLICATE("This supplier already lists this SKU", 409)`,
  `SUPPLIER_ITEM_IN_USE("An open purchase order still orders this SKU from this supplier", 409)`,
  each in its HTTP-status group, with `error.<CODE>` in both bundles (`MessageBundleTest` checks both).

Mapping and SQL decisions:
- Entities map every column the domain writes; `currency` on both tables is written from the domain's `Money`. `created_*`/`last_modified_*` come from `BaseEntity`.
- `supplierForShare`: native `SELECT id, code, name, status, currency, lead_time_days FROM procurement.suppliers WHERE id = ?1 FOR SHARE` (the supplier entity does not map `currency`, and must not gain an insertable mapping: inserts rely on the column default).
- `findByIdForUpdate`: `@Lock(PESSIMISTIC_WRITE)` query + `entityManager.refresh(entity, PESSIMISTIC_WRITE)`, as `SupplierRepositoryAdapter.findByIdForUpdate` does.
- `lockCatalogOf`: `select pg_advisory_xact_lock(hashtextextended(?1, 769))` with the inventory item id as text (seed 769 is this lock's namespace; inventory uses 701).
- `save`: `saveAndFlush`; a `DataIntegrityViolationException` whose most specific cause names `uk_supplier_items_pair` → `BusinessException(SUPPLIER_ITEM_DUPLICATE)`; anything else rethrown. On insert, write the first price period `[now, null)`. On a `PriceChange`: load the open period (`effective_to IS NULL`); if its `effective_from` equals `now`, set its `unit_price`; otherwise set its `effective_to = now` and insert `[now, null)`. Flush before returning.
- `usedByOpenPurchaseOrder`: native `EXISTS` over `procurement.purchase_order_lines l JOIN procurement.purchase_orders po ON po.id = l.po_id WHERE po.supplier_id = ?1 AND l.inventory_item_id = ?2 AND po.status IN ('DRAFT','PENDING_APPROVAL','APPROVED','CONFIRMED','PARTIALLY_RECEIVED')` — the same status list as `SupplierJpaRepository.hasOpenPurchaseOrders`.
- `listBySupplier`: `SortWhitelist.of("createdAt", "lastModifiedAt", "currentPrice").withDefault("createdAt", Sort.Direction.DESC)`, `Pages.of(...)`.
- `suppliersOf`: one JPQL/native query joining `supplier_items` to `suppliers` for the item, `ORDER BY is_preferred DESC, s.name, s.code`.

- [ ] **Step 1: Write the failing tests** (`@IntegrationTest @Import(PostgresContainer.class)`, rows from `ReferenceRows.approvedProduct(em)` and a supplier inserted through `SupplierService.create`):

```java
@Test void insertOpensTheFirstPricePeriod()            // one row in supplier_item_prices, effective_to null, unit_price = 10.00
@Test void repricingClosesTheOpenPeriodAndOpensOne()   // now1 then now2 = now1 + 1s → periods [now1,now2) and [now2,null)
@Test void repricingAtTheSameInstantOverwritesTheOpenPeriod() // two saves with the same Instant → still one period, latest price
@Test void aDuplicatePairIsABusinessError()            // second create of same (supplier, item) → SUPPLIER_ITEM_DUPLICATE
@Test void usedByOpenPurchaseOrderSeesOnlyOpenOrders()  // a DRAFT PO line → true; the same PO CANCELLED → false
@Test void suppliersOfListsThePreferredFirst()          // two suppliers, B preferred → [B, A]
```

Use a `TransactionTemplate` per call so each save commits; read periods with `JdbcTemplate`.

- [ ] **Step 2: Run** `mvn -q test -Dtest=SupplierItemRepositoryAdapterIntegrationTest` — expected: compilation failure.
- [ ] **Step 3: Implement** entities, JPA repositories, adapter and the three error codes with both bundle entries.
- [ ] **Step 4: Run** the same test — expected: PASS; then `mvn -q test -Dtest=MessageBundleTest,ArchitectureTest` — expected: PASS.
- [ ] **Step 5: Commit** (after the owner's yes): `feat(procurement): persist supplier items and their price periods (SCRUM-77)`.

---

### Task 3: `SupplierItemService`

**Files:**
- Create: `src/main/java/com/stockflow/procurement/api/SupplierItemService.java`
- Create: `src/main/java/com/stockflow/procurement/api/SaveSupplierItemCommand.java`
- Create: `src/main/java/com/stockflow/procurement/api/SupplierItemSummary.java`
- Create: `src/main/java/com/stockflow/procurement/api/SkuSupplierSummary.java`
- Create: `src/main/java/com/stockflow/procurement/internal/service/SupplierItemServiceImpl.java`
- Test: `src/test/java/com/stockflow/procurement/internal/service/SupplierItemServiceIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 port/domain, Task 2 error codes; `InventoryService.itemPolicy(Sku)`, `InventoryService.itemPolicies(Collection<UUID>)`, `ProductService.purchasability(Collection<String>)`, `Clock`.
- Produces:
  - `record SaveSupplierItemCommand(String sku, String supplierSkuCode, BigDecimal price, int moq, int packSize, Integer leadTimeDays, boolean preferred, Long version)` — `sku` is read on create only; `version` required on update.
  - `record SupplierItemSummary(UUID supplierItemId, UUID supplierId, UUID inventoryItemId, String sku, String supplierSkuCode, BigDecimal price, String currency, int moq, int packSize, Integer leadTimeDays, int effectiveLeadTimeDays, boolean preferred, long version, Instant createdAt, Instant lastModifiedAt)`
  - `record SkuSupplierSummary(UUID supplierId, String supplierCode, String supplierName, String supplierStatus, UUID supplierItemId, String supplierSkuCode, BigDecimal price, String currency, int moq, int packSize, int effectiveLeadTimeDays, boolean preferred)`
  - `interface SupplierItemService`:
    - `SupplierItemSummary create(UUID supplierId, SaveSupplierItemCommand command)`
    - `SupplierItemSummary update(UUID supplierId, UUID supplierItemId, SaveSupplierItemCommand command)`
    - `void delete(UUID supplierId, UUID supplierItemId, long version)`
    - `PageResponse<SupplierItemSummary> list(UUID supplierId, int page, int size, String sort)`
    - `List<SkuSupplierSummary> suppliersOf(String sku)`

Behaviour (`@Service @Transactional`, package-private class, public methods, `@Auditable` on writes with `resourceType = "supplier-item"`):
- **create:** `supplierForShare` → `SUPPLIER_NOT_FOUND` / `SUPPLIER_INACTIVE` (unless ACTIVE); `new Sku(sku)` with `IllegalArgumentException` → `VALIDATION_FAILED` on field `sku`; `itemPolicy(sku)` → `INVENTORY_ITEM_NOT_FOUND`; `purchasability` not `PURCHASABLE` → `SKU_NOT_PURCHASABLE` with the verdict as field code on `sku`; terms with `Money(price, supplier.currency())`; if `preferred`, `lockCatalogOf(item)` then unset the current preferred row (save it with `preferred=false`) before saving the new one.
- **update:** supplier ACTIVE as above; `findByIdForUpdate`; empty or `supplierId` mismatch → `SUPPLIER_ITEM_NOT_FOUND`; `command.version()` ≠ item version → `OPTIMISTIC_LOCK`; if `preferred` and the item is not already preferred, lock and unset the other preferred row; `item.update(...)` and save with its `PriceChange`.
- **delete:** supplier must exist (any status); same lookup/mismatch/version checks; `usedByOpenPurchaseOrder` → `SUPPLIER_ITEM_IN_USE`; delete.
- **list:** supplier must exist (`SUPPLIER_NOT_FOUND`); SKUs for the page through one `itemPolicies(ids)` call (no per-row lookups).
- **suppliersOf:** malformed SKU → `VALIDATION_FAILED`; unknown → `INVENTORY_ITEM_NOT_FOUND`; map rows in order.
- Unpreferring the old row and preferring the new one is ordered so the partial unique index never sees two preferred rows: save the old row (flush) first.

- [ ] **Step 1: Write the failing tests** (`@IntegrationTest`, real commits through `TransactionTemplate` only where concurrency is tested):

```java
@Test void createListsTheSkuInTheSuppliersCurrencyWithOnePricePeriod()
@Test void anInactiveSupplierCannotListOrEdit()                    // SUPPLIER_INACTIVE on create and update
@Test void anUnknownSkuIsRefused()                                 // INVENTORY_ITEM_NOT_FOUND
@Test void aMalformedSkuIsAValidationError()                       // "not a sku!" → VALIDATION_FAILED, field "sku"
@Test void aDraftSkuIsNotPurchasable()                             // ReferenceRows.product(em) → SKU_NOT_PURCHASABLE
@Test void aDuplicatePairIsAConflict()                             // SUPPLIER_ITEM_DUPLICATE
@Test void moqNotAMultipleOfPackSizeIsRefused()                    // 5 / 2 → VALIDATION_FAILED, field "moq"
@Test void priceChangeWritesANewPeriodAndClosesTheOld()            // and an unchanged price writes none
@Test void preferringASecondSupplierUnprefersTheFirst()            // exactly one preferred row for the item
@Test void resavingThePreferredRowKeepsIt()
@Test void unpreferringLeavesNoPreferredSupplier()
@Test void concurrentPreferredCreatesSerialise()                   // two threads, both preferred=true, CountDownLatch start → both succeed, exactly one preferred
@Test void anItemIsOnlyReachableThroughItsOwnSupplier()            // update/delete via supplier A of B's item → SUPPLIER_ITEM_NOT_FOUND
@Test void aStaleVersionIsAConflict()                              // update and delete → OPTIMISTIC_LOCK
@Test void deleteIsRefusedWhileAnOpenOrderUsesThePair()            // SUPPLIER_ITEM_IN_USE; allowed once that PO is CANCELLED
@Test void listingAnUnknownSupplierIs404()
@Test void suppliersOfAcceptsALowercaseSkuAndListsThePreferredFirst()
```

The open PO for `deleteIsRefusedWhileAnOpenOrderUsesThePair` is raised through `ProcurementService.createPurchaseOrder` (STANDARD, the listed SKU) and cancelled through `ProcurementService.cancel`.

- [ ] **Step 2: Run** `mvn -q test -Dtest=SupplierItemServiceIntegrationTest` — expected: compilation failure.
- [ ] **Step 3: Implement** the api records, the interface and `SupplierItemServiceImpl`.
- [ ] **Step 4: Run** the same test — expected: PASS. Then `mvn -q test -Dtest=ModularityTest,ArchitectureTest` and `python -X utf8 tools/verify.py` — expected: PASS / no findings.
- [ ] **Step 5: Commit** (after the owner's yes): `feat(procurement): supplier catalog service with price history and preferred supplier (SCRUM-77)`.

---

### Task 4: REST API, security tests and docs

**Files:**
- Create: `src/main/java/com/stockflow/procurement/internal/controller/SupplierItemController.java`
- Create: `src/main/java/com/stockflow/procurement/internal/controller/dto/SaveSupplierItemRequest.java`
- Create: `src/main/java/com/stockflow/procurement/internal/controller/dto/SupplierItemResponse.java`
- Create: `src/main/java/com/stockflow/procurement/internal/controller/dto/SkuSupplierResponse.java`
- Modify: `docs/superpowers/specs/2026-10-16-scrum-69-procurement-data-design.md` §3.1 (drop `SUPPLIER_ITEMS`), §3.3 and §6 (permission decision below)
- Modify: `docs/business-design/04c-all-flows.md` or the procurement API section of `README.md` — wherever supplier endpoints are listed today; add the five routes (skip if neither lists supplier endpoints)
- Test: `src/test/java/com/stockflow/procurement/SupplierItemApiIntegrationTest.java`

**Interfaces:**
- Consumes: `SupplierItemService` (Task 3).
- Produces: the five routes of spec §3.3. No `@PermissionResource` on this controller — the resource is the one `SupplierController` already declares.

Controller decisions:
- `@RequestMapping("/api/v1")` with full paths on each handler: `GET/POST /suppliers/{supplierId}/items`, `PUT/DELETE /suppliers/{supplierId}/items/{supplierItemId}`, `GET /procurement/skus/{sku}/suppliers`.
- Guards: `@RequiresPermission(resource = SupplierResources.SUPPLIER_ITEMS, ...)`: both GETs `Action.READ`; POST `CREATE`; PUT `UPDATE`; DELETE `DELETE`. The controller declares `@PermissionResource` for it.
- POST → 201; DELETE → 204 with required `@RequestParam long version`; PUT body carries `version` (`@NotNull` in an `Update` validation group, as `SaveSupplierRequest` does).
- `SaveSupplierItemRequest`: `sku` `@NotBlank` on create only (ignored on update), `supplierSkuCode` `@Size(max = 50)`, `price` `@NotNull @Positive @Digits(integer = 16, fraction = 2)`, `moq`/`packSize` `@NotNull @Min(1)`, `leadTimeDays` `@Min(0) @Max(365)`, `preferred` nullable → false, `version` `Long`.
- `size` defaults to `Pages.DEFAULT_PAGE_SIZE`, as `SupplierController.list` does.

- [ ] **Step 1: Write the failing tests** (`@SecuredIntegrationTest`, `@MockitoBean JwtDecoder`, `SecuredMvc`):

```java
@Test void procurementStaffMaintainsACatalog()      // PROCUREMENT_STAFF: POST 201 → GET page has it → PUT 200 → DELETE ?version= 204
@Test void anAccountantReadsButCannotEdit()         // ACCOUNTANT: GET 200, POST 403
@Test void warehouseStaffCannotRead()               // WAREHOUSE_STAFF: GET /suppliers/{id}/items 403
@Test void anonymousIsRefused()                     // 401
@Test void skuSuppliersListsThePreferredFirst()     // GET /api/v1/procurement/skus/{lowercase sku}/suppliers → [preferred, other]
@Test void badBodiesAre400()                        // price 0, price 1.005, moq 0, leadTimeDays 366, missing version on PUT → 400 VALIDATION_FAILED with the field
@Test void aMalformedSkuInThePathIs400()            // GET /api/v1/procurement/skus/%20bad%21/suppliers → 400
```

- [ ] **Step 2: Run** `mvn -q test -Dtest=SupplierItemApiIntegrationTest` — expected: FAIL (404 for every route).
- [ ] **Step 3: Implement** the DTOs and `SupplierItemController`; update the spec's permission paragraphs to say the seeded resource `procurement-supplier-items` is used (GETs READ, POST CREATE, PUT UPDATE, DELETE DELETE; no migration, no seed change) and drop the grant bullet from §6.
- [ ] **Step 4: Run** `mvn -q test -Dtest=SupplierItemApiIntegrationTest,PermissionMatrixTest,ModularityTest,ArchitectureTest` — expected: PASS. Then `python -X utf8 tools/verify.py`.
- [ ] **Step 5: Full build:** `mvn verify` (≈11–16 min, run in the background) — expected: BUILD SUCCESS. Report any failure with its output; do not disable a test.
- [ ] **Step 6: Commit** (after the owner's yes): `feat(procurement): supplier catalog REST API (SCRUM-77)`.
