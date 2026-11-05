# SCRUM-92 Putaway by carton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn received INBOUND stock into AVAILABLE stock in a bin through putaway tasks — one per carton, one per loose remainder — with bin suggestions and a scan confirmation that moves the goods in one transaction.

**Architecture:** `warehouse` owns the `PutawayTask` aggregate over the existing `warehouse.putaway_task` table and exposes it through `warehouse :: api`. `inventory` orchestrates: a listener opens tasks from `ReceiptStockReadyForPutaway`, `PutawayService` suggests bins (pure `PutawayRanker`) and confirms by moving stock with the existing carton / loose move code, then publishes `PutawayCompleted`, on which `procurement` closes the receipt.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Modulith 1.4, Spring Data JPA / JdbcTemplate, PostgreSQL 16, JUnit 5 + Testcontainers.

**Spec:** `docs/superpowers/specs/2026-10-26-scrum-92-putaway-design.md` — read it first; section numbers below (§n) are the spec's.

## Global Constraints

- **No migration.** Nothing is added under `src/main/resources/db/migration` (schema owned by Tú).
- `inventory` may use `warehouse :: api` only; `warehouse` depends on nothing; `procurement` may use both apis. Run `ModularityTest` after every task that touches packages or apis.
- All code, identifiers and javadoc in English; javadoc explains why. Vietnamese only in `messages_vi.properties`.
- Every new `ErrorCode` gets `error.<CODE>` in `messages.properties` and `messages_vi.properties` (`MessageBundleTest`).
- Permission resource `warehouse-putaway-tasks` (already seeded): READ for GETs, UPDATE for the confirmation. Handlers are `public` with method-level `@RequiresPermission`.
- Lock order for a confirmation: **task row `FOR UPDATE` → SKU advisory lock → `stock_package FOR UPDATE` → stock rows by id**.
- Primary keys `Identifiers.newId()`; enums `EnumType.STRING`; never return an entity from a controller or put one in an api.
- Lot "same lot" in suggestions means the same **lot number** (lot ids are per receipt).
- `@ApplicationModuleListener` methods are `public`.
- Never edit sources while `mvn test` runs (16 GB machine). Iterate with `mvn -o test -Dtest=<classes> -Djacoco.skip=true`; one full `mvn -o verify` in Task 9.
- **Never `git commit` without the user's go-ahead.** The commit steps below prepare the commit (stage + message); run `git commit` only once the user has said yes for the branch. Messages carry no tool attribution (`.claude/skills/git-commits`).

## Review Focus

1. **Two partial confirmations of one loose task** — the second must move stock, not be taken for a replay of the first (spec §6). Pinned in Task 3 (`putAwayLooseTwiceUnderOneTaskMovesBoth`) and Task 7.
2. **The putaway event delivered twice** (publication registry retry) — no second task, carton or loose. Pinned in Task 5.
3. **A confirmation for a task in another warehouse** — 403 before its status or LPN is revealed, even when the task is DONE. Pinned in Task 7 (`doneTaskOfAnotherWarehouseIs403`).
4. **A bin the clerk chose that breaks a hard rule** (area, other warehouse, unusable, class, capacity, weight) — refused although no suggestion list was asked for. Pinned in Task 7.
5. **A mixed receipt whose non-QC lines were put away while still IN_QC**, last QC line accepting nothing — must still close. Pinned in Task 8.

---

### Task 1: `PutawayTask` aggregate and `warehouse :: api` `PutawayTasks`

**Files:**
- Create: `src/main/java/com/stockflow/warehouse/internal/domain/PutawayTask.java`
- Create: `src/main/java/com/stockflow/warehouse/internal/domain/PutawayTaskRepository.java`
- Modify: `src/main/java/com/stockflow/warehouse/internal/entity/PutawayTaskJpaEntity.java` (map `stock_package_id`; add `apply(int quantity, UUID targetLocationId, PutawayStatus status)`)
- Modify: `src/main/java/com/stockflow/warehouse/internal/repository/PutawayTaskJpaRepository.java` (replace the TODO with finders)
- Create: `src/main/java/com/stockflow/warehouse/internal/repository/PutawayTaskRepositoryAdapter.java`
- Create: `src/main/java/com/stockflow/warehouse/api/PutawayTasks.java`, `PutawayTaskView.java`, `NewPutawayTask.java`
- Create: `src/main/java/com/stockflow/warehouse/internal/service/PutawayTasksImpl.java`
- Add `PUTAWAY_TASK_NOT_FOUND` (404), `PUTAWAY_TASK_DONE` (409), `PUTAWAY_QUANTITY_EXCEEDED` (422) to `ErrorCode` + both bundles
- Test: `src/test/java/com/stockflow/warehouse/internal/domain/PutawayTaskTest.java`, `src/test/java/com/stockflow/warehouse/PutawayTasksIntegrationTest.java`

