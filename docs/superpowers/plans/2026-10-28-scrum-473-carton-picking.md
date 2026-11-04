# Picking by Carton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allocation draws whole cartons for the round part and opened cartons first for the remainder (inside FEFO/FIFO), and a pick line drawn from a carton carries its LPN, which the picker must scan.

**Architecture:** The order lives in the pure domain service `StockAllocator`, fed two new facts per candidate by the existing projection query. `HoldView` (`inventory :: api`) carries the hold's LPN and whether it is the whole carton; fulfillment copies both onto `fulfillment.pick_line` (new columns, guarded by a trigger) and `PickList.confirm` requires the LPN for those lines.

**Tech Stack:** Java 21, Spring Boot 3.5 / Spring Modulith 1.4, Spring Data JPA (JPQL constructor expressions), PostgreSQL 16 + Flyway, JUnit 5 / AssertJ / Mockito, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-10-28-scrum-473-carton-picking-design.md`

## Global Constraints

- Branch `feature/SCRUM-473-carton-picking` (from `develop` @ `13071c10`). Never commit or push without the owner's go-ahead; no tool/AI attribution or `Co-Authored-By` in commits; merge `develop`, never rebase or force-push.
- FEFO/FIFO (BR-STK-006) always first; the carton rule orders only the last group (spec §3.2).
- Remainder order: opened cartons → loose → break a sealed carton. Wrong LPN is refused, never swapped.
- No new error code: a wrong scan on a carton line is `PICK_ITEM_MISMATCH`, message `"This line picks carton " + lpn`.
- Migration `V20261028003000__fulfillment_pick_line_carton.sql`; version must stay newer than `develop`'s newest at merge (rename if needed).
- Lock order unchanged: SKU advisory locks (sorted) → `stock_package FOR UPDATE` → stock rows by id. Planning reads projections only.
- Javadoc says why; English only in code. Keep existing constructors as delegating overloads so untouched callers and tests compile unchanged.
- Test runs: targeted tests per task; one full `mvn -o verify` at the end (16 GB laptop — never edit sources while `mvn test` runs; Docker Desktop must be up).

## Review Focus

1. **A carton another order already holds part of** must not be treated as whole (else two orders "take the whole carton") — Task 1 `partlyHeldSealedCartonCountsAsOpened`, Task 2 `partlyHeldCartonIsNotWhole`.
2. **Need smaller than every whole carton, no opened or loose stock** — must break the smallest carton, not fail — Task 1 `breaksTheSmallestWholeCartonWhenNothingElseCovers`.
3. **Pick lines created before the migration** for holds on carton rows — must get their LPN by backfill so the scan rule applies, and the trigger must accept them — Task 3 `backfillNamesTheCartonOfExistingLines`.
4. **LPN typed in lower case or with spaces** on the scanner — must be accepted — Task 4 `cartonLineAcceptsTheLpnInAnyCase`.
5. **Replay of a confirmed carton line with the SKU instead of the LPN** — must not be read as a replay; it is refused as a mismatch — Task 4 `replayOfACartonLineNeedsTheLpn`.

---

### Task 1: Carton-aware plan in `StockAllocator`

**Files:**
- Modify: `src/main/java/com/stockflow/inventory/internal/domain/StockAllocator.java`
- Test: `src/test/java/com/stockflow/inventory/internal/domain/StockAllocatorTest.java`

**Interfaces:**
- Produces: `StockAllocator.Candidate` canonical constructor
  `(StockItemId stockItemId, LocationId location, String lotNumber, LocalDate expiryDate, StockStatus status, Quantity available, Instant receivedAt, UUID stockPackageId, boolean wholeCarton)`;
  the existing 7-arg and 6-arg constructors delegate with `(null, false)`. Accessors `stockPackageId()`, `wholeCarton()`.
  Compact constructor rejects `wholeCarton && stockPackageId == null` (`IllegalArgumentException`).
- `plan(...)` signatures unchanged; `comparator(strategy)` and `FEFO` unchanged.

- [ ] **Step 1: Write the failing tests** (nested class `CartonOrder` in `StockAllocatorTest`; SKU, bin `HCM-A01-1-A`, one expiry date for "same group"; helpers `whole(id, qty)`, `opened(id, qty)`, `loose(id, qty)` build candidates with `stockPackageId = UUID.randomUUID()` / `wholeCarton` accordingly)

```java
@Test void wholeCartonsForTheRoundPart() {
    // whole 1000, whole 1000, whole 1000, opened 400 ; need 2300
    // -> [whole#1 1000, whole#2 1000, opened 300]   (no third carton broken)
}
@Test void largestWholeCartonFirst() {
    // whole 980, whole 1000 ; need 1980 -> [1000, 980], both whole
}
@Test void remainderFromOpenedBeforeLoose() {
    // opened 50, loose 500 ; need 40 -> [opened 40]
}
@Test void remainderFromLooseBeforeBreakingACarton() {
    // whole 1000, loose 30 ; need 20 -> [loose 20]
}
@Test void breaksTheSmallestWholeCartonWhenNothingElseCovers() {
    // whole 1000, whole 980 ; need 500 -> [whole(980) 500]
}
@Test void openedCartonsSmallestFirst() {
    // opened 70, opened 20 ; need 50 -> [20, 30 from the 70]
}
@Test void partlyHeldSealedCartonCountsAsOpened() {
    // candidate with stockPackageId, wholeCarton=false, available 600 (another order holds 400); whole 1000 ; need 300
    // -> [the 600 one, 300]
}
@Test void fefoBeatsCartonPreference() {
    // opened 300 expiring 2026-12-01, whole 1000 expiring 2027-01-01 ; need 1000
    // -> [opened 300, whole(2027) 700]   (earlier group taken first, then the later group's carton is broken)
}
@Test void fifoGroupsByReceivedAt() {
    // RemovalStrategy.FIFO, today; whole 10 received T2, loose 4 received T1 ; need 10 -> [loose 4, whole 6]
}
@Test void looseOnlyPlansAsBefore() {
    // three loose rows 5, 2, 9 same expiry ; need 8 -> [2, 5, 1 of the 9]  (today's smaller-remainder order)
}
```
Each asserts `plan.stream().map(l -> l.stockItemId() + ":" + l.quantity().value())` with `containsExactly(...)`.

- [ ] **Step 2: Run them, expect failures**

Run: `mvn -o -q test -Dtest=StockAllocatorTest`
Expected: compilation error on the 9-arg `Candidate` constructor, then (after adding it) assertion failures on carton order.

- [ ] **Step 3: Implement**

`Candidate`: add the two components and delegating constructors (see Interfaces). In `allocate(sku, candidates, required, comparator)`, after filtering and the total check, replace the single loop by grouping on the comparator's primary key. Since the primary key differs per caller, pass it in: `allocate(..., Comparator<Candidate> order, Function<Candidate, Object> groupKey)` — `plan(sku, candidates, required)` uses `c -> c.expiryDate()`, `plan(..., strategy, today)` uses `c -> List.of(expiry-or-sentinel, receivedAt-or-sentinel)` for FEFO and `c -> receivedAt` for FIFO (nulls compare as one group, as they sort together today). Then:

```java
for each group in comparator order (LinkedHashMap over the sorted list):
    if (groupTotal <= outstanding) { take every candidate fully, in sorted order; continue; }
    // last group
    take whole cartons sorted by available DESC, then location, then id, while available <= outstanding
    take opened cartons (stockPackageId != null && !wholeCarton) sorted by available ASC, location, id
    take loose (stockPackageId == null) sorted by available ASC, location, id
    take remaining whole cartons sorted by available ASC, location, id
    (each "take" = min(outstanding, available); stop at 0)
