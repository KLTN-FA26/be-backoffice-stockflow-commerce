# SCRUM-69 Procurement data — design

*2026-10-16 · StockFlowCommerce (FA26SE029) · owner: Phương*

Jira: [SCRUM-69](https://minh7n3.atlassian.net/browse/SCRUM-69) "Procurement Data" (WBS 3.1.4), with its
subtasks [SCRUM-77](https://minh7n3.atlassian.net/browse/SCRUM-77) (supplier catalog) and
[SCRUM-78](https://minh7n3.atlassian.net/browse/SCRUM-78) (PO lines follow the catalog, order-up-to level).
The tickets carry the decisions agreed on 2026-10-14; this document records how they land in the code and
the points the tickets left open.

## 1. What exists on `develop` today

| | State |
|---|---|
| `procurement.supplier_items` | Migrated (`V20260928002000`). Unique pair `uk_supplier_items_pair`, one preferred per item `uk_supplier_items_preferred`, FK to `inventory.inventory_items`. **No Java code reads or writes it.** |
| `procurement.supplier_item_prices` | Migrated. Periods `[effective_from, effective_to)` never overlap (`ex_supplier_item_prices_overlap`). No Java code. |
| Demo data | `V20260928009000__demo_master_data.sql` already lists SOFA-3S-GREY and TABLE-OAK-160 for GOVIET, with one price period each. |
| PO line writes | Exactly two paths, `createPurchaseOrder` and `reviseDraft`, both through `ProcurementServiceImpl.checkLines`. A SUBCONTRACT PO is built by `SubcontractPurchasingImpl` and never goes through `checkLines`; `reviseDraft` refuses anything but a STANDARD draft. |
| PO currency | Chosen per PO (`CreatePurchaseOrderRequest.currency`). One supplier can have POs in several currencies — `SupplierSpendByCurrencyIntegrationTest` pins it. |
| `inventory_items.max_qty` | Column exists, unused. `InventoryPolicy` (`inventory :: api`) has no field for it. |
| BR-PO-001 | `05-business-rules.md` names `PurchaseOrder.addLine()`, which does not exist. The `createPurchaseOrder` javadoc says MOQ "is not enforced". |

No migration is needed for the feature itself. Two CHECK constraints are missing and are Tú's to add (§6).

## 2. Delivery

Two stacked PRs, as with SCRUM-45:

1. **PR1 — SCRUM-77**: the catalog (domain, persistence, service, API, permissions).
2. **PR2 — SCRUM-78**: PO lines enforce the catalog, price and lead-time prefill, `maxQty`, demo script,
   and the existing PO tests seeding the catalog.

## 3. PR1 — the supplier catalog

### 3.1 Shape

`SupplierItem` is its own aggregate in `procurement/internal/domain`, not a child collection of
`Supplier`:

- a catalog is paged and can be long; loading a supplier must not load it;
- switching the preferred supplier is keyed by the *inventory item* and spans several suppliers, which is
  outside one `Supplier`'s boundary;
- PO validation needs "this supplier × these items", never the whole supplier.

| Layer | Type |
|---|---|
| domain | `SupplierItem` (aggregate), `SupplierItemTerms`, `PriceChange`, `OrderQuantityViolation`, `CatalogSupplier` (values), `SupplierItemRepository` (port) |
| entity | `SupplierItemJpaEntity`, `SupplierItemPriceJpaEntity` |
| repository | `SupplierItemJpaRepository`, `SupplierItemPriceJpaRepository`, `SupplierItemRepositoryAdapter` |
| api | `SupplierItemService`, `SaveSupplierItemCommand`, `SupplierItemSummary`, `SkuSupplierSummary` |
| service | `SupplierItemServiceImpl` |
| controller | `SupplierItemController`, request/response records |

`SupplierItemService` is in `api` because the SKU → suppliers view is read by the SKU page and the PO
form, and PR2's `checkLines` reads the catalog through the same service's port.

### 3.2 `SupplierItem`

Fields: `id`, `supplierId`, `inventoryItemId`, `sku`, `supplierSkuCode` (nullable, ≤ 50), `price`
(`Money`, in the supplier's currency), `moq`, `packSize`, `leadTimeDays` (nullable), `preferred`, `version`.

Invariants, checked in the factory and in `update`, each also a DB CHECK (§6 for the missing one):

- price > 0, at most 2 decimals;
- `moq ≥ 1`, `packSize ≥ 1`, **`moq % packSize == 0`**;
- `leadTimeDays` null or 0–365. Null means "use the supplier's lead time".

Behaviour:

- `update(SupplierItemTerms terms, boolean preferred)` → `Optional<PriceChange>`; empty when the price is
  unchanged. The adapter applies a `PriceChange` by closing the open period (`effective_to = now`) and
  inserting the new one, in the caller's transaction; a period that opened at the same instant is
  overwritten instead. The gist exclusion is the last line of defence.
- `orderQuantityViolations(int quantity)` → list of violations (`BELOW_MOQ`, `NOT_PACK_MULTIPLE`). Returns,
  never throws, so the service can collect every line's errors into one refusal (used in PR2).
- `effectiveLeadTimeDays(int supplierLeadTimeDays)`.

### 3.3 API

| Method | Path | Result |
|---|---|---|
| GET | `/api/v1/suppliers/{supplierId}/items?page&size&sort` | page of `SupplierItemResponse` |
| POST | `/api/v1/suppliers/{supplierId}/items` | `{sku, supplierSkuCode?, price, moq, packSize, leadTimeDays?, preferred?}` → 201 |
| PUT | `/api/v1/suppliers/{supplierId}/items/{supplierItemId}` | full update + `version` → 200 |
| DELETE | `/api/v1/suppliers/{supplierId}/items/{supplierItemId}?version=` | 204 |
| GET | `/api/v1/procurement/skus/{sku}/suppliers` | every supplier selling the SKU, preferred first, then by name |

Every handler is public and carries `@RequiresPermission` on the method. Permissions use the seeded resource
`procurement-supplier-items` (V20260928006000, PROCUREMENT_STAFF holds all of it): GETs need READ, POST
CREATE, PUT UPDATE, DELETE DELETE. No migration and no seed change.

### 3.4 Rules

- Supplier must exist (`SUPPLIER_NOT_FOUND`) and be ACTIVE (`SUPPLIER_INACTIVE`) to add or edit an item.
  Reading and deleting work on any status.
- The SKU must have an inventory item (`INVENTORY_ITEM_NOT_FOUND`) and be purchasable
  (`products.purchasability`, `SKU_NOT_PURCHASABLE`).
- `currency` is the supplier's; the client does not send it.
- Duplicate pair → 409 `SUPPLIER_ITEM_DUPLICATE`, translated from `uk_supplier_items_pair` only (constraint
  name matched, nothing broader).
- **Preferred switch.** Before touching `is_preferred`, take `pg_advisory_xact_lock` keyed by the
  inventory item id (the pattern `InventoryPolicyRepository` already uses). The lock is taken whether or
  not the item has catalog rows yet, so two concurrent creates that both ask for `preferred=true` are
  serialised and the second one clears the first; the unique index never surfaces as a 500. Setting
  `preferred=true` clears the previous preferred row of the same item; setting it to false leaves the item
  with no preferred supplier.
- **Delete** is refused (409 `SUPPLIER_ITEM_IN_USE`) while a PO line in DRAFT, PENDING_APPROVAL,
  APPROVED, CONFIRMED or PARTIALLY_RECEIVED orders that pair. Price periods go with the row (`ON DELETE
  CASCADE`).
- Optimistic locking on `version` for PUT and DELETE (`OPTIMISTIC_LOCK`).
- Writes are `@Auditable` (resource type `supplier-item`).

## 4. PR2 — PO lines follow the catalog

### 4.1 Where

`checkLines` gains the supplier (id, currency, lead time). It is the single choke point for STANDARD PO
line writes, so create and draft edit are covered together, and SUBCONTRACT POs are exempt without a
branch (they never reach it). A test pins that exemption anyway.

BR-PO-001's enforcement point becomes `SupplierItem.orderQuantityViolations`, called from
`ProcurementServiceImpl.checkLines`. `05-business-rules.md` and the `createPurchaseOrder` javadoc are
updated to say so.

### 4.2 Order inside `checkLines`

1. Per line: inventory item resolved, duplicate SKU refused, description resolved — unchanged.
2. Load the supplier's catalog rows for all the request's inventory items in **one** query.
3. Per line, collecting errors:
   - not listed → `lines[i].sku` / `SUPPLIER_ITEM_NOT_LISTED`;
   - below MOQ → `lines[i].quantity` / `PO_LINE_BELOW_MOQ`;
   - not a pack multiple → `lines[i].quantity` / `PO_LINE_NOT_PACK_MULTIPLE`;
   - price: a typed price wins (negotiated price). A null price takes `current_price` when the PO
     currency equals the catalog currency; otherwise `lines[i].unitPrice` / `PO_LINE_PRICE_REQUIRED`.
   Any error → one `BusinessException(SUPPLIER_TERMS_VIOLATED, 409, fieldErrors)`, same shape as
   `SKU_NOT_PURCHASABLE`.
4. Amount checks (quantity range, price scale and cap, tax rate, total cap) — moved here from the top of
   the method, because the price is only known after step 3.
5. `requirePurchasable` — unchanged.

### 4.3 Lead time and expected date

- PO `leadTimeDays` = the largest `effectiveLeadTimeDays` among its lines.
- When the request has no `expectedAt`, it is today (business calendar) + that lead time. Today it is
  today + the supplier's lead time.
- Draft edit recomputes both the same way.

### 4.4 API contract changes

- `unitPrice` becomes optional on `CreatePOLineRequest` and `UpdatePOLineRequest`.
- New error codes: `SUPPLIER_TERMS_VIOLATED` (409) as the envelope code; `SUPPLIER_ITEM_NOT_LISTED`,
  `PO_LINE_BELOW_MOQ`, `PO_LINE_NOT_PACK_MULTIPLE`, `PO_LINE_PRICE_REQUIRED` as field codes. All of them
  get `_vi` and English messages.
- Existing POs are never re-validated; catalog edits do not touch written POs.

### 4.5 Order-up-to level `maxQty`

- `InventoryPolicy` gains `Integer maxQty`, mapped to `inventory_items.max_qty`. The inventory-control
  endpoint (SCRUM-70) exposes it through its existing request/response.
- Rule: `maxQty ≥ reorderPoint` when both are set; otherwise 400 `VALIDATION_FAILED` with the error on
  `maxQty`. Checked in the inventory domain where the policy is configured.
- `min_qty` stays unused; the javadoc of `InventoryPolicy` says it is superseded by `safetyStock` /
  `reorderPoint`.

### 4.6 Data and tests

- `src/main/resources/db/demo/R__demo_supplier_items.sql`, repeatable, modelled on
  `R__demo_catalog_selling_prices.sql`: lists the demo SKUs not yet in the catalog for the demo suppliers,
  `ON CONFLICT (supplier_id, inventory_item_id) DO NOTHING`, plus an opening price period for each.
- `support/SupplierItemFixtures` — one call to list a SKU for a supplier. The existing tests that raise POs
  through the service (SupplierProcurement, PurchaseOrderDraftEdit, PurchaseOrderWarehouseScope,
  SupplierSpendByCurrency, ProductEndOfLifePurchasing, ProductLifecycle, ProcurementPurchasability,
  ProcurementBusinessDate, and any others the build turns up) seed the catalog through it. Tests that
  build a `PurchaseOrder` directly are not affected.

## 5. Tests

**PR1**
- CRUD happy path through the real security chain; 403 without the resource.
- Duplicate pair → 409 `SUPPLIER_ITEM_DUPLICATE`.
- Inactive supplier refused on create and update.
- Unknown SKU → `INVENTORY_ITEM_NOT_FOUND`; non-purchasable SKU → `SKU_NOT_PURCHASABLE`.
- `moq` not a multiple of `packSize` → 400.
- Price change closes the open period at now and opens a new one; no overlap; unchanged price writes no period.
- Preferred switch leaves exactly one preferred row; two concurrent switches (and two concurrent preferred
  creates) serialise.
- Delete refused while an open PO line uses the pair; allowed once the PO is RECEIVED, CLOSED or CANCELLED.
- SKU → suppliers lists the preferred supplier first.
- `SupplierItem` unit tests for the invariants and `orderQuantityViolations`.

**PR2**
- Unlisted SKU refused; below MOQ refused; not a pack multiple refused.
- Several bad lines → one refusal, one error per field.
- Null price prefilled from the catalog; typed price kept.
- PO in another currency with a null price → `PO_LINE_PRICE_REQUIRED`; with a typed price → accepted.
- PO lead time is the largest line lead time; a line without one uses the supplier's.
- No `expectedAt` → today + that lead time.
- SUBCONTRACT PO untouched.
- Draft edit to below MOQ refused.
- `maxQty < reorderPoint` → 400.

Each PR runs the focused tests, `ModularityTest`, `ArchitectureTest` and `tools/verify.py` per task, and
one full `mvn verify` at the end.

## 6. Coordination

- **Tú** (schema and PO code owner):
  - add `ck_supplier_items_moq_pack CHECK (moq % pack_size = 0)` and `CHECK (max_qty IS NULL OR
    reorder_point IS NULL OR max_qty >= reorder_point)` on `inventory_items`;
  - PO create/edit paths and ~14 PO tests change (catalog seeding);
  - decide on dropping `inventory_items.default_supplier_id` (unused; `supplier_items.is_preferred` is the
    single source of truth).
- **Võ**: `maxQty` appears on `/products/{productId}/skus/{skuId}/inventory-control`.
- **FE**: supplier detail gets an "items" tab; the PO form lists only the chosen supplier's SKUs, the price
  is optional and prefilled; field error codes above.

## 7. Out of scope

Replenishment proposals / MRP (later consumers of `is_preferred`, `moq`, `pack_size`, `max_qty`); alerts
when a preferred supplier becomes INACTIVE; re-validating existing POs.
