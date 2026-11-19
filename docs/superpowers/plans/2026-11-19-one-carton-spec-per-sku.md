# One carton spec per SKU Implementation Plan (part 2 of 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a SKU's carton spec five columns of `inventory.inventory_items`, read from one place by every flow, with the count locked once the SKU is used, and drop the multi-unit model (`uom_conversions`, `variant_sales_units`, kits, partial cartons).

**Architecture:** Expand → move readers → contract. Task 1 adds the columns, fills them, and keeps them in step with `uom_conversions` through a transitional trigger, so every later task can switch one area to the columns and stay green. Each area task drops the `uom_conversion_id` column it no longer needs in its own migration. Task 7 drops the tables and the bridge.

**Tech Stack:** Spring Boot 3.5 / Spring Modulith, Java 21, PostgreSQL 16 + Flyway, JDBC repositories in `inventory`, JUnit 5 + Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-19-one-carton-spec-per-sku-design.md`. Prerequisite: part 1, `docs/superpowers/plans/2026-11-19-legacy-demo-skus-removal.md` (no furniture, no factor-1 carton left in the demo data).

## Global Constraints

- Start after part 1 and after PR #378 (`feature/production-order-slip-layout`) is merged into `develop`; rebase this branch on `develop` first.
- **Never `git commit` or `git push` without the repository owner's go-ahead** (CLAUDE.md). Each "Commit" step: show the diff summary, ask, then commit.
- Migrations `V20261206000200` … `V20261206000600`, one per task, in task order; renumber before merging if anything newer landed. Never edit a merged one.
- Spec values: `units_per_carton > 1`; carton columns all null or all set; size `> 0`; weight `> 0`; the count locked for ever once used; size and weight editable when no carton would be stranded.
- "Used" = a row of the SKU in `inventory.stock_package`, `procurement.goods_receipt_lines`, `ordering.quote_line`, `ordering.order_line`, `ordering.production_order_line` or `catalog.customer_price`, in any status.
- Every drop or CHECK change gets adversarial SQL in a new `tools/db/qa/` file; data moved by a migration gets a `*MigrationUpgradeTest`.
- SQL functions mentioning a dropped object are rewritten in the same migration.
- `ModularityTest` after any change to `api` packages; `tools/verify.py` before every commit.
- New error codes go into `ErrorCode` and both i18n bundles (`MessageBundleTest`).
- A task that breaks a `support/` fixture fixes it in the same task.
- The front end follows in its own repository; the PR description lists the API changes of the spec's "API" table for the FE owner.

## Review Focus

1. **A first quote line racing a count change** on the same SKU: one of them must fail; never a quote at the old count on a SKU whose count changed. → Task 2, `countChangeAndFirstUseDoNotInterleave`.
2. **A size correction that would strand a carton** in its bin: refused with `CARTONS_WOULD_NOT_FIT`, nothing changed (columns, change log). → Task 2.
3. **A SKU without a carton spec** reaching a quote, a production order, a receipt or the load estimate: each refuses with `CARTON_SPEC_MISSING` (estimate: its existing 422), never a guessed carton. → Tasks 3, 4, 5.
4. **Editing the template after a SKU was generated** leaves the SKU's carton as copied. → Task 6.
5. **The migration over an ambiguous item** (two packing units > 1, none default-shipping) or a carton unit without size or weight fails naming the SKU. → Task 1 upgrade test.

---

### Task 1: Carton columns on the item, filled and kept in step

**Files:**
- Create: `src/main/resources/db/migration/V20261206000200__inventory_carton_spec_columns.sql`, `tools/db/qa/100_carton_spec_columns.sql`, `src/test/java/com/stockflow/CartonSpecColumnsMigrationUpgradeTest.java`
- Modify: `inventory/api/CartonSpec.java`, `inventory/api/InventoryControlService.java`, `inventory/api/ItemLogistics.java`, `inventory/internal/repository/InventoryPolicyRepository.java`, `inventory/internal/service/InventoryControlServiceImpl.java`, `inventory/internal/domain/ItemLogisticsRules.java`, `inventory/internal/domain/PackagingRules.java` (level 3 removed), `inventory/internal/controller/dto/UpdateItemLogisticsRequest.java`, `inventory/internal/controller/dto/InventoryItemResponse.java`, `inventory/internal/controller/InventoryItemWebMapper.java`, `inventory/internal/service/ItemSpecificationsImpl.java`, every caller of `CartonSpec.measured()` / `unitOfMeasure()` (`fulfillment/internal/service/CartonMeasures.java`, `CartonCheckService.java`)
- Test: `CartonSpecColumnsMigrationUpgradeTest`, `inventory/InventoryControlServiceIntegrationTest` (new), `PackagingRulesTest` (level-3 cases deleted), `InventoryItemMasterDataIntegrationTest`

(Main paths are under `src/main/java/com/stockflow/`, tests under `src/test/java/com/stockflow/`.)

**Interfaces:**
- Produces:
  - `public record CartonSpec(String sku, int unitsPerCarton, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm, BigDecimal weightKg, boolean rotatable)` with `BigDecimal volumeM3()`; `unitOfMeasure` and `measured()` removed (a spec is whole or absent).
  - `InventoryControlService`: `Optional<CartonSpec> cartonSpec(Sku sku)` (unchanged name) and new `Map<String, CartonSpec> cartonSpecs(Collection<Sku> skus)` (absent key = no spec).
  - `ItemLogistics` without `packageWeightKg`, `packageLengthCm`, `packageWidthCm`, `packageHeightCm`, `packageCount`, `packSize`.
  - `InventoryItemResponse.cartonSpec` (`CartonSpecResponse` or null) instead of the `package*` fields.
  - DB: columns `units_per_carton`, `carton_length_cm`, `carton_width_cm`, `carton_height_cm`, `carton_weight_kg`; CHECKs `ck_inventory_items_units_per_carton` (`> 1`), `ck_inventory_items_carton` (all null or all set, each `> 0`); transitional trigger `tg_uom_conversions_mirror_carton` (AFTER INSERT/UPDATE/DELETE on `inventory.uom_conversions`): recomputes the item's five columns from its carton by the `cartonOf` rule, all null when there is none or it lacks size or weight. Removed in Task 7.

- [ ] **Step 1: Write the failing upgrade test** — `CartonSpecColumnsMigrationUpgradeTest` (shape of `WarehouseCapacityMigrationUpgradeTest`: migrate to `V20261206000100`, insert rows, migrate on): `oneCartonUnitFillsTheColumns` (THUNG ×1000, 60×40×50, 8.6 → columns equal), `defaultShippingBreaksATie`, `noCartonUnitLeavesThemNull`, `ambiguousUnitsFailNamingTheSku` (message contains the SKU), `unmeasuredCartonFailsNamingTheSku`, `factorOneUnitIsNeverTaken` (only EACH + a factor-1 unit, no cartons → null), `itemHoldingCartonsWithoutASpecFails`.
- [ ] **Step 2: Run it** — `mvn test -Dtest=CartonSpecColumnsMigrationUpgradeTest` → FAIL (no migration).
- [ ] **Step 3: Write the migration** — add `units_per_carton`; rename `package_length_cm`→`carton_length_cm`, `package_width_cm`→`carton_width_cm`, `package_height_cm`→`carton_height_cm`, `package_weight_kg`→`carton_weight_kg` (and their CHECKs); drop `package_count`, `pack_size`; overwrite all five from the chosen unit (null when none); the failing checks above with `RAISE EXCEPTION '…%', sku`; add the CHECKs and the mirror trigger.
- [ ] **Step 4: Switch the reads** — `InventoryPolicyRepository.findCartonSpecs` selects the item columns (`where units_per_carton is not null`); `cartonSpecs` delegates to it; drop the package fields through `ItemLogistics`, the request, response, mapper, rules and `ItemSpecificationsImpl.copy` (which keeps copying units until Task 6); `PackagingRules` loses the item-box level and `kitParts` stays 1.
- [ ] **Step 5: Write `InventoryControlServiceIntegrationTest`** — `cartonSpecComesFromTheItemColumns`, `cartonSpecsReadsManyInOneCall`, `itemWithoutSpecIsAbsent`; and QA `100_carton_spec_columns.sql`: `units_per_carton = 1` refused, three of five carton columns refused, a zero size refused.
- [ ] **Step 6: Run** — `mvn test -Dtest='CartonSpecColumnsMigrationUpgradeTest,InventoryControlServiceIntegrationTest,PackagingRulesTest,InventoryItemMasterDataIntegrationTest,CartonCheckStationIntegrationTest,RepositorySqlQaTest,ModularityTest'` → PASS.
- [ ] **Step 7: Commit** (owner's go-ahead) — `feat(inventory): a SKU's carton spec as columns of its item`

