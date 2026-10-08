# SCRUM-70 / SCRUM-71 backend handoff

## Status (2026-10-08)

Canonical adapters and an append-only cutover migration are implemented. **Schema-owner review,
upstream PIM writer integration and frontend UAT remain merge gates.** This document does not
claim that the legacy product/gallery creation APIs have completed their separate C1 migration.

The branch now includes PR #36 at `c2fa904` (code `1671584`) and develop through `276567d` (#58-60).
The conflict resolution retains stock move/adjustment, admin order listing, transfer orders and
canonical inventory/commerce behavior together. PR #36 was pushed first; merge order remains
#36 before #38. No GitHub PR merge or production deployment is performed by this handoff.

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
[SCRUM-71](https://minh7n3.atlassian.net/browse/SCRUM-71). The previous handoff reported child-task checks;
this integration pass did not independently access Jira acceptance criteria.

## Migration and remaining coordination

PR #36 remains the first PR to merge. The supplier review migration is now
`V20260930000500__po_review_permissions_and_attempt_numbers.sql`, followed by #38's
`V20260930001000`, `V20260930001100` and
`V20261008000100__canonical_inventory_and_ecommerce.sql`, then the append-only
`V20261008000200__preserve_unused_draft_sku_rename.sql` fix. This removes the earlier collision
with the unpublished PO review version. All SQL already published on develop, remote #36
and remote #38 retains its original filename/content/checksum.

A DB on the completed #36 release upgrades normally with out-of-order disabled. A populated
clone check preserved three POs, five delivery logs and their attempt numbers while applying
the three original #38 versions, followed by the draft-rename correction. Fresh and populated
upgrades are also regression-tested.

An existing DB from **exactly remote #38 `3bfd605`** has already applied canonical `20261008000100`
but lacks the lower PO review version `20260930000500`. Default Flyway correctly refuses this
history. A disposable DB built from the actual archived release reproduced the failure and
validated the following operator recovery (not an application default):

1. Stop writers, back up and restore a clone. Verify the release's full migration history,
   filenames/checksums, absence of failed/unknown versions and canonical schema state.
2. Run the read-only preflight. With the new migration directory and clone datasource configured,
   inspect `flyway -outOfOrder=true -target=20261008000100 info`. **Only `20260930000500`** may be
   pending. A different pending set or script at version `20261008000100` needs its own plan.
3. On that clone, run `flyway -outOfOrder=true -target=20261008000100 migrate` once. Remove both
   overrides and run normal `flyway migrate` and `flyway validate`; test PO dates, attempts,
   role grants and inventory/commerce behavior. The probe validated all 76 migrations, including the normal final draft-rename correction.
4. Repeat only under the intended environment's operator change plan. No global out-of-order,
   automatic history rewrite, blind repair or database reset is introduced.

The equivalent settings were tested with Flyway's Java API; an installed CLI/VPS was not tested.
For old remote #36 `9fbb90f`, follow the distinct prerequisite recovery in
[the supplier runbook](SCRUM-115-118-backend.md#database-upgrade).
A DB that applied the unpublished **PO** script at `20261008000100` is a different history from
#38's canonical script at that number and must not use either procedure blindly.

Run `psql -X -v ON_ERROR_STOP=1 -f tools/sql/inventory-commerce-upgrade-preflight.sql` first.
History checks work before and after cutover; legacy-source checks run only while the private
policy table exists.
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

## Compatibility with new develop inventory operations

Stock moves preserve receipt timestamps and serial identity. SKU locks precede row locks to match
reservation/policy edits. The old move/adjust request identifies only SKU/location/lot: when several
receipt/serial layers match, it now returns `INVENTORY_POLICY_STOCK_CONFLICT` instead of choosing
an arbitrary row. Consolidating different receipt identities is also refused. A future explicit
layer/serial selector is required to support those ambiguous operations; FIFO replenishment guards
remain active. This limitation is not hidden by a green build.

Transfer creation and dispatch use the configured FIFO/FEFO policy and the Vietnam business date,
exclude expired stock and take SKU locks before row locks at dispatch. Regression fixtures use
canonical tracked SKUs, so the new operations do not rely on invalid lot data on untracked demo SKUs.

## Verification

- Combined #36 plus develop through #59: `mvn -B -ntp -o clean verify` passed **581 tests**,
  zero failures/errors/skips, including packaging, architecture and module checks.
- Final develop #60 integration: full `clean verify` passed **591 tests**, zero failures/errors/skips.
  After the draft-rename SQL QA finding, **27 migration/architecture/module tests plus packaging**
  passed against the correction. The exact pushed commit is also checked by GitHub Actions.
- `python -X utf8 tools/verify.py`: all static checks pass (944 Java files at this integration point).
- Tests cover canonical APIs/gallery, source versions, security B2B gates, SYSTEM_ADMIN,
  fresh/populated migration and conflict rollback, ATP expiry, stock move receipt/serial identity,
  ambiguous-layer refusal and configured transfer allocation.
- Separate disposable-DB probes verified normal #36-to-#38 upgrade with populated PO history and
  the controlled old-remote-#38 prerequisite backfill. Neither probe used a production database.

The SQL QA suite exposed a cutover regression: the auto-created inventory item prevented an
otherwise valid unused DRAFT variant SKU rename. The append-only `20261008000200` migration
preserves item identity/policy with an FK cascade only for unused DRAFT parents, increments the
item version, and retains refusal for stock, pricing and operational references. Direct item
reassignment and ACTIVE variant renaming remain blocked. The migration regression reproduces
the old failure before upgrade; SQL QA then passes all 166 checks.

Schema-owner review, upstream PIM writer migration, canonical FE identifiers, demo selling-price
confirmation and frontend UAT remain the coordination gates above. Tests do not establish them.
