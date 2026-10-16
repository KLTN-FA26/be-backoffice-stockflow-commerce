# SCRUM-97 Location barcode labels — design

*2026-10-14 · StockFlowCommerce (FA26SE029) · owner: Phương*

Jira: [SCRUM-97](https://minh7n3.atlassian.net/browse/SCRUM-97) "Location barcode generation & printing"
(WBS 3.5.1.3), a subtask of [SCRUM-89](https://minh7n3.atlassian.net/browse/SCRUM-89) Warehouse Map &
Location Master (Done). The ticket has no description; this document is the requirement.

## 1. What the ticket means on `develop` today

Business source: `kltn-docs/docs/warehouse/06-warehouse-map-slotting` lists "the full location code
(location barcode) for every bin and area" as an output of module 06, consumed by 05 Putaway, 07 Picking
and 11 Transfer for scan confirmation. BR-13 forbids changing a code "because it is already printed on the
label". Nothing in the docs fixes the label size, the symbology or the printer.

| Half of the ticket | State on `develop` |
|---|---|
| **Generation** | **Done in SCRUM-89.** `location_code` is assembled when a bin or area is created (BR-10: `HCM-A01-2-B`, `HCM-QC01`), unique system-wide, immutable (BR-13, `tg_storage_location_immutable`), alphabet `[A-Z0-9-]`. |
| Scanning it | Done. Pick confirmation (SCRUM-274) and put-back take `locationCode`; `WarehouseService.findLocation(code)` and `GET /api/v1/locations/{code}` match it case-insensitively. |
| **Printing** | **Nothing.** No barcode or PDF library in the backend or the frontend; no endpoint that lists the locations to print. |

So the barcode payload **is** `location_code` — no new code, no new column, no migration. What this
ticket builds is the label: a PDF the warehouse prints and sticks on shelves and floor areas.

## 2. Scope

**In scope**
1. `POST /api/v1/warehouses/{warehouseId}/location-labels` returning `application/pdf`.
2. Four ways to choose the locations: a shelf (optionally some of its levels), a zone, a list of codes,
   or the whole warehouse.
3. Two sheet formats: `A4_SHEET` (24 labels per A4 page) and `THERMAL_ROLL` (one label per page).
4. Each label: the code as large text, a QR code, a Code 128 barcode, and a small line of context.
5. Error responses stay JSON when the client asked for a PDF (§7).

**Out of scope**
- Recording that a label was printed (needs a column; the schema owner, Tú, has not planned one).
- SKU / product labels, shipping labels — other tickets; §8 says where the code would be shared.
- The FE screen — FE only calls the endpoint and opens or prints the returned blob.
- Any migration.

## 3. Decisions

### D1. The backend renders a PDF
Chosen over "FE draws with JsBarcode" and "BE returns one image per code". A PDF prints the same from
every browser and printer driver, sizes are in millimetres rather than CSS pixels, and the rule of which
locations get a label (§5) stays next to the data it depends on. FE work shrinks to one download.

### D2. Libraries: ZXing core + Apache PDFBox 3 + an embedded Noto Sans
- `com.google.zxing:core` (Apache 2.0) encodes Code 128 and QR into a module matrix. Only `core`:
  the `javase` artifact is not needed, tests decode with `RGBLuminanceSource`, which is in `core`.
- `org.apache.pdfbox:pdfbox` 3.0.x (Apache 2.0) draws every bar and QR module as a filled vector
  rectangle, so the output is sharp at 203 dpi (thermal) and 600 dpi (office) alike.
- The 14 standard PDF fonts are WinAnsi only and cannot draw Vietnamese (`Kho Hồ Chí Minh`, `Kệ`,
  `Tầng`). Noto Sans Regular and Bold (SIL OFL 1.1) go in `src/main/resources/fonts/` with their
  `OFL.txt`, loaded once into memory at startup and embedded per document as a subset
  (`PDType0Font.load(doc, stream, true)`), so a PDF carries only the glyphs it uses.

Exact versions are pinned in the plan, checked against Maven Central at implementation time. The font
files must be downloaded once (from the `notofonts` release on GitHub); that download is a step that
needs the owner's go-ahead.

Rejected: OpenPDF (built-in `Barcode128`, but an iText-2-era API and awkward millimetre layout for two
formats); Thymeleaf + openhtmltopdf (heavy, slow, CSS print layout drifts).

### D3. Both symbologies on every label
Code 128 for handheld 1D scanners, which is what warehouses use; a QR code for phone and tablet cameras,
which is what the demo will use. Both encode exactly `location_code` — no prefix, no URL, no checksum
added — because that string is what every scan endpoint already accepts.

### D4. Which locations get a label
A label belongs to a place that is on the layout. **Excluded:**
- a location whose own status is `INACTIVE`;
- a bin whose shelf is `INACTIVE` (the shelf is off the layout, and every bin on it with it).

**Included:** `ACTIVE`, `BLOCKED` and `MAINTENANCE` — these are temporary states of a place that is
physically there, and the label outlives them. **The warehouse's own status is ignored**: labels are
printed while a new warehouse is being fitted out, before it is activated. A `NON_STORAGE` area has no
location and therefore never appears. This is deliberately *not* `StorageLocationView.usable`, which
answers "may stock move here now", a different question.

### D5. Areas have no zone
`warehouse.area` has no `zone_id` (see `AreaJpaEntity`'s javadoc). Selecting by zone therefore yields the
bins of the zone's shelves only. An area's label is printed through `locationCodes` or the whole
warehouse.

## 4. API

```
POST /api/v1/warehouses/{warehouseId}/location-labels
Accept: application/pdf, application/json
Content-Type: application/json
```

```json
{
  "format": "A4_SHEET",
  "shelfId": "0190…",
  "levelIndexes": [1, 2],
  "zoneId": null,
  "locationCodes": null
}
```

| Field | Rule |
|---|---|
| `format` | required, `A4_SHEET` or `THERMAL_ROLL` |
| `shelfId` | optional; the shelf must belong to this warehouse |
| `levelIndexes` | optional, only with `shelfId`; 1–99 each, non-empty when present, duplicates ignored; each must exist on the shelf |
| `zoneId` | optional; the zone must belong to this warehouse |
| `locationCodes` | optional; 1–2000 entries, each `[A-Za-z0-9-]{1,64}`, matched case-insensitively, duplicates ignored |

At most one of `shelfId`, `zoneId`, `locationCodes`. **None of them = the whole warehouse.**

- POST rather than GET: a code list does not fit in a URL. It writes nothing, so no `Idempotency-Key`
  is needed; the idempotency filter only acts when the header is sent.
- Permission: the existing `warehouse-locations` / `READ`. Printing is reading the map.
- `200`: `Content-Type: application/pdf`,
  `Content-Disposition: attachment; filename="location-labels-HCM-20261014-1530.pdf"` (warehouse prefix
  and the injected `Clock`'s time in `Asia/Ho_Chi_Minh`, to the minute).
- The OpenAPI operation declares the `application/pdf` binary response and the JSON error responses.

### Errors (all JSON `ApiResponse`, §7)

| Case | Status / code |
|---|---|
| Body shape: missing `format`, two selectors, `levelIndexes` without `shelfId`, bad code pattern, list too long | `400 VALIDATION_FAILED` |
| Warehouse unknown | `404 WAREHOUSE_NOT_FOUND` |
| Shelf / zone unknown or in another warehouse | `404 SHELF_NOT_FOUND` / `404 ZONE_NOT_FOUND` |
| A requested level index not on the shelf | `404 SHELF_LEVEL_NOT_FOUND` |
| Some `locationCodes` not in this warehouse | `404 LOCATION_NOT_FOUND`, message lists up to 10 of them (safe to echo: the pattern above already restricts them to `[A-Z0-9-]`) |
| A requested code is excluded by D4 | **new** `409 LOCATION_INACTIVE`, message lists up to 10 |
| The selection resolves to no label (empty shelf, zone without shelves) | **new** `422 LOCATION_LABELS_EMPTY` |
| More than 2,000 labels resolved | **new** `422 LOCATION_LABELS_TOO_MANY`, message gives the count and the limit |

Explicitly requested codes are refused rather than silently dropped: someone asked for that label and
must learn why it is missing. A broad selection (shelf, zone, warehouse) silently skips excluded places,
because those are not individually requested. The three new codes get `messages.properties` and
`messages_vi.properties` entries (`MessageBundleTest` checks both).

The 2,000 limit is about 84 A4 pages: a large warehouse in one go. Measured worst case (2,000 labels,
a 200-character warehouse name cut on every label): 0.8 s, after `LabelFonts` drops the fonts' `GSUB`
table - with it, PDFBox recompiled glyph-substitution patterns on every `showText` and the same sheet
took 3.5-9 s. `LocationLabelPdfRendererTest.twoThousandLabelsRenderInAboutASecond` guards it. Beyond that, print by zone or shelf.

## 5. Label content and layout

Every label, whatever the format:

```
┌───────────────────────────────────────┐
│ HCM-A01-2-B                  ┌──────┐ │  code: Noto Sans Bold, shrunk to fit (16pt → 8pt)
│ Kho Hồ Chí Minh              │  QR  │ │  line 1: warehouse name
│ Kệ A01 · Tầng 2 · Bin B · NORMAL      │  line 2: bin path (or area name) · storage class
│                              └──────┘ │
│ ║│║║│║│║║│║│║║│║║│║│║║│║║│║║│║│║║│║║ │  Code 128, full width, ≥ 8 mm tall
└───────────────────────────────────────┘
```

- **Words come from the message bundles** (`warehouse.label.shelf` = `Shelf` / `Kệ`, `…level`,
  `…bin`), resolved with the request locale as every other message is. No Vietnamese in Java.
- Area labels: line 2 is the area's name and storage class.
- **Code 128:** ZXing picks the code sets. Quiet zone of 10 modules each side. Module width is the
  largest of 0.375 / 0.25 / 0.19 mm that fits the label width (0.375 and 0.25 are 3 and 2 dots at 203 dpi);
  if even 0.19 mm does not fit — only for codes far longer than any real one — it is scaled to fit and the
  QR remains the readable symbol.
- **QR:** error correction M, quiet zone 4 modules, square side 16 mm on `A4_SHEET`, 15 mm on
  `THERMAL_ROLL`. Runs of dark modules in a row are merged into one rectangle to keep the PDF small.
- No status marking on the label: it is permanent, the status is not.

| Format | Page | Label | Grid |
|---|---|---|---|
| `A4_SHEET` | A4 210 × 297 mm | 70 × 37 mm, 3 mm padding | 3 × 8 = 24 per page, 0.5 mm top margin — the common "A4 24 labels 70×37" decal sheet |
| `THERMAL_ROLL` | 60 × 40 mm | the page itself, 2.5 mm padding | 1 per page |

**Order:** bins by shelf code, level index (numerically: level 10 after level 2, which sorting by
`location_code` would get wrong), bin code; then areas by code. For `locationCodes` the same order, not
request order, so a sheet comes out in walking order.

## 6. Components (module `warehouse`, nothing in `api/`)

No other module needs labels, so `warehouse :: api` does not change and neither does `ModularityTest`.

| Class | Package | Job |
|---|---|---|
| `LocationLabelController` | `internal/controller` | Validates the body (`LocationLabelRequest`), calls the service, then the renderer; builds the `ResponseEntity<byte[]>` with headers. `@RequiresPermission(warehouse-locations, READ)` on a **public** method. |
| `LocationLabelRequest` | `internal/controller/dto` | The body; bean validation for §4's shape rules. |
| `LocationLabelService` / `…Impl` | `internal/service` | `@Transactional(readOnly = true)`. Checks the warehouse, shelf, levels and zone; resolves the selection; applies D4; enforces the limit and the error cases. Returns `LocationLabelSheet(warehousePrefix, warehouseName, List<LocationLabel>)`. |
| `LocationLabel` | `internal/service` | Read model: `code`, `kind`, `warehouseName`, `shelfCode`, `levelIndex`, `binCode`, `areaName`, `storageClass`. |
| `LocationLabelPdfRenderer` | `internal/service` | `byte[] render(LocationLabelSheet, LabelFormat, LabelWords)`; the controller resolves the words (`Kệ`, `Tầng`, `Bin`) from the message bundles, so the renderer needs no `MessageSource`. ZXing and PDFBox live here only; knows nothing about JPA. |
| `LabelFormat` | `internal/service` | `A4_SHEET`, `THERMAL_ROLL` with their page and label geometry in millimetres. |
| `LocationLabelRepository` | `internal/repository` | The read port: `findLabelRows(warehouseId, LabelSelection)` and the existence checks. Implemented in `LayoutReadRepositoryAdapter` with JPQL record projections, like the layout read. |

**Rendering runs outside the transaction.** The controller calls the service (one short read-only
transaction), then the renderer, so a connection is not held while a large PDF is drawn.

**Queries:** at most two per request — one for bins (location ⋈ bin ⋈ level ⋈ shelf, filtered by
warehouse and by shelf / levels / zone / codes) and one for areas (location ⋈ area, only for the
whole-warehouse and code selections) — plus the small existence checks. No N+1: everything a label
prints comes from the projection.

## 7. The `Accept: application/pdf` trap

A client asking for a PDF sends `Accept: application/pdf`. When the handler throws, `GlobalExceptionHandler`
returns `ResponseEntity<ApiResponse>` without a content type, Spring negotiates against the `Accept`
header, finds no converter that writes `ApiResponse` as `application/pdf`, and the client gets a 406 or
a 500 instead of the 404 it should have seen.

**Fix in `GlobalExceptionHandler`:** its two response builders (`respond(...)` and the framework-
exception path) set `.contentType(MediaType.APPLICATION_JSON)`. A preset concrete content type skips
negotiation, so the error body is always the JSON envelope. This is the right fix rather than telling
FE to add `application/json` to `Accept`, because every future binary endpoint has the same trap. It is
a change to the shared base layer: every error response already is JSON, so nothing observable changes
for JSON clients; a regression test in `common` pins it.

FE should still send `Accept: application/pdf, application/json` — harmless, and correct HTTP.

## 8. Not shared yet

The Code 128 / QR drawing could serve SKU labels at receiving later. It stays in `warehouse` until a
second user exists; moving `LocationLabelPdfRenderer`'s barcode half into `common` is then a mechanical
extraction. Building it in `common` now would be a shared API with one caller.

## 9. Testing

| Test | Proves |
|---|---|
| `LocationLabelPdfRendererTest` (unit) | Page count and media box per format (25 labels → 2 A4 pages; 3 labels → 3 thermal pages); every label's Code 128 **and** QR decode back to its `location_code` (render page with PDFBox `PDFRenderer` at 300 dpi, crop each label cell, decode with ZXing `RGBLuminanceSource`); Vietnamese text renders without a missing-glyph exception; a 55-character code still produces a decodable QR. |
| `LocationLabelServiceIntegrationTest` (Testcontainers, `DemoData.WAREHOUSE_HCM`) | Each selector returns the right codes in walking order; `INACTIVE` bin and a bin on an `INACTIVE` shelf skipped in a broad selection, refused when named; `BLOCKED`/`MAINTENANCE` included; an inactive warehouse still prints; zone selection has no areas; every error case of §4; query count per request is constant (see `jpa-orm-review`). |
| `LocationLabelControllerHttpTest` (standalone MockMvc + `GlobalExceptionHandler`) | `200` with `application/pdf` and the `Content-Disposition` filename; body validation → `400`; **an error under `Accept: application/pdf` is a JSON `ApiResponse` with the right status** (§7). |
| Security integration test (pattern: `InventoryCatalogSecurityIntegrationTest`) | `401` anonymous, `403` without `warehouse-locations` READ, `200` with it, through the real filter chain. |
| `WarehouseOpenApiIntegrationTest` | The new operation is documented with the binary response. |
| Regression test in `common` (next to `GlobalExceptionHandler`'s existing tests) | Error responses carry `Content-Type: application/json` whatever the `Accept` header. |

Run during work: the focused tests, `ModularityTest`, `ArchitectureTest`, `python -X utf8 tools/verify.py`.
One full `mvn verify` at the end of the branch.

## 10. Follow-ups outside the code

- Jira SCRUM-97: write this scope into the empty description; it is still in the closed Sprint 2.
- FE: one call, `responseType: 'blob'`, open in a new tab or `print()`; a form with the four selectors
  and the format.
- No coordination with Tú needed: no migration, no schema change.
