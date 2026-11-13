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
allowedFontCodes?, active, model, printAreas}`.
- `model` is the cup or bag geometry:
  - `{kind: "parametric-cup", topDiameterMm, bottomDiameterMm, heightMm, rimMm, seamMm, bandTopOffsetMm,
    bandBottomOffsetMm, material}`;
  - or `{kind: "parametric-bag", widthMm, gussetMm, heightMm, topFoldMm, handle, material}`.
- The cup's sector is computed by the server.

### Designs (P1, P6)
| Endpoint | Body / query | Notes |
|---|---|---|
| `POST /api/v1/designs/mine` | `{productId, sku, templateCode, name, projectId?}` | 201. Empty layout. 409 `DESIGN_DRAFT_LIMIT_REACHED`; with `projectId` 409 `DESIGN_PROJECT_FULL` / `DESIGN_PROJECT_ARCHIVED` |
| `GET /api/v1/designs` | `?status=&page&size` | The caller's own designs, `ARCHIVED` excluded |
| `GET /api/v1/designs/{id}` | | The design (§6) |
| `PUT /api/v1/designs/{id}/name` | `{version, name}` | Drafts only, otherwise 409 `DESIGN_NOT_DRAFT` |
| `POST /api/v1/designs/{id}/archive` | `{version}` | Deletes a draft. An open quote request on it closes |
| `POST /api/v1/designs/{id}/revisions` | | From an approved design: copies its approved layout and images; counts against the 50 drafts. It joins the source's project while that project is open and has room; otherwise it stands alone |
| `POST /api/v1/designs/{id}/clones` | `{variantSku, document}`; header `Idempotency-Key` **required** | 201 `{design, notInProject?}`. A new draft of the source (any of the caller's designs but an archived one) on another ACTIVE variant of the same product, with the active template `document.templateCode` names; `document` is the layout fitted to that template (checked as a layout save, at most 1,000,000 bytes) and may place only the source's images. The clone gets the source's images **with the same `assetId`s**, so the layout needs no rewriting. Name `"<source name> · <sku>"`. Joins the source's project as a revision does; `notInProject` = `PROJECT_ARCHIVED` / `PROJECT_FULL` when it could not. 409 `DESIGN_CLONE_TARGET_INVALID` (same variant, another product, a variant that cannot be designed on), `DESIGN_DRAFT_LIMIT_REACHED`, `PRODUCT_NOT_CUSTOMIZABLE`; 404 `PRINT_CONFIG_NOT_FOUND`; 400 `VALIDATION_FAILED`; 413. Same key and body again: the first answer; same key, other body: 422 `IDEMPOTENCY_KEY_REUSED` |
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
- **The print file:**
  - one page per print area, in the template's order;
  - each page the size of that area's bleed box (±0.5 mm);
  - for a cup, the flattened sector's bounding box including bleed.
- **Reading:** `GET .../artifacts` lists them, `GET .../artifacts/{artifactId}/download-url` returns a signed link.
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
| `POST /api/v1/design-projects/{id}/designs` | `{designId}` | 409 `DESIGN_IN_ANOTHER_PROJECT`, `DESIGN_PROJECT_FULL` (20) |
| `DELETE /api/v1/design-projects/{id}/designs/{designId}` | | 409 `DESIGN_PENDING_IN_PROJECT` while it waits for review |
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
- if any fails, the answer is 422 `DESIGN_BATCH_PREFLIGHT_FAILED` with `details.designs: [{designId, issues? | roles?}]`;
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
updatedAt}`.

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

---

## 9. Error codes (design)

**409:**
- `DESIGN_DRAFT_LIMIT_REACHED`, `DESIGN_NOT_DRAFT`, `DESIGN_NOT_APPROVED`;
- `DESIGN_FILES_OUTDATED` (`details.roles`), `DESIGN_PRINT_FILE_INVALID`, `DESIGN_ASSET_LIMIT_REACHED`;
- `DESIGN_PROJECT_ARCHIVED`, `DESIGN_PROJECT_FULL`, `DESIGN_PROJECT_LIBRARY_FULL`, `DESIGN_PROJECT_HAS_PENDING`;
- `DESIGN_IN_ANOTHER_PROJECT`, `DESIGN_PENDING_IN_PROJECT`, `DESIGN_CLONE_TARGET_INVALID`;
- `CUSTOMER_NOT_ACTIVE`, `CUSTOMER_SKU_SOURCE_UNAVAILABLE`, `CONFLICT` (stale version).

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

- **PDF/X:** the stamped print file is identified as PDF/X-4 (TrimBox, BleedBox, `GTS_PDFXVersion`, `Trapped`) but
  has no ICC output intent until the printer's profile is chosen (B35).
- **Quotes:** writing a quote from a request, with the customer's SKU filled in, is plan D5 and belongs to the order
  module. Quotes, orders and production orders still use the blank SKU and snapshot until then.
- **Library formats:** the library takes the image types a design takes (PNG, JPEG, WebP). B43's SVG and PDF are
  refused, because no template could place them.
- **Text extent:** the server cannot check how far text reaches (see §8).
