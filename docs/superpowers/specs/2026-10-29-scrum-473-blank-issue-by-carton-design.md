# SCRUM-473 — Issuing blanks by carton (design)

GitHub #138, feature line "Xuất phôi cho in theo thùng; đóng thùng thành phẩm sau in". In-house print jobs only.

## 1. Goal

A storekeeper bringing blanks to the print shop takes the carton the system deducts from. Today the scan checks the
location and the SKU or barcode, so any carton of the SKU passes: the ledger empties carton A while carton D goes to
the press, and the books disagree with the shelf until a count finds it.

Success:
- A hold in a carton (`HoldView.lpn` set) accepts only that LPN as its scanned code; the SKU, the barcode or another
  LPN is refused with `ISSUE_ITEM_MISMATCH` naming the LPN to take, and nothing moves.
- `GET /production/orders/{id}/material-issues/pending` shows each hold's `lpn` and `wholeCarton`.
- A loose hold and an issue sent with no scanned code behave exactly as today.

## 2. Decisions (agreed 2026-10-29)

| Point | Decision |
|---|---|
| Finished goods into cartons | **Dropped.** G4-Q2 (lead, 2026-10-21) consumes the printed goods when the production order completes; nothing stays in stock to put in a carton. Shipping a whole carton as a parcel is SCRUM-279/283/284. |
| How a carton enters the print shop | Loose, as today: `issueReserved` moves the hold to the PRODUCTION area and the carton opens or empties. The shop unpacks cartons to print; keeping them sealed there would teach every workshop operation (print, scrap, return, finish to packing, consume, subcontract) about cartons for the rare unopened return. |
| Is a scan required for a carton hold | No. An issue with no scanned code keeps the SCRUM-397 contract (the hold alone decides). A scanned code, when sent, must be the LPN. |
| Which carton holds need the LPN | Every hold with an `lpn`, whole or part of an opened carton, as `PickList.confirm` does for pick lines. |
| Schema | No migration. `production_material_issue` does not record the LPN; the ledger line `issueReserved` writes already names the carton. |

## 3. API

### 3.1 Issue — `POST /production/orders/{id}/material-issues`

Request unchanged. `scannedCode` is compared as follows:

| Hold | Accepted `scannedCode` |
|---|---|
| `lpn` set | that LPN, any case, surrounding blanks ignored |
| `lpn` null (loose stock, or a SKU reservation) | the SKU (any case) or the SKU's barcode, as today |

`scannedLocation` is checked first, as today. A refusal is `409 ISSUE_ITEM_MISMATCH` with the message
`"This hold is in carton <LPN>; scan its label"`.

### 3.2 Pending — `GET /production/orders/{id}/material-issues/pending`

`MaterialIssues.PendingHold` gains `String lpn` (null for loose) and `boolean wholeCarton`, copied from `HoldView`.
Its javadoc changes from "scan it, then the SKU or barcode" to say a carton hold is confirmed by its LPN.

## 4. Behaviour

Only `production/internal/service/MaterialIssueService` changes:

- `requireScanned` reads the chosen hold's `HoldView` as it does now, and when `scannedCode` is present picks the
  rule of §3.1 by `hold.lpn()`.
- `pending` maps `lpn` and `wholeCarton` onto `PendingHold`.

The allocation already prefers whole sealed cartons for production holds (`StockAllocator`, SCRUM-473 picking), so
the pending list shows which cartons to bring and in what order. `ISSUE_ITEM_MISMATCH` keeps its code and bundle
texts, as `PICK_ITEM_MISMATCH` did for carton pick lines: the exception's own message names the LPN.

## 5. Not in this change

- Finished-goods cartons (§2).
- Sealed cartons in the print shop, and unprinted blanks returning to storage in a carton.
- Blanks sent to a subcontractor (`SubcontractService` dispatch), which has no scan.
- A required scan for carton holds.

## 6. Tests

| Level | What |
|---|---|
| Integration (`SampleToPackingIntegrationTest` or a new `MaterialIssueCartonIntegrationTest`) | a production hold drawn from a sealed carton: the pending list shows its LPN and `wholeCarton = true`; scanning the LPN (lower case) issues it; scanning the SKU, the barcode or another carton's LPN is refused with `ISSUE_ITEM_MISMATCH` and the hold stays where it was; an issue with no scanned code still moves it; a loose hold still accepts the SKU |
| Gates | `ModularityTest`, `ArchitectureTest`, `tools/verify.py`, one full `mvn -o verify` |

## 7. Who to tell

- **Võ** — `MaterialIssueService` and `MaterialIssues.PendingHold` change; PR #196 edits `waiting()` in the same
  file, so a merge may need a small hand resolution.
- **Frontend** — show `lpn` on pending holds and send the scanned LPN as `scannedCode` for carton holds.