```
Update the class javadoc: a paragraph "Cartons" saying why the rule is inside the last group only and why a partly held sealed carton counts as opened.

- [ ] **Step 4: Run** `mvn -o -q test -Dtest='StockAllocatorTest,StockPolicyTest,SkuReservationTest'` — Expected: all PASS.

- [ ] **Step 5: Commit** (after go-ahead) `feat(inventory): SCRUM-473 draw whole cartons first, the remainder from opened ones`

---

### Task 2: Carton facts in the projections (`inventory`)

**Files:**
- Modify: `src/main/java/com/stockflow/inventory/internal/repository/AvailabilityRow.java`
- Modify: `src/main/java/com/stockflow/inventory/internal/repository/StockItemJpaRepository.java` (`findAvailabilityBySku`, `findHolds`)
- Modify: `src/main/java/com/stockflow/inventory/internal/domain/HoldDetail.java`
- Modify: `src/main/java/com/stockflow/inventory/api/HoldView.java`
- Modify: `src/main/java/com/stockflow/inventory/internal/service/InventoryServiceImpl.java` (`holds(...)` mapping only)
- Test: `src/test/java/com/stockflow/inventory/CartonAllocationIntegrationTest.java` (new)

**Interfaces:**
- Consumes: Task 1 `Candidate` 9-arg constructor.
- Produces:
  - `AvailabilityRow(..., Instant receivedAt, UUID stockPackageId, boolean wholeCarton)` → `toCandidate()` passes both.
  - `HoldDetail(..., String serialNumber, String lpn, boolean wholeCarton)`; existing constructors delegate `(null, false)`.
  - `HoldView(UUID reservationId, String sku, String locationCode, String lotNumber, int quantity, Status status, Instant expiresAt, LocalDate expiryDate, String serialNumber, String lpn, boolean wholeCarton)`; the existing 9-arg and 8-arg constructors delegate `(null, false)`. Javadoc `@param lpn` (null for loose or a SKU reservation) and `@param wholeCarton` (the hold is the carton's whole content: it was sealed and the hold takes all of it).
- JPQL, both queries: `left join StockPackageJpaEntity p on p.id = s.stockPackageId`.
  - availability `wholeCarton` = `case when p.unopened = true and s.reserved = 0 and s.onHand = p.receivedQty then true else false end`
  - holds `lpn` = `p.lpn`; `wholeCarton` = `case when p.unopened = true and r.quantity = s.onHand and s.onHand = p.receivedQty then true else false end`
  (check the field names on `StockPackageJpaEntity`; adjust to its mapping.)

- [ ] **Step 1: Write the failing integration test** `CartonAllocationIntegrationTest` (`@IntegrationTest @Import(PostgresContainer.class)`), fixtures copied from `StockPackageConsumptionIntegrationTest` (fresh SKU `PKA-…`, FIFO policy, `uom_conversions` CARTON factor 10, cartons on `HCM-A01-1-B` received at one instant, `confirmedOrder()` by SQL, reserve → `pinReservations` → `allocate(order, "HCM")`).

```java
@Test void allocationTakesWholeCartonsThenTheOpenedOne() {
    // cartons A(10, sealed), B(10, sealed), C opened with 4 left (unopened=false, row on_hand 4), loose row 3
    // order 24 -> holds: A 10 whole, B 10 whole, C 4 ; nothing from the loose row
    // assert via inventory.holds(ids): lpn + wholeCarton + quantity per hold
}
@Test void partlyHeldCartonIsNotWhole() {
    // one sealed carton of 10; order X takes 3 (allocated), order Y takes 7
    // Y's hold: lpn = the carton's, wholeCarton = false
}
@Test void looseHoldHasNoLpn() {
    // only a loose row of 5; order 2 -> hold lpn null, wholeCarton false
}
```

- [ ] **Step 2: Run** `mvn -o -q test -Dtest=CartonAllocationIntegrationTest` — Expected: FAIL (`HoldView` has no `lpn()`).

- [ ] **Step 3: Implement** the records, both JPQL queries and the `holds(...)` mapping per Interfaces. Update `AvailabilityRow` and `HoldView` javadoc.

- [ ] **Step 4: Run** `mvn -o -q test -Dtest='CartonAllocationIntegrationTest,StockPackageConsumptionIntegrationTest,StockAllocatorTest'` — Expected: PASS.

- [ ] **Step 5: Commit** (after go-ahead) `feat(inventory): SCRUM-473 say which carton a hold is in, and whether it is the whole carton`

---

### Task 3: `pick_line.lpn` / `whole_carton` migration

**Files:**
- Create: `src/main/resources/db/migration/V20261028003000__fulfillment_pick_line_carton.sql`
- Test: `src/test/java/com/stockflow/fulfillment/PickLineCartonMigrationTest.java` (new)

**Interfaces:**
- Produces: columns `fulfillment.pick_line.lpn VARCHAR(30) NULL`, `whole_carton BOOLEAN NOT NULL DEFAULT FALSE`; constraints `fk_pick_line_lpn` → `inventory.stock_package (lpn)`, `ck_pick_line_whole_carton CHECK (NOT whole_carton OR lpn IS NOT NULL)`; function `fulfillment.check_pick_line_carton()` + trigger `tg_pick_line_carton BEFORE INSERT OR UPDATE OF lpn, reservation_id`.

- [ ] **Step 1: Write the failing migration test** (`@IntegrationTest @Import(PostgresContainer.class)`; fixture: `ReferenceRows.orderWithLine` for an order line, one carton row + one loose row as in Task 2, an allocation hold on each through `InventoryService`, one `fulfillment.pick` row for the order — fill its NOT NULL columns as `V20260928005300` defines)

```java
@Test void lineOnACartonHoldTakesItsLpn()          // insert with the carton's LPN -> ok
@Test void wrongLpnIsRefused()                      // insert naming another carton's LPN -> DataIntegrityViolation
@Test void lpnOnALooseHoldIsRefused()               // loose hold + any LPN -> refused
@Test void lineOnACartonHoldWithoutLpnIsRefused()   // carton hold + lpn NULL -> refused
@Test void wholeCartonNeedsAnLpn()                  // whole_carton TRUE, lpn NULL, loose hold -> refused (check)
@Test void backfillNamesTheCartonOfExistingLines()  // run the migration's UPDATE statement again on a line inserted
                                                    // with the trigger disabled (ALTER TABLE ... DISABLE TRIGGER) and
                                                    // lpn NULL -> lpn = the carton's LPN
