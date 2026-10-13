# SCRUM-45 Variant & SKU management — design

*2026-10-13 · StockFlowCommerce (FA26SE029) · owner: Phương*

Jira: [SCRUM-45](https://minh7n3.atlassian.net/browse/SCRUM-45) (WBS 3.1.2) — subtasks
[SCRUM-58](https://minh7n3.atlassian.net/browse/SCRUM-58) attribute configuration,
[SCRUM-60](https://minh7n3.atlassian.net/browse/SCRUM-60) auto SKU code generation,
[SCRUM-61](https://minh7n3.atlassian.net/browse/SCRUM-61) manual SKU input with uniqueness validation.
[SCRUM-373](https://minh7n3.atlassian.net/browse/SCRUM-373) (variant matrix UI) is frontend and only
consumes the API designed here.

## 1. Why the Jira description is out of date

The ticket was written on 2026-09-11 against classes that no longer exist in that shape. Since then the
PIM tables were migrated (`V20260928001000`–`001300`), the legacy tables dropped (C1,
`V20261011000300`), and the business narrowed to B2B cup and packaging printing (2026-10-06).

| The ticket assumes | What is true on `develop` |
|---|---|
| A `SkuJpaEntity` with `code`, `barcode`, `lotTracked`, `unitOfMeasure` | **A variant is the SKU** (`product.variants.sku`, decision D1). There is no SKU table. Barcode, UoM and tracking flags live on `inventory.inventory_items`, created per variant by trigger `tg_variant_inventory_item`, and are already edited through `SkuLogisticsController` / `InventoryControlController`. |
| `VariantJpaEntity` has only `productId` + `name` | It maps `product.variants` fully; `ProductVariantController` already has list/get/add/update/activation/blocking/obsoletion. |
| Attribute storage is undecided (table vs JSON) | Decided and migrated: seven tables (§3), with integrity triggers in `V20260928001300__pim_integrity.sql`. **No Java maps them yet.** |
| Price might belong on the SKU | Price is `catalog.pricing_rule` (`BASE:<sku>`), edited at `/products/{id}/skus/{sku}/base-price`. Out of scope. |
| SKU uniqueness needs a DB constraint | `uk_variants_sku`, `ck_variants_sku` and `tg_variants_sku_frozen` exist. What is missing is the domain half: BR-PRD-002 names a `Sku` value object that was never written. |

What actually remains is the Java side of the attribute model, deriving variants from it, and the SKU
rules. **No migration is needed** for any of it; the schema owner (Tú) is not blocked on.

## 2. Scope

**In scope**
1. Attribute dictionary: attributes and their values (master data).
2. Category attributes: the suggested attribute set of a category.
3. Product attributes: variant axes with their options, and descriptive attributes.
4. Variants built from options: derived `attribute_signature`, auto SKU code, manual SKU override with
   uniqueness in the domain and the database.
5. Variant matrix generation on the backend.
6. `POST /products` accepting axes, so a product with variants is created in one transaction.
7. Variant endpoints moved to their own permission resources.

**Out of scope**
- Customization templates and print areas — SCRUM-297.
- Prices, including per-customer prices (open gap in `2026-10-06-b2b-production-plan.md`).
- Barcode / UoM / weight / tracking flags — already on the inventory item.
- The FE screens — SCRUM-373 and the FE repo.
- A foreign key from `production.*.blank_sku` to `variants.sku` — schema owner.
- Any migration.

## 3. The data model being mapped

All in schema `product`, all already migrated. Listed with the rules the triggers enforce, because the
service must state the same rules first and return a real error code (CLAUDE.md §7: every invariant
twice).

| Table | Holds | Database rules |
|---|---|---|
| `attributes` | `code`, `name`, `data_type` (TEXT, NUMBER, BOOLEAN, SELECT, MULTISELECT, COLOR), `unit`, `is_filterable`, `is_searchable`, `sort_order`, `is_active` | `code` unique, `^[A-Z0-9][A-Z0-9_]{0,49}$`, immutable |
| `attribute_values` | `attribute_id`, `code`, `label`, `value_number`, `hex_color`, `sort_order`, `is_active` | `(attribute_id, code)` unique; code `^[A-Z0-9][A-Z0-9_-]{0,49}$`; hex `^#[0-9A-F]{6}$` |
| `category_attributes` | `category_id`, `attribute_id`, `default_role`, `is_required`, `sort_order` | pair unique; role VARIANT_AXIS or DESCRIPTIVE |
| `product_attributes` | `product_id`, `attribute_id`, `role`, `sort_order` | pair unique; product/attribute/role immutable; a VARIANT_AXIS must be SELECT or COLOR |
| `product_attribute_options` | `product_attribute_id`, `attribute_value_id` | pair unique; only on a VARIANT_AXIS; value must belong to the axis's attribute |
| `product_descriptive_values` | `product_attribute_id`, one of `attribute_value_id` / `value_text` / `value_number` / `value_boolean` | exactly one non-null; type must match; only MULTISELECT may hold several rows |
| `variant_attribute_values` | `variant_id`, `product_attribute_option_id` | option must belong to the variant's product; at most one option per axis |
| `variants` (already mapped) | `sku`, `name`, `status`, `is_default`, `attribute_signature`, `position` | `sku` unique and `^[A-Z0-9][A-Z0-9._-]{0,63}$`; `(product_id, attribute_signature)` unique; one default per product; SKU frozen once not DRAFT |

Every FK from these tables to the dictionary is `ON DELETE RESTRICT`; product-side rows cascade from the
product.

## 4. Decisions

### D1. The default variant stays, and is never renamed automatically
`POST /products` without axes keeps today's behaviour: one variant, SKU = product code, signature
`DEFAULT`, `is_default = true`. Three things depend on it existing:
- `Product.submit` does not check "at least one variant" (BR-PRD-001) because the product is created with one.
- Other modules use the product code as a SKU straight after creation (`SupplierProcurementIntegrationTest:357`).
- Many B2B items have no variants at all (a single-size lid, one bag design).

When axes are added later to such a product (only possible while it is DRAFT, D3), the default variant
**takes the first combination**: its options and signature are set, its **SKU is kept**. This is the
same behaviour as Shopify's "Default variant". An automatic rename is rejected because a rename is only
legal while nothing references the SKU (stock, price, PO lines — `V20261008000200`), which lives in
other modules and cannot be checked in advance. The user can still rename through `PUT` while the
variant is DRAFT; the database refuses when it must.

### D2. A product created with axes never gets a placeholder SKU
`POST /products` accepts optional `axes`. With axes, the product, its axes, its options and the full
matrix are written in one transaction; the first combination is the default variant and **every** SKU
follows the rule in D5. This is what F-PRD step 01 describes (`+ product, + variant × N`) and what the
FE create form already sends.

### D3. Axes are fixed once the product leaves DRAFT; options are not
- Adding or removing an **axis** is allowed only while the product is `DRAFT`. An axis added to a
  product with live variants would leave existing stock with no value on that axis, which the system
  cannot guess.
- Adding an **option** to an existing axis (a new 20OZ size) is allowed whenever the product accepts
  changes (`Product.acceptsChanges()`: not PENDING_APPROVAL, not DISCONTINUED). Its variants are created
  as DRAFT and go through activation as today.
- Removing an option is allowed only while no variant uses it; the FK `RESTRICT` backs it.

### D4. Attribute signature format
`ATTR=VALUE;ATTR=VALUE`, attribute codes sorted ascending, value codes from `attribute_values.code`.
Example `CAPACITY=12OZ;COLOR=CLEAR`. `;` matches the seed and demo data; the migration comment's `|` is
historical and cannot be edited (applied migration). Two special forms stay valid:
- `DEFAULT` — the default variant of a product with no axes.
- `SKU=<sku>` — an extra variant of a product with no axes (today's behaviour, legacy rows).

The signature is **derived, never accepted from the client**. `VariantRequest.attributeSignature` is
removed.

### D5. SKU code generation rule
`PRODUCT_CODE-OPTION_CODE-OPTION_CODE…`, option codes ordered by the axis `sort_order` (then attribute
code). Example: product `CUP-PET`, axes CAPACITY then COLOR → `CUP-PET-12OZ-CLEAR`.
- Collision with an existing SKU → `409 VARIANT_SKU_ALREADY_EXISTS`. No silent `-2` suffix: a SKU is
  printed on labels and must be predictable.
- Longer than 64 characters → `400 VALIDATION_FAILED`; the user supplies a manual SKU.
- Product codes allow `_`, which the SKU alphabet also allows; no translation needed.

### D6. Manual SKU
Any create path accepts an optional `sku`; when given it overrides D5. It is upper-cased and validated
by the new `Sku` value object, whose pattern is exactly `ck_variants_sku`. The current DTO pattern
`[A-Za-z0-9]…` is replaced by normalise-then-validate in one place.

### D7. Matrix generation is idempotent and bounded
`POST /products/{id}/variants/generation` computes the cartesian product of the selected options,
skips every combination whose signature already exists on the product, and creates the rest as DRAFT.
Calling it twice creates nothing the second time. A single call may create at most **100** variants
(`400 VARIANT_MATRIX_TOO_LARGE`), so a careless selection cannot create thousands of inventory items
in one transaction.

### D8. Permissions
| Endpoints | Resource | Actions |
|---|---|---|
| Attribute dictionary, category attributes | `product-attributes` | READ / CREATE / UPDATE / DELETE |
| Product axes and options, variants, generation | `product-variants` | READ / CREATE / UPDATE; activation and obsoletion APPROVE |
| Descriptive attributes | `product-products` | READ / UPDATE |

Checked against the seeds: the two roles holding `product-products` (ECOMMERCE_ADMIN, SALES_STAFF) hold
the equivalent `product-variants` actions (`V20260928006000:45-54`), so moving the variant endpoints
changes nobody's access and needs no grant migration.

### D9. Category attributes are suggestions
The backend stores and returns them; it does not force a product to use them. `is_required` is a hint
for the FE form. Enforcing it would block every product created before the category got its set.

## 5. Components

All inside the `product` module; nothing new in `product.api` (no other module needs these types).

### Domain (`product/internal/domain`, no Spring/JPA)
- `Attribute` aggregate with its `AttributeValue`s: code/type immutability, values only for
  SELECT/MULTISELECT/COLOR, `hexColor` required for COLOR and forbidden otherwise, deactivate instead of
  delete when in use. Port `AttributeRepository`.
- `Sku` value object: normalise + `ck_variants_sku` pattern. Used by every SKU entry point.
- `VariantSignature` value object: built from `(attributeCode, valueCode)` pairs, sorted, plus the
  `DEFAULT` / `SKU=` forms.
- `SkuCodeRule`: pure function `(productCode, orderedOptionCodes) → Sku` (D5).
- `VariantMatrix`: pure cartesian product with the D7 limit and the "skip existing signatures" step.

### Persistence (`internal/entity`, `internal/repository`)
Entities for the six unmapped tables, a `VariantAttributeValueJpaEntity`, and adapters. Reads that list a
product's axes with options, or variants with their option values, use one join query each — no
per-row lazy loads (see the `jpa-orm-review` skill).

### Services (`internal/service`)
- `AttributeDictionaryService` — dictionary and values.
- `CategoryAttributeService` — read and replace a category's set.
- `ProductAttributeService` — axes, options, descriptive values; D3 checks.
- `ProductVariantService` (existing, extended) — add/update from options, generation, the D1 default
  transform. Every write keeps locking the product first (`requireChangeable` → `findForUpdate`), which
  already serialises concurrent variant edits of one product.
- `ProductServiceImpl.create` — calls the variant service when `axes` are present (D2).

### Error codes (new, in `common/error/ErrorCode`)
`ATTRIBUTE_NOT_FOUND` 404, `ATTRIBUTE_VALUE_NOT_FOUND` 404, `ATTRIBUTE_CODE_ALREADY_EXISTS` 409,
`ATTRIBUTE_IN_USE` 409, `PRODUCT_AXES_LOCKED` 409, `VARIANT_OPTIONS_INVALID` 400 (missing axis, two
options for one axis, option not on the product), `VARIANT_MATRIX_TOO_LARGE` 400. Existing
`VARIANT_SKU_ALREADY_EXISTS` and `INVALID_VARIANT_TRANSITION` are reused.

## 6. API

Base `/api/v1`. All writes take the entity `version` where an existing row is changed (optimistic
locking, as elsewhere).

### Attribute dictionary — `product-attributes`
| Method | Path | Notes |
|---|---|---|
| GET | `/attributes?q&dataType&active&page&size` | list |
| GET | `/attributes/{attributeId}` | with values |
| POST | `/attributes` | `code, name, dataType, unit, filterable, searchable, sortOrder` |
| PUT | `/attributes/{attributeId}` | `version, name, unit, filterable, searchable, sortOrder, active`; `code` and `dataType` immutable |
| DELETE | `/attributes/{attributeId}` | only when unused, else `409 ATTRIBUTE_IN_USE` |
| POST | `/attributes/{attributeId}/values` | `code, label, valueNumber, hexColor, sortOrder` |
| PUT | `/attributes/{attributeId}/values/{valueId}` | `version, label, valueNumber, hexColor, sortOrder, active` |
| DELETE | `/attributes/{attributeId}/values/{valueId}` | only when unused |

A deactivated attribute or value cannot be chosen for a new axis, option or descriptive value; existing
variants keep it.

### Category attributes — `product-attributes`
| Method | Path | Notes |
|---|---|---|
| GET | `/categories/{categoryId}/attributes` | the set, with attribute summaries |
| PUT | `/categories/{categoryId}/attributes` | replaces the set: `[{attributeId, defaultRole, required, sortOrder}]`; VARIANT_AXIS only for SELECT/COLOR |

### Product attributes
| Method | Path | Resource | Notes |
|---|---|---|---|
| GET | `/products/{productId}/attributes` | `product-variants` READ | axes with options + descriptive values |
| PUT | `/products/{productId}/axes` | `product-variants` UPDATE | replaces the set `[{attributeId, sortOrder, optionValueIds[]}]`; the diff is checked against D3 |
| PUT | `/products/{productId}/descriptive-attributes` | `product-products` UPDATE | replaces the set `[{attributeId, valueIds[] | text | number | boolean}]` |

The options of an axis are ordered by the dictionary (`attribute_values.sort_order`, then code); the
order of `optionValueIds` in a request is not stored. PR 3's matrix order and "first combination"
(D1/D2) follow this order, with axes ordered by `product_attributes.sort_order` and then attribute code.

When `PUT /axes` gives a product its first axes and the default variant still has signature `DEFAULT`,
nothing is generated yet; the default transform (D1) happens at the next generation call.

### Variants — `product-variants`
| Method | Path | Change |
|---|---|---|
| GET | `/products/{productId}/variants` | response gains `options: [{attributeCode, valueCode, label}]` |
| GET | `/products/{productId}/variants/{variantId}` | same |
| POST | `/products/{productId}/variants` | body `{sku?, name?, optionValueIds[], position?}`; product with axes: exactly one option per axis, `sku` defaults to D5; product without axes: no options, `sku` required; action CREATE |
| PUT | `/products/{productId}/variants/{variantId}` | body `{sku, name, optionValueIds?, position?}`; options may change only while the variant is DRAFT |
| POST | `/products/{productId}/variants/generation` | body `{optionValueIds?: {attributeId: [valueId]}}` — omitted means all of the product's options; response `{created: [VariantResponse], skipped: n}`; D7 |
| POST | `…/activation`, `…/blocking`, `…/obsoletion` | unchanged behaviour, resource moved |

`name` defaults to `"<product name> – <label> / <label>"`.

### Products
`POST /products` gains `axes?: [{attributeId, sortOrder?, optionValueIds[]}]` (D2). The response is
unchanged; the variants are read from the variants endpoint.

## 7. Testing

- **Unit (domain):** `Sku` (normalisation, the exact DB alphabet, 64 limit), `VariantSignature`
  (ordering, special forms), `SkuCodeRule`, `VariantMatrix` (cartesian product, skip existing, limit),
  `Attribute` invariants.
- **Integration (Testcontainers, real triggers):**
  - Create a product with axes → N variants, rule-based SKUs, first is default, one inventory item each.
  - Generation run twice → second creates nothing.
  - Product created without axes, then axes, then generation → the default variant keeps its SKU and
    gets the first combination.
  - Adding an axis to an approved product → `409 PRODUCT_AXES_LOCKED`; adding an option → variant
    created DRAFT.
  - Duplicate SKU (manual and generated) → `409`; SKU over 64 → `400`.
  - Deleting a used attribute or value → `409 ATTRIBUTE_IN_USE`.
  - Each service rule also tested against the trigger behind it by bypassing the service once, so a
    drifting rule is caught.
- **Security:** a role without `product-variants` gets 403 on the variant endpoints; SALES_STAFF cannot
  activate.
- **Fitness:** `ModularityTest`, `ArchitectureTest`, `python3 tools/verify.py` after each PR.

## 8. Delivery

Four stacked PRs, one GitHub issue each, under one tracking issue.

| # | PR | Covers | Depends on |
|---|---|---|---|
| 1 | Attribute dictionary and category attributes | §6 dictionary + category; `Attribute` aggregate; error codes | — |
| 2 | Product axes, options and descriptive attributes | §6 product attributes; D3 | 1 |
| 3 | Variants from options, SKU rules and matrix generation | `Sku`, `VariantSignature`, `SkuCodeRule`, `VariantMatrix`; variant endpoints rewritten; D1 transform; D7; D8 permission move | 2 |
| 4 | Create a product with axes, and the docs | D2 in `POST /products`; `05-business-rules.md` BR-PRD-002 enforcement point; F-PRD; `Product` javadoc; FE contract note for SCRUM-373 | 3 |

Coordination:
- **Tú** — PR 4 changes `CreateProductRequest`, `CreateProductCommand` and `ProductServiceImpl.create`.
- **Võ** — PR 1 adds `/categories/{id}/attributes` next to the category endpoints (SCRUM-171); it lives in
  its own controller so the two do not conflict.
- **FE** — the variant request loses `attributeSignature` and gains `optionValueIds` (PR 3). The FE
  screens are still mock-driven, so nothing breaks today.
