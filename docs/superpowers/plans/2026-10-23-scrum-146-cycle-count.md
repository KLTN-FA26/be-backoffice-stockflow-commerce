# SCRUM-146 Cycle Count Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Managers plan blind cycle counts by zone/shelf/bin/SKU, staff count loose stock and LPN cartons, and submitting a count turns every variance into a four-eyes `COUNT_VARIANCE` stock adjustment (SCRUM-146, 162, 164).

**Architecture:** A `CycleCount` aggregate in `inventory/internal/domain` replaces the `Recounts` record port; it is persisted with JPA, its special queries (slot claims, the SHORT_PICK freeze, scope generation, book quantities) stay native SQL in one adapter. Submission calls a new `StockOperations.requestCountVariance`; approval stays on the existing adjustment endpoint and gains a carton path. `warehouse :: api` gains two read methods.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Modulith 1.4, Spring Data JPA + `NamedParameterJdbcTemplate`, PostgreSQL 16, JUnit 5 + AssertJ + Testcontainers (`@IntegrationTest`, `@SecuredIntegrationTest`).

**Spec:** `docs/superpowers/specs/2026-10-23-scrum-146-cycle-count-design.md` — read it first; section numbers below (§n) point into it.

## Global Constraints

- Branch `feature/SCRUM-146-cycle-count` from `develop` at `816813cd` or later. Never `git commit`/`git push` without the owner's go-ahead (CLAUDE.md §7); the commit steps below run only once that go-ahead is given for the branch.
- **No migration.** The tables are complete (`V20260928005000`, `V20261022000100`). The one grant (`inventory-cycle-counts:UPDATE` for `WAREHOUSE_STAFF`, §8) is Tú's; tests must not depend on it.
- Statuses stay `PLANNED / IN_PROGRESS / COMPLETED / CANCELLED`; triggers stay the CHECK's five. `EnumType.STRING`. Ids `Identifiers.newId()`. Count numbers `CC-yyyyMMdd-NNNN` from `platform.document_sequence` type `CC` (as `RecountsAdapter.openShortPick` does today).
- Lock order everywhere: cycle-count row `FOR UPDATE` → `policies.lock(sku)` (SKU advisory) → `stock_package FOR UPDATE` (id order) → stock rows by id. Slot claims use `pg_advisory_xact_lock(hashtextextended(key, 703))`, key `"<location>|<sku>|<lot or empty>"` for loose, `"pkg|<stockPackageId>"` for a carton, taken in sorted key order.
- Max lines per planned count: property `stockflow.inventory.cycle-count-max-lines`, default `500` (add under `stockflow.inventory` in `application.yml`, read with `@Value("${stockflow.inventory.cycle-count-max-lines:500}")`).
- New error codes in `ErrorCode` (status in brackets) with `error.<CODE>` lines in `i18n/messages.properties` and `messages_vi.properties`: `CYCLE_COUNT_EMPTY_SCOPE` (422), `CYCLE_COUNT_TOO_LARGE` (422), `CYCLE_COUNT_NOT_ASSIGNEE` (403), `CYCLE_COUNT_LINE_NOT_COUNTED` (409), `CYCLE_COUNT_SLOT_BUSY` (409), `CYCLE_COUNT_UNKNOWN_LAYER` (422), `CYCLE_COUNT_STALE` (409), `STOCK_PACKAGE_WRONG_LOCATION` (422), `STOCK_PACKAGE_OVERFILLED` (422).
- Blind rule: a caller without `inventory-cycle-counts:APPROVE` never sees `expectedQty`, the count's note, or a line's adjustments on a blind count — before or after submission.
- Identifiers, code and javadoc in English; javadoc explains why. Vietnamese only in `messages_vi.properties` and `docs/business-design/**`.
- Owner's test policy (memory: SDD speed): implementers write code **and** tests but run **no tests** during Tasks 1–4 — only `mvn -q test-compile`. Reviewers read the diff. Every test runs once, in Task 5.

## Review Focus

1. **A submit sent twice** (double tap, retry after a timeout) must not raise a second set of adjustments — Task 4 test `submitIsIdempotent`.
2. **Stock picked between counting a line and submitting** so the shortage exceeds what the slot holds now — must refuse with `CYCLE_COUNT_STALE`, not post a negative layer — Task 4 test `shortageBeyondCurrentStockIsStale`.
3. **The counter reopening a submitted blind count** must still not see book quantities, notes or adjustment deltas — Task 4 test `counterNeverSeesTheBooks`.
4. **A carton counted above its received quantity** must be refused before it reaches the commit-time trigger (which would surface as a 500) — Task 4 test `cartonAboveReceivedIsRefused`.
5. **Planning over a slot an open SHORT_PICK recount already holds** must skip it and list it, never create a second open count for the slot — Task 4 test `planSkipsBusySlots`.

