# Kế hoạch: Design Studio 2D/3D — API backend từ A tới Z

- Ngày lập: 2026-11-06. Nhánh: `feature/design-studio-api` (tách từ `feature/s3-file-workflows` @ `f9300266`, đã push).
- Nguồn đã đọc:
  - FE storefront `origin/main` @ `7a3905f`:
    - `docs/superpowers/specs/2026-10-25-design-api-be-proposal.md` (P1–P6, G11)
    - `2026-10-25-design-studio-3d-design.md`
    - `plans/2026-10-26-design-studio-be-test-checklist.md`
    - `src/features/product-design/{api,schemas,capabilities}.ts`
    - `engine/{document,template}/schema.ts`
    - `engine/surfaces/cup.ts`
    - `engine/preflight/rules/area.ts`
    - `engine/geometry/element.ts`
  - Tài liệu nghiệp vụ `KLTN-FA26/docs` `origin/develop` @ `ddd1260` (**bản mới nhất, chốt 2026-11-05**):
    - `ecommerce/12-product-design`
    - `warehouse/01-product-creation` §4.8, §4.10
    - `ecommerce/13-catalog`, `14-cart-and-order`, `16-sales-chat`
    - `open-questions` B5–B40
    - `00-system-overview` §2
  - Repo BE:
    - `docs/business-design/**`
    - module `design`, `product`, `catalog`, `identity`, `customer`, `notification`, `common/storage`
    - migration `design.*`, `product.customization_templates`, `product.print_areas`

---

## 0. Phải chốt trước khi code: làm theo quy trình nào

Hiện có **ba phiên bản nghiệp vụ** cho phần thiết kế, và chúng mâu thuẫn nhau:

| Phiên bản | Quy trình | Ai đang theo |
|---|---|---|
| Tài liệu trong repo BE (`03 §8`, `04c F-DSG`, thời B2C) | Khách vẽ → preflight → khách tự chốt → LOCKED | Không ai (lỗi thời) |
| `docs/12` bản 2026-10-26 | **Nhân viên** thiết kế hộ → **QC** duyệt kỹ thuật → Sale gửi → **khách xác nhận** → snapshot | **Code BE hiện tại**; **FE storefront** dựng theo bộ từ vựng này rồi đề xuất thêm P1–P6 |
| **`docs/12` bản 2026-11-05 (mới nhất, đã chốt)** | **Chỉ khách tự thiết kế** → bấm **Gửi duyệt** → hệ thống **preflight** (Đạt/Không đạt) → `Pending Review` (khoá sửa) → **Quản lý kinh doanh** duyệt kỹ thuật + kiểm duyệt nội dung → **Duyệt** = hệ thống xuất bộ file (PDF/X), chốt snapshot, **sinh SKU riêng của khách** trong cùng một bước / **Trả lại** kèm lý do → `Draft` | Chưa ai |

Ghi chú về bản 2026-11-05:
- "Quản lý kinh doanh" chính là `ECOMMERCE_ADMIN` đổi tên (`00-system-overview` §2).
- Role QC bị bỏ khỏi luồng thiết kế.
- Quyền "Duyệt kỹ thuật thiết kế" mặc định thuộc Quản lý kinh doanh.

**Đề xuất: làm theo bản 2026-11-05**, vì đây là nghiệp vụ đã chốt. Phần API mà FE cần làm đúng theo đề xuất của FE:
- giữ nguyên: P1, P2, P3, P4, P6, G11;
- đổi nghĩa: P5 thành "Gửi duyệt".

FE sẽ phải sửa phần vòng đời (§9). Nếu chọn giữ quy trình cũ (QC + khách xác nhận), các pha 1–3 không đổi; pha 4–7 rút gọn lại, còn đúng đề xuất P5 của FE.

Các quyết định khác. Mình chọn mặc định như sau, bạn có thể đổi:

| # | Quyết định | Mặc định trong kế hoạch | Lý do |
|---|---|---|---|
| D1 | Tên trạng thái | `DRAFT`, `PENDING_REVIEW`, `APPROVED`, `ARCHIVED`. Bỏ `SUBMITTED`, `REJECTED` (`REJECTED` chưa bao giờ được gán) | Một từ cho một khái niệm, khớp tài liệu (CLAUDE.md §3 Naming). Dữ liệu cũ: `SUBMITTED` → `PENDING_REVIEW`, `REJECTED` → `DRAFT` |
| D2 | Ai "xuất bộ file" | Bước 1: FE dựng `PRINT_READY`, `CUSTOMER_PREVIEW`, `TECHNICAL_SPEC` khi khách bấm Gửi duyệt, gắn với **checksum document**. Bước 2: lúc Duyệt, BE chốt các file đó; ghi TrimBox / BleedBox và metadata PDF/X vào `PRINT_READY`; tự sinh `PRODUCTION_INSTRUCTION` từ quy cách thùng (PDFBox + Noto Sans có sẵn) | Bộ dựng (compositor) chỉ có ở trình duyệt. Dựng lại phía server từ document thì phải port cả font, chữ uốn và nét cọ, không đảm bảo giống bản khách thấy |
| D3 | PDF/X-4 thật | Ghi nhận dạng PDF/X-4 (hộp trang, `GTS_PDFXVersion`, XMP, `Trapped`). **Output intent CMYK cần profile ICC của nhà in**: khi có thì nhúng, chưa có thì ghi rõ "chưa đạt chuẩn PDF/X-4" trong đặc tả | B35 vẫn là giả định. Không có veraPDF để kiểm chuẩn |
| D4 | SKU riêng của khách khi Duyệt | Làm ở pha 6: sinh biến thể (variant) gắn khách sở hữu và snapshot, mã `MÃSP-MÃKH-NN`, `Active`, Inventory Item chép quy cách thùng; **catalog ẩn SKU riêng với khách khác** | BR-09: duyệt = file + snapshot + SKU trong một bước |
| D5 | Báo giá / đơn / Đơn sản xuất chuyển sang dùng SKU riêng | **Ngoài nhánh này** (đề xuất phiên riêng) | Đây là đợt đổi phạm vi lớn 04–05/11 ("kho chỉ chứa thành phẩm"), chạm các module order, production, inventory. Trong lúc chờ, các module này vẫn dùng `blank_sku` + snapshot như hiện tại |
| D6 | Preflight phía server | **Chặn cứng** (đúng BR-05, không có mức Warning — B18), kiểm: có phần tử; DPI ảnh (dùng kích thước px do BE đo); ảnh và nét vẽ nằm trong vùng in / lề an toàn / lề tràn (port hình học ly và túi của FE); font được phép; định dạng / dung lượng; quy cách / sản phẩm / khuôn còn dùng được; quy cách đã có quy cách thùng; bộ file khớp document. **Khung bao của chữ không kiểm được ở server** (cần số đo font); phần này để FE preflight lo và Quản lý kinh doanh duyệt bằng mắt | Nói trước giới hạn, không hứa điều không làm được |
| D7 | Ai thấy gì (BR-03, B38) | `Draft`: chỉ chủ sở hữu. `Pending Review`: chủ sở hữu + người giữ quyền duyệt + Sale có thẻ yêu cầu báo giá gắn bản đó (chỉ xem). `Approved`: chủ sở hữu + nhân viên tra theo khách. `Archived`: 404 với khách | Đúng tài liệu |
| D8 | DPI tối thiểu mặc định | 300 (B34); đổi default cột `min_dpi` từ 150 → 300 | Seed đang dùng 300 |

---

## 1. Hiện trạng (tóm tắt)

**Đã có và dùng lại được:**
- **Module `design`:**
  - artifact (upload qua S3, ClamAV, idempotency, link tải 5 phút);
  - snapshot bất biến (trigger) cùng ba mức kiểm checksum: DB → metadata → byte;
  - lịch sử quyết định append-only;
  - fork (`/revisions`).
