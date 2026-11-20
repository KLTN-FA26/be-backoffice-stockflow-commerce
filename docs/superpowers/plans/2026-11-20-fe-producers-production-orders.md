# Brief FE: Danh mục đơn vị sản xuất + Đơn sản xuất (SCRUM-756)

Giao cho session làm việc ở repo `fe-backoffice-stockflow-commerce`. Backend đã xong; hợp đồng API bên dưới chép từ
BE `develop` cộng PR [be#383](https://github.com/KLTN-FA26/be-backoffice-stockflow-commerce/pull/383) (bỏ giá khỏi đơn
sản xuất), và nhánh BE `feature/coordinator-owns-production` (2026-11-20: **Điều phối sản xuất gửi Đơn sản xuất,
không còn bước duyệt**; Điều phối sản xuất duyệt bản thiết kế của khách). Nghiệp vụ: kltn-docs
`docs/ecommerce/17-order-management/README.md` §4.3, §5.3, BR-05/06/22–26 (BR-23 viết lại 2026-11-20) và
`docs/ecommerce/12-product-design/README.md` §4 bước 3.

> **Đã đổi so với bản brief trước (2026-11-20):** bỏ `/submission`, `/approval`, `/return` và quyền
> `production-orders:APPROVE`; thêm `POST /{id}/sending` (Điều phối sản xuất), `sentBy`, `acceptanceNote` (ghi chú
> khi ghi nhận nhận đơn), quyền `production-order-sends:READ` (Sales Manager được báo khi một Đơn sản xuất được gửi);
> hàng đợi duyệt bản thiết kế chuyển sang Điều phối sản xuất. Sales Manager chỉ **xem** Đơn sản xuất.

**Ngoài phạm vi** (task khác): vòng mẫu in (SCRUM-761), lịch sản xuất đầy đủ (SCRUM-767), cờ trễ (SCRUM-835),
màn khách duyệt mẫu trên storefront (SCRUM-764).

---

## 0. Trước khi code

1. Đọc `CLAUDE.md` / `AGENTS.md` của repo FE. Làm trên **nhánh mới từ `origin/develop`**: nhánh hiện tại
   `refactor/remove-retired-pages` có 2 file chưa commit của chủ repo, không đụng vào.
2. Khuôn mẫu feature: `src/features/warehouse-capacity/` (`api.ts`, `schemas.ts`, `queries.ts`, `mutations.ts`,
   `lifecycle.ts`, `selectors.ts`, `errors.ts`, `components/`, `__tests__/`) và trang `src/app/admin/warehouse-capacity/`.
   Route, quyền, nhãn: `src/constants/routes.ts`, `permissions.ts`, `labels.ts`. Mock: `src/lib/api/mock-routes-*.ts`.
3. **Gỡ code cũ, không viết chồng.** `src/features/order/production-schemas.ts` là hợp đồng của module sản xuất nội
   bộ đã bị xoá khỏi BE (`/production/orders`, "LSX", `plannedQuantity`, `mode`, `parentId`, loại `SAMPLE`). Nó được
   import ở `features/order/{index,types,schemas,action-api,queries}.ts` và `contract-schemas.test.ts`. Thay bằng hợp
   đồng mới dưới đây (trang đơn bán dùng `GET /production-orders?salesOrderId=…`). `PRODUCTION_ORDER_PERMISSIONS`
   trong `constants/permissions.ts` cũng ghi theo SCRUM-405..415 cũ: sửa lại theo §1.

## 1. Quyền và vai trò (BE `PermissionMatrixTest`)

