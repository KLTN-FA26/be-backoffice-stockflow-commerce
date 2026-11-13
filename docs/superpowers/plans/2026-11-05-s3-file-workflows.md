# Kế hoạch tối ưu các flow file trên S3

> Nhánh `feature/s3-file-workflows` (từ `origin/develop` @ `9fe13f3e`).
> Bucket: `stockflow-456015014152-ap-southeast-2-an` (region `ap-southeast-2`).

## Trạng thái thực hiện (cập nhật 2026-11-06)

| Phase | Commit | Đã làm |
|---|---|---|
| 0 | `0b597dca` | Template CloudFormation dùng được bucket tạo tay, script cấu hình bucket, bucket policy chặn ở tầng S3, deploy chuyển MinIO ↔ AWS qua `.env`, health ClamAV, ClamAV chờ slot |
| 1 | `0cd34e9b` | Kiểm design 3 mức (checkout không gọi S3, `HEAD` checksum, prepress băm lại), Object Lock khi khách xác nhận |
| 2 | `28146d1c` | Quét + đẩy file ngoài transaction (design, trả hàng, hoá đơn, báo hỏng song song), link tải tên gốc, link xem cache được, audit tải file |
| 3 | `bd2cb33f` | Trình duyệt PUT thẳng lên S3 (`StagedUploads`) cho design, ảnh trả hàng, 3 luồng báo hỏng, scan hoá đơn |
| 4 | `2f59be18` | Bản xem ảnh bằng chứng không EXIF/GPS (256/1600 px), `?original=true` có audit, thumbnail trong danh sách |
| 5 | `13af7bc3`, `fd2bec3f` | Từ chối PDF có JavaScript/Launch/XFA/media/mật khẩu; job dọn file mồ côi (mặc định chỉ báo cáo) |
| 6 | `9b0d2b8a` | Preflight tự động (font nhúng, ≥150 dpi, RGB, bleed), chặn duyệt kỹ thuật khi có lỗi chặn |
| 7 | `4cca4e73` | Đính kèm PO (bảng có sẵn, không cần migration) |
| Sửa sau review | `83a4cb3e` | Đính kèm PO theo phạm vi kho; gửi lại sau commit không xoá file đã gắn; PDF lọt JS qua chuỗi `/Next` và field; preflight đệ quy vô hạn; list S3 bỏ trang; ảnh không bị từ chối khi decoder bận |
| 7b | `c537ad06` | Ảnh nhận hàng (DRAFT/chờ duyệt, cả lô hoặc từng dòng) và ảnh QC nhập (IN_QC, chỉ dòng có QC): bảng mới `procurement.goods_receipt_photos` (migration `V20261106000100`), tối đa 10 ảnh/đối tượng, chỉ thêm không sửa/xoá |
| Sửa sau review lần 2 | `3a671ee3`, `d704795b`, `65e89a45`, `6ba7361d`, `67176513` | PDF: chỉ cho phép hành động điều hướng (chặn GoToE/GoToR/SubmitForm/…; chuỗi quá sâu, bookmark); tên file tải lấy đuôi theo loại file thật; ảnh giải mã từ đĩa khi có decoder rảnh (tránh hết RAM); ảnh dòng phiếu nhập không còn chặn đếm lại; preflight kiểm cả bộ file (cả PRINT_READY), chạy ngoài transaction và sau khi kiểm quyền; người review bị chặn upload trước khi đọc file; ảnh hỏng pick/pack xử lý trước khi khoá phiếu pick; MinIO tự dọn `incoming/` 1 ngày, `exports/` 7 ngày; ClamAV bật được bằng profile `scan` |
| Sửa sau review lần 3 | `41d545d2`, `ef067f0c`, `b606d99c`, `f9300266` | PDF: chặn hành động có `/F` (Thread mở file từ xa), `Named` chỉ 4 lệnh chuyển trang, link chỉ http/https/mailto; tên file giữ số phiên bản; trigger chỉ gỡ ảnh khi dòng thật sự bị xoá; preflight chỉ kiểm file in (preview + PRINT_READY), không chặn tài liệu hướng dẫn; gửi lại báo hỏng đang chạy song song được trả kết quả cũ thay vì 409 |

