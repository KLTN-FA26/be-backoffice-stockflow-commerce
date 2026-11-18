# SCRUM-666 No stacking height for areas; no deactivation while cartons remain — design

*2026-11-13 · StockFlowCommerce (FA26SE029) · owner: Phương*

Jira: [SCRUM-666](https://minh7n3.atlassian.net/browse/SCRUM-666) under story SCRUM-625 · kltn-docs 06 §4.1, §4.6,
BR-04, BR-19, BR-23.

## Intent

The ticket asked for a mandatory `max_stack_height` on storage areas, so that area volume = width × length × height
would count towards the warehouse capacity board (06 BR-19, §4.6) and be guarded by BR-23. The owner rejected that
on 2026-11-13: in a real warehouse a floor area's volume is not a meaningful capacity. Receiving, dispatch, staging
and quarantine are places goods pass through, not storage; aisles, gaps and uneven stacking make L × W × H overstate
what fits; warehouse systems that model floor storage use floor slots and stack layers, not a volume. Counting those
areas would make the capacity board report free space that cannot be used.

**Decision (approach B): areas have no capacity.** Capacity is bins only. Cartons standing in an area still count as
occupied (06 §4.6 unchanged on that point). What remains of the ticket is BR-04.

## Approaches considered

| | What | Why not / why |
|---|---|---|
| A. As the docs say | `max_stack_height`, area volume = W × L × H, discounted by the warehouse utilisation factor | Transit areas inflate free space; the number has no physical meaning |
| **B. Areas have no capacity** (chosen) | No column; capacity from bins only; areas are counted only as occupied | Matches a rack-first warehouse; least work; needs kltn-docs 06 changed |
| C. Floor slots | Areas split into floor positions with a stack-layer limit | Closest to reality, far beyond the capstone's scope |

## Goals

1. **BR-04:** changing a bin, a shelf, an area or a warehouse to `INACTIVE` is refused while cartons still stand
   there; the refusal lists them. `BLOCKED` / `MAINTENANCE` stay allowed with cartons in place.
2. kltn-docs records decision B (06, glossary, diagrams README, open-questions C40 and a new entry).
3. The javadoc that said "area volume is not modelled yet" says it is not modelled by decision.

Non-goals: label printing (BR-17), the FE (SCRUM-667), the capacity board (SCRUM-669, which now counts bins only),
BR-23 for areas (moot: an area has no volume to shrink).

## Design

- **"A carton stands there"** = an `inventory.stock_package` row whose `location_code` is one of the place's
  location codes and whose status is `RECEIVING` or `IN_STOCK` (my call: a carton labelled on a draft receipt is
  physically in the receiving area too; `CONSUMED`, `LOST`, `VOID` cartons are gone).
- **Which locations:** a bin → its own code; a shelf → every bin of every level; an area → its location (a
  `NON_STORAGE` area has none, never refused); a warehouse → every location of the warehouse. The warehouse module
  owns these codes and works them out; inventory only answers for a list of codes.
- **SPI:** a second interface next to `BinOccupants` (which stays as it is, for BR-23 on bins):
  `warehouse.spi.LocationOccupants.cartonsAt(Collection<String> locationCodes, int limit)` → total count plus the
  first `limit` cartons (location code, LPN) in location, LPN order. Implemented in `inventory` next to
  `BinStranding`. *(Changed while planning: a separate interface instead of renaming, so neither question drags the
  other along.)*
- **Where checked:** in `ShelfLayoutServiceImpl.changeShelfStatus` / `changeBinStatus`,
  `AreaLayoutServiceImpl.changeAreaStatus`, `WarehouseLayoutServiceImpl.deactivate`, under the warehouse row lock
  each already takes (deactivate must take it too), only when the target status is `INACTIVE` and the place is not
  already `INACTIVE`.
- **Refusal:** new `ErrorCode.LOCATION_HOLDS_CARTONS` (409), en + vi messages; details carry `total` and the first 50
  `{locationCode, lpn}`.
- **No database twin** *(changed while planning)*. BR-04 spans the warehouse and inventory schemas — the exception
  `ShelfLayoutServiceImpl` already documents for BR-06/BR-07 — and a warehouse trigger reading `inventory` would
  reverse the module dependency. It would also not close the concurrent-move gap below, and it would forbid the test
  fixtures that set a stocked demo place inactive by SQL. The services' javadoc says so.
- **Known gap, documented:** a carton moved in by a concurrent transaction may slip past both checks (READ
  COMMITTED, moves do not take the warehouse lock) — the same accepted gap `BinStranding` documents.

## Branch and tests

Branch `feature/SCRUM-666-deactivate-with-cartons` from `origin/develop` (no area column any more, so nothing ties it
to the putaway-trip branch). No migration. Tests: integration tests for the four refusals and for `BLOCKED` still
allowed, `MessageBundleTest`; the existing layout tests that deactivate stocked demo places move the cartons out
first. One test run at the end.
