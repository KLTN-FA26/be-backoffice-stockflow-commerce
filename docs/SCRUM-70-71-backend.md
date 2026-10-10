# SCRUM-70 / SCRUM-71 backend handoff

## Status (2026-10-09)

Canonical adapters and an append-only cutover migration are implemented. Tu approved the final
schema model in [review 6058462025](https://github.com/KLTN-FA26/be-backoffice-stockflow-commerce/pull/38#issuecomment-6058462025)
at reviewed head `a919c75ec47571ea0a42419537e460eabb14f7c1`. His
[9 October re-review](https://github.com/KLTN-FA26/be-backoffice-stockflow-commerce/pull/38#pullrequestreview-5462668849)
approved `9572c7a` after runtime projection/recovery checks. Upstream PIM writer integration and
frontend UAT remain outstanding. Demo-only sample
selling prices now complete the seed, with authenticated fresh/upgrade startup coverage.
The legacy product/gallery creation APIs have not completed their separate C1 migration.

The branch now includes develop `fe0daa7`, with merged PR #36 and #61's receiving/QC schema.
The conflict resolution retains stock move/adjustment, admin order listing, transfer orders and
canonical inventory/commerce behavior together. PR #36 was pushed first; merge order remains
#36 before #38; #36 is now merged upstream. No GitHub PR merge or production deployment is performed by this handoff.

## Canonical ownership

- Inventory control reads and writes **inventory.inventory_items**, including its own version,
  audit fields, UoM and tracking flags. No runtime policy reads/writes use `inventory.sku_policy`
  or threshold columns on `product.sku`.
- A canonical `product.variants` insert automatically creates one inventory item with no tracking
  flags and nullable thresholds. The same migration fills items for existing canonical variants.
  **Cutover debt accepted by Tu:** replace `tg_variant_inventory_item` with an inventory-owned
  listener for a variant-created event through the module API/contracts. The trigger remains for
  this cutover; no new event architecture or C1/C3 activation is included in this fix.
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
| Previously PUBLISHED demo products missing from catalog | Startup/retry rebuild canonical projections; real demo selling-price seed completes fresh and upgraded demo databases |
| Demo checkout | Demo-only sample prices: SOFA 12,500,000 VND / TABLE 8,000,000 VND; existing prices are preserved |

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

A DB on develop after both #36 and #61 upgrades normally with out-of-order disabled. A populated
clone check preserved three POs, five delivery logs and their attempt numbers while applying
the three original #38 versions, followed by the draft-rename correction. Fresh and populated
upgrades are also regression-tested.

An existing DB from **exactly remote #38 `3bfd605`** has already applied canonical `20261008000100`
but lacks the lower PO review version `20260930000500` and newly merged develop QC version
`20260929000250`. Default Flyway correctly refuses this
history. A disposable DB built from the actual archived release reproduced the failure and
validated the following operator recovery (not an application default):

1. Stop writers, back up and restore a clone. Verify the release's full migration history,
   filenames/checksums, absence of failed/unknown versions and canonical schema state.
2. Run the read-only preflight. With the new migration directory and clone datasource configured,
   inspect `flyway -outOfOrder=true -target=20261008000100 info`. **Only `20260929000250` and `20260930000500`** may be
   pending. A different pending set or script at version `20261008000100` needs its own plan.
3. On that clone, run `flyway -outOfOrder=true -target=20261008000100 migrate` once. Remove both
   overrides and run normal `flyway migrate` and `flyway validate`; test PO dates, attempts,
   role grants and inventory/commerce behavior. The probe validated all 76 migrations, including the normal final draft-rename correction.
4. Repeat only under the intended environment's operator change plan. No global out-of-order,
   automatic history rewrite, blind repair or database reset is introduced.

The earlier probe predates the QC migration; its 76-version result is historical evidence only.
The current `9572c7a` recovery is separately tested below. The equivalent settings use Flyway's
Java API; an installed CLI/VPS was not tested.
For old remote #36 `9fbb90f`, follow the distinct prerequisite recovery in
[the supplier runbook](SCRUM-115-118-backend.md#database-upgrade).
A DB that applied the unpublished **PO** script at `20261008000100` is a different history from
#38's canonical script at that number and must not use either procedure blindly.

**New develop #61 prerequisite:** a database from the published #38 `9572c7a` (or completed
#36 before #61) lacks `V20260929000250__receipt_qc_flow.sql`. Preserve that develop filename and
checksum; do not renumber it, rewrite history or enable out-of-order in application configuration.
On a backed-up disposable clone of exactly `9572c7a`, verify the complete history against the
archived manifest, and check that
`flyway -outOfOrder=true -target=20261008000200 info` reports **only `20260929000250`** pending.
Apply that bounded one-off migration, remove both overrides, then run normal migrate/validate
and API checks. The regression reconstructs all 79 published migration/demo files with their
normalized SHA-256 hashes and verifies policy, SEO/slug/publication and demo selling prices survive.
Fresh databases and develop after #61 need no override. Other release histories need their own
pending-set review; old #36 `9fbb90f` now also needs QC `20260929000250` alongside its two earlier
develop prerequisites. No real database or VPS is changed here.

Run `psql -X -v ON_ERROR_STOP=1 -f tools/sql/inventory-commerce-upgrade-preflight.sql` first — on a
database that is still before the legacy-tables removal (C1–C4); past it the script's old tables are
gone (`tools/sql/README.md`).
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

### Re-review 5462668849 and develop #61 integration (2026-10-09)

- Verified local HEAD and fetched PR head at `9572c7a`; no tracked edits or unfinished merge.
  No `AGENTS.md` exists in the checkout or its parent chain. Read all three issue comments,
  the approval review and the complete inline/thread collections (both empty).
- Merged develop `fe0daa7` without textual conflicts. Its receiving/QC migration, domain enums,
  schema documentation and SQL QA are retained byte-for-byte. All previously published #38 and
  develop migration filenames/content remain unchanged.
- Retry contention: bounded keyset scanning now checks listing/source revision, SEO, visibility
  and projected ACTIVE SKU completeness in a read-only transaction before taking a writer lock.
  Healthy products are skipped. Repairs still lock and re-read current canonical data; startup
  retains its complete scan. Missing-price rollback/retry and stale-event guards remain in place.
  The authenticated fresh/upgrade startup tests hold real product row locks on another connection
  while retry completes within five seconds without writes, then check lost entry/listing repair.
- Formatting: only import grouping/order and the added constructor parameter indentation changed
  in `StockOperationsServiceImpl`; no broad formatting pass.
- Removed `docs/PR-36-review.md` as requested, with a backup at
  `C:/Users/VO HOANG MINH/Documents/Codex/PR38-review-5462668849-backup/`. The supplier handoff's
  sole local reference now links to that document at immutable prerequisite commit `c2fa904`.
- The archived `9572c7a` migration regression proves normal validation rejects the lower QC
  prerequisite, verifies the exact one-file recovery set and validates normal operation afterward.
  The existing archived #36 test now includes the same develop QC prerequisite. Default Flyway
  out-of-order stays disabled. Updated the read-only preflight and release-specific runbook.
- Targeted startup/migration regressions: **10 tests**, zero failures/errors/skips; all **186 SQL QA
  checks** pass and the orphan report is clean. Log:
  `outputs/pr38-review-5462668849-targeted.log`. Static checks pass (947 Java files).
- Added post-merge regression coverage proving new `INBOUND` and `BLOCKED` stock contributes
  neither to storefront availability nor policy usable-on-hand evaluation.
- Local full `clean verify` exercised **602 tests**: 601 passed, with one error in the newly added
  QC fixture (duplicate receipt-layer key). Corrected the fixture to use distinct receipt times;
  reran its complete **32-test class** with `mvn -B -ntp -o verify
  -Dtest=InventoryCatalogIntegrationTest`: zero failures/errors/skips, JaCoCo and packaged jar.
  All other full-suite classes, including 18 architecture, 3 module and 9 catalog/security tests,
  passed. No whole-suite rerun was needed for that fixture-only correction. Logs:
  `outputs/pr38-review-5462668849-clean-verify.log` and
  `outputs/pr38-review-5462668849-fixture-verify.log`. GitHub CI must verify the pushed SHA.

### Review 6058462025 correction (local, not pushed)

- Baseline: local HEAD and fetched PR #38 head were both `a919c75`; fetched develop was `276567d`
  and already an ancestor. There were no tracked edits, unmerged paths or `MERGE_HEAD`.
- Before fix: a new PostgreSQL/Testcontainers security regression migrated a fresh database,
  inserted a valid canonical PUBLISHED fixture with approved gallery and an explicit test selling
  price, then requested detail. Detail failed **expected 200, actual 404**. Neither projection
  table had a row for the product. Log: `outputs/pr38-review-6058462025-before.log`.
- Fix: `CatalogProjectionListener` scans canonical publications on `ApplicationReadyEvent` and
  continues a bounded keyset scan during scheduled retry, including products with no listing.
  `CatalogProjectionService` locks the canonical product and creates/rebuilds its snapshot in one
  REQUIRES_NEW transaction. It checks active category/SKUs, approved gallery and effective selling
  prices. It never changes source publication, slug, SEO or pricing rules. Complete projections
  at the current revision are unchanged; missing SKU entries are repaired. Upserts reject another
  product owner or a newer entry revision instead of overwriting it. Failed work rolls back and is
  retried from current source after restart; no migration checksum changes are required.
- After fix: the same regression returned 200 for authenticated list/detail. Targeted validation
  passed 63 tests, followed by four recovery/startup cases. Coverage includes an actual populated
  pre-#38 database upgrade followed by application startup, real RSA JWT signature validation,
  unchanged reruns, concurrent initial rebuilds, partial projection loss, missing-price rollback
  and retry recovery, private DRAFT/APPROVED products, unapproved gallery, newer-revision refusal,
  and unpublish/republish with updated price/SEO and stable slug. The existing security test class
  stubs only JWT decoding; the separate startup test uses a real decoder and signing key.
- At `e622cb4`, `mvn -B -ntp -o clean verify`: **598 tests**, zero failures/errors/skips and packaged jar.
  The fresh-migration test also executes all four repository SQL QA scripts: **166 checks pass**;
  the orphan report is clean. Static checks pass (945 Java files); `git diff --check` passes and
  all previously applied migration files are unchanged. Full log:
  `outputs/pr38-review-6058462025-clean-verify.log`. This fix is local; GitHub CI has not run on it.
- **Demo seed completed after `e622cb4`:** `db/demo/R__demo_catalog_selling_prices.sql` supplies
  explicit sample prices (SOFA-3S-GREY 12,500,000 VND; TABLE-OAK-160 8,000,000 VND), marked
  `created_by='demo-seed'`. These are demo/test values, not business quotations or purchase costs.
  The default/production migration location excludes this file. It requires the exact canonical
  demo product/variant IDs, codes and seed creator. A repeatable migration works on both blank
  demo databases and already-migrated ones without old checksum edits or out-of-order overrides.
  Product row locks serialize with price edits; inserts never update existing rows. Existing
  SKU rules (including inactive ones), active general rules or cached SKU prices prevent adding
  competing sample prices; unique constraints also arbitrate duplicate attempts.
- `CatalogProjectionFreshStartupIntegrationTest` boots from a blank container; the upgrade variant
  starts from the old fully migrated demo release without the new repeatable seed. Both use the
  actual seed and **no manual price inserts**, then verify list contains both products and both
  details work without unpublish/republish. RSA JWTs cover the five roles in Tu's review;
  anonymous requests still return 401. Upgrade SEO/slug is preserved, and projection reruns,
  concurrent rebuilds and later unpublish/republish/price edits remain covered.
- Latest targeted run: **70 tests pass**, zero failures/errors/skips, including seed preservation
  and idempotency, missing-price rollback/retry for non-demo products, catalog security, upgrade
  migrations, all **166 SQL QA checks**, clean orphan report, architecture and module boundaries.
  Static checks pass (947 Java files), `git diff --check` passes, and existing versioned/demo
  migrations are unchanged. Log: `outputs/pr38-demo-seed-regression.log`. The earlier 598-test
  full run is retained; the full suite was not repeated for this demo-seed-only follow-up.
  Missing genuine selling prices still fail normal publication/projection validation; no default
  business price or relaxed validation was introduced. No production/VPS data was modified.
- Reduced formatting-only edits in StockOperationsServiceImpl, TransferOrderServiceImpl,
  InventoryServiceImpl and OrderServiceImpl against develop. Executable tokens were preserved;
  SKU-before-row locking, receipt-layer compatibility and ambiguous-layer 409s remain. Tu's future
  `stockItemId` selector is not implemented here. The four-file diff against develop shrank from
  1,310 to 246 changed lines (additions plus deletions).
- Removed the branch-only progress report after backup outside the repo at
  `C:/Users/VO HOANG MINH/Documents/Codex/PR38-review-6058462025-backup/`.
  `docs/PR-36-review.md` was initially retained as an inherited prerequisite record. The
  9 October follow-up removes it as requested after archiving it outside the repo, and changes
  the supplier handoff's reference to the immutable GitHub archive. Merge order remains #36 -> #38.

### Earlier integration checks

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

Upstream PIM writer migration, canonical FE identifiers and frontend UAT remain
the coordination gates above. Demo sample prices no longer require a business-price decision.
