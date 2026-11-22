# Rà soát phân quyền: code ↔ kltn-docs (2026-11-20)

**Nguồn so sánh**

- Docs: kltn-docs `origin/develop` @ `8ee9611` (2026-11-18) — `00-system-overview` §2 (role, quyền theo ngữ cảnh, RBAC),
  bảng actor của 15, 17, 20, 21.
- Code: nhánh `feature/drop-production-order-price` @ `c15413c4`.
  - Ma trận role × quyền: **chạy thật toàn bộ migration** (`db/migration`, 47 file) lên một Postgres 16 tạm rồi đọc
    `identity.role_permission` — không đoán từ file SQL. Seed demo (`db/demo`, chạy ở profile `local`/`test`) chỉ gán
    user vào role, không đổi quyền của role, nên ma trận dưới đây là ma trận của mọi môi trường.
  - Quyền của từng endpoint: đọc `@RequiresPermission` / mapping của 651 handler trong `src/main/java`; cộng các kiểm tra
    trong service (`user.can(...)`, `actor.require(...)`) và các "audience" của thông báo.

Ký hiệu role: **SA** System Admin · **WM** Quản lý kho · **WS** Nhân viên kho · **PC** Điều phối sản xuất ·
**AC** Kế toán · **SM** Quản lý kinh doanh · **SS** Nhân viên Sale · **CU** Khách hàng.

---

## 1. Tóm tắt

| | Kết quả |
|---|---|
| 8 role hệ thống, đúng tên & phạm vi dữ liệu theo §2.5.3 | ✅ (WS, WM = `WAREHOUSE`; còn lại `ALL` — xem điểm **B4** về CUSTOMER) |
| SA giữ toàn bộ 262 quyền | ✅ |
| Quản trị tài khoản & phân quyền chỉ SA (chốt 2026-11-07) | ✅ `identity-*` chỉ SA |
| Ma trận CUSTOMER và SYSTEM_ADMIN cố định (§2.5.3 điểm 5) | ✅ `IdentityServiceImpl.isEditable` |
| Ràng buộc người quản trị (§2.5.1): không cấp vượt mình, không tự quản lý, giữ ≥ 1 SA, giữ ≥ 1 role có quyền sửa phân quyền | ✅ `PrivilegeGuard` (`PRIVILEGE_ESCALATION`, `OWN_ACCOUNT_NOT_MANAGEABLE`), `LAST_SYSTEM_ADMIN`, kiểm "last one holding identity-rbac:APPROVE" |
| Bốn mắt: duyệt Đơn sản xuất ≠ người lập; duyệt điều chỉnh tồn ≠ người gửi; duyệt trả hàng ≠ người mở; duyệt sản phẩm | ✅ `PRODUCTION_ORDER_SELF_APPROVAL`, `ADJUSTMENT_SELF_APPROVAL`, `RETURN_SELF_APPROVAL`, `SELF_APPROVAL_NOT_ALLOWED` |
| WM có đủ quyền thao tác như WS | ✅ không có quyền nào WS có mà WM thiếu |
| Mọi quyền endpoint dùng đều có trong catalogue | ✅ không thiếu |
| Endpoint không có guard | 21 — đã đối chiếu từng cái: đều có trong `PermissionMatrixTest.UNGUARDED` (login, webhook, link ký của đơn vị sản xuất, catalog công khai, `/me`, đổi mật khẩu) |

**Lệch so với docs: 4 điểm nên sửa (A), 6 điểm cần nhóm quyết (B), 1 nhóm dọn dẹp (C).**

---

## 2. A — Lệch rõ với docs, nên sửa

> **Đã sửa (2026-11-20, chưa commit):** A1 → `V20261209000100__identity_actuator_admin_only.sql`; A3 →
> `V20261209000200__identity_accountant_reads_stock_adjustments.sql`. Kèm `tools/db/qa/99_permission_review_a1_a3.sql`,
> pin trong `PermissionMatrixTest.SEEDED_HOLDERS`, test HTTP trong `StaffAdjustmentAccessIntegrationTest`.
>
> **Đã sửa (2026-11-20, chưa commit):** A2 → `V20261209000300__identity_lot_trace_permission.sql` (quyền mới
> `inventory-lot-traces`); A4 → `V20261209000400__identity_coordinator_reads_delay_alerts.sql` (quyền mới
> `delivery-delay-alerts:READ`, PC được `delay-alerts:READ`, danh sách bỏ `DELIVERY_LATE` với ai không có quyền giao).

