# SCRUM-473 Carton Lookup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Three read-only endpoints under `/api/v1/inventory/stock-packages`: one carton by LPN, what is at one location (whole / opened cartons, loose), and a paged list of cartons.

**Architecture:** A read-side port `StockPackageSearch` (repository package, like `StockBrowseSearch`) reads the view `inventory.stock_package_balance` and `inventory.stock_item` through `NamedParameterJdbcTemplate`, one statement per method; a `StockPackageLookup` service (next to `StockPackageLabels`) applies scope, existence and defaults in one read-only transaction; `StockPackageController` exposes it through response DTOs mapped by a MapStruct `StockPackageWebMapper`.

**Tech Stack:** Spring Boot 3.5, `NamedParameterJdbcTemplate`, MapStruct, Testcontainers Postgres.

**Spec:** `docs/superpowers/specs/2026-10-26-scrum-473-carton-lookup-design.md`

## Global Constraints

- No migration, no new `ErrorCode`; reuse `STOCK_PACKAGE_NOT_FOUND`, `LOCATION_NOT_FOUND`, `OUT_OF_DATA_SCOPE`, `VALIDATION_FAILED`.
- Do not edit `StockOperationsServiceImpl`, `InventoryService`, `GoodsReceiptServiceImpl`, anything under `warehouse/` (putaway runs on a sibling branch).
- Every handler public, `@RequiresPermission(resource = InventoryResources.STOCK_PACKAGES, action = Action.READ)`.
- One SQL statement per kind of fact; never one per carton. No JPA entity leaves the repository package.
- English identifiers and javadoc; javadoc says why.

## Review Focus

- Scope before existence: an outsider asking for a VOID carton of another warehouse gets 403, not its status — Task 3 (`aCartonInAnotherWarehouseIsOutOfScope`).
- An LPN in lower case or with spaces finds the carton — Task 2 (`detailByLowerCaseLpn`).
- A RECEIVING carton (no rows yet) has `onHand` 0 and empty `contents`, not a null or a 500 — Task 2 (`aReceivingCartonHoldsNothingYet`).
- The list unfiltered by status must not show CONSUMED/VOID cartons at their last bin — Task 2 (`listHidesClosedCartonsByDefault`).
- A warehouse-bound caller listing unfiltered sees only their warehouses — Task 3 (`anOutsiderListsNothing`).

---

### Task 1: Read-side queries `StockPackageSearch`

**Files:**
- Create: `src/main/java/com/stockflow/inventory/internal/repository/StockPackageSearch.java`
- Create: `src/main/java/com/stockflow/inventory/internal/repository/StockPackageSearchAdapter.java`

**Interfaces — Produces:**
```java
public interface StockPackageSearch {
    record CartonRow(UUID id, String lpn, String sku, String unitOfMeasure, int nominalQty, Integer receivedQty,
                     String locationCode, StockPackageStatus status, boolean unopened, String lotNumber,
                     LocalDate expiryDate, int onHand, int reserved, UUID goodsReceiptLineId, Instant closedAt) {}
    record ContentRow(String lotNumber, LocalDate expiryDate, Instant receivedAt, StockStatus status, int onHand,
                      int reserved) {}
    record LocationLine(String sku, String lotNumber, LocalDate expiryDate, StockStatus status,
                        int wholeCartons, int wholeCartonUnits, int openedCartons, int openedCartonUnits,
                        int looseUnits) {}
    /** Every field may be null but statuses (never empty). warehousePrefixes null = not limited. */
    record CartonCriteria(String locationCode, String sku, List<StockPackageStatus> statuses,
                          Set<String> warehousePrefixes) {}

    Optional<CartonRow> byLpn(String lpn);                    // stock_package_balance
    List<ContentRow> contentsOf(UUID stockPackageId);         // on_hand > 0; expiry nulls last, received_at, id
    List<LocationLine> atLocation(String locationCode);       // one grouped statement over stock_item ⟕ stock_package
    Page<CartonRow> cartons(CartonCriteria criteria, Pageable pageable);   // count + page over the view
}
```
Sort properties map `lpn→lpn`, `locationCode→location_code`, `sku→sku`, always then `stock_package_id`.

### Task 2: `StockPackageLookup` service + integration test

