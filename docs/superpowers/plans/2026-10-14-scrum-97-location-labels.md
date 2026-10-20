# SCRUM-97 Location Barcode Labels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /api/v1/warehouses/{warehouseId}/location-labels` returns a PDF of Code 128 + QR labels
for a shelf (optionally some levels), a zone, a list of codes or a whole warehouse, in `A4_SHEET` or
`THERMAL_ROLL` format.

**Architecture:** All inside module `warehouse`, nothing in `api/`. A read-only service resolves the
selection into `LocationLabel` rows (two JPQL projections) and applies the "on the layout" rule; the
controller then calls a renderer (ZXing + PDFBox, fonts embedded) **outside** the transaction.
`GlobalExceptionHandler` is fixed so that errors stay JSON when the client asked for a PDF.

**Tech Stack:** Java 21, Spring Boot 3.5, JPA/JPQL projections, `com.google.zxing:core`,
`org.apache.pdfbox:pdfbox` 3.0.x, Noto Sans TTF (OFL), JUnit 5 + Testcontainers.

**Spec:** `docs/superpowers/specs/2026-10-14-scrum-97-location-labels-design.md` — read it first; §
numbers below refer to it.

## Global Constraints

- No migration, no file in `db/migration` (CLAUDE.md §7; schema owner is Tú).
- Nothing added to `warehouse/api`; `ModularityTest` must pass unchanged.
- Permission: existing `WarehouseResources.LOCATIONS` + `Action.READ`, on a **public** handler method.
- English in all Java and javadoc; Vietnamese only in `messages_vi.properties` and test data.
- Barcode and QR payload = `location_code` exactly, nothing added (D3).
- Limit `2000` labels per request; `locationCodes` 1–2000 entries, each `[A-Za-z0-9-]{1,64}`.
- Label geometry (mm): `A4_SHEET` page 210×297, label 70×37, padding 3, grid 3×8, top margin 0.5,
  QR 16; `THERMAL_ROLL` page = label 60×40, padding 2.5, grid 1×1, QR 15.
- Code 128 module width: largest of 0.375 / 0.25 / 0.19 mm that fits, else scaled to fit; quiet zone
  10 modules each side; bar height ≥ 8 mm. QR: error correction M, quiet zone 4 modules.
- Code text: Noto Sans Bold, 16 pt shrinking in 0.5 pt steps to 8 pt; context lines 6.5 pt Regular.
- Filename `location-labels-{PREFIX}-{yyyyMMdd-HHmm}.pdf`, time from the `Clock` bean in
  `BusinessCalendar.ZONE`.
- Never `git commit`/`git push` without the owner's go-ahead (CLAUDE.md §7). Commit steps below are
  run only after the owner approved committing for this branch. No tool attribution in messages
  (`.claude/skills/git-commits`).
- Downloading the font files needs the owner's explicit go-ahead (Task 2, Step 1).
- During tasks run only focused tests + `ModularityTest`, `ArchitectureTest`,
  `python -X utf8 tools/verify.py`; one full `mvn verify` in Task 4.

## Review Focus

1. **Selector on another warehouse's shelf/zone** — a valid `shelfId` of warehouse B posted to
   warehouse A must be `404 SHELF_NOT_FOUND`, never labels of B. Test in Task 3.
2. **Case and duplicates in `locationCodes`** — `["hcm-a01-1-a", "HCM-A01-1-A"]` prints one label, not
   two, and not a 404. Test in Task 3.
3. **Error under `Accept: application/pdf`** — must be a JSON envelope with the real status, not 406/500.
   Tests in Task 1 (handler) and Task 4 (real endpoint).
4. **A code too long for Code 128 at 0.19 mm** — the PDF still renders and the QR still decodes. Test in
   Task 2.
5. **Inactive warehouse** — labels still print (D4), while `usable` would say false. Test in Task 3.

---

## File Structure