**Bạn cần tự làm (mình không có quyền / không thuộc repo này):**
1. AWS: `infra/aws/configure-existing-bucket.sh <bucket> ap-southeast-2 "<origin FE>"`, rồi deploy
   `infra/aws/media-cloudfront.yaml` (`UseExistingBucket=true`, cùng region), tạo IAM user gắn policy output,
   điền `.env` theo khối "object storage on AWS S3" trong `deploy/.env.example` (xem `deploy/README.md` §4a).
2. FE: làm theo `docs/direct-uploads-fe-contract.md` (luồng upload thẳng S3, thumbnail, preflight, đính kèm PO).
3. Quét virus trên VPS: ClamAV đang tắt vì thiếu RAM → máy ≥ 8 GB: đặt `COMPOSE_PROFILES=scan` và `UPLOAD_INSPECTION_ENABLED=true` trong `.env`; hoặc GuardDuty Malware Protection.
4. Chốt thời hạn Object Lock (`stockflow.storage.object-lock.*`, đang 3 năm design / 10 năm hoá đơn) với luật áp dụng.
5. Sau vài đêm đọc log báo cáo file mồ côi thấy đúng → bật `stockflow.storage.orphan-sweep.delete=true`.
6. (Tuỳ chọn) đổi tên `purchase_order_attachments.file_url` → `storage_key` cho đúng nghĩa (cột đang chứa key, không phải URL).

**Còn giới hạn (cần Design Studio API trước):** preflight dùng DPI tối thiểu cố định 150 và chưa kiểm vùng an toàn (safe area),
vì design chưa gắn với template (`min_dpi`, vùng in) — làm cùng P1/P2 của Design Studio.

**Chưa làm và vì sao:** ảnh chat (module chat chưa có API gửi tin nhắn), export báo cáo (chưa có API xuất file nào),
logo brand/ảnh danh mục (chưa có màn hình dùng). Design Studio 2D/3D: FE storefront đã có nhưng chạy mock; BE thiếu
P1–P6 + G11 theo `fe-storefront-stockflow-commerce/docs/superpowers/specs/2026-10-25-design-api-be-proposal.md`
(template 3D dựng từ thông số, không cần upload file GLB).

---

## 1. Hiện tại → sau khi làm

| Chỗ | Hiện tại | Sau khi làm | Được gì |
|---|---|---|---|
| **Kiểm file design** (11 chỗ gọi) | Mỗi lần tải lại cả bundle từ S3 (≤50 MB/file), quét ClamAV lại, băm SHA-256 — kể cả lúc checkout | Checkout/báo giá/mẫu: **0 lệnh S3**. Chỗ dùng file: 1 `HEAD` đọc checksum S3. Prepress: băm thật 1 lần | Checkout nhanh, hết 429 "inspection busy", đúng BR-DSG-003 / BR-PRD-02 |
| **Chống sửa file** | Chỉ phát hiện khi lệch | **Object Lock**: file design đã xác nhận và hoá đơn NCC không ai xoá/ghi đè được | Nghiệp vụ "thiết kế đã khoá là bất biến" được đảm bảo ở tầng lưu trữ |
| **Upload** design, ảnh trả hàng, hoá đơn NCC, ảnh hỏng | Khoá bản ghi DB suốt lúc quét virus + đẩy S3 | Quét + đẩy xong mới khoá, transaction vài ms | ClamAV chậm/chết không kéo sập cả hệ thống |
| **File lớn / ảnh điện thoại** | Trình duyệt → BE (ghi đĩa 2 lần) → S3 | Trình duyệt PUT thẳng lên S3 `incoming/`; S3 chặn sai cỡ ngay tại cửa; BE quét rồi copy trên S3 | BE không gánh chặng upload chậm; gửi lại không tạo bản trùng |
| **Nội dung nguy hiểm** | Chỉ ClamAV (mã độc đã biết) | + PDF có JavaScript/Launch/file nhúng bị từ chối; ảnh bằng chứng được mã hoá lại, xoá EXIF/GPS | Chặn được thứ ClamAV không biết; không lộ vị trí khách |
| **Xem ảnh** | FE gọi API lấy link từng ảnh, tải ảnh gốc 5–20 MB | Link ký sẵn trong danh sách, ảnh thumbnail 256 px | 20 ảnh = 1 request, nhẹ hơn hàng chục lần |
| **Tải file** | Tên UUID, không ai biết ai đã tải | Tên gốc; audit mỗi lần cấp link file nhạy cảm | Truy vết được ai tải hoá đơn / file in |
| **Ảnh storefront** | Chưa có CDN → gallery public 503 | CloudFront + OAC | Chạy được, cache vĩnh viễn |
| **Lưu trữ** | Không có chính sách; xoá ảnh sản phẩm để lại object | Thời hạn theo loại file + job tìm file mồ côi | Đúng luật kế toán / dữ liệu cá nhân, không tốn dung lượng rác |
| **Preflight** (BR-DSG-001) | Người review tick tay | Tự kiểm DPI, bleed, CMYK, font nhúng theo template | Đúng nghiệp vụ, review chỉ còn phần cần mắt người |