### Task 2: Edit the carton spec, the count locked

**Files:**
- Create: `src/main/resources/db/migration/V20261206000300__inventory_carton_spec_lock.sql`, `inventory/api/CartonSpecDraft.java`, `inventory/internal/service/CartonSpecServiceImpl.java`, `inventory/internal/controller/dto/CartonSpecRequest.java`, `CartonSpecResponse.java` (if not made in Task 1), `tools/db/qa/101_carton_spec_lock.sql`
- Modify: `inventory/api/CartonSpecService.java` (new), `inventory/internal/controller/InventoryItemController.java`, `inventory/internal/service/BinStranding.java`, `common/error/ErrorCode.java`, `i18n/messages*.properties`, `product/internal/...` variant response mapper (read-only `cartonSpec`), `support/PermissionMatrixTest.java`
- Test: `inventory/CartonSpecIntegrationTest.java` (new), `inventory/CartonSpecHttpTest.java` (new)

**Interfaces:**
- Consumes: Task 1 `CartonSpec`, `cartonSpec(Sku)`.
- Produces:
  - `public record CartonSpecDraft(int unitsPerCarton, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm, BigDecimal weightKg)`
  - `CartonSpec changeCartonSpec(UUID inventoryItemId, long expectedVersion, CartonSpecDraft draft)` on `CartonSpecService` (`inventory :: api`).
  - `GET /api/v1/inventory/items/{id}/carton-spec` (`inventory-items` READ), `PUT` same path (`inventory-items` UPDATE), body `CartonSpecRequest(unitsPerCarton, lengthCm, widthCm, heightCm, weightKg, version)`.
  - `ErrorCode.CARTON_SPEC_LOCKED` (409, "The units per carton of a SKU already used cannot change; a different carton is a new SKU"); vi: "SKU đã dùng nên không đổi được số cái / thùng; thùng khác là SKU riêng mới".
  - `BinStranding.requireCartonResizable(UUID inventoryItemId, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm)` replacing the unit-keyed one (the unit one stays until Task 7 for the old endpoint).
  - DB: `inventory.carton_spec_lock_key(item uuid) returns bigint`; `inventory.sku_in_use(item uuid) returns boolean` (the "used" test of Global Constraints); trigger `tg_inventory_items_carton_locked` BEFORE UPDATE OF `units_per_carton` (and setting the spec back to null) refusing when `sku_in_use`, after `pg_try_advisory_xact_lock(carton_spec_lock_key)`; insert triggers on the six "use" tables take `pg_advisory_xact_lock_shared(carton_spec_lock_key(item))`.
  - Variant responses in `product` gain `cartonSpec` (read via `InventoryControlService.cartonSpec`).