| File | Responsibility |
|---|---|
| `common/web/GlobalExceptionHandler.java` (modify) | Error bodies always `application/json` |
| `pom.xml` (modify) | `zxing.version`, `pdfbox.version` properties + the two dependencies |
| `src/main/resources/fonts/NotoSans-Regular.ttf`, `NotoSans-Bold.ttf`, `OFL.txt` (new) | Embedded font + licence |
| `warehouse/internal/service/LabelFormat.java` | The two formats and their geometry |
| `warehouse/internal/service/LocationLabel.java`, `LocationLabelSheet.java`, `LabelWords.java` | Render input |
| `warehouse/internal/service/LabelFonts.java` | Loads the TTF bytes once |
| `warehouse/internal/service/LocationLabelPdfRenderer.java` | ZXing + PDFBox, nothing else |
| `warehouse/internal/domain/LabelSelection.java` | Sealed selection value |
| `warehouse/internal/domain/EffectiveStatus.java` (modify) | `isOnLayout` (D4) |
| `warehouse/internal/repository/LocationLabelRepository.java` | Read port + `LabelRow` |
| `warehouse/internal/repository/LayoutReadRepositoryAdapter.java` (modify) | Implements the port |
| `warehouse/internal/service/LocationLabelService.java`, `LocationLabelServiceImpl.java` | Selection → sheet, every error case |
| `common/error/ErrorCode.java` + `i18n/messages*.properties` (modify) | 3 codes + label words |
| `warehouse/internal/controller/LocationLabelController.java`, `dto/LocationLabelRequest.java` | HTTP edge |

---

### Task 1: Error responses stay JSON whatever the `Accept` header

**Files:**
- Modify: `src/main/java/com/stockflow/common/web/GlobalExceptionHandler.java` (the two
  `ResponseEntity.status(...)` builders, ~lines 415 and 475)
- Test: `src/test/java/com/stockflow/common/web/ErrorResponseContentTypeTest.java` (new)

**Interfaces:**
- Produces: every `ResponseEntity<ApiResponse<…>>` from the handler carries
  `Content-Type: application/json`. Task 4 relies on it.

- [ ] **Step 1: Write the failing test**

Standalone MockMvc, `setControllerAdvice(new GlobalExceptionHandler(new Messages(source), source))`
(same construction as `DesignArtifactHttpTest`), with a nested test controller:
`@PostMapping(value = "/pdf", produces = MediaType.APPLICATION_PDF_VALUE)` that throws
`new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "x")`, and a second `/pdf-missing-body` taking a
`@Valid @RequestBody` record with one `@NotNull` field.

```java
@Test void aBusinessErrorUnderAcceptPdfIsAJsonEnvelope() {
    mvc.perform(post("/pdf").accept(MediaType.APPLICATION_PDF))
       .andExpect(status().isNotFound())
       .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
       .andExpect(jsonPath("$.errorCode").value("LOCATION_NOT_FOUND"));
}
@Test void aFrameworkErrorUnderAcceptPdfIsAJsonEnvelope() { /* POST /pdf-missing-body, body "{}", Accept pdf → 400, JSON, errorCode VALIDATION_FAILED */ }
@Test void aJsonClientStillGetsJson() { /* same as first with Accept application/json → 404 JSON */ }
```

- [ ] **Step 2: Run it — expect the two Accept-pdf tests to FAIL** (406 or 500 instead of 404/400)

Run: `mvn -q test -Dtest=ErrorResponseContentTypeTest`

- [ ] **Step 3: Add `.contentType(MediaType.APPLICATION_JSON)` to both builders**

Add one sentence to the class javadoc saying why: a preset concrete content type skips `Accept`
negotiation, so a client that asked for a binary (PDF) still receives the envelope.

- [ ] **Step 4: Run** `mvn -q test -Dtest='ErrorResponseContentTypeTest,BusinessFieldErrorsTest,IntegrityViolationMappingTest'` — expect PASS

- [ ] **Step 5: Commit** — `fix(common): keep error responses JSON whatever the Accept header (SCRUM-97)`

---

### Task 2: The label PDF renderer