| Quyền | Vai trò |
|---|---|
| `sales-producers:READ` | Điều phối SX, Sales Manager, Sale, Quản lý kho, Admin |
| `sales-producers:CREATE`, `:UPDATE` | Điều phối SX, Admin |
| `production-orders:READ` | Điều phối SX, Sales Manager, Sale, Quản lý kho (chỉ kho của mình — BE tự lọc), Admin |
| `production-orders:CREATE`, `:UPDATE` (lập, sửa, **gửi**, ghi nhận, huỷ, đóng) | Điều phối SX, Admin |
| `production-order-sends:READ` | Sales Manager, Admin |
| `production-schedule:READ` | Điều phối SX, Sales Manager, Sale, Quản lý kho, Admin |
| `design-designs:APPROVE`, `:READ`, `:VIEW_PAGE` (hàng đợi duyệt bản thiết kế, vòng duyệt) | Điều phối SX, Admin |

- **`production-orders:APPROVE` không còn** (BE đã xoá quyền): bỏ khỏi `PRODUCTION_ORDER_PERMISSIONS` và mọi chỗ
  kiểm quyền; không có nút Duyệt / Trả lại.
- **`production-order-sends:READ`** chỉ là **người nhận thông báo**: không guard endpoint nào, không có `VIEW_PAGE`,
  không có màn hình. FE chỉ cần thêm nó vào danh sách quyền (để `/identity/me/permissions` và màn phân quyền parse được)
  — action là `READ`, không có action mới. Thông báo vào inbox như các thông báo khác: `category = "PRODUCTION_ORDER"`,
  `sourceType = "PRODUCTION_ORDER"`, `severity = INFO`, link `/production/orders/{id}` (FE map sang route chi tiết
  Đơn sản xuất dưới `/admin/...`).
- **Duyệt bản thiết kế của khách** (hàng đợi duyệt, vòng duyệt dự án — kltn-docs 12 §4 bước 3) là việc của **Điều phối
  SX**; Sales Manager không còn `design-designs:APPROVE` và **không còn đọc bản thiết kế đang chờ duyệt** (chỉ đọc qua
  yêu cầu báo giá đang mở). Menu / nút duyệt thiết kế theo quyền, không theo tên role: kiểm lại test theo vai trò.

Mỗi resource còn có `:VIEW_PAGE` để hiện menu/trang. Route BE khai báo cho menu: `/production/producers`,
`/production/orders`, `/production/schedule` (theo quy ước FE thì đặt dưới `/admin/...`).

Mọi endpoint dưới `/api/v1`, bọc `ApiResponse<…>` như các feature khác; danh sách là `PageResponse`
(`items, page, size, totalElements, totalPages, hasNext, hasPrevious`). Jackson `non_null`: field null **bị bỏ khỏi
JSON**, nên field tuỳ chọn trong zod là `nullish`. Ngày `LocalDate` là `"yyyy-MM-dd"`, thời điểm `Instant` là ISO.

---

## 2. Màn A — Danh mục đơn vị sản xuất

**API**

| | Endpoint | Ghi chú |
|---|---|---|
| Danh sách | `GET /producers?q=&status=ACTIVE\|INACTIVE&page=&size=&sort=` | `q` tìm theo mã hoặc tên; sắp theo tên |
| Chi tiết | `GET /producers/{id}` | |
| Tạo | `POST /producers` → 201 | 409 `PRODUCER_CODE_TAKEN` |
| Sửa / ngừng dùng | `PUT /producers/{id}` | gửi kèm `version`; mã **không đổi được** |

```ts
// Producer (response)
{ id, code, name, contactName?, contactPhone?, email?, channel: "EMAIL" | "PDF",
  standardLeadDays: number, status: "ACTIVE" | "INACTIVE", note?, version: number }

// ProducerRequest (POST và PUT)
{ code,            // chỉ POST; 2–32 ký tự chữ/số/'-'/'_', bắt đầu bằng chữ hoặc số; BE tự viết hoa
  name,            // bắt buộc, ≤ 200
  contactName?, contactPhone?,   // ≤ 200, ≤ 32
  email?,          // ≤ 254; BẮT BUỘC khi channel = EMAIL
  channel,         // bắt buộc: EMAIL (hệ thống gửi mail) | PDF (điều phối tự giao phiếu)
  standardLeadDays?, // 1–365, bỏ trống = 14 ("thời gian sản xuất chuẩn", dùng cho cảnh báo PO_NEEDED)
  note?,           // ≤ 500
  status?,         // chỉ PUT: ACTIVE | INACTIVE
  version? }       // chỉ PUT: version đã đọc
```

