# Bin suggestions for a carton move (issue #412) — design

Business decision: KLTN-FA26/docs#46 (still an open issue on 2026-11-25; the owner chose to build on its proposal now).

## Intent

An intra-warehouse move (kltn-docs 11 §4.1) is refused whole when one carton does not fit the target bin (11 BR-10,
`CARTON_DOES_NOT_FIT_BIN`). The clerk guesses, is refused, tries again — most often moving cartons out of an
`OVERFLOW` area (05 BR-13). Putaway already ranks bins (`PutawayRanker`, 05 §4.2); a move gets the same, advisory only.

## Goals / non-goals

- Goal: `GET /api/v1/inventory/stock-packages/bin-suggestions?lpn=…&lpn=…` (1..500 LPNs) →
  `{ suggestions: [{locationCode, reason, freeVolumeM3, freeWeightKg}], overflowAreas: [...] }`.
- Goal: the suggestion is judged by the same code that putaway uses, so it cannot drift from what the move accepts.
- Non-goal: nothing changes in `moveStockPackages` (C24 stays: no volume / load / putaway-flag check on a move).
- Non-goal: no space is held, nothing is stored, no migration.
- Anti-goal: a copy of the putaway gathering code; a query count that grows with the number of cartons.

## Approaches

- **Chosen — backend endpoint reusing putaway's code.** One rule for suggestion and acceptance; ten rows over the wire.
- Rejected — compute in the frontend: needs raw occupancy / trip-hold / carton-size APIs anyway and a TypeScript copy
  of `CartonFit` + `PutawayRanker`, which would drift from the move's BR-10 check (owner agreed 2026-11-25).
- Rejected — call `occupancyOf` once per (SKU, lot): query count grows with lots.

## Design

1. **Shared inputs, extracted from `PutawayService`** into a package-private component (`inventory/internal/service`):
   candidates (`WarehouseService.putawayCandidates` → `Bin`), occupancy of the candidates (leaving given cartons out),
   plus putaway-trip holds (`heldByTrips` moves here), and carton needs (`BinOccupancyQuery.needsOf`).
   `PutawayService` and `PutawayTripService` use it; putaway behaviour unchanged.
2. **`BinOccupancyQuery.occupancyOf` for a group**: a collection of cartons to leave out and a set of (SKU, lot) pairs;
   `holdsSku` / `holdsLot` true only when the bin holds **every** SKU / every (SKU, lot) of the group. The existing
   single-carton signature delegates. One statement whatever the group size.
3. **`PutawayRanker.rank(List<Need>, Lot, bins, occupancy)`**: every carton fits the bin dimension by dimension
   (`CartonFit`); the summed volume and weight fit the bin's free room; empty bins ranked by the group's total
   (`Lot` = the group). Order unchanged. The single-`Need` `rank` delegates; `refusal` unchanged.
4. **`MoveSuggestionService`** (`@Transactional(readOnly = true)`): cartons by LPN (missing → 404
   `STOCK_PACKAGE_NOT_FOUND`); `WarehouseScope.requireLocation` per carton location (403); more than one warehouse →
   409 `MOVE_ACROSS_WAREHOUSES`; trip holds counted; the cartons' own current locations excluded; overflow areas
   (usable, not a current location) only when no bin takes the group. Held / non-`IN_STOCK` cartons are not refused.
5. **Endpoint** in `StockPackageController`, `@Operation` with error codes, `inventory-stock-items:UPDATE`.

## Calls made by Claude (check these)

- `SAME_LOT` / `SAME_SKU` only when the bin holds every lot / SKU of the group; a mixed-lot group against a bin holding
  one lot is never `SAME_LOT`.
- Empty bins ranked by the group's own total, not by "the rest of the lot" (no putaway tasks are involved).
- Candidates are putaway candidates (`is_putaway_target`, usable): the same set putaway suggests from.

## Tests

- `PutawayRankerTest`: one carton too big → bin excluded; summed volume over free room → excluded; mixed lots → no
  `SAME_LOT`; single-carton results unchanged.
- Integration on the demo seed (`support/`): a carton in `OVERFLOW` gets a bin that `POST /inventory/movements`
  accepts; another warehouse's carton → 403; LPNs from two warehouses → 409; query count constant for 50 cartons.
- `PermissionMatrixTest`, `ArchitectureTest`, `ModularityTest`, `tools/verify.py` green.
