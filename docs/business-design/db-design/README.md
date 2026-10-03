# Database — quy tắc & luồng làm việc cho cả team (đọc trước khi code)

Từ 29/9/2026, **toàn bộ schema đã có sẵn trên `develop`**: 107 bảng, 213 FK, permission seed sẵn.
Mỗi người **chỉ viết code** (entity, repository, service, controller). Không ai phải viết migration.

| File | Để làm gì |
|---|---|
| [`2026-09-28-migration-plan.md`](2026-09-28-migration-plan.md) | Kế hoạch đầy đủ; **§8 = bảng nào ai làm** (theo Jira) |
| [`2026-09-28-traceability.md`](2026-09-28-traceability.md) | Mỗi bảng ↔ bước nào trong docs (dùng khi bảo vệ) |
| [`schema.dbml`](schema.dbml) | Sơ đồ, **sinh từ DB thật** (`tools/db/gen_dbml.py`), dán vào dbdiagram.io |
| [`2026-09-27-catalog-warehouse-phase2-qa.md`](2026-09-27-catalog-warehouse-phase2-qa.md) | Ghi chép QA đã dẫn tới thiết kế này |

---

## 1. Quy tắc

1. **Không tự thêm file vào `src/main/resources/db/migration`.** Cần đổi schema (thêm cột, thêm bảng) thì báo Tú. Tú viết migration, chạy bộ kiểm tra, rồi mới merge.
2. **Số migration luôn lớn hơn file mới nhất trên `develop`** tại thời điểm merge. Flyway không chạy file có số nhỏ hơn file đã chạy (không out-of-order). Nhánh nào có migration số cũ thì **phải đổi số trước khi merge**.
3. **Tham chiếu sang module khác là FK thật** (ADR-0007): đơn → khách, giữ tồn → đơn, người duyệt → `app_user`… Hệ quả:
   - Service phải **kiểm tham chiếu trước** và trả 404/409 có mã lỗi riêng. Nếu để DB chặn, người dùng nhận `409 DUPLICATE_KEY` (sai nghĩa) kèm log ERROR.
   - Test không được dùng `Identifiers.newId()` cho khách/user/đơn "tưởng tượng". Dùng `support/DemoData` (test có seed demo) hoặc `support/ReferenceRows` (test `@DataJpaTest`).
4. **Entity:** kế thừa `BaseEntity`, enum dùng `EnumType.STRING`, id là `Identifiers.newId()`. Mọi bảng mới đã có đủ cột audit.
5. **Bảng cũ và bảng mới đang song song** (product, procurement, warehouse). Code mới **viết trên bảng mới**, không thêm tính năng vào bảng cũ.
6. **Ghi `inventory.stock_movement` ở mọi chỗ làm thay đổi tồn** (nhận, cất, lấy, chuyển, điều chỉnh, hoàn).
7. **Số chứng từ mới** (phiếu nhận, lệnh chuyển, điều chỉnh, kiểm kê, RMA…) lấy từ `platform.document_sequence`, không tạo bảng đếm riêng.
8. **Khai báo `@PermissionResource` đúng tên đã seed** (plan §8). Tên sai là quyền không gán được cho vai trò nào.

## 2. Luồng làm việc

```
Code module trên bảng mới  ──▶  PR vào develop (review + test)
        │
        ▼  báo Tú "module X đã chuyển xong"
Tú kích hoạt bước dọn tương ứng trong db/pending:
   product + design xong ─▶ C1    warehouse xong ─▶ C2
   C1 xong + inventory item ─▶ C3    procurement xong ─▶ C4
   dữ liệu cũ sạch (orphan_check = 0) ─▶ C0
        │
        ▼  bước dọn xoá bảng cũ + thêm FK còn lại
```

Trước mỗi PR đụng tới DB: `python3 tools/verify.py`, `mvn -o clean test`, `tools/db/qa/run.sh <db>`
(DB local có seed demo). Kiểm tra dữ liệu mồ côi: `tools/db/orphan_check.sql`.

