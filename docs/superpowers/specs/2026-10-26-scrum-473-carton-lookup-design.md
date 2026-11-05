# SCRUM-473 — Carton lookup ("Tra cứu thùng")

GitHub #125 item 11, the "Tra cứu thùng" line of #138. Stacked on PR #197 (`feature/SCRUM-473-receive-in-cartons`).
Read-only API in `inventory`; no migration, no change to the write side.

## 1. Purpose

A warehouse worker scans a carton label and needs to know what the carton is, how much is left in it and where it
should be. A supervisor standing at a bin (or an area: receiving, QC, quarantine…) needs to know what is there, split
into whole cartons, opened cartons and loose units, so a count or a pick can be planned. The back office needs a
list of cartons to filter.

Success: three GET endpoints under the existing `inventory-stock-packages` resource, correct under warehouse data
scope, each answering in a fixed number of queries whatever the number of cartons.

## 2. What exists and is reused

| Need | Reused |
|---|---|
| LPN normalisation (trim, upper-case) | `StockPackageView.normalLpn` |
| One row per carton with its SKU, unit, lot, expiry, on hand and reserved | the view `inventory.stock_package_balance` (V20261022000100 §3, written "for the LPN lookup, the stock-by-bin screen and reports"); already read by `CycleCountRepositoryAdapter` |
| Warehouse scope | `WarehouseScope.requireLocation`, `WarehouseScope.locationsIn("locationCode")` |
| Location exists? | `LocationDirectory.stateOf` (asks `warehouse :: api`) → `LOCATION_NOT_FOUND` |
| Paging / sorting | `Pages`, `SortWhitelist`, `PageResponse` |
| Permission | `inventory-stock-packages:READ` — seeded for WAREHOUSE_MANAGER, WAREHOUSE_STAFF, INVENTORY_PLANNER, QC_STAFF, SYSTEM_ADMIN (V20261020000700) |

No new `ErrorCode` is needed: `STOCK_PACKAGE_NOT_FOUND` (404), `LOCATION_NOT_FOUND` (404), `OUT_OF_DATA_SCOPE`
(403) and `VALIDATION_FAILED` (400) already exist with both bundle entries.

## 3. Endpoints

All three on `StockPackageController` (`/api/v1/inventory/stock-packages`), public handlers,
`@RequiresPermission(resource = STOCK_PACKAGES, action = READ)`. The read logic goes in a new
`StockPackageLookup` service next to `StockPackageLabels` (`inventory/internal/service/`), `@Transactional(readOnly
= true)`. The two aggregate reads go in a new read-side port `StockPackageSearch` / `StockPackageSearchAdapter` in
`inventory/internal/repository/`, as `StockBrowseSearch` does (paging is Spring Data vocabulary, so it stays out of
the domain port). Nothing returned is a JPA entity.

### 3.1 `GET /stock-packages/{lpn}` — one carton

Order of checks: normalise the LPN → read it (absent: 404 `STOCK_PACKAGE_NOT_FOUND`) → `WarehouseScope.requireLocation`
(403 before anything else is said about it, its status included — as `StockPackageLabels.named` does) → build.

Response `StockPackageDetailResponse`:

```
lpn, sku, unitOfMeasure, nominalQty (the unit's factor when the carton was made), receivedQty (null until counted),
lotNumber, expiryDate   of its rows holding stock (one lot at most per carton, rule 2 of V20261022000100)
onHand, reserved        summed over its rows (0 when it has none, e.g. RECEIVING); reserved > 0 = held for an order
status, unopened, locationCode, goodsReceiptLineId (null for a carton made in the warehouse), closedAt,
contents: [ { lotNumber, expiryDate, receivedAt, status, onHand, reserved } ]   its rows with on_hand > 0, FEFO order
```

Queries: the carton's row of `stock_package_balance` (1), its stock rows (1). The goods-receipt *number* is not
returned: it lives in `procurement`, which inventory does not read; the line id is what the receipt screen links by.
The unit's current factor is not returned either: `nominalQty` is what the carton was made to hold, which is what a
reader of the label compares with.

### 3.2 `GET /stock-packages/location-summary?location={code}` — what is at one location

`location` is one exact location code, a bin or an area (`HCM-A01-1-A`, `HCM-RCV01`). Not a prefix: a prefix such as
`HCM-A0` silently spans shelves, and `HCM` would also match a warehouse `HCMX`. Order: `requireLocation` (403) →
`LocationDirectory.stateOf` UNKNOWN → 404 `LOCATION_NOT_FOUND` → one aggregate query.

One row per **SKU, lot and stock status** (status added to the key: a QC area may hold the same lot as HOLD and as
AVAILABLE, and adding them up would hide that):

