# Receiving into cartons (SCRUM-473) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A draft goods receipt labels each carton with an LPN and its count, confirmation puts the goods into IN_STOCK cartons, QC moves and quarantines whole cartons, and carton labels print as a PDF.

**Architecture:** Procurement keeps owning the receipt and its count; every carton write goes through new `inventory :: api` methods that keep the #125 lock order (SKU advisory lock → `stock_package FOR UPDATE` → stock rows by id). The carton reconciliation is a pure domain class; carton rows move and change status in place. Label drawing is extracted from `warehouse` into `common` and reused.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Modulith 1.4, JPA/Hibernate, PostgreSQL 16 (Testcontainers), PDFBox + ZXing (already used by location labels).

**Spec:** `docs/superpowers/specs/2026-10-26-scrum-473-receive-in-cartons-design.md` — read it with this plan; section numbers below (§n) refer to it.

## Global Constraints

- No migration. The schema is complete (§1); if a task seems to need one, stop and ask — schema belongs to Tú.
- Lock order in every carton write: `policies.lock(sku)` → `StockPackageRepository.findAllByIdForUpdate` → `stockItems.findByIdForUpdate` in id order.
- LPN format `LPN-yyyyMMdd-nnnnnn`, `platform.document_sequence` type `LPN`, day in `BusinessCalendar.ZONE`.
- Every new `ErrorCode` gets `error.<CODE>` in `messages.properties` and `messages_vi.properties` (Vietnamese text in the `_vi` bundle only).
- Identifiers, code and javadoc in English; javadoc says why. Change any javadoc the new behaviour makes false (e.g. `GoodsReceiptServiceImpl` QC javadoc, `ReceiveStockCommand`).
- Tests that touch cartons must commit (service calls from a non-transactional test, as `GoodsReceiptIntegrationTest` does) — the content check of `V20261020000200` runs only at commit.
- Owner's test preference: write tests with the code, but during Tasks 1–7 only compile (`mvn -q -o test-compile`) and run `python -X utf8 tools/verify.py`; every test runs once in Task 8.
- Commit at the end of a task only once the owner has approved committing on this branch; never push without asking.

## Review Focus

1. A line counted entirely into cartons (loose remainder 0) must confirm without writing a zero-quantity loose row — Task 5 `confirmAllInCartons`.
2. `inventory.receive` replayed for the same receipt line (retried confirmation) must not create a second set of carton rows — Task 3 `receiveReplayWritesNothingTwice`.
3. A carton counted above its unit's factor (excess) is accepted and stays unopened at that count — Task 5 `excessCartonIsKept`.
4. QC naming a carton of another line of the same receipt is refused, not quarantined — Task 6 `inspectRefusesAnotherLinesCarton`.
5. Recounting a line into another receiving area takes its RECEIVING cartons along — Task 5 `recountMovesCartonsWithTheLine`.

---

### Task 1: Shared label drawing in `common`

**Files:**
- Create: `src/main/java/com/stockflow/common/label/LabelDrawing.java` (Code 128, QR, fitted text, mm↔pt, `Cell`)
- Move: `warehouse/internal/service/LabelFormat.java` → `common/label/LabelFormat.java` (public)
- Move: `warehouse/internal/service/LabelFonts.java` → `common/label/LabelFonts.java` (public class, public ctor)
- Modify: `warehouse/internal/service/LocationLabelPdfRenderer.java` (delegate to `LabelDrawing`), imports of `LabelFormat` in `warehouse/internal/controller/**`
- Move test helper: `src/test/java/com/stockflow/warehouse/internal/service/LabelDecoding.java` → `src/test/java/com/stockflow/common/label/LabelDecoding.java` (public)

**Interfaces:**
- Produces: `public final class LabelDrawing` with `public record Cell(double leftMm, double topMm, double widthMm, double heightMm)`, `static void barcode(PDPageContentStream out, Cell cell, String code, double leftMm, double topMm, double widthMm, double heightMm)`, `static void qr(PDPageContentStream out, Cell cell, String code, double leftMm, double topMm, double sideMm)`, `static void text(PDPageContentStream out, Cell cell, PDFont font, float size, String text, double leftMm, double baselineMm)`, `static float fittingSize(PDFont font, String text, double widthMm, float max, float min, float step)`, `static String cut(PDFont font, String text, double widthMm)`, `static float points(double mm)`, `static double millimetres(float points)`; `LabelFormat` and `LabelFonts` unchanged apart from package and visibility.

