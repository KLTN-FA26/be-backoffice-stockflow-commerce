# SCRUM-70 / SCRUM-71 backend handoff

## Status (2026-10-08)

Canonical adapters and an append-only cutover migration are implemented. **Schema-owner review,
upstream PIM writer integration and frontend UAT remain merge gates.** This document does not
claim that the legacy product/gallery creation APIs have completed their separate C1 migration.

The existing merge commit `6936c91` included the develop baseline and supplier/PO fixes at takeover.
During validation, develop advanced to `d1b9590` (#53-57); that update is merged without aborting
the merge. Both SCRUM-158 ATP mapping and 70/71 commerce mapping are retained with distinct names.
There was no unfinished merge at takeover. Existing working changes were retained and formatted;
no reset, clean, merge abort, PR merge or deployment was performed.

## Canonical ownership

- Inventory control reads and writes **inventory.inventory_items**, including its own version,
  audit fields, UoM and tracking flags. No runtime policy reads/writes use `inventory.sku_policy`
  or threshold columns on `product.sku`.
- A canonical `product.variants` insert automatically creates one inventory item with no tracking
  flags and nullable thresholds. The same migration fills items for existing canonical variants.
- Existing `min_qty`/`max_qty` and logistics fields are preserved. `safety_stock` is independent
  of `min_qty`. Added fields are `safety_stock`, `removal_strategy`, `max_shelf_life_days` and
  `policy_configured` (distinguishes unmanaged legacy receipts from explicitly configured policy).
- Tracking supports NONE, LOT, SERIAL and **LOT_SERIAL**. Expiry requires a lot and FEFO, matching
  the existing database constraint; SERIAL alone cannot enable expiry. FIFO uses real receipt
  time, never a fabricated `created_at`/migration timestamp.
- **product.products** owns slug/SEO. Product API edits the source; catalog stores a read snapshot,
  projection progress and publication visibility. Public gallery links use the canonical catalog
  gallery route, not the legacy product-gallery reader. The listing FK targets `product.products`.
  Slugs remain unique at the source and projection and cannot change after first publication,
  including after unpublication. SEO title is at most 255; description input at most 5000.
- Publication reads canonical products, categories, ACTIVE variants and published media with
  reviewer metadata; an image cannot be approved by its recorded creator. New catalog publication
  does not read legacy product/gallery tables. Existing legacy gallery endpoints need the PIM
  owner's cutover before the complete create/approve/gallery/publish UI flow can use these APIs.

## API contract

| Route | Behavior | Permission |
| --- | --- | --- |
| GET/PUT /api/v1/products/{productId}/skus/{skuId}/inventory-control | Canonical product and **variant id**; version is inventory-item version | product-products READ/UPDATE |
| GET/PUT /api/v1/products/{productId}/ecommerce | Canonical source content and product version; separate projected revision | product-products READ/UPDATE |
| GET/PUT /api/v1/products/{productId}/skus/{sku}/base-price | Positive whole VND base price | product-products READ/UPDATE |
| POST /api/v1/products/{productId}/publication or /unpublication | Master/category/gallery/active-SKU/price gates | product-products APPROVE |
| GET /api/v1/catalog/products/{slug}/gallery | Approved canonical media; live visibility guard | Authenticated |
| GET /api/v1/catalog/products[/{slug}] | Projection with live canonical publication guard | Authenticated |

Management URL shapes and permissions are retained. Following the newly merged B2B decision,
storefront list/detail/gallery require authentication and use `/api/v1/catalog/products`.
The older anonymous catalog routes are no longer exposed. FE must supply canonical product/variant identifiers,
reload the inventory-item version, and handle LOT_SERIAL. Ecommerce revision is the canonical
product version; price writes advance it too, so stale edits cannot overwrite another source writer. Legacy ids are not guessed or mapped by
product name. Configure stock on DRAFT/BLOCKED variants as well as ACTIVE ones; only ACTIVE variants
can be published or purchased. Obsolete variants are excluded.

## Policy and publication behavior

- Null thresholds mean unconfigured; zero is a real threshold; negatives are rejected.
- Safety stock <= reorder point when both exist. A threshold above stock is allowed and evaluated
  immediately. Reorder uses usable on-hand <= threshold; safety uses usable on-hand < safety stock.
  Expired/quarantined/damaged stock is excluded; reservations are not subtracted (unlike ATP).
- A successful policy change emits `SkuInventoryControlChanged` with inventory-item version.
  SCRUM-147 may consume it after commit; this PR does not send alert email.
- Incompatible existing stock and active develop cycle counts block operational policy changes.
  Policy changes and stock ingress share the SKU advisory lock; optimistic version prevents lost edits.
- SEO projection may briefly lag; retry reads current product source. Checkout rechecks live status,
  variant availability and server price under product locks. Old events cannot republish hidden data.
- Existing guest/account checkout validation rejects missing price (`PRICE_NOT_AVAILABLE`) and client price
  tampering (`CHECKOUT_PRICE_CHANGED`). No new mandatory quoteId field is introduced. Guest checkout remains disabled by default; the
  retained compatibility tests do not authorize guest sales under the new B2B business scope.
  Public design checkout remains `DESIGN_QUOTE_REQUIRED` until SCRUM-298 supplies accepted quotes.

## Review disposition

| Tu's finding on PR #38 | Result |
| --- | --- |
| Duplicate cycle-count tables / failed CI | Earlier scope split removed extension migrations 001200..001600 and their runtime features; develop owns cycle-count/ledger schema |
| Duplicate inventory policy source | Canonical item adapter; new migration backfills safely and removes private policy table / new legacy SKU control columns |
| Duplicate editable SEO / old product FK | Product API owns canonical SEO; catalog snapshot has canonical FK |
| Scope beyond 70/71 | Preserve split of 146/147/298; see separate handoff manifest |
| Generic publication errors | Specific listing/approval/category/gallery/SKU/availability/price errors, EN/VI bundles |
| Missing SYSTEM_ADMIN permission | Corrective grant in new migration covers inherited procurement-suppliers:DELETE; increments version only when grants change |
| Formatting and i18n conflicts | Touched code formatted; both message and validation bundle pairs have matching unique keys |
| Alert recipients / count ledger / wrong count warehouse | Work belongs to 147/146 and is absent from this PR; not claimed implemented |
| Demo checkout | Await confirmed selling prices; supplier purchase costs are not used as selling prices |

Source review: [29 September](https://github.com/KLTN-FA26/be-backoffice-stockflow-commerce/pull/38#issuecomment-5892128787),
[7 October permissions](https://github.com/KLTN-FA26/be-backoffice-stockflow-commerce/pull/38#issuecomment-6037831143).
Jira: [SCRUM-70](https://minh7n3.atlassian.net/browse/SCRUM-70),
[SCRUM-71](https://minh7n3.atlassian.net/browse/SCRUM-71); all six child tasks were also checked.

## Migration and remaining coordination

PR #36 is still open. This branch retains its existing stacked ancestry; merge order remains
#36 before #38 as requested in review. No worktree or branch for SCRUM-115-118 was modified.

`V20261008000100__canonical_inventory_and_ecommerce.sql` is appended after branch/develop migrations.
Earlier migration contents and checksums remain unchanged. The original temporary policy/listing
schema is transitioned in this new migration, not by rewriting Flyway history.

For databases that used old PR38, run `tools/sql/inventory-commerce-upgrade-preflight.sql` first.
Every private policy must map to a canonical variant/item by SKU; every listing must map to a
canonical product by id. Conflicting flags, non-null thresholds or slug/SEO stop migration with a
reconciliation error. No source is silently selected, text truncated, history repaired or database
reset. Safe upgrades preserve min/max, logistics, policy values, SEO and published URL protection.
Databases that already ran removed 146/147/298 migrations require the schema owner's explicit
upgrade path; this PR does not pretend those histories are compatible.

The PIM owner still needs to migrate the legacy product/variant/gallery writers and existing data.
Do not activate C1/C3 here. The canonical adapters are independently testable but the legacy UI's
full product-creation flow is not accepted until that dependency and identifier migration are done.
The owner checklist is `business-design/db-design/SCRUM-70-71-schema-change-request.md`.

## Verification

After integrating `d1b9590`, ATP batch reads share the single-SKU business-date/expiry rule, and
security tests cover anonymous denial, signed-in catalog/availability reads and mutation permissions.

Final local validation on 08 October after merging `d1b9590`:

- `mvn -B -ntp -o clean verify`: **540 tests passed**, zero failures/errors/skips; packaging passed.
- Includes canonical APIs/gallery, source-version conflict, security B2B gates, SYSTEM_ADMIN,
  fresh/populated migration and conflict rollback, ATP expiry consistency, module and architecture tests.
- Branch diff against develop passes the append-only migration check. No unresolved merge paths.
- The final PR commit is also validated by GitHub Actions; see its check result for that exact commit.

Tests use isolated Docker
PostgreSQL/Redis containers, not application databases. Covered: fresh migrations, populated upgrade
and rollback on conflict, inventory version/tracking/expiry/FIFO/ingress concurrency, canonical SEO,
published slug protection, price validation, public visibility, real security and SYSTEM_ADMIN.

`tools/verify.py` currently reports 29 test-fixture dependency findings in unchanged test files;
no production-code finding. Module and architecture tests remain authoritative CI gates.
