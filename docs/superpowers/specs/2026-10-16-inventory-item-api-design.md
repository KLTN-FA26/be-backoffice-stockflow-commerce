# Inventory item API — separate the inventory item from the product variant

Date: 2026-10-16 · Branch: `fix/inventory-item-api` (stacked on `feature/SCRUM-78-po-catalog-terms`)

## 1. Problem

The database already separates the two concepts:

| | `product.variants` (product module) | `inventory.inventory_items` (inventory module) |
|---|---|---|
| Answers | what is sold | what the warehouse keeps and how |
| Holds | name, SKU code, option signature, DRAFT/ACTIVE status, images | unit of measure, barcode, weight, dimensions, package, storage class, QC, thresholds, FIFO/FEFO, lot/serial tracking, shelf life |
| Referenced by | cart and order lines (`sku → variants.sku`) | stock, receiving, picking, purchase orders (`sku` or `inventory_item_id → inventory_items`) |

They are one-to-one through `inventory_items.sku → variants.sku`; creating a variant creates its item
(database trigger). The `skus` table no longer exists.

The API does not follow. The item's logistics and stock policy are edited through
`/api/v1/products/{productId}/skus/{skuId}/logistics` and `/inventory-control`, where `{skuId}` is in
fact the **variant id**, and every edit goes through the product module (`SkuInventoryControlService`).
Consequences:

- the path names a table that is gone and an id that is not the item's;
- editing a SKU's weight or reorder point is refused while the *product* is `PENDING_APPROVAL` or
  `DISCONTINUED` — a content-review state blocking a warehouse fact;
- each edit writes a `LOGISTICS` / `INVENTORY_CONTROL` row into the product's history
  (`product.product_change_log`), and the `platform.audit_log` row carries the variant id as the
  `inventory-item` resource id;
- the guard is `product-products`, not the already seeded `inventory-inventory-items`.

## 2. Decisions

| # | Decision | Rejected alternative |
|---|---|---|
| D1 | The inventory module owns the item's API. No product-status guard, no product-history rows; the audit trail is `platform.audit_log` with the **item id** as resource id. | Inventory publishes an event so product keeps writing history; or keep the product guard by copying product status into inventory. |
| D2 | Path identifier is the item's **UUID**. `VariantResponse` gains `inventoryItemId` so the product screen can navigate; `GET /inventory/items?sku=` resolves a code. | SKU code in the path: it changes while a variant is DRAFT, and procurement already references items by id. |
| D3 | One GET returns the whole item; edits are two grouped PUTs, `/logistics` and `/policy` (renamed from `inventory-control`). | A single PUT with ~25 fields. |
| D4 | The two product routes are deleted outright, not deprecated. Not in production; no external client. | Keep them deprecated for one PR. |
| D5 | The list endpoint ships now, paged with basic filters. | Exact `?sku=` lookup only; or no list. |

## 3. API

All under the inventory module, guarded by `inventory-inventory-items` (seeded in `V20260928006000`):
`WAREHOUSE_MANAGER` and `INVENTORY_PLANNER` all actions, `WAREHOUSE_STAFF` and `PROCUREMENT_STAFF`
`VIEW_PAGE,READ`.

```
GET /api/v1/inventory/items?sku=&q=&storageClass=&policyConfigured=&page=&size=   READ
GET /api/v1/inventory/items/{id}                                                  READ
PUT /api/v1/inventory/items/{id}/logistics                                        UPDATE
PUT /api/v1/inventory/items/{id}/policy                                           UPDATE
```

**List** — `PageResponse` of `{ id, sku, unitOfMeasure, barcode, storageClass, trackingMode,
policyConfigured }`, ordered by `sku`. Filters, all optional and combined with AND:
`sku` exact match; `q` case-insensitive substring of `sku` or `barcode`; `storageClass` one value;
`policyConfigured` boolean. The list carries no product or variant name: inventory may not call
product (see §4.3).

**Detail** — GET, and the response of both PUTs:

```json
{ "id": "…", "sku": "CUP-12", "version": 7,
  "logistics":  { "unitOfMeasure", "barcode", "weightKg", "lengthCm", "widthCm", "heightCm",
                  "packageWeightKg", "packageLengthCm", "packageWidthCm", "packageHeightCm",
                  "packageCount", "packSize", "storageClass", "requiresAdultSignature",
                  "shippingRestrictionNote", "qcRequired" },
  "policy":     { "reorderPoint", "safetyStock", "maxQty", "removalStrategy", "trackingMode",
                  "expiryTracked", "maxShelfLifeDays" },
  "evaluation": { "usableOnHand", "reorderRequired", "belowSafetyStock" } }
```

**PUT bodies** — `{ "version": <item version>, …the group's fields }`, full replacement of that group.
Both groups share the one item `version`.

**Errors**

| Case | Status · code |
|---|---|
| unknown id | 404 · `INVENTORY_ITEM_NOT_FOUND` (both PUTs and GET) |
| stale `version` | 409 · `OPTIMISTIC_LOCK` (both PUTs — today policy answers `CONFLICT`) |
| unit of measure changed while stock of the SKU is held | 409 · `INVENTORY_POLICY_STOCK_CONFLICT` |
| barcode already on another item | 409 · `CONFLICT` |
| policy incompatible with stock on hand | 409 · `INVENTORY_POLICY_STOCK_CONFLICT` |
| removal / tracking / expiry change during an active cycle count | 409 · `COUNT_POLICY_CONFLICT` |
| invalid field values | 400 · validation, as today (`StockPolicy.validate`, `ItemLogisticsRules.validate`) |

