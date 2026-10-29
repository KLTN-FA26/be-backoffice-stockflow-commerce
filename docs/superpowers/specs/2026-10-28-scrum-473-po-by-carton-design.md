# SCRUM-473 — Purchase orders by carton (design)

GitHub #138, feature line "PO đặt theo thùng"; #125 item 6. Standard purchase orders only.

## 1. Goal

A buyer orders "5 CARTON" of a SKU instead of working out 5,000 units. The line keeps the packing unit and the
number of cartons; everything priced or checked stays in base units. When the goods arrive, the receipt takes the
ordered carton unit by default.

Success:
- `POST /purchase-orders` with a line `{sku, unitOfMeasure: "CARTON", unitQuantity: 5}` on an item whose CARTON factor
  is 1000 stores `ordered_qty = 5000`, `uom_conversion_id = <CARTON>`, `unit_qty = 5`, and is priced 5000 × unit price.
- The same line without `unitOfMeasure` behaves exactly as today.
- MOQ and pack multiple are checked on the 5000 units, as today.
- A receipt line of that PO line that lists cartons but names no unit is counted in CARTON.

## 2. Decisions (agreed 2026-10-28)

| Point | Decision |
|---|---|
| Unit price | Per base unit, as today. The catalog price, line totals and approval limits do not change. The response also shows the price of one carton, for display only. |
| Receipt | The ordered unit is the default, not a rule: a supplier that ships another pack size is received in that one. |
| Schema | No migration. `purchase_order_lines.uom_conversion_id` / `unit_qty` and `tg_purchase_order_lines_unit` exist (V20261020000400); revisions already copy them (#139 / #145). |
| Subcontract orders | Out of scope: created by production, single line, never through this API. Their existing unit handling (`wholeUnits`, amendments) is untouched. |

## 3. API

### 3.1 Request — `CreatePOLineRequest`, `UpdatePOLineRequest`

Two optional fields:

| Field | Rule |
|---|---|
| `unitOfMeasure` | A unit of the line's item, any case (`"carton"` = `CARTON`). Must exist (`UOM_CONVERSION_NOT_FOUND`) and be active (`PO_LINE_UNIT_INVALID`, new). Naming the base unit means "in base units": the line is stored with no packing unit, `unitQuantity` is then the base quantity. |
| `unitQuantity` | Number of those units, 1 … 1,000,000; required with `unitOfMeasure`, refused without it (`VALIDATION_FAILED`). |

`quantityOrdered` becomes optional (`Integer`): required when there is no `unitOfMeasure`; with one it is derived as
`unitQuantity × factor`, and if the client sends it as well it must equal that (`VALIDATION_FAILED` on
`lines[i].quantityOrdered`). The derived quantity keeps the existing 1 … 1,000,000 bound.

Errors are field errors on `lines[i].unitOfMeasure` / `lines[i].unitQuantity`, collected with the other line errors
the way `checkLines` already reports catalog-term violations.

### 3.2 Response — `POLineResponse`

Adds, all null for a line in the base unit:

- `unitOfMeasure` — the packing unit ordered in (the existing `uom` stays the base unit);
- `unitFactor` — what one of them holds, as stored for the line's unit;
- `unitQuantity` — how many were ordered;
- `openUnitQuantity` — `openQuantity / unitFactor`, rounded down: the cartons still expected;
- `packingUnitPrice` — `unitPrice × unitFactor`, display only.

### 3.3 Receipt — `GoodsReceiptResponse.Line`

Adds `orderedUnitOfMeasure`: the unit the PO line was ordered in, null for base. The app pre-selects it.

## 4. Behaviour

### 4.1 Creating and revising a draft

`ProcurementServiceImpl.checkLines` resolves the unit with the item's units (`InventoryControlService` /
`units.conversionsOf`, already used by receiving), validates §3.1, derives the base quantity, and only then runs the
MOQ / pack-multiple and price checks on that quantity. `CheckedLine` carries the chosen unit (id, code, factor,
count) or none.