**Đã tốt, giữ nguyên:** ảnh sản phẩm (quét/đẩy ngoài transaction, 3 rendition, tách bản gốc private với bản public),
kiểm loại file theo byte đầu, key UUID bất biến, bucket private, link có hạn ≤ 1 giờ, rollback xoá object.

---

## 2. Các vấn đề trong code hiện tại

| # | Vấn đề | Ở đâu |
|---|---|---|
| P-1 | Verify design tải + quét + băm toàn bộ bundle mỗi lần | [`DesignServiceImpl.inspect/digest`](../../../src/main/java/com/stockflow/design/internal/service/DesignServiceImpl.java) (dòng 99, 120), gọi từ `OrderServiceImpl:220`, `QuoteService:568`, `SampleRequestService:181,407`, `DesignWorkflowService:181`, `DesignSnapshotQueryService:113`, `FulfillmentServiceImpl:265`, `PackingService:831`, `ProductionWorkflowService:66,88`, `QcReferenceService:80` |
| P-2 | Giữ khoá DB trong lúc ClamAV + PUT | `DesignArtifactService:52→72`, `ReturnRequestService:277→293`, `SupplierInvoiceServiceImpl:205`, `StockDamageService:161` (5 ảnh quét tuần tự) |
| P-3 | ClamAV `Semaphore(2)` + `tryAcquire()` → bận là 429 ngay | `UploadInspection:32,44` |
| P-4 | Mọi upload qua BE, multipart bị filter idempotency bỏ qua | các controller multipart |
| P-5 | Link: luôn `HEAD` trước khi ký, list không kèm URL, không `Content-Disposition`, không audit (0/7 endpoint `download-url` có `@Auditable`) | `FileTransfers:82`, các `…/download-url` |
| P-6 | Ảnh trả hàng / hỏng lưu nguyên bản kèm EXIF/GPS, xem là tải ảnh gốc | `ReturnRequestService.addEvidence`, `DamageEvidence.store` |
| P-7 | Region mặc định `ap-southeast-1` (bucket ở `-2`); thiếu `s3:ListBucket` thì `HEAD` key không có trả 403 → health DOWN, publish ảnh lỗi | `application-dev.yml:126`, `application-prod.yml:123` |
| P-8 | Javadoc `StorageConfig` nói static key chỉ dùng với MinIO, code dùng bất cứ khi nào có key | `StorageConfig` |

---

## 3. Thiết kế đích

### 3.1 Ba kiểu upload

| Kiểu | Dùng cho | Cách chạy |
|---|---|---|
| **U1 Direct-to-S3** | design, hoá đơn NCC, đính kèm PO, ảnh kho/trả hàng/QC | xin slot → PUT thẳng `incoming/` → khi gắn: BE kiểm + quét + copy sang prefix thật → transaction ngắn |
| **U2 Qua BE để xử lý ảnh** | ảnh sản phẩm | giữ như hiện tại |
| **U3 Qua BE, đã sửa tx** | endpoint multipart cũ, trong lúc FE chuyển sang U1 | quét + đẩy trước, khoá sau |