**Màn hình**
- Bảng: mã, tên, kênh, email/người liên hệ, thời gian SX chuẩn (ngày), trạng thái. Lọc trạng thái, ô tìm kiếm.
- Form tạo/sửa (dialog hoặc trang): validate như trên phía client; email bắt buộc khi chọn kênh EMAIL; ô mã khoá khi sửa.
- Ngừng dùng = sửa `status` sang `INACTIVE` (không có xoá). Đơn vị `INACTIVE` không nhận đơn sản xuất mới
  (BE trả 409 `PRODUCER_INACTIVE`), nên các ô chọn đơn vị sản xuất ở màn B chỉ liệt kê `ACTIVE`.
- Nút tạo/sửa chỉ hiện khi có `sales-producers:CREATE` / `:UPDATE`.

---

## 3. Màn B — Đơn sản xuất

### 3.1 API

| Thao tác | Endpoint | Body | Quyền |
|---|---|---|---|
| Danh sách | `GET /production-orders?warehouseId=&status=&producerId=&salesOrderId=&page=&size=&sort=` | | READ |
| Chi tiết (có `history`) | `GET /production-orders/{id}` | | READ |
| **Lập** | `POST /production-orders` → 201 | `{ salesOrderId, deliveryBatchSeq, producerId }` | CREATE |
| Sửa nháp | `PUT /production-orders/{id}` | `EditRequest` | UPDATE |
| **Gửi cho đơn vị SX** | `POST /{id}/sending` | `{ version }` | UPDATE |
| Ghi nhận đơn vị SX nhận đơn | `POST /{id}/acceptance` | `{ version, expectedCompletionDate, note? }` | UPDATE |
| Đổi ngày hoàn thành dự kiến | `PUT /{id}/expected-completion-date` | `{ version, expectedCompletionDate }` | UPDATE |
| Bắt đầu sản xuất (không cần duyệt mẫu) | `POST /{id}/production-start` | `{ version, expectedCompletionDate? }` | UPDATE |
| Huỷ | `POST /{id}/cancellation` | `{ version, reason }` | UPDATE |
| Đóng | `POST /{id}/closing` | `{ version, reason }` | UPDATE |
| Link tải phiếu PDF đã gửi | `GET /{id}/slip` → `DownloadLink` | | READ |
| Kênh gửi + lịch sử gửi | `GET /{id}/dispatch?page=&size=` | | READ |
| Gửi lại mail | `POST /{id}/dispatch/resend` | | UPDATE |

`sort` nhận `createdAt`, `needByDate`, `code`, `status` (mặc định `createdAt,desc`). `reason` bắt buộc, ≤ 500.
Ngày dự kiến không được ở quá khứ (BE trả `VALIDATION_FAILED`).

- `/submission`, `/approval`, `/return` **đã bị xoá** (404). Không có bước duyệt: Điều phối SX gửi thẳng
  `DRAFT → SENT`, **người lập tự gửi được**. Khi gửi BE vẫn làm đủ: kiểm lại file in (409 `PRINT_FILE_MISMATCH`, không
  lưu gì), kiểm đơn vị SX còn `ACTIVE` (409 `PRODUCER_INACTIVE`), xuất phiếu PDF, cảnh báo sức chứa, gửi cho đơn vị SX,
  báo Quản lý kho của kho nhận và Sales Manager, đơn bán sang `AWAITING_STOCK`. Nút "Gửi" nên có hộp xác nhận (gửi
  rồi không rút lại được; đơn vị SX rút lui thì huỷ kèm lý do và lập Đơn sản xuất mới).