```
For the backfill test, extract the UPDATE into the test as the same SQL text the migration runs (keep one copy in a `static final String` of the test and say so in a comment).

- [ ] **Step 2: Run** `mvn -o -q test -Dtest=PickLineCartonMigrationTest` — Expected: FAIL (column `lpn` does not exist).

- [ ] **Step 3: Write the migration**: header comment in the house style (why: SCRUM-473 pick by carton, #125 delegation, the scan needs the LPN on the line; why the trigger reads `inventory.stock_reservation` → `stock_item.stock_package_id` → `stock_package.lpn`). Order: `ALTER TABLE` (spec §4.2) → backfill
  `UPDATE fulfillment.pick_line l SET lpn = p.lpn FROM inventory.stock_reservation r JOIN inventory.stock_item s ON s.id = r.stock_item_id JOIN inventory.stock_package p ON p.id = s.stock_package_id WHERE r.id = l.reservation_id AND l.lpn IS NULL`
  → function + trigger (raise `check_violation` with a message naming the expected LPN) → `COMMENT ON COLUMN` for both columns.

- [ ] **Step 4: Run** `python3 tools/verify.py` and `mvn -o -q test -Dtest='PickLineCartonMigrationTest,PickConfirmationIntegrationTest'` — Expected: verify clean (Flyway version unique), tests PASS.

- [ ] **Step 5: Commit** (after go-ahead) `feat(fulfillment): SCRUM-473 pick lines name the carton they take from`

---

### Task 4: Pick line carries the LPN; confirm requires it (`fulfillment`)

**Files:**
- Modify: `src/main/java/com/stockflow/fulfillment/internal/domain/PickList.java` (`Hold`, `plan`, `confirm`, `matches`)
- Modify: `src/main/java/com/stockflow/fulfillment/internal/domain/PickLine.java`
- Modify: `src/main/java/com/stockflow/fulfillment/internal/entity/PickLineJpaEntity.java`
- Modify: `src/main/java/com/stockflow/fulfillment/internal/repository/PickLineMapper.java`
- Modify: `src/main/java/com/stockflow/fulfillment/internal/service/PickListService.java` (`holdsOf`, `view`)
- Modify: `src/main/java/com/stockflow/fulfillment/internal/service/PickLists.java` (`PickLineView`)
- Modify: `src/main/java/com/stockflow/fulfillment/internal/controller/dto/PickLineResponse.java`
- Modify: `src/main/java/com/stockflow/fulfillment/internal/controller/FulfillmentWebMapper.java`
- Test: `src/test/java/com/stockflow/fulfillment/internal/domain/PickListTest.java`, `src/test/java/com/stockflow/fulfillment/internal/service/PickListServiceTest.java`

**Interfaces:**
- Consumes: Task 2 `HoldView.lpn()`, `HoldView.wholeCarton()`; Task 3 columns.
- Produces:
  - `PickList.Hold(UUID reservationId, String sku, String locationCode, UUID warehouseId, String lotNumber, int quantity, boolean live, String lpn, boolean wholeCarton)`; 7-arg constructor delegates `(null, false)`.
  - `PickLine` gains `lpn()`, `wholeCarton()`; both rehydrating constructors get a variant with `..., int shortQty, String lpn, boolean wholeCarton)` and the existing ones delegate `(null, false)`; `PickLine.open(..., int allocatedQty, String lpn, boolean wholeCarton)`.
  - `PickLineJpaEntity`: `@Column(name = "lpn", length = 30, updatable = false) String lpn`, `@Column(name = "whole_carton", nullable = false, updatable = false) boolean wholeCarton`, getters/setters as its other columns.
  - `PickLists.PickLineView` and `PickLineResponse`: append `String lpn, boolean wholeCarton`.

- [ ] **Step 1: Write the failing domain tests** in `PickListTest` (helpers `cartonHold(...)` and `openCartonLine(sku, location, qty, lpn)`)

```java
@Test void planCopiesTheCartonOfTheHold()       // hold lpn "LPN-20261028-000001", whole=true -> line.lpn(), wholeCarton()
@Test void cartonLineConfirmsWithItsLpn()        // scan location + "LPN-20261028-000001" -> PICKED
@Test void cartonLineAcceptsTheLpnInAnyCase()    // " lpn-20261028-000001 " -> PICKED
@Test void cartonLineRefusesTheSku()             // scan SKU -> PICK_ITEM_MISMATCH, message contains "LPN-20261028-000001"
@Test void cartonLineRefusesTheBarcode()         // scan barcode -> PICK_ITEM_MISMATCH
@Test void cartonLineRefusesAnotherLpn()         // "LPN-20261028-000002" -> PICK_ITEM_MISMATCH
@Test void replayOfACartonLineNeedsTheLpn()      // PICKED carton line, same scan with LPN -> replay (no change);
                                                 // with the SKU -> PICK_LINE_NOT_OPEN (not a replay)