### 3.2 Luồng U1

```mermaid
sequenceDiagram
    participant FE as Trình duyệt / app kho
    participant BE as Backend
    participant S3
    participant AV as ClamAV
    FE->>BE: POST /…/uploads {name, type, size, sha256}
    BE->>BE: quyền + trạng thái nghiệp vụ + allow-list + quota → phiên Redis (TTL 1h)
    BE-->>FE: {uploadId, url PUT 15' — đã ký Content-Type, Content-Length, x-amz-checksum-sha256}
    FE->>S3: PUT incoming/… (S3 từ chối nếu sai cỡ hoặc sai hash)
    FE->>BE: POST /…/artifacts {uploadId}  (JSON → idempotency dùng lại được)
    BE->>S3: HEAD incoming (cỡ, type, checksum — lớp kiểm cuối)
    BE->>S3: GET incoming → 1 lượt đọc: magic bytes + ClamAV + SHA-256 + kiểm PDF / tạo rendition ảnh
    BE->>S3: CopyObject → <category>/…/{uploadId}.<ext> (ChecksumAlgorithm SHA256)
    BE->>BE: TX ngắn: INSERT; afterCommit → xoá incoming (+ đặt Object Lock nếu là hồ sơ)
    BE-->>FE: 201
```

- **Không cần migration:** phiên ở Redis; key chính thức dùng chính `uploadId` (UUIDv7) nên retry nhận ra qua unique
  constraint đã có (`storage_key`, `file_key`, `upload_key`); key rendition suy ra từ key gốc (như `publishedKeyOf`).
- **Endpoint xin slot đặt trong từng module**, dùng permission đã seed (`designs:UPDATE`, `inventory-damage-reports:CREATE`,
  `sales-rmas:CREATE`, …) → không phải seed permission mới. Logic nằm ở `common/storage` (`StagedUploads`).
- Slot gắn với `userId`; chỉ chính người đó + có quyền nghiệp vụ mới gắn được → `uploadId` lộ ra là vô dụng.
- Quét đồng bộ lúc gắn (50 MB ≈ vài giây). Chỉ chuyển sang S3 Event → SQS → worker khi đo thấy chậm.
- FE nén ảnh trước khi upload (cạnh dài ~2048 px) và upload ngay lúc chụp.

### 3.3 Kiểm file design theo mức

| Mức | Ở đâu | Làm gì |
|---|---|---|
| **DB** | checkout, báo giá, yêu cầu mẫu | chỉ kiểm snapshot đã xác nhận, đúng khách, đúng SKU; lấy checksum từ DB. **Không gọi S3** |
| **HEAD** | xác nhận design, preview, kho lấy file, chốt đóng gói, lấy print-file, QC | `HeadObject` + `ChecksumMode.ENABLED` so SHA-256 S3 đã lưu; preview chỉ kiểm đúng file đang xem |
| **DEEP** | prepress (cổng cuối trước khi in) | tải + băm thật, 1 lần / lệnh sản xuất |

ClamAV **không** nằm trong đường kiểm nữa: quét là việc của lúc upload. Object cũ chưa có SHA-256 trong S3 → fallback tải +
băm (không quét), kèm job backfill một lần (`CopyObject` vào chính nó với `ChecksumAlgorithm.SHA256`).

Vì sao `HEAD` là đủ: S3 chỉ lưu `checksumSHA256` sau khi tự tính trên byte nhận được và khớp. Ghi đè mà không có đúng
SHA-256 → checksum mất/khác → MISMATCH. Object Lock giữ lại bản gốc để khôi phục.

### 3.4 Chống sửa: Object Lock

- Bật Object Lock cho bucket (cần versioning), **không** đặt retention mặc định cho cả bucket (sẽ khoá cả `incoming/` và
  làm hỏng dọn dẹp khi rollback).
