# One carton spec per SKU — design

Date: 2026-11-19 · Size: project · Status: approved by the owner (2026-11-19), not built

## Intent

kltn-docs settled long ago that a customer SKU is packed in **one** carton: a fixed number of units per carton, a
carton size (L×W×H → m³) and a full-carton weight (01 BR-03, A11, decided 2026-11-06). The producer packs every carton
to that standard, cartons that differ are refused at receiving (03 BR-13), the warehouse never opens a carton, quotes,
orders and production orders count cartons, and allocation picks N whole cartons (17 BR-21).

The schema and the code still carry a general multi-unit model from before the rescope: `inventory.uom_conversions`
lets an item have any number of packing units, each with its own factor and size, chosen by `default_shipping` /
`default_purchase` marks; `inventory_items` keeps kit and box columns (`package_*`, `package_count`, `pack_size`);
`product.variant_sales_units` lists sales units nobody reads. Nothing enforces "one carton", and the code picks "the
carton" in five different ways:

| Where | How the carton is chosen today |
|---|---|
| quote, storefront, stock report | `UomConversion.cartonOf`: the one active non-base unit with factor > 1; among several, the one marked `default_shipping`; otherwise none |
| capacity board, carton check, shipping label | `InventoryPolicyRepository.findCartonSpecs`: also accepts factor 1 |
| goods receipt | `GoodsReceiptServiceImpl.productionCartonUnit`: any non-base unit whose factor equals the line's |
| production instruction sheet | `DesignReviewService.approval`: the largest factor |
| load estimate, production order slip | `PackagingRules`: four levels (shipping unit → largest unit → item box × kit count → one unit), with partial cartons and kits |

So the capacity board and the production slip can disagree on the same carton (the comment in
`ProductionOrderDispatch` admits it), the design preflight can pass a SKU that the quote then refuses, approving a
design copies every unit of the template into the customer SKU, and the receipt can label cartons in a unit other than
the one quoted.

The owner's decision (2026-11-19): every column that only says "this row is the carton" exists because the table holds
many units; with one carton per SKU, what is left is five values in a 1:1 relation with the SKU. They become columns of
`inventory_items` — where kltn-docs 01 already places the carton spec — and the multi-unit model goes.

## Decided (owner, 2026-11-19)

- **The carton spec is five columns of `inventory.inventory_items`**: `units_per_carton`, `carton_length_cm`,
  `carton_width_cm`, `carton_height_cm`, `carton_weight_kg`. `inventory.uom_conversions` and
  `product.variant_sales_units` are dropped, with every `uom_conversion_id` column pointing at them.
- **The number of units per carton is locked for ever** once the SKU has been used: any carton (LPN) of it, or any
  quote, order, production order or goods receipt line of it, in any status, or a customer price list of it
  (`catalog.customer_price`: its tiers and MOQ are counted in cartons, the very harm named below). Price list writes
  take the same item-level lock as the other uses, so the test stays race-free. A producer that changes its packing
  standard means a **new customer SKU** (a design revision), as 01 §6 and BR-10 already say. Loosening the lock to
  "no stock and no open documents" was considered and rejected: a carton shipped in the old count can come back as a
  return after the change, the customer's price tiers and MOQ are in cartons and would silently change meaning, and
  "open document" would have to list every place a carton can be. If customers often reorder after a packing change,
  the answer is a shortcut that makes the new SKU from the old one, not a looser lock.
- **The carton's size and weight stay editable**, also once the SKU is used: every carton of the SKU is the same
  physical carton, so correcting a measurement corrects it for all of them at once and never makes two kinds of
  carton. The edit is refused when a carton of the SKU would no longer fit the bin it stands in (today's
  `BinStranding` check, which the unit resize already runs), and recorded in the change history.
- **Nothing is kept for history in a units table.** History is already on the documents: `stock_package.nominal_qty`
  (immutable), and `units_per_carton` copied onto quote, order and production order lines when drawn up (A11).