### A1. SM có quyền actuator (`platform-actuator:READ` / `UPDATE`)

- Code: `platform-actuator:READ|UPDATE` → **SM, SA** (`ResourceServerSecurityConfig:160-162`). `UPDATE` phủ mọi method
  không phải GET/HEAD trên actuator.
- Docs §2.1: SM là việc kinh doanh (sản phẩm, giá, thiết kế, khách, duyệt Đơn sản xuất); cấu hình / vận hành hệ thống là
  việc của SA. Không chỗ nào giao actuator (metrics, health, module map, thao tác vận hành) cho SM.
- Javadoc `ActuatorPermission` nói đây là giữ lại "roles that reached them before" — tức là di sản, không phải quyết định
  nghiệp vụ.
- **Đề xuất:** thu `platform-actuator:*` của SM (migration mới, nhớ `UPDATE identity.app_role SET version = version + 1`
  cho SALES_MANAGER).

### A2. Truy xuất lô: SS và PC không đọc được

- Docs 20 §3 (đầu ra): **Báo cáo truy xuất lô** → "Quản lý kho, **Sale (CSKH)**, **Điều phối sản xuất**, thu hồi hàng".
- Code: `GET /api/v1/inventory/lots`, `/lots/{lotId}`, `/lots/{lotId}/movements` cần `inventory-stock-movements:READ` →
  chỉ **WS, WM, SA**. SS và PC gọi là 403.
- Vướng: cùng quyền đó mở cả sổ biến động tồn. Cấp nguyên `inventory-stock-movements:READ` cho SS/PC là cho xem cả sổ
  kho (docs 20: SS/PC "không thao tác tồn" — xem thì docs không cấm, nhưng cũng không giao).
- **Đề xuất:** tách tài nguyên riêng cho truy xuất lô (vd. `inventory-lot-trace:READ`) gán WM, WS, SS, PC; hoặc nhóm
  chốt lại docs nếu không muốn SS/PC xem.

### A3. Kế toán không xem được phiếu điều chỉnh tồn

- Docs 20 §3 (đầu ra): **Phiếu điều chỉnh tồn** (`Pending Approval → Posted / Rejected / Withdrawn`) → "Quản lý kho,
  **Kế toán**".
- Code: `GET /api/v1/inventory/adjustments[/{id}]` cần `inventory-stock-adjustments:READ` → **WS, WM, SA**. AC không có.
- Thêm nữa, phạm vi của AC là `ALL` nên cấp quyền đọc là AC thấy điều chỉnh mọi kho — đúng ý "kế toán đối chiếu".
- **Đề xuất:** cấp `inventory-stock-adjustments:READ` (+ `VIEW_PAGE`) cho ACCOUNTANT.

### A4. PC không đọc được danh sách cảnh báo trễ

- Docs 17 §2 và bảng đầu ra: **Cảnh báo trễ** (`AT_RISK`, `PRODUCTION_LATE`, `PO_NEEDED`, `DELIVERY_LATE`) →
  "**Điều phối sản xuất (trừ `DELIVERY_LATE`)**, Sale, Sales Manager; Quản lý kho (đợt giao trễ)". PC "nhận và **xử lý**
  cảnh báo trễ với đơn vị sản xuất".
- Code: `GET /api/v1/delay-alerts` cần `delay-alerts:READ` → **SM, SS, WM, SA**. PC **không** có.
  - Thông báo thì đúng: AT_RISK/PRODUCTION_LATE/PO_NEEDED gửi cho người giữ `production-schedule:READ` (có PC),
    DELIVERY_LATE gửi cho người giữ `delay-alerts:READ` (không có PC) — `DelayAlertNotificationListener`.
  - Tức là `delay-alerts:READ` đang bị dùng làm **audience** của DELIVERY_LATE, nên ai cấp nó cho PC sẽ vô tình làm PC
    nhận cả DELIVERY_LATE — trái BR-30.