**Files:**
- Modify: `pom.xml`
- Create: `src/main/resources/fonts/NotoSans-Regular.ttf`, `NotoSans-Bold.ttf`, `OFL.txt`
- Create: `src/main/java/com/stockflow/warehouse/internal/service/{LabelFormat,LocationLabel,LocationLabelSheet,LabelWords,LabelFonts,LocationLabelPdfRenderer}.java`
- Test: `src/test/java/com/stockflow/warehouse/internal/service/LocationLabelPdfRendererTest.java`,
  `src/test/java/com/stockflow/warehouse/internal/service/LabelDecoding.java` (test helper)

**Interfaces:**
- Consumes: domain enums `StorageLocationKind`, `StorageClass`.
- Produces:
  ```java
  enum LabelFormat { A4_SHEET, THERMAL_ROLL;
      double pageWidthMm(); double pageHeightMm(); double labelWidthMm(); double labelHeightMm();
      double paddingMm(); int columns(); int rows(); double topMarginMm(); double qrSideMm();
      int labelsPerPage(); }                                   // values: Global Constraints
  record LocationLabel(String code, StorageLocationKind kind, String shelfCode, Integer levelIndex,
                       String binCode, String areaName, StorageClass storageClass) {}
  record LocationLabelSheet(String warehousePrefix, String warehouseName, List<LocationLabel> labels) {}
  record LabelWords(String shelf, String level, String bin) {}
  @Component class LabelFonts { byte[] regular(); byte[] bold(); }   // read once in the constructor
  @Component class LocationLabelPdfRenderer {
      LocationLabelPdfRenderer(LabelFonts fonts);
      public byte[] render(LocationLabelSheet sheet, LabelFormat format, LabelWords words); }
  ```
  `LabelFormat` is public (the controller DTO uses it); the records are public, package `internal.service`.

- [ ] **Step 1: Fonts — ask the owner before downloading**, then fetch into `src/main/resources/fonts/`:
  `https://github.com/notofonts/notofonts.github.io/raw/main/fonts/NotoSans/hinted/ttf/NotoSans-Regular.ttf`,
  the same path with `NotoSans-Bold.ttf`, and `OFL.txt` from
  `https://github.com/notofonts/latin-greek-cyrillic/raw/main/OFL.txt`.
  Check: each `.ttf` starts with bytes `00 01 00 00` and is > 300 KB (static, not a variable font).

- [ ] **Step 2: pom** — properties `<zxing.version>3.5.3</zxing.version>` and `<pdfbox.version>` set to
  the newest `3.0.x` on Maven Central (confirm with `mvn -q dependency:resolve`); dependencies
  `com.google.zxing:core` and `org.apache.pdfbox:pdfbox` in compile scope, placed after
  `metadata-extractor`, under the "Versions NOT managed by the Spring Boot BOM" comment block.

- [ ] **Step 3: Write the failing tests**

`LabelDecoding` (test helper): `static String decode(BufferedImage image, BarcodeFormat format)` —
pixels via `getRGB`, `RGBLuminanceSource`, `HybridBinarizer`, `MultiFormatReader` with
`POSSIBLE_FORMATS = [format]` and `TRY_HARDER`; and
`static BufferedImage cell(BufferedImage page, LabelFormat f, int index, float dpi)` cropping label
`index` (row-major) from the grid geometry.

Fixture: sheet `("HCM", "Kho Hồ Chí Minh", …)` with bins `HCM-A01-1-A`…; words `("Kệ", "Tầng", "Bin")`.
Render pages with `new PDFRenderer(doc).renderImageWithDPI(i, 300, ImageType.GRAY)`.