**Interfaces:**
- Produces (`warehouse :: api`):
  ```java
  public record NewPutawayTask(UUID goodsReceiptId, UUID goodsReceiptLineId, String sku, int quantity, UUID stockPackageId) {}
  public record PutawayTaskView(UUID id, UUID goodsReceiptId, UUID goodsReceiptLineId, String sku, int quantity,
                                UUID stockPackageId, UUID targetLocationId, String status) {}
  public interface PutawayTasks {
      PutawayTaskView open(NewPutawayTask task);                       // idempotent, see below
      Optional<PutawayTaskView> find(UUID taskId);
      PutawayTaskView lockForUpdate(UUID taskId);                      // PUTAWAY_TASK_NOT_FOUND
      PutawayTaskView recordPutaway(UUID taskId, int units, int leftBefore, UUID targetLocationId);
      List<PutawayTaskView> tasksOfReceipt(UUID goodsReceiptId);       // every status, oldest first
      List<PutawayTaskView> openTasks();                               // PENDING + IN_PROGRESS
      Map<UUID, Integer> doneQuantityByReceiptLine(UUID goodsReceiptId);
  }
  ```
- `open` returns the existing open task for the same `stockPackageId` (carton), or the existing task with `stockPackageId == null` for the same `goodsReceiptLineId` (loose, any status), instead of inserting a second one.
- Domain: `PutawayTask.open(UUID id, NewPutawayTask)` → PENDING; `recordPutaway(int units, int leftBefore, UUID locationId)`: refuses `PUTAWAY_TASK_DONE` when DONE, `PUTAWAY_QUANTITY_EXCEEDED` when `units > leftBefore` or `units < 1`; sets `targetLocationId`; status `leftBefore - units == 0 ? DONE : IN_PROGRESS`. A carton task (`stockPackageId != null`) must be put away in one go: `units == quantity`.

