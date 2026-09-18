# SCRUM-43: product images and design artifacts

> Historical baseline from the initial SCRUM-43 commit. Lifecycle, gallery, scanning and retry
> behavior below are superseded by [SCRUM-53/54 implementation](SCRUM-53-54-implementation.md).
> Use that document for the current HTTP contract and outstanding release gates.

## HTTP contract

All paths below are relative to `/api/v1`. Responses use `ApiResponse`. Uploads are
`multipart/form-data` with exactly one file part named `file`. The parent must already exist.

| Method | Path | Permission | Result |
| --- | --- | --- | --- |
| POST | `/products/{productId}/images` | `product-products:UPDATE` | 201, image metadata |
| GET | `/products/{productId}/images` | `product-products:READ` | Ordered gallery metadata |
| GET | `/products/{productId}/images/{imageId}/download-url` | `product-products:READ` | URL and `expiresAt` |
| POST | `/designs/{designId}/artifacts` | `designs:UPDATE` | 201, artifact metadata and SHA-256 |
| GET | `/designs/{designId}/artifacts` | `designs:READ` | Artifact revisions, newest first |
| GET | `/designs/{designId}/artifacts/{artifactId}/download-url` | `designs:READ` | URL and `expiresAt` |

Image metadata: `imageId`, `sortOrder`, `originalName`, `contentType`, `sizeBytes`, `storedAt`.
Legacy gallery rows instead carry `legacyUrl`. Artifact metadata: `artifactId`, `designId`,
`originalName`, `contentType`, `sizeBytes`, `storedAt`, `checksum`.
The first image by `sortOrder` is the cover. Download responses have `Cache-Control: no-store`.
Only the backend sees storage keys. Callers request URLs using business IDs, never arbitrary keys.

```sh
curl -H "Authorization: Bearer $TOKEN" \
  -F "file=@cup.png;type=image/png" \
  http://localhost:8080/api/v1/products/PRODUCT_ID/images
```

## Business rules and existing clients

Products accept uploads only in `DRAFT`. Existing JSON create/update contracts still accept
legacy external image URLs. Uploaded files are managed separately: updating legacy `images`
does not remove uploaded attachments. Product detail's existing `images` field remains the
legacy URL list; clients must use the media endpoints to display the complete gallery.
No signed URL is persisted, cached in a product summary, or published in a product event.

Design access requires BOTH the endpoint permission and a matching `owner_user_id` or
`assigned_user_id`. These are identity user UUIDs, distinct from CRM `customer_id`.
The migration deliberately does not infer owners for existing drafts. The studio's trusted
draft creation/assignment workflow must set those columns. An unassigned legacy draft returns
404, including to users with broad RBAC scope. In local security-off mode, design endpoints
still require an authenticated identity for ownership checks.

Each upload appends a revision, updates `current_artifact_id`, and resets `preflight_passed`.
Only `DRAFT` without an existing confirmed snapshot is editable. Old files are retained;
there is no endpoint that overwrites or deletes confirmed content. Snapshot creation and any
future confirmation service must lock the same draft row before checking the current artifact
and preflight result. This patch does not implement draft creation, assignment, preflight,
customer confirmation, snapshot JSON generation, order relinking, or packing verification.
Warehouse access through an authorized order is a future fulfillment use case, not an
ownership bypass on these private design endpoints.

## Existing storage infrastructure

Services call the existing `FileStorage` through a small transaction coordinator, `FileTransfers`.
`ContentTypePolicy` remains the only file-type/category validator. Existing limits are unchanged:
product JPEG/PNG/WebP <= 10 MiB; design JPEG/PNG/WebP/PDF <= 50 MiB. No JSON/SVG category is added.
SHA-256 is computed from consumed file bytes on the server, not accepted from the client.

`S3Presigner` is supplied by the existing AWS SDK `s3` artifact. Its endpoint, credentials,
region and path-style addressing match the upload client. The configured endpoint must be
reachable from the browser as well as the server. Do not rewrite the signed URL's host.
Presigned download URLs last `stockflow.storage.presigned-url-ttl` (default ten minutes,
positive and at most one hour). Anyone holding the URL can use it until expiry; changing a
permission does not revoke an already issued URL immediately. Keep URLs out of logs.

Use MinIO/S3 for browser downloads. Local disk supports upload/server-side read tests only;
the HTTP download-url endpoint returns 503 rather than exposing a server `file:` URI.
This story supplies private object-store delivery, not a deployed CDN distribution. Public
storefront CDN delivery needs its own publishing policy, provider/domain and cache setup.

## Failure behavior

| Condition | HTTP / code |
| --- | --- |
| Category limit exceeded, or servlet multipart limit exceeded | 413 / `PAYLOAD_TOO_LARGE` |
| Unsupported MIME, mismatching signature, or wrong request media type | 415 / `UNSUPPORTED_MEDIA_TYPE` |
| Empty file or invalid filename metadata | 400 / `VALIDATION_FAILED` |
| Backend service/network/disk failure | 503 / `STORAGE_ERROR` |
| Missing parent/attachment, or design outside owner/assignee scope | 404 |
| Parent is no longer editable | 409 |

Servlet limits are 50 MiB/file and 51 MiB/request; product's stricter limit is enforced by the
existing policy. Configure any reverse proxy limits and error envelope separately: requests
rejected by the proxy never reach Spring. The shared handler sanitizes backend 5xx messages.

Database rollback deletes a successfully stored new object. Cleanup errors are logged without
replacing the original failure. Unknown commit outcomes preserve objects. A process crash or
cleanup outage can still leave an orphan requiring reconciliation; no destructive retention
job is introduced. Multipart bypasses the existing JSON idempotency filter: retrying a successful
upload may create another attachment. The client should refresh the list after an ambiguous result.

## Verification

```sh
mvn test -Dtest=ModularityTest,ArchitectureTest,StoragePolicyTest,ProductTest,FileStorageIntegrationTest,ProductImageServiceTest,DesignArtifactServiceTest,ProductImageHttpTest,DesignArtifactHttpTest,MediaPersistenceIntegrationTest
python -X utf8 tools/verify.py
```

`ProductImageHttpTest` uses embedded Tomcat and actual HTTP multipart requests to verify the
servlet size failure, error envelopes and permission interception. The storage tests exercise
local disk, rollback cleanup, an S3 SDK connection failure, and real URL signing without network.
`MediaPersistenceIntegrationTest` needs Docker/PostgreSQL and explicitly skips if Docker is absent.
The existing static verifier reports 11 test-only dependency violations on unmodified main too.

On this Windows host, Java's default UNIX-domain socket temp path failed with `Invalid argument:
connect`. For the HTTP test use an existing short directory with
`-Djdk.net.unixdomain.tmpdir=<absolute-directory>`; no production JVM settings were changed.
