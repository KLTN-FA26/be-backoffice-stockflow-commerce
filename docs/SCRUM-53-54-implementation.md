# SCRUM-53 / SCRUM-54: media implementation contract

## Scope and release boundary

The backend supports managed product-image uploads, product/variant galleries and public
CloudFront delivery, plus an assisted multi-artifact design workflow through technical review,
customer confirmation, immutable snapshots and packing-time verification.
It reuses `FileStorage`, `FileTransfers`, `StorageProperties`, `FileCategory`, `StorageKeys`,
`ContentTypePolicy` and `ObjectStorageHealthIndicator`; no replacement storage backend was built.

The repository implementation for SCRUM-53/54 is complete. AWS account provisioning, DNS,
credentials and real-user UAT remain deployment activities and cannot be inferred by source code.

## Product images (SCRUM-53)

Paths are relative to `/api/v1`; responses use `ApiResponse`. Uploads use multipart part `file`.
The parent must exist. Storage keys are never accepted from the caller.

| Method | Path | Permission / behavior |
| --- | --- | --- |
| POST | `/products/{id}/images` | `product-products:UPDATE`; original and three renditions |
| GET | `/products/{id}/images` | `product-products:READ`; asset library, not approved display order |
| GET | `/products/{id}/images/{imageId}/download-url` | READ; private original URL |
| GET | `/products/{id}/images/{imageId}/renditions/{edge}/download-url` | READ; private rendition URL |
| GET | `/products/{id}/gallery` | READ; working and approved (`published`) lists plus revision |
| PUT | `/products/{id}/gallery` | UPDATE; `{revision, items:[{imageId, caption}]}` |
| POST | `/products/{id}/gallery/approval` | APPROVE; `{revision}`; editor cannot approve own changes |

Flow:

1. Create the product through the existing product master endpoint.
2. Upload JPEG/PNG/WebP, at most 10 MiB. Decode with a 40-million-pixel ceiling; normalize EXIF
   orientation and preserve transparency. Scan the original with ClamAV.
3. Store the original under `product-originals/` and longest-edge 256/768/1600 renditions (no
   upscaling) under `product-renditions/`; both are private. Approving a gallery copies the renditions
   of the approved images to `product-images/`, the only prefix the CDN serves, so an image is public
   only after someone other than its editor approved it. The copy is made inside the approving
   transaction and removed again if that rolls back; it is never removed afterwards (URLs are
   immutable and cached for a year).
   Decode work, the virus scan and the object-store writes run outside any database transaction;
   two short transactions bracket them (read to fail fast, locked write to attach). Display files are
   PNG for alpha images, JPEG at 85% quality otherwise, without source metadata.
4. Edit up to 20 distinct managed image IDs; first is cover, caption at most 500 characters.
   Reorder/replace/remove changes the working list, not approved content or stored objects.
5. After the product is APPROVED/PUBLISHED, a different authorized actor approves the exact
   gallery revision atomically. Publication requires an approved non-empty product gallery.
   The anonymous storefront endpoint returns immutable CloudFront rendition URLs. Variant/SKU
   overrides are optional and otherwise inherit the product gallery.

Uploads/edits are allowed in DRAFT, APPROVED and PUBLISHED, blocked in PENDING_APPROVAL and
DISCONTINUED. Existing product approval remains separate. Original and rendition storage use
existing rollback cleanup. Processing is synchronous with bounded decoder concurrency.

Legacy JSON `images` URLs remain readable and uploaded attachments survive legacy detail updates.
External URLs are not inspected and cannot enter the new gallery. Re-upload old stored images
without renditions before approving them. Originals can retain metadata; display renditions do not.

## Design artifacts (SCRUM-54)

| Method | Path | Permission / additional scope |
| --- | --- | --- |
| POST | `/designs` | `design-administration:CREATE`; trusted assisted creation |
| PUT | `/designs/{id}/assignment` | `design-administration:UPDATE`; designer/reviewer assignment only |
| GET | `/designs`, `/designs/{id}`, `/designs/{id}/history` | `design-designs:READ`; owner, designer or reviewer |
| GET | `/designs/{id}/snapshot` | READ; confirmed evidence for this draft, no storage keys |
| POST | `/designs/{id}/artifacts?role=...` | `design-designs:UPDATE`; owner/designer, DRAFT only |
| GET | `/designs/{id}/artifacts` | READ; scoped revision metadata |
| GET | `/designs/{id}/artifacts/{artifactId}/download-url` | READ; scoped private URL |
| PUT | `/designs/{id}/specification` | UPDATE; `{version, spec}` |
| POST | `/designs/{id}/technical-reviews` | APPROVE; assigned reviewer; `{version, artifactId, passed, notes}` |
| POST | `/designs/{id}/confirmation-requests` | UPDATE; owner/designer; `{version}` |
| POST | `/designs/{id}/change-requests` | UPDATE; owner only; `{version, reason}` |
| POST | `/designs/{id}/confirmation-withdrawals` | UPDATE; owner/designer; `{version, reason}` |
| POST | `/designs/{id}/confirmations` | UPDATE; owner only; `{version, artifactId}` |
| POST | `/designs/{id}/revisions` | UPDATE; owner/designer; fork APPROVED design |

