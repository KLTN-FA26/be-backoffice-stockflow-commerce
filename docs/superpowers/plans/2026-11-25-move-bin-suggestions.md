# Bin suggestions for a carton move — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `GET /api/v1/inventory/stock-packages/bin-suggestions?lpn=…` ranks the bins a group of cartons may be moved into, with putaway's own rule and ranking (issue #412).

**Architecture:** The putaway gathering code (candidates, occupancy, trip holds, overflow areas) moves out of `PutawayService` into one package-private component both putaway and the new `MoveSuggestionService` use. `BinOccupancyQuery` learns to read a bin's contents for a group of cartons in one statement; `PutawayRanker` gets a group overload. Read-only, nothing stored, `moveStockPackages` untouched.

**Tech Stack:** Java 21, Spring Boot 3.5, JdbcTemplate, JUnit 5 + AssertJ, Testcontainers Postgres.

**Spec:** `docs/superpowers/specs/2026-11-25-move-bin-suggestions-design.md`

## Global Constraints

- All code, identifiers and javadoc in English; javadoc says why. Cite kltn-docs 11 §4.1, 11 BR-10, 06 BR-01, 05 §4.2, 05 BR-16, C24, docs#46.
- No migration. No change to `StockOperationsServiceImpl.moveStockPackages` / `relocatePackages`.
- Permission `InventoryResources.STOCK_ITEMS` + `Action.UPDATE` (the move's). Handler method `public`.
- 1..500 LPNs; the same `MAX_SUGGESTIONS` (10) as putaway.
- Query count constant in the number of cartons.
- **No `git commit` / `git push` without the repository owner's go-ahead** (CLAUDE.md §7). Steps end at "tests green"; committing is asked once at the end.

## Review Focus

1. The same LPN twice in the request → treated once (no doubled volume). Test in Task 3.
2. LPN in lower case → found as the move finds it (`StockPackageRepository.findByLpns`, same behaviour as the move — do not normalise differently). Test in Task 3 only if `findByLpns` is case-insensitive; otherwise mirror the move and note it.
3. A carton already standing in a bin that is a candidate → that bin is never suggested (its own location), and the carton's volume is not counted in any other bin. Test in Task 3.
4. A single-carton putaway suggestion is unchanged by the refactor (same order, same reasons, weight-null semantics). Existing `PutawayRankerTest`, `PutawayConfirmationIntegrationTest`, `PutawayTripIntegrationTest` must stay green; plus the equivalence test in Task 1.
5. Every carton in `OVERFLOW` and no bin fits → `overflowAreas` lists usable OVERFLOW areas of the warehouse except the cartons' current one. Test in Task 3.

---

### Task 1: `PutawayRanker` ranks a group of cartons

**Files:**
- Modify: `src/main/java/com/stockflow/inventory/internal/domain/PutawayRanker.java`
- Test: `src/test/java/com/stockflow/inventory/internal/domain/PutawayRankerTest.java`

**Interfaces:**
- Produces:
  - `public static List<Suggestion> rank(List<Need> needs, Lot group, List<Bin> bins, Map<String, Occupancy> occupancy)`
  - `public static Refusal refusal(List<Need> needs, Bin bin, Occupancy occupancy)`
  - `public static Lot Lot.of(List<Need> needs)` — summed volume and weight (null weights count as nothing).
  - Existing `rank(Need, Lot, …)`, `rank(Need, …)`, `refusal(Need, …)`, `fits(Need, …)` delegate with `List.of(need)` and behave exactly as today.

- [ ] **Step 1: Write the failing tests** (in `PutawayRankerTest`, reuse `CARTON` = 0.06 m³ / 5 kg, `CUBE`, `bin()`, `holding()`):
  - `oneCartonTooBigExcludesTheBin`: needs `[CARTON, BIG]` where `BIG` is 120×40×30 cm; bin `CUBE` 1 m³ empty → `rank` is empty; `refusal(...)` is `DOES_NOT_FIT`.
  - `summedVolumeOverFreeRoomExcludesTheBin`: needs = 3 × `CARTON` (0.18 m³); bin `"B"` 1 m³ holding `0.83` → empty, `refusal` = `NO_ROOM`; holding `0.82` → one suggestion with `freeVolumeM3` 0.18.
  - `summedWeightOverLimitExcludesTheBin`: bin with `maxWeightKg` 20 holding 6 kg, 3 × `CARTON` (15 kg) → `NO_ROOM`; holding 5 kg → suggested.
  - `mixedLotsAreNotSameLot`: occupancy `new Occupancy(1, 0.1, 0, true, false)` (the caller computed "holds every lot" false) → reason `SAME_SKU`, not `SAME_LOT`.
  - `aGroupOfOneRanksAsTheSingleCarton`: for a list of 5 varied bins/occupancies, `rank(List.of(CARTON), Lot.of(CARTON), bins, occ)` equals `rank(CARTON, Lot.of(CARTON), bins, occ)`; and with a `Need` whose weight is null against a bin already over its weight limit, both answer suggested (unweighed).
  - `emptyBinsRankedByTheGroupTotal`: empty bins 0.15 m³ and 0.2 m³ and 1 m³, needs = 3 × `CARTON` (0.18 m³) → order `[0.2, 1]` with 0.15 excluded (does not take it).

- [ ] **Step 2: Run, expect compile failure / FAIL**
  Run: `mvn -q test -Dtest=PutawayRankerTest`

- [ ] **Step 3: Implement.** `refusal(List<Need>, …)`: `DOES_NOT_FIT` if any carton fails `CartonFit.fits`; volume check against the sum of `carton().volumeM3()`; weight checked only when at least one need has a weight, against the sum of the known weights. `rank(List<Need>, …)` filters with it; ordering and `suggest`/`takesTheLot` unchanged. Update the class javadoc: a group (11 §4.1, docs#46) is judged carton by carton for size and together for room.

- [ ] **Step 4: Run** `mvn -q test -Dtest='PutawayRankerTest,CartonFitTest'` → PASS.

---

### Task 2: Shared bin inputs, and the group occupancy

**Files:**
- Create: `src/main/java/com/stockflow/inventory/internal/service/BinSuggestionInputs.java` (`@Component`, package-private class)
- Modify: `src/main/java/com/stockflow/inventory/internal/repository/BinOccupancyQuery.java`
- Modify: `src/main/java/com/stockflow/inventory/internal/service/PutawayService.java` (remove `heldByTrips`, `Reserved`, `overflowAreas`, `binOf`, the merge loop; delegate)
- Modify: `src/main/java/com/stockflow/inventory/internal/service/PutawayTripService.java` (use `BinSuggestionInputs.Reserved` / `heldByTrips`)
- Test: `src/test/java/com/stockflow/inventory/BinOccupancyGroupIntegrationTest.java` (`@IntegrationTest`)

**Interfaces:**
- Consumes: Task 1's `PutawayRanker` API.
- Produces:
  - `BinOccupancyQuery.SkuLot(String sku, String lotNumber)` — public record.
  - `BinOccupancyQuery.BinContents(int units, BigDecimal volumeM3, BigDecimal weightKg, Set<String> skus, Set<SkuLot> lots)` — `skus` / `lots` only those of the group the bin holds.
  - `Map<String, BinContents> BinOccupancyQuery.contentsOf(Collection<String> locationCodes, Set<SkuLot> group, Collection<UUID> leaveOut)` — ONE statement; bins holding nothing absent. A pair with a null lot never matches a lot (today's rule).
  - Existing `occupancyOf(codes, sku, lot[, leaveOut])` keeps its signature and delegates to `contentsOf` + the reduction below.
  - `BinSuggestionInputs`:
    - `record Reserved(String locationCode, String sku, String lotNumber, int units, BigDecimal volumeM3, BigDecimal weightKg)` (moved from `PutawayService`)
    - `List<PutawayRanker.Bin> bins(UUID warehouseId)` (from `putawayCandidates`, `CartonPlacement.spaceOf`)
    - `List<Reserved> heldByTrips(UUID warehouseId, UUID exceptTaskId)` (body moved verbatim)
    - `Map<String, Occupancy> occupancy(Collection<String> locationCodes, Set<SkuLot> group, Collection<UUID> leaveOut, List<Reserved> reserved)` — contents plus reserved (a reserved carton adds its SKU / pair when they are in the group), then reduced: `holdsSku` = bin holds every SKU of the group; `holdsLot` = bin holds every pair of the group and no pair has a null lot.
    - `List<String> overflowAreas(UUID warehouseId)` (moved verbatim)
    - `Map<UUID, Need> needsOf(Collection<UUID> cartonIds)`, `Map<UUID, BinOccupancyQuery.CartonLoad> loadsOf(Collection<UUID> cartonIds)` — pass-throughs.

- [ ] **Step 1: Write the failing integration test** `BinOccupancyGroupIntegrationTest` on the demo seed, cartons made with `support/CartonRows.carton(...)` inside `TransactionTemplate`, two fresh SKUs, bin codes from `DemoData` / `PutawayCandidatesIntegrationTest`:
  - bin X holds SKU1/lot L1 and SKU2/lot L2 → group `{(SKU1,L1),(SKU2,L2)}`: `skus` = both, `lots` = both.
  - group `{(SKU1,L1),(SKU1,L9)}` against X → `lots` = `{(SKU1,L1)}` only.
  - `leaveOut` = the SKU1 carton → X's units/volume exclude it and `skus` = `{SKU2}`.
  - `occupancyOf(List.of(X), SKU1, L1)` equals the reduction of `contentsOf` for the group of one (old path still answers the same).

- [ ] **Step 2: Run, expect FAIL** — `mvn -q test -Dtest=BinOccupancyGroupIntegrationTest`

- [ ] **Step 3: Implement** `contentsOf` (arrays bound as `text[]` / `uuid[]`, aggregated with `array_agg(distinct …) filter (where …)`), the delegating `occupancyOf`, then `BinSuggestionInputs`, then switch `PutawayService.suggest(..., reserved)` and `PutawayTripService` to it. `PutawayService.suggest` keeps its signature; its group is `Set.of(new SkuLot(task.sku(), lot))`, leaveOut `[task.stockPackageId()]`.

- [ ] **Step 4: Run** `mvn -q test -Dtest='BinOccupancyGroupIntegrationTest,PutawayRankerTest,PutawayService*Test,PutawayConfirmationIntegrationTest,PutawayTripIntegrationTest,CartonQuarantineIntegrationTest,GoodsReceiptPutawayCloseIntegrationTest'` → PASS (putaway unchanged).

---

### Task 3: `MoveSuggestionService` and the endpoint

**Files:**
- Create: `src/main/java/com/stockflow/inventory/internal/service/MoveSuggestionService.java` (`@Service`, public class)
- Create: `src/main/java/com/stockflow/inventory/internal/controller/dto/MoveSuggestionResponse.java`
- Modify: `src/main/java/com/stockflow/inventory/internal/controller/StockPackageController.java`
- Modify: `src/main/java/com/stockflow/inventory/internal/controller/StockPackageWebMapper.java` (if mapping lives there)
- Test: `src/test/java/com/stockflow/inventory/MoveBinSuggestionIntegrationTest.java` (`@SecuredIntegrationTest`, `SecuredMvc`, as `MoveUnderCountIntegrationTest`)

**Interfaces:**
- Consumes: Task 2's `BinSuggestionInputs`, Task 1's `PutawayRanker.rank(List<Need>, Lot, …)`.
- Produces:
  - `public record MoveSuggestions(List<PutawayRanker.Suggestion> suggestions, List<String> overflowAreas)` (nested in the service)
  - `@Transactional(readOnly = true) public MoveSuggestions suggest(List<String> lpns)`
  - `MoveSuggestionResponse(List<PutawaySuggestionResponse> suggestions, List<String> overflowAreas)`
  - `GET /api/v1/inventory/stock-packages/bin-suggestions` with repeated `lpn` → `ApiResponse<MoveSuggestionResponse>`

Order inside `suggest` (decided): blank/empty list or > 500 → `VALIDATION_FAILED`; distinct LPNs; `StockPackageRepository.findByLpns`; any missing → `STOCK_PACKAGE_NOT_FOUND`; `WarehouseScope.requireLocation` for each distinct current location; `WarehouseService.findLocations` → more than one `warehouseId` → `MOVE_ACROSS_WAREHOUSES`; group from `loadsOf`, needs from `needsOf` (missing → `Need.UNMEASURED`); bins = `inputs.bins(wh)` minus current location codes; occupancy with `leaveOut` = the cartons, `reserved` = `heldByTrips(wh, null)`; `rank(needs, Lot.of(needs), …)`; overflow = `overflowAreas(wh)` minus current codes, only when suggestions are empty. Held / not `IN_STOCK` cartons are not refused (the move does that).

- [ ] **Step 1: Write the failing integration tests:**
  - `overflowCartonGetsABinTheMoveAccepts`: two cartons of a fresh SKU at `HCM-OVF01` → 200, `suggestions` non-empty, every `locationCode` starts with `HCM-`, none is `HCM-OVF01`, `overflowAreas` empty; then `POST /api/v1/inventory/movements` with those LPNs to `suggestions[0].locationCode` → 200.
  - `ownLocationIsNeverSuggested`: a carton standing in a candidate bin → that bin absent.
  - `duplicateLpnCountedOnce`: `lpn=A&lpn=A` answers the same as `lpn=A`.
  - `nothingFitsGivesOverflowAreas`: a carton unit larger than every demo bin, carton at a bin-less area (e.g. the receiving area) → `suggestions` empty, `overflowAreas` = `["HCM-OVF01"]`.
  - `otherWarehouseCartonIs403`: a clerk assigned only to HCM asks for a carton in the other demo warehouse → 403.
  - `twoWarehousesIs409`: as manager with both warehouses, one LPN in each → 409 `MOVE_ACROSS_WAREHOUSES`.
  - `unknownLpnIs404`: → 404 `STOCK_PACKAGE_NOT_FOUND`.
  - `queryCountIsFlat`: Hibernate `Statistics.getPrepareStatementCount()` plus a `@MockitoSpyBean JdbcTemplate` invocation count (`Mockito.mockingDetails(jdbc).getInvocations().size()`), measured for 5 and for 50 cartons via `MoveSuggestionService.suggest` → equal.

- [ ] **Step 2: Run, expect FAIL** — `mvn -q test -Dtest=MoveBinSuggestionIntegrationTest`

- [ ] **Step 3: Implement** the service, DTO, and handler:
  `@GetMapping("/bin-suggestions")`, `@RequiresPermission(resource = InventoryResources.STOCK_ITEMS, action = Action.UPDATE)`, `@RequestParam(name = "lpn") List<String> lpns`. `@Operation` summary "Bins a group of cartons may be moved into (advice only)"; description names: 1..500 LPNs; every carton fits each bin and the bin's free room takes them all; putaway's order; overflow only when no bin; own locations excluded; nothing held; the move checks only what it checks today (11 BR-10, C24); errors 400 VALIDATION_FAILED, 404 STOCK_PACKAGE_NOT_FOUND, 403 OUT_OF_DATA_SCOPE, 409 MOVE_ACROSS_WAREHOUSES.

- [ ] **Step 4: Run** `mvn -q test -Dtest='MoveBinSuggestionIntegrationTest,StockPackage*Test'` → PASS.

---

### Task 4: Fitness functions and the whole suite

- [ ] **Step 1:** `PYTHONUTF8=1 python -X utf8 tools/verify.py` → no findings.
- [ ] **Step 2:** `mvn -q test -Dtest='ModularityTest,ArchitectureTest,PermissionMatrixTest,MessageBundleTest'` → PASS.
- [ ] **Step 3:** `mvn test` → green; any red test is read, not skipped.
- [ ] **Step 4:** Ask the owner whether to commit (on `develop` per session rules, or a feature branch) and push.