- Hệ quả: PC được báo cảnh báo trễ nhưng không mở được danh sách cảnh báo để xử lý (chỉ còn cờ trễ trên lịch sản xuất).
- **Đề xuất:** cho PC đọc `/delay-alerts` nhưng chỉ ba loại sản xuất (lọc theo quyền như cách `TaskAccess` làm), hoặc
  tách audience DELIVERY_LATE khỏi quyền đọc. Cần xác nhận lịch sản xuất có đủ để PC "xử lý" không trước khi sửa.

---

## 3. B — Code làm khác hoặc vượt docs, cần nhóm quyết rồi ghi lại vào docs

### B1. Duyệt quyết toán trả hàng do Kế toán (`sales-rma-resolutions:APPROVE` → AC)

- Code: Sale đề xuất hoàn tiền từng dòng (`/returns/{id}/resolution`, `sales-rma-resolutions:UPDATE` → SS), **Kế toán**
  duyệt / từ chối (`/returns/{id}/settlement-approval|rejection`, `sales-rma-resolutions:APPROVE` → AC).
- Docs 17 §4 b38–40 và bảng 00 §2.3: Sale duyệt yêu cầu trả; hệ thống tính số phải hoàn; Kế toán chỉ **ghi nhận đã
  hoàn**. Không có bước "người thứ hai duyệt đề xuất hoàn" và không có quyền tương ứng trong §2.3.
- **Cần quyết:** giữ bước duyệt của Kế toán (thì ghi vào 17 §4 và 00 §2.3) hay bỏ.

### B2. SM xem hoá đơn điện tử (`einvoices:READ`, `VIEW_PAGE`)

- Docs 21 §2 đã tự ghi chú: SS lẽ ra xem hoá đơn của đơn mình phụ trách nhưng "backend chưa cấp … hiện chỉ Sales
  Manager xem được (cập nhật 2026-11-14 — đồng bộ code)". Tức docs đang mô tả theo code, còn ý gốc là **SS**.
- **Cần quyết:** cấp `einvoices:READ` cho SS (kèm lọc "đơn mình phụ trách") hay chốt hẳn là chỉ SM.

### B3. SM không đọc được đơn hàng (`sales-orders:READ` thiếu SM)

- `sales-orders:READ` → AC, CU, PC, SS, SA, WM — **không có SM**; SM cũng không có `sales-quotes:*`.
- SM có `sales-contracts:READ`, `sales-order-delivery-batches:READ`, Đơn sản xuất, dashboard. Docs §2.1 không giao SM
  việc gì trên đơn, nhưng SM là người **duyệt Đơn sản xuất** của một đơn và xem dashboard đơn — khó duyệt nếu không mở
  được đơn gốc.
- **Cần quyết:** cấp `sales-orders:READ` (+ `VIEW_PAGE`) cho SM hay không.

### B4. CUSTOMER có phạm vi `ALL`, docs nói `OWN`

- Docs §2.5.3: phạm vi `OWN` "dùng cho các chức năng của khách trên cổng".
- Code: `CUSTOMER.data_scope = ALL`; việc chặn khách xem dữ liệu khách khác dựa vào **kiểm chủ sở hữu trong từng
  handler** (`ScopeGuards`, endpoint `/me`), và `PermissionMatrixTest.aCustomerCannotReachAnotherCustomersData` gọi thử
  mọi handler CUSTOMER với id của khách khác.
- Không phải lỗ hổng hiện tại (đã có test), nhưng là cơ chế khác docs: một handler mới quên owner check thì phạm vi
  không cứu. **Cần quyết:** sửa docs cho đúng cơ chế, hoặc chuyển CUSTOMER sang `OWN`.

### B5. `inventory-reservations:UPDATE` cho WS — endpoint tiêu thụ hold qua HTTP

- `POST /api/v1/inventory/reservations/{id}/consumption` cần `inventory-reservations:UPDATE` → **WS, WM, SA**.
  CLAUDE.md: "nothing in the repo calls it — picking never goes over HTTP"; FE cũng không gọi (đã grep
  `fe-backoffice-stockflow-commerce`). Tức nhân viên kho có một đường trừ tồn ngoài luồng lấy hàng mà không màn nào
  dùng (07 BR-10: lấy hàng chỉ theo LPN).
- **Cần quyết:** thu quyền này của WS (chỉ để WM/SA cho vận hành), hoặc bỏ endpoint.