- [ ] **Step 1: Write the failing tests** — `CartonSpecIntegrationTest`: `unusedSkuChangesItsCount`, `countIsLockedByEachUse` (parameterised over carton, receipt line, quote line, order line, production order line, customer price → `CARTON_SPEC_LOCKED`, columns unchanged), `sizeCorrectionOnAStockedSkuMovesEveryReader` (capacity board m³ via `WarehouseOccupancy.cartonVolumesM3`, `cartonSpec`, carton check measures all show the new size), `correctionThatStrandsACartonIsRefused` (`CARTONS_WOULD_NOT_FIT`, nothing changed), `changeIsRecordedInTheHistory`, `countChangeAndFirstUseDoNotInterleave` (two transactions via `TransactionTemplate`: one inserts a first customer price, the other changes the count; exactly one commits); `CartonSpecHttpTest`: warehouse manager may PUT, sales staff gets 403 on PUT and sees `cartonSpec` on the variant response; stale `version` → 409.
- [ ] **Step 2: Run** — `mvn test -Dtest='CartonSpecIntegrationTest,CartonSpecHttpTest'` → FAIL.
- [ ] **Step 3: Implement** — migration (functions, triggers); service checks `sku_in_use` first for a clean error, the trigger is the second statement of the rule; stranding check before the update; change history through the existing product change log (subject type is the builder's call per spec); endpoint; variant response; permission matrix rows.
- [ ] **Step 4: Run** — the two classes plus `PermissionMatrixTest`, `MessageBundleTest`, `RepositorySqlQaTest` (QA `101`: count update under each kind of use refused; size update allowed) → PASS.
- [ ] **Step 5: Commit** (owner's go-ahead) — `feat(inventory): the carton spec edited by the warehouse, its count locked once used`

### Task 3: Quotes and orders count by the SKU's carton

**Files:**
- Create: `src/main/resources/db/migration/V20261206000400__ordering_lines_without_unit.sql`, `tools/db/qa/102_lines_without_unit.sql`
- Modify: `order/internal/service/QuoteService.java` (`cartonUnits` → `cartonSpecs`), `order/internal/domain/QuoteLine.java` (`Carton` holds `unitsPerCarton` only), `order/internal/domain/OrderLine.java` (`withCartons(int cartons, int unitsPerCarton)`), `order/api/PlaceOrderCommand.java` (`Carton(int cartons, int unitsPerCarton)`), `order/internal/entity/QuoteLineJpaEntity.java`, `OrderLineJpaEntity.java`, `order/internal/repository/QuoteRepositoryAdapter.java`, `OrderPersistenceMapper.java`, `order/internal/service/OrderServiceImpl.java`; fixtures `support/CustomerSkuRows.java` (`Issued` loses `uomConversionId()`), `ConfirmedOrders.java`, `ProductionReceivables.java`
- Test: `order/QuoteIntegrationTest`, `order/internal/service/QuoteServiceTest`, `QuoteServiceTransitionsTest`, and the tests using `sku.uomConversionId()` (`OrderThroughContractIntegrationTest`, `ReturnRequestIntegrationTest`, `SalesContractIntegrationTest`, `payment/CancellationRefundIntegrationTest`, `InstallmentReceiptIntegrationTest`, `PaymentReportsIntegrationTest`)

**Interfaces:**
- Consumes: Task 1 `cartonSpecs(Collection<Sku>)`.
- Produces: quote and order lines carry `cartons` and `unitsPerCarton`, no unit id. DB: `quote_line.uom_conversion_id`, `order_line.uom_conversion_id` dropped; `ordering.check_line_carton_unit` rewritten: a new carton line's `units_per_carton` equals the SKU's `units_per_carton` and the SKU has a spec — **the 14 BR-04 customer check kept as is**; `ck_quote_line_shape`, `ck_order_line_cartons` without the column; `inventory.unit_in_use` rewritten without those two tables.
- [ ] **Step 1: Failing tests** — `QuoteIntegrationTest.quoteLineTakesTheSkusCount` (SKU 1,000/carton → line `unitsPerCarton` 1,000), `quoteRefusesASkuWithoutCartonSpec` (`CARTON_SPEC_MISSING`); QA `102`: a quote line whose count differs from the SKU's refused, a line of another customer's SKU refused (BR-04 still there).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** the listed classes + `RepositorySqlQaTest` → PASS.
- [ ] **Step 5: Commit** (owner's go-ahead) — `refactor(order): quote and order lines count by the SKU's carton`

### Task 4: Receiving, cartons, occupancy and allocation without units

**Files:**
- Create: `src/main/resources/db/migration/V20261206000500__inventory_cartons_without_unit.sql`, `tools/db/qa/103_cartons_without_unit.sql`
- Modify: `inventory/api/CountReceivingPackagesCommand.java`, `inventory/api/StockPackageView.java`, `inventory/internal/domain/StockPackage.java`, `inventory/internal/entity/StockPackageJpaEntity.java`, `inventory/internal/repository/StockPackageRepositoryAdapter.java` (delete `cartonFactorOf`), `BinOccupancyQuery.java`, `WarehouseOccupancyQuery.java`, `StockPackageSearchAdapter.java`, `CycleCountRepositoryAdapter.java`, `inventory/internal/service/StockOperationsServiceImpl.java` (delete `packingUnit`), `StockPackageLabels.java`, `BinStranding.java`, `CartonQuarantineService.java`, `CartonReturnService.java`, `inventory/internal/domain/StockAllocator.java`; `procurement/internal/domain/ReceiptLine.java`, `procurement/internal/entity/GoodsReceiptLineJpaEntity.java`, `procurement/internal/repository/GoodsReceiptRepositoryAdapter.java`, `procurement/internal/service/GoodsReceiptServiceImpl.java` (delete `productionCartonUnit`), `procurement/internal/controller/GoodsReceiptController.java` and its request/response DTOs; fixtures `support/CartonRows.java`, `ReceiptCartons.java`, `ShippedCartons.java`
- Test: `procurement/ProductionReceiptIntegrationTest`, `inventory/ReceivingPackagesIntegrationTest`, `StockPackageLayerIntegrationTest`, `StockPackageConsumptionIntegrationTest`, `PutawayConfirmationIntegrationTest`, `PutawayTripIntegrationTest`, `CycleCountIntegrationTest`, `CountVarianceAdjustmentIntegrationTest`, `BinOccupancyQueryIntegrationTest`, `SameReceiptMergeIntegrationTest`, `StockPackageSchemaMigrationTest`, `warehouse/internal/service/BinResizeStrandingIntegrationTest`, `PlaceDeactivationIntegrationTest`, `inventory/internal/service/StockPackageLabelsTest`, `inventory/internal/domain/StockAllocatorTest`

**Interfaces:**
- Consumes: Task 1 columns; Task 2 `carton_spec_lock_key`.
- Produces: `StockAllocator.plan(String sku, List<Candidate> candidates, Quantity required, int unitsPerCarton)` — takes only cartons whose `available` equals `unitsPerCarton` (callers in `inventory` read the item's count); the LPN label prints a new i18n key `stock-package.label.carton` (vi "Thùng", en "Carton") instead of a unit code. DB: `stock_package.uom_conversion_id`, `goods_receipt_lines.uom_conversion_id` dropped; `check_stock_package_created`: the item has a spec and `nominal_qty = units_per_carton`, taking the shared carton-spec lock; `check_goods_receipt_line_unit` and the production check: the production order line's `units_per_carton` equals the SKU's; `check_stock_package_fits_bin` and the view `inventory.stock_package_balance` (rebuilt first, without `unit_of_measure`) read the item columns; occupancy weight is `carton_weight_kg` (no `weight_kg × qty` fallback); `unit_in_use`, `unit_lock_key`, `guard_unit_in_use_change` and `tg_uom_conversions_locked_in_use` dropped (no reference left).
- [ ] **Step 1: Failing tests** — `ProductionReceiptIntegrationTest.cartonsTakeTheSkusCount` (no unit in the request; LPN `nominal_qty` = 1,000); `StockAllocatorTest.cartonOfAnotherCountIsNotAllocated` (candidates 1,000 and 500, line count 1,000 → only the 1,000 ones); QA `103`: a carton with `nominal_qty` ≠ the SKU's count refused; a carton of a SKU without spec refused.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** the listed classes + `RepositorySqlQaTest` + `ModularityTest` → PASS.
- [ ] **Step 5: Commit** (owner's go-ahead) — `refactor(inventory): cartons and receipts take the SKU's carton, no unit`

### Task 5: Load estimate, production slip, instruction sheet and capacity from one carton

**Files:**
- Delete: `inventory/api/PackagingSpecResolver.java`, `PackagingSpec.java`, `PackagingRequest.java`, `RuleLevel.java`, `inventory/internal/service/PackagingSpecResolverImpl.java`, `inventory/internal/domain/PackagingRules.java` and their tests (`PackagingRulesTest`, `PackagingSpecResolverImplTest`, `PackagingSpecResolverIntegrationTest`)
- Modify: `inventory/internal/controller/InventoryItemController.java` (delete `packaging-preview`), `fulfillment/internal/service/LoadEstimateService.java`, `EstimateCartons.java`, `LoadEstimates.java`, `LoadPlanEstimateComparison.java`, `LoadPlans.java`, `CartonMeasures.java`, the load-estimate response DTOs; `order/internal/service/ProductionOrderDispatch.java` (slip carton size), `order/internal/service/WarehouseCapacityService.java`; `inventory/internal/service/WarehouseOccupancyImpl.java`; `design/internal/service/DesignReviewService.java` (`approval` carton), `ProductionInstructionSheet.java`
- Test: `fulfillment/LoadEstimateIntegrationTest`, `fulfillment/internal/service/EstimateCartonsTest`, `LoadEstimateServiceTest`, `LoadEstimateMissingFieldsTest`, `order/ProductionOrderDispatchIntegrationTest`, `WarehouseCapacityIntegrationTest`, `design/internal/service/ApprovalFilesTest`, `inventory/InventoryItemHttpIntegrationTest`, `InventoryItemSecurityIntegrationTest`

**Interfaces:**
- Consumes: Task 1 `cartonSpecs`.
- Produces: an estimate row is `(sku, cartons = order line cartons, carton = CartonSpec)`; fields `level`, `kitParts`, `partialCarton`, `warnings` gone from the response; a SKU without spec keeps `LOAD_ESTIMATE_PACKAGING_MISSING` (422).
- [ ] **Step 1: Failing test** — `ProductionOrderDispatchIntegrationTest.slipAndCapacityBoardShowTheSameCarton` (slip text carries "60 × 40 × 50 cm", the board's m³ = cartons × 0.12); `LoadEstimateIntegrationTest.estimateCountsTheOrdersCartons` (12 cartons ordered → 12 placed, no partial).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** (delete first, let the compiler list the callers). **Step 4: Run** the listed classes → PASS.
- [ ] **Step 5: Commit** (owner's go-ahead) — `refactor: load estimate, production slip and capacity read the one carton spec`

### Task 6: Design, catalogue, reports and SKU generation

**Files:**
- Modify: `design/internal/service/TemplateAvailability.java`, `DesignSubmissionService.java`, `catalog/internal/service/CatalogCustomizationService.java`, `StorefrontAvailabilityService.java`, `reporting/internal/service/StockSummaryReportService.java`, `inventory/internal/service/ItemSpecificationsImpl.java` (copy the five columns and `rotatable`; no units), `product/internal/service/CustomerSkuService.java`
- Test: `design/DesignSubmissionIntegrationTest`, `DesignReviewIntegrationTest`, `DesignProjectIntegrationTest`, `DesignQuoteRequestIntegrationTest`, `DesignStudioEndToEndTest` (set the template's spec through `CartonSpecService`, not `UomConversionService.add`), `product/CustomerSkuIntegrationTest`, `catalog/internal/service/StorefrontAvailabilityServiceTest`, `reporting/internal/service/StockSummaryReportServiceTest`

**Interfaces:** Consumes Task 1 `cartonSpec(s)`, Task 2 `changeCartonSpec`.
- [ ] **Step 1: Failing tests** — `CustomerSkuIntegrationTest.generatedSkuCopiesTheTemplatesCarton` and `templateEditLaterLeavesTheSkuAlone`; `DesignSubmissionIntegrationTest.preflightNeedsTheTemplatesCartonSpec` (`CARTON_SPEC_MISSING` until set).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** the listed classes → PASS.
- [ ] **Step 5: Commit** (owner's go-ahead) — `refactor: design, catalogue and reports read the carton spec`

### Task 7: Drop the units model

**Files:**
- Create: `src/main/resources/db/migration/V20261206000600__inventory_drop_units.sql`, `tools/db/qa/104_units_dropped.sql`
- Delete: `inventory/api/UomConversion.java`, `UomConversionDraft.java`, `UomConversionService.java`, `inventory/internal/service/UomConversionServiceImpl.java`, `inventory/internal/domain/UomConversionRules.java`, `inventory/internal/repository/UomConversionRepository.java`, `product/internal/controller/UomConversionController.java`, `SalesUnitController.java`, `SkuUnitWebMapper.java`, their DTOs (`CreateUomConversionRequest`, `UpdateUomConversionRequest`, `UomConversionResponse`, `CreateSalesUnitRequest`, `SalesUnitResponse`), `product/internal/repository/VariantSalesUnitRepository.java`, the units part of `product/internal/service/SkuUnitService.java`, and their tests (`UomConversionCartonTest`, `UomConversionRulesTest`, `UomConversionServiceImplTest`, `product/UomConversionHttpIntegrationTest`)
- Modify: `common/error/ErrorCode.java` and both bundles (remove `UOM_CONVERSION_*`, `CARTON_SPEC_UNMEASURED` if no caller is left), `docs/business-design/db-design/schema.dbml` (regenerate with `tools/db/gen_dbml.py`), mocks in `DesignModuleTest`, `InvoicingModuleTest`, `CartonQuarantineServiceTest`
- Test: `tools/db/qa/104_units_dropped.sql`, the full suite

**Interfaces:** DB: drop `tg_uom_conversions_mirror_carton`, `tg_inventory_items_base_uom`, `tg_inventory_items_follow_base_uom`, `tg_variant_sales_units_item` and their functions, `product.variant_sales_units`, `inventory.uom_conversions`; `inventory_items.unit_of_measure` stays (spec).
- [ ] **Step 1: Failing QA** — `104`: `inventory.uom_conversions` and `product.variant_sales_units` do not exist; no function body in `pg_proc` mentions `uom_conversion` (`prosrc ilike '%uom_conversion%'` count 0).
- [ ] **Step 2: Run** `mvn test -Dtest=RepositorySqlQaTest` → FAIL. **Step 3: Implement** (delete the classes, let the compiler list what remains).
- [ ] **Step 4: Verify** — if `tools/db/seed/seed_test_data.sql` writes `uom_conversions`, `variant_sales_units` or the old `package_*` columns, regenerate it; `PYTHONUTF8=1 python -X utf8 tools/verify.py` → clean; `mvn test` in the background → green except the known flaky `SalesRollupIntegrationTest.replayedEventsAreIgnored` (rerun alone).
- [ ] **Step 5: Commit** (owner's go-ahead) — `refactor: drop the units-of-measure model; one carton spec per SKU`

### Task 8: kltn-docs wording (docs repository, its own PR)

**Files:** `docs/warehouse/01-product-creation/README.md` (§3 Input rows "Đơn vị quy đổi" and "Quy cách thùng", §4.7, BR-10, §6 edge cases), `docs/open-questions/README.md` (A11), in the kltn-docs repository on a branch from `origin/develop`.

- [ ] **Step 1:** Apply the spec's "kltn-docs" section, each change marked `✅ Chốt lại 2026-11-19`.
- [ ] **Step 2:** Show the diff to the owner; commit, push and open the PR only on the owner's go-ahead; link it from the Jira story.