- **Module `common/storage`:** từ nhánh S3 (`PreparedFiles`, `StagedUploads`, `ImageRenditions`, `EvidenceCopies`, `OrphanedFileSweeper`, Object Lock).
- **Catalog:** đã trả `kind` ở cả hai DTO (G3 xong).
- **Bảng:** `product.customization_templates` và `product.print_areas` đã có (cột, CHECK, trigger "chỉ trên sản phẩm CUSTOMIZABLE").

**Còn thiếu:**
- **Phía template (khuôn in):**
  - Java cho template / vùng in (entity, service, API) và cột `model_params_json` (G11);
  - `product :: api` để tìm sản phẩm theo slug, biến thể theo SKU, template theo code.
- **Phía bản thiết kế:**
  - cột `sku` / `template` / `document` trên `design_draft`; bảng document, bảng asset;
  - `FileCategory` cho ảnh của khách; bộ chuẩn hoá JSON RFC 8785;
  - preflight theo template (đang cố định 150 dpi);
  - lọc theo trạng thái; hàng chờ duyệt; xoá bản nháp; giới hạn 50 bản nháp;
  - yêu cầu báo giá; sinh SKU riêng.
- **Hạ tầng:**
  - event thiết kế: `contracts.DesignConfirmed` có sẵn nhưng không ai phát; `design` chưa được phép phụ thuộc `notification`;
  - giới hạn kích thước body JSON: `IdempotencyFilter` đọc hết body, không có giới hạn.
- **Seed (lệch với FE):**
  - khuôn ly dùng mã vùng `WRAP` (FE chỉ nhận `BODY` / `FRONT` / `BACK` / `LEFT` / `RIGHT`);
  - `shape_json` / `uv_mapping_json` đều NULL;
  - định dạng là `PDF`, `AI`, `PNG` (không phải MIME);
  - không có thông số model;
  - demo/test không có sản phẩm CUSTOMIZABLE nào.

**Bẫy đã biết:**
- `design-designs` chưa khai báo action `CREATE` trong `@PermissionResource`, nên thêm `@RequiresPermission(CREATE)` là ứng dụng không khởi động được.
- Đổi công thức `manifestChecksum` sẽ làm hỏng việc kiểm snapshot cũ (17 snapshot trong seed, cộng `ConfirmedDesigns` trong test).
- Mọi thay đổi `identity.role_permission` phải tăng `app_role.version`.

---

## 2. Hợp đồng API đích

Mọi phản hồi dùng phong bì chung, `non_null`, `version` là khoá lạc quan (sai → 409).

### Phía khách (storefront)