- `acceptance.note`: tuỳ chọn, ≤ 500 ký tự, BE tự `trim`, để trống = không có (vd "anh Hùng xác nhận qua Zalo").
  Ghi một lần, không sửa được sau đó.

```ts
// ProductionOrder (response; list không có history)
{ id, code /* PRD-yyyyMMdd-NNNN */, salesOrderId, deliveryBatchId, customerId, producerId, receivingWarehouseId,
  needByDate, status, sampleApprovalRequired, sampleRequiredByDefault, sampleWaiverReason?, note?,
  drawnUpBy, sentAt?, sentBy?, productionStartedAt?, expectedCompletionDate?, acceptanceNote?,
  cancelledAt?, cancellationReason?, closedAt?, closingReason?,
  submittedAt?, approvedBy?, approvedAt?, returnedReason?,   // LỊCH SỬ: chỉ có ở đơn trước 2026-11-20, đơn mới bỏ khỏi JSON
  capacityComputed: boolean,
  capacity?: { board: WarehouseCapacityBoard, neededM3?, exceedsProjected, exceedsIfAllApproved, shortfallM3? },
  lines: [{ id, orderLineId, sku, cartons, unitsPerCarton, totalUnits, designSnapshotId, printFileChecksum,
            packingRequirements?, receivedCartons }],
  history?: [{ from?, to, reason?, by?, at }],
  version }
// KHÔNG còn unitPrice / currency (be#383).
// sentBy: người gửi (đơn gửi trước 2026-11-20: người đã duyệt). acceptanceNote: ghi chú lúc ghi nhận nhận đơn.
// capacity.exceedsIfAllApproved giữ tên API cũ, nghĩa nay là "vượt nếu mọi Đơn sản xuất chưa gửi về kho này đều được gửi".

// EditRequest (chỉ khi DRAFT)
{ version, producerId, needByDate, note?, sampleApprovalRequired: boolean, sampleWaiverReason?,
  lines?: [{ orderLineId, cartons /* ≥ 1 */, packingRequirements? }] }   // chỉ dòng muốn đổi
```

`WarehouseCapacityBoard` đã có schema trong `features/warehouse-capacity` — dùng lại, đừng định nghĩa lần hai.

**Response chỉ có id**, không có tên: đơn vị SX (`producerId`), khách (`customerId`), đơn bán (`salesOrderId`),
kho nhận (`receivingWarehouseId`), người lập/gửi. Hiển thị tên bằng cách tra danh sách đơn vị SX và kho (ít, cache
được), còn số đơn bán/khách lấy từ endpoint đơn bán sẵn có hoặc từ lịch sản xuất (§3.3). Nếu thấy phải gọi N lần cho
một trang danh sách thì **ghi lại để BE thêm field**, đừng gọi N+1.

### 3.2 Trạng thái và nút theo trạng thái (kltn-docs 17 §5.3)

| Trạng thái (nhãn) | Nút hiện | Ai |
|---|---|---|
| `DRAFT` (Nháp) | Sửa, **Gửi**, Huỷ | UPDATE (người lập gửi được) |
| `PENDING_APPROVAL` (Chờ duyệt — lịch sử) | không có | BE không còn đơn nào ở trạng thái này; chỉ cần nhãn cho `history` cũ |
| `SENT` (Đã gửi) | Ghi nhận nhận đơn (nhập ngày dự kiến, ghi chú tuỳ chọn), Huỷ | UPDATE |
| `ACCEPTED` (Đơn vị SX đã nhận) | Bắt đầu sản xuất — **chỉ khi `sampleApprovalRequired = false`** | UPDATE |
| | Đổi ngày dự kiến, Huỷ | UPDATE |
| `SAMPLE_PENDING` (Đang duyệt mẫu) | Đổi ngày dự kiến, Huỷ (vòng mẫu là SCRUM-761) | UPDATE |
| `IN_PRODUCTION` (Đang sản xuất) | Đổi ngày dự kiến, Đóng | UPDATE |
| `PARTIALLY_RECEIVED` (Nhận một phần) | Đổi ngày dự kiến, Đóng | UPDATE |
| `RECEIVED`, `CANCELLED`, `CLOSED` | không có | |