```
and in `PickListServiceTest`: `holdsOfPassesTheCarton` — `inventory.holds(ids)` returns a `HoldView` with an LPN and `wholeCarton=true`; the planned line saved through `pickLines` carries both (capture the saved `PickLineJpaEntity`).

- [ ] **Step 2: Run** `mvn -o -q test -Dtest='PickListTest,PickListServiceTest'` — Expected: compile failure, then assertion failures.

- [ ] **Step 3: Implement** per Interfaces. In `PickList`: `itemMatches(line, scannedCode, barcode)` becomes `line.lpn() != null ? sameCode(scannedCode, line.lpn()) : <today's rule>`; the mismatch message is `"This line picks carton " + line.lpn()` for a carton line, unchanged otherwise. `plan` passes `hold.lpn()`, `hold.wholeCarton()` to `PickLine.open`. Update the `confirm` javadoc (a carton line is confirmed by its LPN; the SKU or barcode is not enough because the deduction is from that carton's row). `PickListService.holdsOf` passes the two fields; `view(...)` and `FulfillmentWebMapper.toResponse` append them.

- [ ] **Step 4: Run** `mvn -o -q test -Dtest='PickListTest,PickListShortPickTest,PickListServiceTest,PickConfirmationIntegrationTest,ArchitectureTest,ModularityTest'` — Expected: PASS.