- Đặt retention **từng object, trong `afterCommit`** của sự kiện nghiệp vụ: khách xác nhận design → khoá mọi file trong
  manifest; gắn scan hoá đơn NCC → khoá scan đó.
- Dev/staging dùng `GOVERNANCE` (dọn được dữ liệu test bằng quyền bypass); production chốt `GOVERNANCE` hay `COMPLIANCE`.
- Có cờ `stockflow.storage.object-lock.enabled` để local/test (MinIO không bật lock, `LocalFileStorage`) bỏ qua.

### 3.5 Gỡ nội dung nguy hiểm (thêm vào bước quét)

- **PDF** (PDFBox, đã có trong pom): từ chối PDF có JavaScript, `OpenAction`/`AA` chạy JS, `Launch`, `EmbeddedFiles`,
  `RichMedia`, XFA, PDF mã hoá. Không rasterize lại — sẽ phá chất lượng in. Parse với bộ nhớ giới hạn + timeout.
- **Ảnh bằng chứng** (trả hàng, hỏng, nhận hàng, QC): giữ bản gốc private làm bằng chứng (có SHA-256, Object Lock nếu cần);
  sinh bản mã hoá lại 1600 + 256 px **không EXIF/GPS** để xem. Dùng chung lõi của `ProductImageProcessor` (chuyển phần
  decode/resize sang `common/storage`, giữ giới hạn 25 MP và semaphore).
- Khách chỉ thấy bản đã làm sạch; tải bản gốc là thao tác riêng, có audit.

### 3.6 Xem / tải

| Kiểu | Dùng cho | Cách |
|---|---|---|
| V1 Public | ảnh storefront đã publish | CloudFront + OAC trên `product-images/*` |
| V2 Xem riêng tư | thumbnail, ảnh bằng chứng, preview | URL ký sẵn trong response danh sách (ký là phép tính cục bộ), không `HEAD`, `inline`, `response-cache-control: private, max-age=<ttl>` |
| V3 Tải riêng tư | file in, hoá đơn, đính kèm, bản gốc ảnh | `HEAD` + ký, `attachment; filename*=UTF-8''<tên gốc>`, **audit** (`AuditAction.EXPORT`: ai, file nào, lúc nào) |
| V4 Sinh ra | nhãn PDF: stream thẳng; export: job → `exports/` → V3 | |

---

## 4. Từng flow

| Flow | Đích | Phase |
|---|---|---|
| Kiểm file design | 3 mức DB / HEAD / DEEP | 1 |
| Upload design | U3 → U1 + kiểm PDF + preflight tự động | 2 → 3 → 6 |
| Ảnh trả hàng (khách/kho/QC) | U3 → U1 + bản làm sạch | 2 → 3 → 4 |
| Ảnh báo hỏng (kệ/nhặt/đóng gói) | U3 (quét song song) → U1 `photoUploadIds` + bản làm sạch | 2 → 3 → 4 |
| Scan hoá đơn NCC | U3 → U1 + Object Lock + audit tải | 2 → 3 |
| Ảnh sản phẩm | giữ U2; thêm SHA-256 khi PUT; list kèm URL thumbnail | 2, 4 |
| Gallery storefront | CloudFront + OAC | 0 / 4 |
| Nhãn PDF | giữ nguyên | – |
| Chưa có: ảnh nhận hàng, ảnh QC nhập, đính kèm PO, ảnh chat, logo/danh mục, template in, export | xây thẳng trên U1 / V2 / V3 | 7 |

---

## 5. Cấu hình bucket

### 5.1 Biến môi trường

```bash
S3_BUCKET=stockflow-456015014152-ap-southeast-2-an
AWS_REGION=ap-southeast-2          # bắt buộc — yml mặc định ap-southeast-1
S3_ENDPOINT=                       # trống với S3 thật
S3_PATH_STYLE=false
S3_ACCESS_KEY=… / S3_SECRET_KEY=…  # chỉ khi BE không chạy trên AWS có IAM role
PRODUCT_MEDIA_CDN_BASE_URL=https://dxxxxxxxx.cloudfront.net
STOCKFLOW_UPLOAD_INSPECTION_HOST=clamav
```