**Files:**
- Create: `src/main/java/com/stockflow/inventory/internal/service/StockPackageLookup.java`
- Test: `src/test/java/com/stockflow/inventory/StockPackageLookupIntegrationTest.java`

**Produces** (public class, `@Service`, `@Transactional(readOnly = true)`, nested records with String statuses):
`Detail(lpn, sku, unitOfMeasure, nominalQty, receivedQty, lotNumber, expiryDate, onHand, reserved, status, unopened, locationCode, goodsReceiptLineId, closedAt, List<Content> contents)`,
`Content(lotNumber, expiryDate, receivedAt, status, onHand, reserved)`,
`LocationLine(sku, lotNumber, expiryDate, status, wholeCartons, wholeCartonUnits, openedCartons, openedCartonUnits, looseUnits, totalUnits)`,
`Carton(lpn, sku, unitOfMeasure, nominalQty, receivedQty, lotNumber, expiryDate, onHand, reserved, status, unopened, locationCode, goodsReceiptLineId)`,
`CartonQuery(location, sku, List<StockPackageStatus> statuses, page, size, sort)`;
`Detail byLpn(String)`, `List<LocationLine> atLocation(String)`, `PageResponse<Carton> cartons(CartonQuery)`.

Rules: `byLpn` — normalise, 404, `requireLocation`, then contents. `atLocation` — blank → `VALIDATION_FAILED`; `requireLocation`; `LocationDirectory.stateOf == UNKNOWN` → `LOCATION_NOT_FOUND`. `cartons` — location trimmed/upper-cased then `requireLocation`; sku trimmed/upper-cased; no statuses → `[RECEIVING, IN_STOCK]`; prefixes from `WarehouseScope.restriction()`; sort whitelist `lpn` (default ASC), `locationCode`, `sku`.

Tests (fixture from `StockPackageMoveIntegrationTest`: line of 2000, cartons 1000 + 980, received at `HCM-RCV01`, 20 loose):
- `detailByLowerCaseLpn`, `aReceivingCartonHoldsNothingYet`, `anUnknownLpnIsNotFound`,
- `summaryAtTheReceivingArea` (whole 2/1980, opened 0/0, loose 20, total 2000),
- `anOpenedCartonIsCountedApart` (−100 from the 980 carton committed directly, carton opened → whole 1/1000, opened 1/880, loose 20, total 1900),
- `anUnknownOrBlankLocation`, `listFiltersBySkuAndLocation`, `listHidesClosedCartonsByDefault`.

### Task 3: Controller, DTOs, mapper + HTTP test

**Files:**
- Modify: `src/main/java/com/stockflow/inventory/internal/controller/StockPackageController.java`
- Create: `controller/dto/StockPackageDetailResponse.java` (nested `Content`), `StockPackageLocationLineResponse.java`, `StockPackageResponse.java` — same components as Task 2's records
- Create: `controller/StockPackageWebMapper.java` (MapStruct, `unmappedTargetPolicy = ERROR`)
- Test: `src/test/java/com/stockflow/inventory/internal/controller/StockPackageLookupHttpTest.java`

Handlers: `GET /{lpn}`, `GET /location-summary?location=`, `GET ?location&sku&status&page&size&sort`.

Tests (fixture from `StockPackageLabelHttpTest`, plus receiving the line): `staffReadAllThree`, `readOnlyRolesRead` (INVENTORY_PLANNER, QC_STAFF), `aCartonInAnotherWarehouseIsOutOfScope` (detail, VOID detail, summary, filtered list → 403), `anOutsiderListsNothing`, `unknowns` (404 `STOCK_PACKAGE_NOT_FOUND`, 404 `LOCATION_NOT_FOUND`).

### Task 4: Verification

- [ ] `PYTHONUTF8=1 python3 tools/verify.py` → no findings.
- [ ] `mvn -o test -Dtest=ModularityTest,ArchitectureTest,MessageBundleTest,StockPackageLookupIntegrationTest,StockPackageLookupHttpTest,StockPackageLabelHttpTest` → green.
- [ ] One `mvn -o verify -Djacoco.skip=true` at the end, no source edits while it runs.
- [ ] CLAUDE.md §4 "Receiving into cartons": one sentence on the lookup. Commit only on the owner's go-ahead.