### B6. "Đổi SKU dòng đơn" (17 §4.7 b25) chưa có endpoint

- Docs: Sale đổi SKU của dòng đã chốt sang SKU của bản thiết kế sửa (giữ số thùng, số cái/thùng, đơn giá), khi Đơn
  sản xuất liên quan chưa `In Production`; ghi lịch sử. Là việc của SS trong 00 §2.1 và 17 §2.
- Code: không có handler nào dưới `/api/v1/orders` đổi SKU dòng đơn, không có quyền tương ứng. Đây là **chức năng
  thiếu**, không phải lệch quyền — ghi lại để nhóm biết danh sách quyền của SS chưa phủ hết docs.

---

## 4. C — Quyền được seed nhưng không chỗ nào kiểm (dọn dẹp)

Không endpoint nào, không `can()/require()` nào, không audience thông báo nào dùng các quyền sau (đã loại trừ các
quyền `VIEW_PAGE` — FE dùng cho menu):

| Quyền | Đang gán cho |
|---|---|
| `customer-customers:APPROVE` | SM, SS, SA (javadoc `CustomerStatusResourceDeclaration` đã nói Sales giữ nó nhưng không dùng) |
| `fulfillment-packages:CREATE` | WS, WM, SA |
| `fulfillment-pick-lists:UPDATE` | WS, WM, SA |
| `fulfillment-shipments:CREATE`, `fulfillment-shipments:UPDATE` | WS, WM, SA |
| `inventory-inventory-items:CREATE`, `:EXPORT` | WM, SA |
| `inventory-stock-items:EXPORT`, `inventory-stock-movements:EXPORT` | SA |
| `payment-refunds:APPROVE` | AC, SA |
| `product-customization-templates:DELETE` | SM, SA (javadoc controller đã ghi là baseline còn seed) |
| `product-variants:EXPORT` | SM, SA |
| `sales-orders:CREATE`, `sales-orders:EXPORT` | SS / SA |