## 3. Việc riêng cho 2 PR đang mở

### PR #36 — Võ (SCRUM-115/118, supplier & PO communication)

PR DB đã vào `develop` **trước** #36, nên #36 phải:

1. **Đổi số 4 file migration** cho lớn hơn `V20260928006000`, ví dụ:
   `V20260925000100` → `V20260930000100`, `…000200` → `V20260930000200`, `…000300` → `V20260930000300`,
   `V20260926000100` → `V20260930000400` (**đã làm 29/9** khi merge develop vào nhánh). Phần riêng của #38 dùng dải
   `V20260930001000` trở đi, để luôn chạy sau #36.
2. Biết rằng procurement đã có **bộ bảng mới** theo po-schema (`suppliers`, `supplier_items`, `purchase_orders` + revisions/approvals/events, `goods_receipts`, `supplier_invoices`…). Trạng thái PO mới là `CONFIRMED`, **không có `SENT`**. Bảng cũ còn nguyên nên #36 vẫn chạy.
3. Tính năng của #36 **không mất** khi dọn: bước C4 chuyển FK của `procurement.po_delivery_decision` và `notification.po_delivery_control` sang `purchase_orders` (đã test). Khi chuyển code PO, giữ nguyên id của PO.
4. **Không đổi tên `SENT` thành `CONFIRMED` một cách máy móc:** SENT chỉ là đã yêu cầu gửi;
   phản hồi NCC vẫn độc lập. Xem bảng ánh xạ và điều kiện đối soát tại
   [`SCRUM-115-118-backend.md — C4 mapping`](../../SCRUM-115-118-backend.md#c4-mapping-legacy-po-versus-target-purchase_orders).
   Giữ snapshot nội dung gửi, lịch sử gửi/huỷ, MST cũ và bằng chứng người/thời điểm xác nhận;
   không tự bịa dữ liệu để qua constraint bảng mới. #36 chưa kích hoạt C4.

### PR #28 — Phương (SCRUM-89, warehouse map)

1. **Xoá file `V20260918000100__warehouse_map_layout.sql`**. Số của nó đã cũ (Flyway không chạy), và bảng map kho đã do `V20260928000100/0200` tạo.
2. **Sửa entity theo model trên `develop`.** Khác #28 ở các điểm sau:
   - Mỗi chỗ để hàng là một dòng `warehouse.storage_location` (kind `BIN`/`AREA`, `location_code`, `storage_class`, `capacity_units`, `max_weight`, `is_pickable`, `is_putaway_target`, `status`). `bin.location_id` và `area.location_id` trỏ vào đó; bin/area **không còn** cột `location_code` riêng. Cần thêm `StorageLocationJpaEntity`.
   - `area` không có `zone_id`. `shelf.warehouse_id` và `storage_location.warehouse_id` có FK.
   - `bin`, `shelf_level` có thêm cột audit (có default, không bắt buộc map).
   - DB đã có trigger chặn: bin trỏ location loại AREA hoặc khác kho, mã ghép sai (`PREFIX-KỆ-TẦNG-BIN`), đổi prefix/code/level (BR-13).
3. Bảng `warehouse` giữ tạm cột cũ `code/address_line/city` (entity cũ còn map). Entity mới map `prefix/address/return_address/map_*`. Làm xong báo Tú chạy C2.

## 4. Việc tiếp theo của từng người

Xem plan §8. Tóm tắt: Tú (product, PO, cart, stock movement, transfer, COD), Võ (supplier, category, inventory item, adjustment, RMA, pick/pack/tracking, 3-way match), Phương (map kho, variant, cycle count, duyệt/sửa PO, hoá đơn NCC, nhận chuyển kho). **Nhận hàng/QC (WBS 3.3) chưa có story Jira → cần tạo.** Hưng và Thái Vũ làm frontend, không đụng schema.