| Endpoint | Ghi chú | So với đề xuất FE |
|---|---|---|
| `GET /catalog/products/{slug}/customization` | Template đang active của các quy cách `Active`, kèm vùng in, ràng buộc file (MIME), `model`. Không cần giá | = P2 |
| `POST /designs/mine` `{productId, sku, templateCode, name}` | Chủ sở hữu và khách lấy từ người gọi; tạo kèm document rỗng | = P1. Không gán reviewer; thêm 409 `DESIGN_DRAFT_LIMIT_REACHED` (50) |
| `GET /designs?status=&page&size` | Bản của chính mình, trừ `ARCHIVED` | = P6 |
| `GET /designs/{id}` | Thêm `productSlug`, `sku`, `templateCode`, `reviewRequestedAt`, `reviewedAt`, `updatedAt`, `previewArtifactId`, `customerSkuCode` | P6 + thêm |
| `GET /designs/{id}/template` | Template của bản thiết kế. Bản chưa duyệt: cấu hình **hiện hành** (B39), kể cả khi template đã tắt. Bản đã duyệt: bản **đóng băng trong snapshot** | Mới (FE đã đề xuất làm phương án) |
| `PUT /designs/{id}/name` `{version, name}` | Đổi tên khi còn `Draft` (tài liệu §4.1) | Mới |
| `GET` / `PUT /designs/{id}/document` | ≤ 1.000.000 byte. Checksum SHA-256 trên JSON chuẩn RFC 8785. Kiểm: template / SKU / mã mặt in; ≤ 200 phần tử mỗi mặt; `assetId` phải là ảnh của chính bản này | = P3 |
| `POST /designs/{id}/assets` (multipart), `POST /assets/uploads` + `POST /assets` (JSON, upload thẳng S3), `GET /assets`, `GET /assets/{assetId}/download-url` | PNG / JPEG / WebP ∩ ràng buộc của template; ≤ `max_file_size_mb`; kích thước px sau khi xoay theo EXIF; ≤ 40 MP; ≤ 100 ảnh mỗi bản thiết kế | = P4 + upload thẳng S3 + giới hạn số ảnh |
| `POST /designs/{id}/artifacts?role=&documentChecksum=` | Như hiện tại, thêm checksum document mà file được dựng từ đó | Thêm một tham số |
| `POST /designs/{id}/technical-review-requests` `{version, quoteRequest?: {cartons, desiredDeliveryDate?}}` | **Gửi duyệt** (kèm yêu cầu báo giá nếu có). Không đạt → 422 `DESIGN_PREFLIGHT_FAILED` kèm danh sách lỗi `{surfaceCode, elementId, rule, message}`, đồng thời ghi `PREFLIGHT_FAILED`. Đạt → `PENDING_REVIEW` | P5 đổi nghĩa |
| `POST /designs/{id}/archive` `{version}` | Xoá bản nháp → `ARCHIVED` | Mới (BR-16) |
| `POST /designs/{id}/revisions` | Tạo bản sửa: chép document **từ snapshot** và ảnh (cùng id), tính vào giới hạn 50 | Như cũ, nay chép thêm document và ảnh |
| `POST /designs/{id}/quote-requests` `{cartons, desiredDeliveryDate?}` | Từ bản `APPROVED` | Mới (§4.8) |
| `GET /designs/{id}/history`, `/snapshot`, `/artifacts…` | Như hiện tại; snapshot thêm `document`, `documentChecksum`, `assets[]`, `customerSkuCode` | Mở rộng |

### Phía nội bộ (backoffice)

| Endpoint | Quyền | Ghi chú |
|---|---|---|
| `GET` / `POST /products/{productId}/variants/{variantId}/customization-templates`, `PUT /{templateId}`, `POST /{templateId}/activation`, `/deactivation` | `product-customization-templates` (Quản lý kinh doanh) | Cấu hình in (01 §4.10): ràng buộc file, `model` (ly / túi), vùng in. **Hình quạt của vùng in thân ly do server tự tính từ thông số ly** (công thức `cupSector` của FE), không nhận từ client. File khuôn 2D tham khảo upload lên S3 (không bắt buộc) |
| `GET /designs/review-queue?page&size` | `design-designs:APPROVE` | Bản `PENDING_REVIEW`, cũ nhất trước |
| `POST /designs/{id}/reviews` `{version, approved, notes}` | `design-designs:APPROVE` | Trả lại (bắt buộc lý do ≤ 2.000) → `DRAFT`. Duyệt → bộ file + snapshot + SKU riêng → `APPROVED` |
| `GET /design-snapshots?customerId&productId&sku` | Như hiện tại | Sale tra bản đã duyệt và SKU riêng theo khách |

### Bỏ đi

Các endpoint sau thuộc luồng nhân viên thiết kế, QC và khách xác nhận, nay không còn trong nghiệp vụ:
- `POST /designs` (nhân viên tạo)
- `PUT /{id}/assignment`
- `/confirmation-requests`
- `/confirmations`
- `/change-requests`
- `/confirmation-withdrawals`
- `/technical-reviews`

Bỏ kèm quyền `design-administration`. QC mất `design-designs:APPROVE`; Quản lý kinh doanh (`ECOMMERCE_ADMIN`) được cấp quyền này. Migration tăng `app_role.version` của các role bị đổi quyền.