Create body: `customerId`, `productId`, `ownerUserId`, optional `assignedUserId`, `reviewerUserId`,
`name` (200 characters), `spec` (4000 characters). Assignment body: `version`, optional
`designerId`, `reviewerId`. Review notes/customer reasons are required, at most 2000 characters.
Use the latest response's version/revision, not a client-side counter.

`customerId` is a CRM ID, not an identity UUID. Only trusted administrators may establish the
customer-to-login binding in assisted creation. **Verified CRM/login linking and self-service
creation are not implemented.** Do not grant `design-administration` to ordinary customers.
Legacy ownership is not inferred. Inaccessible drafts return 404 even with broad RBAC rights.

`DRAFT -> technical review passed -> SUBMITTED -> customer confirms -> APPROVED snapshot`

- Upload appends an artifact for one bundle role, computes server SHA-256 and makes that role current. JPEG/PNG/WebP/PDF
  use existing DESIGN_RENDER policy (50 MiB). Original bytes are scanned before storage.
- Changing artifact/specification invalidates technical approval. Review is a recorded human
  decision, **not** automated DPI/font/printability analysis.
- Reviewer must be assigned and differ from customer, assigned designer and last editor.
  Failed review leaves DRAFT with notes.
- Sending requires the current artifact and passing review. SUBMITTED blocks edits. Customer
  changes or owner/designer withdrawal returns to DRAFT and requires another review.
  Administrative reassignment also invalidates review and pending confirmation.
- Only `ownerUserId` confirms; staff cannot confirm for the customer. Snapshot binds the full
  artifact manifest, aggregate checksum, specification, reviewer evidence, customer identity and
  timestamp. Every stored object is re-read/scanned before committing confirmation.
- Repeating confirmation for the same artifact returns the same snapshot. Stale version or
  different artifact does not silently confirm a newer design.
- Database triggers reject snapshot/history UPDATE and DELETE. APIs never overwrite confirmed
  keys. Production IAM, backup and bucket versioning are still needed against privileged edits.
- Fork creates a new DRAFT linked by `parentSnapshotId`, without an active artifact. Upload,
  review and confirmation must run again; the old snapshot remains unchanged.
- New order lines with snapshots verify customer, product/SKU membership, stored checksum and
  scan result; they persist checksum alongside snapshot ID. Legacy orders are not rewritten.

## Retry, concurrency and failures

Uploads accept optional `Idempotency-Key`, scoped to actor and parent, persisted with a unique
constraint. Same key/name/MIME/bytes returns the existing attachment; different content returns
422 `IDEMPOTENCY_KEY_REUSED`. Without a key, retries can create attachments. Image replay still
decodes input but stores no new objects. Confirmation itself is replay-safe.

Mutations lock the parent row. Design actions check entity version; gallery actions check
gallery revision. Reload after a conflict rather than retrying stale input.

| Condition | HTTP / code |
| --- | --- |
| Category/servlet limit exceeded; image pixel ceiling | 413 / `PAYLOAD_TOO_LARGE` |
| Unsupported type or signature mismatch | 415 / `UNSUPPORTED_MEDIA_TYPE` |
| Empty/malformed image, invalid metadata, infected file | 400 / `VALIDATION_FAILED` |
| Storage or scanner unavailable/inconclusive | 503 / `STORAGE_ERROR` |
| Missing/inaccessible object | 404 |
| Wrong lifecycle, stale version, checksum mismatch | 409 |
| Decoder/scanner concurrency capacity exhausted | 429 |

Servlet limits remain 50 MiB/file and 51 MiB/request; product policy enforces 10 MiB. Proxy
limits/envelopes need separate configuration. Scanner fails closed, but cannot guarantee
detection of every malicious document. Legacy files need a reinspection/migration policy.