- Domain: `PoLine` gains an optional packing unit — a value `PackingUnit(UUID uomConversionId, String unitOfMeasure,
  int factor, int count)` whose constructor states `count ≥ 1`, `factor ≥ 1` (as `ck_uom_conversions_factor`; a
  packing unit of one unit is legal). `PoLine` states
  `quantityOrdered == count × factor` when it has one. `PurchaseOrder.draft` / `DraftLine` carry it.
- Entity: `PoLineJpaEntity` writes `uom_conversion_id` and `unit_qty` on insert, and in a draft revision
  (`reviseDraft`), where the buyer may change, add or drop the unit. `uom_conversion_id` becomes updatable;
  `tg_purchase_order_lines_draft_content` still refuses any change once the order has left DRAFT.
  `reviseDraftQuantity` (subcontract) keeps its current behaviour.
- The javadoc that says "the application still orders in the base unit" is rewritten.

A draft line that already has a unit and is revised without `unitOfMeasure` keeps that unit, and its quantity must be a
whole number of it (`PO_LINE_UNIT_NOT_MULTIPLE`) — today's behaviour, pinned by
`PurchaseOrderDraftEditIntegrationTest.draftEditKeepsTheLineUnit`, so a client that knows nothing of units cannot drop
one by accident. To go back to base units the buyer names the base unit.

### 4.2 Receiving

`GoodsReceiptServiceImpl.cartonUnit`: when a line lists cartons and names no unit, the receipt line's own unit is used
if it was already counted in one (a recount must not switch it and void its labelled cartons), else the PO line's unit
if it has one; otherwise the existing `"Cartons need the packing unit they are in"` error stands. Every other rule of
`cartonUnit` (unit exists, not base, rule 7) applies to the defaulted unit too. A retired unit is accepted when it is
the line's own or the one the PO line was ordered in, as `check_goods_receipt_line_unit` (V20261020000400) allows:
goods ordered before the supplier changed its carton arrive in the old one. A unit the client names always wins.
(Amended after the whole-branch review, 2026-10-28.)

## 5. Not in this change

- Prices per carton, or any schema change.
- Units on subcontract orders through this API.
- A default unit taken from `uom_conversions.default_purchase` when the buyer names none: the frontend may pre-select
  it, the API stays explicit.
- Receiving refusing a unit other than the ordered one.

## 6. Tests

| Level | What |
|---|---|
| Domain (`PoLineTest` or `PurchaseOrderTest`) | `PackingUnit` bounds; a line with a unit whose quantity is not count × factor is refused |
| Service / HTTP integration (`PurchaseOrderIntegrationTest` or a new `PurchaseOrderUnitIntegrationTest`) | create "5 CARTON" → `ordered_qty`, `uom_conversion_id`, `unit_qty`, totals; lower-case unit accepted; naming the base unit stores no packing unit; retired unit, unknown unit, `unitQuantity` without unit, mismatching `quantityOrdered` each refused on its field; MOQ / pack multiple checked on the derived quantity; draft revision changes the unit, keeps it when omitted, drops it when the base unit is named, adds it; submitting and approving keeps it in the revision snapshot; response fields |
| Receiving (`GoodsReceiptIntegrationTest`) | cartons listed without a unit on a PO line ordered in CARTON are counted in CARTON; a named unit wins; a base-unit PO line still needs the unit named |
| Gates | `ModularityTest`, `ArchitectureTest`, `MessageBundleTest` (new error code in both bundles), `tools/verify.py`, one full `mvn -o verify` |

## 7. Who to tell

- **Tú** — purchase-order code in `procurement` (no schema change); SCRUM-114 approval limits are unaffected, amounts
  are unchanged.
- **Frontend** — the request and response fields above; pre-select `orderedUnitOfMeasure` on receipt lines.
