# SCRUM-43 — Báo cáo API File Storage & CDN Integration

## 1. Quyết định nghiệp vụ đã áp dụng

- Ảnh sản phẩm đã publish là nội dung công khai. Backend trả URL CloudFront HTTPS; khách không cần
  đăng nhập để đọc gallery công khai.
- S3 bucket luôn private. CloudFront Origin Access Control chỉ được `GetObject` dưới prefix
  `product-images/`; `design-renders/` không thể đi qua CDN công khai.
- Product gallery có ảnh cover, thứ tự, caption, bản đang chỉnh và bản đã duyệt. Gallery variant/SKU
  là override tùy chọn; variant không có override sẽ kế thừa gallery chung của product.
- URL ảnh CDN dùng key UUID bất biến và cache một năm. Đổi gallery không ghi đè file cũ nên không
  cần purge cache ảnh; JSON gallery công khai chỉ cache năm phút.
- Design draft có thể chứa bundle gồm `CUSTOMER_PREVIEW`, `TECHNICAL_SPEC`, `PRINT_READY` và
  `PRODUCTION_INSTRUCTION`. Mỗi role có tối đa một file hiện hành; upload file mới tạo revision mới.
- Chỉ customer owner xác nhận. Nhân viên được phân công có thể sửa; QC reviewer độc lập kiểm tra kỹ
  thuật; không ai được sửa snapshot đã xác nhận. Muốn thay đổi phải fork thành draft mới và xác nhận lại.
- Order lưu `snapshotId` và SHA-256 của toàn bundle. Nhân viên kho chỉ lấy được artifact của đúng
  order line trong task được giao. Không có API đọc storage key.
- Khi đóng gói, server tải lại toàn bundle từ S3, quét ClamAV, tính lại SHA-256 và ghi evidence.
  Mismatch chuyển package và order sang `ON_HOLD`; không có quyền bypass. Chỉ QC hoặc warehouse
  manager được reverify, và chỉ kết quả match mới đóng hold.
- Order đã gắn snapshot không tự đổi sang revision mới. Nếu khách muốn sửa bản đã xác nhận thì tạo
  revision mới; việc đổi giá/đổi order là quy trình amendment riêng, không được âm thầm relink.

Tất cả response dùng envelope `ApiResponse`: thành công có `success`, `data`, `timestamp`; thất bại có
`errorCode`, `message`, `correlationId` và có thể có `fieldErrors`.

## 2. Nhóm Product image và CDN (SCRUM-53)

### Asset và rendition nội bộ

| API | Ai được thao tác | Chức năng | Dữ liệu chính trả về |
| --- | --- | --- | --- |
| `POST /api/v1/products/{productId}/images` | Có `product-products:UPDATE` | Upload JPEG/PNG/WebP tối đa 10 MiB, scan virus, chuẩn hóa EXIF, tạo rendition 256/768/1600; hỗ trợ `Idempotency-Key` | `imageId`, metadata file, checksum gián tiếp qua quản lý nội bộ, danh sách kích thước rendition |
| `GET /api/v1/products/{productId}/images` | `product-products:READ` | Liệt kê asset đã upload, chưa quyết định thứ tự hiển thị | `imageId`, filename, MIME, size, storedAt, renditions |
| `GET /api/v1/products/{productId}/images/{imageId}/download-url` | `product-products:READ` | URL ký ngắn hạn của original để admin kiểm tra | `url`, `expiresAt`; response `no-store` |
| `GET /api/v1/products/{productId}/images/{imageId}/renditions/{edge}/download-url` | `product-products:READ` | URL ký ngắn hạn của rendition cụ thể | `url`, `expiresAt`; response `no-store` |

### Gallery chung và gallery theo variant

| API | Ai được thao tác | Chức năng | Dữ liệu chính trả về |
| --- | --- | --- | --- |
| `GET /api/v1/products/{productId}/gallery` | `product-products:READ` | Xem working/published gallery | `revision`, `working`, `published`, editor, approver |
| `PUT /api/v1/products/{productId}/gallery` | `product-products:UPDATE` | Thêm/bỏ/sắp xếp tối đa 20 ảnh; phần tử đầu là cover | Gallery và revision mới |
| `POST /api/v1/products/{productId}/gallery/approval` | `product-products:APPROVE`, khác editor | Duyệt đúng revision hiện tại | Published gallery mới |
| `GET /api/v1/products/{productId}/variants/{variantId}/gallery` | `product-products:READ` | Xem override của variant | Working/published override |
| `PUT /api/v1/products/{productId}/variants/{variantId}/gallery` | `product-products:UPDATE` | Chỉnh override bằng asset thuộc đúng product | Gallery variant và revision mới |
| `POST /api/v1/products/{productId}/variants/{variantId}/gallery/approval` | `product-products:APPROVE`, khác editor | Duyệt override | Published override |

### Publish và storefront công khai