- **The legacy demo SKUs `SOFA-3S-GREY` and `TABLE-OAK-160`** (furniture from before the rescope, packing unit
  `CARTON` of factor 1) are removed from the demo data, with their products `SOFA-3S` and `TABLE-OAK` (owner,
  2026-11-19, after the builder check showed about 90 test and QA files use them). Keeping them with a carton of one
  unit (`units_per_carton >= 1`) was offered and declined: `units_per_carton > 1` stays, and those tests move onto the
  cup SKUs of the demo data as part of this work.
- The business change in kltn-docs is one wording change (below); the rules stay.
- Approach B below.

## Goals

- One source for a SKU's carton everywhere: quote, order, production order and its slip, receiving, LPN creation,
  putaway fit, bin and warehouse occupancy, capacity board, carton check, shipping label, load estimate and load plan,
  production instruction sheet, design preflight, storefront, reports.
- The database states the rule twice, as the house rules ask: in the aggregate and as constraints/triggers.
- The template variant (quy cách) carries a carton spec that a customer SKU copies when the design is approved; editing
  the template later does not reach SKUs already generated.
- The carton spec is edited by whoever holds `inventory-items:UPDATE` (Warehouse Manager, System Admin), as kltn-docs
  01 §2 says ("Quản lý kho cập nhật quy cách thùng"). Today it sits behind `product-products:UPDATE` (Sales Staff,
  Sales Manager, System Admin), so the Warehouse Manager cannot do it — a mismatch this change fixes. Sales then no
  longer set a template's carton spec themselves: a design on a template without one fails preflight until the
  Warehouse Manager declares it, as kltn-docs B25 and 12 §6 already say.
