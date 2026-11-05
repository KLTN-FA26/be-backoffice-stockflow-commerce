# Direct uploads: the frontend contract

Files can now go from the browser straight to object storage instead of through the backend. The
multipart endpoints keep working unchanged; move to this flow screen by screen. Both ways end in the
same checks (type by its bytes, size, malware scan, SHA-256) before a file is attached to anything.

## Why switch

- A 50 MB design no longer ties up a backend request for the whole of a slow upload.
- Phone photos can be uploaded the moment they are taken, while the user fills in the form; the
  final "Report damage" / "Add photo" call is a small JSON request.
- The final call is JSON, so it is retried safely (the platform's `Idempotency-Key` filter applies,
  and attaching the same upload twice answers with the first result).

## The three steps

```
1. POST <slot endpoint>        {fileName, contentType, sizeBytes, sha256}   -> 201 {uploadId, method, url, headers, expiresAt}
2. PUT  <url>                  body = the file, headers = exactly `headers`  -> 200 from storage
3. POST <attach endpoint>      {uploadId} (or photoUploadIds: [...])        -> 201/200 the record
```

1. **Declare the file.** Compute the SHA-256 in the browser and send the declaration:

   ```js
   const bytes = await file.arrayBuffer();
   const digest = await crypto.subtle.digest('SHA-256', bytes);
   const sha256 = [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, '0')).join('');
   const slot = await api.post(slotUrl, {
     fileName: file.name, contentType: file.type, sizeBytes: file.size, sha256,
   });
   ```

   The response is `Cache-Control: no-store`: the URL inside is a short-lived credential. Never log it
   or store it. Refusals: `415 UNSUPPORTED_MEDIA_TYPE`, `413 PAYLOAD_TOO_LARGE`, `400 VALIDATION_FAILED`
   (bad declaration), `429 RATE_LIMITED` (too many slots this hour), `404/409` (the record cannot take
   a file now - nothing to upload).

2. **Upload.** `PUT` the bytes to `slot.url` with every header in `slot.headers` (they are part of the
   signature; `Content-Length` is set by the browser from the body). Storage refuses any other bytes.

   ```js
   await fetch(slot.url, { method: 'PUT', headers: slot.headers, body: file });
   ```

   The URL is valid for 15 minutes from the slot (`expiresAt`); a transfer that has started finishes
   even if it crosses the limit. Expired before starting: ask for a new slot.

3. **Attach.** Name the `uploadId` in the record's own request. A `409 CONFLICT` means the same upload
   is being attached by another request right now: retry after a moment, and the answer is the record.
   `400 VALIDATION_FAILED` means the stored file is not the declared one (or was never uploaded),
   `415` that its bytes are not the declared type. An upload must be attached within an hour.

**Compress phone photos before step 1** (longest edge about 2048 px, JPEG quality about 0.8, e.g. a
canvas `toBlob`): a 5 MB photo becomes a few hundred KB, and the SHA-256 is of the compressed file.

## Endpoints

| Flow | Slot (step 1) | Attach (step 3, `application/json`) |
|---|---|---|
| Design revision | `POST /api/v1/designs/{designId}/artifacts/uploads` | `POST /api/v1/designs/{designId}/artifacts` `{uploadId, role}` |
| Return photo, customer / Sales | `POST /api/v1/returns/{returnId}/evidence/uploads` | `POST /api/v1/returns/{returnId}/evidence` `{uploadId}` |
| Return photo, warehouse | `POST /api/v1/returns/{returnId}/receipt-evidence/uploads` | `POST /api/v1/returns/{returnId}/receipt-evidence` `{uploadId}` |
| Return photo, QC | `POST /api/v1/returns/{returnId}/inspection-evidence/uploads` | `POST /api/v1/returns/{returnId}/inspection-evidence` `{uploadId}` |
| Damage on the shelf | `POST /api/v1/inventory/damage-reports/photo-uploads` | `POST /api/v1/inventory/damage-reports` body of `report` + `photoUploadIds: [...]` |
| Damage while picking | `POST /api/v1/fulfillment/tasks/{taskId}/damage-photo-uploads` | `POST /api/v1/fulfillment/tasks/{taskId}/pick-lines/{lineId}/damage` body of `report` + `photoUploadIds` |
| Damage at packing | `POST /api/v1/fulfillment/tasks/{taskId}/packing-damage-photo-uploads` | `POST /api/v1/fulfillment/tasks/{taskId}/pick-lines/{lineId}/damage-after-pick` body of `report` + `photoUploadIds` |
| Supplier invoice scan | `POST /api/v1/supplier-invoices/{invoiceId}/scan/uploads` | `POST /api/v1/supplier-invoices/{invoiceId}/scan` `{uploadId}` |
| Purchase order document (new) | `POST /api/v1/purchase-orders/{purchaseOrderId}/attachments/uploads` | `POST /api/v1/purchase-orders/{purchaseOrderId}/attachments` `{uploadId, category}` |

Purchase order documents are new: `category` is `QUOTATION`, `CONTRACT`, `SUPPLIER_CONFIRMATION`,
`DELIVERY_NOTE` or `OTHER`; multipart is `file` + `?category=`; `GET .../attachments` lists them and
`GET .../attachments/{attachmentId}/download-url` saves one under its name (audited). PDF, Word, Excel,
or a JPEG/PNG photo of a paper document, at most 25 MB.

The attach endpoints share their path with the multipart ones; the `Content-Type` of the request
picks which one runs. Each slot endpoint needs the same permission as its attach endpoint.

Accepted types and sizes are the category's: design PNG/JPEG/WebP/PDF up to 50 MB; return photos
JPEG/PNG/WebP up to 15 MB; damage photos JPEG/PNG up to 20 MB; invoice scans PDF/JPEG/PNG up to 15 MB.

## Design preflight (BR-DSG-001)

`GET /api/v1/designs/{designId}/artifacts/{artifactId}/preflight` answers `{passed, findings: [{code,
detail, blocking}]}` for one revision - show it to the designer after an upload and to the reviewer.
Blocking: `FONT_NOT_EMBEDDED`, `LOW_RESOLUTION` (an image below 150 dpi at its printed size),
`PDF_UNREADABLE`, `IMAGE_UNREADABLE`. Warnings: `RGB_COLOUR`, `NO_BLEED`, `RASTER_ARTWORK` (pixel size of
a JPEG/PNG/WebP, whose print size only the reviewer can judge). Passing a technical review
(`POST /designs/{id}/technical-reviews` with `passed: true`) is refused with 409 while a blocking
finding remains; rejecting is always allowed.

## Viewing and downloading

- Download links (`.../download-url`) now name the file: the browser saves `HD-0012.pdf`, not the
  storage key. Use them for documents.
- Image links (renditions, return and damage photos) are `inline` and may be cached by the browser
  for as long as the link lives (`expiresAt`); reuse a link instead of asking for a new one while it
  is valid.
- Return and damage photos are shown as display copies: upright, re-encoded, without the phone's
  metadata (GPS). Their detail views carry `thumbnailUrl` per photo (256 px); if a thumbnail fails to
  load (a photo stored before copies existed), fall back to the photo's `download-url`. Add
  `?original=true` to a photo's `download-url` to save it exactly as uploaded.
- The product media list carries `url` per rendition; no per-image link request is needed.
- Downloading a design file, the print file, an invoice scan, a product original or an original
  evidence photo is recorded in the audit log.
- A PDF with JavaScript, a Launch action, embedded media, an XFA form or a password is refused at
  upload (`400 VALIDATION_FAILED`); a design PDF also with attached files or encryption.
