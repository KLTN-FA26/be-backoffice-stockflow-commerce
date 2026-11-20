# Receiving: remove what kltn-docs 03 no longer has — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the receiving-flow leftovers that kltn-docs 03 / 01 no longer describe (purchase receipts, receipt-origin
lots, the default purchase unit, constant response fields), and enforce 01 BR-03 "one carton spec per SKU" so the
receiving code can stop choosing between carton units.

**Architecture:** Four additive Flyway migrations (one per group, each with its leftover-row cleanup and its adversarial
SQL), the matching Java removals in `procurement`, `inventory` and `product`, and one GitHub issue on the FE repository
for the response/request fields the FE still reads. The response fields the FE parses as required are removed last,
only after the FE change is merged.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Modulith 1.4, PostgreSQL 16 (Flyway), JUnit 5 + Testcontainers.

**Spec:** no separate spec file — the findings are the comparison of 2026-11-20 between this code and kltn-docs
`origin/develop` (`docs/warehouse/03-receipt`, `docs/warehouse/01-product-creation` BR-03). Read 03 §1-§6 and 01 BR-03
before starting. Summary of what each task removes and why:

| Group | What | Why it is redundant |
|---|---|---|
| A2 | `PURCHASE` receipt type, the historical-purchase branches, `CONFIRMED` in `ck_goods_receipts_status` | Purchasing is gone (03 §1); databases are rebuilt on deploy (QD-1) and no seed has a PURCHASE receipt; 03 §5 "xác nhận là một bước, không phải trạng thái lưu lại" |
| A3 | `stock_lot` origin `GOODS_RECEIPT`, its columns, index and branch | `ReceiptOrigin` has only `ofProductionOrder`; 03 BR-03 "lô = mã Đơn sản xuất" |
| A4 | `uom_conversions.default_purchase` | The purchase unit belonged to purchase orders |
| A5 | `LineView.lotTracked` (always true), `warnings` (always empty), `looseQuantity` (always 0), `quantityForPutaway` (= `quantity`) | 03 BR-12 (no loose stock), BR-07 (no QC), 01 BR-17 (every SKU lot-tracked) |
| B | several carton units per SKU in receiving | 01 BR-03 "Mỗi SKU riêng chỉ có một quy cách thùng" (chốt 2026-11-06) |

