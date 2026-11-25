# Design Studio: API contract for the frontends

The contract between the backend and the two frontends: the storefront's studio (customers) and the backoffice (Sales
Manager, sales staff). It covers Design Studio packages 2F-1 to 2F-9. The business rules are in kltn-docs
`docs/ecommerce/12-product-design` (cited as 12 §x / BR-x / Bx), `16-sales-chat` and `13-catalog`.

What the backend checks is authoritative. The studio's own checks are a convenience, so the customer sees a problem
before they press a button.

---

## 1. Conventions

- **Envelope.** Every response is `{success, data}`, or `{success: false, errorCode, message, fieldErrors?, details?,
  correlationId, timestamp}`. Null fields are omitted, so test for absence rather than for `null`.
- **Pages.** `?page=0&size=20` returns `data: {items, page, size, totalElements, totalPages, hasNext, hasPrevious}`. `size` is at most 200;
  more is a 400, never a short page.
- **Version.** Every write echoes the `version` the user last read; a stale one is 409 `CONFLICT` (BR-11). These move
  a design's version:
  - saving the layout;
  - uploading a file;
  - submitting;
  - deciding;
  - renaming;
  - joining or leaving a project.

  Re-read the design before the next write.
- **Idempotency.** Send `Idempotency-Key` on POSTs that create something (uploads, submissions). A repeat of a
  settled request is answered with the stored response.
- **Signed links.** `GET .../download-url` returns `{url, expiresAt}`, valid for minutes. Never cache, log or share
  the URL. The object store allows the storefront's origin for `GET` (CORS), so the studio may draw images straight
  from it.
- **Checksums.**
  - A layout is stored as canonical JSON (RFC 8785, JCS), and its checksum is the lower-case hex SHA-256 of that text.
  - The studio must compute the same: numbers as JavaScript doubles, keys sorted by UTF-16 code units, no
    whitespace.
  - Files are bound to the layout by that checksum (BR-10).
- **Dates.** Instants are ISO-8601 UTC. `desiredDeliveryDate` is a calendar date in Vietnam (`Asia/Ho_Chi_Minh`).

---

## 2. Lifecycle

| Design status | Who changes it | Into |
|---|---|---|
| `DRAFT` | the owner | `PENDING_REVIEW` (submission passes preflight), `ARCHIVED` (deleted) |
| `PENDING_REVIEW` | the Sales Manager | `APPROVED` (files stamped, snapshot, own SKU), `DRAFT` (returned with a reason) |
| `APPROVED` | nobody | final: changing it means a revision (a new design) |
| `ARCHIVED` | nobody | final, and invisible (404) |

- A customer holds at most 50 drafts (B37).
- Projects are `ACTIVE` or `ARCHIVED`. A project closes for good once none of its designs waits for review (BR-22).

---

## 3. Customer endpoints (storefront)

All need a signed-in customer. A design is its owner's; anyone else gets 404, so a design's id reveals nothing.

### Template (P2)
`GET /api/v1/catalog/products/{slug}/customization` returns the active templates of the product's `ACTIVE` variants.
Each template is `{templateId, code, name, sku, minDpi, colorMode, allowedFileFormats, maxFileSizeMb, maxColors?,
allowedFontCodes?, model, printAreas, hasCartonSpec, model3d?}`. Filter `templates[]` by `sku`.
- `model` is the cup or bag geometry:
  - `{kind: "parametric-cup", topDiameterMm, bottomDiameterMm, heightMm, rimMm, seamMm, bandTopOffsetMm,
    bandBottomOffsetMm, material}`;
  - or `{kind: "parametric-bag", widthMm, gussetMm, heightMm, topFoldMm, glueFlapMm, bottomFoldMm, handle,
    material}`. `glueFlapMm` is the vertical glue flap and `bottomFoldMm` the depth of the bottom fold of the flat
    sheet the bag is printed on (see "The print file"). A template saved before they existed reads with the defaults:
    `glueFlapMm` 20, `bottomFoldMm` = round2(0.6 × `gussetMm`) (assumed, pending the workshop's confirmation).
- The cup's sector is computed by the server.
- `hasCartonSpec`: the variant has an active packing unit of more than one piece. Without it a design cannot be
  submitted (`CARTON_SPEC_MISSING`), so the studio can say so before the customer starts.
- `model3d: {url, surfaces: [{printAreaCode, meshName}]}` is the template's `.glb`: a signed link valid one hour,
  readable from the storefront's origin, and the mesh each print area is drawn on (`meshName` is always
  `print:<printAreaCode>`). Absent when the template has no file; the studio then draws the parametric `model`.
  `model`'s measurements are taken from that file by the back office and only ever change together with it.
- `GET /api/v1/designs/{id}/template` returns the template a design is drawn on (see Designs) in nearly the same
  shape, plus:
  - `active`: whether that template still takes **new** designs. A draft keeps its template when it is deactivated
    (B39), so `active: false` alone does not stop a submission;
  - `designable`: the variant is `ACTIVE` and an ordinary one of an `APPROVED` or `PUBLISHED` CUSTOMIZABLE product;
    `false` exactly when a submission now would fail with `VARIANT_UNAVAILABLE`;
  - `hasCartonSpec`: as on `/customization`; `false` exactly when a submission now would fail with
    `CARTON_SPEC_MISSING`.

  Both are computed by the very rule the submission's preflight uses, as they are now - also for an approved design,
  whose template is otherwise the one frozen in its snapshot. Gate "Gửi duyệt" on these two, not on whether the
  template is listed in `/customization` (which lists only published products and active templates).