### 5.2 IAM của app

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "Head404NotForbidden", "Effect": "Allow", "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::<BUCKET>" },
    { "Sid": "Objects", "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:PutObjectRetention"],
      "Resource": "arn:aws:s3:::<BUCKET>/*" }
  ]
}
```

Không cấp `s3:BypassGovernanceRetention` cho app.

### 5.3 Bucket policy (chặn ở tầng S3, code có lỗi cũng không lọt)

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "TlsOnly", "Effect": "Deny", "Principal": "*", "Action": "s3:*",
      "Resource": ["arn:aws:s3:::<BUCKET>", "arn:aws:s3:::<BUCKET>/*"],
      "Condition": { "Bool": { "aws:SecureTransport": "false" } } },
    { "Sid": "NoPresignedReadOfUnscanned", "Effect": "Deny", "Principal": "*", "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::<BUCKET>/incoming/*",
      "Condition": { "StringEquals": { "s3:authType": "REST-QUERY-STRING" } } },
    { "Sid": "PresignedUploadsOnlyIntoIncoming", "Effect": "Deny", "Principal": "*", "Action": "s3:PutObject",
      "NotResource": "arn:aws:s3:::<BUCKET>/incoming/*",
      "Condition": { "StringEquals": { "s3:authType": "REST-QUERY-STRING" } } },
    { "Sid": "LinksLiveAtMostOneHour", "Effect": "Deny", "Principal": "*", "Action": "s3:*",
      "Resource": "arn:aws:s3:::<BUCKET>/*",
      "Condition": { "NumericGreaterThan": { "s3:signatureAge": "3600000" } } },
    { "Sid": "CloudFrontReadsPublishedImagesOnly", "Effect": "Allow",
      "Principal": { "Service": "cloudfront.amazonaws.com" }, "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::<BUCKET>/product-images/*",
      "Condition": { "StringEquals": { "AWS:SourceArn": "arn:aws:cloudfront::<ACCOUNT>:distribution/<DIST_ID>" } } }
  ]
}
```

- Link ký (query-string) **không bao giờ** đọc được `incoming/`, và trình duyệt **chỉ** PUT được vào `incoming/`. Lệnh SDK
  của BE (header auth) vẫn đọc `incoming/` để quét.
- Policy giới hạn tuổi chữ ký 1 giờ, khớp với giới hạn trong `StorageProperties`.

### 5.4 Thiết lập khác

| Mục | Giá trị |
|---|---|
| Block Public Access | bật cả 4 |
| Mã hoá | SSE-S3 |
| Versioning + Object Lock | bật; không đặt retention mặc định |
| Lifecycle | `incoming/` 1 ngày · `exports/` 7 ngày · noncurrent versions 30 ngày (version bị khoá vẫn được giữ đến hết hạn khoá) · abort multipart 1 ngày |
| CORS | origin FE; `GET, PUT, HEAD`; headers `*`; expose `ETag` |
| Region | bucket ở Sydney. Nếu chưa có dữ liệu, cân nhắc tạo lại ở `ap-southeast-1` (gần VN hơn); BE trên AWS nên cùng region với bucket |

---

## 6. Lộ trình

Sau mỗi phase: `python3 tools/verify.py`, `mvn test -Dtest=ModularityTest,ArchitectureTest` + test phần đã sửa.
Làm trước **Phase 0–2**: phủ các điểm lợi nhất (kiểm 3 mức, Object Lock, chặn ở bucket, audit).

**Phase 0 — Hạ tầng (không đổi logic)**
- [ ] Mục 5: env, IAM, bucket policy, BPA, versioning + Object Lock, lifecycle, CORS
- [ ] Sửa javadoc `StorageConfig`; thêm health indicator ClamAV (`zPING`, không vào readiness)
- [ ] Deploy ClamAV (`docker-compose.media.yml` đã đúng giới hạn 52M) hoặc chốt GuardDuty Malware Protection
- Xong khi: `/actuator/health` báo storage UP với bucket thật; link ký vào `incoming/` bị 403; link > 1h bị 403