```java
@Test void a4PutsTwentyFourLabelsOnAPage()      // 25 labels → 2 pages, mediaBox 595.28 × 841.89 pt (±0.5)
@Test void thermalPutsOneLabelOnAPage()         // 3 labels → 3 pages, mediaBox 170.08 × 113.39 pt (±0.5)
@Test void everyLabelDecodesToItsCodeInBothSymbologies()  // A4, 5 labels: CODE_128 and QR_CODE of cell i == code i
@Test void thermalLabelDecodesToo()             // 1 label, both symbologies
@Test void vietnameseTextIsEmbeddedAndExtractable() // PDFTextStripper text contains "Kho Hồ Chí Minh" and "Kệ A01 · Tầng 1 · Bin A"
@Test void anAreaLabelShowsTheAreaName()        // AREA label "HCM-RCV01", areaName "Khu nhận hàng" → text contains it, no "Kệ"
@Test void aCodeTooLongForCode128StillRendersAndItsQrDecodes() // 55-char code "ABCDEFGHIJ-ABCDEFGHIJKLMNOPQRST-99-ABCDEFGHIJKLMNOPQRST" → QR decodes to it
@Test void anEmptySheetIsRefused()              // IllegalArgumentException: the service never sends one
```

- [ ] **Step 4: Run** `mvn -q test -Dtest=LocationLabelPdfRendererTest` — expect FAIL (classes missing)

- [ ] **Step 5: Implement**

Renderer decisions the tests do not fix:
- mm → pt is `mm * 72 / 25.4`; PDF origin is bottom-left, cells are laid out row-major from the top.
- Per label: code text top-left (Bold, fit width = inner width − QR side − 2 mm); under it line 1 =
  warehouse name, line 2 = `"{shelf} {shelfCode} · {level} {levelIndex} · {bin} {binCode} · {storageClass}"`
  for a bin, `"{areaName} · {storageClass}"` for an area; a context line that does not fit is cut and
  ends with `…`. QR top-right, side `qrSideMm`. Code 128 across the bottom, height = inner height −
  QR side − 1.5 mm.
- Code 128 modules from `new Code128Writer().encode(code)` (a `boolean[]`); draw each run of `true`
  as one `addRect`, then one `fill()`.
- QR from `Encoder.encode(code, ErrorCorrectionLevel.M, Map.of(EncodeHintType.CHARACTER_SET, "UTF-8")).getMatrix()`;
  merge horizontal runs of dark modules into one rectangle each.
- Fonts: `PDType0Font.load(doc, new ByteArrayInputStream(bytes), true)` per document; `LabelFonts`
  reads both TTFs with `ClassPathResource` in its constructor and fails startup if one is missing.
- An `IOException` becomes `UncheckedIOException` (a server fault, mapped to 500).

- [ ] **Step 6: Run** `mvn -q test -Dtest=LocationLabelPdfRendererTest` — expect PASS; then
  `python -X utf8 tools/verify.py` and `mvn -q test -Dtest='ArchitectureTest,ModularityTest'` — PASS

- [ ] **Step 7: Commit** — `feat(warehouse): render location labels as a PDF (SCRUM-97)` (pom, fonts, OFL.txt, renderer, tests)

---

### Task 3: Resolving a selection into labels

**Files:**
- Create: `warehouse/internal/domain/LabelSelection.java`,
  `warehouse/internal/repository/LocationLabelRepository.java`,
  `warehouse/internal/service/LocationLabelService.java`, `LocationLabelServiceImpl.java`
- Modify: `warehouse/internal/domain/EffectiveStatus.java`,
  `warehouse/internal/repository/LayoutReadRepositoryAdapter.java`,
  `common/error/ErrorCode.java`, `src/main/resources/i18n/messages.properties`, `messages_vi.properties`
- Test: `warehouse/internal/domain/EffectiveStatusTest.java` (extend),
  `warehouse/internal/service/LocationLabelServiceIntegrationTest.java` (new),
  `warehouse/internal/service/LocationLabelServiceTest.java` (new)

**Interfaces:**
- Consumes: `LocationLabel`, `LocationLabelSheet` (Task 2);
  `LocationLookupRepository.findWarehouseHeader(UUID)` (existing).