- [ ] **Step 1: Write failing unit tests** in `PutawayTaskTest`: `newTaskIsPending`; `partialPutawayIsInProgress` (quantity 100, `recordPutaway(40, 100, bin)` → IN_PROGRESS, target bin); `lastPartIsDone` (`recordPutaway(60, 60, bin2)` → DONE, target bin2); `doneRefusesMore` (→ `PUTAWAY_TASK_DONE`); `moreThanLeftRefused` (`recordPutaway(61, 60, …)` → `PUTAWAY_QUANTITY_EXCEEDED`); `cartonTaskIsWhole` (carton task quantity 980, `recordPutaway(500, 980, …)` → `PUTAWAY_QUANTITY_EXCEEDED`).
- [ ] **Step 2:** `mvn -o test -Dtest=PutawayTaskTest` → FAIL (class missing).
- [ ] **Step 3:** Implement `PutawayTask` (+ `PutawayTaskRepository` port: `save`, `findById`, `findByIdForUpdate` (native `select id … for update` then refresh, the `StockPackageRepositoryAdapter.findAllByIdForUpdate` pattern), `findOpenByStockPackage`, `findLooseOfReceiptLine`, `findByReceipt`, `findOpen`, `doneQuantityByReceiptLine`).
- [ ] **Step 4:** Unit tests PASS.
- [ ] **Step 5: Write failing `PutawayTasksIntegrationTest`** (`@IntegrationTest`, `@Import(PostgresContainer.class)`, a real confirmed receipt line from `support/ReferenceRows` / the fixture pattern of `StockPackageMoveIntegrationTest`): `openTwiceForOneCartonReturnsOneTask`, `openTwiceForOneLooseLineReturnsOneTask`, `recordPutawayPersistsStatusAndTarget`, `doneQuantityByReceiptLineCountsDoneTasksOnly` (a DONE task of 40 and an IN_PROGRESS one → `{line: 40}`).
- [ ] **Step 6:** Implement entity mapping, adapter, `PutawayTasksImpl` (`@Service @Transactional`, joins the caller's transaction; `openTasks` and `tasksOfReceipt` ordered by id).
- [ ] **Step 7:** `mvn -o test -Dtest=PutawayTaskTest,PutawayTasksIntegrationTest,ModularityTest,MessageBundleTest` → PASS.
- [ ] **Step 8: Prepare commit** — `git add` the files above; message `feat(warehouse): putaway task aggregate and api (SCRUM-92)`.

### Task 2: Putaway candidates from the warehouse map

**Files:**
- Create: `src/main/java/com/stockflow/warehouse/api/PutawayCandidate.java`
- Modify: `src/main/java/com/stockflow/warehouse/api/WarehouseService.java`, `internal/service/WarehouseServiceImpl.java`
- Modify: `src/main/java/com/stockflow/warehouse/internal/repository/LocationLookupRepository.java` and its implementation `LayoutReadRepositoryAdapter.java`
- Test: `src/test/java/com/stockflow/warehouse/PutawayCandidatesIntegrationTest.java`

**Interfaces:**
- Produces:
  ```java
  public record PutawayCandidate(UUID locationId, String locationCode, Integer capacityUnits, BigDecimal maxWeightKg) {}
  // WarehouseService
  List<PutawayCandidate> putawayCandidates(UUID warehouseId, StorageLocationView.StorageClass storageClass);
  ```
- One SQL: BINs of the warehouse whose effective status is usable (bin, shelf and warehouse ACTIVE — the same rule as `EffectiveStatus.ofBin`) and whose `storage_class` equals the argument; `capacity_units`, `max_weight` as stored (null kept); ordered by `location_code`.

- [ ] **Step 1:** Failing test `PutawayCandidatesIntegrationTest`: on the demo map (`support/DemoData`), `onlyUsableBinsOfTheClass` (no AREA, no bin on a MAINTENANCE shelf, no COLD bin when asking NORMAL), `capacityAndWeightAsStored` (a bin given `capacity_units = 500`, `max_weight = 200` in the test's setup SQL comes back with them; another with nulls).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement `LocationLookupRepository.findPutawayCandidates(UUID warehouseId, StorageClass storageClass)` and `WarehouseServiceImpl.putawayCandidates`.
- [ ] **Step 4:** `mvn -o test -Dtest=PutawayCandidatesIntegrationTest,ModularityTest` → PASS.
- [ ] **Step 5: Prepare commit** — `feat(warehouse): putaway candidates of a warehouse (SCRUM-92)`.

### Task 3: Inventory stock primitives for putaway

**Files:**
- Modify: `src/main/java/com/stockflow/inventory/internal/domain/StockItem.java` (`reclassifyInPackage`)
- Modify: `src/main/java/com/stockflow/inventory/api/ReclassifyStockPackagesCommand.java` (allow `AVAILABLE`; javadoc)
- Modify: `src/main/java/com/stockflow/inventory/internal/service/StockOperationsServiceImpl.java` (split `relocate`; add `putAwayLoose`), `StockOperations.java`
- Modify: `src/main/java/com/stockflow/inventory/internal/domain/StockItemRepository.java` + `repository/StockItemRepositoryAdapter.java` + `StockItemJpaRepository.java`
- Modify: `src/main/java/com/stockflow/inventory/internal/domain/StockMovementLog.java` + `repository/StockMovementLogAdapter.java` + `StockMovementJpaRepository.java`, and the fake in `PickConsumptionServiceTest`
- Test: `StockItemTest`, `src/test/java/com/stockflow/inventory/PutawayStockIntegrationTest.java`

**Interfaces:**
- `StockItem.reclassifyInPackage(StockStatus target)`: also accepts `AVAILABLE`, from `INBOUND` only (QUARANTINE stock goes to AVAILABLE through re-inspection, not putaway).
- `ReclassifyStockPackagesCommand`: disposition `AVAILABLE`, `QUARANTINE` or `BLOCKED`.
- `relocate(...)` keeps scope, SKU lock and replay; its body after the replay becomes `private StockMove moveLayer(Sku sku, String lotNumber, Instant receivedAt, LocationId from, LocationId to, int qty, StockStatus target, StockMovement.ReferenceType referenceType, UUID referenceId, UUID actorId, String reason)`.
- `StockOperations`:
  ```java
  /** Loose INBOUND units of a receipt's lot to a bin as AVAILABLE; ledger (MOVE, PUTAWAY_TASK, taskId); no replay lookup (spec §6). */
  int putAwayLoose(Sku sku, UUID lotId, String toLocationCode, int units, UUID taskId, UUID actorId);
  ```
  Takes the SKU lock, reads `findLooseInboundByLot`, takes from rows in id order through `moveLayer` (one ledger line per row), refuses `STOCK_ITEM_NOT_FOUND` when they hold fewer than `units`. Returns `units`.
- `StockItemRepository.findLooseInboundByLot(Sku sku, UUID lotId)` → `List<StockItem>` (loose, INBOUND, that lot, id order, unlocked).
- `StockMovementLog.sumQuantityByReference(StockMovement.MovementType type, StockMovement.ReferenceType referenceType, UUID referenceId)` → `int` (0 when none).

- [ ] **Step 1:** Failing tests: `StockItemTest.inboundRowInCartonBecomesAvailable`, `quarantinedRowInCartonCannotBeMadeAvailable`; `PutawayStockIntegrationTest` (receive a 2000 line as cartons 1000 + 980 and 20 loose, fixture of `StockPackageMoveIntegrationTest`): `cartonPutAwayAsAvailableStaysUnopened` (`reclassifyStockPackages(lpn, BIN, AVAILABLE, PUTAWAY_TASK, task, …)` → carton at BIN, row AVAILABLE, unopened, same row id), `putAwayLooseTwiceUnderOneTaskMovesBoth` (`putAwayLoose(…, 12, task)` to BIN then `putAwayLoose(…, 8, task)` to BIN2 → 12 AVAILABLE at BIN, 8 at BIN2, nothing INBOUND loose left, `sumQuantityByReference(MOVE, PUTAWAY_TASK, task) == 20`), `putAwayLooseMoreThanThereIsRefused` (21 → `STOCK_ITEM_NOT_FOUND`, nothing moved).
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement the five interface items above; update the javadoc of `ReclassifyStockPackagesCommand` ("putaway as AVAILABLE is a plain move" is no longer true).
- [ ] **Step 4:** `mvn -o test -Dtest=StockItemTest,PutawayStockIntegrationTest,StockPackageMoveIntegrationTest,GoodsReceiptPackageQcIntegrationTest,PickConsumptionServiceTest` → PASS (the last three guard the `relocate` split).
- [ ] **Step 5: Prepare commit** — `feat(inventory): put away a carton or loose units as available stock (SCRUM-92)`.

### Task 4: `PutawayRanker` and bin occupancy

**Files:**
- Create: `src/main/java/com/stockflow/inventory/internal/domain/PutawayRanker.java`
- Create: `src/main/java/com/stockflow/inventory/internal/repository/BinOccupancyQuery.java`
- Test: `src/test/java/com/stockflow/inventory/internal/domain/PutawayRankerTest.java`, `src/test/java/com/stockflow/inventory/BinOccupancyQueryIntegrationTest.java`

**Interfaces:**
- Pure, no Spring:
  ```java
  public final class PutawayRanker {
      public enum Reason { SAME_LOT, SAME_SKU, EMPTY, HAS_ROOM }
      public record Bin(String locationCode, Integer capacityUnits, BigDecimal maxWeightKg) {}
      public record Occupancy(int units, BigDecimal weightKg, boolean holdsSku, boolean holdsLot) {
          public static final Occupancy EMPTY = new Occupancy(0, BigDecimal.ZERO, false, false);
      }
      /** units: what must fit (a carton's units; 1 for a loose task); unitWeightKg may be null. */
      public record Need(int units, BigDecimal unitWeightKg) {}
      public record Suggestion(String locationCode, Reason reason, Integer freeUnits, BigDecimal freeWeightKg) {}
      public static final int MAX_SUGGESTIONS = 10;
      public static List<Suggestion> rank(Need need, List<Bin> bins, Map<String, Occupancy> occupancy);
      /** The hard capacity / weight rule alone, for a bin the clerk chose (spec §6 step 4). */
      public static boolean fits(Need need, Bin bin, Occupancy occupancy);
  }
  ```
  Filters: capacity when `capacityUnits != null` (`capacityUnits - units >= need.units`); weight when `maxWeightKg != null && unitWeightKg != null` (`weightKg + need.units × unitWeightKg <= maxWeightKg`). Order: reason rank (SAME_LOT, SAME_SKU, EMPTY, HAS_ROOM), then larger `freeUnits` first (null = unlimited, first), then `locationCode`. `freeUnits` / `freeWeightKg` null when not recorded.
- `BinOccupancyQuery` (`@Repository`, JdbcTemplate, inventory schema only):
  `Map<String, PutawayRanker.Occupancy> occupancyOf(Collection<String> locationCodes, String sku, String lotNumber)` — one grouped query over `inventory.stock_item` joined with `inventory.inventory_items` (`on_hand × weight_kg`, null weight counted as 0); `holdsSku` / `holdsLot` true when a row of that SKU / SKU + lot number with `on_hand > 0` is there. Absent codes are empty bins.

- [ ] **Step 1:** Failing `PutawayRankerTest`: `areaAndFullBinsAreDropped` (capacity 500 with 495 held, need 10 → absent), `nullCapacityIsUnlimited`, `weightFilterOnlyWhenBothKnown`, `ranksSameLotThenSameSkuThenEmptyThenOther`, `moreRoomFirstWithinARank`, `atMostTen` (15 bins → 10), `fitsMatchesTheFilter`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5:** Failing `BinOccupancyQueryIntegrationTest`: two SKUs in one bin → units summed, weight = Σ on_hand × weight_kg, `holdsSku`/`holdsLot` for the asked SKU and lot; an empty bin absent. Implement; PASS.
- [ ] **Step 6: Prepare commit** — `feat(inventory): rank putaway bins after hard filters (SCRUM-92)`.

### Task 5: Opening tasks from `ReceiptStockReadyForPutaway`

**Files:**
- Create: `src/main/java/com/stockflow/inventory/internal/service/PutawayTaskCreator.java`
- Modify: `src/main/java/com/stockflow/inventory/internal/domain/StockPackageRepository.java` + adapter: `Map<StockPackageId, Integer> inboundOnHandOf(Collection<StockPackageId> ids)` (sum of INBOUND rows only)
- Test: `src/test/java/com/stockflow/inventory/PutawayTaskCreatorIntegrationTest.java`

**Interfaces:**
- Consumes: `PutawayTasks.open` (Task 1), `StockPackageRepository.findByReceiptLines`, `StockMovementLog.findLooseByReference`.
- `@ApplicationModuleListener public void on(ReceiptStockReadyForPutaway event)`: per line — cartons of the line that are IN_STOCK at `line.locationCode()` with `inboundOnHandOf > 0` → `open(new NewPutawayTask(receiptId, lineId, sku, units, cartonId))`; loose = `line.quantity() − Σ those units`, opened with `stockPackageId = null` when > 0. Spec §4.

- [ ] **Step 1:** Failing tests (drive the receipt through `GoodsReceipts` as `GoodsReceiptPackageQcIntegrationTest` does; await the async listener with `support/Await`): `twoStepLineOpensATaskPerCartonAndOneLoose` (1000 + 980 + 20 loose, no QC → 3 tasks: 1000/carton1, 980/carton2, 20/loose); `threeStepOpensTasksForTheAcceptedPartOnly` (quarantine carton 1 → tasks for carton 2 and the loose part only); `redeliveredEventOpensNothingNew` (call `creator.on(sameEvent)` again directly → still 3 tasks).
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** `mvn -o test -Dtest=PutawayTaskCreatorIntegrationTest,ModularityTest` → PASS.
- [ ] **Step 5: Prepare commit** — `feat(inventory): open putaway tasks when received goods are ready (SCRUM-92)`.

### Task 6: Reading tasks — `PutawayService` and the GET endpoints

**Files:**
- Create: `src/main/java/com/stockflow/inventory/internal/service/PutawayService.java`
- Create: `src/main/java/com/stockflow/inventory/internal/controller/PutawayController.java`, `dto/PutawayTaskResponse.java`, `dto/PutawaySuggestionResponse.java`
- Test: `src/test/java/com/stockflow/inventory/internal/controller/PutawayHttpTest.java` (GET part)

**Interfaces:**
- `PutawayService` (`@Service @Transactional(readOnly = true)` for reads):
  ```java
  public record TaskView(UUID id, UUID goodsReceiptId, UUID goodsReceiptLineId, String sku, String lotNumber,
                         int quantity, int left, String sourceLocationCode, String lpn, String status,
                         String targetLocationCode, List<PutawayRanker.Suggestion> suggestions) {}
  TaskView get(UUID taskId);                                   // suggestions filled; 404 PUTAWAY_TASK_NOT_FOUND
  PageResponse<TaskView> list(UUID warehouseId, UUID receiptId, String status, int page, int size); // suggestions empty
  ```
  Source of a task (spec §4, §8): carton → the carton's location; loose → the location of the first loose INBOUND row of the lot from `findLooseByReference(RECEIPT, GOODS_RECEIPT_LINE, lineId).lotId()`, else the target location (DONE). `left = quantity − sumQuantityByReference(MOVE, PUTAWAY_TASK, id)`. Scope: `WarehouseScope.requireLocation(source)` per task; list with neither `warehouseId` nor `receiptId` → `VALIDATION_FAILED`.
- Suggestions: `WarehouseService.putawayCandidates(warehouseOf(source), item storage class)` → `BinOccupancyQuery.occupancyOf` → `PutawayRanker.rank(new Need(carton ? units : 1, item weightKg), …)`. Item via `InventoryPolicyRepository.findBySkus` (`ItemRow.logistics()`).
- Controller `@RequestMapping("/api/v1/putaway-tasks")`, `@PermissionResource(code = "warehouse-putaway-tasks", group = "Warehouse", label = "Putaway tasks", route = "/warehouse/putaway-tasks", apiPath = "/api/v1/putaway-tasks", actions = {VIEW_PAGE, READ, UPDATE})`; `GET` list and `GET /{id}`.

- [ ] **Step 1:** Failing `PutawayHttpTest` GET cases (`support/SecuredMvc`, roles as in `StockPackageLabelHttpTest`): `staffSeeTaskWithSuggestions` (a carton task → `suggestions[0].reason` present, at most 10), `listNeedsReceiptOrWarehouse` (400 `VALIDATION_FAILED`), `taskOfAnotherWarehouseIs403`, `unknownTaskIs404`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS with `ModularityTest,ArchitectureTest`.
- [ ] **Step 5: Prepare commit** — `feat(inventory): read putaway tasks with bin suggestions (SCRUM-92)`.

### Task 7: Confirming a putaway and `PutawayCompleted`

**Files:**
- Modify: `PutawayService.java` (`confirm`), `PutawayController.java` (`POST /{id}/confirmations`), create `dto/PutawayConfirmationRequest.java`
- Create: `src/main/java/com/stockflow/contracts/PutawayCompleted.java`
- Add `PUTAWAY_LPN_MISMATCH` (409), `PUTAWAY_TARGET_NOT_BIN` (422), `LOCATION_STORAGE_CLASS_MISMATCH` (409), `LOCATION_CAPACITY_EXCEEDED` (409) to `ErrorCode` + both bundles
- Test: `src/test/java/com/stockflow/inventory/PutawayConfirmationIntegrationTest.java`, `PutawayHttpTest` (POST part)

**Interfaces:**
- `public record PutawayCompleted(UUID goodsReceiptId, UUID goodsReceiptLineId, UUID putawayTaskId) {}` (contracts; javadoc: published by inventory when a task becomes DONE, consumed by procurement).
- `PutawayService.confirm(UUID taskId, String locationCode, String lpn, Integer quantity, UUID actorId)` → `TaskView`, `@Transactional`, steps of spec §6 in order: `PutawayTasks.lockForUpdate` → scope on source → DONE check → scan (`lpn` for carton via `StockPackageView.normalLpn`, `quantity` for loose; the other field present → `VALIDATION_FAILED`) → target checks (`WarehouseService.findLocation`; BIN; same warehouse; usable; class; `PutawayRanker.fits`) → `reclassifyStockPackages(AVAILABLE, PUTAWAY_TASK, taskId)` or `putAwayLoose` → `PutawayTasks.recordPutaway(taskId, units, left, targetId)` → publish `PutawayCompleted` when DONE.
- Request: `record PutawayConfirmationRequest(@NotBlank @Size(max = 64) String locationCode, @Size(max = 30) String lpn, @Positive Integer quantity)`.

- [ ] **Step 1:** Failing `PutawayConfirmationIntegrationTest`: `cartonConfirmedMovesItWholeAsAvailable` (one ledger line `(MOVE, PUTAWAY_TASK)` naming the carton both sides, task DONE, one `PutawayCompleted` via `support/RecordedEvents`); `looseConfirmedInTwoBins` (12 then 8 → DONE after the second, one event); `looseMoreThanLeftRefused` (`PUTAWAY_QUANTITY_EXCEEDED`); `confirmAfterDoneRefused` (`PUTAWAY_TASK_DONE`); `wrongLpnRefused` (`PUTAWAY_LPN_MISMATCH`); one test per hard rule on a chosen bin: `areaRefused` (`PUTAWAY_TARGET_NOT_BIN`), `otherWarehouseRefused` (`MOVE_ACROSS_WAREHOUSES`), `unusableBinRefused` (`LOCATION_NOT_USABLE`), `wrongClassRefused`, `capacityRefused`, `weightRefused`; `atpGrowsByThePutaway` (`InventoryService.availableToPromise` before/after = +units).
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5:** Failing `PutawayHttpTest` POST cases: `staffConfirm` (200, body status DONE), `readOnlyRoleGets403`, `doneTaskOfAnotherWarehouseIs403` (403, not 409). Implement controller; PASS with `ModularityTest,ArchitectureTest,MessageBundleTest`.
- [ ] **Step 6: Prepare commit** — `feat(inventory): confirm a putaway by scan in one transaction (SCRUM-92)`.

### Task 8: Closing the receipt (procurement)

**Files:**
- Modify: `src/main/java/com/stockflow/procurement/internal/domain/GoodsReceipt.java` (add `closeIfPutAway`)
- Create: `src/main/java/com/stockflow/procurement/internal/service/PutawayCompletedListener.java`
- Modify: `src/main/java/com/stockflow/procurement/internal/service/GoodsReceiptServiceImpl.java` (`inspect`: try closing after `receipt.inspect` when IN_PUTAWAY)
- Test: `GoodsReceiptTest`, `src/test/java/com/stockflow/procurement/GoodsReceiptPutawayCloseIntegrationTest.java`

**Interfaces:**
- `public boolean closeIfPutAway(Map<UUID, Integer> doneByLine, Instant now)` on `GoodsReceipt`: when `IN_PUTAWAY` and every line with `quantityForPutaway() > 0` has `doneByLine.getOrDefault(id, 0) >= quantityForPutaway()` → CLOSED, `closedAt = now`, returns true; otherwise unchanged, false. Javadoc: why quantities and not "no open task" (spec §7).
- Listener: `@ApplicationModuleListener public void on(PutawayCompleted event)` → load receipt, `closeIfPutAway(putawayTasks.doneQuantityByReceiptLine(receiptId), clock.instant())`, save when true; a receipt not IN_PUTAWAY is left alone.

- [ ] **Step 1:** Failing `GoodsReceiptTest`: `closesWhenEveryLineIsPutAway`, `staysWhileALineIsShort`, `linesWithNothingToPutAwayDoNotHoldItOpen` (a fully rejected QC line), `notInPutawayIsLeftAlone`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5:** Failing `GoodsReceiptPutawayCloseIntegrationTest`: `twoStepReceiptClosesAfterItsLastTask` (all tasks confirmed → receipt CLOSED, awaited); `mixedReceiptClosesWhenLastQcLineAcceptsNothing` (non-QC line put away while IN_QC, then QC rejects everything on the QC line → CLOSED at `inspect`). Implement listener and the `inspect` hook; PASS with `ModularityTest`.
- [ ] **Step 6: Prepare commit** — `feat(procurement): close a receipt once its goods are put away (SCRUM-92)`.

### Task 9: Docs and the full build

**Files:**
- Modify: `CLAUDE.md` §4 (a "Putaway (SCRUM-92)" paragraph: where it lives, lock order task → SKU → carton → rows, why putaway skips the reference replay, why confirmation is synchronous)
- Modify: `docs/business-design/04c-all-flows.md` `F-PUT` (Vietnamese note: xác nhận đồng bộ trong inventory; đợt A chưa lưu người được giao và lý do gợi ý)

- [ ] **Step 1:** Write both doc changes.
- [ ] **Step 2:** `PYTHONUTF8=1 python3 tools/verify.py` → "all static checks passed".
- [ ] **Step 3:** `mvn -o verify -Djacoco.skip=true` (background, no edits meanwhile) → BUILD SUCCESS; report the test count.
- [ ] **Step 4: Prepare commit** — `docs: putaway in the working notes and F-PUT (SCRUM-92)`; then ask the user before committing, pushing or opening the PR (stacked on #197: base `feature/SCRUM-473-receive-in-cartons`).