**Phase 1 — Kiểm design 3 mức + Object Lock**
- [ ] `FileStorage.stat(key)` (S3: `HeadObject` + `ChecksumMode.ENABLED`; Local: tự băm)
- [ ] `store` gửi `checksumSHA256` khi biết hash; `copy` thêm `ChecksumAlgorithm.SHA256`
- [ ] `verifySnapshot` / `verifySnapshotForSku` chỉ kiểm DB; `fulfillmentDownload` / preview / packing `HEAD`; prepress DEEP
- [ ] Bỏ `inspection.requireClean` khỏi `digest`; fallback băm cho object cũ + job backfill
- [ ] `FileStorage.lock(key, until)`; gọi trong `afterCommit` của `DesignWorkflowService.confirm`
- [ ] Test: MinIO Testcontainers xác nhận `HEAD` trả SHA-256; ghi đè byte khác → MISMATCH; object cũ → fallback
- Xong khi: checkout đơn có design sinh **0** request S3; preview sinh 1 `HEAD`

**Phase 2 — Ranh giới transaction + tải file + audit**
- [ ] Design / trả hàng / hoá đơn NCC: đọc không khoá → quét + `store` ngoài tx → tx ngắn khoá, kiểm lại, gắn (mẫu `ProductMediaService.upload/attach`)
- [ ] Báo hỏng: quét + đẩy 1–5 ảnh song song (executor giới hạn), trước khi mở tx
- [ ] `UploadInspection`: `max-concurrent` + `acquire-timeout` thành property; băm cùng lượt gửi ClamAV
- [ ] `presignedDownloadUrl(key, ttl, Disposition)`; `FileTransfers.viewLink` không `HEAD`; `ReturnRequestQueries` dùng `FileTransfers`
- [ ] `@Auditable` cho cấp link hoá đơn NCC, file in, design artifact, bản gốc ảnh bằng chứng
- [ ] Object Lock cho scan hoá đơn NCC (afterCommit)
- Xong khi: ClamAV giả chậm 5s, request khác sửa cùng bản ghi không phải chờ; tải hoá đơn ra file đúng tên gốc và có dòng audit

**Phase 3 — Direct upload (U1)** · cần FE
- [ ] `StagedUploads.begin/accept`, phiên Redis, `incoming/` trong `StorageKeys`, quota theo user, `FileTransfers.adopt`
- [ ] Ký `Content-Length` vào presigned PUT (kiểm bằng test; nếu SDK không ký được → presigned POST với `content-length-range`)
- [ ] Kiểm PDF có nội dung chủ động trong lượt quét
- [ ] Design trước, rồi ảnh hỏng (`photoUploadIds`), ảnh trả hàng, hoá đơn NCC; giữ multipart song song đến khi FE chuyển xong
- Xong khi: upload 50 MB mà BE không nhận request multipart; PUT sai cỡ bị S3 từ chối; gửi lại cùng `uploadId` trả bản ghi cũ

**Phase 4 — Xem ảnh**
- [ ] Bản làm sạch 1600/256 px cho ảnh bằng chứng (lõi xử lý ảnh dùng chung ở `common/storage`)
- [ ] List media / evidence / damage photos kèm `viewUrl` thumbnail + `expiresAt`
- [ ] CloudFront + OAC, set `PRODUCT_MEDIA_CDN_BASE_URL`
- Xong khi: trang 20 ảnh = 1 request API; ảnh khách xem không còn EXIF/GPS

**Phase 5 — Lưu trữ & dọn dẹp**
- [ ] Bảng thời hạn theo category (chốt ở mục 7), áp bằng lifecycle + Object Lock
- [ ] Job đối chiếu S3 (Inventory hoặc ListObjectsV2) với các cột key trong DB → báo cáo / xoá object mồ côi quá N ngày
- [ ] Xoá theo yêu cầu khách cho design / ảnh của họ, trừ hồ sơ đang bị khoá theo luật