**Lịch sử quyết định** chuyển thành enum, khớp §5.3: `CREATED`, `DERIVED_FROM_SNAPSHOT`, `PREFLIGHT_FAILED`, `SUBMITTED_FOR_REVIEW`, `RETURNED`, `APPROVED`, `ARCHIVED`, `QUOTE_REQUESTED`.
- Giữ được các chuỗi cũ để đọc lịch sử cũ.
- Thêm CHECK cho dòng mới.

---

## 3. Các pha thực hiện

Mỗi pha:
- xong thì chạy test của pha, `verify.py`, `ModularityTest`, `ArchitectureTest`;
- commit một lần;
- chuyển pha tiếp.

Migration mới đánh số sau `V20261106000100`.

### Pha 1 — Nền móng dùng chung (`common`)
1. **`CanonicalJson` (RFC 8785):**
   - sắp key theo đơn vị UTF-16;
   - định dạng số theo ECMAScript (Java 21 `Double.toString` đã cho dạng ngắn nhất, rồi đổi sang luật số mũ của ES);
   - escape chuỗi như `JSON.stringify`;
   - từ chối key trùng.
   - Test bằng bộ ví dụ trong RFC 8785 và vector do Node `JSON.stringify` sinh ra.
2. **`ImageDimensions`:** đọc kích thước đã xoay theo EXIF (orientation 5–8 đổi rộng / cao), không giải mã toàn bộ ảnh, từ chối ảnh > 40 MP. Tách từ `ImageRenditions` (hàm `orientation` đang private).
3. **`FileCategory`:**
   - thêm `DESIGN_ASSET` (`design-assets`, PNG / JPEG / WebP, tối đa 50 MB, private, không có bản hiển thị);
   - thêm `PRINT_TEMPLATE` (`print-templates`: PDF / PNG / SVG — SVG chỉ khi đã có bộ lọc an toàn, nếu không thì bỏ SVG).
   - Cập nhật `StoredFileReferencesIntegrationTest`, `UploadInspection`, `PdfContentPolicy`.
4. **Giới hạn body JSON:** document 1.000.000 byte → 413. Chặn ở `IdempotencyFilter` (đọc có giới hạn) và một `RequestBodyLimit` cho `PUT /document`, trước khi Jackson đọc.

### Pha 2 — Cấu hình in (module `product`) + P2
1. **Migration:**
   - `model_params_json JSONB` + CHECK `kind IN ('parametric-cup','parametric-bag')` (`gltf` để sau);
   - template active bắt buộc có model;
   - `min_dpi` default 300;
   - `template_2d_url` → khoá file S3 không bắt buộc (khuôn tham khảo; khuôn thật là hình học tham số);
   - CHECK `allowed_file_formats` là MIME;
   - mã vùng in ∈ {`BODY`, `FRONT`, `BACK`, `LEFT`, `RIGHT`}.
2. **Domain:**
   - `PrintConfig` / `PrintArea` / `CupModel` / `BagModel`;
   - `CupGeometry` (port `cupSector` và `isCylindricalCup`, test bằng số liệu trong đề xuất FE: 230.04 / 327.05 / 0.84894);
   - luật "ly có 1 vùng `BODY` hình quạt; túi có 4 vùng chữ nhật".
3. **API `product :: api`:**
   - `PrintConfigs`: `activeOfProduct(productId)`, `ofVariant(sku, code)`, `byId(templateId)`;
   - `ProductService`: `findBySlug`, `variantOf(sku)` (id, productId, status).
4. **API nội bộ** cho Quản lý kinh doanh (bảng §2): tạo, sửa, bật / tắt; upload khuôn tham khảo lên S3; ghi lịch sử thay đổi (`@Auditable`).
5. **P2** trong `StorefrontCatalogController`: `no-store`, 404 slug lạ / chưa xuất bản, 409 `PRODUCT_NOT_CUSTOMIZABLE`.
6. **Sửa seed** (`gen_seed_test_data.py` + SQL):
   - ly → `BODY` hình quạt tính từ thông số thật;
   - túi → 4 mặt;
   - định dạng MIME;
   - hộp không có model → `is_active = false` (FE v1 chưa hỗ trợ).
   - Thêm fixture CUSTOMIZABLE cho test (`ReferenceRows.customizableProduct`).