- Produces:
  ```java
  // domain
  public sealed interface LabelSelection {
      record WholeWarehouse() implements LabelSelection {}
      record OfShelf(UUID shelfId, Set<Integer> levelIndexes) implements LabelSelection {}  // empty set = all levels
      record OfZone(UUID zoneId) implements LabelSelection {}
      record OfCodes(Set<String> codes) implements LabelSelection {}   // normalised: stripped, upper-cased (Locale.ROOT), deduplicated
  }
  // EffectiveStatus
  public static boolean isOnLayout(LocationStatus shelfOrNull, LocationStatus location) // false if either is INACTIVE; warehouse ignored
  // repository port
  public interface LocationLabelRepository {
      boolean shelfExists(UUID warehouseId, UUID shelfId);
      Set<Integer> levelIndexesOf(UUID shelfId);
      boolean zoneExists(UUID warehouseId, UUID zoneId);
      List<LabelRow> findBinRows(UUID warehouseId, LabelSelection selection);   // ordered shelf code, level index, bin code
      List<LabelRow> findAreaRows(UUID warehouseId, LabelSelection selection);  // ordered area code; empty for OfShelf/OfZone
      record LabelRow(String locationCode, StorageLocationKind kind, StorageClass storageClass,
                      LocationStatus status, LocationStatus shelfStatus, String shelfCode,
                      Integer levelIndex, String binCode, String areaName) {}
  }
  // service
  public interface LocationLabelService {
      int MAX_LABELS = 2000;
      LocationLabelSheet sheetFor(UUID warehouseId, LabelSelection selection);
  }
  // ErrorCode
  LOCATION_INACTIVE("This location is off the layout", 409),
  LOCATION_LABELS_EMPTY("No storage location matches this selection", 422),
  LOCATION_LABELS_TOO_MANY("Too many labels for one request", 422),
  ```

- [ ] **Step 1: Write the failing tests**

`EffectiveStatusTest`: `isOnLayout(null, ACTIVE)` true; `(ACTIVE, BLOCKED)` true;
`(MAINTENANCE, MAINTENANCE)` true; `(INACTIVE, ACTIVE)` false; `(ACTIVE, INACTIVE)` false.

`LocationLabelServiceIntegrationTest` — `@IntegrationTest @Import(PostgresContainer.class) @Transactional`,
demo warehouse `DemoData.WAREHOUSE_HCM` (shelves A01, A02 in zone A, B01 in zone B; levels 1–2; bins
A, B; areas RCV01, QC01, PACK01, DSP01 and the `NON_STORAGE` OFFICE). Status changes in a test are
plain `jdbc.update` inside the rolled-back transaction, then `entityManager.flush(); clear()`.
Other test classes may commit extra shelves to HCM, so whole-warehouse assertions use
`containsSubsequence`, never an exact list.