- [ ] **Step 5: Commit** (after go-ahead) `feat(fulfillment): SCRUM-473 a carton line is confirmed by scanning its LPN`

---

### Task 5: Docs and the full gate

**Files:**
- Modify: `CLAUDE.md` §4 — a short "Picking by carton (SCRUM-473)" paragraph after "Putaway": the order inside the last FEFO/FIFO group, partly held sealed = opened, LPN on `pick_line` guarded by `tg_pick_line_carton`, scan = LPN for carton lines.
- Modify: `docs/superpowers/specs/2026-10-28-scrum-473-carton-picking-design.md` §4.2 / §7 — QA: the trigger is pinned by `PickLineCartonMigrationTest` instead of `tools/db/qa` (a QA fixture for a pick line needs an order, a task and a hold); integration: `CartonAllocationIntegrationTest` + the existing `StockPackageConsumptionIntegrationTest` cover allocation and carton open/close, the scan rule is domain-tested.

- [ ] **Step 1:** Edit both files.
- [ ] **Step 2:** `python3 tools/verify.py` — Expected: no findings.
- [ ] **Step 3:** `mvn -o verify` in the background (Docker up, no source edits meanwhile) — Expected: BUILD SUCCESS; record the test count before/after.
- [ ] **Step 4:** Commit (after go-ahead) `docs: SCRUM-473 picking by carton`; then ask before pushing and opening the PR into `develop`. PR body lists: Tú (migration), Võ (`PickList`/`PickLine`), frontend (`lpn`, `wholeCarton`, scan the LPN).