---

### Task 1: Domain — `CycleCount` aggregate, variance split, error codes

**Files:**
- Create: `src/main/java/com/stockflow/inventory/internal/domain/CycleCount.java`, `CycleCountLine.java`, `CycleCountStatus.java`, `CycleCountTrigger.java`, `CountSlot.java`, `CountVariance.java`, `CycleCountRepository.java`
- Modify: `src/main/java/com/stockflow/common/error/ErrorCode.java`, `src/main/resources/i18n/messages.properties`, `messages_vi.properties`, `src/main/resources/application.yml`
- Test: `src/test/java/com/stockflow/inventory/internal/domain/CycleCountTest.java`, `CountVarianceTest.java`

**Interfaces:**
- Produces (all in `com.stockflow.inventory.internal.domain`):
  - `enum CycleCountStatus { PLANNED, IN_PROGRESS, COMPLETED, CANCELLED; boolean isOpen() }` — open = PLANNED or IN_PROGRESS.
  - `enum CycleCountTrigger { SCHEDULED, SHORT_PICK, MOVE_DISCREPANCY, TRANSFER_DISCREPANCY, MANUAL }`
  - `record CountSlot(LocationId location, Sku sku, String lotNumber, StockPackageId stockPackageId)` — blank lot normalised to null; `boolean isCarton()`; `String lockKey()` (formats in Global Constraints); `static CountSlot loose(LocationId, Sku, String lot)`, `static CountSlot carton(LocationId, Sku, String lot, StockPackageId)`.
  - `record CountLineDraft(CountSlot slot, int expectedQty)` (nested in `CycleCount`).
  - `final class CycleCountLine` — `UUID id()`, `CountSlot slot()`, `int expectedQty()`, `Integer countedQty()`, `UUID countedBy()`, `Instant countedAt()`, `boolean recountRequired()`, `boolean isCounted()`, `Integer delta()` (null until counted); package-private mutators used by `CycleCount`.
  - `final class CycleCount extends AggregateRoot` with:
    - `static CycleCount plan(String number, UUID warehouseId, CycleCountTrigger trigger, boolean blind, UUID assignedUserId, LocalDate plannedDate, String note, List<CountLineDraft> lines)` — empty `lines` → `CYCLE_COUNT_EMPTY_SCOPE`; duplicate slot → `IllegalArgumentException`.
    - `static CycleCount shortPick(String number, UUID warehouseId, CountSlot slot, int expectedQty, String note, LocalDate day)` — blind, `SHORT_PICK`, PLANNED for `day`, no assignee.
    - a rehydrating constructor taking every field plus `List<CycleCountLine>` and `long version`.
    - `void assign(UUID userId)` — PLANNED only, else `BusinessException(CONFLICT)`.
    - `void requireCounter(UUID userId, boolean mayDecide)` — assigned to someone else and `!mayDecide` → `CYCLE_COUNT_NOT_ASSIGNEE`.
    - `CycleCountLine count(UUID lineId, int countedQty, int bookQty, UUID by, Instant at)` — closed → `CYCLE_COUNT_NOT_OPEN`; unknown line → `NOT_FOUND`; negative → `VALIDATION_FAILED`; overwrites `expectedQty` with `bookQty` (§5, D3); first count sets `IN_PROGRESS` and `startedAt`.
    - `CycleCountLine addLine(CountSlot slot, int countedQty, int bookQty, UUID by, Instant at)` — slot already on this count → `CYCLE_COUNT_SLOT_BUSY`; otherwise as `count`.
    - `boolean submit(Instant at)` — `COMPLETED` → returns `false` (idempotent); `CANCELLED` → `CYCLE_COUNT_NOT_OPEN`; any line uncounted → `CYCLE_COUNT_LINE_NOT_COUNTED` (message lists the line's location/LPN); else `COMPLETED`, `completedAt`, returns `true`.
    - `boolean cancel(String reason, Instant at)` — `CANCELLED` → `false`; `COMPLETED` → `CYCLE_COUNT_NOT_OPEN`; appends `"Cancelled: " + reason` to the note with `" | "` separator, truncated to 1000 (today's SQL behaviour, moved here).
    - `List<CountSlot> markRecount(Collection<UUID> lineIds)` — COMPLETED only (else `CYCLE_COUNT_NOT_OPEN`); sets `recountRequired`; returns the slots.
    - getters: `id, number, warehouseId, trigger, status, blind, assignedUserId, plannedDate, startedAt, completedAt, note, createdAt, lines, version`.
  - `final class CountVariance` (no Spring): `record Layer(StockItemId id, Instant receivedAt, LocalDate expiryDate, int onHand)`, `record Share(StockItemId id, Instant receivedAt, int delta)`, `static List<Share> split(int delta, List<Layer> layers)`.
  - `interface CycleCountRepository` — declared here, implemented in Task 2:
    ```java
    Optional<CycleCount> findById(UUID id);
    Optional<CycleCount> findByIdForUpdate(UUID id);
    CycleCount save(CycleCount count);
    String nextNumber(LocalDate day);
    Optional<CycleCount> findOpenFor(CountSlot slot);                 // takes the slot's advisory lock (seed 703)
    Map<CountSlot, String> claim(Collection<CountSlot> slots);         // locks all, sorted; returns busy slot -> count number
    List<CountLineDraft> stockIn(String warehousePrefix, Collection<String> locationCodes,
                                 Collection<String> locationPrefixes, Collection<String> skus);
    int bookQuantity(CountSlot slot);                                  // committed on_hand, read FOR SHARE
    List<CycleCount> find(CycleCountStatus status, Set<UUID> warehouses, long offset, int limit);
    long count(CycleCountStatus status, Set<UUID> warehouses);
    Map<UUID, List<LineAdjustment>> adjustmentsOf(Collection<UUID> lineIds);
    Map<UUID, String> lpnsOf(Collection<StockPackageId> ids);
    List<Frozen> frozen(Collection<String> skus, LocalDate today);
    record LineAdjustment(UUID id, String number, int quantityDelta, String status) { }
    record Frozen(UUID stockItemId, String sku, String locationCode, long free) { }
    ```

- [ ] **Step 1: Write `CycleCountTest`** — one `@Test` per rule, AssertJ, no Spring:
  - `planRefusesAnEmptyScope` → `CYCLE_COUNT_EMPTY_SCOPE`
  - `firstCountStartsTheCountAndOverwritesTheBook` — plan with expected 10, `count(line, 7, 9, u, t)` → status `IN_PROGRESS`, `startedAt == t`, `expectedQty == 9`, `delta == -2`
  - `recountingALineOverwritesEverything` — second `count(line, 9, 9, u2, t2)` → `countedBy == u2`, `delta == 0`
  - `submitNeedsEveryLineCounted` → `CYCLE_COUNT_LINE_NOT_COUNTED`
  - `submitIsIdempotentOnTheAggregate` — first `true`, second `false`, status `COMPLETED`
  - `cancelledCountRefusesCounting` → `CYCLE_COUNT_NOT_OPEN`
  - `cancelAppendsTheReason` — note `"found 1"` → `"found 1 | Cancelled: Bin checked"`; a 1000-char note stays 1000 long
  - `onlyTheAssigneeCountsUnlessTheyMayDecide` — other user → `CYCLE_COUNT_NOT_ASSIGNEE`; same user with `mayDecide=true` passes
  - `addLineRefusesASlotAlreadyOnTheCount` → `CYCLE_COUNT_SLOT_BUSY`
  - `assignOnlyWhilePlanned`
  - `markRecountOnlyOnACompletedCount`
- [ ] **Step 2: Write `CountVarianceTest`:**
  - `surplusGoesToTheNewestLayer` — layers received t1 (10), t2 (5); `split(3, …)` → one share `(t2, +3)`
  - `shortageFollowsFefo` — expiry 2026-11-01 (on hand 2), 2026-12-01 (on hand 10), null expiry (4); `split(-5, …)` → `[(Nov, -2), (Dec, -3)]`
  - `shortageCappedPerLayer` — never a share whose `-delta` exceeds its layer's `onHand`
  - `shortageBeyondEveryLayerIsStale` — layers totalling 3, `split(-5, …)` → `BusinessException` `CYCLE_COUNT_STALE`
  - `zeroDeltaHasNoShares` → empty list
  - `surplusWithNoLayerIsUnknown` → `CYCLE_COUNT_UNKNOWN_LAYER`
- [ ] **Step 3: Implement the types listed in Interfaces**, plus the nine error codes, their en/vi messages and the `cycle-count-max-lines` property (Global Constraints). FEFO order in `split`: expiry ascending, nulls last, then `receivedAt` ascending, nulls first; surplus: latest `receivedAt`, nulls oldest.
- [ ] **Step 4: Compile** — Run: `mvn -q test-compile` · Expected: exit 0, no output.
- [ ] **Step 5: Commit** — `git add` the files above; `git commit -m "feat(inventory): cycle count aggregate and variance split (SCRUM-146)"`

---

### Task 2: Persistence — JPA mapping, one adapter, `Recounts` retired

**Files:**
- Create: `inventory/internal/entity/CycleCountJpaEntity.java`, `CycleCountLineJpaEntity.java`; `inventory/internal/repository/CycleCountJpaRepository.java`, `CycleCountPersistenceMapper.java`, `CycleCountRepositoryAdapter.java`
- Delete: `inventory/internal/domain/Recounts.java`, `inventory/internal/repository/RecountsAdapter.java`
- Modify: `inventory/internal/service/PickConsumptionService.java:74-79,224-244`, `InventoryServiceImpl.java:104-114,299-305,386`, `CycleCountService.java`, `CycleCounts.java`, `inventory/internal/controller/CycleCountController.java:96-101`; test `inventory/internal/service/PickConsumptionServiceTest.java:100`
- Test: kept green unchanged — `SkuReservationAllocationIntegrationTest.shortPickOpensOneBlindRecount`, `PickConfirmationIntegrationTest`

**Interfaces:**
- Consumes: Task 1's `CycleCountRepository`, `CycleCount`, `CountSlot`.
- Produces: `CycleCountRepositoryAdapter implements CycleCountRepository` (`@Repository`, package-private); and in `inventory/internal/service`:
  ```java
  public record CycleCountView(UUID id, String number, UUID warehouseId, String triggerType, String status,
          boolean blind, UUID assignedUserId, LocalDate plannedDate, Instant startedAt, Instant completedAt,
          String note, Instant createdAt, List<Line> lines) {
      public record Line(UUID id, String locationCode, String sku, String lotNumber, UUID stockPackageId,
              String lpn, Integer expectedQty, Integer countedQty, UUID countedBy, Instant countedAt,
              boolean recountRequired, List<CycleCountRepository.LineAdjustment> adjustments) { }
  }
  ```
  `CycleCounts.list(...)` and `cancel(...)` keep their parameters and now return `CycleCountView`.

- [ ] **Step 1: Map the tables.** `CycleCountJpaEntity extends BaseEntity` on `inventory.cycle_count` (columns per `V20260928005000`), `@OneToMany(mappedBy, cascade = ALL, orphanRemoval = true, fetch = LAZY)` lines, like `TransferOrderJpaEntity`; `CycleCountLineJpaEntity` on `inventory.cycle_count_line` including `stock_package_id`. `jpa-orm-review` skill applies: one fetch of the lines per load (`join fetch`), no N+1 in `find`.
- [ ] **Step 2: Implement `CycleCountRepositoryAdapter`.** `findById / findByIdForUpdate / save` through JPA + mapper (`findByIdForUpdate` is `PESSIMISTIC_WRITE` then `entityManager.refresh`, as `findByIdForUpdate` in `StockItemRepositoryAdapter`). Native SQL through `NamedParameterJdbcTemplate` for:
  - `nextNumber` — the `document_sequence` upsert now in `RecountsAdapter.openShortPick`.
  - `findOpenFor` — today's query, extended to carton slots (`stock_package_id = :pkg`), loose slots matching only `stock_package_id IS NULL`.
  - `claim` — sorted advisory locks, then one query for slots on PLANNED/IN_PROGRESS counts.
  - `stockIn` — loose: `SELECT location_code, sku, lot_number, sum(on_hand) … WHERE stock_package_id IS NULL GROUP BY … HAVING sum(on_hand) > 0`; cartons: `stock_package` `status = 'IN_STOCK'` with their balance from `inventory.stock_package_balance`. Location filter: `location_code IN (:codes) OR location_code LIKE ANY(:prefixes)`; no location criterion → `location_code LIKE :warehousePrefix || '-%'`; `skus` non-empty → `AND sku IN (:skus)`.
  - `bookQuantity` — loose: sum of the slot's loose rows' `on_hand` `FOR SHARE`; carton: sum over the carton's rows `FOR SHARE`.
  - `adjustmentsOf`, `lpnsOf`, `find`, `count` — `find/count` keep `RecountsAdapter.where`'s "empty warehouse set → no query" rule.
  - `frozen` — today's query `OR` (count `COMPLETED` and an `inventory.stock_adjustment` with `reason_code = 'COUNT_VARIANCE'`, `status = 'PENDING_APPROVAL'`, `cycle_count_line_id = l.id`) — §6 D7.
- [ ] **Step 3: Move callers onto the port.** `PickConsumptionService.requestRecount`: `findOpenFor(CountSlot.loose(…))`, else `CycleCount.shortPick(repo.nextNumber(day), …)` + `save`. `InventoryServiceImpl` uses `CycleCountRepository.Frozen`. `CycleCountService.list/cancel` load aggregates, build `CycleCountView` (lpns and adjustments in one batch each), and blind them as today plus hide `adjustments`. `cancel` keeps its idempotent CANCELLED return. Delete `Recounts` and `RecountsAdapter`; fix the javadoc that said counting is not built.
- [ ] **Step 4: Compile** — `mvn -q test-compile` · Expected: exit 0.
- [ ] **Step 5: Commit** — `git commit -m "refactor(inventory): cycle counts as a JPA aggregate; the short-pick freeze holds until the variance is decided (SCRUM-146)"`

---

### Task 3: Count variances in the SCRUM-145 adjustment mechanism, with cartons

**Files:**
- Modify: `inventory/internal/domain/StockAdjustment.java`, `inventory/internal/entity/StockAdjustmentJpaEntity.java`, `inventory/internal/repository/StockAdjustmentRepositoryAdapter.java`, `inventory/internal/domain/StockMovement.java`, `inventory/internal/service/StockOperations.java`, `StockOperationsServiceImpl.java:860-920,1042-1047`, `inventory/api/StockAdjustmentSummary.java`, `inventory/internal/controller/dto/StockAdjustmentResponse.java` (+ its mapper)
- Test: `src/test/java/com/stockflow/inventory/internal/domain/StockMoveAndAdjustTest.java` (extend), `src/test/java/com/stockflow/inventory/CountVarianceAdjustmentIntegrationTest.java` (new, `@IntegrationTest`)

**Interfaces:**
- Consumes: Task 1 `CountSlot`, `CountVariance`.
- Produces:
  - `AdjustmentReason` and `api.StockAdjustmentReason` gain `COUNT_VARIANCE` (rewrite `AdjustmentReason`'s javadoc, which says it is deliberately absent: it is now present and only a count may use it).
  - `StockAdjustment`: `StockAdjustment forCountLine(UUID cycleCountLineId)`, `UUID cycleCountLineId()`, `StockAdjustment inPackage(StockPackageId id)`, `StockPackageId stockPackageId()` (set-once withers like `atLayer`).
  - `requestAdjustment` (the manual path) refuses `COUNT_VARIANCE` with `VALIDATION_FAILED` ("a count variance comes from a cycle count") — otherwise `ck_stock_adjustment_count_variance` would answer a typed-in request with a 500.
  - `StockMovement arriving(StockPackageId)` — the `toStockPackage` twin of `leaving`.
  - `StockAdjustmentSummary` gains trailing components `UUID stockPackageId, UUID cycleCountLineId`; `StockAdjustmentResponse` exposes both.
  - In `StockOperations`:
    ```java
    /** Submission's side of SCRUM-164: the variance of one counted line as pending COUNT_VARIANCE adjustment(s). */
    List<StockAdjustmentSummary> requestCountVariance(UUID cycleCountLineId, CountSlot slot, int delta,
                                                      UUID requestedBy, String note);
    ```

- [ ] **Step 1: Extend `StockMoveAndAdjustTest`:** `countVarianceNamesItsLine` (`forCountLine(x).cycleCountLineId() == x`), `cartonAdjustmentKeepsItsCarton` (`inPackage(p).stockPackageId() == p`).
- [ ] **Step 2: Write `CountVarianceAdjustmentIntegrationTest`** (fixtures as in `StockPackageConsumptionIntegrationTest` / `StockOperationsIntegrationTest`; two different users request and approve):
  - `manualAdjustmentCannotClaimACountVariance` — `requestAdjustment` with `COUNT_VARIANCE` → `VALIDATION_FAILED`, no row written
  - `looseShortageSplitsFefoAndPostsOnApproval` — two receipts of one lot in one bin, on hand 2 (earlier expiry) and 10, `requestCountVariance(line, slot, -5, …)` → two PENDING adjustments `-2`, `-3`, `reason COUNT_VARIANCE`, `cycleCountLineId == line`; approving both → `on_hand` 0 and 7, two `ADJUSTMENT` ledger rows
  - `looseSurplusOnTheNewestLayer`
  - `cartonNotFoundGoesLost` — unopened carton of 50, delta `-50` → one adjustment with `stockPackageId`; approval → rows 0, carton `LOST`, ledger row names the carton (`from_stock_package_id`)
  - `openedCartonWrittenDownStaysOpen` — opened carton 30, delta `-4` → carton `IN_STOCK`, `unopened = false`, rows 26
  - `lostCartonFoundAgain` — LOST carton, delta `+20` → carton `IN_STOCK`, rows 20, ledger `to_stock_package_id` set
  - `shortageIntoAHoldIsRefusedAtApproval` — 10 on hand, 8 reserved, delta `-5` → approval throws `InsufficientStockException`; nothing posted; reservation untouched
  - `requesterCannotApprove` → `ADJUSTMENT_SELF_APPROVAL`
- [ ] **Step 3: Implement.** `requestCountVariance`: `WarehouseScope.requireLocation`, `policies.lock(sku)`; loose → load `findLooseBySkuAndLocation`, filter the slot's lot, `CountVariance.split`, one `StockAdjustment.request(...).atLayer(share.receivedAt()).forCountLine(line)` per share; carton → one adjustment `.inPackage(id).forCountLine(line)`. `approve`: when `stockPackageId() != null` take the carton path of spec §6 (lock order in Global Constraints; reuse `StockPackageContents` for locking and `StockPackageRepository.save`; `found()` before a write-up; after a write-down `lose()` if left 0 and reason `COUNT_VARIANCE`, `consume()` if left 0 otherwise, `open()` otherwise); loose path unchanged. `StockAdjustmentRepositoryAdapter` and the entity carry `stock_package_id` both ways; `cycle_count_line_id` goes from domain to entity and back.
- [ ] **Step 4: Compile** — `mvn -q test-compile` · Expected: exit 0.
- [ ] **Step 5: Commit** — `git commit -m "feat(inventory): count variances and carton adjustments through the four-eyes adjustment (SCRUM-164)"`

---

### Task 4: Planning, counting, submission, recount — service, warehouse api, HTTP

**Files:**
- Modify: `warehouse/api/WarehouseService.java`, `warehouse/internal/service/WarehouseServiceImpl.java`, `warehouse/internal/repository/LocationLookupRepository.java`, `LayoutReadRepositoryAdapter.java`; `inventory/internal/service/CycleCounts.java`, `CycleCountService.java`; `inventory/internal/controller/CycleCountController.java`
- Create: `inventory/internal/controller/dto/PlanCycleCountRequest.java`, `CountLineRequest.java`, `AddCountLineRequest.java`, `RecountRequest.java`, `AssignCycleCountRequest.java`, `CycleCountResponse.java`, `PlannedCycleCountResponse.java` (move the two nested records out of the controller)
- Test: `src/test/java/com/stockflow/inventory/CycleCountIntegrationTest.java` (`@IntegrationTest`), `CycleCountHttpSecurityIntegrationTest.java` (`@SecuredIntegrationTest`), `src/test/java/com/stockflow/warehouse/internal/WarehouseLocationLookupIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 aggregate/port, Task 2 `CycleCountView`, Task 3 `requestCountVariance`; `IdentityService.assignableUsers(Set<String>, UUID)`; `WarehouseDirectory.prefixOf(UUID)`.
- Produces:
  - `WarehouseService`: `List<String> shelfCodesOf(UUID zoneId)` (empty for an unknown zone) and `List<StorageLocationView> findLocations(Collection<String> locationCodes)` (unknown codes absent, any status); `LocationLookupRepository` gains `findByLocationCodes` and `findShelfCodesInZone`.
  - `CycleCounts`:
    ```java
    CycleCountView get(UUID id, boolean showExpected);
    PlannedCount plan(PlanCycleCountCommand command);
    CycleCountView assign(UUID id, UUID userId);
    CycleCountView count(UUID id, UUID lineId, Integer countedQty, Boolean present, UUID actor, boolean mayDecide);
    CycleCountView addLine(UUID id, AddCountLineCommand command);
    CycleCountView submit(UUID id, UUID actor, boolean mayDecide);
    CycleCountView recount(UUID id, List<UUID> lineIds, String note, UUID actor);
    List<AssignableUser> assignees(UUID warehouseId);
    record PlanCycleCountCommand(UUID warehouseId, LocalDate plannedDate, UUID assignedUserId, boolean blind,
            String note, List<UUID> zoneIds, List<String> shelfCodes, List<String> locationCodes, List<String> skus) { }
    record AddCountLineCommand(String locationCode, String sku, String lotNumber, String lpn, int countedQty,
            UUID actor, boolean mayDecide) { }
    record PlannedCount(CycleCountView count, List<SkippedSlot> skipped) { }
    record SkippedSlot(String locationCode, String sku, String lotNumber, String lpn, String countNumber) { }
    ```
  - HTTP under `/api/v1/inventory/cycle-counts` (all `scope = DataScope.WAREHOUSE`; `mayDecide = actor.can(CYCLE_COUNTS, APPROVE)`):

    | Method, path | Action | Body → response |
    |---|---|---|
    | `POST /` | CREATE | `PlanCycleCountRequest` → 201 `PlannedCycleCountResponse { count, skipped[] }` |
    | `GET /{id}` | READ | → `CycleCountResponse` |
    | `GET /assignees?warehouseId=` | CREATE | → `[{ userId, displayName, roles }]` |
    | `PUT /{id}/assignment` | CREATE | `{ assignedUserId }` |
    | `PUT /{id}/lines/{lineId}/count` | UPDATE | `{ countedQty?, present? }` — exactly one |
    | `POST /{id}/lines` | UPDATE | `{ locationCode?, sku?, lotNumber?, lpn?, countedQty }` — `lpn` xor (`locationCode`+`sku`) |
    | `POST /{id}/submission` | UPDATE | → `CycleCountResponse` |
    | `POST /{id}/recounts` | APPROVE | `{ lineIds[], note }` → 201 the new count |
    | `POST /{id}/cancellation` | APPROVE | unchanged |

- [ ] **Step 1: Write `WarehouseLocationLookupIntegrationTest`:** `shelfCodesOfADemoZone` (`DemoData.ZONE_HCM_A` → its shelf codes, sorted), `findLocationsKeepsBlockedBins`, `unknownCodesAreAbsent`.
- [ ] **Step 2: Write `CycleCountIntegrationTest`** (service-level, demo warehouse HCM, `TestUsers` actors; requester ≠ approver):
  - `planByShelfGeneratesLooseAndCartonLines` — every loose slot with stock and every IN_STOCK carton under shelf `HCM-A01`, trigger `MANUAL`, number `CC-…`
  - `planByZoneEqualsPlanByItsShelves`
  - `planBySkuOnlyCoversBinsNotAreas` — stock of the SKU in a receiving area is not on the count
  - `planSkipsBusySlots` *(Review Focus 5)* — open SHORT_PICK recount on `HCM-A01-1-B`; planning shelf `HCM-A01` → that slot absent, `skipped` names it with the recount's number
  - `planRefusesEmptyAndTooLarge` — `CYCLE_COUNT_EMPTY_SCOPE`; `max-lines` set to 1 via `@TestPropertySource` → `CYCLE_COUNT_TOO_LARGE`
  - `fullLooseCycle` — plan → count (book re-read) → submit → adjustments PENDING with `cycleCountLineId` → approve → `on_hand` matches the count
  - `submitIsIdempotent` *(Review Focus 1)* — second submit returns the same view; adjustments counted once
  - `shortageBeyondCurrentStockIsStale` *(Review Focus 2)* — count 0 of 5, move 3 out of the bin, submit → `CYCLE_COUNT_STALE`, count still `IN_PROGRESS`, no adjustment
  - `cartonPresentAndMissing` — unopened carton `present=true` → no variance; another `present=false` → one adjustment, approval → `LOST`
  - `cartonAboveReceivedIsRefused` *(Review Focus 4)* → `STOCK_PACKAGE_OVERFILLED`
  - `addLineFindsALostCarton` and `addLineRefusesUnknownLayerAndWrongLocation` → `CYCLE_COUNT_UNKNOWN_LAYER`, `STOCK_PACKAGE_WRONG_LOCATION`
  - `shortPickFreezeLastsUntilTheVarianceIsDecided` — short pick → recount counted short → submit → ATP still excludes the free units; approve → frozen list empty
  - `recountOpensANewBlindCount` — reject the adjustment, `recount` → new MANUAL blind count with that slot, old line `recountRequired`
  - `assigneeMustBeWarehouseStaff` and `onlyTheAssigneeCounts`
- [ ] **Step 3: Write `CycleCountHttpSecurityIntegrationTest`** (`SecuredMvc`):
  - `counterNeverSeesTheBooks` *(Review Focus 3)* — after submission, `GET /{id}` as `INVENTORY_PLANNER` (no APPROVE) → `$.data.lines[*].expectedQty` all null, `adjustments` empty, `note` null; as `WAREHOUSE_MANAGER` → present
  - `warehouseStaffCannotPlanOrDecide` — `POST /` and `/recounts` → 403 `FORBIDDEN`
  - `outOfScopeWarehouseIsDenied` → 403 `OUT_OF_DATA_SCOPE`
  - `countBodyNeedsExactlyOneOfCountedOrPresent` → 400
- [ ] **Step 4: Implement** the warehouse methods, the service and the controller. Planning order: zones → `shelfCodesOf`; every shelf (given or from a zone) → location prefix `"<warehousePrefix>-<shelfCode>-"`; `stockIn`, SKU-only filter to `Kind.BIN` via `findLocations`, cap check, `claim`, drop busy, `CycleCount.plan`, save. `count`/`addLine`: lock the count, `requireCounter`, resolve the slot (`lpn` → `StockPackageRepository.findByLpn`; carton not at the line's bin → `STOCK_PACKAGE_WRONG_LOCATION`; LOST carton accepted on `addLine`; counted > `receivedQty` → `STOCK_PACKAGE_OVERFILLED`; `present` only on an unopened carton), `bookQuantity`, aggregate call. `submit`: lock, `requireCounter`, if `count.submit(now)` then per line with `delta != 0` `stockOperations.requestCountVariance(line.id(), line.slot(), delta, actor, "Count " + number)`; a thrown `CYCLE_COUNT_STALE` rolls the whole submission back. `recount`: lines must each have at least one `REJECTED` adjustment, else `CONFLICT`. Upper-case location codes and LPNs on input.
- [ ] **Step 5: Compile** — `mvn -q test-compile` · Expected: exit 0.
- [ ] **Step 6: Commit** — `git commit -m "feat(inventory): plan, count and submit cycle counts; warehouse lookups by zone and code (SCRUM-162, SCRUM-164)"`

---

### Task 5: Docs, the grant request, and the one full test run

**Files:**
- Modify: `docs/business-design/03-state-machines.md` §14, `docs/business-design/04c-all-flows.md` (`F-CNT` table and notes; the X-SHORT note's "chưa làm" sentence), `CLAUDE.md` §4 (one paragraph: where the count lives, like the pick-confirmation one)
- Create: `docs/handoffs/2026-10-23-scrum-146-grant-request.md` — the SQL of spec §8 for Tú, with why and the test that changes with it

- [ ] **Step 1: Update the docs.** 03 §14 → the real states and "variance review = the adjustment's `PENDING_APPROVAL`"; 04c F-CNT rows 01–06 → plan, count (book re-read), submit → adjustments, approval posts; keep Vietnamese in `business-design`.
- [ ] **Step 2: Static checks** — Run: `python -X utf8 tools/verify.py` · Expected: every check passes.
- [ ] **Step 3: Architecture** — Run: `mvn -q test -Dtest=ModularityTest,ArchitectureTest` · Expected: `BUILD SUCCESS`.
- [ ] **Step 4: Focused tests** — Run: `mvn test -Dtest=CycleCountTest,CountVarianceTest,StockMoveAndAdjustTest,CountVarianceAdjustmentIntegrationTest,CycleCountIntegrationTest,CycleCountHttpSecurityIntegrationTest,WarehouseLocationLookupIntegrationTest,SkuReservationAllocationIntegrationTest,PickConfirmationIntegrationTest,PickConsumptionServiceTest,StockPackageConsumptionIntegrationTest,PlannerAdjustmentAccessIntegrationTest` · Expected: all pass. Fix and re-run only the failing classes.
- [ ] **Step 5: Full build** — Run in the background, and do not edit sources while it runs (the IDE rebuilds `target/`): `mvn verify` · Expected: `BUILD SUCCESS`, JaCoCo report written. Start it in parallel with the final whole-branch review.
- [ ] **Step 6: Commit** — `git commit -m "docs: cycle count flow as built (SCRUM-146)"`; then ask the owner before pushing or opening the PR.