```java
@Test void aShelfGivesItsBinsInWalkingOrder()      // OfShelf(A01, {}) → codes [HCM-A01-1-A, HCM-A01-1-B, HCM-A01-2-A, HCM-A01-2-B]; prefix "HCM", name "Kho Hồ Chí Minh"
@Test void levelsNarrowAShelf()                    // OfShelf(A01, {2}) → [HCM-A01-2-A, HCM-A01-2-B]
@Test void aZoneGivesTheBinsOfItsShelvesAndNoArea() // OfZone(ZONE_HCM_A) → 8 codes, A01 before A02, none starts with "HCM-RCV"
@Test void theWholeWarehouseGivesBinsThenAreas()   // containsSubsequence(HCM-A01-1-A … HCM-B01-2-B, HCM-DSP01, HCM-PACK01, HCM-QC01, HCM-RCV01); no OFFICE
@Test void codesAreMatchedIgnoringCaseAndPrintedOnce() // OfCodes(normalised {"HCM-A01-1-A"} from "hcm-a01-1-a" and "HCM-A01-1-A") → 1 label
@Test void codesComeOutInWalkingOrderNotRequestOrder() // {"HCM-RCV01","HCM-B01-1-A","HCM-A01-1-A"} → [HCM-A01-1-A, HCM-B01-1-A, HCM-RCV01]
@Test void aBinLabelCarriesItsPath()               // HCM-A01-2-B → shelfCode A01, levelIndex 2, binCode B, kind BIN, storageClass OVERSIZE
@Test void anInactiveBinIsSkippedInABroadSelection()   // set location HCM-A01-1-A INACTIVE → OfShelf(A01) has 3 codes
@Test void binsOnAnInactiveShelfAreSkipped()       // shelf A02 INACTIVE → OfZone(A) has only A01's 4
@Test void blockedAndMaintenanceArePrinted()       // A01-1-A BLOCKED, shelf A01 MAINTENANCE → OfShelf(A01) still 4
@Test void anInactiveWarehouseStillPrints()        // warehouse HCM INACTIVE → OfShelf(A01) still 4
@Test void aNamedInactiveCodeIsRefused()           // OfCodes({HCM-A01-1-A}) after it went INACTIVE → BusinessException LOCATION_INACTIVE, message contains "HCM-A01-1-A"
@Test void unknownCodesAre404WithTheirNames()      // {"HCM-A01-1-A","HCM-ZZ9-1-A"} → LOCATION_NOT_FOUND, message contains "HCM-ZZ9-1-A"
@Test void aCodeOfAnotherWarehouseIs404()          // insert a 2nd warehouse "HN" + area RCV01 via jdbc → posting its code to HCM → LOCATION_NOT_FOUND
@Test void aShelfOfAnotherWarehouseIs404()         // shelf of the 2nd warehouse → SHELF_NOT_FOUND
@Test void aZoneOfAnotherWarehouseIs404()          // → ZONE_NOT_FOUND
@Test void anUnknownLevelIs404()                   // OfShelf(A01, {1, 3}) → SHELF_LEVEL_NOT_FOUND
@Test void anUnknownWarehouseIs404()               // → WAREHOUSE_NOT_FOUND
@Test void anEmptySelectionIs422()                 // all four bins of A01 INACTIVE → OfShelf(A01) → LOCATION_LABELS_EMPTY
@Test void theQueryCountDoesNotGrowWithTheLabels() // Hibernate Statistics (as in jpa-orm-review): OfShelf(A01) and whole warehouse execute the same number of statements, ≤ 5
```

`LocationLabelServiceTest` (new, unit, mocked `LocationLabelRepository` and `LocationLookupRepository`) —
the limit, because 2,001 real bins would have to pass the map's geometry triggers:

```java
@Test void moreThanTheLimitIs422()   // findBinRows returns 2001 ACTIVE rows → LOCATION_LABELS_TOO_MANY, message contains "2001" and "2000"
@Test void exactlyTheLimitPasses()   // 2000 rows → sheet of 2000
```

For the cross-warehouse fixtures, look at how `LayoutReadIntegrationTest` / `ShelfLayoutServiceIntegrationTest`
insert map rows; storage locations must be owned by a bin or area by commit, which a rolled-back
transaction never reaches.

- [ ] **Step 2: Run** `mvn -q test -Dtest='EffectiveStatusTest,LocationLabelServiceTest,LocationLabelServiceIntegrationTest'` — expect FAIL (compile)

- [ ] **Step 3: Implement**

- `EffectiveStatus.isOnLayout` with javadoc tying it to spec D4 and saying why it differs from `isUsable`.
- `LabelSelection.OfCodes` normalises in its compact constructor (`strip().toUpperCase(Locale.ROOT)`,
  `LinkedHashSet`, unmodifiable). `OfShelf` copies `levelIndexes` to an unmodifiable set, null → empty.
- Adapter: two JPQL record projections modelled on `findBins` / `findAreas`.
  Bins: `from BinJpaEntity b join b.level l join l.shelf s join b.location loc where s.warehouseId = :w`
  plus, by selection, `and s.id = :shelf [and l.levelIndex in :levels]` / `and s.zoneId = :zone` /
  `and loc.locationCode in :codes`; `order by s.code, l.levelIndex, b.code`.
  Areas: `from AreaJpaEntity a join a.location loc where a.warehouseId = :w` [`and loc.locationCode in :codes`],
  `order by a.code`; returns `List.of()` for `OfShelf` and `OfZone` without querying. Use a `switch`
  over the sealed type. Existence checks are `select count(…) > 0` JPQL.
