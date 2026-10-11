# Yêu cầu schema — SCRUM-429 / SCRUM-450 (onboarding khách B2B)

Ngày đề xuất: 12/10/2026. Người xử lý schema: **Tú**. Đây là **đề xuất**, không phải migration đã
viết hay đã duyệt. Nhánh `SCRUM-429-b2b-onboarding` **không thêm file nào vào `db/migration`**.

Nguồn nghiệp vụ: kltn-docs `ecommerce/18-customer-information` (bản `main` merge 10/10/2026,
commit `84b8b54`): §4.1 tạo & kích hoạt hồ sơ, §5 vòng đời, BR-01, BR-01a, BR-02.
Schema được kiểm ở `origin/develop` = `1de8f42` (12/10/2026).

## 1. Đã có trên develop và đang được dùng

| Nhu cầu | Bảng/cột hiện có | Cách nhánh dùng |
| --- | --- | --- |
| Hồ sơ công ty | `customer.customer.full_name` | Tên công ty do Sale nhập |
| Email/điện thoại người liên hệ | `customer.customer.email`, `phone`; `uk_customer_email_normalized` | Email đăng nhập của người liên hệ, kiểm trùng không phân biệt hoa thường |
| Tài khoản cổng | `identity.app_user` (`full_name` = tên người liên hệ), `identity.user_role` CUSTOMER, `customer.customer.user_id` + `uk_customer_user` | Sale tạo tài khoản chưa có mật khẩu, gắn với hồ sơ |
| Điều khoản thanh toán | `customer.credit_profile` (SCRUM-427), quyền `customer-credit-profiles:APPROVE` | Không ghi khi tạo khách; người duyệt hạn mức đặt qua API sẵn có (BR-02). Chưa có dòng = chỉ trả trước |
| Lời mời một lần | `identity.app_user.password_hash` | Tài khoản chờ kích hoạt lưu `{portal-invitation}<issued>:<expires>:<sha256>` thay cho hash mật khẩu. Kích hoạt ghi hash bcrypt (lời mời hết hiệu lực), gửi lại ghi giá trị mới (link cũ hết hiệu lực). Chỉ lưu digest |
| Pending Activation | `customer.customer.status = 'INACTIVE'` + tài khoản còn lời mời | Biểu diễn tạm, xem §2.2 |

## 2. Còn thiếu — đề nghị Tú bổ sung

### 2.1 Mã số thuế (BR-01) — **chặn tiêu chí "tax code"**

`customer.customer` không có cột MST. Nhánh **không nhận** MST trong API để không giả là đã lưu.

| Đề xuất | Ràng buộc |
| --- | --- |
| `customer.customer.tax_code VARCHAR(32)` | Nullable cho hồ sơ cũ; khách tạo mới bắt buộc ở tầng service |
| Định dạng | Cùng quy ước NCC: `^[0-9A-Za-z][0-9A-Za-z-]{6,30}[0-9A-Za-z]$` và có chữ số (`V20260930000200`) — Tú xác nhận có dùng chung không |
| Uniqueness | BR-01 nói "không cho hai hồ sơ **Active** trùng MST". Cần chốt: unique toàn bảng hay partial index chỉ trên `ACTIVE`/`PENDING_ACTIVATION`. Đề xuất tối thiểu: `CREATE UNIQUE INDEX ... (upper(tax_code)) WHERE tax_code IS NOT NULL`; nếu chỉ chặn hồ sơ còn hiệu lực thì thêm điều kiện trạng thái sau khi chốt §2.2 |

Khi có cột: thêm `taxCode` vào `CreateBusinessCustomerRequest`, kiểm trùng trước rồi để index chặn race (giống email).

### 2.2 Trạng thái `PENDING_ACTIVATION` (kltn-docs 18 §5)

`ck_customer_status` chỉ có `ACTIVE/INACTIVE/BLOCKED`. Tài liệu có `Pending Activation`, `Suspended`, `Closed`, `Anonymized`, `Merged`.
Hiện nhánh dùng `INACTIVE` cho khách chờ kích hoạt — đúng hành vi BR-01a (không đăng nhập, không
xem giá/đặt hàng vì mọi kiểm tra đều đòi `ACTIVE`), nhưng **không phân biệt được** với khách đã đóng.

Đề xuất: thêm `PENDING_ACTIVATION` vào `ck_customer_status`, kèm `UPDATE customer.customer SET status='PENDING_ACTIVATION' WHERE status='INACTIVE' AND user_id IN (SELECT id FROM identity.app_user WHERE password_hash LIKE '{portal-invitation}%')`.
Ánh xạ `Suspended`→`BLOCKED`, `Closed`→`INACTIVE` cần Tú/chủ nghiệp vụ chốt; nhánh không tự đổi.

### 2.3 Quyền `customer-customers:CREATE`

Chưa được khai báo/seed. Nhánh gác tạo/gửi lại bằng `customer-customers:UPDATE` **cộng** kiểm tra role
`SALES_STAFF`/`ECOMMERCE_ADMIN` — cùng mẫu với `PUT /api/v1/customers/{id}` sẵn có (role CUSTOMER cũng có UPDATE nhưng bị chặn bởi kiểm tra role).
Đề xuất migration: khai báo `customer-customers:CREATE`, cấp cho `SALES_STAFF`, `ECOMMERCE_ADMIN`
(`SYSTEM_ADMIN` nhận qua cross join như các migration khác), và `UPDATE identity.app_role SET version = version + 1`
cho các role đổi quyền. Sau đó đổi annotation sang `Action.CREATE` và thêm `CREATE` vào `@PermissionResource`.

### 2.4 Bảng lời mời riêng (không bắt buộc)

Nếu Tú muốn lịch sử lời mời (ai gửi, bao nhiêu lần, trạng thái gửi mail) thay vì một credential trên
tài khoản: `identity.portal_invitation(id, user_id FK, token_digest CHAR(64) UNIQUE, issued_at, expires_at,
consumed_at, issued_by, version, audit)`, `CHECK (expires_at > issued_at)`, partial unique một lời mời mở mỗi user.
Code hiện tại gói toàn bộ quy tắc trong `User`/`PortalInvitation`, nên chuyển sang bảng chỉ đổi adapter.

## 3. Không làm

- Không tạo bảng/cột song song, không sửa migration đã áp dụng, không dùng `user_session`/audit/Redis làm kho lời mời.
- Không ghi `credit_profile` khi Sale tạo khách (BR-02: chỉ người duyệt).
