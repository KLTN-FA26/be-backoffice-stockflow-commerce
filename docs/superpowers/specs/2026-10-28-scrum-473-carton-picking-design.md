# SCRUM-473 — Picking by carton (design)

GitHub #138, feature line "Soạn & giao theo thùng"; #125 item 10. Pick only: packing and shipping stay as they are.

## 1. Goal

When an order is released to a warehouse, its stock is drawn **whole cartons first for the round part and opened
cartons first for the remainder**, without ever breaking FEFO/FIFO (BR-STK-006). A pick line drawn from a carton names
that carton's LPN, and the picker must scan **that** LPN to confirm it.

Success:
- An order for 2,300 units of a SKU with sealed cartons of 1,000 is allocated two whole cartons and 300 from an opened
  carton (or loose stock, or one newly opened carton, in that order), all within the earliest-expiring group.
- The pick list shows the LPN for each carton line and whether the line takes the whole carton.
- Confirming a carton line with the SKU, the barcode or another LPN is refused.
- Picking a whole carton closes it CONSUMED; picking part of one leaves it opened — both already done by
  `PickConsumptionService` / `StockPackageContents`, unchanged.

## 2. Decisions (agreed 2026-10-28)

| Point | Decision |
|---|---|
| Scope | Picking only. A picked whole carton is not turned into a shipping package (packing: SCRUM-279/283/284). |
| Wrong carton scanned | Refused. No swapping the hold onto the carton actually scanned. |
| Remainder order | Opened cartons → loose units → open a sealed carton. |
| LPN on the pick line | Stored on `fulfillment.pick_line` (approach A), one migration, under the carton delegation of #125. |

## 3. Allocation order — `StockAllocator`

### 3.1 Candidate

`StockAllocator.Candidate` gains two fields, both read by the existing projection query (no entity is loaded before
the locks):

- `stockPackageId` — the carton the row is in, null for loose stock.
- `wholeCarton` — the row is the carton's entire content and nothing of it is held: carton `unopened`, row
  `reserved = 0`, row `on_hand = received_qty`.

A carton row that is not whole is treated as **opened**, including a sealed carton another order already holds part of:
that order's pick will open it anyway.

The existing constructors stay (loose, not whole), so callers and tests that build candidates by hand are unchanged.

`AvailabilityRow` and `StockItemJpaRepository.findAvailabilityBySku` select the two facts with a left join on
`StockPackageJpaEntity` (`s.stockPackageId = p.id`). Still one query per SKU, still a constructor expression.

### 3.2 Plan

The primary order is unchanged: FEFO = expiry, then received-at; FIFO = received-at; expired stock is never planned.
(The older `plan(sku, candidates, required)` overload orders by expiry alone; its groups are equal expiry.)
Candidates are split into **groups** of equal primary key. Groups are consumed in order; every group before the one
where the need runs out is taken entirely, so the order inside it does not matter. Inside the **last** group:

1. Whole cartons, largest first, each taken whole while it fits in what is still needed.
2. Opened cartons, smallest available first.
3. Loose rows, smallest available first (today's tie-break).
4. Whole cartons left over, smallest first: the first one is broken for the rest.

Ties inside each step break on location code, then stock item id, as today, so a plan is deterministic.

This never fails when the group holds enough: after step 1, every whole carton left is larger than what is still needed,
so either steps 2–3 cover it or step 4 does. A group with only loose rows gives exactly today's plan.

`plan(...)` (both overloads) uses this order, so it applies to every caller: order allocation and reallocation after a
short pick, production material holds (`reserveForProduction`) — all in `InventoryServiceImpl` — and transfer orders
(`TransferOrderServiceImpl`). Production and transfers gain the carton order but no LPN scan (§6).
`comparator(strategy)` stays as it is (it only orders the stock-by-location display).

A carton spread over more than one stock row (e.g. part of it in another status) has no whole row, so all its rows count
as opened. That is the safe side: it is never promised as a whole carton.

## 4. LPN on the pick line

### 4.1 `inventory :: api`

`HoldView` gains `lpn` (null for loose stock or a SKU reservation) and `wholeCarton` (the hold is the carton's whole
content: carton unopened, hold quantity = row on-hand = received quantity). The existing constructors stay and pass
`null` / `false`. `HoldDetail` and `StockItemJpaRepository.findHolds` select them with the same left join.

### 4.2 Migration `V20261028003000__fulfillment_pick_line_carton.sql`

```sql
ALTER TABLE fulfillment.pick_line
    ADD COLUMN lpn          VARCHAR(30),
    ADD COLUMN whole_carton BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT fk_pick_line_lpn FOREIGN KEY (lpn) REFERENCES inventory.stock_package (lpn),
    ADD CONSTRAINT ck_pick_line_whole_carton CHECK (NOT whole_carton OR lpn IS NOT NULL);
```

- A trigger on INSERT and on UPDATE OF `lpn`, `reservation_id`: `lpn` must be the LPN of the carton of the
  reservation's stock item, and NULL when that row is loose or there is no reservation. The invariant is stated twice
  (CLAUDE.md §7): in `PickList.plan`, which copies it from the hold, and here.
- Backfill: lines whose hold sits on a carton row get its LPN; `whole_carton` stays FALSE for them (not known after
  the fact; it is a hint, not a rule).
- Tests: `PickLineCartonMigrationTest` pins the trigger, the check and the backfill — wrong LPN refused, LPN on a loose
  hold refused, carton hold without LPN refused, `whole_carton` without LPN refused. Not `tools/db/qa`: a pick line
  there needs an order, a task and a hold built by hand.

Version must be newer than the newest on `develop` at merge (today `V20261028002000`). Tú is told: the schema is
theirs; this migration is written under the #125 delegation.

### 4.3 Fulfillment

- `PickList.Hold` and `PickLine` gain `lpn` and `wholeCarton`; `PickLine.open` takes them, `PickList.plan` copies them
  from the hold. `PickLineJpaEntity` maps both columns, `updatable = false`.
- `PickListService.holdsOf` passes them from `HoldView`.
- `PickLists.PickLineView` and `PickLineResponse` gain `lpn` and `wholeCarton`; the MapStruct mapper maps them.

## 5. Scanning — `PickList.confirm`

The request keeps its shape: `locationCode`, `code`, `quantity`.

- A line with an LPN: after the location, `code` must be that LPN (normalised as `StockPackageView.normalLpn`: trimmed,
  upper case). The SKU, the barcode or another LPN is `PICK_ITEM_MISMATCH` with the message
  "This line picks carton LPN-…". No new error code.
- A loose line: unchanged, SKU or barcode.
- The replay check (same scan on a PICKED line returns it unchanged) uses the same rule.
- Quantity rules unchanged: a whole-carton line is confirmed with the carton's quantity, a part with the part.

## 6. Not in this change

- Packing a whole carton as a shipping package.
- Swapping a hold onto the carton actually scanned.
- Put-back into a carton: a CONSUMED carton is final, so goods put back stay loose, as today.
- Short picks on carton lines: X-SHORT already counts a carton row as its carton (`PickConsumptionService`).
- Transfer picking (`TransferPack`) and production material issue do not get an LPN scan; only their allocation
  order changes (§3.2). Issuing blanks to print by carton is its own #138 line.

## 7. Tests

| Level | What |
|---|---|
| `StockAllocatorTest` (pure) | whole cartons for the round part; remainder from opened before loose before breaking a sealed carton; largest whole first; a group with only loose rows plans as before; FEFO beats carton preference (an opened earlier-expiring carton is taken before a whole later one); FIFO groups by received-at; never fails when the group suffices |
| `PickListTest` (domain) | carton line: right LPN confirms, SKU/barcode/other LPN refused, replay with the LPN; loose line unchanged |
| Integration (Testcontainers) | `CartonAllocationIntegrationTest`: release onto a bin with two sealed cartons, one opened carton and loose units — holds name the expected LPNs and `wholeCarton`; a partly held carton is not whole; a loose hold has no LPN. `PickLineCartonMigrationTest` (§4.2). Carton open/close on a pick stays covered by `StockPackageConsumptionIntegrationTest`; the scan rule by `PickListTest` |
| Existing suites | `StockPolicyTest`, `SkuReservationTest`, transfer order tests, pick confirmation tests stay green |
| Gates | `ModularityTest`, `ArchitectureTest`, `tools/verify.py`, one full `mvn -o verify` |

## 8. Who to tell

- **Tú** — the migration on `fulfillment.pick_line`.
- **Võ** — `PickList` / `PickLine` (SCRUM-265/269) gain two fields and the LPN rule in `confirm`.
- **Frontend** — `PickLineResponse.lpn`, `wholeCarton`; scan the LPN for carton lines.