**Phase 6 — Preflight tự động (BR-DSG-001)**
- [ ] Trong lượt quét: DPI, bleed/TrimBox, CMYK, font nhúng, định dạng & cỡ — luật lấy từ `customization_templates`
      (`min_dpi`, `color_mode`, `allowed_file_formats`, `max_file_size_mb`)
- [ ] FAIL chặn gửi duyệt; PASS vẫn qua review kỹ thuật như hiện tại

**Phase 7 — Flow chưa có, xây thẳng trên nền mới**
Ảnh nhận hàng, ảnh QC nhập, đính kèm PO (cần Tú đổi `file_url` → `storage_key`), ảnh chat, logo/ảnh danh mục, template in,
export báo cáo.

---

## 7. Cần chốt trước khi làm

1. BE deploy ở đâu (AWS có IAM role hay nơi khác dùng access key)?
2. Giữ bucket Sydney hay tạo lại Singapore khi chưa có dữ liệu?
3. ClamAV tự chạy (~1–1.5 GB RAM) hay GuardDuty Malware Protection (có phí theo GB quét)?
4. Object Lock production: `GOVERNANCE` hay `COMPLIANCE`?
5. Thời hạn lưu: hoá đơn NCC (Luật Kế toán — thường 10 năm, **cần đối chiếu văn bản**); design khách; ảnh bằng chứng (sau
   khi khiếu nại đóng bao lâu). Ảnh/design của khách là dữ liệu cá nhân — đối chiếu quy định bảo vệ dữ liệu cá nhân hiện
   hành về thời hạn và quyền yêu cầu xoá.
6. FE làm kịp Phase 3 không? Chưa thì dừng ở Phase 2 vẫn có phần lớn lợi ích phía server.

## 8. Rủi ro

- `mvn test` chưa từng chạy trên code này (CLAUDE.md §1) — chạy trước để biết test nào đỏ sẵn.
- Không thêm file vào `db/migration` (của Tú). Phase 0–6 không cần migration; Phase 7 (đính kèm PO) cần.
- Endpoint multipart là hợp đồng với FE — chỉ xoá khi FE đã chuyển hết.
- Phải xác nhận bằng test: MinIO hỗ trợ `x-amz-checksum-sha256` trên PUT/COPY/HEAD; SDK 2.31 ký `Content-Length` vào presigned PUT.
- PDFBox parse file của khách cũng là bề mặt tấn công: giới hạn bộ nhớ, timeout, số luồng.
- Link ký là chìa khoá tạm thời: không log, không lưu DB, không cache.

---

## Phụ lục: API file hiện có

| Flow | Endpoint |
|---|---|
| Design | `POST/GET /api/v1/designs/{designId}/artifacts`, `GET …/{artifactId}/download-url`, `GET /api/v1/design-snapshots/{snapshotId}/preview` |
| Design cho kho / sản xuất | `GET /api/v1/fulfillment/tasks/{taskId}/lines/{lineId}/design-artifacts/{role}/download-url`, `GET /api/v1/production/orders/{id}/print-file`, `POST …/prepress` |
| Ảnh sản phẩm | `POST/GET /api/v1/products/{productId}/variants/{variantId}/media`, `GET …/{mediaId}/download-url`, `GET …/{mediaId}/renditions/{edge}/download-url`, `POST …/media/publication`, `GET /api/v1/public/products/{productId}/gallery` |
| Trả hàng | `POST /api/v1/returns/{id}/evidence` · `/receipt-evidence` · `/inspection-evidence`, `GET …/evidence/{evidenceId}/download-url` |
| Báo hỏng | `POST /api/v1/inventory/damage-reports`, `GET …/{reportId}/photos/{photoId}/download-url`, `POST /api/v1/fulfillment/tasks/{taskId}/pick-lines/{lineId}/damage` · `/damage-after-pick` |
| Hoá đơn NCC | `POST /api/v1/supplier-invoices/{invoiceId}/scan`, `GET …/scan/download-url` |
| Nhãn PDF | `POST /api/v1/inventory/stock-packages/labels`, `POST /api/v1/warehouses/{id}/location-labels` |