| API | Ai được thao tác | Chức năng | Dữ liệu chính trả về |
| --- | --- | --- | --- |
| `POST /api/v1/products/{productId}/publication` | `product-products:APPROVE` | Chuyển `APPROVED -> PUBLISHED`; bắt buộc gallery chung đã duyệt và không rỗng | Success envelope |
| `POST /api/v1/products/{productId}/unpublication` | `product-products:APPROVE` | Gỡ khỏi storefront, `PUBLISHED -> APPROVED` | Success envelope |
| `GET /api/v1/public/products/{productId}/gallery?sku=SKU` | Công khai, không cần token | Trả gallery đã duyệt của product PUBLISHED; nếu SKU có override thì dùng override, nếu không thì kế thừa | `productId`, `variantId`, images/caption và các CloudFront URL theo edge |

Ghi chú: điều kiện selling price thuộc catalog/pricing story. SCRUM-53 thực thi đầy đủ media gate;
không tạo giá giả trong product module để lách ranh giới module.

## 3. Nhóm Design artifact (SCRUM-54)

### Draft, phân công và artifact

| API | Ai được thao tác | Chức năng | Dữ liệu chính trả về |
| --- | --- | --- | --- |
| `POST /api/v1/designs` | E-commerce admin có `design-administration:CREATE` | Tạo draft trợ giúp, gắn customer owner, designer và reviewer độc lập | Draft, assignees, status, version |
| `PUT /api/v1/designs/{id}/assignment` | E-commerce admin có `design-administration:UPDATE` | Đổi designer/reviewer; làm mất hiệu lực review cũ | Draft và version mới |
| `POST /api/v1/designs/{id}/artifacts?role=...` | Owner hoặc assigned designer, `design-designs:UPDATE`, draft còn editable | Upload/replace một role, scan, server tính SHA-256; hỗ trợ `Idempotency-Key` | `artifactId`, role, filename, MIME, size, storedAt, checksum |
| `GET /api/v1/designs/{id}/artifacts` | Owner/designer/reviewer có `design-designs:READ` | Liệt kê revision metadata | Danh sách artifact, không có storage key |
| `GET /api/v1/designs/{id}/artifacts/{artifactId}/download-url` | Cùng data scope ở trên | URL ký S3 ngắn hạn | `url`, `expiresAt`; `no-store` |
| `PUT /api/v1/designs/{id}/specification` | Owner/assigned designer, `design-designs:UPDATE` | Sửa spec theo optimistic version; vô hiệu review cũ | Draft/version mới |

### Review, customer confirmation và immutable snapshot

| API | Ai được thao tác | Chức năng | Dữ liệu chính trả về |
| --- | --- | --- | --- |
| `POST /api/v1/designs/{id}/technical-reviews` | QC có `design-designs:APPROVE` và là reviewer được gán; không được là owner/designer/last editor | Ghi PASS/FAIL và notes cho đúng preview/version | Draft, review evidence, version |
| `POST /api/v1/designs/{id}/confirmation-requests` | Owner/assigned designer | Gửi bản đã review cho customer | Draft status `SUBMITTED` |
| `POST /api/v1/designs/{id}/change-requests` | Customer owner | Từ chối/yêu cầu sửa, quay lại draft và vô hiệu review | Draft/version mới và history |
| `POST /api/v1/designs/{id}/confirmation-withdrawals` | Owner/assigned designer | Thu hồi yêu cầu xác nhận trước khi customer xác nhận | Draft/version mới |
| `POST /api/v1/designs/{id}/confirmations` | Chỉ customer owner | Re-read + scan toàn bundle, tạo manifest và aggregate SHA-256, đóng snapshot bất biến | Snapshot ID, checksum bundle, spec, confirmer, time và metadata từng artifact |
| `GET /api/v1/designs/{id}/snapshot` | Owner/designer/reviewer | Xem bằng chứng đã xác nhận | Snapshot + manifest metadata; không có key/URL công khai |
| `GET /api/v1/designs/{id}/history` | Owner/designer/reviewer | Xem lịch sử decision | Actor, artifact, decision, notes, version, time |
| `POST /api/v1/designs/{id}/revisions` | Owner/assigned designer | Fork snapshot đã xác nhận thành draft mới | Draft mới có `parentSnapshotId`; snapshot cũ giữ nguyên |

`GET /api/v1/designs` và `GET /api/v1/designs/{id}` chỉ trả row mà caller là owner, assigned
designer hoặc reviewer. Có quyền RBAC rộng không đồng nghĩa đọc được thiết kế của mọi customer.

## 4. Nhóm fulfillment, quyền file theo order và checksum gate

