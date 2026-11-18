# SCRUM-666 No deactivation while cartons remain — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** refuse `INACTIVE` for a bin, a shelf, an area or a warehouse while cartons stand there (kltn-docs 06 BR-04),
and record decision B (areas have no capacity) in kltn-docs.

**Architecture:** the warehouse map services ask inventory, through a new SPI `warehouse.spi.LocationOccupants`, which
cartons stand at a list of location codes; the warehouse module works out the codes (bin, shelf's bins, area's
location, every location of a warehouse). Checked under the warehouse row lock each write already takes.

**Tech Stack:** Spring Boot 3.5, Spring Modulith 1.4, JdbcTemplate, Testcontainers Postgres.

**Spec:** `docs/superpowers/specs/2026-11-13-scrum-666-deactivate-with-cartons-design.md`

## Global Constraints

- A carton stands at a location when `inventory.stock_package.location_code` is one of its codes and `status` is
  `RECEIVING` or `IN_STOCK`.
- Refusal: `ErrorCode.LOCATION_HOLDS_CARTONS`, HTTP 409; details `total` and the first 50 `{locationCode, lpn}` in
  location code, LPN order. en and vi messages.
- Only a change *to* `INACTIVE` from another status is checked; `BLOCKED` / `MAINTENANCE` never are.
- Identifiers, code and javadoc in English; Vietnamese only in the vi bundle and kltn-docs.
- No migration (no column, and no trigger — see Task 3).

## Review Focus

1. A shelf whose cartons are on a bin of level 2 only → refused (all levels counted).
2. A `NON_STORAGE` area → never refused (no location).
3. A carton labelled on a draft receipt (`RECEIVING`) in the receiving area → counts.
4. A carton `CONSUMED` still recorded at the location → does not count.
5. More than 50 cartons → `total` is the real count, the list stops at 50.

---

### Task 1: SPI, inventory answer, error code

**Files:** create `warehouse/spi/LocationOccupants.java`, `inventory/internal/service/CartonsAtLocations.java`;
modify `warehouse/spi/package-info.java`, `inventory/internal/repository/BinOccupancyQuery.java`,
`common/error/ErrorCode.java`, `i18n/messages.properties`, `i18n/messages_vi.properties`.

**Produces:**
- `LocationOccupants.Cartons cartonsAt(Collection<String> locationCodes, int limit)`;
  `record Cartons(long total, List<Carton> first)`, `record Carton(String locationCode, String lpn)`;
  `static void refuse(Cartons cartons, String place)` throws `LOCATION_HOLDS_CARTONS` unless `total == 0`.
- `BinOccupancyQuery.cartonsStanding(Collection<String> codes, int limit)` → `LocationOccupants.Cartons` (one
  count query and one listing query, or one with `count(*) over ()`).

### Task 2: the four refusals

**Files:** modify `ShelfLayoutServiceImpl` (`changeShelfStatus`, `changeBinStatus`), `AreaLayoutServiceImpl`
(`changeAreaStatus`), `WarehouseLayoutServiceImpl.deactivate` (now takes `WarehouseLocks.lock`),
`WarehouseRepository` + adapter (`List<String> findLocationCodes(UUID warehouseId)`), `Warehouse.deactivate` javadoc.
Each service gets `ObjectProvider<LocationOccupants>` like the existing `BinOccupants` one.

### Task 3: tests and the existing layout tests

- New `warehouse/internal/service/PlaceDeactivationIntegrationTest` (rolled back, demo HCM):
  bin with cartons refused, naming its LPNs, and stays ACTIVE; same bin BLOCKED allowed; an empty bin allowed;
  shelf A01 refused; area QC01 refused; `NON_STORAGE` OFFICE allowed; warehouse HCM refused with `total` = all
  cartons and 50 listed; a `RECEIVING` carton counts; a `CONSUMED` one does not.
- Existing tests that deactivate stocked demo places to test the layout move the cartons out first (a support
  helper moving them to `HCM-OVF01` by SQL), or set the status by SQL where the deactivation is not under test:
  `AreaAndBoundaryIntegrationTest`, `LayoutReadIntegrationTest`, `ShelfLayoutServiceIntegrationTest`.
- No database trigger: BR-04 spans the warehouse and inventory schemas, the documented exception that
  `ShelfLayoutServiceImpl` gives for BR-06/BR-07; a warehouse trigger reading inventory would also reverse the module
  dependency. Said in the services' javadoc.

### Task 4: javadoc and kltn-docs

- `BinStranding`: areas are not judged because areas have no capacity (decided 2026-11-13), not "not modelled yet".
- kltn-docs (branch from `origin/develop` of the docs repo): 06 (§1 note, §2 actor, §3 input, §4.1 steps 4/7/9, §4.6,
  data table, BR-13, BR-19, BR-23, edge cases), glossary, diagrams README, open-questions C40 + a new decided entry.

### Task 5: verify

- `PYTHONUTF8=1 python -X utf8 tools/verify.py`
- `mvn -o clean test -Dtest=PlaceDeactivationIntegrationTest,AreaAndBoundaryIntegrationTest,LayoutReadIntegrationTest,ShelfLayoutServiceIntegrationTest,WarehouseLayoutServiceIntegrationTest,BinResizeStrandingIntegrationTest,ModularityTest,ArchitectureTest,MessageBundleTest`
- then the full suite once, in the background.
