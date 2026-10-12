# Thay đổi schema — SCRUM-429 / SCRUM-450 (onboarding khách B2B)

> **Cập nhật 12/10/2026:** chủ repo cho phép nhánh tự viết migration cho phạm vi này. Đề xuất cũ đã
> được hiện thực trong `V20261014000100__customer_b2b_onboarding.sql` (hậu tố `00100` của ngày
> 20261014 dành cho nhánh B2B; nhánh production dùng `00200` trở lên). Tài liệu này giờ mô tả
> **cái đã làm** và **các quyết định mặc định chưa được chủ nghiệp vụ duyệt**.

Nguồn nghiệp vụ: kltn-docs `ecommerce/18-customer-information` (main, `84b8b54`, 10/10/2026):
§4.1, §5, BR-01, BR-01a, BR-02. Jira SCRUM-429/450 đọc trực tiếp 12/10/2026: không comment, không
AC riêng ngoài mô tả.

## 1. `customer.customer`

| Thay đổi | Chi tiết |
| --- | --- |
| `tax_code VARCHAR(14)` | NULL cho mọi khách tạo trước migration — **không bịa, không backfill**. Khách mới bắt buộc có MST (service + `@NotBlank`). Sale bổ sung/sửa MST qua `PUT /api/v1/customers/{id}`. |
| `ck_customer_tax_code` | `^[0-9]{10}(-[0-9]{3})?$`. Chuẩn hoá ở `TaxCode`: bỏ khoảng trắng/dấu chấm, 13 số liền → `10-3` (Thông tư 105/2020/TT-BTC). Không kiểm chữ số kiểm tra (tài liệu không nêu). |
| `uk_customer_tax_code_live` | Unique `tax_code` **chỉ** với `PENDING_ACTIVATION` và `ACTIVE`. BR-01 nói "hai hồ sơ Active"; §4.1 yêu cầu kiểm trùng khi Sale tạo (lúc đó hồ sơ là pending) nên pending được tính để hai lần tạo đồng thời không cùng lọt. `INACTIVE`/`BLOCKED` không bị ràng buộc — không siết hơn tài liệu. |
| `ck_customer_status` | Thêm `PENDING_ACTIVATION`. |
| `ck_customer_pending_has_account` | Khách pending luôn có `user_id` (tài khoản sẽ được kích hoạt). |

Ánh xạ vòng đời §5 (**quyết định mặc định của nhánh, chờ chủ nghiệp vụ xác nhận**): Pending Activation
= `PENDING_ACTIVATION`, Active = `ACTIVE`, Suspended = `BLOCKED`, Closed = `INACTIVE`. Chuyển trạng thái do
staff: không ai đặt `PENDING_ACTIVATION` thủ công; từ pending chỉ được đóng (`INACTIVE`); `PENDING → ACTIVE`
chỉ qua kích hoạt.

Chuyển dữ liệu: chỉ những khách `INACTIVE` có tài khoản mang lời mời kiểu cũ của nhánh
(`{portal-invitation}…` trong `password_hash`) mới thành `PENDING_ACTIVATION`. Mọi `INACTIVE` khác giữ nguyên.

## 2. `identity`

| Thay đổi | Chi tiết |
| --- | --- |
| `app_user.password_hash` nullable | NULL = tài khoản chưa đặt mật khẩu (người liên hệ chưa kích hoạt). Login trả 401 như tài khoản không tồn tại. |
| `identity.portal_invitation` | Mỗi email mời một dòng; chỉ lưu `token_digest` (SHA-256). Kết thúc đúng một cách: `consumed_at` (kích hoạt) hoặc `revoked_at` (gửi lại). CHECK: digest hex, `expires_at > issued_at`, không vừa consumed vừa revoked, consumed trong thời hạn, cặp `delivery_status`/`delivery_attempted_at`. `uk_portal_invitation_open`: một lời mời mở mỗi tài khoản. FK `user_id` DEFERRABLE (thứ tự flush Hibernate). |
| Chuyển lời mời cũ | Dòng `{portal-invitation}<issued>:<expires>:<digest>` → một dòng `portal_invitation` cùng digest và thời hạn, rồi `password_hash = NULL`. Link đã gửi vẫn dùng được. |

Lý do chuyển từ `password_hash` sang bảng: quy tắc repo "mỗi bất biến hai lần (aggregate + CHECK)" không
làm được trong một chuỗi; cần lưu kết quả gửi mail để lỗi "nhìn thấy được"; và cột mật khẩu không còn
mang credential khác loại.

## 3. Quyền

`customer-customers:CREATE` cho `SALES_STAFF`, `ECOMMERCE_ADMIN`, `SYSTEM_ADMIN`; tăng `app_role.version`
của ba role này trong cùng migration. Tạo khách và gửi lại lời mời yêu cầu quyền này; người giữ role
CUSTOMER bị từ chối kể cả khi có quyền.

## 4. Kiểm chứng

- `tools/db/qa/09_customer_onboarding.sql` (18 kiểm tra), `B2bOnboardingMigrationUpgradeTest` (nâng cấp
  từ `V20261013000600` có dữ liệu cũ), `schema.dbml` sinh lại bằng `tools/db/gen_dbml.py`.

## 5. Còn chờ quyết định (không tự coi là đã duyệt)

TTL lời mời 72 giờ, cooldown gửi lại 1 phút, route FE `/portal/activate` (chỉ là mặc định profile local),
ánh xạ Suspended/Closed ở §1, và việc MST của hồ sơ `BLOCKED` có nên chặn tạo mới hay không.
