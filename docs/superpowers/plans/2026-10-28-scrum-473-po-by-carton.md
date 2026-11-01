# Purchase Orders by Carton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A standard purchase-order line can be entered as "N of a packing unit" (e.g. 5 CARTON). Quantity, price and terms stay in base units, and receiving defaults to the ordered unit.

**Architecture:**
- A `PackingUnit` value on the `PoLine` aggregate carries the unit and the count.
- `ProcurementServiceImpl.checkLines` resolves it from inventory's unit list, then runs the existing base-unit checks.
- `PoLineJpaEntity` writes the existing `uom_conversion_id` / `unit_qty` columns.
- Receiving reads the PO line's unit as the default carton unit.
- No migration.

**Tech Stack:** Java 21, Spring Boot 3.5 / Modulith, JPA, PostgreSQL 16, JUnit 5 / AssertJ, Testcontainers, MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-28-scrum-473-po-by-carton-design.md`

## Global Constraints

- **Branch and git:**
  - Branch `feature/SCRUM-473-po-by-carton`, cut from `develop` @ `dd8a5d3c`.
  - Commit and push only on the owner's go-ahead. No tool/AI attribution, no `Co-Authored-By`.
- **No schema change:** no new file in `db/migration`.
- **Units and prices:**
  - Price is per base unit. MOQ and pack multiple are checked on the base quantity (`SupplierItem.orderQuantityViolations`).
  - Unit codes are matched upper-case (`"carton"` = `CARTON`).
- **`unitOfMeasure` rules:**
  - Unknown unit → `UOM_CONVERSION_NOT_FOUND`.
  - Retired unit → new `PO_LINE_UNIT_INVALID` (409), with a message in both `messages.properties` and `messages_vi.properties`.
  - Base unit → "in base units": no packing unit is stored.
  - Omitted on create → base units.
  - Omitted on a draft revision of a line that has a unit → the line keeps that unit, and the quantity must be a whole number of it (`PO_LINE_UNIT_NOT_MULTIPLE`).
- **Quantities:**
  - `unitQuantity` without `unitOfMeasure` → `VALIDATION_FAILED`.
  - `quantityOrdered` is optional when a unit is named. If sent, it must equal `unitQuantity × factor` (`VALIDATION_FAILED`).
  - The derived quantity stays within 1 … 1,000,000.
- **Out of scope:** subcontract orders — no unit through this API; `reviseDraftQuantity` and amendments are untouched.
- **House style:** javadoc says why; English in code. Delegating constructors keep untouched callers compiling.
- **Tests:** targeted tests per task; one full `mvn -o verify` at the end (Docker up, no source edits while it runs).

## Review Focus

1. **A PO line in a retired unit that is revised in its draft without naming a unit.**
   - Expected: it keeps the retired unit, the same way the receipt keeps a retired unit already on its line. It must not be refused as `PO_LINE_UNIT_INVALID`.
   - Pinned by Task 3 `revisionKeepsARetiredUnitTheLineAlreadyHas`.
2. **The same SKU on two lines, one in cartons and one loose.**
   - Still refused, as today ("appears twice").
   - Pinned by Task 3 `sameSkuTwiceStillRefusedWithUnits`.
3. **Factor 1 packing unit (`PAIR`-like but 1).**
   - Allowed: count equals quantity, and nothing divides wrong.
   - Pinned by Task 1 `factorOneUnitIsAllowed`.
4. **Cartons listed on a receipt line whose PO line is in the base unit.**
   - Still "Cartons need the packing unit they are in".
   - Pinned by Task 4 `baseUnitLineStillNeedsTheUnitNamed`.
5. **Response for a line in base units.**
   - Every new field is null, and `packingUnitPrice` is not 0.
   - Pinned by Task 3 `baseUnitLineHasNoUnitFields`.

---

### Task 1: `PackingUnit` on the PO line (domain)

**Files:**
- Create: `src/main/java/com/stockflow/procurement/internal/domain/PackingUnit.java`
- Modify: `src/main/java/com/stockflow/procurement/internal/domain/PoLine.java`, `PurchaseOrder.java` (`DraftLine`, `line(...)`, `reviseDraft`)
- Test: `src/test/java/com/stockflow/procurement/internal/domain/PurchaseOrderTest.java` (new nested class `PackingUnits`)

**Interfaces:**
- Produces:
  - `record PackingUnit(UUID uomConversionId, String unitOfMeasure, int factor, int count)`.
    - Compact constructor: non-null id and code; `factor ≥ 1`; `count ≥ 1`.
    - `int quantity()` returns `count × factor`.
    - `static PackingUnit of(UomConversion unit, int count)`, where `UomConversion` is `inventory.api`.
    - `PackingUnit withQuantity(String poNumber, int quantity)` returns the same unit with `count = PoLineJpaEntity.wholeUnits`-style arithmetic. It throws `BusinessException(PO_LINE_UNIT_NOT_MULTIPLE)` when the quantity is not a whole number of units. Move the `wholeUnits` check into `PackingUnit` and have `PoLineJpaEntity.wholeUnits` delegate to it, so there is one rule.
  - `PoLine`:
    - gains `PackingUnit packingUnit()`, nullable;
    - the canonical constructor gains it last; the existing constructor delegates with `null`;
    - invariant: `packingUnit == null || packingUnit.quantity() == quantityOrdered`, else `IllegalArgumentException`;
    - `PoLine.draft(...)` gains a `PackingUnit` overload.
  - `PurchaseOrder.DraftLine` gains `PackingUnit unit, boolean unitNamed`; the old 8-arg constructor delegates with `(null, false)`.
  - `PurchaseOrder.line(...)` gains a `PackingUnit` overload.
  - `PurchaseOrder.reviseDraft` rule, for an existing line:
    - `unitNamed` → use `wanted.unit()` (null = base);
    - else if `existing.packingUnit() != null` → `existing.packingUnit().withQuantity(poNumber, wanted.quantity())`;
    - else null.
    - A new line uses `wanted.unit()`.

- [ ] **Step 1: Write the failing tests** in `PurchaseOrderTest.PackingUnits`:
  `packingUnitQuantityIsCountTimesFactor` (factor 1000, count 5 → 5000),
  `factorOneUnitIsAllowed` (factor 1, count 7 → 7),
  `lineQuantityMustMatchItsUnit` (PoLine qty 4999 with unit 5×1000 → IAE),
  `revisionWithoutUnitKeepsTheLinesUnit` (line 5×1000, revise qty 6000 unnamed → unit count 6),
  `revisionWithoutUnitRefusesAPartUnit` (revise qty 5500 unnamed → `PO_LINE_UNIT_NOT_MULTIPLE`),
  `revisionNamingNoUnitGoesToBase` (`unitNamed=true`, unit null → `packingUnit()` null),
  `revisionNamingAnotherUnitTakesIt`.
- [ ] **Step 2: Run** `mvn -o -q test -Dtest=PurchaseOrderTest`. Expected: compile failure on `PackingUnit`.
- [ ] **Step 3: Implement** per Interfaces. Javadoc on `PackingUnit`: why the count is stored (the buyer's own words, and `tg_purchase_order_lines_unit` checks `ordered_qty = unit_qty × factor`), and why price stays per base unit.
- [ ] **Step 4: Run** `mvn -o -q test -Dtest='PurchaseOrderTest,ProcurementBusinessValidationTest'`. Expected: PASS.
- [ ] **Step 5: Commit** (after go-ahead) `feat(procurement): SCRUM-473 a purchase-order line may be ordered in a packing unit`

---

### Task 2: Persist and read the unit

**Files:**
- Modify:
  - `src/main/java/com/stockflow/procurement/internal/entity/PoLineJpaEntity.java`
  - `src/main/java/com/stockflow/procurement/internal/repository/PoLineUnits.java`
  - `src/main/java/com/stockflow/procurement/internal/repository/PurchaseOrderRepositoryAdapter.java` (save lines 150–192, `toDomain`, summary at ~425)
  - `src/main/java/com/stockflow/procurement/api/POLineSummary.java`
- Test: `src/test/java/com/stockflow/procurement/PurchaseOrderUnitIntegrationTest.java` (new; service-level part)

**Interfaces:**
- Consumes: Task 1 `PoLine.packingUnit()`, `PackingUnit`.
- Produces:
  - `PoLineJpaEntity`:
    - `uom_conversion_id` becomes insertable and updatable; `unit_qty` stays as mapped.
    - `PoLineJpaEntity(PoLine)` writes both columns from `line.packingUnit()`.
    - `reviseDraft(PoLine line)` drops its `unitFactor` parameter and takes both columns from `line.packingUnit()`. Its only caller is the adapter (line 174), so no overload is kept.
    - `reviseDraftQuantity(int, int)` (subcontract) is unchanged.
    - Rewrite the class javadoc paragraph "the application still orders in the base unit".
  - `PoLineUnits.units(Collection<PoLineJpaEntity>) → Map<UUID, UomConversion>` by line id; `factors(...)` is reimplemented on top of it.
  - Adapter `toDomain` builds each line's `PackingUnit.of(unit, entity.getUnitQuantity())` when `uom_conversion_id` is set. One `units` call per order, as `factors` does today.
  - `POLineSummary` appends `String unitOfMeasure, Integer unitFactor, Integer unitQuantity`; the old constructor delegates with nulls.

- [ ] **Step 1: Write the failing test** `PurchaseOrderUnitIntegrationTest` (`@IntegrationTest @Import(PostgresContainer.class)`).
  - Fixtures: like `PurchaseOrderDraftEditIntegrationTest.setUp` — a supplier, approved SKUs, and `SupplierItemFixtures.list` with MOQ 1 and pack 1. Add a `CARTON` factor 1000 unit and a retired `BOX` factor 10 unit (`active = false`) via `inventory.uom_conversions` SQL.
  - Test `storedUnitIsReadBack`:
    - Create a base-unit draft with `procurement.createPurchaseOrder` (qty 5000).
    - Put the line in CARTON with SQL, as `draftEditKeepsTheLineUnit` does: `uom_conversion_id = <CARTON>`, `unit_qty = 5`.
    - `procurement.findById` → the line's `unitOfMeasure() == "CARTON"`, `unitFactor() == 1000`, `unitQuantity() == 5`.
  - Test `draftRevisionWritesTheUnitFromTheDomain`: same setup, then revise the draft with the line's quantity 6000 through `RevisePOLineCommand` with no unit → DB `unit_qty = 6`. The adapter now takes it from `PoLine.packingUnit()`, not from `PoLineUnits.factors`.
- [ ] **Step 2: Run** `mvn -o -q test -Dtest=PurchaseOrderUnitIntegrationTest`. Expected: compile failure on `POLineSummary.unitOfMeasure()`.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** `mvn -o -q test -Dtest='PurchaseOrderUnitIntegrationTest,PurchaseOrderDraftEditIntegrationTest,SubcontractPurchasingIntegrationTest,PurchaseOrderApprovalLimitIntegrationTest'`. Expected: PASS. `draftEditKeepsTheLineUnit` must stay green unchanged.
- [ ] **Step 5: Commit** (after go-ahead) `feat(procurement): SCRUM-473 store and read the packing unit of a purchase-order line`

---

### Task 3: Order in a unit through the API

**Files:**
- Modify:
  - `src/main/java/com/stockflow/procurement/api/CreatePOLineCommand.java`, `RevisePOLineCommand.java`
  - `src/main/java/com/stockflow/procurement/internal/controller/dto/CreatePOLineRequest.java`, `UpdatePOLineRequest.java`, `POLineResponse.java`
  - `src/main/java/com/stockflow/procurement/internal/controller/PurchaseOrderWebMapper.java`, and the controller code that maps requests to commands
  - `src/main/java/com/stockflow/procurement/internal/service/ProcurementServiceImpl.java` (`LineInput`, `ResolvedLine`, `CheckedLine`, `checkLines`, `toLines`, `reviseDraft`)
  - `src/main/java/com/stockflow/common/error/ErrorCode.java`
  - `src/main/resources/i18n/messages.properties`, `messages_vi.properties`
- Test: `PurchaseOrderUnitIntegrationTest` (HTTP part via `MockMvc`, as `PurchaseOrderDraftEditIntegrationTest` does)

**Interfaces:**
- Consumes: Tasks 1–2.
- Produces:
  - **Commands:**
    - `CreatePOLineCommand(String sku, String description, Integer quantityOrdered, BigDecimal unitPrice, BigDecimal taxRate, String unitOfMeasure, Integer unitQuantity)`. The existing constructors keep `int quantityOrdered` and delegate with `(null, null)`.
    - `RevisePOLineCommand` gets the same two trailing fields and delegating constructors.
  - **Requests:**
    - `CreatePOLineRequest` / `UpdatePOLineRequest`: `Integer quantityOrdered` (`@Positive @Max(1_000_000)`, no longer required), `@Size(max = 20) String unitOfMeasure`, `@Positive @Max(1_000_000) Integer unitQuantity`.
    - Bean validation does not express "one of the two"; `checkLines` does.
  - **Error code:** `ErrorCode.PO_LINE_UNIT_INVALID("The unit is retired; order in an active unit", 409)`.
    - EN `error.PO_LINE_UNIT_INVALID=The unit is retired; order in an active unit of the item`.
    - VI `error.PO_LINE_UNIT_INVALID=Đơn vị này đã ngừng dùng; hãy đặt theo một đơn vị đang dùng của mặt hàng`.
  - **`checkLines`:** per line, before MOQ:
    1. Neither `quantityOrdered` nor `unitOfMeasure` → field error `lines[i].quantityOrdered` `VALIDATION_FAILED`.
    2. `unitQuantity` without unit → `lines[i].unitQuantity` `VALIDATION_FAILED`.
    3. Unit not found among `units.conversionsOf(sku)` → `lines[i].unitOfMeasure` `UOM_CONVERSION_NOT_FOUND`.
    4. Retired, and not the existing line's own unit (revise only) → `lines[i].unitOfMeasure` `PO_LINE_UNIT_INVALID`.
    5. Unit named without `unitQuantity` → `lines[i].unitQuantity` `VALIDATION_FAILED`.
    6. Base unit → no `PackingUnit`; quantity = `unitQuantity`.
    7. Derived quantity ≠ sent `quantityOrdered` → `lines[i].quantityOrdered` `VALIDATION_FAILED`.

    - Rules 1–7 run in their own pass, right after the SKUs are resolved and before the supplier-terms pass.
    - Their field errors are collected and thrown together as `BusinessException(VALIDATION_FAILED, "<n> line(s) name an invalid quantity or unit", errors)`. Each `FieldError` carries its own code (`UOM_CONVERSION_NOT_FOUND`, `PO_LINE_UNIT_INVALID`, `VALIDATION_FAILED`).
    - The supplier-terms pass then sees the derived base quantity.
    - `CheckedLine` carries `PackingUnit unit, boolean unitNamed`.
  - **`POLineResponse`** appends `String unitOfMeasure, Integer unitFactor, Integer unitQuantity, Integer openUnitQuantity, BigDecimal packingUnitPrice`:
    - `openUnitQuantity = openQuantity / unitFactor` (floor);
    - `packingUnitPrice = unitPrice × unitFactor`;
    - all null for base. Map in `PurchaseOrderWebMapper` with `@Mapping(expression=…)` or a default method, matching the mapper's style.

- [ ] **Step 1: Write the failing tests** in `PurchaseOrderUnitIntegrationTest`:
  - `createInCartons`: POST line `{"sku":…,"unitOfMeasure":"carton","unitQuantity":5,"unitPrice":10}` →
    - 201;
    - DB `ordered_qty=5000`, `unit_qty=5`, `uom_conversion_id=<CARTON>`;
    - response `unitOfMeasure="CARTON"`, `unitFactor=1000`, `unitQuantity=5`, `openUnitQuantity=5`, `packingUnitPrice=10000`, `lineTotal` = 50000 + tax.
  - `namingTheBaseUnitStoresNone` → `uom_conversion_id` null, `unit_qty` null, `ordered_qty` = unitQuantity.
  - `retiredUnitRefused` → top-level `VALIDATION_FAILED`, field `lines[0].unitOfMeasure` with code `PO_LINE_UNIT_INVALID`.
  - `unknownUnitRefused` → `UOM_CONVERSION_NOT_FOUND`.
  - `unitQuantityWithoutUnitRefused`.
  - `mismatchingQuantityRefused` (`quantityOrdered` 4000 with 5 CARTON).
  - `moqCheckedOnTheBaseQuantity`: supplier MOQ 6000, 5 CARTON → `PO_LINE_BELOW_MOQ`.
  - `revisionChangesKeepsAndDropsTheUnit`:
    - PUT naming `CARTON` 6 → 6000;
    - PUT omitting the unit with quantity 7000 → keeps CARTON, count 7;
    - PUT naming the base unit → none.
  - `revisionKeepsARetiredUnitTheLineAlreadyHas`: line in CARTON, then `UPDATE uom_conversions SET active=false`, then PUT omitting the unit → 200.
  - `sameSkuTwiceStillRefusedWithUnits`.
  - `submittedRevisionSnapshotKeepsTheUnit`: submit → `purchase_order_line_revisions` row has `uom_conversion_id` and `unit_qty`.
  - `baseUnitLineHasNoUnitFields`: every new response field is JSON null.
- [ ] **Step 2: Run** `mvn -o -q test -Dtest=PurchaseOrderUnitIntegrationTest`. Expected: failures (fields unknown / ignored).
- [ ] **Step 3: Implement** per Interfaces; update the `CreatePOLineRequest` / `UpdatePOLineRequest` `@Schema` descriptions.
- [ ] **Step 4: Run** `mvn -o -q test -Dtest='PurchaseOrderUnitIntegrationTest,PurchaseOrderDraftEditIntegrationTest,PurchaseOrderCatalogTermsIntegrationTest,PurchaseOrderApprovalLimitIntegrationTest,MessageBundleTest,ArchitectureTest,ModularityTest'`. Expected: PASS.
- [ ] **Step 5: Commit** (after go-ahead) `feat(procurement): SCRUM-473 order a purchase-order line in cartons through the API`

---

### Task 4: Receiving defaults to the ordered unit

**Files:**
- Modify:
  - `src/main/java/com/stockflow/procurement/internal/domain/ReceivingPurchaseOrder.java` (`Line` gains `UUID uomConversionId`; the old constructor delegates with null)
  - `src/main/java/com/stockflow/procurement/internal/repository/ReceivingPurchaseOrderAdapter.java:107`
  - `src/main/java/com/stockflow/procurement/internal/service/GoodsReceiptServiceImpl.java` (`cartonUnit`, its caller near line 236, the view near line 995)
  - `src/main/java/com/stockflow/procurement/internal/service/GoodsReceipts.java` (`LineView`)
  - `src/main/java/com/stockflow/procurement/internal/controller/dto/GoodsReceiptResponse.java` (`Line` gains `String orderedUnitOfMeasure`) and its mapper
- Test: `src/test/java/com/stockflow/procurement/GoodsReceiptPackageIntegrationTest.java` (new tests; reuse its fixtures)

**Interfaces:**
- Consumes: Task 2 (the PO line's `uom_conversion_id` is written).
- Produces:
  - `cartonUnit(...)` gains a `UUID orderedUnitId` parameter, the PO line's unit or null.
  - When `in.packages()` is not empty and `code == null` and `orderedUnitId != null`, it uses the unit with that id from `itemUnits`. Every later rule applies to it: not base, active unless already the line's unit, rule 7.
  - A named code always wins.
  - `LineView` / `GoodsReceiptResponse.Line` append `orderedUnitOfMeasure` (code from `itemUnits` by id; null for base).

- [ ] **Step 1: Write the failing tests**:
  - `cartonsWithoutAUnitTakeTheOrderedUnit`: a PO line in CARTON; the receipt line lists two cartons and no `unitOfMeasure` → saved line unit CARTON, cartons labelled in CARTON.
  - `aNamedUnitWins`: the same, naming another active unit → that unit.
  - `baseUnitLineStillNeedsTheUnitNamed`: PO line in base; cartons with no unit → `VALIDATION_FAILED` on `unitOfMeasure`, as today.
  - `receiptLineShowsTheOrderedUnit`: response `orderedUnitOfMeasure = "CARTON"`.

  Set the PO line's unit with SQL if creating through the API needs Task 3; it does not otherwise depend on it.
- [ ] **Step 2: Run** `mvn -o -q test -Dtest=GoodsReceiptPackageIntegrationTest`. Expected: the new tests fail.
- [ ] **Step 3: Implement** per Interfaces; update the `cartonUnit` javadoc ("the ordered unit is the default, never a rule").
- [ ] **Step 4: Run** `mvn -o -q test -Dtest='GoodsReceiptPackageIntegrationTest,GoodsReceiptPackageHttpTest,GoodsReceiptIntegrationTest,ReceivingPurchaseOrderTest'`. Expected: PASS.
- [ ] **Step 5: Commit** (after go-ahead) `feat(procurement): SCRUM-473 a receipt counts cartons in the ordered unit by default`

---

### Task 5: Docs and the full gate

**Files:**
- Modify: `CLAUDE.md` §4 — a short "Purchase orders by carton (SCRUM-473)" paragraph after "Picking by carton". It covers:
  - `PackingUnit` on `PoLine`, with price and terms in base units;
  - the revision rule (omitted keeps, base unit drops);
  - the receipt default.

- [ ] **Step 1:** Edit `CLAUDE.md`.
- [ ] **Step 2:** `PYTHONUTF8=1 python tools/verify.py`. Expected: "all static checks passed".
- [ ] **Step 3:** `mvn -o verify` in the background. Expected: BUILD SUCCESS, 0 failures.
- [ ] **Step 4:** Commit (after go-ahead) `docs: SCRUM-473 purchase orders by carton`. Ask before pushing and opening the PR. The PR body tells Tú (procurement code, no schema change) and the frontend (the new request and response fields; pre-select `orderedUnitOfMeasure`).