### Designs (P1, P6)
| Endpoint | Body / query | Notes |
|---|---|---|
| `POST /api/v1/designs/mine` | `{productId, sku, templateCode, name, projectId}` | 201. Empty layout. Every design starts in a project (BR-23.2): 400 `VALIDATION_FAILED` without `projectId` (`fieldErrors[0].field` = `projectId`); 404 for a project that is not the caller's or not of their customer; 409 `DESIGN_PROJECT_ARCHIVED`, `DESIGN_PROJECT_FULL`, `CUSTOMER_NOT_ACTIVE`, `DESIGN_DRAFT_LIMIT_REACHED`. A refused start writes nothing |
| `GET /api/v1/designs` | `?status=&page&size` | The caller's own designs, `ARCHIVED` excluded |
| `GET /api/v1/designs/{id}` | | The design (§6) |
| `PUT /api/v1/designs/{id}/name` | `{version, name}` | Drafts only, otherwise 409 `DESIGN_NOT_DRAFT` - checked before `version`, so a design submitted from another tab says so rather than 409 `CONFLICT`; the same for the archive and the layout save |
| `POST /api/v1/designs/{id}/archive` | `{version}` | Deletes a draft. An open quote request on it closes |
| `POST /api/v1/designs/{id}/revisions` | | From an approved design: copies its approved layout and images; counts against the 50 drafts. Body `{projectId?}`, optional. Written into a project (BR-23.3): `projectId` when given (an open project of the caller's with room: 404 when not theirs, 409 `DESIGN_PROJECT_ARCHIVED` / `DESIGN_PROJECT_FULL`); else the source's project while it is open and has room; else 409 `DESIGN_PROJECT_ARCHIVED` / `DESIGN_PROJECT_FULL` with `details.projectId` = the source's project, and nothing is written. A source in no project needs `projectId` (400 `VALIDATION_FAILED`). Never standalone |
| `POST /api/v1/designs/{id}/clones` | `{variantSku, document, projectId?}`; header `Idempotency-Key` **required** | 201 `{design}`. A new draft of the source (any of the caller's designs but an archived one) on another ACTIVE variant of the same product, with the active template `document.templateCode` names; `document` is the layout fitted to that template (checked as a layout save, at most 1,000,000 bytes) and may place only the source's images. The clone gets the source's images **with the same `assetId`s**, so the layout needs no rewriting. Name `"<source name> · <sku>"`. Written into a project exactly as a revision is (BR-23.3, owner decision 2026-11-25): `projectId` when given, else the source's open project with room, else 409 `DESIGN_PROJECT_ARCHIVED` / `DESIGN_PROJECT_FULL` with `details.projectId`; never standalone. `notInProject` is no longer sent (it would always be null). A `projectId` that is not a UUID is 400 `VALIDATION_FAILED`. 409 `DESIGN_CLONE_TARGET_INVALID` (same variant, another product, a variant that cannot be designed on), `DESIGN_DRAFT_LIMIT_REACHED`, `PRODUCT_NOT_CUSTOMIZABLE`; 404 `PRINT_CONFIG_NOT_FOUND`; 400 `VALIDATION_FAILED`; 413. Same key and body again: the first answer; same key, other body: 422 `IDEMPOTENCY_KEY_REUSED` |
| `GET /api/v1/designs/{id}/history` | `?page&size` | Decision history, newest first (§7) |
| `GET /api/v1/designs/{id}/snapshot` | | The approved snapshot: files, `document`, `documentChecksum`, `assets`, `customerSku` |
| `GET /api/v1/designs/{id}/template` | | The template the design is drawn on: current for a draft (B39); frozen in the snapshot for an approved design |

### Layout (P3)
- `GET /api/v1/designs/{id}/document` returns `{designId, version, document, checksum, updatedAt}`.
- `PUT /api/v1/designs/{id}/document` takes `{version, document}`:
  - at most 1,000,000 bytes, and at most 200 elements a surface;
  - `templateCode`, `sku` and surface codes must match the design's template;
  - an image element's `assetId` must be an image of this design.

  It returns the stored canonical layout and its checksum.

### Images (P4)
- **Multipart upload:** `POST /api/v1/designs/{id}/assets` with part `file`.
- **Straight to storage:**
  1. `POST /api/v1/designs/{id}/assets/uploads` with `{fileName, contentType, sizeBytes, sha256}` returns
     `{uploadId, method, url, headers, expiresAt}`.
  2. The browser sends the file with `method` to `url`, with exactly `headers`.
  3. `POST /api/v1/designs/{id}/assets` with `{uploadId}` records it.
- **Rules (both paths):**
  - PNG, JPEG or WebP, and only types in the template's `allowedFileFormats`;
  - at most `maxFileSizeMb`, at most 40 MP, at most 100 images a design;
  - only drafts take images;
  - pixel size is measured after EXIF rotation.
- **From the project library:** `POST /api/v1/designs/{id}/assets/library` with `{assetId}` copies a project library
  file into the design, checked against this design's template; 415 if the template does not take its type.
- **Reading:** `GET /api/v1/designs/{id}/assets` lists them (newest first); `GET .../assets/{assetId}/download-url`
  returns a signed link.
- Uploading 120 files an hour per user is allowed; more is 429.

### Rendered files
- **Upload:** `POST /api/v1/designs/{id}/artifacts?role=&documentChecksum=` (multipart `file`), or the slot path
  (`/artifacts/uploads`, then `/artifacts` with `{uploadId, role, documentChecksum}`).
- **Roles the studio renders:** `CUSTOMER_PREVIEW` (an image), `PRINT_READY` (PDF) and `TECHNICAL_SPEC` (PDF).
- **The print file (`PRINT_READY`):** PDF/X-4, vector where it can be, one optional content group (layer) per
  element, the guide layer "Hướng dẫn kỹ thuật (không in)" switched off in `/OCProperties /D /OFF`, and an output
  intent (the server puts its own in its place when it stamps the file, see §11).
  - **Pages.** A cup: one page per print area, in the template's order. A bag: **one page, the flat sheet** the four
    sides are laid out on (below). A 4-page bag file, one page per side as before, is still accepted so that bags
    submitted before the change can be approved; any other page count is refused (`PRINT_FILE_INVALID`).
  - **Page boxes** (PDF points, origin the bottom-left corner of the bleed box; `W × H` = the print area's bleed box,
    for a cup the flattened sector's bounding box including bleed):

    | Box | Value | Checked |
    |---|---|---|
    | MediaBox | `[-5 mm, -14 mm, W + 5 mm, H + 5 mm]`: a 5 mm white margin left, right and top, a 14 mm slug below | must contain the BleedBox |
    | BleedBox | `[0, 0, W, H]` | read as declared (not clipped to the MediaBox), its size ±0.5 mm; a page with none falls back to its MediaBox, so older files still pass |
    | TrimBox | the bounding box of the trim outline, same origin | position and size ±0.5 mm, only when declared |
    | CropBox | not set (= MediaBox) | |

    Example, a 12 oz cup (`CUP12-BODY`): BleedBox 286.54 × 125.53 mm, TrimBox 279.67 × 120.85 mm, MediaBox 296.54 ×
    144.53 mm. When the design is approved the server stamps the TrimBox from the BleedBox's origin, keeps a declared
    BleedBox and sets the CropBox to the MediaBox, so the press sees the slug.
  - **Bag: the flat sheet.** `W` = `widthMm`, `g` = `gussetMm`, `T` = `topFoldMm`, `G` = `glueFlapMm`, `B` =
    `bottomFoldMm`, `P` = round2(`heightMm`) − round2(`T`) (a side's printable height, as the print areas are
    computed), `b` = the largest `bleedMm` of the four print areas.
    - Trim: `Wt = G + 2W + 2g`, `Ht = T + P + B`. BleedBox `(Wt + 2b) × (Ht + 2b)`, TrimBox `Wt × Ht` at `(b, b)`, each
      ±0.5 mm.
    - Sides (top-left corner of each side's trim, in the sheet's trim coordinates, y downwards): FRONT `(G, T)`, RIGHT
      `(G + W, T)`, BACK `(G + W + g, T)`, LEFT `(G + 2W + g, T)`, each the size of its print area (`W × P` or
      `g × P`).
    - Die line: the trim rectangle `(0, 0, Wt, Ht)`. Vertical folds at `G`, `G+W`, `G+W+g/2`, `G+W+g`, `G+2W+g`,
      `G+2W+1.5g`; horizontal folds at `T` and `T + P`.
    - Pinned by both sides' tests: `W 260, g 120, H 320, T 40, G 20, B 72, b 3` → `P 280`, `Wt 780`, `Ht 392`, bleed
      box `786 × 398` mm.
    - The template keeps its four print areas and the customer still designs each side; only the print file is one
      sheet.
  - **Cut line.** A paper cup and a bag carry the die line as the spot colour `CutContour`, overprinting. A clear PET
    cup (`material = "pet-clear"`) has **no** `CutContour` (it is printed once formed, not die-cut) and an extra layer
    "Lót trắng (mực White)", switched **on**, under the artwork: the separation `White` (alternate CMYK `0 0 0 0`), the
    artwork above it overprinting (`OP true`) so that the white plate stays.
  - **What the stamp keeps.** Every layer and its state, the `White` separation and the overprint; the XMP the studio
    wrote (`xmpMM:DocumentID`, `InstanceID`, `VersionID`, `RenditionClass`, `xmp:CreateDate`, `xmp:CreatorTool`, ...)
    and the trailer `/ID`. The server sets again only `dc:title`, `pdfxid:GTS_PDFXVersion`, `pdf:Trapped` (`False`),
    `xmp:ModifyDate` and `xmp:MetadataDate`; a file with no XMP gets a packet of those fields alone.
- **The technical spec (`TECHNICAL_SPEC`):** the multi-page A4 landscape design description (positions, sizes and
  colours of each element, the proof sign-off box). It is stored and handed over as it is; the server reads nothing
  in it.
- **Reading:** `GET .../artifacts` lists them, `GET .../artifacts/{artifactId}/download-url` returns a signed link.
  Before the link is issued:
  - a file of the **approved** bundle is checked against its checksum: missing or changed is 409 `CONFLICT`;
  - any other file (a draft's) is checked only for being there: missing is 503 `STORAGE_ERROR`.

  Either way no link to a broken file is ever issued.
- **Generated at approval, never uploaded:** the stamped `PRINT_READY` and `PRODUCTION_INSTRUCTION`.

### Submission (P5)
`POST /api/v1/designs/{id}/technical-review-requests` takes `{version, quoteRequest?: {cartons, desiredDeliveryDate?}}`.
1. **Preflight on the saved layout.** A failure is 422 `DESIGN_PREFLIGHT_FAILED` with `details.issues: [{rule,
   surfaceCode?, elementId?, message, values?}]` (§8). It is recorded as `PREFLIGHT_FAILED` and the version does not
   move.
2. **The three files must be rendered from the current layout and template.** Otherwise 409 `DESIGN_FILES_OUTDATED`
   with `details.roles`. A template edited in place since rendering counts too.
3. **On success** the design is `PENDING_REVIEW` and locked.
4. **`quoteRequest`** ("Gửi duyệt và yêu cầu báo giá"):
   - it is checked first: 400 `quoteRequest.cartons` (1 to 1,000,000), 400 `quoteRequest.desiredDeliveryDate`
     (today or later), 409 `CUSTOMER_NOT_ACTIVE`;
   - it is asked only once the design is submitted;
   - a design returned after a request still has it open, and the request now asks the new numbers.

### Quote requests (12 §4.8)
| Endpoint | Body | Notes |
|---|---|---|
| `POST /api/v1/designs/{id}/quote-requests` | `{cartons, desiredDeliveryDate?}` | Approved designs only (409 `DESIGN_NOT_APPROVED`). 201 for a new request; 200 when the open one now asks this |
| `GET /api/v1/designs/{id}/quote-requests` | | The design's requests, newest first |

- One request per design is open at a time.
- It closes when a salesperson has answered it, or when the draft is deleted. Asking after that opens a new one.

### Projects (12 §4.9–§4.11)
| Endpoint | Body | Notes |
|---|---|---|
| `POST /api/v1/design-projects` | `{name, description?}` | 201 |
| `GET /api/v1/design-projects` | `?status=ACTIVE\|ARCHIVED&page&size` | The caller's own, newest first, with `designCount` |
| `GET /api/v1/design-projects/{id}` | | The project with `designs` side by side (archived ones excluded) and `assetCount` |
| `PUT /api/v1/design-projects/{id}` | `{version, name, description?}` | Open projects only |
| `POST /api/v1/design-projects/{id}/archive` | `{version}` | 409 `DESIGN_PROJECT_HAS_PENDING` while a design waits for review |
| `POST /api/v1/design-projects/{id}/designs` | `{designId}` | Adds a design in no project, or moves one from another of the caller's open projects (BR-23.4); its history gets `REMOVED_FROM_PROJECT` (old project) then `ADDED_TO_PROJECT` (this one). 404 for a design not the caller's, archived, or of another customer; 409 `DESIGN_PROJECT_ARCHIVED` (this project closed, or the design's project closed: then `details.projectId` names it), `DESIGN_PENDING_IN_PROJECT` (waiting for review), `DESIGN_IN_OPEN_QUOTE_REQUEST` (an open quote request of its project names it; `details.projectId`), `DESIGN_PROJECT_FULL` (20), `CONFLICT` (moved meanwhile: retry). A design never leaves for no project: `DELETE /api/v1/design-projects/{id}/designs/{designId}` is gone (404/405) |
| `GET /api/v1/design-projects/{id}/history` | `?page&size` | The project's history (§7) |
| `POST /api/v1/design-projects/{id}/assets` | multipart `file`; header `Idempotency-Key` optional | The library: as a design's images; at most 30 files (409 `DESIGN_PROJECT_LIBRARY_FULL`). With a key, a retry of an upload that succeeded returns the same file and takes no second place; the same key with another file is 422 `IDEMPOTENCY_KEY_REUSED` |
| `POST /api/v1/design-projects/{id}/assets/from-design` | `{designId, assetId}` | 201, the library file (as an upload's). A server-side copy of an image of one of the project's designs; the same pair again returns the file it made. 404 when the design is not the caller's or not in this project, or the image is not that design's; 409 `DESIGN_PROJECT_LIBRARY_FULL`, `DESIGN_PROJECT_ARCHIVED` |
| `GET /api/v1/design-projects/{id}/assets`, `.../assets/{assetId}/download-url` | | |
| `DELETE /api/v1/design-projects/{id}/assets/{assetId}` | | 204. Designs it was placed on keep their copy |
| `GET /api/v1/design-projects/{id}/texts` | | The library's texts, newest first: `[{id, projectId, text, style, createdAt}]` |
| `POST /api/v1/design-projects/{id}/texts` | `{text, style: {fontCode, sizePt, color, bold?, italic?, align, letterSpacingEm?, lineHeight, outline?: {color, widthMm}}}` | 201, the text. `text` 1–500 characters; `color` `#rgb`/`#rrggbb`/`#rrggbbaa`; `align` `left`/`center`/`right`; `sizePt`, `lineHeight`, `outline.widthMm` > 0; `bold`/`italic` default `false`, `letterSpacingEm` `0`. At most 30 texts, apart from the 30 files: 409 `DESIGN_PROJECT_LIBRARY_FULL`; closed project 409 `DESIGN_PROJECT_ARCHIVED` |
| `DELETE /api/v1/design-projects/{id}/texts/{textId}` | | 204. Open projects only |
| `POST /api/v1/design-projects/{id}/preflight-failures` | `{items: [{designId, issues: [{rule, surfaceCode?, elementId?}]}]}` | 201, one history row `{actorId, event: "BATCH_PREFLIGHT_FAILED", notes, createdAt}`. For a batch the studio's own preflight refused; nothing else changes. 1–20 items, 1–50 issues each, every field at most 64 characters; a `message` is accepted and not stored. Notes: `"<designId>: RULE SURFACE/element, ...; ..."`. 404 for a design not in the project; 409 `DESIGN_PROJECT_ARCHIVED`; 429 `RATE_LIMITED` (60 an hour) |
| `POST /api/v1/design-projects/{id}/review-rounds` | `{designs: [{id, version}, ...]}` | "Gửi duyệt cả bộ", details below |
| `POST /api/v1/design-projects/{id}/quote-requests` | `{lines: [{designId, cartons}], desiredDeliveryDate?}` | 201. Approved designs of an open project only, each named once |
| `GET /api/v1/design-projects/{id}/quote-requests` | | |

**Batch submission (`review-rounds`):**
- at least 2 drafts, all or none;
- designs that cannot go as they are are **all** named, whichever check met them (the first read, or the locked
  check just before submitting):
  - `details.designs: [{designId, errorCode}]`, every design refused, each with its own code: `NOT_FOUND` (deleted,
    or not in this project), `DESIGN_NOT_DRAFT` (submitted, approved or archived since), `CONFLICT` (a draft at another
    version);
  - the answer's own code is the most serious of them, in that order: 404 `NOT_FOUND`, then 409 `DESIGN_NOT_DRAFT`,
    then 409 `CONFLICT`;
  - `details.designIds`: the designs refused for that same code (as before);
- a design no longer a draft is `DESIGN_NOT_DRAFT` even when its `version` is stale too: status is checked before
  version, for a design submitted alone as well;
- if any fails preflight, the answer is 422 `DESIGN_BATCH_PREFLIGHT_FAILED` with
  `details.designs: [{designId, issues? | roles?}]`;
- on success it returns 201 `{roundId, project}`.

---

## 4. Backoffice endpoints

| Endpoint | Permission | Notes |
|---|---|---|
| `GET/POST /api/v1/products/{productId}/variants/{variantId}/customization-templates`, `PUT /{templateId}`, `POST /{templateId}/activation`, `/deactivation` | `product-customization-templates` | Templates (2F-2) |
| `GET /api/v1/designs/review-queue` | `design-designs:APPROVE` | Designs submitted on their own, the longest waiting first |
| `POST /api/v1/designs/{id}/reviews` | `design-designs:APPROVE` | `{version, approved, notes}`, details below |
| `GET /api/v1/design-review-rounds`, `/{id}` | `design-designs:APPROVE` | A project's batch as one item, with the project's name, the customer, and each design as `{designId, design?, outcome?, decidedAt?}`: `design` only while it waits or once approved, `outcome` once decided; `closedAt` once every design is decided |
| `POST /api/v1/design-review-rounds/{id}/approvals` | `design-designs:APPROVE` | "Duyệt tất cả", details below |
| `GET /api/v1/design-quote-requests` | `design-quote-requests:READ` | `?customerId&status=OPEN\|CLOSED&page&size`, details below |
| `POST /api/v1/design-quote-requests/{id}/closure` | `design-quote-requests:UPDATE` | Close a request once answered |
| `GET /api/v1/design-project-quote-requests` | `design-quote-requests:READ` | `?customerId&page&size`: project requests with the project's name and a line per design |
| `GET /api/v1/design-snapshots` | `design-snapshots:READ` | Approved designs and their own SKUs by customer |

**Templates and their `.glb` (`/api/v1/products/{productId}/variants/{variantId}/customization-templates`):**

The back office measures the model from its `.glb` at upload, so a template's measurements and its file are saved
together and never apart.

| Endpoint | Body | Notes |
|---|---|---|
| `POST` | `multipart/form-data`: part `template` = the template JSON, sent as a Blob with type `application/json`; part `file` = the `.glb` | 201. The file is required: without one (or as plain JSON) 409 `PRINT_CONFIG_MODEL_FILE_REQUIRED` |
| `PUT /{templateId}` | the same multipart, `template` with `version`; `file` optional | Measurements, print areas, mesh map and file are saved in one transaction; a failure changes nothing |
| `PUT /{templateId}` | `application/json`, the template with `version` | For edits without a file |
| `POST /{templateId}/activation`, `/deactivation` | `{version}` | Activation needs a file and a complete mesh map, else 409 / 422 as below |
| `GET /{templateId}/model-file/download-url` | | A signed link to the stored `.glb` |

The response is the template with `model3d: {hasFile, fileName?, fileChecksum?, surfaces: [{printAreaCode,
meshName}]}`; `fileChecksum` is the SHA-256 (hex) of the file. `PUT /{templateId}/model-file` is gone: a file alone
would leave the measurements of the file before.

- **File:** a glTF 2.0 binary (`.glb`), at most 10 MB, virus-scanned. 415 `UNSUPPORTED_MEDIA_TYPE`, 413
  `PAYLOAD_TOO_LARGE`, 400 `VALIDATION_FAILED` when the scan refuses it, 429 `RATE_LIMITED` when too many files are
  being scanned at once (retry later). The download link names the file `<name>.glb`, also for a file stored before
  the extension was kept.
- **Order of checks on `POST`**, as the server runs them - the cheap, pure checks before the file is scanned and
  stored, the product last, inside the transaction:
  1. a file is there: a JSON `POST` (no file) is 409 `PRINT_CONFIG_MODEL_FILE_REQUIRED`, whatever the product;
  2. the model and the print areas: 400 `PRINT_CONFIG_INVALID` with `fieldErrors` (below), whatever the product;
  3. the file itself: 415 / 413 / 400 / 429 as above;
  4. the product: 409 `PRODUCT_NOT_CUSTOMIZABLE`; then the code (400 `PRINT_CONFIG_INVALID`, 409
     `PRINT_CONFIG_CODE_TAKEN`) and the mesh map (422).

  So a multipart `POST` with an invalid model on a product that is not customizable is 400, not 409. The order is
  kept on purpose: checking the product first would cost a virus scan and an upload for a request the model alone
  already refuses.
- **Bag fields `glueFlapMm`, `bottomFoldMm`:** `0 ≤ glueFlapMm ≤ 100`, `0 ≤ bottomFoldMm ≤ gussetMm`. Free to change
  without a file, like `topFoldMm`: the `.glb` does not determine them. Omitted on a save, each takes its default
  (`glueFlapMm` 20, `bottomFoldMm` = round2(0.6 × `gussetMm`)): a `PUT` replaces the whole model, so send back the
  values you read to keep them.
- **`PRINT_CONFIG_INVALID` (400)** carries `fieldErrors: [{field, message, code}]`: `field` is the path in the request
  (`model.seamMm`, `model.rimMm`, `model.topFoldMm`, `model.glueFlapMm`, `model.bottomFoldMm`,
  `printAreas[i].safeMarginMm`, `printAreas[i].bleedMm`, ... - `i` the area's index in the request), `message` the
  English reason, `code` the rule (`Range`, `OneOf`, `NoSafeArea`, ...). Map `field` to the form; show `message` only
  as a fallback.
- **Values only a new file may change** (409 `PRINT_CONFIG_MODEL_FILE_REQUIRED`, the message names them). Checked on
  **every** save without a file, whether or not the template has one. Values are stored as sent and compared after
  rounding each to 0.01 mm, so re-sending what was read is never refused:
  - cup: `topDiameterMm`, `bottomDiameterMm`, `heightMm`, `bandTopOffsetMm`, `bandBottomOffsetMm`;
  - bag: `widthMm`, `gussetMm`, and `round(heightMm) − round(topFoldMm)` (each rounded first, then subtracted; the
    fold may move if the height moves with it);
  - the model's `kind`, and the set of `model3d.surfaces`. A request with no `surfaces` keeps the stored rows whose
    print area the new model still has, and drops the others.

  Free to change without a file: `name`, `minDpi`, `colorMode`, `allowedFileFormats`, `maxFileSizeMb`, `maxColors`,
  `allowedFontCodes`, `seamMm`, `rimMm`, `material`, `handle`, and each print area's name and margins. A template
  written before files were required, with none, is read as before and takes only these edits until a file is
  uploaded with it. One written before models were stored (no `model` at all) is refused (409) on any save without
  a file, since any `model` sent differs in `kind`: it needs the file with its measurements.
- **Activation** of a template without its file, or without a model at all, is 409
  `PRINT_CONFIG_MODEL_FILE_REQUIRED`: in both cases the fix is to upload the `.glb` with its measurements.
- **Mesh map with a file** (stored, or sent with the request): every print area has exactly one entry, on the mesh
  `print:<printAreaCode>` (`print:BODY`, `print:FRONT`, ...). Otherwise 422 `PRINT_CONFIG_SURFACES_INVALID` with
  `fieldErrors`: `model3d.surfaces` / `PrintAreaMissing`, `model3d.surfaces[i].meshName` / `MeshNameMismatch`, and the
  earlier `PrintAreaUnknown`, `PrintAreaDuplicate`, `Size`. Checked again on activation.
- Stale `version`: 409 `OPTIMISTIC_LOCK`. Permission `product-customization-templates:{VIEW_PAGE, READ, CREATE,
  UPDATE}`, held by the Sales Manager and the System Admin. There is no `DELETE`: a template is never deleted - designs
  and snapshots name it - only deactivated, which is an `UPDATE`.

**`POST /designs/{id}/reviews`:**
- Returning requires `notes`, 1–2000 characters.
- Approving stamps the print file, draws the production instruction, freezes the snapshot and issues the own SKU, all
  in one step (BR-09). The answer to an approval can be:
  - 409 `DESIGN_PRINT_FILE_INVALID`;
  - 409 `CUSTOMER_SKU_SOURCE_UNAVAILABLE`, after which the design stays pending.

**`POST /design-review-rounds/{id}/approvals`:**
- Body `{designs: [{id, version}]}`: each design still waiting is approved in turn.
- The answer is `{outcomes: [{designId, outcome: APPROVED|NOT_APPROVED|SKIPPED, errorCode?, message?}], round}`.

**`GET /design-quote-requests`:**
- Each request carries the design as it is now: `designStatus`, `customerSku` once approved (the only time it can be
  quoted), and `returnReason` while it is a draft again.
- A request is closed when it has `closedBy`.

**Who reads what:**
- A reviewer reads a design while it waits for review.
- A holder of `design-quote-requests:READ` reads a design that has an open request while it waits or once it is
  approved. A returned draft is the customer's alone.
- Staff see a project's name only (BR-17).

---

## 5. Notifications (in-app inbox; mail best effort)

| When | Who | Link |
|---|---|---|
| A design submitted on its own | holders of `design-designs:APPROVE` | `/admin/designs/review-queue` |
| A project batch submitted | holders of `design-designs:APPROVE`, once a round | `/admin/design-review-rounds/{roundId}` |
| A design approved or returned | the owner | the design |
| A quote asked, or asked again | holders of `design-quote-requests:READ` | `/admin/design-quote-requests` |
| A design with an open request decided | holders of `design-quote-requests:READ` | `/admin/design-quote-requests` |
| A project quote asked | holders of `design-quote-requests:READ` | `/admin/design-project-quote-requests` |

There is no chat module yet. The quote-request card (16 BR-11) and routing to one salesperson (16 §4.1) come with it.
Until then every holder of the right hears.

---

## 6. The design object

`{id, customerId, productId, ownerUserId, name, status, version, productSlug, sku, templateCode, projectId,
currentArtifactId, previewArtifactId, reviewRequestedAt, reviewedAt, reviewedBy, reviewNotes, parentSnapshotId,
updatedAt, previewUrl?, variantName, productName}`.

- `previewUrl` is a signed link to the current `CUSTOMER_PREVIEW`, absent when there is none; lists carry it too.
- `variantName` is the variant's name, or its SKU when it has none; `productName` names the product even once it is
  no longer sold.
- `reviewNotes` is the reason the design was returned.
- `updatedAt` covers layout saves.
- `parentSnapshotId` is set on a revision.

---

## 7. History words

- **Design (12 §5.3):**
  - `CREATED`, `DERIVED_FROM_SNAPSHOT`;
  - `PREFLIGHT_FAILED`, `SUBMITTED_FOR_REVIEW` (names the round of a batch);
  - `RETURNED`, `APPROVED` (snapshot, SKU, checksum, and the manager's note);
  - `ARCHIVED`;
  - `QUOTE_REQUESTED` ("Asked again: ..." when an open request changes);
  - `ADDED_TO_PROJECT`, `REMOVED_FROM_PROJECT`.

  Rows written by the retired staff workflow keep their old words.
- **Project (12 §5.4):** `PROJECT_CREATED`, `ASSET_ADDED`, `ASSET_REMOVED`, `TEXT_ADDED`, `TEXT_REMOVED`,
  `BATCH_PREFLIGHT_FAILED`, `BATCH_SUBMITTED`, `BATCH_CLOSED`, `PROJECT_QUOTE_REQUESTED`, `PROJECT_ARCHIVED`.
  `TEXT_*` are the library's texts, beyond kltn-docs.

---

## 8. Preflight rules (server)

Every finding blocks; there is no warning level (B18). Each finding has a `rule`:

| `rule` | Meaning | `values` |
|---|---|---|
| `EMPTY_DESIGN` | nothing on any surface | |
| `ELEMENT_INVALID` | an element the rules cannot read | |
| `OUTSIDE_PRINT_AREA` | an image or drawing past the bleed, or text anchored off the trim | |
| `CROSSES_SAFE_AREA` | an image or drawing over the safe line (the cup's glue flap included), or text anchored outside it. An element covering the whole trim is a background and may reach into the bleed | |
| `DPI_BELOW_MIN` | effective resolution below the template's `minDpi` (default 300) | `dpi`, `minDpi` |
| `ASSET_INVALID` | not this design's image, or a type or size the template no longer takes | |
| `FONT_NOT_ALLOWED` | a font outside `allowedFontCodes` | |
| `VARIANT_UNAVAILABLE` | the variant or product can no longer be designed on | |
| `CARTON_SPEC_MISSING` | the variant has no carton specification (B25) | |
| `PRINT_FILE_INVALID` | the print file or spec is not a PDF, or the print file is not one page per print area of its bleed size | |

The server has no font metrics, so it checks text by its anchor only. How far text reaches is the studio's to check.

This list is the whole set today. A new or renamed `rule` is announced to the frontends before it ships.

---

## 9. Error codes (design)

**409:**
- `DESIGN_DRAFT_LIMIT_REACHED`, `DESIGN_NOT_DRAFT`, `DESIGN_NOT_APPROVED`;
- `DESIGN_FILES_OUTDATED` (`details.roles`), `DESIGN_PRINT_FILE_INVALID`, `DESIGN_ASSET_LIMIT_REACHED`;
- `DESIGN_PROJECT_ARCHIVED`, `DESIGN_PROJECT_FULL`, `DESIGN_PROJECT_LIBRARY_FULL`, `DESIGN_PROJECT_HAS_PENDING`;
- `DESIGN_PENDING_IN_PROJECT`, `DESIGN_IN_OPEN_QUOTE_REQUEST`, `DESIGN_CLONE_TARGET_INVALID` (`DESIGN_IN_ANOTHER_PROJECT` is no longer sent: a design moves);
- `CUSTOMER_NOT_ACTIVE`, `CUSTOMER_SKU_SOURCE_UNAVAILABLE`, `CONFLICT` (stale version);
- in a batch, `NOT_FOUND`, `CONFLICT` and `DESIGN_NOT_DRAFT` carry `details.designIds` and `details.designs`.

**Other statuses:**
- 422: `DESIGN_PREFLIGHT_FAILED` (`details.issues`), `DESIGN_BATCH_PREFLIGHT_FAILED` (`details.designs`);
- 415: `UNSUPPORTED_MEDIA_TYPE`;
- 413: `PAYLOAD_TOO_LARGE`;
- 429: `RATE_LIMITED`;
- 404: `NOT_FOUND`, for anything not the caller's.

Every message has Vietnamese text in the `_vi` bundle. Show `errorCode`-specific copy in the UI rather than the
`message`.

---

## 10. What the storefront must change (from the 2026-11-05 build)

**Status and lifecycle:**
- `designStatusSchema` is `DRAFT | PENDING_REVIEW | APPROVED | ARCHIVED`.
- Drop `reviewPassed`, `awaitingConfirmation` and "Chốt thiết kế".
- Add "Chờ duyệt" and "Bị trả lại (lý do)" using `reviewNotes`.

**Submitting ("Gửi duyệt"):**
1. Save the layout.
2. Render and upload `CUSTOMER_PREVIEW`, `PRINT_READY` and `TECHNICAL_SPEC` with the layout's `documentChecksum`.
3. Re-read the design's `version`.
4. Submit.

Show `DESIGN_PREFLIGHT_FAILED` issues per element, and select the element when its issue is clicked.

**The studio's own preflight:** `CROSSES_SAFE_AREA` blocks, except for a background covering the trim. There is no DPI
warning level, and the minimum is the template's `minDpi` (300).

**Add:**
- deleting a draft, renaming;
- the "Yêu cầu báo giá" box (cartons, delivery date) and "Gửi duyệt và yêu cầu báo giá";
- showing the open request;
- the own SKU on an approved design.

**Projects:**
- a list, and the project screen with designs side by side;
- adding and removing designs;
- the library and "place from library";
- "Gửi duyệt cả bộ" with per-design errors;
- "Yêu cầu báo giá cả dự án".

**Remove:** `reviewerUserId` and QC from the UI; the reviewer is the Sales Manager.

**Backoffice:**
- the review queue and rounds (decide per design, "Duyệt tất cả");
- the quote request lists with the close action;
- templates.

---

## 11. Known limits

- **PDF/X:** the stamped print file is identified as PDF/X-4 (TrimBox, BleedBox, `GTS_PDFXVersion`, `Trapped`) and
  carries one `GTS_PDFX` output intent from `stockflow.design.print.output-intent`, in place of the studio's: by
  default ISO Coated v2 (ECI), condition `FOGRA39`, info "ISO Coated v2 (ECI)", registry `http://www.color.org` - the
  file `ISOcoated_v2_eci.icc` bundled with the server, byte for byte the one the storefront embeds from
  `public/print/icc/`. A printer's own profile replaces it by configuration (`DESIGN_PRINT_ICC_PROFILE`,
  `DESIGN_PRINT_OUTPUT_CONDITION`, `DESIGN_PRINT_OUTPUT_INFO`); the production instruction says which intent is
  embedded. Clear PET cups (dry offset, white underlay) will need a printing condition of their own. Colours are never
  converted to the profile, and nothing in the build checks conformance (no veraPDF).
- **Quotes:** writing a quote from a request, with the customer's SKU filled in, is plan D5 and belongs to the order
  module. Quotes, orders and production orders still use the blank SKU and snapshot until then.
- **Library formats:** the library takes the image types a design takes (PNG, JPEG, WebP). B43's SVG and PDF are
  refused, because no template could place them.
- **Text extent:** the server cannot check how far text reaches (see §8).
- **One formula in three places.** The print areas are computed from the model by the server
  (`CupGeometry`, `PrintConfigRules`), the storefront and the back office alike. `PrintConfigRulesTest` pins the
  server's with literal numbers, the same as the frontends' test: a 90 / 60 / 110 mm cup, band 6 mm below the rim
  and 8 mm above the base, 8 mm seam → inner radius 230.04 mm, outer 327.05 mm, sweep 0.84894 rad, box 279.67 ×
  120.85 mm. Whichever side changes the formula fails its own test. The server rounds to 0.01 mm when it stores.
