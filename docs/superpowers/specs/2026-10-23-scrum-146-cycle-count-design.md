# SCRUM-146 Cycle count — design

*2026-10-23 · StockFlowCommerce (FA26SE029) · owner: Phương*

Jira: [SCRUM-146](https://minh7n3.atlassian.net/browse/SCRUM-146) "Cycle Count" (WBS 3.6.6), with the backend
subtasks [SCRUM-162](https://minh7n3.atlassian.net/browse/SCRUM-162) (schedule and task generation) and
[SCRUM-164](https://minh7n3.atlassian.net/browse/SCRUM-164) (discrepancy detection and adjustment).
SCRUM-163 (mobile counting UI) is the frontend's. The story's own instruction decides the shape of the whole
design: *"don't invent a second adjustment mechanism, feed into that one"* (SCRUM-145).

Tú asked on 2026-10-23 whether the backend scope is complete. It is not: BE #110 only granted the planner read
access to adjustments, and `V20261023000300` gave the warehouse staff `READ` on counts. This document is the
answer to that question.

## 1. What exists on `develop` today

| | State |
|---|---|
| `inventory.cycle_count` | Migrated (`V20260928005000`). Status CHECK is `PLANNED / IN_PROGRESS / COMPLETED / CANCELLED`; triggers `SCHEDULED / SHORT_PICK / MOVE_DISCREPANCY / TRANSFER_DISCREPANCY / MANUAL`; `is_blind` default true; `assigned_user_id`, `planned_date`, `started_at`, `completed_at`. Numbers come from `platform.document_sequence` type `CC`. |
| `inventory.cycle_count_line` | Migrated. `(location_code, sku, lot_number)` or, since `V20261022000100`, a `stock_package_id`; `expected_qty NOT NULL`, `counted_qty / counted_by / counted_at` all-or-nothing, `recount_required`. One loose line per (count, bin, SKU, lot); one line per carton per count. A trigger checks the bin is in the counted warehouse. |
| `inventory.stock_adjustment` | Migrated and implemented (SCRUM-145): four-eyes request → approve-and-post or reject or withdraw. Has `cycle_count_line_id` (mapped on the entity, unused by the domain), reason `COUNT_VARIANCE` (CHECK requires the line id), `received_at` to name one receipt layer, and `stock_package_id` (column only, no code). |
| Code | Only the SHORT_PICK recount (docs 04c X-SHORT step 03): `Recounts` port and `RecountsAdapter` (JDBC), `CycleCountService.list / cancel`, `GET /api/v1/inventory/cycle-counts`, `POST /{id}/cancellation`. An open SHORT_PICK count freezes the free units of its bin, SKU and lot out of ATP and FEFO (`Recounts.frozen`). |
| Permissions | `inventory-cycle-counts`: manager has all actions; planner `VIEW_PAGE, READ, CREATE, UPDATE`; warehouse staff `VIEW_PAGE, READ` (`V20261023000300`, whose comment already says entering counts is `UPDATE` and comes with this story). |
| Frontend | FE #26 is the adjustment screens. Nothing in the FE calls a cycle-count endpoint yet, so the API below is free to design. |
| Earlier attempt | `b7d6cda` (Võ, local branch `feature/SCRUM-146-147-298-split-handoff`) built counts on a legacy schema that was dropped. Not ported; its acceptance gates (`docs/SCRUM-146-147-298-split-handoff.md`) are met in §10. |

**Docs disagree with the schema.** `03-state-machines.md` §14 says
`PLANNED → COUNTING → VARIANCE_REVIEW → APPROVED → POSTED`. The database says otherwise, and the review and
posting steps already exist — as the adjustment's own states. This design keeps the database's states and
updates §14 (§9).

## 2. Decisions taken on 2026-10-23

| # | Question | Decision |
|---|---|---|
| D1 | How far does "schedule" go (162)? | A manager or planner plans a count by hand for a scope (zones, shelves, bins, SKUs). No recurring job. |
| D2 | Recount before adjusting? | No automatic recount. A variance becomes a pending adjustment at once; the manager rejects a doubtful one and asks for a recount (§6). |
| D3 | Freeze bins while counting? | No. The book quantity is re-read at the moment a line's count is entered, and the variance is a delta from that. |
| D4 | Cartons (LPN) in scope? | Yes, the full flow: an unopened carton is confirmed by scanning (#125), an opened one is counted in units, a missing one goes LOST through an approved adjustment, a LOST one found again comes back. |
| D5 | How does a count meet adjustments? | Approach A: the count is a thin document. Submitting it turns each variance into `COUNT_VARIANCE` adjustment(s) through the SCRUM-145 mechanism; approval happens there. |
| D6 | Unknown stock in a bin | A line for a (SKU, lot) with no stock row at that bin is refused: misplaced goods are a move, not an adjustment. Same for a carton scanned in a bin other than its recorded one. |
| D7 | SHORT_PICK freeze | Extended: it lasts until every `COUNT_VARIANCE` adjustment of the line is decided, not just until the count closes. |

## 3. Lifecycle

```
PLANNED ──first count entered──▶ IN_PROGRESS ──submit (every line counted)──▶ COMPLETED
   │                                  │
   └──────────── cancel (APPROVE) ────┴──▶ CANCELLED
```

- `started_at` is set on the first count entered; `completed_at` on submission (the existing CHECKs).
- Cancelling is the existing endpoint, unchanged: a CANCELLED count is returned as is, a COMPLETED one is
  refused with `CYCLE_COUNT_NOT_OPEN`.
- Submitting a COMPLETED count returns it unchanged (idempotent retry). Nothing reopens a COMPLETED count; a
  recount is a new count (§6).
- Variance review, approval and posting are the adjustment's `PENDING_APPROVAL → POSTED | REJECTED |
  WITHDRAWN`.

## 4. Planning a count (SCRUM-162)

`POST /api/v1/inventory/cycle-counts` — `inventory-cycle-counts:CREATE`, warehouse data scope.

```json
{
  "warehouseId": "…", "plannedDate": "2026-10-30", "assignedUserId": "…", "blind": true, "note": "…",
  "scope": { "zoneIds": [], "shelfCodes": [], "locationCodes": [], "skus": [] }
}
```

- The scope is the **union** of zones, shelves and bins; `skus` alone means every storage bin of the warehouse
  holding that SKU, and next to a location criterion it filters it. At least one criterion is required.
- A shelf is a location-code prefix (`HN-A01-…`, `LocationCode.ofBin`), so it is resolved inside the
  `inventory` schema. A zone is a group of shelves owned by `warehouse`: `WarehouseService` gains
  `List<String> shelfCodesOf(UUID zoneId)` (warehouse module, also Phương's — no one else to notify).
  SKU-only scopes are narrowed to `BIN` locations through `WarehouseService.findLocations(codes)` in one call,
  not by guessing from the code's shape.
- Lines are generated from stock as it stands:
  - one **loose line** per (bin, SKU, lot) holding loose stock with `on_hand > 0`, whatever its status —
    a physical count counts what is on the shelf, damaged and quarantined included;
  - one **carton line** per `IN_STOCK` carton in scope.
  `expected_qty` is filled at planning and overwritten when the line is counted (§5).
- A **busy** slot (bin, SKU, lot) or carton is **skipped and listed** in the response (`skipped: [...]`), so two
  counts can never raise adjustments for the same stock. Busy: on another open count, or on a `COMPLETED` one
  whose `COUNT_VARIANCE` adjustment of that line is still `PENDING_APPROVAL` (approving both would post the
  difference twice; once it is decided the slot is free, which is what lets a rejected line be recounted).
  Slots are claimed under the same advisory lock `findHolding` uses (seed 703), taken in sorted key order, and
  both read the one busy predicate (`CycleCountRepositoryAdapter.BUSY`). A carton is one slot whatever bin it is
  recorded at: slots are compared by their lock key (the package alone), not by record equality.
- An empty result is `422 CYCLE_COUNT_EMPTY_SCOPE`. More than
  `stockflow.inventory.cycle-count-max-lines` (default 500) is `422 CYCLE_COUNT_TOO_LARGE`.
- The assignee, when given, must be an active staff member of that warehouse holding `WAREHOUSE_STAFF` or
  `WAREHOUSE_MANAGER` (`IdentityService.assignableUsers`, as fulfillment's task assignment does).
  `GET /cycle-counts/assignees?warehouseId=` returns that list for the picker.
- `trigger_type = MANUAL`. SHORT_PICK counts keep being opened by `PickConsumption.requestRecount`, one per slot
  (bin, SKU and lot, or the carton a carton row belongs to): a short pick on a busy slot reuses the count holding
  it, whatever its trigger (`created = false`), instead of opening a second count of the same stock. Its line
  expects the slot's book quantity (`bookQuantity`, every loose row of the slot or the carton's rows), as a
  plan's does.
- The plan's answer is blinded as `GET /{id}` is: whoever holds `APPROVE` sees the books of what they planned.

`PUT /{id}/assignment` (`CREATE`) changes the assignee while the count is `PLANNED`.

## 5. Counting

`inventory-cycle-counts:UPDATE`. On an assigned count only the assignee may count; a holder of `APPROVE` always
may. Anyone else gets `403 CYCLE_COUNT_NOT_ASSIGNEE`. On an assigned count a holder of `APPROVE` may not overwrite a
line that another user already counted (`403 CYCLE_COUNT_LINE_COUNTED_BY_OTHER`, only the assignee corrects it):
otherwise they would see the books, replace the assignee's figure and approve the variance it raises.

| Endpoint | Body | Effect |
|---|---|---|
| `PUT /{id}/lines/{lineId}/count` | `{ "countedQty": 37 }` — or, for an unopened carton, `{ "present": true }` | Records who, when, how many, and re-reads the book quantity into `expected_qty` (D3). Repeating it overwrites all four until submission. First entry moves the count to `IN_PROGRESS`. |
| `POST /{id}/lines` | `{ "locationCode", "sku", "lotNumber", "countedQty" }` or `{ "locationCode", "lpn", "countedQty" }` | Adds a found line. `locationCode` is always required: for a carton it is the bin it was scanned in (`400 VALIDATION_FAILED` without it). Refused with `CYCLE_COUNT_UNKNOWN_LAYER` when the bin holds no row of that SKU and lot (D6), with `STOCK_PACKAGE_WRONG_LOCATION` for a carton recorded elsewhere, with `CYCLE_COUNT_SLOT_BUSY` when the slot is busy (§4). A LOST carton is accepted: that is how it is found again. |
| `POST /{id}/submission` | — | §6. `409 CYCLE_COUNT_LINE_NOT_COUNTED` while any line has no count. |

- **Book quantity** of a loose line: the sum of `on_hand` over the loose rows of that (bin, SKU, lot), read
  `FOR SHARE` so it is a committed value. Of a carton line: the sum over the carton's rows
  (`inventory.stock_package_balance`).
- `{ "present": true }` on an unopened carton records `countedQty = received_qty` (the count at receipt is
  trusted, #125; for a sealed carton in stock it equals the book quantity); `false` records `0`. On an opened
  carton `present` is refused: count it. A LOST carton is never unopened (`lose()` clears the flag), so it is
  always counted in units.
- **Carton lines follow their carton.** A carton moved whole to another bin of the same warehouse since planning
  is counted where it is: counting and submission move the line to the carton's current bin, and the variance
  names that bin. A carton that left the warehouse is `STOCK_PACKAGE_WRONG_LOCATION`.
- A carton counted above what was counted into it at receipt (`received_qty`) is refused
  (`422 STOCK_PACKAGE_OVERFILLED`): the database never lets a carton hold more (`V20261022000100` rule 5), and
  the extra units are loose stock — a loose line of their own.
- **Lock order**, the same everywhere stock is touched (#125): cycle-count row `FOR UPDATE` → SKU advisory lock
  → `stock_package FOR UPDATE` → stock rows by id.

Reads: `GET /{id}` is new and carries the lines. The list returns count headers only, with `lineCount`,
`countedCount` and — for whoever holds `APPROVE` — `varianceCount` (counted lines that differ from the books);
it never carries lines, so a page stays the size of the page. On the count screen, whoever lacks `APPROVE`
never sees `expected_qty`, the note, or the adjustments of a blind count, before or after submission — the
delta would give the book quantity away. **Blind across the system (owner decision 28/10).** The rule is not the count screen only: for a caller without
`inventory-cycle-counts:APPROVE`, the books of a slot a count holds are hidden everywhere they can be read. A slot is
held while its count is `PLANNED` / `IN_PROGRESS`, or `COMPLETED` with a `COUNT_VARIANCE` of the line still
`PENDING_APPROVAL` (the same `BUSY` predicate the slot claim uses). Stock rows of the slot (`GET /stock-items?sku=`,
`GET /warehouses/{id}/stock-items`, the balances of `GET /lots/{id}`) stay but carry no `onHand` / `reserved` /
`available` and `hiddenDuringCount = true`; ledger lines (`GET /movements`, `GET /lots/{id}/movements`) and adjustments
(`GET /adjustments`, `GET /adjustments/{id}` answers 404) of the slot are left out. SKU- and lot-level totals
(`/stock-levels`, `/stock-items/atp`, the totals of a lot, alerts) stay: they are not a slot's.

**Moves (owner decision MOVE-HELD, 28/10).** `POST /inventory/movements` (the manual move, `MOVE_TASK`) is refused with
`409 STOCK_SLOT_UNDER_COUNT` (`details`: `locationCode` of the held end, `cycleCountNumber`) when the loose slot (bin, SKU,
lot) it takes stock from, or puts it into, is held - the same `BUSY` predicate, asked through `CycleCountRepository.claim`.
Putaway, picking, packing, receiving, transfers and adjustments are not covered.

**Known limitation (owner decision BLIND-TOTALS, 28/10): accepted.** Because SKU and lot totals stay visible, a counter
can still infer a held slot's book quantity: when the SKU sits in that one slot only, its total *is* the slot's books,
and in general `total - sum(visible rows)` is what the held slots hold. Closing this would mean hiding the totals of
every SKU with a held slot, which would also blind sales' available-to-promise. The team accepts the gap; the count
screen, slot rows, ledger and adjustments stay hidden as described above.

## 6. Submission, adjustments, approval, recount (SCRUM-164)

**Submission** runs in one transaction, in the lock order of §5. For every line, `delta = counted − expected`:

| Line | `delta` | Adjustment(s) requested, `reason = COUNT_VARIANCE`, `cycle_count_line_id` set, requested by the submitter |
|---|---|---|
| loose | `> 0` | One, on the most recently received loose row of the slot. For a FIFO SKU, whose layers are never raised (`inventory.enforce_sku_policy`), approval posts it into a **new layer** beside that row, received at approval: found units have no provable receipt and must not be as old as the layer, first out. FEFO and unconfigured SKUs write the named row up. |
| loose | `< 0` | One per row, each at most that row's `on_hand`. Free units (`on_hand − reserved`) are taken first, rows in FEFO order (the order a picker takes them); only then held units, again in FEFO order — writing a held unit down while a free one stands on the books would ask for an adjustment approval refuses. A shortage larger than what the slot holds *now* (held or not) means stock left after the line was counted: submission is refused with `409 CYCLE_COUNT_STALE`, and the counter counts it again. Holds that move between submission and approval are refused at approval (below). |
| carton | `≠ 0` | **One** per carton, `stock_package_id` set; approval spreads it over the carton's rows (§ below). A carton emptied (`CONSUMED`) after it was counted makes submission `409 CYCLE_COUNT_STALE` naming the line; a recount of such a carton is refused the same way. |
| any | `0` | None. |

Then the count is `COMPLETED`. A loose adjustment names its row by `received_at`, as SCRUM-145 already does; a
carton adjustment names the carton.

One adjustment per carton, not per row, because the carton's state must change exactly once: the database
refuses a LOST carton that still holds stock and an IN_STOCK one that holds none, so a carton emptied by two
separately approved adjustments would pass through a state the commit check rejects.

**Extending SCRUM-145 for cartons.** `StockAdjustment` gains `stockPackageId` and `cycleCountLineId`; the entity
maps `stock_package_id`. A loose adjustment behaves exactly as today (`requireLoose` stays). Approving a carton
adjustment takes the carton path, in the #125 lock order (SKU, carton, its rows by id):

- LOST and written up → `found()` first, then the units go onto its first row (stock rows are never deleted at
  zero, so a LOST carton keeps the row to write back into). A carton write-up always refills the carton's own
  row, FIFO or not — never a new layer: the carton is the identity of its units;
- written down → taken from its rows in id order, each at most what is not reserved; a shortfall that would cut
  into a hold is refused as for loose stock (below);
- then, by what is left: nothing left after a `COUNT_VARIANCE` → `lose()` (a count that finds nothing found the
  carton missing); nothing left otherwise → `consume()`; something left after a write-down → `open()`.

The ledger names the carton on the side that has it (`StockMovement.leaving` for a write-down, a new
`arriving` for a write-up).

A count variance is requested through a new `StockOperations.requestCountVariance(...)`, called by submission
with the slot already chosen, so the (SKU, location, lot) ambiguity `findOne` refuses never arises.

**Approval** stays where it is: the manager approves or rejects each adjustment on the existing adjustment
screen (FE #26). No new approval endpoint. Four eyes covers the counter as well as the requester: whoever entered a
line's figure (`cycle_count_line.counted_by`) cannot approve or reject that line's `COUNT_VARIANCE` adjustment
(`409 ADJUSTMENT_SELF_APPROVAL`), whoever submitted the count. A `COUNT_VARIANCE` adjustment is never withdrawn, not even by the
submitter (`409 COUNT_VARIANCE_NOT_WITHDRAWABLE`): withdrawn, it would leave a completed count whose variance
nobody decided. A typed-in adjustment request cannot claim `COUNT_VARIANCE` (`400 VALIDATION_FAILED`). A shortage that would cut into reserved units is refused by
`StockItem.adjustBy` as today (`InsufficientStockException`): the manager reallocates or releases the holds first
(X-SHORT reallocation), then approves. A customer's hold is never cancelled silently by a count.

**Recount.** `POST /{id}/recounts` with `{ "lineIds": [...], "note": "…" }` (`APPROVE`) for lines whose
`COUNT_VARIANCE` adjustment was rejected: sets `recount_required` on those lines and opens a new blind count with
just those slots and cartons, noted "Recount of CC-…" - `SHORT_PICK` when the doubted count was one (so the slot
stays frozen while recounted), `MANUAL` otherwise. A line already flagged is refused (`CONFLICT`): it is recounted
once. Returns the new count.

**SHORT_PICK freeze (D7).** `CycleCountRepository.frozen` also holds back a slot of a `COMPLETED` SHORT_PICK count
while one of its line's `COUNT_VARIANCE` adjustments is `PENDING_APPROVAL`. Otherwise, between submission and
approval, the system would promise again exactly the units a picker could not find. It also freezes the free units
of any slot with a pending **negative** `COUNT_VARIANCE` adjustment, whatever the count's trigger: a short pick on
such a slot reuses that (possibly MANUAL) count, so the freeze must not depend on the trigger. Accepted gap: a short
pick that reuses an *open* MANUAL count leaves the slot unfrozen until that count is submitted.

## 7. Code shape

All in `inventory`; no new module dependency (`identity :: api` and `warehouse :: api` are already allowed).

| Layer | Change |
|---|---|
| `domain` | New aggregate `CycleCount` (lines, `plan`, `count`, `addLine`, `submit`, `cancel`, `requireAssignee`) with `CycleCountLine`, `CycleCountStatus`, `CycleCountRepository` port. The pure variance split of §6 as `CountVariance` (no Spring), unit-tested on its own. `Recounts` is folded into the repository port; `CycleCount.shortPick(...)` replaces `openShortPick`. `StockAdjustment` gains `stockPackageId`, `cycleCountLineId`. |
| `entity` | `CycleCountJpaEntity`, `CycleCountLineJpaEntity` (`EnumType.STRING`, `BaseEntity`). `StockAdjustmentJpaEntity` maps `stock_package_id`. |
| `repository` | `CycleCountRepositoryAdapter` + mapper; `findHolding`, `claim`, `frozen`, the list's headers and scope generation stay native SQL. |
| `service` | `CycleCountService` grows plan / assign / count / addLine / submit / recount; `StockOperationsServiceImpl` gains `requestCountVariance` and the carton approval path. |
| `controller` | `CycleCountController` grows the endpoints above; DTOs in `controller/dto`. |
| `warehouse :: api` | `WarehouseService.shelfCodesOf(zoneId)` and a bulk `findLocations(Collection<String> codes)` returning `StorageLocationView`s. `pickableLocations` does not do: it drops blocked bins, and a blocked bin still holds stock to count. |
| `common/error` | `CYCLE_COUNT_EMPTY_SCOPE`, `CYCLE_COUNT_TOO_LARGE`, `CYCLE_COUNT_NOT_ASSIGNEE`, `CYCLE_COUNT_LINE_NOT_COUNTED`, `CYCLE_COUNT_SLOT_BUSY`, `CYCLE_COUNT_UNKNOWN_LAYER`, `CYCLE_COUNT_STALE`, `COUNT_VARIANCE_NOT_WITHDRAWABLE`, `STOCK_PACKAGE_WRONG_LOCATION`, `STOCK_PACKAGE_OVERFILLED`, with `_vi` messages. |

## 8. Schema: one migration, Tú's

No table changes. One grant, asked of Tú:

```sql
-- inventory-cycle-counts:UPDATE for WAREHOUSE_STAFF: the counter enters counted quantities (SCRUM-146).
INSERT … permission.code = 'inventory-cycle-counts:UPDATE' … WHERE role.code = 'WAREHOUSE_STAFF' ON CONFLICT DO NOTHING;
UPDATE identity.app_role SET version = version + 1 … WHERE code = 'WAREHOUSE_STAFF';   -- ADR-0008
```

Until it lands, only managers and planners can count; nothing else waits on it. When it lands,
`SkuReservationAllocationIntegrationTest.shortPickOpensOneBlindRecount` — which pins the staff's cycle-count
grants to exactly `VIEW_PAGE, READ` — changes in the same commit.

## 9. Docs to update in the same PR

- `03-state-machines.md` §14: the real states, and "variance review = the adjustment's `PENDING_APPROVAL`".
- `04c-all-flows.md` F-CNT steps 01–06 and the X-SHORT note ("counting and posting … not built yet").
- Javadoc of `Recounts` / `CycleCountController` / `CycleCountService`, which say counting is not built.

## 10. Acceptance gates from the split handoff

| Gate | Where it is met |
|---|---|
| Existing `cycle_count` / line + `stock_adjustment` | §1, §6 — no new tables |
| `document_sequence` | `CC` numbers, as SHORT_PICK already does |
| Blind count, data scope | §5 reads; `WarehouseScope` on every call |
| Clear wrong-warehouse denial | `OUT_OF_DATA_SCOPE`; the line trigger as a last line of defence |
| Four-eyes | the adjustment's own rule and `ck_stock_adjustment_four_eyes` |
| Reservation shortage | §6 approval: loud refusal, never a silent release |
| Count staleness | D3: book quantity re-read at entry, delta posted |
| Idempotent posting + ledger atomically | submission idempotent on COMPLETED; approval posts stock and `stock_movement` in one transaction (existing) |

## 11. Testing

- Unit: `CycleCount` (transitions, assignee, all-lines-counted), `CountVariance` (positive, FEFO split, carton
  cases), `StockAdjustment` carton fields.
- Integration (Testcontainers, security on): plan by shelf and by zone → blind count by the assignee → submit →
  loose and carton adjustments → approve → `on_hand`, carton status and ledger correct; skipped busy slots;
  counter never sees the book quantity; non-assignee refused; reserved shortfall refused at approval; SHORT_PICK
  freeze held until the adjustment is decided; recount opens a new count.
- `ModularityTest` (warehouse api changes), `ArchitectureTest`, `tools/verify.py`; one `mvn verify` at the end of
  the branch.

## 12. Out of scope

- A recurring count schedule (D1), ABC-driven frequencies.
- Auto-recount and value thresholds for approval levels (BR-STK-005's "configured value threshold").
- Creating stock rows from a count; moving a carton found in the wrong bin (D6).
- The mobile UI (SCRUM-163).