Rollback removes newly stored objects; cleanup failures do not replace the original error.
Crashes can leave orphans. No destructive retention job is introduced; removing gallery items
does not remove objects or confirmed designs.

## Runtime and delivery

New variables: `STOCKFLOW_UPLOAD_INSPECTION_HOST` (localhost for host-run backend),
`STOCKFLOW_UPLOAD_INSPECTION_PORT` (3310). Scanner is mandatory for uploads/snapshot verification:

```sh
docker compose -f docker-compose.yml -f docker-compose.media.yml up -d clamav
```

`stockflow.upload-inspection.enabled` defaults to `true` (fail closed); only the `local` profile turns
it off so a laptop without ClamAV can still upload. The overlay raises clamd's `StreamMaxLength`,
`MaxFileSize` and `AlertExceedsMax` so a file above a limit is rejected, never silently unscanned.

Wait for healthy status/signature loading. The overlay sets container-run `app` host to `clamav`.
Spring does not automatically load root `.env`: import it into IDE/shell or use Compose mappings.
Never commit `.env` credentials. The additive overlay does not override unrelated base settings.

Private browser downloads use short-lived S3 signed URLs with no-store headers. Public product
renditions use CloudFront; `infra/aws/media-cloudfront.yaml` provisions a private versioned bucket,
OAC, product-prefix-only read policy, cache/CORS policies and an application IAM managed policy.
Local storage supports backend reads but download-url returns 503 rather than exposing `file:` URIs.

The MinIO test uses official Quay because Docker Hub pull failed on this host. The pinned image
is a test fixture, not a new production recommendation. References:
[MinIO namespace](https://github.com/minio/minio/blob/master/docs/docker/README.md),
[ClamAV Docker](https://docs.clamav.net/manual/Installing/Docker.html),
[ClamD protocol](https://docs.clamav.net/manual/Usage/ClamdProtocol.html).

## Verification and deployment acceptance gates

Relevant suites: `ModularityTest`, `ArchitectureTest`, `StoragePolicyTest`,
`FileStorageIntegrationTest`, `ProductImageProcessorTest`, `ProductImageServiceTest`,
`ProductGalleryServiceTest`, `ProductImageHttpTest`, `DesignArtifactServiceTest`,
`DesignArtifactHttpTest`, `DesignWorkflowTest`, `DesignWorkflowIntegrationTest`,
`MediaPersistenceIntegrationTest`, `UploadInspectionTest`, `MinioMediaIntegrationTest`,
`ClamAvMediaIntegrationTest`, `PlaceOrderIntegrationTest`. Container tests require Docker and
may skip without it. Windows may require a short existing directory via
`-Djdk.net.unixdomain.tmpdir=<absolute-directory>`. Also run `python -X utf8 tools/verify.py`;
the pre-existing static baseline contains 11 test-only dependency violations.

The implementation now includes order-scoped warehouse downloads, packing checksum evidence,
non-bypassable holds, QC/manager reverification, variant galleries and multi-role design bundles.
Before production acceptance, deploy the CloudFormation stack, connect the runtime IAM role,
configure the CloudFront output, restore Docker for the real PostgreSQL/MinIO/ClamAV regression,
and run the real-user/browser UAT checklist in
`docs/business-design/07-scrum-43-api-report.md`. Retention/orphan reconciliation, legacy
reinspection, backup drills, load tests and automated print preflight are operational or separate
product stories; none should be represented as an upload/storage implementation.

### Local verification record (2026-09-18)

- The final focused suite completed successfully, including architecture/modularity, HTTP 413/415/503,
  image processing, upload replay, public/variant gallery, design domain and fulfillment hold tests.
- Full `mvn test` discovered 258 tests: 237 passed, 0 assertion failures, 11 container tests skipped,
  and 10 context errors confined to `InventoryEventIntegrationTest`, `InventoryModuleTest` and
  `PlaceOrderIntegrationTest` because Testcontainers could not connect to Docker Desktop.
- Earlier real PostgreSQL media/workflow tests, MinIO presigned-download test and ClamAV clean
  scan test completed successfully. Their first infrastructure failures were resolved by
  using the official Quay image and waiting for ClamD health rather than an open port alone.
- The latest migrations (variant gallery, artifact manifest, order hold, fulfillment evidence and
  RBAC additions) still require the real PostgreSQL/container regression after Docker is restored.
  No Docker reset or application-data deletion was performed.
- Static verification still reports the same 11 pre-existing test-only dependency violations;
  `git diff --check` passes. Real `.env` remains ignored by Git.