### Pha 3 — Bản thiết kế của khách: P1, P3, P4, P6, đổi tên, xoá nháp, tạo bản sửa
1. **Migration:**
   - `design_draft` thêm `variant_id`, `sku`, `template_id`, `template_code`, `review_requested_at`, `reviewed_at`, `archived_at`;
   - đổi trạng thái (D1) + chuyển dữ liệu cũ;
   - bảng `design_document`: `draft_id` PK, `document` TEXT (JSON chuẩn), `checksum` CHAR(64), `updated_at`;
     - CHECK `checksum = encode(sha256(convert_to(document,'UTF8')),'hex')`: luật nêu hai lần (§7);
     - CHECK ≤ 1.000.000 byte;
   - bảng `design_asset`: `(draft_id, id)` PK; append-only bằng trigger; `uk (draft_id, upload_key)`;
   - index cho bộ đếm 50 bản nháp.
2. **Domain `DesignDraft`** (đưa hành vi từ entity về aggregate thật):
   - `startFor`, `rename`, `saveDocument`, `archive`, `deriveFrom`;
   - `requireOwner` thay cho owner / assignee / reviewer.
3. **P1:**
   - khách đang hoạt động (`CUSTOMER_NOT_ACTIVE`), sản phẩm `APPROVED` / `PUBLISHED` và CUSTOMIZABLE, quy cách `Active`, template active trên đúng quy cách;
   - khoá advisory theo khách để đếm 50 cho đúng khi hai tab cùng tạo;
   - ghi `CREATED`.
   - Khai báo `Action.CREATE` trong `@PermissionResource`.
4. **P3:**
   - kiểm nông theo đề xuất, thêm `assetId` thuộc bản này;
   - lưu JSON chuẩn; checksum RFC 8785;
   - mỗi lần lưu tăng version, không ghi lịch sử từng lần lưu.
5. **P4:**
   - multipart và upload thẳng S3, theo mẫu `PreparedFiles` (đọc ngắn → chuẩn bị ngoài transaction → khoá ngắn + `adopt`);
   - idempotency theo người dùng;
   - đo kích thước sau EXIF;
   - chỉ bản `Draft`; không đổi version; `DesignAssetFileReferences` cho sweep.
6. **P6:** thêm field, lọc trạng thái (400 `UNSUPPORTED_PARAMETER`), sắp như cũ.
7. **Truy cập theo D7** trong `access()`; `PermissionMatrixTest` thêm thân request tấn công cho mọi handler mới.
8. **Fork:**
   - chép document của snapshot và các dòng asset (cùng id, cùng khoá S3);
   - từ chối khi quy cách / template không còn dùng được;
   - ghi `DERIVED_FROM_SNAPSHOT`.

### Pha 4 — Gửi duyệt + preflight phía server (P5)
1. **`DesignPreflightCheck`** (thuần, không dùng Spring), các luật theo D6:
   - port `toFlat`, `contains(bleed|trim|safe)`, `inSeam` của ly / túi;
   - khung bao của ảnh và nét vẽ (`strokeReachMm`);
   - test bằng vector lấy từ test của FE.
2. **Gắn artifact với document:**
   - thêm cột `design_artifact.document_checksum`;
   - Gửi duyệt đòi `CUSTOMER_PREVIEW`, `PRINT_READY`, `TECHNICAL_SPEC` hiện hành khớp checksum document hiện hành, sai → 409 `DESIGN_FILES_OUTDATED`;
   - preflight PDF hiện có chạy với `min_dpi` của template.
3. **Luồng:**
   - đọc ngắn;
   - preflight không giữ transaction;
   - không đạt → ghi `PREFLIGHT_FAILED` (transaction riêng, không đổi version) rồi trả 422;
   - đạt → khoá, kiểm lại version → `PENDING_REVIEW` + `reviewRequestedAt` + `SUBMITTED_FOR_REVIEW`.
