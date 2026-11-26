# ADR-0011 — Stock is recorded as cartons, not as units

* **Status:** Accepted — being carried out in steps; steps 1, 2, 3a, 3b, 3c and 3d are done (see *Steps*). Amends kltn-docs 01 BR-09 and
  20 §1 ("base units are kept internally"), which must be reworded in the kltn-docs repository.
* **Date:** 2026-11-26
* **Deciders:** repository owner (Phương)
* **Project:** StockFlowCommerce (FA26SE029)

---

## Context

Since the rescope ([ADR-0010](0010-rescope-b2b-printed-goods.md)) stock is only ever whole, sealed LPN cartons of the
SKU's standard count: a carton is received whole, put away whole, allocated whole, picked whole, counted present or not
found, and written off or restocked whole. The schema still recorded all of that in units, in seven tables, and then
spent triggers forcing each unit figure to be one of two values:

| Column | The only values it could take |
|---|---|
| `stock_item.on_hand` | 0, or the carton's `nominal_qty` |
| `stock_item.reserved` | 0, or `on_hand` |
| `stock_package.received_qty` | NULL (not counted in yet), or `nominal_qty` |
| `cycle_count_line.expected_qty`, `counted_qty` | 0, or `nominal_qty` |
| `stock_adjustment.quantity_delta` | plus or minus `nominal_qty` |
| `stock_movement.quantity` | `nominal_qty` of the carton the line names |
| `stock_movement.from_stock_package_id`, `to_stock_package_id` | the same carton, or one of them NULL |

One carton has exactly one `stock_item` row (`uk_stock_item_package`), so that row is the carton's state under another
name. The copies were kept equal by `check_stock_package_contents`, `ck_stock_package_standard_count`,
`check_allocation_whole_carton`, `check_cycle_count_line_carton` and `check_stock_adjustment_whole_carton` - except
`stock_item.reserved`, which nothing reconciles with `stock_reservation`. Outside `inventory` almost nothing reads the
unit figures of stock (one file in `fulfillment`); the hold command already speaks cartons (`HoldStockCommand.cartons`).

Units are still what money is counted in: `order_line.quantity` and `unit_price`, invoices, installments, and the
refund of an opened returned carton (`goods_receipt_return_cartons.counted_qty`). None of that is stock.

## Decision

**In `inventory`, the carton is the unit. The number of units a carton stands for is written in one place,
`stock_package.nominal_qty` (a snapshot of `uom_conversions.factor` taken when the carton is created), and read from
there by whoever needs units.** Anything else that was an integer of units becomes what it actually is: a yes/no, a
direction, or the carton's id.

Target shape (the full table list is the owner-approved proposal of 2026-11-26):

* `stock_package` absorbs `stock_item`: it carries the stock status (`AVAILABLE`, `QUARANTINE`, ...), the lot, the FIFO
  date, and `counted_at` in place of `received_qty`. `stock_item`, `on_hand` and `reserved` go.
* `stock_reservation` holds cartons (`cartons`, with the packing unit they are of) and an allocation names its carton
  (`stock_package_id`); "is this carton taken" is read from the allocation, not from a second counter.
* `stock_movement` names one carton (`stock_package_id`, required) and no quantity: one line is one carton.
* `cycle_count_line` records `booked` and `found`; `stock_adjustment` records a `direction`.

Units stay in `order`, `payment`, `invoicing` and on return receipts.

## Steps

The change touches locking, allocation, receiving, picking and returns, so it lands table by table, each step green on
its own:

1. **Counts and adjustments** - `V20261230000100`: `cycle_count_line.booked` / `found`, `stock_adjustment.direction`,
   both documents name their carton (`stock_package_id NOT NULL`). Kept for now: the `sku`, `lot_number` and
   `location_code` snapshots on both tables, because the lot still lives on `stock_item` until step 3.
   The HTTP contract changes with it: `StockAdjustmentResponse.direction` and `CycleCountLineAdjustment.direction`
   (`WRITE_OFF` | `RESTOCK`) replace `quantityDelta`. A count line already answered `booked` / `found`.
