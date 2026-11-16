# SCRUM-669 Warehouse capacity board — design

*2026-11-16 · StockFlowCommerce (FA26SE029) · owner: Phương*

Jira: [SCRUM-669](https://minh7n3.atlassian.net/browse/SCRUM-669) `[BE] utilization_factor, API bảng`, story SCRUM-626
(G4-05) · kltn-docs 06 §4.6, BR-19, BR-20 · feeds SCRUM-673 (capacity warnings, 06 BR-21; 17 BR-25) and the FE page
SCRUM-671.

## Intent

The board answers one question: **will the goods on their way still find room on the bins of this warehouse?** The
warehouse manager reads it to prepare room; Sales, the production coordinator and the Sales Manager will be warned from
it (SCRUM-673) when they choose a shipping warehouse, draw up or approve a production order. It is computed when read,
never stored (06 §4.6 step 3).

## Decided (owner, 2026-11-13 → 16)

1. **An area has no capacity** (kltn-docs 06 BR-19, KLTN-FA26/docs#32). Capacity is bins only.
2. **What counts as occupied** is what occupies or will need a bin, nothing else:

   | cartons standing in | occupied? | why |
   |---|---|---|
   | a bin | yes | it is there |
   | `RECEIVING`, `OVERFLOW`, `QUARANTINE` areas | yes | they go up to a bin (or may: quarantine, counted to be safe) |
   | `STAGING` area | **no** | picked, leaving; never goes back to a bin |
   | `DISPATCH` area | no | already deducted at the pick scan (`CONSUMED`), so not in stock at all |

   This departs from 06 §4.6 / C40 ("cartons in every area count"); kltn-docs is updated in the same work.
3. **Outbound ("Sắp xuất") is for reference only**, never added back to free space: when held cartons leave is unknown,
   and goods may arrive first.
4. **One utilisation factor per warehouse**, default 100%, in (0, 100%]; it applies to the board only — putaway keeps
   checking a bin's full computed volume (06 §4.6, C43).
5. **Approach A — no cross-schema read** (ADR-0005 stays; the ADR-0011 draft was discarded). The board is composed in
   `order`, the one module allowed to call both `warehouse :: api` and `inventory :: api`, which also owns production
   orders and is where SCRUM-673 will warn.

## Approaches considered

| | How | Verdict |
|---|---|---|
| **A. Compose in `order` through `api`** | `warehouse` and `inventory` each answer one question; `order` adds its production orders and does the arithmetic | **chosen**: no rule bent, no new SPI, the warnings of SCRUM-673 call it in-module |
| B. Board in `warehouse` with two SPIs | inventory and order implement SPIs the warehouse declares | two SPIs and a round trip (`order` → `warehouse` → `order`) for one screen |
| C. One SQL across schemas (or published views) | board in `warehouse`, a single statement | needs ADR-0005's no-JOIN rule dropped; the owner kept it |
| `reporting` module | read model | `reporting` depends on `order`; SCRUM-673 in `order` would have to call it back: a cycle |
| Stored board fed by events | | the docs want it computed on read; consistency for nothing at this scale |

## The numbers

For one warehouse, read in one transaction (read-only):

| Column | Value |
|---|---|
| **Capacity** (m³) | Σ computed volume of the warehouse's **usable** bins (effective status `ACTIVE`: bin, shelf and warehouse) × utilisation factor. A bin without a volume (a legacy level with no clear height) adds nothing and is counted in `binsWithoutVolume` |
| **Occupied** (m³, kg, cartons) | cartons with units in them standing at any bin of the warehouse (whatever its status) or at a `RECEIVING` / `OVERFLOW` / `QUARANTINE` area; volume and full weight from the carton unit (as putaway counts them, `BinOccupancyQuery.occupancyOf`) |
| **Incoming** (m³, kg, cartons) | for production orders with this receiving warehouse in `SENT`, `ACCEPTED`, `SAMPLE_PENDING`, `IN_PRODUCTION`, `PARTIALLY_RECEIVED`: Σ per line `(cartons − received_cartons)` × the SKU's carton volume / full weight |
| **Pending approval** (m³, kg, cartons) | the same for `DRAFT`, `PENDING_APPROVAL` |
| **Expected free** | Capacity − Occupied − Incoming (may be negative) |
| **Free if all approved** | Expected free − Pending approval (may be negative) |
| **Outbound** *(reference)* | Σ over **every** `HELD` reservation of this warehouse (holds, allocations, and allocations whose carton waits in a `STAGING` area — 06 §4.6 counts what is held for orders not yet delivered) of units ÷ the SKU's units per carton × carton volume / weight |
| **Capacity (kg)** *(reference, my call)* | Σ declared load limit of the usable bins (the limit putaway checks); a usable bin without one adds nothing and is counted in `binsWithoutWeightLimit` (06: no limit declared = not checked). Shown with Occupied kg, free kg and % kg — 06 §4.6 step 3 and SCRUM-626 ask for kg next to m³ |
| **% occupied**, **% expected free** | Occupied ÷ Capacity, Expected free ÷ Capacity; null when Capacity is 0 |
| **Unmeasured cartons** | cartons counted in Occupied / Incoming / Pending / Outbound whose SKU has no full carton size: counted as cartons, 0 m³, and reported (`unmeasuredCartons`) so the number is known to be low |

**Drill-down** (06 §4.6 step 4): one row per **zone** (bins of shelves in that zone; a shelf without a zone under
"no zone"), per **shelf**, and per storage **area**. A zone or shelf row has Capacity, Occupied, Current free (Capacity −
Occupied) and %, in m³ and kg; its Capacity is **multiplied by the same utilisation factor** (06 §4.6, C43: one factor
for every level), so the zone rows add up to the warehouse's. An **area row has Occupied only** (decision 1), and the `STAGING` / `DISPATCH` areas are listed with
their occupancy shown but excluded from the warehouse's Occupied, so the manager still sees what stands there.
Incoming, Pending and Outbound exist at warehouse level only (production orders and holds name a warehouse, not a bin).

Rounding: volumes in m³ to 3 decimals, weights to 1 decimal, percentages to 1 decimal, all `BigDecimal`
`HALF_UP` and only when rendering; sums are kept exact.

## What each module adds

### `warehouse`

- **Migration** `VyyyyMMddNNNNNN__warehouse_utilization_factor.sql` (newer than every migration on `develop`):
  `warehouse.warehouse.utilization_factor numeric(5,4) NOT NULL DEFAULT 1`, `CHECK (utilization_factor > 0 AND
  utilization_factor <= 1)`; adversarial SQL under `tools/db/qa/`. Existing rows take the default (no upgrade test
  needed: nothing but a default is written).
- `Warehouse` aggregate carries the factor (invariant twice: aggregate + CHECK); `PUT /api/v1/warehouses/{id}` takes
  `utilizationFactor` (optional in the request, unchanged when absent) and returns it; `warehouses:UPDATE` as today.
- `warehouse :: api` gains one read: `WarehouseService.capacityLayout(UUID warehouseId)` →
  `CapacityLayout(UUID warehouseId, String prefix, BigDecimal utilizationFactor, List<Bin> bins, List<Area> areas)`;
  `Bin(String locationCode, UUID zoneId, String zoneName, String shelfCode, BigDecimal volumeM3 /* null = unknown */,
  BigDecimal maxWeightKg /* null = not declared */, boolean usable)`; `Area(String locationCode, String code, String name, StorageLocationView.AreaType type)` (storage
  areas only; `NON_STORAGE` has no location). Built from the layout read the map already uses (`LayoutReadRepository`),
  with the bin volume from `BinSize` (never stored, 06 BR-19) and `usable` from `EffectiveStatus`.

### `inventory`

`inventory :: api` (`InventoryService`) gains two reads, both in the caller's transaction, no locks:

- `Map<String, LocationLoad> loadAt(Collection<String> locationCodes)` → per location `LocationLoad(int cartons,
  BigDecimal volumeM3, BigDecimal weightKg, int unmeasuredCartons)`; a location holding nothing is absent. Same
  counting as `BinOccupancyQuery.occupancyOf` (every carton with units, any stock status) plus the carton count.
- `OutboundLoad outboundAt(String warehouseCode)` → `OutboundLoad(int cartons, BigDecimal volumeM3, BigDecimal
  weightKg, int unmeasuredCartons)` over `HELD` reservations of that warehouse.

And `InventoryControlService.cartonSpecs(Collection<Sku>)` → `Map<Sku, CartonSpec>`, a batch of the existing
`cartonSpec(Sku)`, so `order` prices incoming cartons in one read instead of one per SKU.

### `order`

- `ProductionOrderRepository` (port + adapter): `List<DueCartons> dueAt(UUID warehouseId)` →
  `DueCartons(String sku, ProductionOrderStatus status, long cartons)` summed over lines, `cartons − received_cartons
  > 0`, statuses `DRAFT` … `PARTIALLY_RECEIVED` (not `RECEIVED`, `CANCELLED`, `CLOSED`). Own schema only.
- `CapacityBoard` (domain, pure): takes the layout, the loads, the outbound and the due cartons with their carton
  specs; computes every column and the drill-down. All arithmetic lives here and is unit-tested.
- `CapacityBoardService` (`internal/service`, read-only transaction): `CapacityBoard boardOf(UUID warehouseId)`;
  calls `WarehouseScope.requireWarehouse(warehouseId)` (a warehouse manager sees their own warehouses only; Sales and
  the coordinator have scope ALL). SCRUM-673 will call it in-module.
- `CapacityBoardController`: `GET /api/v1/warehouses/{warehouseId}/capacity` → the board (record DTOs, never an
  entity). The URL sits under `warehouses` because that is what it describes; the class javadoc says why `order`
  serves it (decision 5). Unknown warehouse → 404 `WAREHOUSE_NOT_FOUND`.

### Permissions

New resource `warehouse-capacity`, action `READ`, granted to `WAREHOUSE_MANAGER`, `SALES_STAFF`, `SALES_MANAGER`,
`PRODUCTION_COORDINATOR`, `SYSTEM_ADMIN` (the roles 06 §2 and SCRUM-626 name), in the same migration, advancing
`identity.app_role.version` for each (CLAUDE.md §5). `PermissionMatrixTest` gains the entry. Before adding it, grep
the baseline for a pre-seeded capacity resource (memory: some resources were seeded ahead of their controllers).

## Response shape (for the FE, SCRUM-671)

```json
{
  "warehouseId": "…", "prefix": "HCM", "utilizationFactor": 0.85,
  "capacityM3": 120.000, "occupied": {"cartons": 75, "volumeM3": 4.500, "weightKg": 360.0},
  "incoming": {…}, "pendingApproval": {…}, "outbound": {…},
  "expectedFreeM3": 98.250, "freeIfAllApprovedM3": 90.100,
  "occupiedPercent": 3.8, "expectedFreePercent": 81.9,
  "capacityKg": 6000.0, "freeKg": 5640.0, "occupiedKgPercent": 6.0,
  "binsWithoutVolume": 0, "binsWithoutWeightLimit": 0, "unmeasuredCartons": 0,
  "zones":  [{"zoneId": "…", "name": "Khu A - Sofa", "capacityM3": …, "occupiedM3": …, "freeM3": …, "occupiedPercent": …}],
  "shelves":[{"shelfCode": "A01", "zoneId": "…", "capacityM3": …, "occupiedM3": …, "freeM3": …, "occupiedPercent": …}],
  "areas":  [{"code": "QC01", "type": "QUARANTINE", "occupied": {…}, "countsAsOccupied": true}]
}
```

## Non-goals

The warnings and the ProductionOrderSent notifications (SCRUM-673), the FE page (SCRUM-671), a forecast by day, a
heatmap, a per-SKU "how many cartons still fit" simulation (more exact than m³ × factor, deliberately left for later),
any change to putaway.

## Tests

- `CapacityBoardTest` (unit): every formula, negatives, capacity 0 → null percentages, bins without volume, unmeasured
  cartons, STAGING / DISPATCH excluded from Occupied but listed, outbound not subtracted.
- Integration against the demo seed (HCM: 12 bins, 75 cartons): the endpoint's numbers match a hand-worked expectation;
  a production order `SENT` adds Incoming, `DRAFT` adds Pending only, `RECEIVED` adds nothing; a bin set `BLOCKED`
  leaves Capacity; utilisation factor 0.85 scales Capacity only; the factor update via `PUT` and its CHECK.
- Security: a warehouse manager of another warehouse gets 403; `PermissionMatrixTest`, `ModularityTest`,
  `ArchitectureTest`, `MessageBundleTest` green; QA SQL for the CHECK.
- One full `mvn test` at the end (memory: speed preferences).

## Docs to update with the code

kltn-docs 06 §4.6 (Đang chiếm excludes the staging area; dispatch cartons are already out of stock) and open-questions
C40 (`✅ chốt lại 2026-11-16`); the Jira story SCRUM-626 (area rows show Occupied only) as a comment.

## Calls made after the builder check (owner to confirm while reading)

1. Capacity also in kg (declared load limits of usable bins), for reference, with free kg and % kg.
2. Outbound counts every HELD reservation, cartons already staged included (06 §4.6 wording).
3. Zone and shelf capacity use the warehouse's utilisation factor too (06 C43).

## Left to the builder

Class and DTO names beyond those above, the exact query shapes (one per fact, measured with the jpa-orm-review skill's
query-count method), how the layout read is reused, error message wording.