4. **Event `DesignSubmittedForReview`** (contracts) → `notification`: hộp thư của những người giữ `design-designs:APPROVE`. `design` phát event, không gọi trực tiếp `notification`.
5. **`GET /designs/review-queue`.**

### Pha 5 — Quản lý kinh doanh duyệt / trả lại
1. **Trả lại:** lý do bắt buộc → `DRAFT`, `reviewedAt`, `RETURNED`; event → báo khách.
2. **Duyệt** (BR-09: một bước, hỏng thì ở lại `PENDING_REVIEW`):
   - **ngoài transaction:**
     - kiểm byte các file;
     - đóng dấu PDF/X (D3);
     - sinh `PRODUCTION_INSTRUCTION` từ quy cách thùng;
     - chuẩn bị file bằng `PreparedFiles`.
   - **trong transaction ngắn:**
     - khoá, kiểm lại version;
     - gắn file;
     - snapshot gồm manifest file + document + checksum document + danh sách asset + template đóng băng;
     - Object Lock cho file và ảnh được document dùng;
     - (pha 6) sinh SKU;
     - → `APPROVED`, `APPROVED` (ghi mã snapshot, SKU, checksum);
     - event → báo khách, báo Sale nếu có yêu cầu báo giá.
3. **Checksum snapshot:**
   - chỉ thêm dòng `DESIGN_DOCUMENT:<checksum>` và `DESIGN_ASSET:<id>:<checksum>` khi snapshot có document;
   - snapshot cũ tính đúng như trước nên vẫn kiểm qua;
   - cập nhật `DesignServiceImpl.inspect` (kiểm cả ảnh) và `ConfirmedDesigns`.
4. **Bỏ endpoint và quyền cũ** (§2), sửa test và seed tương ứng.

### Pha 6 — Sinh SKU riêng của khách khi Duyệt (D4)
1. **Migration `product.variants`:**
   - thêm `owner_customer_id`, `design_snapshot_id`, `source_variant_id`;
   - CHECK "có chủ thì có snapshot và quy cách gốc";
   - unique mã;
   - bộ đếm số thứ tự theo (sản phẩm, khách).
2. **`product :: api` `CustomerSkus.issue(...)`** (gọi trong transaction duyệt):
   - mã `MÃSP-MÃKH-NN` ≤ 64 ký tự;
   - `ACTIVE`;
   - Inventory Item chép quy cách thùng và logistics của quy cách gốc (qua `inventory :: api`);
   - từ chối khi sản phẩm / quy cách không còn dùng được → duyệt bị từ chối.
3. **Catalog và mọi danh sách biến thể công khai lọc bỏ SKU riêng** của người khác (BR-02 catalog).
4. **Test:**
   - SKU cũ vẫn `Active` sau khi có bản sửa (bản sửa sinh SKU mới);
   - khách khác không thấy SKU.

### Pha 7 — Yêu cầu báo giá từ bản thiết kế
1. **`design.quote_request`** (bản, số thùng ≥ 1, ngày giao ≥ hôm nay, trạng thái theo bản thiết kế):
   - từ `APPROVED`;
   - hoặc đi kèm Gửi duyệt;
   - ghi `QUOTE_REQUESTED`.
2. **Event `DesignQuoteRequested`** → `chat`: mở / gắn hội thoại với Sale phụ trách, kèm **thẻ yêu cầu báo giá**:
   - thẻ đi theo bản thiết kế khi bị trả lại;
   - thẻ đóng khi bản bị xoá (B40).
   - Nút "Lập báo giá" chỉ bật khi bản `APPROVED`. Điền sẵn SKU riêng vào báo giá thuộc D5, ngoài nhánh này.
3. **Sale được xem bản `Pending Review`** có thẻ của mình (D7).