- `LocationLabelServiceImpl` (`@Service @Transactional(readOnly = true)`, package-private class):
  1. warehouse header or `WAREHOUSE_NOT_FOUND`;
  2. `OfShelf`: `shelfExists` or `SHELF_NOT_FOUND`; requested levels ⊆ `levelIndexesOf` or
     `SHELF_LEVEL_NOT_FOUND`. `OfZone`: `zoneExists` or `ZONE_NOT_FOUND`;
  3. rows = bin rows + area rows;
  4. `OfCodes`: requested − found → `LOCATION_NOT_FOUND` listing up to 10, sorted; found but not
     `isOnLayout` → `LOCATION_INACTIVE` listing up to 10. Other selections: drop rows not on layout;
  5. empty → `LOCATION_LABELS_EMPTY`; `> MAX_LABELS` → `LOCATION_LABELS_TOO_MANY` with message
     `"%d labels requested; at most %d per request - print by zone or shelf"`;
  6. map rows to `LocationLabel`, return the sheet.
- `ErrorCode`: the three constants next to `LOCATION_NOT_FOUND` / `LOCATION_NOT_USABLE`.
  `messages.properties`: `error.LOCATION_INACTIVE=This location is off the layout`,
  `error.LOCATION_LABELS_EMPTY=No storage location matches this selection`,
  `error.LOCATION_LABELS_TOO_MANY=Too many labels for one request`.
  `messages_vi.properties`: `Vị trí này đã ngừng sử dụng`, `Không có vị trí nào khớp lựa chọn để in nhãn`,
  `Số nhãn vượt quá giới hạn một lần in`.

- [ ] **Step 4: Run** `mvn -q test -Dtest='EffectiveStatusTest,LocationLabelServiceTest,LocationLabelServiceIntegrationTest,MessageBundleTest'`
  — expect PASS; then `ArchitectureTest`, `ModularityTest`, `python -X utf8 tools/verify.py` — PASS

- [ ] **Step 5: Commit** — `feat(warehouse): resolve a label selection into locations (SCRUM-97)`

---

### Task 4: The endpoint, security, OpenAPI and the full build

**Files:**
- Create: `warehouse/internal/controller/LocationLabelController.java`,
  `warehouse/internal/controller/dto/LocationLabelRequest.java`
- Modify: `src/main/resources/i18n/messages.properties`, `messages_vi.properties` (label words)
- Test: `warehouse/internal/controller/LocationLabelControllerHttpTest.java`,
  `src/test/java/com/stockflow/warehouse/LocationLabelSecurityIntegrationTest.java`,
  `warehouse/internal/controller/WarehouseOpenApiIntegrationTest.java` (extend)

**Interfaces:**
- Consumes: `LocationLabelService.sheetFor` (Task 3), `LocationLabelPdfRenderer.render`, `LabelFormat`,
  `LabelWords` (Task 2), `LabelSelection` (Task 3), `Messages.get(key, fallback)`, the `Clock` bean.
- Produces:
  ```java
  public record LocationLabelRequest(@NotNull LabelFormat format, UUID shelfId,
          @Size(min = 1) Set<@Min(1) @Max(99) Integer> levelIndexes, UUID zoneId,
          @Size(min = 1, max = 2000) List<@NotNull @Pattern(regexp = "[A-Za-z0-9-]{1,64}") String> locationCodes) {
      @AssertTrue(message = "At most one of shelfId, zoneId, locationCodes") boolean isOneSelector();
      @AssertTrue(message = "levelIndexes needs shelfId") boolean isLevelsWithShelf();
      LabelSelection toSelection();   // none → WholeWarehouse
  }
  @RestController @RequestMapping("/api/v1") class LocationLabelController {
      @PostMapping(value = "/warehouses/{warehouseId}/location-labels", produces = MediaType.APPLICATION_PDF_VALUE)
      @RequiresPermission(resource = WarehouseResources.LOCATIONS, action = Action.READ)
      public ResponseEntity<byte[]> labels(@PathVariable UUID warehouseId, @Valid @RequestBody LocationLabelRequest request);
  }
  ```
  Message keys: `warehouse.label.shelf` = `Shelf` / `Kệ`, `warehouse.label.level` = `Level` / `Tầng`,
  `warehouse.label.bin` = `Bin` / `Bin`.