**Out of scope (do not touch):** `procurement.goods_receipt_photos` and its service/controllers (team plan 4D-3 uses
them as return-receipt evidence; waiting on the owner's decision), `ArchivedPurchasingFileReferences` /
`platform.legacy_archive` (the orphaned-file sweep's protection), `ordering.production_order_receipt` (deliberate
mirror: `order` cannot read `procurement`), `default_shipping` (redundant once B lands — a follow-up, not this plan),
the return receipt's `counted_qty` / loose lines (code is ahead of the docs there; docs need the update, not the code).

## Global Constraints

- **Never `git commit` / `git push` without the repository owner's go-ahead** (CLAUDE.md §7). Each task ends with a
  "stage" step; the owner commits.
- Work in a **git worktree on a new branch `chore/receiving-redundancy-removal` cut from `origin/develop`**. The main
  checkout carries the owner's uncommitted work: do not touch it.
- Migrations above every one on `origin/develop` (latest `V20261212000100`) and clear of the in-flight
  `V20261210000100/200` of `feature/coordinator-owns-production`. Use exactly:
  `V20261213000100__procurement_purchase_receipts_removed.sql`,
  `V20261213000200__inventory_stock_lot_receipt_origin_removed.sql`,
  `V20261213000300__inventory_uom_default_purchase_removed.sql`,
  `V20261213000400__inventory_one_carton_unit_per_item.sql`.
  Re-check the latest version on `origin/develop` before each; rename upward if someone merged above.
- Never edit an existing migration or demo migration. A SQL function a migration changes is rewritten from its latest
  body (`git grep "FUNCTION <name>" origin/develop -- src/main/resources/db/migration`; today all three named below
  live only in the baseline).
- Historical enum constants stay in Java (`GoodsReceiptType.PURCHASE`, `GoodsReceiptStatus.PENDING_APPROVAL` and
  `CONFIRMED`, `StockLotJpaEntity.Origin.GOODS_RECEIPT`): rewrite their javadoc to "historical, no row holds it".
- Every drop or CHECK change gets adversarial SQL in **`tools/db/qa/96_receiving_redundancy_removal.sql`** (one file,
  one section per task, same `pg_temp.expect_fail_like` helpers as `61_purchasing_removal.sql`), run by
  `RepositorySqlQaTest`.
- When behaviour changes, change the javadoc that justified the old behaviour (CLAUDE.md §3).
- Verification for every task: `PYTHONUTF8=1 python -X utf8 tools/verify.py`, then the task's tests, then
  `mvn test -Dtest='ModularityTest,ArchitectureTest,MessageBundleTest,RepositorySqlQaTest'`.

## Review Focus

1. **An FE build from before the FE issue still sends `defaultPurchase` on a carton create/update.** Expected: accepted,
   ignored (Spring Boot leaves `FAIL_ON_UNKNOWN_PROPERTIES` off; no override in `src/main`). Pinned in Task 4.
2. **A database where an item already has two active carton units** (fixtures or an old seed). Expected: migration
   `…000400` retires the extras deterministically instead of failing. Pinned in Task 5 (QA section + upgrade test).
3. **A DRAFT receipt counted in a carton unit that was retired before the recount.** Expected: the recount refuses with
   `RECEIPT_LINE_HAS_PACKAGES` naming the cartons to leave out (unchanged rule, new code path). Pinned in Task 5.
4. **The FE receipt screen after `looseQuantity` / `quantityForPutaway` leave the response.** Expected: nothing breaks,
   because Task 6 runs only after the FE change is merged. Pinned by the gate in Task 6.
5. **Lot trace of a lot that was `GOODS_RECEIPT` before Task 2.** Expected: it is `UNTRACED` afterwards and still traces
   its stock and ledger. Pinned in Task 3 (`StockLotReceiptOriginMigrationUpgradeTest`).

---

### Task 0: Worktree and green baseline

- [ ] **Step 1:** `git fetch origin && git worktree add ../be-receiving-cleanup -b chore/receiving-redundancy-removal origin/develop`
- [ ] **Step 2:** In the worktree: `PYTHONUTF8=1 python -X utf8 tools/verify.py` → no findings.
- [ ] **Step 3:** `mvn test -Dtest='ProductionReceiptIntegrationTest,GoodsReceiptPackageHttpTest,LegacyPurchaseReceiptIntegrationTest,GoodsReceiptTest,UomConversionServiceImplTest,InventoryItemMasterDataIntegrationTest,RepositorySqlQaTest'` → green. A red test here is not this plan's; report it before going on.
- [ ] **Step 4:** On a freshly migrated database with the demo seed (`docker compose up -d`, run the app once or
  `tools/db/qa/run.sh`'s setup), record the counts the migrations will clean:

```sql
SELECT count(*) FROM procurement.goods_receipts WHERE receipt_type = 'PURCHASE' OR status = 'CONFIRMED';      -- expect 0
SELECT count(*) FROM inventory.stock_lot WHERE origin_type = 'GOODS_RECEIPT';                                -- expect 0
SELECT inventory_item_id, count(*) FROM inventory.uom_conversions
 WHERE NOT is_base AND active GROUP BY 1 HAVING count(*) > 1;                                                -- expect no rows
```

Write the three answers into the PR description; a non-zero answer is what the migration's cleanup has to handle.

### Task 1: The FE issue

**Files:** none in this repository.

- [ ] **Step 1:** Ask the repository owner before creating it (an issue is published content). Then:

```bash
gh issue create --repo KLTN-FA26/fe-backoffice-stockflow-commerce \
  --title "Receiving: stop reading fields the BE removes (looseQuantity, quantityForPutaway, lotTracked, warnings, defaultPurchase)" \
  --body-file <scratchpad>/fe-issue.md
```

`fe-issue.md` (Vietnamese, the FE team's language) says:
- `src/features/receipt/schemas.ts`: drop `looseQuantity`, `quantityForPutaway`, `lotTracked`, `warnings` from the
  receipt line / receipt schemas (or make them `.optional()` first), and from `__fixtures__/receipt.ts`,
  `schemas.test.ts`, `lib/api/mock-routes-goods-receipts-store.ts`, `lib/api/mock-receipt-cartons.ts`.
- `src/features/product/carton-api.ts`, `src/lib/domain/carton.ts`, `lib/api/mock-routes-uom-conversions.ts`: stop
  sending and reading `defaultPurchase`.
- A SKU has one carton spec (kltn-docs 01 BR-03): declaring a second active carton unit is refused with
  `UOM_CARTON_ALREADY_DECLARED` (409) — retire the current one first.
- BE removes `looseQuantity` / `quantityForPutaway` only after this issue is merged.

- [ ] **Step 2:** Put the issue URL in the PR description and in Task 6's gate.

### Task 2: Purchase receipts and the CONFIRMED status (A2)

**Files:**
- Create: `src/main/resources/db/migration/V20261213000100__procurement_purchase_receipts_removed.sql`
- Modify: `procurement/internal/domain/GoodsReceipt.java`, `GoodsReceiptType.java`, `GoodsReceiptStatus.java`, `ReceiptLine.java`
- Modify: `procurement/internal/service/GoodsReceiptServiceImpl.java` (`replaceLines`, `confirm`, `cancel`, `get`, `view`)
- Modify: `procurement/internal/entity/GoodsReceiptJpaEntity.java` / `GoodsReceiptLineJpaEntity.java` if they map `production_order_line_id` as nullable
- Delete: `src/test/java/com/stockflow/procurement/LegacyPurchaseReceiptIntegrationTest.java`
- Modify: `src/test/java/com/stockflow/procurement/internal/domain/GoodsReceiptTest.java`
- Modify: `tools/db/qa/22_production_receipts.sql` (PR4), `42_alerts_and_expiry_removed.sql` (the PURCHASE header block), `52_return_receipts.sql`, `53_returns_through_receipts.sql`, `61_purchasing_removal.sql` (R1)
- Create: `tools/db/qa/96_receiving_redundancy_removal.sql` (section A2)

**Interfaces:**
- Produces: `GoodsReceipt.productionDraft(...)` unchanged; `GoodsReceipt.againstProductionOrder()`, `type()` and
  `requireProductionOrder(String)` are deleted — a goods receipt is always against a production order.

- [ ] **Step 1: Write the QA section (it fails before the migration).** In `96_…sql`, section A2:
  - `expect_fail_like('A2.1 a PURCHASE receipt', 'ck_goods_receipts_type', …INSERT … receipt_type 'PURCHASE'…)`
  - `expect_fail_like('A2.2 a receipt line with no production order line', 'production_order_line_id', …INSERT …production_order_line_id NULL…)`
  - `expect_fail_like('A2.3 a receipt stored CONFIRMED', 'ck_goods_receipts_status', …UPDATE … SET status = 'CONFIRMED', confirmed_at = now(), confirmed_by = …)`
  - happy path: the demo seed's production receipts still insert, count and confirm (reuse the PR1 fixture of `22_…`).
- [ ] **Step 2:** `mvn test -Dtest=RepositorySqlQaTest` → FAIL on A2.2 and A2.3 (A2.1 is refused today by the trigger, with another message).
- [ ] **Step 3: The migration.** In order:
  1. Delete leftover rows (none on a rebuilt database): the lines, then the receipts, `WHERE receipt_type = 'PURCHASE'`; any `procurement.goods_receipt_photos` of them first. If a stock package still points at such a line the FK refuses and the migration stops — that cannot happen on a database rebuilt from the baseline, and is the right failure if it does.
  2. `ck_goods_receipts_type` → `('PRODUCTION','RETURN')`; `ck_goods_receipts_status` → without `CONFIRMED`.
  3. `ALTER TABLE procurement.goods_receipt_lines ALTER COLUMN production_order_line_id SET NOT NULL`.
  4. `CREATE OR REPLACE FUNCTION procurement.check_goods_receipt()` from the baseline body without the `TG_OP = 'INSERT' AND NEW.receipt_type = 'PURCHASE'` branch (the CHECK now says it).
  5. `COMMENT ON COLUMN procurement.goods_receipts.receipt_type` without PURCHASE.
- [ ] **Step 4: Java.** Delete the purchase branches: `replaceLines` and `confirm` call the production path directly;
  `cancel` always goes through `cancelProduction`; `get` always finds the production order; `GoodsReceipt.cancel`
  accepts DRAFT only; the constructor check on `PENDING_APPROVAL` and `requireProductionOrder` go; every
  "null on a historical purchase receipt" javadoc and the `ReceiptView`/`Row` nullability comments are rewritten.
  Keep the enum constants with "historical" javadoc.
- [ ] **Step 5: Tests.** Delete `LegacyPurchaseReceiptIntegrationTest`; in `GoodsReceiptTest` drop the purchase cases
  and add `cancelRefusesAnythingButDraft` (a `PENDING_APPROVAL` receipt can no longer be built: assert the
  constructor or `cancel` refuses with `INVALID_RECEIPT_TRANSITION`). Update the five QA files so their PURCHASE
  negatives expect `ck_goods_receipts_type`; delete `42_…`'s PURCHASE header block (the CHECK now refuses it even with
  triggers off).
- [ ] **Step 6:** `mvn test -Dtest='RepositorySqlQaTest,GoodsReceiptTest,ProductionReceiptIntegrationTest,ReturnReceiptIntegrationTest,GoodsReceiptPackageHttpTest'` → PASS; `verify.py` clean.
- [ ] **Step 7:** Stage: `git add -A src/main/resources/db/migration src/main/java/com/stockflow/procurement src/test/java/com/stockflow/procurement tools/db/qa`

### Task 3: Lot origin GOODS_RECEIPT (A3)

**Files:**
- Create: `src/main/resources/db/migration/V20261213000200__inventory_stock_lot_receipt_origin_removed.sql`
- Modify: `inventory/internal/entity/StockLotJpaEntity.java`, `inventory/internal/repository/StockLotsAdapter.java`, `StockLotJpaRepository.java`, `inventory/internal/domain/StockLots.java`
- Modify: `inventory/internal/domain/LotTrace.java`, `inventory/internal/repository/LotTraceSearchAdapter.java`, `inventory/internal/controller/dto/LotTraceResponse.java`
- Modify: `inventory/api/ReceiptOrigin.java` (javadoc), `inventory/internal/service/StockOperationsServiceImpl.java` (`receive`)
- Modify: `tools/db/qa/96_…sql` (section A3), and `15_sku_reservation_and_lots.sql`, `22_…`, `42_…`, `52_…`, `99_demo_history.sql` wherever they insert a `GOODS_RECEIPT`-origin lot or read the dropped columns

**Interfaces:**
- Produces: `StockLots.openForReceipt(ReceiptLot)` with `record ReceiptLot(Sku sku, Instant receivedAt, UUID warehouseId, UUID productionOrderId, String productionOrderCode)` — all non-null; it finds or opens the lot of (production order, SKU).
- Produces: `LotTrace.Summary` without `goodsReceiptId` / `goodsReceiptNumber`; `LotTraceResponse` the same (the FE does not read them: checked 2026-11-20).

- [ ] **Step 1: QA section A3:** a `GOODS_RECEIPT` lot is refused (`ck_stock_lot_origin`); a `PRODUCTION_ORDER` lot
  without `production_order_id` is still refused (`ck_stock_lot_production_order`); an `UNTRACED` lot still inserts;
  and (Review Focus 5) a lot inserted as `GOODS_RECEIPT` *before* the migration is impossible to stage in a QA file
  that runs after it — cover that in a lean `StockLotReceiptOriginMigrationUpgradeTest` instead (template:
  `DeliveryFailureCasesMigrationUpgradeTest`): migrate to `V20261213000100`, insert a GOODS_RECEIPT lot with one stock
  row in a carton, migrate on, assert the lot is `UNTRACED` and `LotTraceSearchAdapter` still returns its row.
- [ ] **Step 2:** Run both → FAIL.
- [ ] **Step 3: The migration.** `UPDATE inventory.stock_lot SET origin_type = 'UNTRACED' … WHERE origin_type = 'GOODS_RECEIPT'`
  (clearing what `ck_stock_lot_untraced` forbids); drop `ck_stock_lot_receipt`, `ck_stock_lot_production_order`,
  `uk_stock_lot_receipt`; drop columns `goods_receipt_id`, `goods_receipt_number`; re-add
  `ck_stock_lot_production_order` without its `goods_receipt_*` conjuncts; `ck_stock_lot_origin` → `('PRODUCTION_ORDER','UNTRACED')`;
  `ck_stock_lot_untraced` → re-add as whatever remains meaningful or drop it if it said only "no receipt id".
- [ ] **Step 4: Java.** `StockLotsAdapter.openForReceipt` keeps only the production-order branch; delete
  `findByGoodsReceiptIdAndSku` and the GOODS_RECEIPT constructor of `StockLotJpaEntity`; `receive` builds the
  narrower `ReceiptLot` (a receipt with no origin opens no lot, as today when `origin() == null`); remove the two
  fields from the lot trace SQL, `LotTrace.Summary` and `LotTraceResponse`.
- [ ] **Step 5:** `mvn test -Dtest='RepositorySqlQaTest,StockLotReceiptOriginMigrationUpgradeTest,ProductionReceiptIntegrationTest,ReturnReceiptIntegrationTest,*LotTrace*'` → PASS.
- [ ] **Step 6:** Stage the migration, the inventory sources, the test and the QA files.

### Task 4: Default purchase unit and the response fields the FE does not read (A4, A5 part 1)

**Files:**
- Create: `src/main/resources/db/migration/V20261213000300__inventory_uom_default_purchase_removed.sql`
- Modify: `inventory/api/UomConversion.java`, `inventory/api/UomConversionDraft.java`, `inventory/internal/domain/UomConversionRules.java`, `inventory/internal/repository/UomConversionRepository.java`, `inventory/internal/service/UomConversionServiceImpl.java`, `inventory/internal/service/ItemSpecificationsImpl.java`, `product/internal/service/SkuUnitService.java` and the product controller DTOs that carry `defaultPurchase`
- Modify: `procurement/internal/service/GoodsReceipts.java` (`UnitView` without `defaultPurchase`; `LineView` without `lotTracked`, `warnings`; `ReceiptView` without `warnings`), `GoodsReceiptServiceImpl.view`, `procurement/internal/controller/dto/GoodsReceiptResponse.java`, `GoodsReceiptWebMapper.java`
- Test: every file listed by `grep -rln 'defaultPurchase\|default_purchase' src/test tools/db/qa` (19 today), QA section A4

**Interfaces:**
- Produces: `UomConversion` and `UomConversionDraft` records without `defaultPurchase`; every constructor call site updated. Request DTOs drop the field; an FE still sending it is ignored (Review Focus 1).

- [ ] **Step 1: Failing tests.** QA A4: `SELECT default_purchase FROM inventory.uom_conversions` fails (column gone); inserting an inventory item still creates its base unit (`create_base_uom`). `UomConversionHttpIntegrationTest.defaultPurchaseInTheBodyIsIgnored`: POST a carton with `"defaultPurchase": true` → 201, response has no `defaultPurchase`. `GoodsReceiptPackageHttpTest`: assert `$.data.lines[0].lotTracked` and `$.data.warnings` do not exist.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: The migration.** `CREATE OR REPLACE FUNCTION inventory.create_base_uom()` from the baseline body without `default_purchase`; drop `uk_uom_conversions_default_purchase`, `ck_uom_conversions_default_purchase`, then the column.
- [ ] **Step 4: Java.** Remove the field end to end; delete `clearDefaultPurchase`, the default-purchase half of `defaultToBaseIfNone` (keep it if it also restores default shipping), `retiredIsNotDefault`, the base-unit default-purchase rule in `validateBase`, and their javadoc lines (the class comment of `UomConversionServiceImpl` names purchase order lines: rewrite it). Remove `lotTracked` and both `warnings`.
- [ ] **Step 5:** Update the 19 test files mechanically (constructor arity, fixture INSERTs, `packing(...)` helpers).
- [ ] **Step 6:** `mvn test` (full) → PASS.
- [ ] **Step 7:** Stage.

### Task 5: One carton unit per SKU (B)

**Files:**
- Create: `src/main/resources/db/migration/V20261213000400__inventory_one_carton_unit_per_item.sql`
- Modify: `common/error/ErrorCode.java` (+ `UOM_CARTON_ALREADY_DECLARED("This SKU already has a carton unit; retire it before declaring another", 409)`), `src/main/resources/i18n/messages.properties`, `messages_vi.properties`
- Modify: `inventory/internal/service/UomConversionServiceImpl.java` (`add`, `change`), `inventory/internal/repository/UomConversionRepository.java` (`activeCartonOf(UUID itemId, UUID except)`)
- Modify: `procurement/internal/service/GoodsReceiptServiceImpl.java` — replace `productionCartonUnit` with `cartonUnit`; `GoodsReceipts.NewLine` javadoc
- Test: `UomConversionServiceImplTest`, `InventoryItemMasterDataIntegrationTest`, `ProductionReceiptIntegrationTest`, `CartonUnitPerItemMigrationUpgradeTest` (new), QA section B

**Interfaces:**
- Produces: `private static UomConversion cartonUnit(LineErrors errors, int index, NewLine in, ReceivableProductionOrder.Line line, List<UomConversion> itemUnits, List<StockPackageView> cartonsBefore)` — the SKU's one active non-base unit; refusals: none active → `RECEIPT_LINE_UNIT_INVALID` "has no active carton (01 BR-11)"; factor ≠ `line.unitsPerCarton()` → `RECEIPT_LINE_UNIT_INVALID`; `in.unitOfMeasure()` given and ≠ its code → `RECEIPT_LINE_UNIT_INVALID`; cartons named again that were labelled in another unit → `RECEIPT_LINE_HAS_PACKAGES` (unchanged message).
- Produces: `ErrorCode.UOM_CARTON_ALREADY_DECLARED` (409).

- [ ] **Step 1: Failing tests.**
  - `UomConversionServiceImplTest.secondActiveCartonIsRefused`: `add` of a second active packing unit → `UOM_CARTON_ALREADY_DECLARED`; `add` of an *inactive* one is allowed; `change` that re-activates a retired unit while another is active → `UOM_CARTON_ALREADY_DECLARED`.
  - `InventoryItemMasterDataIntegrationTest.replacingTheCartonRetiresFirst`: retire CARTON (in use), then add CARTON2 → succeeds.
  - `ProductionReceiptIntegrationTest.recountAfterTheCartonUnitWasRetired` (Review Focus 3): count a draft in CARTON, retire CARTON and declare CARTON2 of the same factor, recount naming the same LPNs → `RECEIPT_LINE_HAS_PACKAGES`; recount naming no old LPN → the old cartons VOID, new ones labelled in CARTON2.
  - QA section B: two active non-base units for one item → refused by `uk_uom_conversions_one_carton`; a retired one beside an active one → accepted.
  - `CartonUnitPerItemMigrationUpgradeTest` (Review Focus 2): migrate to `V20261213000300`, give one item two active cartons (one `default_shipping`), migrate on, assert the default-shipping one is active and the other retired.
- [ ] **Step 2:** Run them → FAIL.
- [ ] **Step 3: The migration.** Retire extras first, keeping per item the `default_shipping` one, else the most recently created: `UPDATE inventory.uom_conversions SET active = false, default_shipping = false WHERE …`; then `CREATE UNIQUE INDEX uk_uom_conversions_one_carton ON inventory.uom_conversions (inventory_item_id) WHERE NOT is_base AND active;` with a `COMMENT ON INDEX` citing 01 BR-03.
- [ ] **Step 4: Service.** `add`/`change` ask `activeCartonOf(itemId, id)` under the SKU lock (already taken) before writing, so the index never surfaces as a bare 409. Receiving: `cartonUnit` replaces `productionCartonUnit` (the active/default-shipping/kept-unit ladder goes); update the javadoc of `replaceLines` and `NewLine.unitOfMeasure` ("optional; when given it must be the SKU's carton").
- [ ] **Step 5:** Fix any fixture in `src/test/java/com/stockflow/support/` that declares two active cartons for one SKU (run the full `mvn test` once; failures with `uk_uom_conversions_one_carton` point at them).
- [ ] **Step 6:** `mvn test` (full) → PASS; `MessageBundleTest` covers the new code in both bundles.
- [ ] **Step 7:** Stage.

### Task 6: `looseQuantity` and `quantityForPutaway` (A5 part 2) — **gated**

**Gate:** start only when the FE issue of Task 1 is merged into the FE `develop`. Until then leave the fields in.

**Files:** `procurement/internal/service/GoodsReceipts.java` (`LineView`), `GoodsReceiptServiceImpl.view`, `GoodsReceiptResponse.java`, `GoodsReceiptWebMapper.java`, `countedIntoCartons` (delete if unused), `GoodsReceiptPackageHttpTest`, `ProductionReceiptIntegrationTest.countedInWholeCartons`.

- [ ] **Step 1:** Change `GoodsReceiptPackageHttpTest` line 87 to `jsonPath("$.data.lines[0].looseQuantity").doesNotExist()` and the same for `quantityForPutaway`; in `countedInWholeCartons` replace the `looseQuantity` assertion with "the cartons' `receivedQty` sum to the line's `quantity`".
- [ ] **Step 2:** Run → FAIL. **Step 3:** remove the two fields. **Step 4:** run → PASS. **Step 5:** Stage.

### Task 7: Regenerate the schema doc and close

- [ ] **Step 1:** `python tools/db/gen_dbml.py` against the migrated database → `docs/business-design/db-design/schema.dbml` changes only where Tasks 2-6 did.
- [ ] **Step 2:** `mvn verify` → PASS; `verify.py` clean.
- [ ] **Step 3:** PR description: the Task 0 counts, the FE issue link, the out-of-scope list above, and that Task 6 is pending if the gate is not met. Hand to the owner for commit and PR.