### Pha 8 — Kiểm thử từ A tới Z và tài liệu
1. Chạy toàn bộ `mvn test`, `verify.py`, `ModularityTest`, `ArchitectureTest`.
2. **Chạy thật** với Postgres + MinIO trên docker, theo kịch bản API:
   1. tạo template;
   2. P2;
   3. P1;
   4. lưu document;
   5. upload ảnh (cả upload thẳng S3);
   6. dựng file;
   7. Gửi duyệt (một lần không đạt, một lần đạt);
   8. Trả lại;
   9. Gửi lại;
   10. Duyệt → snapshot + SKU;
   11. tạo bản sửa;
   12. yêu cầu báo giá;
   13. kiểm checksum khi Đơn sản xuất tải file.
3. **Kiểm cùng FE storefront** (`USE_MOCK=false`) ở trình duyệt: P1–P4 và P6 (phần không đổi hợp đồng).
4. **CORS** cho link presigned `GET` / `HEAD` của ảnh và artifact từ origin storefront (MinIO + script bucket AWS).
5. **`docs/design-studio-fe-contract.md`** (tiếng Anh, commit trong repo): hợp đồng chính thức và danh sách việc FE phải sửa (§9).

---

## 4. Rủi ro và giới hạn biết trước

- **Khung bao của chữ** không kiểm được ở server (D6). Đề xuất về sau: FE gửi kèm số đo của từng phần tử chữ và BE kiểm lại bằng font đã đăng ký (cần đóng gói đúng bộ font FE dùng).
- **PDF/X-4 đạt chuẩn thật** cần profile ICC của nhà in và một công cụ kiểm (veraPDF / Acrobat). Chưa có thì chỉ ghi nhận dạng (D3).
- **Sinh SKU riêng sẽ không đồng bộ phần còn lại của hệ thống:** báo giá / đơn / Đơn sản xuất vẫn dùng `blank_sku` cho tới khi làm D5. SKU riêng sinh ra lúc đó chỉ để tra cứu và hiện cho khách.
- **Thiết kế cũ do nhân viên tạo** (không có SKU / template / document) chỉ xem được, không gửi duyệt được: preflight báo "chưa có bố cục".
- **Module chủ sở hữu:** template thuộc `product` (theo kế hoạch migration, Phương phụ trách), chat thuộc `chat`. Đổi ở đó nên báo người phụ trách.
- **Hợp đồng đổi so với FE hiện tại** (P5, trạng thái, bỏ xác nhận của khách): FE phải sửa trước khi bật `PROPOSED_ENDPOINTS_LIVE`.

## 5. Việc FE phải sửa nếu theo bản 2026-11-05 (đưa vào hợp đồng)

- **`designStatusSchema`:** `DRAFT | PENDING_REVIEW | APPROVED | ARCHIVED`. Vòng đời FE:
  - bỏ `reviewPassed`, `awaitingConfirmation` và nút "Chốt thiết kế" (submit + confirm);
  - thêm "Chờ duyệt" và "Bị trả lại (lý do)".
- **"Gửi duyệt":**
  - upload ba file kèm `documentChecksum`, rồi gọi P5;
  - xử lý 422 `DESIGN_PREFLIGHT_FAILED`: hiện lỗi theo phần tử, bấm vào lỗi thì chọn đúng phần tử.
- **Preflight FE:** `CROSSES_SAFE_AREA` thành **Fail**; bỏ mức Warning DPI (B18 chỉ Đạt / Không đạt); min DPI mặc định 300.
- **Thêm:** xoá bản nháp; đổi tên; hộp "Yêu cầu báo giá" (số thùng, ngày giao) và "Gửi duyệt và yêu cầu báo giá"; hiện mã SKU riêng ở bản đã duyệt.
- **Bỏ:** `reviewerUserId` / QC khỏi giao diện; người duyệt là Quản lý kinh doanh.
- **Mở bản đã duyệt:** dùng `GET /designs/{id}/template`.
- **Màn backoffice cần mới:**
  - cấu hình in theo quy cách;
  - hàng chờ duyệt thiết kế (xem document 2D / 3D, ảnh gốc, kết quả preflight, Duyệt / Trả lại).

## 6. Trạng thái

| Pha | Trạng thái |
|---|---|
| 0 — Chốt quyết định | ⏳ Chờ bạn chọn quy trình |
| 1–8 | Chưa bắt đầu |