- [ ] **Step 1:** Move `LocationLabelPdfRendererTest`'s decoding helper to `common/label/LabelDecoding.java`; update its imports. No new test: the existing `LocationLabelPdfRendererTest`, `LocationLabelServiceTest`, `LocationLabelControllerHttpTest` are the proof that labels are unchanged.
- [ ] **Step 2:** Extract the private static drawing helpers of `LocationLabelPdfRenderer` (lines ~158–268) into `LabelDrawing` with the signatures above; the renderer keeps its layout (`drawLabel`, `context`, `drawable`) and calls `LabelDrawing`. Bodies move unchanged.
- [ ] **Step 3:** Move `LabelFormat`, `LabelFonts`; fix imports (`grep -rl "LabelFormat\|LabelFonts" src`).
- [ ] **Step 4:** `mvn -q -o test-compile` and `python -X utf8 tools/verify.py` → both clean.
- [ ] **Step 5:** Commit `refactor(common): share label drawing between location and carton labels (SCRUM-473)`.

### Task 2: Inventory domain — carton reconciliation and carton rows

**Files:**
- Create: `inventory/internal/domain/ReceivingPackagePlan.java`
- Modify: `inventory/internal/domain/StockItem.java` (`location` no longer final; three methods below)
- Modify: `inventory/internal/entity/StockItemJpaEntity.java` (`apply` also writes `locationCode`), `inventory/internal/repository/StockItemPersistenceMapper.java` (`applyToEntity` passes the location)
- Test: `src/test/java/com/stockflow/inventory/internal/domain/ReceivingPackagePlanTest.java`, add cases to `src/test/java/com/stockflow/inventory/internal/domain/StockItemTest.java` (create the class if absent)

**Interfaces:**
- Produces:
  - `public record ReceivingPackagePlan(List<Recount> recounts, List<Integer> newCounts, List<StockPackage> voided)`, `record Recount(StockPackage carton, int quantity)`; `static ReceivingPackagePlan of(List<StockPackage> current, List<Wanted> wanted)` with `record Wanted(String lpn /* nullable */, int quantity)`. Throws `BusinessException(STOCK_PACKAGE_NOT_FOUND)` for an LPN not in `current`, `INVALID_STOCK_PACKAGE_TRANSITION` for one not RECEIVING, `VALIDATION_FAILED` for an LPN listed twice or a quantity < 1. `current` holds only the line's cartons.
  - `StockItem.receiveInboundInto(StockPackageId carton, Sku sku, LocationId location, String lotNumber, LocalDate expiryDate, Quantity quantity, Instant receivedAt, UUID lotId)` → INBOUND row with `stockPackageId` set.
  - `StockItem.followPackageTo(LocationId destination)` — only for a row with a carton (`IllegalStateException` on a loose row); keeps id, reservations, status.
  - `StockItem.reclassifyInPackage(StockStatus target)` — row in a carton, current status INBOUND or QUARANTINE, target QUARANTINE or BLOCKED, no reserved units (`IllegalStateException` otherwise).

- [ ] **Step 1: Write `ReceivingPackagePlanTest`:** `newEntriesAreCounts` (two `Wanted(null, 1000)`, `Wanted(null, 980)` on empty current → `newCounts == [1000, 980]`), `knownLpnIsRecounted`, `missingCartonIsVoided`, `foreignLpnIsRefused` (`STOCK_PACKAGE_NOT_FOUND`), `voidCartonCannotBeRecounted` (`INVALID_STOCK_PACKAGE_TRANSITION`), `duplicateLpnIsRefused`, `zeroQuantityIsRefused` (`VALIDATION_FAILED`).
- [ ] **Step 2: Write `StockItem` cases:** `receiveInboundIntoKeepsTheCarton`, `followPackageMovesOnlyCartonRows`, `reclassifyInPackageQuarantinesInbound`, `reclassifyInPackageRefusesReservedUnits`.
- [ ] **Step 3:** Implement the four signatures. `applyToEntity` must write `location_code` — today it writes only on_hand, reserved and status.
- [ ] **Step 4:** `mvn -q -o test-compile`, `verify.py` → clean.
- [ ] **Step 5:** Commit `feat(inventory): plan a receipt line's cartons; carton rows move and change status in place (SCRUM-473)`.