Đã grep FE: `src/constants/permissions.ts` tự ghi `fulfillment-packages:CREATE`, `fulfillment-pick-lists:UPDATE`,
`fulfillment-shipments:CREATE/UPDATE` "không còn bảo vệ endpoint nào" (BE PR #148). Riêng hai quyền FE **vẫn dùng làm
hằng số gate nút**: `product-customization-templates:DELETE` và `sales-orders:CREATE` — nút FE có thể đang mở/đóng
theo một quyền mà BE không kiểm; nên xem lại phía FE trước khi xoá.

Không gây hại ở BE (chỉ thừa), nhưng hiện trên màn phân quyền như quyền thật. Nếu dọn: migration xoá `role_permission` +
`permission`, nâng `app_role.version` các role bị đụng, kèm SQL kiểm trong `tools/db/qa/`.

`chat-supervision:UPDATE`, `fulfillment-packing:APPROVE`, `fulfillment-task-oversight:READ`,
`fulfillment-waybill-corrections:UPDATE`, `platform-actuator:*` **có** dùng — kiểm trong service / security config,
không qua `@RequiresPermission`.

---

## 5. Những chỗ đã khớp (đối chiếu từng dòng §2.1 / §2.3)

| Docs | Quyền trong code | |
|---|---|---|
| Giao một phần: SS, SA | `sales-order-partial-shipments:UPDATE` SS SA | ✅ |
| Duyệt kỹ thuật thiết kế: SM | `design-designs:APPROVE` SM SA | ✅ |
| Lập Đơn sản xuất, ghi tiến độ, mẫu in, đóng thiếu/huỷ, danh mục đơn vị SX: PC | `production-orders:CREATE/UPDATE`, `sales-producers:CREATE/UPDATE` PC SA | ✅ |
| Duyệt Đơn sản xuất: SM (≠ người lập) | `production-orders:APPROVE` SM SA + `PRODUCTION_ORDER_SELF_APPROVAL` | ✅ |
| PC không chat với khách | PC không có quyền `chat-*` | ✅ |
| Huỷ có kiểm soát kế hoạch xe: WM, SA | `fulfillment-controlled-cancellations:UPDATE` WM SA | ✅ |
| Duyệt điều chỉnh tồn: WM (≠ người gửi) | `inventory-stock-adjustments:APPROVE` WM SA + `ADJUSTMENT_SELF_APPROVAL` | ✅ |
| Duyệt tín dụng & lịch thanh toán: AC | `sales-order-credit-holds:APPROVE`, `customer-credit-profiles:APPROVE`, `customer-payment-schedules:APPROVE` AC SA | ✅ |
| Hoàn tiền (ghi nhận đã hoàn): AC | `payment-refunds:CREATE/UPDATE` AC SA | ✅ |
| Phát hành hoá đơn điện tử: AC | `einvoices:CREATE/UPDATE/APPROVE/EXPORT` AC SA | ✅ |
| Cấu hình kết nối HĐĐT: SA | `einvoice-settings:UPDATE` SA (AC chỉ READ) | ✅ |
| Nhật ký hệ thống: SA | `platform-audit-logs:*` SA | ✅ |
| Giám sát chat: SM (hoặc SS được chỉ định) | `chat-supervision:*` SM SA — "SS được chỉ định" làm bằng role tự tạo | ✅ |
| SS: chọn kho xuất, treo/gỡ treo, duyệt huỷ, đổi địa chỉ, hợp đồng, báo giá, đơn từ báo giá | `sales-order-shipping-warehouses:UPDATE`, `sales-order-holds:UPDATE`, `sales-order-cancellations:UPDATE`, `sales-order-addresses:UPDATE`, `sales-contracts:*`, `sales-quotes:*`, `sales-quote-conversions:CREATE` | ✅ |
| SS dời cửa sổ giao (17 BR-28) | `sales-contracts:UPDATE` (`/delivery-batches/{id}/window-change`) SS SA | ✅ |
| SS đóng phần còn lại; huỷ đơn thay khách | `sales-order-cancellations:UPDATE` (`/remainder-closure`, `/admin-cancellation`) SS SA | ✅ |
| SS đổi SKU dòng đơn (17 §4.7) | — chưa có endpoint | ❌ xem **B6** |
| PC khai thời gian sản xuất chuẩn | `sales-producers:UPDATE` (`standardLeadDays`) PC SA | ✅ |
| SS duyệt / từ chối yêu cầu trả hàng (≠ người mở) | `sales-rmas:APPROVE` SS SA + `RETURN_SELF_APPROVAL` | ✅ |
| AC xem đơn, hợp đồng chỉ đọc | `sales-orders:READ`, `sales-contracts:READ` AC (không có UPDATE) | ✅ |
| WM: release, task, khu chờ xuất, kết quả giao, sửa vận đơn, webhook lỗi, kiểm kê, cách ly | `sales-order-releases:UPDATE`, `fulfillment-task-assignment:*`, `fulfillment-delivery-outcomes:*`, `fulfillment-waybill-corrections:UPDATE`, `fulfillment-carrier-events:*`, `inventory-cycle-counts:CREATE/APPROVE`, `inventory-stock-packages:UPDATE` (cách ly / gỡ cách ly) | ✅ |
| WS: nhập kho, cất hàng, lấy hàng, kiểm thùng, xếp xe, nhập vận đơn từ file, đếm kiểm kê, đề nghị / rút điều chỉnh | `procurement-goods-receipts:*`, `warehouse-putaway-*`, `fulfillment-picking/packing/dispatch/load-plans`, `fulfillment-waybill-imports:CREATE`, `inventory-cycle-counts:UPDATE`, `inventory-stock-items:UPDATE` | ✅ |
| Báo cáo xuất–nhập–tồn: WM (xuất Excel), SM xem, AC xem + xuất Excel (20 §2, chốt 2026-11-15) | `reporting-stock-summary:READ` WM SM AC, `:EXPORT` WM AC | ✅ |
| Dashboard bán hàng: SM, AC, WM, SA (17 §3) | `reporting-reports:READ` | ✅ |
| Báo cáo công nợ: AC, SM xem + xuất; SS xem (15 §2) | `reporting-receivables:READ` AC SM SS, `:EXPORT` AC SM | ✅ |
| Lịch sản xuất: PC, SS, SM, WM (17 §3) | `production-schedule:READ` | ✅ |
| Khách: duyệt mẫu in, chấp nhận báo giá, yêu cầu báo giá, yêu cầu trả hàng, xem HĐĐT | `production-samples:APPROVE`, `sales-quotes:APPROVE`, `design-quote-requests:CREATE`, `sales-rmas:CREATE`, `einvoices:READ` (chỉ `/me`) | ✅ |

---

## 6. Phụ lục — ma trận đầy đủ (từ DB đã migrate)

| Tài nguyên | Hành động → role |
|---|---|
| `catalog-customer-prices` | **CREATE** SM SA<br>**DELETE** SM SA<br>**READ** SM SS SA<br>**UPDATE** SM SA<br>**VIEW_PAGE** SM SS SA |
| `catalog-storefront` | **READ** CU SA |
| `chat-canned-responses` | **CREATE** SM SS SA<br>**DELETE** SM SS SA<br>**READ** SM SS SA<br>**UPDATE** SM SS SA<br>**VIEW_PAGE** SM SS SA |
| `chat-conversations` | **CREATE** CU SM SS SA<br>**READ** CU SM SS SA<br>**UPDATE** CU SM SS SA<br>**VIEW_PAGE** CU SM SS SA |
| `chat-supervision` | **READ** SM SA<br>**UPDATE** SM SA<br>**VIEW_PAGE** SM SA |
| `customer-addresses` | **CREATE** CU SM SS SA<br>**DELETE** CU SM SS SA<br>**READ** CU SM SS SA<br>**UPDATE** CU SM SS SA<br>**VIEW_PAGE** CU SM SS SA |
| `customer-credit-profiles` | **APPROVE** AC SA<br>**READ** AC SS SA<br>**VIEW_PAGE** AC SS SA |
| `customer-customers` | **APPROVE** SM SS SA<br>**CREATE** SM SS SA<br>**READ** CU SM SS SA<br>**UPDATE** CU SM SS SA<br>**VIEW_PAGE** CU SM SS SA |
| `customer-legal-profiles` | **READ** AC SM SS SA<br>**UPDATE** AC SM SS SA |
| `customer-payment-schedules` | **APPROVE** AC SA<br>**READ** AC SM SS SA<br>**UPDATE** AC SM SS SA |
| `customer-segments` | **CREATE** SM SA<br>**READ** SM SS SA<br>**UPDATE** SM SA<br>**VIEW_PAGE** SM SS SA |
| `customer-status` | **APPROVE** SM SA |
| `delay-alerts` | **READ** SM SS SA WM |
| `design-designs` | **APPROVE** SM SA<br>**CREATE** CU SM SS SA<br>**READ** CU SM SS SA<br>**UPDATE** CU SM SS SA<br>**VIEW_PAGE** CU SM SS SA |
| `design-quote-requests` | **CREATE** CU SA<br>**READ** SM SS SA<br>**UPDATE** SM SS SA<br>**VIEW_PAGE** SM SS SA |
| `design-snapshots` | **READ** CU SS SA<br>**VIEW_PAGE** CU SA |
| `einvoices` | **APPROVE** AC SA<br>**CREATE** AC SA<br>**EXPORT** AC SA<br>**READ** AC CU SM SA<br>**UPDATE** AC SA<br>**VIEW_PAGE** AC SM SA |
| `einvoice-settings` | **READ** AC SA<br>**UPDATE** SA |
| `fulfillment-carrier-events` | **READ** SA WM<br>**UPDATE** SA WM |
| `fulfillment-carriers` | **UPDATE** SA WM |
| `fulfillment-controlled-cancellations` | **UPDATE** SA WM |
| `fulfillment-delivery-corrections` | **UPDATE** SA WM |
| `fulfillment-delivery-outcome-imports` | **CREATE** SA WM<br>**READ** SA WM |
| `fulfillment-delivery-outcomes` | **APPROVE** SA WM<br>**UPDATE** SA WM |
| `fulfillment-dispatch` | **UPDATE** SA WM WS |
| `fulfillment-load-estimates` | **CREATE** SS SA WM<br>**READ** SS SA WM |
| `fulfillment-load-plans` | **CREATE** SA WM WS<br>**READ** SA WM WS<br>**UPDATE** SA WM WS<br>**VIEW_PAGE** SA WM WS |
| `fulfillment-packages` | **CREATE** SA WM WS<br>**READ** SA WM WS<br>**VIEW_PAGE** SA WM WS |
| `fulfillment-packing` | **APPROVE** SA WM<br>**READ** SA WM WS<br>**UPDATE** SA WM WS |
| `fulfillment-picking` | **READ** SA WM WS<br>**UPDATE** SA WM WS |
| `fulfillment-pick-lists` | **READ** SA WM WS<br>**UPDATE** SA WM WS<br>**VIEW_PAGE** SA WM WS |
| `fulfillment-shipments` | **CREATE** SA WM WS<br>**READ** SA WM WS<br>**UPDATE** SA WM WS<br>**VIEW_PAGE** SA WM WS |
| `fulfillment-task-assignment` | **CREATE** SA WM<br>**UPDATE** SA WM |
| `fulfillment-task-oversight` | **READ** SA WM |
| `fulfillment-vehicle-types` | **READ** SA WM WS<br>**UPDATE** SA WM<br>**VIEW_PAGE** SA WM WS |
| `fulfillment-waybill-corrections` | **UPDATE** SA WM |
| `fulfillment-waybill-imports` | **CREATE** SA WM WS<br>**READ** SA WM WS |
| `identity-rbac` | **APPROVE** SA<br>**READ** SA<br>**VIEW_PAGE** SA |
| `identity-roles` | **CREATE** SA<br>**DELETE** SA<br>**READ** SA<br>**UPDATE** SA<br>**VIEW_PAGE** SA |
| `identity-users` | **CREATE** SA<br>**READ** SA<br>**UPDATE** SA<br>**VIEW_PAGE** SA |
| `inventory-cycle-counts` | **APPROVE** SA WM<br>**CREATE** SA WM<br>**READ** SA WM WS<br>**UPDATE** SA WM WS<br>**VIEW_PAGE** SA WM WS |
| `inventory-inventory-items` | **CREATE** SA WM<br>**EXPORT** SA WM<br>**READ** SA WM WS<br>**UPDATE** SA WM<br>**VIEW_PAGE** SA WM WS |
| `inventory-reservations` | **DELETE** SA WM<br>**READ** SA WM WS<br>**UPDATE** SA WM WS<br>**VIEW_PAGE** SA WM WS |
| `inventory-stock-adjustments` | **APPROVE** SA WM<br>**READ** SA WM WS<br>**VIEW_PAGE** SA WM WS |
| `inventory-stock-items` | **EXPORT** SA<br>**READ** SA WM WS<br>**UPDATE** SA WM WS<br>**VIEW_PAGE** SA WM WS |
| `inventory-stock-movements` | **EXPORT** SA<br>**READ** SA WM WS<br>**VIEW_PAGE** SA WM WS |
| `inventory-stock-packages` | **CREATE** SA WM WS<br>**READ** SA WM WS<br>**UPDATE** SA WM<br>**VIEW_PAGE** SA WM WS |
| `notification-notifications` | **READ** AC CU PC SM SS SA WM WS<br>**UPDATE** AC CU PC SM SS SA WM WS<br>**VIEW_PAGE** AC CU PC SM SS SA WM WS |
| `payment-installments` | **READ** AC CU SM SS SA<br>**VIEW_PAGE** AC SM SS SA |
| `payment-receipts` | **CREATE** AC SA<br>**EXPORT** AC SA<br>**READ** AC SA<br>**UPDATE** AC SA<br>**VIEW_PAGE** AC SA |
| `payment-refunds` | **APPROVE** AC SA<br>**CREATE** AC SA<br>**READ** AC CU SM SS SA<br>**UPDATE** AC SA<br>**VIEW_PAGE** AC SM SS SA |
| `platform-actuator` | **READ** SM SA<br>**UPDATE** SM SA |
| `platform-audit-logs` | **READ** SA<br>**VIEW_PAGE** SA |
| `procurement-goods-receipts` | **CREATE** SA WM WS<br>**READ** SA WM WS<br>**UPDATE** SA WM WS<br>**VIEW_PAGE** SA WM WS |
| `product-attributes` | **CREATE** SM SA<br>**DELETE** SM SA<br>**READ** SM SS SA<br>**UPDATE** SM SA<br>**VIEW_PAGE** SM SS SA |
| `product-customization-templates` | **CREATE** SM SA<br>**DELETE** SM SA<br>**READ** SM SA<br>**UPDATE** SM SA<br>**VIEW_PAGE** SM SA |
| `production-orders` | **APPROVE** SM SA<br>**CREATE** PC SA<br>**READ** PC SM SS SA WM<br>**UPDATE** PC SA<br>**VIEW_PAGE** PC SM SS SA WM |
| `production-samples` | **APPROVE** CU SA<br>**READ** CU SA<br>**VIEW_PAGE** CU SA |
| `production-schedule` | **READ** PC SM SS SA WM<br>**VIEW_PAGE** PC SM SS SA WM |
| `product-lifecycle` | **APPROVE** SA |
| `product-products` | **APPROVE** SM SA<br>**CREATE** SM SS SA<br>**DELETE** SM SA<br>**READ** SM SS SA<br>**UPDATE** SM SS SA<br>**VIEW_PAGE** SM SS SA |
| `product-variants` | **APPROVE** SM SA<br>**CREATE** SM SS SA<br>**EXPORT** SM SA<br>**READ** SM SS SA<br>**UPDATE** SM SS SA<br>**VIEW_PAGE** SM SS SA |
| `reporting-receivables` | **EXPORT** AC SM SA<br>**READ** AC SM SS SA<br>**VIEW_PAGE** AC SM SS SA |
| `reporting-reports` | **EXPORT** AC SM SA<br>**READ** AC SM SA WM<br>**VIEW_PAGE** AC SM SA WM |
| `reporting-stock-summary` | **EXPORT** AC SA WM<br>**READ** AC SM SA WM<br>**VIEW_PAGE** AC SM SA WM |
| `sales-contracts` | **APPROVE** SS SA<br>**CREATE** SS SA<br>**READ** AC PC SM SS SA<br>**UPDATE** SS SA |
| `sales-order-addresses` | **UPDATE** SS SA |
| `sales-order-cancellations` | **READ** SS SA<br>**UPDATE** SS SA |
| `sales-order-credit-holds` | **APPROVE** AC SA<br>**READ** AC SS SA<br>**VIEW_PAGE** AC SS SA |
| `sales-order-delivery-batches` | **READ** AC PC SM SS SA WM |
| `sales-order-holds` | **UPDATE** SS SA |
| `sales-order-partial-shipments` | **UPDATE** SS SA |
| `sales-order-releases` | **UPDATE** SA WM |
| `sales-orders` | **CREATE** SS SA<br>**EXPORT** SA<br>**READ** AC CU PC SS SA WM<br>**UPDATE** CU SS SA<br>**VIEW_PAGE** AC CU PC SS SA WM |
| `sales-order-shipping-warehouses` | **READ** PC SM SS SA WM<br>**UPDATE** SS SA |
| `sales-producers` | **CREATE** PC SA<br>**READ** PC SM SS SA WM<br>**UPDATE** PC SA<br>**VIEW_PAGE** PC SM SA |
| `sales-quote-conversions` | **CREATE** SS SA |
| `sales-quotes` | **APPROVE** CU SA<br>**CREATE** SS SA<br>**READ** CU SS SA<br>**UPDATE** SS SA<br>**VIEW_PAGE** CU SS SA |
| `sales-rma-resolutions` | **APPROVE** AC SA<br>**UPDATE** SS SA |
| `sales-rmas` | **APPROVE** SS SA<br>**CREATE** CU SS SA<br>**READ** AC CU SS SA WM WS<br>**UPDATE** CU SS SA<br>**VIEW_PAGE** AC CU SS SA WM WS |
| `warehouse-capacity` | **READ** PC SM SS SA WM<br>**VIEW_PAGE** PC SM SS SA WM |
| `warehouse-locations` | **CREATE** SA WM<br>**DELETE** SA WM<br>**READ** SA WM WS<br>**UPDATE** SA WM<br>**VIEW_PAGE** SA WM WS |
| `warehouse-putaway-tasks` | **READ** SA WM WS<br>**UPDATE** SA WM WS<br>**VIEW_PAGE** SA WM WS |
| `warehouse-putaway-trips` | **APPROVE** SA WM<br>**READ** SA WM WS<br>**UPDATE** SA WM WS<br>**VIEW_PAGE** SA WM WS |
| `warehouse-warehouses` | **CREATE** SA WM<br>**READ** SM SS SA WM WS<br>**UPDATE** SA WM<br>**VIEW_PAGE** SA WM WS |
