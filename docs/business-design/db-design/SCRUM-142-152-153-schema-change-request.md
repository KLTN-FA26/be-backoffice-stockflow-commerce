# Yêu cầu duyệt schema — SCRUM-142 / 152 / 153 (Sprint 3)

Ngày đề xuất: 19/10/2026. Người duyệt schema: **Tú**. Nhánh `feature/sprint3-batch-fefo-traceability-category`,
rebase trên develop `5f00ef5a`, sau đó merge develop `535e6c38`. Chi tiết nghiệp vụ và API: `docs/SCRUM-142-152-153-171-183-backend.md`.

Ba migration mới, version cao hơn bản mới nhất trên develop (`V20261019000700`; develop đã dùng `000100`–`000700` cùng ngày). Không sửa migration đã áp,
không reset dữ liệu, không kích hoạt gì trong `db/pending`.

## 1. `V20261019000800__inventory_sku_reservation_and_allocation.sql` (SCRUM-152)

**Vì sao.** Docs 01 tách *Reserved* (giữ mềm theo SKU, có hạn, chưa gắn vị trí) và *Allocated* (giữ cứng ở bin + lô,
tạo khi release đơn, FEFO — BR-STK-006). Code cũ chọn bin/lô ngay lúc checkout.

| Thay đổi | Ý nghĩa |
|---|---|
| `stock_reservation.stock_item_id` bỏ NOT NULL | NULL = **SKU reservation** (checkout); có giá trị = giữ trên dòng tồn (allocation, production hold, hold cũ) |
| `stock_reservation.sku` NOT NULL, FK `inventory_items(sku)` | backfill từ `stock_item`; trigger `tg_stock_reservation_sku` buộc khớp SKU của dòng tồn |
| `stock_reservation.allocated_from_id` FK tự tham chiếu | allocation trỏ về SKU reservation đã sinh ra nó (giữ qua cả phân bổ lại khi nhặt thiếu) |
| status thêm `ALLOCATED` | chỉ cho SKU reservation (`ck_stock_reservation_kind`) |
| `ck_stock_reservation_kind` | SKU reservation: của đơn khách, không phải production, không tự là allocation, không CONSUMED |
| `ck_stock_reservation_allocation` | allocation thuộc đơn và không có `expires_at` (docs X-RESV) |
| trigger `tg_stock_reservation_soft_atp_insert/update` | lưới an toàn chống bán quá tồn: tổng SKU reservation HELD ≤ on_hand − reserved của dòng AVAILABLE (cùng advisory lock SKU với ứng dụng) |
| index `ix_stock_reservation_sku_held`, `ix_stock_reservation_allocated_from` | ATP theo SKU, truy vết allocation |

Dữ liệu cũ: mọi dòng hiện có đều trên `stock_item`, chỉ được điền `sku`. Hold checkout cũ vẫn hợp lệ và vẫn pick được.

## 2. `V20261019000900__inventory_stock_lot_traceability.sql` (SCRUM-142/153)

| Thay đổi | Ý nghĩa |
|---|---|
| bảng `inventory.stock_lot` | 1 lô = 1 phiếu nhập × (SKU, số lô, HSD); snapshot số phiếu nhập, PO, NCC (FK `procurement.*`, `warehouse.warehouse`); `origin_type` GOODS_RECEIPT / UNTRACED |
| `stock_item.lot_id`, `stock_movement.lot_id` (FK) | mỗi lớp tồn và mỗi dòng sổ cái thuộc 1 lô; trigger tự điền khi ứng dụng không ghi |
| `stock_movement.order_id` (FK `ordering.customer_order`), `order_number` | đơn hàng của dòng PICK / PUT_BACK / PACKAGE, kèm snapshot số đơn |
| `stock_reservation.order_number` | snapshot số đơn trên allocation (trigger `tg_stock_reservation_order_number`) |
| `inventory.item_in_use` | thêm `stock_reservation`, `stock_lot` để guard đổi SKU trả lỗi nghiệp vụ trước khi đụng FK |
| backfill | lô theo phiếu nhập từ sổ cái; còn lại UNTRACED theo định danh lớp tồn; `lot_id`/`order_id`/`order_number` của sổ cái khi xác định được duy nhất. Trigger append-only của `stock_movement` chỉ tạm tắt cho các câu backfill này |

## 3. `V20261019001000__inventory_cycle_count_counter_grant.sql` (X-SHORT / F-CNT)

Cấp `inventory-cycle-counts:VIEW_PAGE`, `READ` cho `WAREHOUSE_STAFF` (docs 04c F-CNT: NV kho là người đếm); seed
`V20260903000100` chỉ cấp cho QL kho và Inventory Planner nên NV kho bị 403 ở danh sách phiếu kiểm kê mù. Tăng
`app_role.version` của WAREHOUSE_STAFF trong cùng migration (ADR-0008). Không đổi bảng nào.

## 4. Đã kiểm tra

- Full `mvn test` (Testcontainers) trên nhánh đã rebase: 1.978 test, không lỗi code; `SkuReservationLotMigrationUpgradeTest` nâng cấp từ
  `20261019000700` có dữ liệu cũ.
- Áp 135 migration + `db/pending/C0` lên DB trống; bộ QA `tools/db/qa/run.sh` 01–15 (thêm
  `15_sku_reservation_and_lots.sql`) không FAIL.
- `schema.dbml` sinh lại bằng `tools/db/gen_dbml.py`.

## 5. Cần Tú quyết

1. Đồng ý giữ chung bảng `stock_reservation` cho cả SKU reservation và allocation (không tách bảng
   `stock_allocation`) để mọi FK hiện có tới `stock_reservation(id)` giữ nguyên.
2. Đồng ý FK chéo schema mới: `stock_lot` → `procurement.*`, `warehouse.warehouse`; `stock_movement.order_id` →
   `ordering.customer_order`.
3. Đồng ý trigger snapshot `order_number` đọc `ordering.customer_order` (đọc trong DB, code không join chéo schema).
4. Đồng ý cấp quyền xem phiếu kiểm kê (`inventory-cycle-counts:VIEW_PAGE`, `READ`) cho `WAREHOUSE_STAFF`.