2. **Ledger** - done, `V20261230000200`: `stock_movement.stock_package_id` replaces `from_stock_package_id` and
   `to_stock_package_id`, and every new line must name its carton (`ck_stock_movement_names_carton`, NOT VALID so that
   loose lines a hand-kept database has stay as they are - until step 3b removed them). The four CHECKs that said "this kind of line names a carton"
   and `ck_stock_movement_package_side` went into that one rule; whether a carton arrived, moved or left is read from
   `movement_type` and the line's places. `quantity` stays until step 3.
   The HTTP contract changes with it: a ledger line (`GET /inventory/movements`) answers one `lpn` where it answered
   `fromLpn` and `toLpn`.
3. **The carton is the stock** - `stock_item` folded into `stock_package`, `stock_reservation` by carton,
   `stock_movement.quantity` dropped, the `inventory :: api` availability types answer in cartons.
   * **3a, the count** - done, `V20261230000300`: `stock_package.counted_at` (NULL until the carton is counted in at
     receiving) replaces `received_qty`; `ck_stock_package_received` and `ck_stock_package_standard_count` went with
     the number, `ck_stock_package_counted` reads the new column. A counted carton takes its `created_at` (the
     application counts a carton in the call that labels it); the migration refuses to run over a carton counted at
     another figure than its `nominal_qty` rather than call it whole. Whoever needs units reads `nominal_qty`.
     The contract changes with it: `countedAt` replaces `receivedQty` on a carton (`GET /inventory/stock-packages...`
     and a goods receipt line's `packages[]`), and on `inventory :: api` `StockPackageView`; the error code
     `RECEIPT_PACKAGE_NOT_STANDARD` is gone, nothing can raise it.
   * **3b, the ledger line** - done, `V20261230000400`: `stock_movement.quantity` is dropped with
     `ck_stock_movement_qty`; a line is its carton, whole, and `stock_package_id` is NOT NULL
     (`ck_stock_movement_names_carton` went into it). The lines of loose stock that step 2 let stand are deleted, not
     converted (QD-1); the migration refuses to run over a line whose quantity is not its carton's `nominal_qty`
     rather than drop a number that said something. No function, view or trigger read the column
     (`procurement.check_return_receipt_closed` already counted the ledger's lines against the cartons put into
     stock). Whoever sums the ledger joins the carton: `StockLedgerTotalsAdapter`, the lot trace,
     `tools/db/stock_ledger_balance.sql`. In the code `StockMovement` has no `quantity` and always a carton,
     `StockMovementLog.findLooseByReference(s)` and `sumQuantityByReference` are gone (a putaway task is one carton:
     `countByReference`), and so are the loose-task branches of `PutawayService` - a putaway task without a carton
     cannot exist (`ck_putaway_task_source`, `ck_putaway_task_receipt_carton`).
     No HTTP contract change: a ledger line (`GET /inventory/movements`) and a lot trace movement still answer
     `quantity`, now the carton's `nominal_qty`. `inventory :: api` `StockMove` loses `quantity` (nothing outside
     `inventory` read it).
   * **3c, the reservation** - done, `V20261230000500`: `stock_reservation.cartons` and `units_per_carton` replace
     `quantity`, and `expires_at` goes with `ck_stock_reservation_quantity`, `ck_stock_reservation_allocation`,
     `ck_stock_reservation_expired_had_deadline` and `ix_stock_reservation_expiry` (no hold has carried a deadline
     since V20261117000300). A SKU-level hold is N cartons of the count its order line was quoted in; an allocation
     is one carton (`ck_stock_reservation_one_carton`) of its carton's `nominal_qty`
     (`check_allocation_whole_carton`, rewritten). `check_sku_reservation_covered` is per (SKU, count): the cartons
     held are covered by free AVAILABLE cartons of that count, over the system and at the hold's warehouse - no
     longer units against units. The migration takes a hold's count from its order line, else from the cartons its
     allocations took, else from the SKU's active packing unit; it refuses to run over a live hold that is not whole
     cartons of a known count, or a live row on a carton that holds part of it, and converts history by rule (a
     closed hold that is not whole cartons is rounded up). `stock_item` and `stock_item_id` are untouched (step 3d).
     In the code a `SkuReservation` stores `cartons` and `unitsPerCarton` and derives `quantity()`; a `Reservation`
     (the allocation) is one carton; `StockAllocator.plan` gives N cartons of the hold's count, FIFO, and the rule
     "a carton larger than what is still needed is skipped" is gone with the unit count it served: cartons of
     another count are not candidates at all. `holdAtWarehouse` promises a line only cartons of its count.
     Contract: `inventory :: api`, `inventory :: consumption` and the events keep their shape and keep answering
     units, derived; `HoldView.expiresAt` stays in the record and is always null; `shrinkHold(keepUnits)` refuses
     (`VALIDATION_FAILED`) a figure that is not a whole number of the hold's cartons. HTTP: a hold in
     `GET /inventory/reservations` loses `expiresAt` (it was always null); `quantity` stays, in units.
     Known limit, until the order module counts cartons too: with cartons of two counts of one SKU in a warehouse,
     the unit figures (`availableToPromise*`, `SkuHoldCover`) cannot say which count is free or short - a line is
     then held for fewer cartons than the units suggest, and `ShippingWarehouseService.giveUpUncovered` may cut a hold
     of a covered count before it reaches the short one. A line with no `units_per_carton` (the legacy redelivery
     path counts it one piece a carton) is held only from cartons of 1.
   * **3d, the carton is the stock** - done, `V20261230000600`: `stock_item`, `on_hand`, `reserved` and the view
     `stock_package_balance` are dropped. `stock_package` carries `stock_status` (set exactly while `IN_STOCK`,
     `ck_stock_package_stock_status`), `lot_id` (required once it has been in stock, `ck_stock_package_lot`) and
     `received_at` (nullable: undated sorts last); lot and date are written at RECEIVING -> IN_STOCK and frozen
     (`tg_stock_package_transition`). `stock_reservation.stock_package_id` replaces `stock_item_id`; a carton is
     taken exactly while a HELD row names it (`uk_stock_reservation_live_carton`) - HELD is the only live status of
     an allocation (CONSUMED, RELEASED and EXPIRED are closed; ALLOCATED is a SKU-level hold's). An allocation
     takes a free AVAILABLE carton of its count that stands in no staging area (`tg_stock_reservation_carton`, which
     locks the carton row before reading it), and a held carton stays AVAILABLE (`tg_stock_package_held_available`,
     a constraint trigger deferred to commit: the application closes the allocation and changes the carton in one
     flush, in an order it does not choose; staging keeps its held carton AVAILABLE and passes). The opened-return and
     returned-carton rules are CHECKs of the carton. The migration repoints every reservation before the table goes
     (`fk_reservation_stock_item` was ON DELETE CASCADE), deletes empty loose rows with their unreferenced closed
     history, and refuses, by name, a carton that has been in stock with no row, and a live hold that is not the one
     allocation of an AVAILABLE carton in stock.
     In the code `StockPackage` is the aggregate root and owns its allocations; `StockItem`, `StockItemRepository`,
     `saveMerged` and the `stockflow.same_receipt_merge` marker are gone ("replenishment needs a new layer" has
     nothing left to refuse: nothing can grow). **A carton that comes back is given its status by what brings it
     back**: put back after a cancelled order AVAILABLE; removed as damaged or received on a return QUARANTINE; a
     LOST carton found AVAILABLE, or QUARANTINE when it stands in a quarantine area. It keeps its lot and its FIFO
     date. Lock order everywhere: SKU advisory locks (sorted) -> cartons `FOR UPDATE` in id order, nothing after;
     allocate and reallocate now lock the cartons themselves (they locked only the stock rows, never the carton a
     move locks). A receipt with no origin opens its UNTRACED lot in the application (`StockLots.openUntraced`; a
     trigger of the stock row did).
     Contract: `inventory :: api`, `inventory :: consumption`, the events and every HTTP field keep their shape;
     fields named `stockItemId` carry the carton's id, and unit fields are cartons times `nominal_qty`. Noticeable:
     stock listings no longer show the zero rows of cartons that left stock (`availabilityOf`, levels), and a held
     carton is held whole in the lot trace. `lot_number` on count lines and adjustments may go in a later step.
   * **3e, cartons in the API** - done, no migration. Owner decision 2026-11-26: **add** carton figures beside the
     unit ones, remove nothing, so the frontend keeps working and moves to the carton fields at its own pace; the unit
     fields may be dropped once it has. The cartons are now **counted**, not divided out of the units by the SKU's
     standard count (`CartonCount.of`, gone), which gave no figure (null) as soon as cartons of two counts of a SKU
     stood in one warehouse or the SKU had no carton unit:
     - Inventory Level (`GET /inventory/stock-levels`, `inventory :: api` `StockLevel`): `onHandCartons`,
       `availableCartons`, `reservedCartons`, `allocatedCartons` are counted from the cartons in stock and the
       allocations naming them (`StockLevelLine.cartons` / `heldCartons`, `StockPackageRepository.Allocated`);
       `atpCartons` is worked out count by count as a hold is promised - free cartons of that count on pickable bins,
       less those held at the warehouse, never more than is left of that count system-wide - then summed. The five
       stay nullable in the schema for compatibility but are always set.
     - A stock row (`StockItemResponse`, `WarehouseStockItemResponse`) is one carton: its carton fields are 1 or 0
       (`CartonCount.ofOneCarton`), null only while withheld from a blind counter.
     - A hold (`GET /inventory/reservations`) answers `cartons` beside `quantity`.
     The unit figures are unchanged, and so are `inventory :: consumption`, the events and every other module.

## Decisions for the rest of step 3 (owner, 2026-11-27)

* **A hold says which cartons it wants by their count, not by a packing-unit id.** A SKU-level hold keeps
  `units_per_carton` (from `order_line.units_per_carton`) beside `cartons`, and is served only by cartons of that
  `nominal_qty`. Retired packing units of one item may share a factor, so an id would be ambiguous where the count is
  not. An order quoted in cartons of 1000 therefore takes cartons of 1000 only.
* **The FIFO date may be unknown.** `stock_package.received_at` stays nullable and an undated carton sorts last, as
  `StockAllocator` orders rows today; filling in `created_at` would reorder stock already on the shelf.
* **A carton out of stock has no stock status.** `stock_status` is set exactly while the carton is `IN_STOCK`; the
  stale status that consumed cartons' rows carried is not kept.
* **Other modules keep reading units** from `inventory :: api` and the events (cartons times `nominal_qty`): `order`
  and `fulfillment` do their arithmetic in units, and a serialized event must keep its shape for retries.
* **`sku` stays on count lines and adjustments**; only `lot_number` goes once the lot is on the carton. A carton's SKU
  never changes and a trigger already checks the copy, so it cannot drift, and the list filters use it.

## Consequences

* A whole class of states can no longer be written, so the triggers that refused them shrink to the rules that are
  still rules (the 500-line cap of a count, the six adjustment reasons).
* A reader of stock asks one table. `reserved` cannot drift from the reservations because it no longer exists.
* A report that wants units multiplies by `nominal_qty`; the ledger no longer answers "how many units" on its own.
* The frontend must read `direction` where it read `quantityDelta` (step 1), `lpn` where it read `fromLpn` / `toLpn` on
  a ledger line (step 2), and cartons where it read `onHand` / `available` (step 3).
* History: rows of loose stock from before the baseline are removed, not converted (QD-1: every database is rebuilt
  from the demo seed on deploy; the baseline had already boxed all loose stock).

## Alternatives considered

* **Keep the schema, show cartons.** Cheapest, and what the code did (`CartonCount`: units shown as cartons beside
  them). It leaves seven places that must agree and one (`reserved`) that nothing checks. Rejected by the owner.
* **Drop only the redundant document columns.** Leaves `stock_item` as the second name of a carton, which is the part
  that costs most to read and to keep consistent.