- Sales keep **reading** the carton spec without a new grant: the product variant responses carry it read-only
  (`product` reads it through `inventory :: api`), so `inventory-inventory-items:READ` stays with the warehouse roles
  and no permission migration is needed (builder's check, settled here).

## Non-goals

- Several cartons or packing levels per SKU, or one SKU holding cartons of two counts (01 BR-03).
- A "new SKU from old SKU with another carton" shortcut (future, see Decided).
- Measuring cartons per LPN. The carton check still weighs and measures what leaves; that stays as it is.
- The base unit's meaning: `inventory_items.unit_of_measure` (`EACH`) stays as today, as does the optional size and
  weight of one unit (01 lists both).
- The front end. The API contract changes (below) and the FE follows in its own repository, in step with this PR.

## Anti-goals

- A migration that leaves a SKU whose cartons in stock have no size (bin fit and occupancy would undercount it).
- Any code path still choosing "the carton" by its own rule.
- An order line being allocated a carton whose count differs from the line's `units_per_carton`.

## Constraints

- Flyway: add only, never edit a merged migration or demo migration; the version must be newer than every migration
  on every branch already pushed (newest seen 2026-11-19: `V20261205000100` on `feature/production-order-slip-layout`).
  The VPS database is rebuilt on deploy (QD-1), but the migration must still run over the demo seed and over the
  rows tests write.
- `ddl-auto: validate`: entities and migration change together.
- Every drop and constraint change gets adversarial SQL under `tools/db/qa/` (run by `RepositorySqlQaTest`), and the
  data move gets a lean `*MigrationUpgradeTest`.
- SQL functions that mention dropped objects are rewritten in the same migration (Postgres does not track them).
- Module boundaries: `inventory :: api` keeps what other modules read; `ModularityTest` after any package change.
- Historical enum constants stay (`product_change_log` subject types `UOM_CONVERSION`, `SALES_UNIT`).
- Build after `feature/production-order-slip-layout` (PR #378) is merged: it rewrites `ProductionOrderDispatch` and
  `ProductionOrderSlipDocument`, which still read `PackagingSpecResolver`. If it is not merged when the work starts,
  branch from it and rebase onto `develop` once it is.
- `tools/db/seed/seed_test_data.sql` is loaded on deployed databases (`SeedTestDataLoadTest`); if it writes
  `uom_conversions`, `variant_sales_units` or the `package_*` columns, it is regenerated in the same PR (not checked
  while writing this design).

## Approaches

- **A. Keep `uom_conversions`, allow one active carton.** A partial unique index on the active non-base unit, CHECKs
  for factor > 1 and full size, one `cartonOf` everywhere, `uom_conversion_id` added to production order lines.
  Smaller blast radius, keeps the foreign keys — but keeps a table, a base-unit row, an `active` history and marks
  whose only job is to tell which row is the carton.
- **B. Carton spec as columns of `inventory_items`, drop the units model.** The schema says what the business says.
  Larger: five foreign key columns, two tables and about eight trigger functions go; about fifty main classes change;
  the FE carton screens follow. **Picked** by the owner after seeing that, with one carton for ever, no row history is
  needed (the documents carry it).
- C. A 1:1 `inventory.carton_spec` table keyed by the item. Same as B with an extra join and no gain; dropped.

## Design

### Schema

`inventory.inventory_items`:

| Column | Change |
|---|---|
| `units_per_carton int` | **new**; `CHECK (units_per_carton > 1)` |
| `carton_length_cm`, `carton_width_cm`, `carton_height_cm numeric(10,2)` | **renamed** from `package_length_cm` / `package_width_cm` / `package_height_cm`; `> 0` |
| `carton_weight_kg numeric(10,3)` | **renamed** from `package_weight_kg`; `> 0` |
| `package_count`, `pack_size` | **dropped** with their CHECKs |
| `unit_of_measure`, `barcode`, `weight_kg`, `length_cm`, `width_cm`, `height_cm`, `rotatable` | unchanged |

- `ck_inventory_items_carton`: the five carton columns are all null (no carton spec yet: a template not set up) or all
  set.
- The values carried over from the old `package_*` columns are replaced by the active carton unit's (see Data
  migration) — the old columns described the item's own box, not the carton.
- A trigger `BEFORE UPDATE OF units_per_carton` refuses the change when the SKU has been used (Decided). A
  `BEFORE UPDATE OF units_per_carton, carton_*` path setting them back to null on a used SKU is refused the same way.

Dropped: `inventory.uom_conversions`, `product.variant_sales_units`, and the columns
`inventory.stock_package.uom_conversion_id`, `procurement.goods_receipt_lines.uom_conversion_id`,
`ordering.quote_line.uom_conversion_id`, `ordering.order_line.uom_conversion_id`, with their FKs and indexes.
`units_per_carton` stays on quote, order and production order lines (the count agreed when drawn up) and
`nominal_qty` on `stock_package`.

Database rules rewritten against the item's columns (names from the baseline):

| Today | Becomes |
|---|---|
| `check_stock_package_created` (unit of the item, not base, active, `nominal_qty = factor`) | the item has a carton spec and `nominal_qty = units_per_carton` |
| `ordering.check_line_carton_unit` (unit of the SKU, `units_per_carton = factor`, a new quote line takes an active unit, and 14 BR-04: the SKU belongs to the quote's / order's customer) | a new carton line's `units_per_carton` equals the SKU's, and the SKU has a carton spec; **the BR-04 customer check stays as it is** |
| view `inventory.stock_package_balance` (joins `uom_conversions`, exposes `unit_of_measure`; read by `StockPackageSearchAdapter`, `CycleCountRepositoryAdapter`, QA 62) | rebuilt without the join and without `unit_of_measure`, before the table is dropped |
| `check_goods_receipt_line_unit` and the production-line check (non-base unit, same factor) | the production order line's `units_per_carton` equals the SKU's |
| `check_stock_package_fits_bin`, occupancy SQL (`BinOccupancyQuery`, `WarehouseOccupancyQuery`) reading the unit's size | read the item's carton columns; the weight fallback to `weight_kg × qty` goes |
| `unit_in_use`, `unit_lock_key`, `guard_unit_in_use_change` | replaced by the lock trigger on `inventory_items` (same "used" test, keyed by item; the advisory lock that makes it race-free moves with it) |
| `create_base_uom`, `follow_base_uom`, `check_sales_unit_of_item` and their triggers | dropped |
| `ck_quote_line_shape`, `ck_order_line_cartons` (name `uom_conversion_id`) | the same without that column |

Lock order is unchanged; the item-level advisory lock replaces the unit-level one at the same places.

### Code

- `inventory :: api` exposes the carton spec as one value (for example `CartonSpec(int unitsPerCarton, lengthCm,
  widthCm, heightCm, weightKg, boolean rotatable)`, with its volume in m³) read by SKU or item id, singly and in bulk.
  It replaces `UomConversion`, `UomConversionService`, `UomConversionDraft`, `PackagingSpecResolver`, `PackagingSpec`,
  `RuleLevel` and `InventoryControlService.cartonSpec`'s present shape. Every reader in the Intent table uses it.
- `PackagingRules` and its four levels, partial cartons, kits and their warnings go. The load estimate counts an order
  line's `cartons` of the SKU's carton; a SKU without a carton spec stays the estimate's existing 422 pointing at the
  item.
- Quote line: `units_per_carton` from the SKU's carton spec (refused as today when it has none); the line no longer
  stores a unit id. Order line copies the quote line as today.
- Goods receipt: no unit to choose or name. Cartons are made with `nominal_qty = units_per_carton`; the request field
  `unitOfMeasure` and the "name the unit" error go.
- Design preflight, template availability, catalog "has a carton spec": the item's carton spec is set.
- Design approval (`ItemSpecificationsImpl.copy`): copies the template's carton spec, `rotatable`, unit size and weight
  and barcode rules as today, and nothing about units, kits or `pack_size`.
- Production instruction sheet and production order slip: the SKU's carton spec (the slip's carton size and the
  capacity board's m³ now come from the same place).
- Allocation (`StockAllocator`) picks only cartons whose `nominal_qty` equals the line's `units_per_carton`. With the
  count locked this always holds; it is the aggregate's half of "state every invariant twice".
- Removed: `UomConversionController`, `SalesUnitController`, `SkuUnitService` (units part), `UomConversionRepository`,
  `VariantSalesUnitRepository`, `StockPackageRepository.cartonFactorOf`, `PackagingSpecResolver.unitCarton`, the
  `packaging-preview` endpoint, `default_purchase` in the receipt view.

### API (breaking; the FE follows)

| Today | After |
|---|---|
| `GET/POST/PUT/DELETE /api/v1/products/{productId}/variants/{variantId}/uom-conversions[/{id}]` (`product-products`) | `GET` and `PUT /api/v1/inventory/items/{id}/carton-spec` (`inventory-items` READ / UPDATE), body the five values plus `version` |
| `…/variants/{variantId}/sales-units` | removed |
| `PUT /api/v1/inventory/items/{id}/logistics` with `package*`, `packageCount`, `packSize` | without those fields |
| `GET /api/v1/inventory/items/{id}/packaging-preview` | removed |
| `InventoryItemResponse` with `package*`, `packageCount`, `packSize` | with `cartonSpec` (null when not set) |
| load estimate rows with `level`, `kitParts`, `partialCarton`, warnings | cartons of the carton spec only |
| goods receipt line request `unitOfMeasure`, receipt view `units` | removed |
| carton lookup (`/inventory/stock-packages/...`) `unitOfMeasure` | removed |
| product variant responses | gain a read-only `cartonSpec` (null when not set) |

New error codes, each in both bundles (`MessageBundleTest`): the count locked (`CARTON_SPEC_LOCKED`), a resize that
strands a carton (today's code is reused if one exists). Existing `CARTON_SPEC_MISSING` stays.

### Data migration (one schema migration, plus one demo migration before it)

1. Demo migration: remove `SOFA-3S-GREY` and `TABLE-OAK-160`, their products `SOFA-3S` and `TABLE-OAK`, and everything
   tied to them in the demo data — about 320 rows across `V20261111000001__demo_seed` (`stock_item`, `stock_package`,
   `stock_movement`, `stock_lot`, `uom_conversions`, `inventory_items`, `product.variants`, `product.media`,
   `product.products`) and `V20261128000100` (75 opening `stock_movement` lines), plus anything tests find later
   (change log rows, attribute values). The ledger's append-only triggers are disabled for the delete and enabled
   again in the same file, as `V20261121000200` does for `tg_stock_movement_append_only`.
   The tests and QA files that use them (about 90 under `src/test` and `tools/db/qa`, among them
   `DemoData.PRODUCT_SOFA` and the design draft tests, QA 50, 52, 62, 63, 99) move onto the demo cup SKUs and their
   product in the same PR; `DemoData` and `support/` keep one place that names the replacement.
2. Schema migration:
   - add `units_per_carton`; rename the `package_*` columns to `carton_*`;
   - fill the five from each item's carton by today's `cartonOf` rule (the one active non-base unit with factor > 1;
     among several, the one marked `default_shipping`; a unit of factor 1 or the base unit is never taken); an item
     with none gets all five null (and must hold no carton); an item that is ambiguous, whose carton lacks its size or
     weight, or that holds cartons but gets no carton spec, makes the migration **fail** with its SKU named, rather
     than guess (builder's check, settled here);
   - check every `stock_package` / quote / order / production order line of an item agrees with its new
     `units_per_carton`, failing otherwise;
   - rewrite the functions and triggers, drop the columns, tables and indexes, in dependency order.

### kltn-docs (a PR on the docs repository)

- 01 BR-10 and §4.7, §6: "đổi quy cách thùng = khai báo đơn vị mới, ngưng đơn vị cũ" becomes "sửa quy cách thùng của
  quy cách (mẫu); SKU riêng đã sinh không đổi". Add: kích thước và khối lượng thùng của SKU riêng sửa được để chữa số
  đo, kể cả khi đã có tồn, miễn thùng còn vừa bin; số cái / thùng khoá vĩnh viễn khi SKU đã dùng.
- 01 §3 Input: the "đơn vị quy đổi" row and "đơn vị giao mặc định" go; "đơn vị thùng" becomes "số cái chuẩn / thùng".
- open-questions A11: a line recording 2026-11-19.

## Tests the builder writes

- The lock: changing `units_per_carton` of a used SKU is refused, in the service and by the trigger, for each kind of
  use (carton, quote line, order line, production order line, receipt line, customer price list); an unused SKU and a template change freely.
- A size/weight correction on a SKU with stock succeeds and moves the capacity board, the bin occupancy, the carton
  check and the load estimate together; one that strands a carton is refused.
- One source: for one SKU, the quote, the receipt's LPN, the capacity board, the shipping label, the load estimate, the
  production slip and the instruction sheet all show the same count and size.
- Design approval copies exactly the template's carton spec; editing the template afterwards leaves the SKU unchanged.
- The migration upgrade test: an item with one carton unit, one with `default_shipping` among two, one ambiguous (the
  migration fails naming it), one without a carton.
- Adversarial SQL: a carton with the wrong `nominal_qty`, a quote line with another count, partial carton columns,
  `units_per_carton = 1`, an update of the count under a used SKU.
- `PermissionMatrixTest`: the carton spec endpoint is `inventory-items`.

## Left to the builder

- Names of the new API record and service, and whether the carton spec endpoint lives in `InventoryItemController`.
- Where the carton-spec change is recorded in the change history (an existing subject type or a new constant).
- How the work is cut into commits; the FE contract change goes out as one PR with a note for the FE owner.
