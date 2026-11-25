# ADR-0011 — Stock is recorded as cartons, not as units

* **Status:** Accepted — being carried out in steps; steps 1 and 2 are done (see *Steps*). Amends kltn-docs 01 BR-09 and
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
   loose lines a hand-kept database has stay as they are). The four CHECKs that said "this kind of line names a carton"
   and `ck_stock_movement_package_side` went into that one rule; whether a carton arrived, moved or left is read from
   `movement_type` and the line's places. `quantity` stays until step 3.
   The HTTP contract changes with it: a ledger line (`GET /inventory/movements`) answers one `lpn` where it answered
   `fromLpn` and `toLpn`.
3. **The carton is the stock** - `stock_item` folded into `stock_package`, `stock_reservation` by carton,
   `stock_movement.quantity` dropped, the `inventory :: api` availability types answer in cartons.

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