**Product side** — `VariantResponse` gains `inventoryItemId` (UUID). The two routes
`/api/v1/products/{productId}/skus/{skuId}/logistics` and `/inventory-control` are removed.

## 4. Code

### 4.1 Inventory — added

```
inventory/internal/
├── controller/
│   ├── InventoryItemController       the four endpoints; InventoryResources.INVENTORY_ITEMS = "inventory-inventory-items"
│   ├── InventoryItemWebMapper
│   └── dto/ InventoryItemResponse, InventoryItemSummaryResponse,
│            UpdateItemLogisticsRequest, UpdateItemPolicyRequest
├── service/
│   ├── InventoryItems                internal interface: get(id), search(filter, page),
│   │                                 describe(id, version, logistics), configure(id, version, policy)
│   └── InventoryItemsImpl            @Transactional; writes are @Auditable(resourceType = "inventory-item", resourceId = "#id")
└── repository/
    └── InventoryItemSearch           JDBC, paged and filtered, after StockBrowseSearch
```

`InventoryItemsImpl` resolves `id → sku` in one query (404 if absent), then runs the existing rules
unchanged: row lock, version check, unit-of-measure guard, policy compatibility and cycle-count
checks, barcode uniqueness, and the `SkuInventoryControlChanged` event that re-evaluates stock
alerts. The bodies of `configure` and `describe` move here from `InventoryControlServiceImpl`; no rule
is rewritten. `InventoryPolicyRepository` gains the id lookup.

### 4.2 Inventory — `api` narrowed

After product stops calling them, no other module uses `InventoryControlService.configure`,
`describe` or `evaluate`, so they leave the interface: only inventory may change an item. The reads
stay — `item()` (procurement reads the unit of measure), `logistics()` (fulfillment reads the barcode),
`policy()`, `expiryWarningDays()`.

`InventoryService` gains `Map<String, UUID> itemIdsOf(Collection<String> skus)`, the inverse of the
existing `skusOf(ids)`, one query for any number of SKUs.

### 4.3 Product — removed and changed

- Removed: `InventoryControlController`, `InventoryControlWebMapper`, `SkuLogisticsController`,
  `SkuInventoryControlService`, `RecordDiff` (its only caller), DTOs `InventoryControlResponse`,
  `UpdateInventoryControlRequest`, `SkuLogisticsRequest`, `SkuLogisticsResponse`, and the two
  logistics methods of `ProductAdminWebMapper`.
- `ProductVariantService.VariantView` and `VariantResponse` gain `inventoryItemId`, filled by one
  `inventory.itemIdsOf(...)` call per response, never one per variant.
- `ProductChangeType.LOGISTICS` and `INVENTORY_CONTROL` stay: existing `product_change_log` rows use
  them and `/products/{id}/versions` must still read those rows. Nothing writes them any more; the
  javadoc says so.

The module dependency is unchanged: product → `inventory :: api`, never the reverse.

### 4.4 Unchanged

No migration (`ck_product_change_log_type` still admits the two values; schema owner Tú is not
needed). The variant trigger that creates items. The `contracts` event name
`SkuInventoryControlChanged` — renaming it is separate work.

## 5. Tests

New `InventoryItemIntegrationTest` (Testcontainers):

- GET returns all three groups; unknown id → `INVENTORY_ITEM_NOT_FOUND`.
- List: paging; each filter alone; `sku` and `q` together.
- PUT logistics: saves and bumps `version`; stale version → `OPTIMISTIC_LOCK`; unit change with stock
  held → 409; duplicate barcode → 409.
- PUT policy: saves; incompatible with stock → 409; active cycle count → `COUNT_POLICY_CONFLICT`;
  the stock alert is re-evaluated.
- `platform.audit_log` row has `resource_id` = the item id.
- Neither PUT writes a `product_change_log` row.

Security: `WAREHOUSE_STAFF` reads and gets 403 on PUT; `INVENTORY_PLANNER` and `WAREHOUSE_MANAGER`
write; a role with only `product-products:UPDATE` gets 403.

Product: `VariantResponse.inventoryItemId` is right; listing N variants costs one inventory query
(measured); the old routes answer 404. `ProductHistoryAndRemovalIntegrationTest` stops expecting new
`LOGISTICS` / `INVENTORY_CONTROL` rows and gains a case reading a pre-existing one through `/versions`.

Existing tests on the old routes move to the new API: `InventoryCatalogIntegrationTest`,
`InventoryCatalogSecurityIntegrationTest`.

Checks: `tools/verify.py`, `ModularityTest` and `ArchitectureTest` after every package or `api`
change; one full `mvn verify` at the end of the branch.

## 6. Documentation

- `docs/SCRUM-70-71-backend.md` — the API contract row for `/inventory-control` points to the new API.
- Javadoc made true: `InventoryItemDirectory` ("nothing in this module edits inventory items yet"),
  `InventoryControlService` ("joins the product transaction"), `ProductChangeType`.
- OpenAPI `@Operation` / `@Schema` on the four endpoints.

## 7. Who to tell

- **Frontend** — breaking: old routes gone; use `/inventory/items/{id}` with `inventoryItemId` from
  `VariantResponse`; editing now needs `inventory-inventory-items:UPDATE`.
- **Võ Hoàng Minh** — author of `SkuInventoryControlService` / `SkuLogisticsController`, removed here.
- **Whoever owns role grants** — product managers lose logistics editing unless granted
  `inventory-inventory-items:UPDATE`.
- **Tú** — for awareness only; no migration.
