# SCRUM-92 Putaway by carton — design

*2026-10-26 · StockFlowCommerce (FA26SE029) · owner: Phương*

Jira: [SCRUM-92](https://minh7n3.atlassian.net/browse/SCRUM-92) (sub-tasks SCRUM-106 task creation, SCRUM-108
confirmation; SCRUM-107 mobile UI is Vũ's) · GitHub #191 · tracking issue #138 (line "Cất hàng theo thùng") ·
builds on PR #197 (receiving into cartons, SCRUM-473).

Received goods sit in the RECEIVING or QUALITY_CONTROL area as INBOUND stock and are never sellable until they
are put away (docs 04c `F-PUT`, machine 5 `INBOUND → AVAILABLE`). Today nothing does that: the event
`ReceiptStockReadyForPutaway` is published and has no listener, and `warehouse.putaway_task` has an entity and a
repository and nothing else. This design creates a putaway task per carton and per loose remainder, suggests
bins, and confirms a putaway by scan in one transaction that moves the stock to the bin as AVAILABLE.

## 1. What exists (branch `feature/SCRUM-473-receive-in-cartons` @ a2942500)

| | State |
|---|---|
| `warehouse.putaway_task` | `id`, `goods_receipt_id` + `goods_receipt_line_id` (or `transfer_order_line_id` / `return_request_line_id`, exactly one: `ck_putaway_task_source`), `sku` (FK to the item), `quantity > 0`, `target_location_id` (FK to `storage_location`), `status` `PENDING / IN_PROGRESS / DONE / CANCELLED`, `stock_package_id` with `uk_putaway_task_open_package` (one open task per carton) and `tg_putaway_task_package` (same SKU; RECEIVING or IN_STOCK when set). No assignee, no source location, no lot, no warehouse, no reason column, no suggestion table. |
| `PutawayTaskJpaEntity` | Maps every column except `stock_package_id`; no domain, service or controller. |
| Event | `ReceiptStockReadyForPutaway(receiptId, receiptNumber, warehouseId, lines[receiptLineId, sku, lotNumber, locationCode, quantity])`, published by procurement at confirmation for lines needing no QC and at `inspect` for the accepted part of a QC line. No listener. |
| Receipt | `IN_PUTAWAY` is entered at confirmation (no QC line) or when the last QC line is decided with something accepted; nothing moves it to `CLOSED`. |
| Stock | `relocate` (loose, with a target status) and `relocatePackages` (cartons, target QUARANTINE / BLOCKED only) exist; every receipt opens its own lot per (SKU, lot number, expiry) (`StockLots.openForReceipt`). Ledger reference type `PUTAWAY_TASK` exists. |
| Map | `storage_location` has `kind` (BIN / AREA), `storage_class`, `capacity_units`, `max_weight` (both nullable); `StorageLocationView` exposes neither capacity nor weight. |
| Permission | `warehouse-putaway-tasks` (VIEW_PAGE, READ, UPDATE) is seeded (`V20260903000100`) and granted to warehouse staff and managers. |
| Modules | `inventory` may use `warehouse :: api`; `warehouse` depends on nothing. `procurement` uses both apis. |

## 2. Decisions taken on 2026-10-26

| # | Question | Decision |
|---|---|---|
| D1 | How far in this round? | **No migration** (schema is Tú's). Tasks per carton and per loose remainder, scan to confirm, suggestions computed when read. Assignment to a person, stored suggestion reasons and BR-SLT-002 (reason / manager approval when the bin differs from the suggestion) wait for a round B with Tú's columns. |
| D2 | Suggestions | Hard filters then a simple ranking with a reason per suggestion; the full engine is SCRUM-91. |
| D3 | Loose units | May be split over several bins: each confirmation puts part of the task into one bin; the task is DONE when nothing is left. A carton task is one bin, one confirmation. |
| D4 | Architecture | **Orchestrated in `inventory`, the task owned by `warehouse` through `warehouse :: api`, confirmed synchronously in one transaction.** This departs from the `F-PUT` diagram (warehouse completes, inventory moves on the event) on purpose: when a scan answers 200 the goods are in the bin, and a refused bin is told to the person holding the scanner instead of failing in a listener nobody watches. |
| D5 | Sources | Goods-receipt lines only. Transfer and return putaway (the other two source columns) are later work. |

## 3. Components

```
procurement ──ReceiptStockReadyForPutaway──▶ inventory.PutawayTaskCreator ──▶ warehouse :: api PutawayTasks.open
                                               (after commit, retried by the publication registry)

client ──GET/POST /api/v1/putaway-tasks──▶ inventory.PutawayController ──▶ inventory.PutawayService
                                               ├─ warehouse :: api PutawayTasks (lock, record, list)
                                               ├─ warehouse :: api putaway candidates (bins)
                                               ├─ PutawayRanker (pure: filter + rank)
                                               └─ stock moves: relocate / relocatePackages (AVAILABLE)
inventory ──PutawayCompleted──▶ procurement listener ──▶ GoodsReceipt CLOSED when every line is put away
```

**warehouse** (owns the task)

- `PutawayTask` aggregate in `warehouse/internal/domain` over the existing table; `PutawayTaskJpaEntity` maps
  `stock_package_id` as well. Behaviour: `recordPutaway(units, locationId)` → `IN_PROGRESS` while units remain,
  `DONE` when none do; a DONE task refuses further work. The units already put away are not a column: the
  caller passes what the ledger says is left (§6), and the task checks it is not asked for more.
- `warehouse :: api`:
  - `PutawayTasks`: `open(NewPutawayTask)` (idempotent per carton and per loose line, §4), `lockForUpdate(id)`,
    `recordPutaway(id, units, leftBefore, locationId)`, `find(id)`, `openTasks()` / `tasksOfReceipt(receiptId)`,
    `doneQuantityByReceiptLine(receiptId)`.
  - `putawayCandidates(warehouseId, storageClass)`: the warehouse's usable BINs of that class with
    `capacityUnits` and `maxWeightKg` (null when not recorded), one query.
  - Records only (`PutawayTaskView`, `NewPutawayTask`, `PutawayCandidate`), never the entity.

**inventory** (orchestrates)

- `PutawayTaskCreator`: `@ApplicationModuleListener` on `ReceiptStockReadyForPutaway` (public method).
- `PutawayService`: task detail with what is left and the suggestions; `confirm`.
- `PutawayRanker` in `inventory/internal/domain`: pure filter and rank over candidates and their occupancy; the
  class SCRUM-91 replaces.
- `PutawayController` in `inventory/internal/controller`, `@PermissionResource(code = "warehouse-putaway-tasks")`.
- Stock: `relocatePackages` and `StockItem.reclassifyInPackage` accept AVAILABLE as a target from INBOUND (today
  QUARANTINE / BLOCKED only); loose putaway goes through `relocate` with target AVAILABLE and reference
  `(PUTAWAY_TASK, taskId)`, **without** its reference-based replay lookup (§6).

**contracts**: `PutawayCompleted(receiptId, receiptLineId, putawayTaskId)`, published by inventory when a task
becomes DONE.

## 4. Creating tasks

`PutawayTaskCreator.on(ReceiptStockReadyForPutaway)`, per event line, for the stock that is INBOUND at the line's
`locationCode`:

1. **One task per carton** of the receipt line that is IN_STOCK, at that location, with INBOUND rows: quantity =
   what is left in it, `stock_package_id` set. Cartons QC quarantined or rejected are QUARANTINE / BLOCKED in the
   quarantine area and drop out by themselves.
2. **One task for the loose part**: quantity = the event line's quantity less the units of the cartons in 1;
   none when that is 0. Its source is not stored (no column): it is the loose INBOUND rows of **the lot this
   receipt opened** for the line (the `lot_id` of the line's RECEIPT ledger rows). Each receipt opens its own lot
   per (SKU, lot number, expiry), so this reaches exactly this receipt's goods wherever they are now — the
   RECEIVING area, or the QC area after `moveToQc` — and nothing quarantined. Two lines of one receipt with the
   same SKU, lot number and expiry share that lot; their loose tasks draw on one pool whose total is theirs.
3. **Idempotent**: the listener is retried by Spring Modulith's publication registry. A carton cannot get a
   second open task (`uk_putaway_task_open_package`; `open` returns the existing one). A loose task is looked up
   by (receipt line, no carton) before one is opened.

A task's warehouse is the receipt's (the event's `warehouseId`); it is not stored (no column) and is recovered
from the task's source when needed (§8).

## 5. Suggestions (`PutawayRanker`)

Computed on `GET /putaway-tasks/{id}`, never stored (D1).

**Hard filters** — a bin failing one is dropped, never down-ranked (BR-SLT-001, BR-SLT-003 at bin level):

| Filter | Rule |
|---|---|
| Kind and place | A BIN (not an AREA) of the task's warehouse, `usable` (itself, its shelf and its warehouse active). |
| Storage class | The bin's class equals the item's `storage_class`. |
| Capacity | When `capacity_units` is recorded: `capacity_units − units on hand at the bin ≥ units needed` (a carton task: the carton's units; a loose task: at least 1, the free room is shown so the clerk can split). |
| Weight | When `max_weight` and the item's `weight_kg` are recorded: `Σ on_hand × weight_kg of every SKU at the bin + units × weight_kg ≤ max_weight`. An SKU without a weight adds nothing (stated in the reason). |

**Ranking** — within a rank, more free room first, then location code:

1. the bin already holds this SKU **and** lot — reason `SAME_LOT`;
2. the bin already holds this SKU — `SAME_SKU`;
3. the bin is empty — `EMPTY`;
4. the bin holds other goods and has room — `HAS_ROOM`.

At most 10. Each suggestion is `{ locationCode, reason, freeUnits?, freeWeightKg? }`; the reason is a code the
client translates (BR-SLT-004 asks for a stated reason; storing it waits for round B).

**Queries**: the candidates in one call to `warehouse :: api`; units and weight on hand at those bins in one
grouped query in inventory (`stock_item` joined with `inventory_items` for weight, both inventory's schema).
Never a query per bin.

**Not in this round (SCRUM-91)**: shelf-level `max_weight` / `usable_height`, hazmat and neighbour rules,
single-SKU bins, volume. BR-SLT-003 is enforced at bin level only; the spec of SCRUM-91 takes the rest.

## 6. Confirming

`POST /api/v1/putaway-tasks/{id}/confirmations` with `{ locationCode, lpn }` for a carton task or
`{ locationCode, quantity }` for a loose one. One `@Transactional` method:

1. **Lock the task** (`PutawayTasks.lockForUpdate`, `SELECT … FOR UPDATE`).
2. **Scope**: the task's source location is in a warehouse the caller is assigned to
   (`WarehouseScope.requireLocation`), before anything else is said about the task — its status included.
   Then a DONE task is `PUTAWAY_TASK_DONE`.
3. **Scan**: a carton task needs `lpn` equal to its carton's (`PUTAWAY_LPN_MISMATCH`); a loose task needs
   `1 ≤ quantity ≤ left`, where `left = task.quantity − Σ quantity of the ledger lines (MOVE, PUTAWAY_TASK,
   taskId)` (`PUTAWAY_QUANTITY_EXCEEDED`).
4. **Target** (hard rules apply to a bin the clerk chose too): exists (`LOCATION_NOT_FOUND`), is a BIN
   (`PUTAWAY_TARGET_NOT_BIN`), same warehouse (`MOVE_ACROSS_WAREHOUSES`), usable (`LOCATION_NOT_USABLE`), the
   item's storage class (`LOCATION_STORAGE_CLASS_MISMATCH`), and within capacity and weight after the putaway
   (`LOCATION_CAPACITY_EXCEEDED`). A bin outside the suggestions is allowed (BR-SLT-002's reason waits for
   round B).
5. **Move**, lock order **task → SKU advisory lock → carton → stock rows by id**, the order picking keeps (pick
   row first, stock after):
   - carton: `relocatePackages(target = AVAILABLE)` — the carton and its rows move whole, keep their ids and
     stay unopened; rows must be INBOUND (`STOCK_STATUS_MISMATCH`), the carton IN_STOCK
     (`INVALID_STOCK_PACKAGE_TRANSITION`). It moves from wherever it stands now in the warehouse (it may have
     been moved by hand since the task was made).
   - loose: `quantity` units from the loose INBOUND rows of the receipt's lot (§4.2) — one lot, so one expiry;
     rows taken in id order — to the bin as AVAILABLE; one ledger line `(MOVE, PUTAWAY_TASK, taskId)` per
     source row taken from. Fewer units there than asked (moved by hand since) is `STOCK_ITEM_NOT_FOUND` /
     the move's own insufficient-stock refusal, and nothing is recorded.
6. **Record**: `recordPutaway` → IN_PROGRESS while units are left, DONE when none are; `target_location_id` is the
   bin of the latest confirmation (every bin is in the ledger).
7. **Publish** `PutawayCompleted` when the task became DONE.

**Partial confirmations and replay.** All confirmations of one task share the reference `(PUTAWAY_TASK, taskId)`,
so `relocate`'s replay lookup — "a MOVE line with my reference means I already ran" — would take the second
partial putaway for a retry of the first and do nothing. Putaway therefore does not use that lookup. A retried
HTTP request is answered by the `Idempotency-Key` filter; without one, a repeat is a second putaway, bounded by
`left` computed under the task lock — never more than the task's quantity in total.

## 7. Closing the receipt (procurement)

A listener on `PutawayCompleted`, and the transition into `IN_PUTAWAY` itself, ask: is every line with
`quantityForPutaway() > 0` fully put away, i.e. the DONE tasks of that line (`doneQuantityByReceiptLine`) add up
to it? If so, `IN_PUTAWAY → CLOSED` with `closedAt`.

- Counting quantities, not "no open task left", because tasks are made after commit by a listener: a line can
  briefly have no task at all, and "nothing open" would close the receipt too early.
- Checking also on entering `IN_PUTAWAY`: in a receipt mixing QC and non-QC lines, the non-QC lines can be put
  away while the receipt is still `IN_QC`; if the last QC decision then accepts nothing, no further
  `PutawayCompleted` would ever arrive.
- A receipt not `IN_PUTAWAY` is left alone; a second listener finding it `CLOSED` does nothing.

## 8. API

| Method and path | Permission | Answer |
|---|---|---|
| `GET /api/v1/putaway-tasks?warehouseId=&status=&receiptId=&page=&size=` | READ | Tasks with SKU, lot, quantity, left, source location, LPN (carton tasks), status, receipt number. |
| `GET /api/v1/putaway-tasks/{id}` | READ | The task and its suggestions (§5). |
| `POST /api/v1/putaway-tasks/{id}/confirmations` | UPDATE | The task after the confirmation, with the bin and what is left. |

**Listing by warehouse without a warehouse column (D1).** A task carries no warehouse; its warehouse is that of
its source (the carton's location, or the receipt lot's `warehouse_id`). One of `receiptId` and `warehouseId` is
required (`VALIDATION_FAILED` otherwise). With `receiptId` the list is that receipt's tasks, every status, each
checked against the caller's warehouses. With `warehouseId` (one the caller is assigned to, 403 otherwise) it is
the **open** tasks (PENDING, IN_PROGRESS) of that warehouse: open tasks are read, their sources resolved in one query per kind (cartons, lots), filtered, then
paged. Open tasks are few (they close as goods reach the bins); DONE tasks are listed per receipt. Round B adds
`warehouse_id` and this becomes a plain query.

## 9. Errors

Reused: `LOCATION_NOT_FOUND` 404, `LOCATION_NOT_USABLE`, `MOVE_ACROSS_WAREHOUSES`, `STOCK_STATUS_MISMATCH`,
`INVALID_STOCK_PACKAGE_TRANSITION`, `VALIDATION_FAILED` (an `lpn` on a loose task, a `quantity` on a carton
task), 403 for another warehouse.

New, each with `error.<CODE>` in `messages.properties` and `messages_vi.properties`:

| Code | HTTP | When |
|---|---|---|
| `PUTAWAY_TASK_NOT_FOUND` | 404 | No such task. |
| `PUTAWAY_TASK_DONE` | 409 | The task is DONE. |
| `PUTAWAY_LPN_MISMATCH` | 409 | The scanned LPN is not the task's carton. |
| `PUTAWAY_QUANTITY_EXCEEDED` | 422 | More units than the task has left. |
| `PUTAWAY_TARGET_NOT_BIN` | 422 | The target is a storage area, not a bin. |
| `LOCATION_STORAGE_CLASS_MISMATCH` | 409 | The bin's storage class is not the item's. |
| `LOCATION_CAPACITY_EXCEEDED` | 409 | The bin would pass its `capacity_units` or `max_weight`. |

## 10. Tests

- **Unit**: `PutawayRanker` (each filter, null capacity / weight / item weight, the four ranks, free-room order,
  the cap of 10, reasons); `PutawayTask` (PENDING → IN_PROGRESS → DONE, refusing work on DONE, refusing more than
  left).
- **Integration** (`@IntegrationTest`, committing, so the carton-content check of `V20261020000200` runs):
  - 2-step receipt: a task per carton and one loose task at the RECEIVING area; 3-step: tasks only for the
    accepted part at the QC area, none for quarantined or rejected cartons; the event delivered twice opens no
    second task.
  - Carton putaway: the carton and its row in the bin, AVAILABLE, unopened, same row id, one ledger line
    `(MOVE, PUTAWAY_TASK)` naming the carton on both sides, task DONE, `PutawayCompleted` published.
  - Loose putaway into two bins, task DONE after the second; more than left refused; a repeated confirmation
    after DONE refused.
  - Every hard rule refused on a bin the clerk chose: area, other warehouse, unusable, storage class, capacity,
    weight.
  - The receipt CLOSED when its last line is put away; the mixed QC / non-QC case closing on entering
    `IN_PUTAWAY`.
  - ATP of the SKU grows by the units put away.
- **HTTP**: warehouse staff confirm, READ-only roles get 403 on POST, a task of another warehouse is 403, the
  JSON shape of a task with suggestions.
- `ModularityTest` (inventory uses only `warehouse :: api`), `ArchitectureTest`, `MessageBundleTest`,
  `tools/verify.py`; one full `mvn verify` at the end of the branch.

## 11. Out of scope, and round B

- **Round B (needs Tú's columns on `putaway_task`)**: `assigned_to` and assignment, `warehouse_id`, source
  location, stored suggestions with reasons (BR-SLT-004 "stored"), the reason / manager approval of BR-SLT-002.
- **SCRUM-91**: the full slotting engine (shelf level, hazmat, neighbours, single-SKU, volume).
- Putaway of transfers and returns; cancelling a task; re-putaway (`X-*` re-slotting).
- SCRUM-107 (Vũ): the mobile scanning screen uses §8.

## 12. Docs to update with the code

- `CLAUDE.md` §4: a "Putaway" paragraph — the lock order task → SKU → carton → rows, why putaway skips the
  reference replay, and D4.
- `docs/business-design/04c-all-flows.md` `F-PUT`: note that confirmation is synchronous in inventory (D4) and
  what round A does not store.
- Issue #138: tick "Cất hàng theo thùng" when merged; #191 closes with it.