| API | Ai được thao tác | Chức năng | Dữ liệu chính trả về |
| --- | --- | --- | --- |
| `POST /api/v1/fulfillment/tasks` | Order coordinator hoặc warehouse manager | Nhận order `PAID`, chuyển `IN_FULFILMENT`, tạo pick/pack và gán nhân viên | Task ID, order ID, assignee, pick/pack status, timestamps, evidence |
| `GET /api/v1/fulfillment/tasks` | Warehouse staff/manager, QC, coordinator | Staff thấy task của mình; QC chỉ thấy hold; manager/coordinator thấy tối đa 100 task mới nhất | Danh sách task-scoped view |
| `PUT /api/v1/fulfillment/tasks/{taskId}/assignment` | Coordinator hoặc warehouse manager | Phân công lại khi pick còn `PENDING` | Task cập nhật |
| `GET /api/v1/fulfillment/tasks/{taskId}` | Assignee; hoặc manager/QC/coordinator | Xem task; user ngoài scope nhận 404 | Task và integrity evidence |
| `POST /api/v1/fulfillment/tasks/{taskId}/picking-start` | Warehouse staff/manager và phải là assignee | `PENDING -> PICKING` | Task/timestamps mới |
| `POST /api/v1/fulfillment/tasks/{taskId}/picking-completion` | Warehouse staff/manager và phải là assignee | `PICKING -> PICKED` | Task/timestamps mới |
| `GET /api/v1/fulfillment/tasks/{taskId}/lines/{lineId}/design-artifacts/{role}/download-url` | Assignee, QC hoặc manager; task và line phải khớp | Verify checksum trước khi cấp URL ký private | Metadata artifact, checksum, URL, expiresAt; không có key |
| `POST /api/v1/fulfillment/tasks/{taskId}/packing-completion` | Assignee là warehouse staff/manager | Verify mọi snapshot line và ghi evidence; match thì `PACKED`, mismatch thì `ON_HOLD` | Task với pack status và evidence MATCH/MISMATCH |
| `POST /api/v1/fulfillment/tasks/{taskId}/design-integrity-reverification` | QC hoặc warehouse manager | Reverify package đang hold; không có bypass; note resolution bắt buộc | Vẫn `ON_HOLD` nếu còn sai, hoặc `PACKED` và order trở lại `IN_FULFILMENT` |

## 5. Error contract quan trọng

| Case | HTTP / code |
| --- | --- |
| Multipart/category/pixel vượt giới hạn | `413 PAYLOAD_TOO_LARGE` |
| MIME hoặc magic bytes không hỗ trợ | `415 UNSUPPORTED_MEDIA_TYPE` |
| File hỏng/nhiễm virus/input sai | `400 VALIDATION_FAILED` |
| Sai permission | `403 FORBIDDEN`; sai data scope nhạy cảm thường trả `404 NOT_FOUND` để không lộ tồn tại |
| State/version/checksum không hợp lệ | `409 CONFLICT` hoặc `409 OPTIMISTIC_LOCK` |
| Idempotency key tái sử dụng cho file khác | `422 IDEMPOTENCY_KEY_REUSED` |
| Decoder/scanner đang quá tải | `429 RATE_LIMITED` |
| S3 hoặc ClamAV không khả dụng | `503 STORAGE_ERROR`; controller không đổi thành generic 500 |

## 6. AWS deployment và UAT trước khi đưa production

Hạ tầng nằm tại `infra/aws/media-cloudfront.yaml`. Template tạo private versioned S3 bucket,
CloudFront OAC, path guard, bucket policy chỉ đọc `product-images/*`, cache/CORS policy và IAM managed
policy cho backend. Custom domain như `media.tenmien.com` là tùy chọn; có thể dùng trực tiếp domain
`*.cloudfront.net` mà không đổi DNS.

Các bước triển khai bắt buộc vì repository không thể tự biết AWS account/domain của môi trường:

1. Deploy CloudFormation, chọn bucket name duy nhất toàn cầu. Nếu dùng custom domain, certificate ACM
   phải ở `us-east-1`; sau đó trỏ DNS alias vào distribution.
2. Attach output `ApplicationMediaPolicyArn` vào IAM role chạy backend; production không dùng access
   key dài hạn trong `.env`.
3. Đặt `S3_BUCKET`, `AWS_REGION`, `S3_ENDPOINT=` rỗng, `S3_PATH_STYLE=false` và
   `PRODUCT_MEDIA_CDN_BASE_URL` bằng output `ProductMediaBaseUrl`.
4. Chạy Flyway migration và chạy ClamAV có health check; upload fail-closed nếu scanner không trả
   kết quả rõ ràng.
5. UAT bằng user thật cho bốn vai trò: admin/approver product, customer + assigned designer + QC,
   coordinator/warehouse assignee và QC/warehouse manager xử lý hold.
6. Kiểm tra: S3 URL trực tiếp bị từ chối; CloudFront đọc được product image; CloudFront trả 404 cho
   `design-renders/*`; user kho ngoài task nhận 404; checksum mismatch tạo evidence + hold; sửa file
   đúng và reverify mới đóng hold.

Sau deployment, theo dõi `ObjectStorageHealthIndicator`, tỷ lệ `STORAGE_ERROR`, ClamAV health,
CloudFront 4xx/5xx và S3 access logs/CloudTrail theo chính sách vận hành của môi trường.