"Gửi lại mail" (`/dispatch/resend`) hiện từ `SENT` tới `PARTIALLY_RECEIVED` khi kênh là `EMAIL`; BE vẫn có thể từ
chối (409 `PRODUCTION_ORDER_RESEND_NOT_ALLOWED`) khi mail trước còn đang gửi.

`IN_PRODUCTION` cũng có thể do hệ thống chuyển (khách duyệt mẫu, hoặc hàng về khi không cần duyệt mẫu);
`PARTIALLY_RECEIVED`/`RECEIVED` chỉ do nhập kho. Đặt quy tắc này trong một `lifecycle.ts` thuần, có unit test, như
`warehouse-capacity`.

### 3.3 Lập đơn — không có form "tạo trắng"

Đơn sản xuất **chỉ lập từ một đợt giao còn thiếu** của đơn bán đã ký hợp đồng (BR-05). Backend tự điền dòng
(số thùng thiếu sau khi trừ hàng đã giữ và các đơn SX đang mở), kho nhận (= kho xuất), ngày cần hàng (= ngày bắt
đầu cửa sổ giao) và cờ duyệt mẫu. FE chỉ gửi `{ salesOrderId, deliveryBatchSeq, producerId }`.

Nguồn danh sách "đợt giao còn thiếu": `GET /production-schedule` → `shortBatches[]`:

```ts
{ salesOrderId, orderNumber, customerId, shippingWarehouseId, shippingWarehouseCode, deliveryBatchId,
  seq, windowStart, windowEnd, shortCartons, lines: [{ orderLineId, sku, cartons }], alerts: [...] }
```

(lọc `producerId`, `warehouseId`, `from`, `to`, `alertsOnly`, `limit` ≤ 200; `truncated = true` nghĩa là bị cắt.)

Luồng đề xuất: trang "Lập đơn sản xuất" (hoặc tab trong trang danh sách) liệt kê `shortBatches`; mỗi dòng có nút
"Lập" → dialog chọn đơn vị SX (`ACTIVE`) → `POST` → chuyển sang trang chi tiết đơn nháp vừa tạo. Trang đơn bán có
thể có lối tắt tương tự cho các đợt của chính đơn đó. Lỗi hay gặp: 409 `PRODUCTION_ORDER_NOTHING_SHORT` (đợt không
còn thiếu — làm mới danh sách), `PRODUCTION_ORDER_NOT_ALLOWED` (đơn chưa CONFIRMED / chưa chọn kho xuất).

### 3.4 Trang chi tiết

- Đầu đơn: mã, trạng thái, đơn bán + đợt giao (cửa sổ giao), khách, đơn vị SX, kho nhận, ngày cần hàng, ngày dự
  kiến, cờ "cần duyệt mẫu in" (+ lý do tắt), ghi chú, người lập / **người gửi** (`sentBy`) và các mốc thời gian, ghi
  chú nhận đơn (`acceptanceNote`), lý do huỷ/đóng. Đơn cũ (trước 2026-11-20) có thể còn `approvedBy`/`approvedAt`/
  `submittedAt`/`returnedReason`: hiện như lịch sử nếu có, không cần ô riêng.
- **Cảnh báo sức chứa (BR-25)** từ `capacity`: khi `exceedsProjected` hoặc `exceedsIfAllApproved` thì hiện banner
  cảnh báo (thiếu `shortfallM3` m³), **không chặn** nút nào. Hiện lúc nháp, tức là cả ngay trước khi Điều phối SX bấm
  Gửi.