### Task 3: Inventory API — count cartons while receiving, confirm into cartons

**Files:**
- Create: `inventory/api/CountReceivingPackagesCommand.java`, `inventory/api/StockPackageView.java`
- Modify: `inventory/api/InventoryService.java`, `inventory/internal/service/InventoryServiceImpl.java`, `inventory/internal/service/StockOperations.java`, `inventory/internal/service/StockOperationsServiceImpl.java` (`receive`), `inventory/internal/domain/StockPackageRepository.java` + `inventory/internal/repository/StockPackageRepositoryAdapter.java` (`nextLpn`), `inventory/api/UomConversionService.java` + `inventory/internal/service/UomConversionServiceImpl.java` + `inventory/internal/repository/UomConversionRepository.java` (lookup by code), `inventory/api/ReceiveStockCommand.java` (javadoc only)
- Test: `src/test/java/com/stockflow/inventory/ReceivingPackagesIntegrationTest.java`

**Interfaces:**
- Consumes: Task 2 `ReceivingPackagePlan`, `StockItem.receiveInboundInto`.
- Produces:
  - `public record CountReceivingPackagesCommand(UUID receiptLineId, Sku sku, UUID uomConversionId /* null when packages empty */, String locationCode, List<PackageCount> packages, UUID actorId)`, `public record PackageCount(String lpn, int quantity)` nested.
  - `public record StockPackageView(UUID id, String lpn, UUID receiptLineId, String sku, UUID uomConversionId, String unitOfMeasure, int nominalQty, Integer quantity, String locationCode, String status, boolean unopened)` — `quantity` = `received_qty` while RECEIVING, on-hand of its rows afterwards.
  - `InventoryService.countReceivingPackages(CountReceivingPackagesCommand) : List<StockPackageView>` (the line's live cartons after the call, LPN order).
  - `InventoryService.receivingPackagesOf(Collection<UUID> receiptLineIds) : Map<UUID, List<StockPackageView>>` (VOID cartons left out).
  - `UomConversionService.conversion(Sku sku, String unitOfMeasure) : Optional<UomConversion>`.
  - `StockPackageRepository.nextLpn(LocalDate day) : String`.

- [ ] **Step 1: Write `ReceivingPackagesIntegrationTest`** (carton unit inserted by JDBC as `StockPackageLayerIntegrationTest` does; receipt line rows via `ReferenceRows`/`DemoData` helpers that exist):
  - `countCreatesLabelledCartons` — two packages → two RECEIVING cartons, LPNs match `^LPN-\d{8}-\d{6}$` and differ, `received_qty` 1000 and 980, location = line's.
  - `recountKeepsLpnAndVoidsTheDropped` — recount first to 990, omit second → first RECEIVING 990, second VOID with `closed_at`.
  - `emptyListVoidsAll`.
  - `receiveIntoCartons` — count 1000 + 980, `receive` with quantity 2000 → cartons IN_STOCK unopened; one INBOUND row per carton (on_hand 1000, 980, `stock_package_id` set); one loose INBOUND row of 20; three RECEIPT ledger rows, two with `to_stock_package_id`; ATP of the SKU unchanged.
  - `receiveRefusesCartonsAboveTheLine` — cartons 1000 + 980, `receive` quantity 1500 → `RECEIPT_PACKAGES_EXCEED_LINE`.
  - `receiveReplayWritesNothingTwice` (Review Focus 2) — second `receive` of the same line returns the first move; row and ledger counts unchanged.
- [ ] **Step 2:** `StockPackageRepositoryAdapter.nextLpn`: same upsert as `CycleCountRepositoryAdapter.nextNumber` with type `'LPN'`, formatted `"LPN-%s-%06d"`, `yyyyMMdd`.
- [ ] **Step 3:** `UomConversionRepository.find(String sku, String unitOfMeasure)` and `UomConversionService.conversion`.
- [ ] **Step 4:** `countReceivingPackages` in `StockOperationsServiceImpl` (delegated by `InventoryServiceImpl`): `WarehouseScope.requireLocation`, lock SKU, `findByReceiptLines` → `findAllByIdForUpdate` of the line's ids, `ReceivingPackagePlan.of`, apply: `StockPackage.receiving(StockPackageId.newId(), nextLpn(today), itemId, uomConversionId, factor, location, receiptLineId)` + `count`; recounts `count` and `moveTo(location)` when it differs; voided `cancel(now)`. Factor and item id from the unit (`UomConversionRepository.find(sku, id)`). Requires `uomConversionId` when packages are non-empty (`VALIDATION_FAILED`).
- [ ] **Step 5:** `receive`: after the replay check and lot opening, read the line's cartons (`findByReceiptLines` → `findAllByIdForUpdate`, RECEIVING only); refuse `Σ received_qty > quantity` with new `ErrorCode.RECEIPT_PACKAGES_EXCEED_LINE("The cartons hold more than the receipt line", 422)`; per carton `stockItems.save(StockItem.receiveInboundInto(...))`, `putInStock()`, save carton, ledger `RECEIPT` `.arriving(carton.id())`; loose remainder through today's code only when > 0. The returned `StockMove` is the loose line, or the first carton's when nothing is loose.
- [ ] **Step 6:** Add the error code and both bundle messages; `test-compile`, `verify.py` → clean.
- [ ] **Step 7:** Commit `feat(inventory): label cartons while receiving and receive into them (SCRUM-473)`.

### Task 4: Inventory API — move and reclassify a whole carton

**Files:**
- Create: `inventory/api/MoveStockPackageCommand.java`, `inventory/api/ReclassifyStockPackageCommand.java`
- Modify: `inventory/api/InventoryService.java`, `InventoryServiceImpl`, `StockOperations`, `StockOperationsServiceImpl`
- Test: `src/test/java/com/stockflow/inventory/StockPackageMoveIntegrationTest.java`

**Interfaces:**
- Consumes: Task 2 `followPackageTo`, `reclassifyInPackage`; Task 3 `StockPackageView`.
- Produces:
  - `public record MoveStockPackageCommand(String lpn, String toLocationCode, MoveReference reference, UUID referenceId, UUID actorId)`
  - `public record ReclassifyStockPackageCommand(String lpn, String toLocationCode, StockDisposition disposition, MoveReference reference, UUID referenceId, UUID actorId, String reason)` — disposition QUARANTINE or BLOCKED only (`IllegalArgumentException` in the compact constructor otherwise).
  - `InventoryService.moveStockPackage(MoveStockPackageCommand) : StockPackageView`
  - `InventoryService.reclassifyStockPackage(ReclassifyStockPackageCommand) : StockPackageView`

- [ ] **Step 1: Write `StockPackageMoveIntegrationTest`** (an IN_STOCK carton made through Task 3's API):
  - `moveTakesTheRowsAlong` — carton and its row at the destination, same row id, still unopened, one MOVE ledger row with `from_stock_package_id` = `to_stock_package_id` = the carton.
  - `moveRefusesAnotherWarehouse` — `MOVE_ACROSS_WAREHOUSES`, as the loose `relocate` answers.
  - `moveRefusesSameLocation` — `VALIDATION_FAILED`.
  - `moveRefusesAClosedCarton` — VOID → `INVALID_STOCK_PACKAGE_TRANSITION`.
  - `reclassifyQuarantinesInPlace` — rows QUARANTINE in the carton at the quarantine area, carton unopened, ledger row with status INBOUND → QUARANTINE and the carton on both sides.
  - `unknownLpn` — `STOCK_PACKAGE_NOT_FOUND`.
- [ ] **Step 2:** Implement both in `StockOperationsServiceImpl`: `findByLpn` (404), scope check on both locations, lock SKU (from the carton's rows / item), `findAllByIdForUpdate(List.of(id))`, rows `findIdsInStockPackage` → `findByIdForUpdate` in id order, destination usable and in the same warehouse (reuse the checks `relocate` makes), `carton.moveTo`, each row `followPackageTo` (and `reclassifyInPackage`), save, one ledger row per row with `.leaving(id).arriving(id)`, movement type and reference as the loose `relocate` writes for the same `MoveReference`.
- [ ] **Step 3:** `test-compile`, `verify.py` → clean.
- [ ] **Step 4:** Commit `feat(inventory): move and reclassify a carton whole (SCRUM-473)`.

### Task 5: Procurement — count cartons on the draft, confirm into them

**Files:**
- Modify: `procurement/internal/domain/ReceiptLine.java` (+ `UUID uomConversionId`, in ctor and `counted`), `procurement/internal/entity/GoodsReceiptLineJpaEntity.java` (+ `uom_conversion_id`), `procurement/internal/repository/GoodsReceiptRepositoryAdapter.java` (map it), `procurement/internal/service/GoodsReceipts.java` (`NewLine` + `unitOfMeasure`, `packages`; `LineView` + `unitOfMeasure`, `looseQuantity`, `packages`), `procurement/internal/service/GoodsReceiptServiceImpl.java` (`replaceLines`, `confirm`, `view`), `procurement/internal/controller/dto/ReceiptLinesRequest.java`, `procurement/internal/controller/dto/GoodsReceiptResponse.java`, the controller's mapping, `common/error/ErrorCode.java`, both bundles
- Test: `src/test/java/com/stockflow/procurement/GoodsReceiptPackageIntegrationTest.java`, one HTTP case in the existing goods-receipt controller HTTP test (or a new `GoodsReceiptPackageHttpTest`)

**Interfaces:**
- Consumes: Task 3 `countReceivingPackages`, `receivingPackagesOf`, `UomConversionService.conversion`, `receive`.
- Produces: `GoodsReceipts.NewLine(UUID purchaseOrderLineId, int quantity, String lotNumber, LocalDate expiryDate, String locationCode, String note, String unitOfMeasure, List<NewPackage> packages)` with `record NewPackage(String lpn, int quantity)`; `LineView` gains `String unitOfMeasure, int looseQuantity, List<PackageView> packages` with `record PackageView(UUID id, String lpn, int quantity, int nominalQty, String status)`. Keep a constructor of `NewLine` with the old six parameters so existing callers compile.

- [ ] **Step 1: Write `GoodsReceiptPackageIntegrationTest`** on the demo SOFA (2-step) item with a CARTON unit of 1000 inserted by JDBC:
  - `draftCartonsGetLpns` — line quantity 4990 with packages 1000, 1000, 1000, 1000, 980 → five distinct LPNs in the view, `unitOfMeasure` CARTON, `looseQuantity` 10.
  - `recountKeepsAndVoids` — resend with the first two LPNs (one at 990) and no others → three VOID, `quantity` of the first 990.
  - `recountMovesCartonsWithTheLine` (Review Focus 5) — second receiving area → cartons' `location_code` follows.
  - `unitChangeWithCartonsKept` → `RECEIPT_LINE_HAS_PACKAGES`; `packingUnitWithoutCartons` and `baseUnitWithCartons` → `RECEIPT_LINE_UNIT_INVALID`; `cartonsAboveLine` → `RECEIPT_PACKAGES_EXCEED_LINE` as a field error on `lines[0].packages`; `subcontractRefusesCartons` → `RECEIPT_PACKAGES_NOT_SUPPORTED`.
  - `cancelVoidsCartons` — existing behaviour still holds with real cartons.
  - `confirmIntoCartons` — cartons IN_STOCK unopened, rows per carton, loose row of the remainder, `goods_receipt_lines.uom_conversion_id` = CARTON.
  - `confirmAllInCartons` (Review Focus 1) — no loose row exists for the line.
  - `excessCartonIsKept` (Review Focus 3) — a carton of 1010 confirms IN_STOCK, unopened, rows on_hand 1010.
  - HTTP: `PUT /api/v1/goods-receipts/{id}/lines` with `unitOfMeasure` and `packages` → 200 with `packages[*].lpn`.
- [ ] **Step 2:** Map `uom_conversion_id` through entity, adapter and `ReceiptLine`.
- [ ] **Step 3:** `replaceLines`: per line, resolve the unit with `UomConversionService.conversion(sku, code)` and apply §3 rules 2, 3, 5, 8 through `LineErrors` (fields `lines[i].unitOfMeasure`, `lines[i].packages`); read existing cartons once (`receivingPackagesOf(existing line ids)`) for rule 7; after `receipts.save(receipt)` (it flushes, so new line ids exist for the FK) call `countReceivingPackages` for each line that has cartons now or had them before. New codes: `RECEIPT_PACKAGES_NOT_SUPPORTED("Cartons are not labelled on a subcontract receipt", 409)`, `RECEIPT_LINE_UNIT_INVALID("A receipt line counts in its item's base unit, or in an active packing unit it has cartons of", 422)`.
- [ ] **Step 4:** `confirm` is unchanged apart from javadoc: `inventory.receive` now finds the cartons. `view` fills `unitOfMeasure`, `packages` and `looseQuantity` (= quantity − Σ live cartons) from one `receivingPackagesOf` call per receipt.
- [ ] **Step 5:** Request/response DTOs: `ReceiptLinesRequest.Line` + `@Size(max = 16) String unitOfMeasure`, `@Size(max = 500) List<@Valid @NotNull Package> packages`, `record Package(@Size(max = 30) String lpn, @Positive int quantity)`; response line mirrors `LineView`. Update the endpoint's `@Operation` description with the new codes.
- [ ] **Step 6:** Codes in both bundles; `test-compile`, `verify.py` → clean.
- [ ] **Step 7:** Commit `feat(procurement): count a draft receipt into labelled cartons (SCRUM-473)`.

### Task 6: Procurement — QC by whole carton

**Files:**
- Modify: `procurement/internal/service/GoodsReceipts.java` (`Part` + `List<String> packages`), `GoodsReceiptServiceImpl.java` (`moveToQc`, `inspect`), `procurement/internal/controller/dto/QcDecisionRequest.java`, controller mapping
- Test: `src/test/java/com/stockflow/procurement/GoodsReceiptPackageQcIntegrationTest.java`

**Interfaces:**
- Consumes: Task 4 `moveStockPackage`, `reclassifyStockPackage`; Task 3 `receivingPackagesOf`.
- Produces: `GoodsReceipts.Part(int quantity, String locationCode, String reason, List<String> packages)`, keeping the three-argument constructor (packages empty).

- [ ] **Step 1: Write `GoodsReceiptPackageQcIntegrationTest`** on the demo TABLE (3-step) item with a CARTON unit:
  - `moveToQcMovesCartonsAndLoose` — two cartons + 5 loose → all at HCM-QCA01; each carton's location equals its rows'.
  - `quarantineOneCarton` — `quarantined = Part(0, "HCM-QC01", "dented", [lpn1])`, accepted = rest → carton 1 rows QUARANTINE at HCM-QC01 in the carton, unopened; `QcInspection` QUARANTINE quantity = carton 1's count.
  - `looseAndCartonTogether` — quarantine 3 loose + carton 2, reject 2 loose → sums hold, statuses right.
  - `partsMustAddUp` → `QC_QUANTITY_MISMATCH`; `looseAboveRemainder` → `QC_QUANTITY_MISMATCH`.
  - `inspectRefusesAnotherLinesCarton` (Review Focus 4) → `STOCK_PACKAGE_NOT_FOUND`; same LPN in both parts → `VALIDATION_FAILED`; a carton moved off the QC area by hand (Task 4 API) before the decision → `STOCK_PACKAGE_WRONG_LOCATION`.
- [ ] **Step 2:** `moveToQc`: for each live carton of the line `moveStockPackage(lpn, qcCode, GOODS_RECEIPT_LINE, line.id(), user)`; `inventory.move` for the loose remainder only when > 0.
- [ ] **Step 3:** `inspect`: validate named cartons against `receivingPackagesOf(List.of(lineId))` — not the line's → `STOCK_PACKAGE_NOT_FOUND`; not IN_STOCK → `INVALID_STOCK_PACKAGE_TRANSITION`; not at the line's QC location → `STOCK_PACKAGE_WRONG_LOCATION`; in both parts → `VALIDATION_FAILED`; quantities per part = loose + Σ carton counts; BR-08 as today on those totals, plus loose parts ≤ loose remainder; `reclassifyStockPackage` per named carton with the part's `QcInspection` id as reference; loose parts through `reclassify` only when > 0. Update the QC javadoc.
- [ ] **Step 4:** `QcDecisionRequest.Part` + `@Size(max = 500) List<@NotBlank @Size(max = 30) String> packages`; `@Operation` text names the new field.
- [ ] **Step 5:** `test-compile`, `verify.py` → clean.
- [ ] **Step 6:** Commit `feat(procurement): QC quarantines and rejects whole cartons (SCRUM-473)`.

### Task 7: Carton labels endpoint

**Files:**
- Create: `inventory/internal/service/StockPackageLabels.java` (read-only service: selection → label data), `inventory/internal/service/StockPackageLabelPdfRenderer.java`, `inventory/internal/controller/StockPackageController.java`, `inventory/internal/controller/dto/StockPackageLabelRequest.java`
- Modify: `inventory/internal/controller/InventoryResources.java` (+ `STOCK_PACKAGES = "inventory-stock-packages"`), `common/error/ErrorCode.java`, both bundles
- Test: `src/test/java/com/stockflow/inventory/internal/service/StockPackageLabelPdfRendererTest.java`, `src/test/java/com/stockflow/inventory/internal/controller/StockPackageLabelHttpTest.java`

**Interfaces:**
- Consumes: Task 1 `LabelDrawing`, `LabelFormat`, `LabelFonts`, `LabelDecoding` (tests); Task 3 cartons.
- Produces: `POST /api/v1/inventory/stock-packages/labels`, body `StockPackageLabelRequest(UUID receiptLineId, List<String> lpns, LabelFormat format)` — exactly one of `receiptLineId`, `lpns`; `@PermissionResource(code = InventoryResources.STOCK_PACKAGES)` on the controller, `@RequiresPermission(resource = STOCK_PACKAGES, action = Action.CREATE)` on the public handler.

- [ ] **Step 1: Write the renderer test:** one page per label on `THERMAL_ROLL`; QR and Code 128 decode (via `LabelDecoding`) to the LPN; the text contains the SKU and `980 / 1000 CARTON`.
- [ ] **Step 2: Write the HTTP test:** warehouse staff → 200 `application/pdf` with as many pages as live cartons of the line; INVENTORY_PLANNER → 403; a VOID LPN → 409 `INVALID_STOCK_PACKAGE_TRANSITION`; 501 LPNs → 422 `STOCK_PACKAGE_LABELS_TOO_MANY`; neither field → 422 `STOCK_PACKAGE_LABELS_EMPTY`; a carton in another warehouse than the caller's scope → 403.
- [ ] **Step 3:** `StockPackageLabels.sheetFor(...)` in a read-only transaction returns label records (LPN, SKU, item name, quantity, nominal, unit code); the controller renders after it returns, as `LocationLabelController` does. New codes `STOCK_PACKAGE_LABELS_EMPTY("Choose a receipt line or the cartons to print", 422)`, `STOCK_PACKAGE_LABELS_TOO_MANY("At most 500 carton labels at a time", 422)`.
- [ ] **Step 4:** Bundles; `test-compile`, `verify.py` → clean.
- [ ] **Step 5:** Commit `feat(inventory): print carton labels (SCRUM-473)`.

### Task 8: Docs and the one full test run

**Files:**
- Modify: `docs/business-design/04c-all-flows.md` (receiving flow: cartons labelled while counting, confirmed IN_STOCK, QC by whole carton — Vietnamese), `CLAUDE.md` §4 (a "Receiving into cartons" paragraph after "Cycle count": `replaceLines` → `countReceivingPackages`, `receive` into cartons, `moveStockPackage` / `reclassifyStockPackage`, lock order)

- [ ] **Step 1:** Write the two doc changes.
- [ ] **Step 2:** `python -X utf8 tools/verify.py` → `all static checks passed`.
- [ ] **Step 3:** Focused run, Docker up: `mvn -o test -Djacoco.skip=true -Dtest=ModularityTest,ArchitectureTest,MessageBundleTest,ReceivingPackagePlanTest,StockItemTest,ReceivingPackagesIntegrationTest,StockPackageMoveIntegrationTest,GoodsReceiptPackageIntegrationTest,GoodsReceiptPackageQcIntegrationTest,StockPackageLabelPdfRendererTest,StockPackageLabelHttpTest,GoodsReceiptIntegrationTest,SubcontractPurchasingIntegrationTest,PrintRunIntegrationTest,LocationLabelPdfRendererTest,LocationLabelControllerHttpTest` → 0 failures. Fix and rerun until green.
- [ ] **Step 4:** Full `mvn verify` in the background (never edit sources while it runs) → 0 failures; record the test count.
- [ ] **Step 5:** Commit `docs: receiving into cartons in the flows and working notes (SCRUM-473)`; report results to the owner before any push.