```
sku, lotNumber, expiryDate, status,
wholeCartons, wholeCartonUnits      unopened cartons and what is in them
openedCartons, openedCartonUnits    opened cartons and what is left in them
looseUnits                          rows with no carton
totalUnits                          the three unit columns added
```

One native query over `inventory.stock_item` (`location_code = :code and on_hand > 0`) left-joined to
`inventory.stock_package`, grouped by sku, lot, expiry, status, with `count(distinct package) filter (where …)` and
`sum(on_hand) filter (where …)`. Ordered by sku, expiry (nulls last), lot. Not paged: one location holds a bounded
number of SKU/lot pairs. A carton whose rows carry two lots is counted once in each lot's row — per lot that is the
truth.

### 3.3 `GET /stock-packages?location=&sku=&status=&page=&size=&sort=` — paged list

Filters, all optional: `location` (exact code), `sku`, `status` (repeatable). With no `status`, only live cartons
(RECEIVING, IN_STOCK): a CONSUMED carton keeps the location it was emptied at, and listing it at that bin would look
like stock. Always narrowed to the caller's warehouses (`WarehouseScope.locationsIn`); a `location` outside them is
403. Sort whitelist: `lpn` (default, ascending; an LPN carries its day, so this is also creation order),
`locationCode`, `sku`.

Each row `StockPackageResponse`: `lpn, sku, unitOfMeasure, nominalQty, receivedQty, lotNumber, expiryDate, onHand,
reserved, status, unopened, locationCode, goodsReceiptLineId`.

Queries: count + page over `stock_package_balance`, whatever the page size. An unknown SKU is an empty page, not 404:
it is a filter.

The reads go through `NamedParameterJdbcTemplate`, as `CycleCountRepositoryAdapter` reads the same view: a view has
no entity, and the lookup is read-only, so nothing pending in the persistence context needs flushing first.

## 4. Lost / found — not an endpoint (decision needed)

`StockPackage.lose()` / `found()` are already reachable, through the reviewed path only:
`CycleCountCountingService` submits a counted carton found empty as a pending `COUNT_VARIANCE` adjustment with
`missingWhenEmpty`, and on approval `StockPackageContents.adjust` writes its rows down and marks it LOST; a later count
that finds it writes it up and `fill` calls `found()`. V20261020000700 says the same in its header ("a lost carton is
booked through an approved count or adjustment") and gives staff no UPDATE.

**Recommendation: ship no direct LOST/FOUND endpoint.** A carton that still holds stock cannot be LOST without
writing that stock off, and writing stock off is four-eyes (SCRUM-145). A direct "mark lost" for an *empty*
carton has no use: an empty carton is already CONSUMED. If the team still wants a manager shortcut, it would be a
separate task that raises the same `COUNT_VARIANCE` adjustment rather than changing the carton itself.

## 5. Errors

| Case | Answer |
|---|---|
| unknown LPN | 404 `STOCK_PACKAGE_NOT_FOUND` |
| carton / location in a warehouse not the caller's | 403 `OUT_OF_DATA_SCOPE`, checked before existence or status is revealed |
| unknown location in the summary | 404 `LOCATION_NOT_FOUND` |
| blank `location` in the summary | 400 `VALIDATION_FAILED` (required parameter) |
| bad sort property | 400, from `SortWhitelist` |

## 6. Tests

- `StockPackageLookupIntegrationTest` (`@IntegrationTest`, `PostgresContainer`; fixture as
  `StockPackageMoveIntegrationTest`: a line of 2000 counted as cartons of 1000 and 980, received, 20 loose):
  detail by lower-case LPN with contents and onHand; RECEIVING carton has onHand 0 and no contents; summary at the
  receiving area = 2 whole cartons / 1980 units + 20 loose; after one carton is opened, 1 whole + 1 opened; list
  filters by SKU, location and status, default hides CONSUMED/VOID. (Query counts are fixed by construction - one
  SQL statement per method of `StockPackageSearch`, no loop over cartons - and JDBC reads are not visible to
  Hibernate statistics, so no statistics test.)
- `StockPackageLookupHttpTest` (`@SecuredIntegrationTest`, like `StockPackageLabelHttpTest`): staff of the warehouse
  read all three (200); INVENTORY_PLANNER and QC_STAFF read (READ-only roles); a staff member of another warehouse
  gets 403 on the detail (a VOID carton too, not its status), on the summary, and on a list filtered to that
  location, and sees an empty list unfiltered; unknown LPN 404; unknown location 404.

## 7. Out of scope

Lost/found endpoint (§4), prefix/shelf summaries, the receipt number in the detail, carton history (the ledger of
its rows is `LotTrace`/ledger territory), putaway (SCRUM-92, sibling session).
