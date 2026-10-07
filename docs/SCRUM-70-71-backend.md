# SCRUM-70 / SCRUM-71 — scoped backend handoff

## Status (2026-10-03)

**Not ready to merge or declare complete.** The scope split is implemented, but schema ownership
and canonical-source adapters still require the schema-owner handoff below. Passing tests against
the temporary legacy adapters is not acceptance of those adapters.

- Current branch: `feature/SCRUM-70-71-inventory-control-ecommerce`.
- Source snapshot preserved at `feature/SCRUM-146-147-298-split-handoff` (`b7d6cda`).
- Latest PR36 fixes (`9fbb90f`) incorporated into the working tree; no new commit/push yet.
- Schema request: [owner checklist](business-design/db-design/SCRUM-70-71-schema-change-request.md).
- Split manifest: [coordination handoff](SCRUM-146-147-298-split-handoff.md).

## Scope boundary

| Keep in 70/71 | Move to separate coordination |
| --- | --- |
| Nullable per-SKU reorder/safety thresholds, FIFO/FEFO, tracking/expiry validation | 146: count creation, assignments, blind/recount, approval and posting |
| Immediate threshold evaluation + SkuInventoryControlChanged contract | 147: alert episodes, inbox, recipient selection, mail/retry |
| Product publication prerequisites, VND selling prices, public read projection | 298: design quote negotiation, offer acceptance and order consumption |
| Minimal existing checkout price validation, without new quoteId request field | Checkout fingerprint schema and full quote-dependent checkout integration |

The minimal price check remains deliberately: removing the quote workflow must not make public
checkout trust a submitted unit price again. Standard guest/account checkout compares the client's
expected amount with the current server price. Missing price is `PRICE_NOT_AVAILABLE`; a changed
price is `CHECKOUT_PRICE_CHANGED`. No made-up demo price or fallback is supplied.

New customer-facing design checkout is **blocked with DESIGN_QUOTE_REQUIRED** until SCRUM-298
provides the accepted server quote integration. Existing order reads/cancellation remain available.
The trusted internal compatibility command remains an existing integration, not a public bypass.
The HTTP request has no new mandatory `quoteId` field in this scoped PR.

## Current routes (legacy adapters; not final schema acceptance)

| Routes | Purpose | Permission |
| --- | --- | --- |
| GET/PUT /api/v1/products/{productId}/skus/{skuId}/inventory-control | Read/replace policy with version; return immediate threshold evaluation | product-products READ/UPDATE |
| GET/PUT /api/v1/products/{productId}/ecommerce | Read/edit listing and revision | product-products READ/UPDATE |
| GET/PUT /api/v1/products/{productId}/skus/{sku}/base-price | Read/update VND base price | product-products READ/UPDATE |
| POST /api/v1/products/{productId}/publication or /unpublication | Publish/unpublish using existing approval rules | product-products APPROVE |
| GET /api/v1/public/catalog/products[/{slug}] | Public cards/detail; live publication guard | Anonymous |

Confirm route/permission changes with the inventory item owner during adapter migration; do not
silently change FE bindings. Controller annotations and generated OpenAPI are authoritative.

## Policy and publication semantics retained

- Null thresholds mean unconfigured; zero is a real threshold; negatives are rejected.
- Safety stock <= reorder point when both exist. Threshold above stock is allowed.
- Reorder uses usable on-hand <= threshold; safety uses usable on-hand < safety stock.
  Expired/quarantined/damaged stock is excluded, reservations are not subtracted (unlike ATP).
- Policy writes emit an after-commit consumable event; no alert delivery worker remains in this PR.
- Stock compatibility is checked before tracking/removal changes. An active count check reads the
  develop cycle_count/cycle_count_line schema, not the removed private count schema.
- Product master and gallery approval, category, SKU and selling price are separate publish checks.
- Draft/unapproved products are not public. Slug uniqueness and published URL protection remain.
- Projection may briefly lag SEO edits. Checkout validates live publication and price under locks.
- VND only, positive whole selling-price amounts.

## Review disposition

| Review finding | Disposition |
| --- | --- |
| Duplicate cycle-count migration prevents fresh boot | Removed five extension migrations 001200..001600 from this branch; recoverable in snapshot |
| Duplicate inventory policy source / legacy SKU | **Blocked:** owner request specifies inventory_items fields, metadata and cutover |
| Catalog owns canonical SEO incorrectly | **Blocked:** product.products is the agreed target; adapter/migration cutover still pending |
| Extra 146/147/298 scope | Removed endpoints/services/entities/tests, notification hooks and new quoteId field; handoff manifest supplied |
| Price/checkout contract | Standard server validation retained, price errors clarified; full quote workflow moved out |
| Generic CATALOG_NOT_READY | Replaced by listing/approval/category/gallery/SKU/availability/price codes with English/Vietnamese bundles |
| Alert recipients/retry; stock-movement ledger; count warehouse scope | Assigned explicitly to separate 147/146 work; not represented as fixed production features |
| Formatting and language | Conflict markers resolved and new errors localized; broader legacy formatting/DTO alignment belongs to adapter cutover |
| PR description | This scoped summary is prepared for the PR; remote description is not updated by this working-tree change |

## Migration safety / merge gate

No new migration was authored in this split. Two existing branch migrations (001000 policy,
001100 catalog) remain temporarily so the legacy implementation can be tested; **they must not
be merged as the final design**. They are not compliant with the new single-source schema.
Tú must approve the replacement/backfill and application cutover together. Do not simply delete
these migrations while the current repositories still need their columns/tables.

Do not repair Flyway, erase history or drop a developer database automatically. Databases which
already applied extension migrations require the owner's explicit upgrade plan.

Before merging: owner schema PR, source adapters/fixtures migrated, no dual source, fresh+upgrade
tests, DB QA/orphans, permissions and role versions, FE price contract/UAT, then commit/push
with repository-owner approval.

## Verification of the split (2026-10-03)

- First full `mvn -o clean test`: 457 tests, one FIFO fixture error caused by a random
  nonexistent order id violating the develop FK; all other cases passed.
- Replaced that fixture with a real order/customer and added a public design-price fallback
  regression. Re-ran InventoryCatalogIntegrationTest, InventoryCatalogSecurityIntegrationTest,
  GuestCheckoutServiceTest, ModularityTest and ArchitectureTest: **51/51 passed**.
- Combined latest Surefire reports: 458 tests, zero errors/failures/skips. This is the full run
  plus the targeted rerun, not a second full-suite run.
- Fresh migrations and procurement upgrade test executed successfully. No application/local
  database was deleted or repaired. Tests used isolated Docker PostgreSQL containers.
- `verify.py`: 19 existing test-fixture dependency findings remain; not claimed clean.
- `git diff --check` clean; no unresolved merge paths. The merge and scope edits are staged,
  but **not committed/pushed**, so Git still reports a merge awaiting its commit.
- Canonical schema migration, DB QA on that future schema and FE UAT remain pending; these test
  results do not clear the merge gates above.