- [ ] **Step 1: Write the failing tests**

`LocationLabelControllerHttpTest` — standalone MockMvc with mocked service and renderer, a fixed
`Clock` at `2026-10-14T08:30:00Z`, message converters `ByteArrayHttpMessageConverter` +
`MappingJackson2HttpMessageConverter`, and `setControllerAdvice(new GlobalExceptionHandler(...))`.

```java
@Test void returnsThePdfAsAnAttachment()
    // 200; Content-Type application/pdf; Content-Disposition attachment; filename="location-labels-HCM-20261014-1530.pdf"; body = renderer bytes
@Test void noSelectorMeansTheWholeWarehouse()         // verify(service).sheetFor(W, new LabelSelection.WholeWarehouse())
@Test void codesAreNormalisedBeforeTheService()        // ["hcm-a01-1-a"] → OfCodes({"HCM-A01-1-A"})
@Test void twoSelectorsAre400()                        // shelfId + zoneId → 400 VALIDATION_FAILED, JSON
@Test void levelsWithoutAShelfAre400()
@Test void aMissingFormatIs400()
@Test void aBadCodeIs400()                             // "HCM_A01" → 400
@Test void aServiceErrorUnderAcceptPdfIsJson()         // service throws LOCATION_NOT_FOUND; Accept application/pdf → 404, JSON, $.errorCode
```

`LocationLabelSecurityIntegrationTest` — `@SecuredIntegrationTest`, same token/role-matrix pattern as
`InventoryCatalogSecurityIntegrationTest`: anonymous → 401; role granted only
`warehouse-warehouses:READ` → 403; granted `warehouse-locations:READ` with `{"format":"A4_SHEET","shelfId":"<A01>"}`
→ 200, `application/pdf`, body starts with `%PDF-`.

`WarehouseOpenApiIntegrationTest` — following the file's existing assertions: the path
`/api/v1/warehouses/{warehouseId}/location-labels` has a `post` whose `200` response content is
`application/pdf`.

- [ ] **Step 2: Run** `mvn -q test -Dtest='LocationLabelControllerHttpTest,LocationLabelSecurityIntegrationTest,WarehouseOpenApiIntegrationTest'` — expect FAIL

- [ ] **Step 3: Implement**

Controller: `sheet = service.sheetFor(...)`, then `renderer.render(sheet, request.format(), words)`
with words from `Messages.get` — the render is outside the service's transaction (javadoc says so and
why). Headers via `ContentDisposition.attachment().filename(...)`. `@Operation` documents the binary
200 (`@ApiResponse(responseCode = "200", content = @Content(mediaType = "application/pdf",
schema = @Schema(type = "string", format = "binary")))`) and that errors are the JSON envelope; tell
clients to send `Accept: application/pdf, application/json`. The OpenAPI `@ApiResponse` clashes with
`common`'s `ApiResponse` by simple name — use the fully qualified annotation name.

- [ ] **Step 4: Run** the three tests — expect PASS

- [ ] **Step 5: Full build**

Run: `python -X utf8 tools/verify.py` then `mvn verify` (≈11–16 min). Expected: BUILD SUCCESS. Any
failure outside this branch's files is reported to the owner, not fixed silently.

- [ ] **Step 6: Commit** — `feat(warehouse): print location labels over HTTP (SCRUM-97)`

- [ ] **Step 7: Hand-off notes for the owner** (not code): Jira SCRUM-97 description from spec §1–§2;
  FE request shape and `responseType: 'blob'`; no coordination with Tú needed.