- Bảng dòng: SKU, số thùng, cái/thùng, tổng cái, yêu cầu đóng gói/dán nhãn, đã nhận `receivedCartons/cartons`.
- Lịch sử trạng thái (`history`).
- Khối "Gửi cho đơn vị sản xuất" (từ `SENT`): kênh, nút tải phiếu (`/slip`), lịch sử mail (`/dispatch`), nút gửi lại.

### 3.5 Form sửa nháp

- Đổi đơn vị SX (chỉ `ACTIVE`), ngày cần hàng, ghi chú, số thùng từng dòng (≥ 1), yêu cầu đóng gói từng dòng.
- Cờ duyệt mẫu: nếu `sampleRequiredByDefault = true` mà người dùng tắt → **bắt buộc nhập lý do**
  (`sampleWaiverReason`, BR-24).
- Số thùng không được vượt phần đợt còn thiếu: BE trả 409 `PRODUCTION_ORDER_EXCEEDS_SHORTAGE` với message
  "…lacks N carton(s)…" — hiển thị lỗi tại dòng.
- Gửi `version` đã đọc; 409 `OPTIMISTIC_LOCK` → báo "đơn đã thay đổi, tải lại".

### 3.6 Mã lỗi cần map sang tiếng Việt (`errors.ts`)

`PRODUCER_NOT_FOUND`, `PRODUCER_CODE_TAKEN`, `PRODUCER_INACTIVE`, `PRODUCTION_ORDER_NOT_FOUND`,
`PRODUCTION_ORDER_STATUS_INVALID`, `PRODUCTION_ORDER_NOT_ALLOWED`,
`PRODUCTION_ORDER_NOTHING_SHORT`, `PRODUCTION_ORDER_EXCEEDS_SHORTAGE`, `PRODUCTION_ORDER_HAS_DRAFT_RECEIPTS`
(huỷ/đóng khi còn phiếu nhập nháp), `PRODUCTION_ORDER_SAMPLE_REQUIRED`, `PRINT_FILE_MISMATCH` (lúc gửi: file in
không còn khớp bản thiết kế khách đã chốt), `PRODUCTION_ORDER_SLIP_NOT_FOUND`, `PRODUCTION_ORDER_SLIP_MISMATCH`,
`PRODUCTION_ORDER_RESEND_NOT_ALLOWED`, `OPTIMISTIC_LOCK`, `VALIDATION_FAILED`. `PRODUCER_INACTIVE` cũng có thể về lúc
gửi. `PRODUCTION_ORDER_SELF_APPROVAL` BE không còn trả (giữ trong enum cho client cũ) — không cần map.

---

## 4. Thứ tự làm và tiêu chí xong

1. Gỡ `production-schemas.ts` cũ và các chỗ import; sửa `PRODUCTION_ORDER_PERMISSIONS`; thêm quyền
   `sales-producers` và `production-schedule`; route + menu.
2. Màn A (đơn vị sản xuất): schema zod, api, queries/mutations, bảng + form, mock routes, test.
3. Màn B: schema, `lifecycle.ts` (bảng §3.2) có unit test theo vai trò, danh sách + lọc, chi tiết, các action
   (dialog nhập lý do / ngày), form sửa nháp, lập đơn từ `shortBatches`, mock routes, test.
4. Trang đơn bán: thay phần "lệnh sản xuất" cũ bằng danh sách đơn SX của đơn (`?salesOrderId=`).

Xong khi: `typecheck`, `lint`, test của repo FE đều xanh; không còn import nào tới hợp đồng LSX cũ; nút hiện đúng
theo bảng §3.2 cho từng vai trò (Điều phối SX lập và gửi — kể cả đơn chính mình lập; Sales Manager, Sale, Quản lý
kho chỉ đọc); không còn chỗ nào gọi `/submission`, `/approval`, `/return` hay kiểm `production-orders:APPROVE`;
hàng đợi duyệt bản thiết kế hiện cho Điều phối SX, không hiện cho Sales Manager.
