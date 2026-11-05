# SCRUM-473 Receiving into cartons — design

*2026-10-26 · StockFlowCommerce (FA26SE029) · owner: Phương*

Jira: [SCRUM-473](https://minh7n3.atlassian.net/browse/SCRUM-473) · tracking issue #138 (line "Nhận hàng theo thùng")
· schema #125 · unfinished parts of #143 (`moveToQc`, `inspect`) carried here.

Items 7 and 8 of #125: a goods receipt labels each carton with an LPN while it is counted, records what each
carton actually holds, and on confirmation puts the goods into those cartons. After a confirmed receipt the
system answers the three questions #125 starts from: how many units, how many cartons, and where each carton
is and what is left in it.

## 1. What exists on `develop` (92bcd760)

| | State |
|---|---|
| Schema | Complete: `inventory.stock_package` (`RECEIVING → IN_STOCK | VOID`, …), `stock_item.stock_package_id`, `stock_movement.from/to_stock_package_id`, `goods_receipt_lines.uom_conversion_id`, the commit-time content check (`V20261020000200`), permission `inventory-stock-packages` (`V20261020000700`). No migration is needed for this design. |
| `StockPackage` | Domain, entity, repository with the lock order; `receiving(...)`, `count`, `putInStock`, `cancel`, `moveTo` exist and have **no caller**. |
| Taking stock out of a carton | Done (#140/#141): every path that lowers `on_hand` locks the carton and opens or empties it (`StockPackageContents`). Moves and adjustments named by (SKU, location, lot, receipt time) reach loose stock only. |
| Goods receipt | `replaceLines` keeps a line's id on recount and refuses to drop a line with cartons (`RECEIPT_LINE_HAS_PACKAGES`); `cancel` voids the line's RECEIVING cartons. Nothing creates a carton. Receipt lines do not map `uom_conversion_id`. |
| QC | `moveToQc` moves the line's quantity as loose stock; `inspect` reclassifies quarantined and rejected units as loose stock. Both fail on a line whose goods are in cartons. |
| Labels | `LocationLabelPdfRenderer`, `LabelFonts`, `LabelFormat` in `warehouse/internal/service` draw text, QR and Code 128 for location codes. |
| Putaway | **Nothing turns INBOUND stock into AVAILABLE**: `reclassify(AVAILABLE)` has no caller and there is no putaway. Out of scope here (SCRUM-92, next spec). |

## 2. Decisions taken on 2026-10-24/26

| # | Question | Decision |
|---|---|---|
| D1 | When is an LPN made? | While counting, on the DRAFT receipt. Cartons are created RECEIVING, recounted until confirmation, voided when dropped or when the receipt is cancelled. |
| D2 | How does QC treat cartons? | By whole carton: QC names the LPNs it quarantines or rejects; a carton moves whole and keeps its LPN, unopened. Loose units are decided by quantity as today. |
| D3 | Labels in this round? | Yes: a PDF with the LPN as text, QR and Code 128, through a shared drawing extracted to `common`. |
| D4 | API shape | Approach A: cartons are part of the existing `PUT /goods-receipts/{id}/lines` count. |
| D5 | Subcontract receipts | No cartons: `holdReceived` holds one row per command, and production's contract would have to change. Refused with a clear error. |
| D6 | Out of scope | Putaway (SCRUM-92), PO lines in a packing unit (#125 item 6), the LPN lookup API (item 11), picking by carton (item 10). |

## 3. Counting cartons on a draft receipt (procurement)

`PUT /api/v1/goods-receipts/{id}/lines`, each line gains two optional fields:

```json
{ "purchaseOrderLineId": "…", "locationCode": "HCM-RCV01", "lotNumber": "L01",
  "quantity": 4980,
  "unitOfMeasure": "CARTON",
  "packages": [ { "lpn": "LPN-20261024-000001", "quantity": 1000 },
                { "quantity": 980 } ] }
```

Rules, checked with every other line rule before anything is refused (SCRUM-464 field errors):

1. `quantity` stays the line's total in base units; tolerance (BR-02) and lot data (BR-03) are unchanged.
2. No `packages` (or an empty list): as today — loose, base unit, `uom_conversion_id` NULL. `unitOfMeasure` is
   then absent or the item's base unit; a packing unit with no carton is refused (`RECEIPT_LINE_UNIT_INVALID`), so
   a line never claims a unit it holds no carton of.
3. With `packages`, `unitOfMeasure` is required and must be an **active packing unit** (not BASE) of the line's
   item. The line stores its `uom_conversion_id`; every carton of the line is in that unit.
4. Every carton holds at least 1 unit. It may hold fewer or more than the unit's factor (shortfall or excess
   against `nominal_qty`).
5. `Σ packages.quantity ≤ quantity`; the remainder is loose stock of the same line.
6. Reconciliation by `lpn`: an entry without `lpn` is a new carton; an entry with `lpn` recounts that carton,
   which must belong to this line and still be RECEIVING; a RECEIVING carton of the line not in the list is voided.
7. The unit of a line changes only when the same request voids every carton it had; otherwise
   `RECEIPT_LINE_HAS_PACKAGES`.
8. A SUBCONTRACT order's receipt sending `packages` → `RECEIPT_PACKAGES_NOT_SUPPORTED`.
9. A line moved to another receiving area takes its RECEIVING cartons with it.

`GoodsReceiptResponse` lines gain `unitOfMeasure`, `looseQuantity` and
`packages: [{ id, lpn, quantity, nominalQty, status }]`, so the client has the LPNs to print after every save.

Procurement maps `goods_receipt_lines.uom_conversion_id` (domain `ReceiptLine`, entity, mapper). It never reads
`inventory.stock_package`; it calls `inventory :: api`.

## 4. Inventory

Every step below keeps the #125 lock order: **SKU advisory lock → `stock_package FOR UPDATE` in id order →
stock rows by id**, inside the caller's transaction.

### 4.1 Counting cartons while receiving

`InventoryService.countReceivingPackages(CountReceivingPackagesCommand)` → `List<ReceivingPackage>`:
`(receiptLineId, sku, unitOfMeasure, locationCode, List<{lpn?, quantity}>)`.

- Lock the SKU, then the line's RECEIVING cartons.
- New entry: `StockPackage.receiving(...)` with a fresh LPN, then `count(quantity)`.
- Known entry: `count(quantity)`; `moveTo(location)` when the line changed area.
- Missing carton: `cancel(now)` (VOID).
- The pure part — which carton is new, recounted or voided, and which LPN is not this line's — is a domain
  class without persistence, unit-tested on its own.

LPN: `LPN-yyyyMMdd-nnnnnn` from `platform.document_sequence` type `LPN`, dated in `BusinessCalendar.ZONE`
(matches `ck_stock_package_lpn`; `ck_document_sequence_type` already accepts `LPN`).

`InventoryService.receivingPackagesOf(Collection<UUID> receiptLineIds)` reads them for the receipt view.

### 4.2 Confirming the receipt

`InventoryService.receive(ReceiveStockCommand)` (existing) finds the line's cartons by `goods_receipt_line_id`:

- Lock the SKU, then the cartons.
- Per carton: one INBOUND stock row in the carton (`stock_package_id`, quantity = `received_qty`, the line's
  lot, expiry and receipt time), then `putInStock()` — IN_STOCK, still unopened. One `RECEIPT` ledger row per
  carton with `to_stock_package_id`.
- The remainder (`quantity − Σ cartons`) takes today's loose path. None is written when it is 0.
- `Σ cartons > quantity` is refused here too (the invariant stated twice).

### 4.3 Moving a carton whole

`InventoryService.moveStockPackages(MoveStockPackagesCommand(lpns, toLocation, reference, referenceId, actor))`:
each carton `moveTo`s and its rows change location in place — never merged into another layer, never opened. One
`MOVE` ledger row per row, naming the carton on both sides. Refused for a closed carton and for a destination in
another warehouse; all of the list moves or none. A list rather than one LPN per call: a line's cartons move
together, and the locks are taken once for all of them.

### 4.4 Reclassifying a carton whole

`InventoryService.reclassifyStockPackages(ReclassifyStockPackagesCommand(lpns, toLocation, disposition, reference,
referenceId, actor, reason))`, disposition QUARANTINE or BLOCKED: each carton moves as in 4.3 and its rows change
status from INBOUND (or QUARANTINE) to the disposition in place. The carton keeps its LPN and stays unopened.

## 5. QC on a line with cartons (procurement)

- `moveToQc`: `inventory.move` for the loose remainder when there is one, and one `moveStockPackages` for every
  IN_STOCK carton of the line. Both name the line; the loose move's replay lookup ignores carton ledger rows
  (`findLooseByReference`), so the order does not matter.
- `inspect`: `Part` (quarantined, rejected) gains `packages: [lpn]`; its `quantity` now means loose units.
  A named carton belongs to the line, is IN_STOCK at the line's QC area, and appears in one list only.
  BR-08 holds with cartons counted at their `received_qty`: accepted + quarantined + rejected = the line's
  quantity, and the loose parts do not exceed the loose remainder. A part's named cartons go through one
  `reclassifyStockPackages`; loose parts through `reclassify` as today. `QcInspection.quantity` records the part's
  total (loose + cartons); which LPN went where is in the ledger (`QC_INSPECTION`, `to_stock_package_id`).
- The accepted part stays INBOUND at the QC area. `ReceiptStockReadyForPutaway` keeps its shape; the putaway spec
  decides whether it carries LPNs.

## 6. Labels

`POST /api/v1/inventory/stock-packages/labels` (`application/pdf`; errors stay the JSON envelope), body
`{ "receiptLineId": "…", "format": … }` for every live carton of a receipt line, or `{ "lpns": [ … ], "format": … }`
to reprint. `@RequiresPermission(inventory-stock-packages, CREATE)` — `V20261020000700` gives it to warehouse
staff for exactly "print and reprint labels". `InventoryResources.STOCK_PACKAGES` is added. Every carton must be
in a warehouse the caller is scoped to (`WarehouseScope.requireLocation`). VOID, CONSUMED and LOST cartons are
not printed; at most 500 labels per request.

A label carries the LPN as large text, QR and Code 128 (all three exactly the LPN), the SKU and
`received / nominal UNIT` (the current count for a RECEIVING carton). **No item name**: it lives in `product`'s schema, which inventory may not read (and `product` depends on inventory). **No lot**: a carton stores none, the lot is
on the receipt line (procurement) or the stock row, and inventory reading procurement would be a cycle. Scanning
the LPN gives the lot through the lookup API (item 11).

The Code 128 / QR / fitted-text drawing, `LabelFonts` and `LabelFormat` move from `warehouse/internal/service`
to `common` (#138 plans this). `LocationLabelPdfRenderer` calls the shared drawing and draws the same labels; its
existing tests prove it. The carton renderer lives in `inventory/internal/service`. As for location labels, the
read transaction ends before the PDF is drawn.

## 7. Errors

Existing codes are reused; every new one gets `error.<CODE>` in both bundles (`MessageBundleTest`).

| Case | Code |
|---|---|
| `packages` on a subcontract receipt | new `RECEIPT_PACKAGES_NOT_SUPPORTED` (409) |
| `Σ packages > quantity` | new `RECEIPT_PACKAGES_EXCEED_LINE` (422) |
| carton quantity < 1; `packages` without `unitOfMeasure`; an LPN listed twice | `VALIDATION_FAILED` |
| unit is BASE or retired; packing unit without cartons | new `RECEIPT_LINE_UNIT_INVALID` (422) |
| unit not a unit of the item | `UOM_CONVERSION_NOT_FOUND` (404) |
| LPN not this line's / not RECEIVING | `STOCK_PACKAGE_NOT_FOUND` (404) / `INVALID_STOCK_PACKAGE_TRANSITION` (409) |
| unit changed while keeping cartons | `RECEIPT_LINE_HAS_PACKAGES` (409) |
| QC names a carton not at the QC area / not the line's / twice | `STOCK_PACKAGE_WRONG_LOCATION` (422) / `STOCK_PACKAGE_NOT_FOUND` / `VALIDATION_FAILED` |
| QC parts do not add up | `QC_QUANTITY_MISMATCH` (409) |
| labels: none selected / over 500 | new `STOCK_PACKAGE_LABELS_EMPTY`, `STOCK_PACKAGE_LABELS_TOO_MANY` (422) |
| label of a closed carton | `INVALID_STOCK_PACKAGE_TRANSITION` (409) |

## 8. Tests

**Integration tests that touch cartons commit.** The content check of `V20261020000200` is a constraint trigger
deferred to commit; a test that rolls back never runs it and passes for the wrong reason.

- Receiving (procurement, HTTP): two cartons and loose units → LPNs returned; recount one, drop one → VOID;
  unit change with cartons kept → 409; subcontract → 409; `Σ > quantity` → 422; cancel → all VOID; confirm →
  cartons IN_STOCK and unopened, one INBOUND row per carton with its count, loose remainder a loose row, ledger
  rows with `to_stock_package_id`, ATP unchanged.
- QC: a line with cartons to the QC area → cartons and loose units there, each carton's location equal to its
  rows'; quarantine one carton by LPN → its rows QUARANTINE, in the carton, still unopened; parts not adding up →
  `QC_QUANTITY_MISMATCH`.
- Labels: page count equals cartons; staff 200, planner 403; VOID carton 409; 501 LPNs 422.
- Unit: the carton reconciliation of §4.1; `ReceiptLine` unit rules.
- Unchanged and green: `GoodsReceiptIntegrationTest`, `SubcontractPurchasingIntegrationTest`,
  `PrintRunIntegrationTest`, the location-label tests, `ModularityTest`, `ArchitectureTest`, `MessageBundleTest`,
  `tools/verify.py`.

## 9. Docs to update with the code

- `docs/business-design/04c-all-flows.md`, the receiving flow: cartons labelled while counting, confirmed into
  IN_STOCK cartons, QC by whole carton.
- `CLAUDE.md` §4: a short "Receiving into cartons" entry next to the cycle count.
- Issue #138: tick the receiving line when the PR merges.
