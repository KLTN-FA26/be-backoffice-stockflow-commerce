# WF3 — Đối chiếu docs ↔ database và thiết kế đề xuất

Ngày 2026-11-11. Phạm vi: luồng **WF3: Thiếu tồn → Đơn sản xuất → Duyệt mẫu in → Nhập kho → Cất hàng**
(Nguyễn Hoài Phương).

- **Docs:** repo `kltn-docs`, commit `601320b` (2026-11-08). Đã đọc 03, 05 toàn bộ; 17 phần §3, §4.1–4.7, §4.12–4.13,
  §5.3, §5.5, §6; 06 phần mô hình, §4.4–4.6, §5, BR; 20 phần §4.3–4.9, §5, BR; 11 §3–6; 01 phần quy cách thùng; 16 phần thẻ
  hành động.
- **Database:** `schema.dbml` trên `origin/develop` `df34c338` (sinh từ database thật ngày 2026-11-11), cùng migration và
  code Java trên nhánh đó.
- **Kết quả:** [`wf3-don-san-xuat-nhap-kho-cat-hang.dbml`](wf3-don-san-xuat-nhap-kho-cat-hang.dbml) (thiết kế đề xuất) và
  [`wf3-giai-thich-bang.md`](wf3-giai-thich-bang.md) (giải thích từng bảng, từng cột).

Phần "code trên develop" dưới đây dựa trên đọc code và báo cáo tiến độ rescope; chưa chạy thử ứng dụng.

## 1. Kết luận

1. **Phần Đơn SX, mẫu in, nhập kho theo thùng đã khớp docs.** Các bảng `production_order*`, `producer`, `delay_alert`,
   `delivery_batch*`, `goods_receipts*` giữ nguyên, chỉ dọn enum.
2. **Năm chỗ docs yêu cầu mà database chưa có**, trong đó ba chỗ chặn thẳng kịch bản demo:
   - lượt cất (bước 10–12, X07, R10–R13);
   - Đơn SX bù (X05, R08);
   - bảng sức chứa kho (bước 1, bước 3, X08);
   - thẻ duyệt mẫu in trong chat (bước 6);
   - lịch sử đổi SKU của dòng đơn (X02).
3. **Tồn kho vẫn là mô hình nhiều đơn vị và đếm theo cái**, trong khi docs đã chốt thùng là đơn vị duy nhất. Đề xuất: quy
   cách thùng nằm trên SKU, gộp `stock_item` vào `stock_package`, mọi số lượng đếm bằng thùng.
4. **Còn nhiều phần thừa của tính năng đã bỏ** (giá trị enum, cột, bảng không còn code dùng). Nên dọn cùng lúc squash
   migration về một baseline (PR #313 đang mở).

## 2. Kịch bản từng bước: docs yêu cầu gì, database có gì

Ký hiệu: ✅ đủ · ⚠️ thiếu một phần · ❌ chưa có.

### Luồng chính

| Bước | Docs | Database / code trên develop | |
|---|---|---|---|
| 1. Sale chọn kho xuất | 17 §4.2 bước 2, BR-22; 06 §4.6 | `customer_order.shipping_warehouse_id` và tồn khả dụng theo kho đã có. **Còn trống dự kiến (m³) chưa tính**: code trả "chưa tính" vì chưa có bảng sức chứa | ⚠️ |
| 2. Giữ phần có sẵn | 17 §4.2 bước 3, BR-21; 20 §5.6 | `stock_reservation` giữ theo SKU tại kho xuất, `delivery_batch_hold` tính cho đợt sớm nhất | ✅ |
| 3. Lập Đơn SX | 17 §4.3 bước 6, BR-05, BR-25, BR-26 | `production_order` + `_line`, cờ mẫu mặc định. **Cảnh báo sức chứa chưa có** | ⚠️ |
| 4. Gửi duyệt, duyệt | 17 bước 7, BR-23 | Người duyệt ≠ người lập, `production_order_slip`, email qua `delivery_log`, đơn → `AWAITING_STOCK`, báo Quản lý kho. **`WARN` khi còn trống chuyển sang âm chưa có** | ⚠️ |
| 5. Đơn vị SX nhận đơn | 17 bước 8 | `expected_completion_date`, trạng thái `ACCEPTED` | ✅ |
| 6. Tải mẫu, gửi thẻ duyệt | 17 bước 9; 16 bước 11, BR-10 | `production_order_sample_round` + `_sample_media`. Khách duyệt được ở "Mẫu thiết kế của tôi". **`chat.message` chỉ có chữ, không có thẻ hành động** | ⚠️ |
| 7. Khách duyệt mẫu | 17 bước 9–10 | Vòng mẫu `APPROVED`, Đơn SX → `IN_PRODUCTION` | ✅ |
| 8. Tạo phiếu nhập, kiểm đếm | 03 §4.1, BR-03 | `goods_receipts` loại `PRODUCTION`, mỗi thùng một `stock_package` (`RECEIVING`), lô = mã Đơn SX | ✅ |
| 9. Xác nhận phiếu | 03 bước 6–7 | Tồn `INBOUND`, Đơn SX → `RECEIVED`, phiếu → `IN_PUTAWAY`, mỗi thùng một `putaway_task` | ✅ |
| 10. Mở lượt cất, quét thùng lên xe | 05 §4.5 bước 12, BR-15 | **Không có bảng lượt cất** | ❌ |
| 11. Bắt đầu đi cất | 05 §4.2, §4.5 bước 13–14, BR-16, BR-17 | Xếp hạng bin 2026-11-08 đã có (`PutawayRanker`). **Gán bin cả xe, giữ chỗ, lộ trình chưa có** | ❌ |
| 12. Cất tại từng điểm dừng | 05 §4.5 bước 15–17 | Cất từng thùng (quét LPN + bin) đã có; phiếu `CLOSED`, đơn → `READY_TO_FULFILL` đã có. **Trạng thái lượt chưa có** | ⚠️ |

### Exception và ràng buộc

| Mã | Database / code trên develop | |
|---|---|---|
| X01 In lại mẫu | `sample_round.reprint_reason`, `sales_contract.free_sample_rounds` | ✅ |
| X02 Khách sửa thiết kế | Bản sửa (`design_draft`), SKU mới, huỷ Đơn SX đã có. **Đổi SKU của dòng đơn và lịch sử đổi chưa có** | ⚠️ |
| X03 Giao thiếu | `PARTIALLY_RECEIVED` → `CLOSED`, `order_partial_shipment_decision`, `installment_reduction` | ✅ |
| X04 Sản xuất trễ | `delay_alert` (4 loại), `delivery_batch_window_change` kèm `evidence_message_id` | ✅ |
| X05 Hàng lỗi → Đơn SX bù | Nhả giữ, cách ly, ghi giảm có duyệt đã có. **Đơn SX bù chưa có** (không có loại, không tham chiếu đơn gốc) | ⚠️ |
| X06 Thùng lỗi là hàng dư | Chỉ ghi giảm | ✅ |
| X07 Bỏ dở lượt cất | Phụ thuộc lượt cất | ❌ |
| X08 Kho sắp đầy | Khu `OVERFLOW` và di chuyển nguyên thùng đã có. **Cảnh báo thể tích chưa có** | ⚠️ |
| R01–R07, R09, R14 | Đã có (theo code trên develop) | ✅ |
| R08 Đơn SX bù vượt số thùng ghi giảm | Phụ thuộc Đơn SX bù | ❌ |
| R10–R13 Giữ chỗ bin, một lượt mở / người | Phụ thuộc lượt cất | ❌ |

## 3. Thiết kế đề xuất

Mọi thay đổi dưới đây đã áp vào file DBML. Trong file, cột khác database có note `đề xuất: …`; bảng mới có Note
`ĐỀ XUẤT (bảng mới)`.

### A. Thêm: docs yêu cầu, database chưa có

**A1. Lượt cất** (05 §4.5, BR-15, BR-16, BR-17)

- `warehouse.putaway_trip`: `warehouse_id`, `operator_id`, `status` (`LOADING` → `IN_PROGRESS` → `COMPLETED` / `CLOSED`),
  `start_location_id`, `started_at`, `last_activity_at`, `closed_at`, `closed_by`.
- `warehouse.putaway_trip_item`: `trip_id`, `putaway_task_id`, `status` (`ON_CART` / `PLACED` / `RETURNED`),
  `assigned_location_id`, `stop_sequence`, `hold_released_at`, `loaded_at`, `placed_at`.
- `warehouse.warehouse.putaway_hold_minutes` (mặc định 30): thời hạn giữ chỗ, cấu hình theo kho (C46).
- Khác với danh sách "dữ liệu tối thiểu" của docs 05 ở ba điểm:
  - Không lưu `holdVolumeM3`, `holdWeightKg`. Thể tích và khối lượng giữ chỗ chính là quy cách thùng của SKU, tính được
    bất cứ lúc nào.
  - Không lưu `stockPackageId` trên dòng lượt. Nhiệm vụ cất đã trỏ tới thùng.
  - Thêm `status` trên dòng lượt. Nhờ đó ràng buộc "một nhiệm vụ thuộc tối đa một lượt mở" viết được bằng unique index,
    và X07 ghi lại được thùng nào đã cất, thùng nào trả về.
- `putaway_task` đổi theo: thêm `warehouse_id` (thùng gỡ cách ly không có phiếu nhập, mà lượt chỉ nhận thùng cùng kho),
  `completed_at`, `completed_by`; `stock_package_id` bắt buộc; đổi tên `target_location_id` thành `placed_location_id`
  (đó là bin đã quét thực tế, bin gợi ý không lưu); bỏ `quantity` và trạng thái `IN_PROGRESS` (docs chỉ có `Pending` /
  `Done` / `Cancelled`; thùng đang trên xe thể hiện bằng dòng lượt).

**A2. Đơn SX bù** (17 §4.13, BR-31)

- `ordering.production_order.kind` (`STANDARD` / `REPLACEMENT`) và `origin_production_order_id`.
- Bảng `ordering.production_order_damage_adjustment` (`production_order_id`, `stock_adjustment_id` duy nhất).
- Docs viết "tham chiếu phiếu điều chỉnh ghi giảm" như một phiếu. Nhưng mỗi phiếu điều chỉnh là đúng một thùng (20 §4.3,
  BR-18), nên bù 2 thùng là tham chiếu 2 phiếu: cần bảng nối, không phải một cột. Unique trên `stock_adjustment_id` làm
  cho mỗi thùng lỗi chỉ được bù một lần, và R08 ("số thùng ≤ số đã ghi giảm") thành phép đếm số dòng.

**A3. Bảng sức chứa kho** (06 §4.6, BR-19, BR-20; 17 BR-25)

- `warehouse.warehouse.utilization_pct` (mặc định 100, trong (0, 100]).
- `warehouse.area.max_stack_height`: bắt buộc với khu chứa hàng; thể tích khu = rộng × dài × chiều cao này.
- `warehouse.shelf_level.usable_height` thành NOT NULL. Hiện là CHECK `NOT VALID` vì dữ liệu cũ; dựng lại database từ
  baseline thì không còn dữ liệu cũ.
- Không thêm bảng nào: sức chứa, đang chiếm, sắp về, chờ duyệt đều tính khi đọc (06 BR-20).

**A4. Thẻ duyệt mẫu in trong chat** (16 bước 11, BR-10)

- `chat.message.kind` (`TEXT` / `ACTION_CARD`), `card_type`, `card_reference_id` (id vòng mẫu).
- Thẻ chỉ trỏ tới chứng từ; trạng thái đọc từ vòng mẫu, không chép sang chat (16 BR-03).

**A5. Lịch sử đổi SKU của dòng đơn** (17 §4.7 bước 25)

- Bảng `ordering.order_line_sku_change`: dòng đơn, SKU cũ / mới, snapshot cũ / mới, người, thời điểm.

### B. Mô hình thùng

Docs: mỗi SKU riêng có đúng một quy cách thùng (01 BR-03, A11); mọi thùng đúng số cái chuẩn (03 BR-13); mọi tồn nằm trong
thùng LPN, không mở thùng (20 BR-14); "thùng không tự mang số lượng", "một thùng chỉ ở một vị trí" (20 BR-18).

**B1. Quy cách thùng nằm trên `inventory.inventory_items`**

- Thêm `units_per_carton`, `carton_length_cm`, `carton_width_cm`, `carton_height_cm`, `carton_weight_kg`. Nullable, có
  CHECK bắt buộc với SKU riêng (quy cách mẫu có thể chưa khai).
- Bỏ `inventory.uom_conversions`, `product.variant_sales_units`, mọi cột `uom_conversion_id`, và trên `inventory_items`:
  `unit_of_measure`, kích thước một cái, `package_*`, `package_count`, `pack_size`.
- Không lưu thể tích thùng: D × R × C tính khi dùng.

**B2. Gộp `inventory.stock_item` vào `inventory.stock_package`**

- Hai bảng hiện là 1:1 (`uk_stock_item_package`: mỗi thùng một dòng tồn) và cùng lưu vị trí của thùng. Phải có trigger và
  CHECK để hai bên không lệch nhau.
- Thùng tự mang `lot_id`, `location_code`, `stock_status` (`INBOUND` / `AVAILABLE` / `QUARANTINE`), `received_at`.
- `on_hand` và `reserved` không cần nữa: thùng `IN_STOCK` là có hàng; thùng bị giữ là thùng có khoản phân bổ `HELD`.
- `stock_reservation.stock_item_id` thành `stock_package_id`. Điều này khớp nguyên văn docs: "mỗi thùng một khoản".
- `stock_status` không suy ra được từ vị trí (thùng gỡ cách ly vẫn `Quarantine` tới khi cất xong; thùng ở `OVERFLOW` là
  `Available`), nên phải lưu.

**B3. Số lượng đếm bằng thùng**

- `stock_reservation.cartons`, `goods_receipt_lines.received_cartons`, `order_line.cartons` (bỏ `quantity` theo cái).
- `stock_movement`: mỗi dòng sổ là một thùng (`stock_package_id` bắt buộc), bỏ `quantity` và cặp `from_/to_stock_package_id`.
- `stock_adjustment`: `direction` (`DECREASE` / `INCREASE`) thay `quantity_delta`; `stock_package_id` bắt buộc.

**B4. Bỏ cột chép lại thứ đã có trong cùng module**

| Cột bỏ | Lấy từ đâu |
|---|---|
| `stock_adjustment.sku`, `lot_number` | Thùng của phiếu (`stock_package_id` nay bắt buộc) |
| `stock_movement.lot_number` | `lot_id` ngay trên cùng dòng |
| `stock_lot.production_order_code` | Luôn bằng `lot_number` (database đã buộc bằng `ck_stock_lot_production_order`) |
| `production_order_line.total_units` | `cartons × units_per_carton` |
| `delivery_batch_hold.order_id` | Dòng đơn (`order_line_id`) |
| `delivery_batch_window_change.order_id` | Đợt giao (đợt giao → hợp đồng → đơn) |
| `production_order_receipt.opened_at`, `settled_at` | Chỉ được ghi, không code nào đọc; thời điểm thật nằm trên `goods_receipts` |
| Bảng `ordering.order_line_reservation` | `delivery_batch_hold` đã có đủ khoản giữ ↔ dòng đơn. Hiện mọi khoản giữ đều được ghi vào cả hai bảng cùng lúc (`ShippingWarehouseService`) |

`stock_lot.lot_number` thành NOT NULL, duy nhất theo `(sku, lot_number)`: mọi tồn đều mang lô (20 BR-22).

### C. Bỏ: phần thừa so với docs

| Chỗ | Database đang có | Docs |
|---|---|---|
| `stock_movement.reference_type` | 16 giá trị, trong đó 8 giá trị code không còn ghi (`QC_INSPECTION`, `TRANSFER_ORDER_LINE`, `PRODUCTION_ORDER`, `EXPIRY_SWEEP`, `DAMAGE_REPORT`…) | Giữ 8 giá trị code đang ghi. `MOVE_TASK` nay là lần di chuyển thủ công (`reference_id` = mã yêu cầu), không còn bảng nhiệm vụ |
| `stock_adjustment.received_at`, `reservation_id` | Dấu vết của tồn lẻ và của lý do `SCRAP` | Cả hai tính năng đã bỏ |
| `stock_lot.goods_receipt_id`, `goods_receipt_number` | Lô theo phiếu mua hàng; với lô của Đơn SX database đã buộc hai cột này trống | Lô = mã Đơn SX, nhận qua nhiều phiếu (03 BR-03) |
| `area.type` | Thêm `PRODUCTION` | 5 loại khu chứa hàng + `NON_STORAGE` (06 §5) |
| `stock_movement.movement_type` | Thêm `PUTAWAY`, `TRANSFER_OUT`, `TRANSFER_IN`, `COUNT_CORRECTION`, `RETURN_IN`, `SCRAP`, `CONSUMPTION`, `SUPPLIER_RETURN` | 6 loại (20 BR-16). Cất hàng ghi `MOVE` |
| `stock_adjustment.reason_code` | Thêm `EXPIRED`, `SCRAP`, `SAMPLE`, `SUBCONTRACT_LOSS` | 6 lý do (20 BR-04) |
| `stock_adjustment.status` | Thêm `APPROVED` | Duyệt là ghi tồn ngay, không có "đã duyệt chưa ghi" (20 BR-05) |
| `stock_reservation` | Trạng thái `EXPIRED`, cột `expires_at` | Khoản giữ không có hạn (20 §5.6) |
| `stock_item.status` | Thêm `BLOCKED`, `DAMAGED`, `EXPIRED`, `IN_PRODUCTION` | 3 trạng thái (20 §5.1) |
| `stock_lot.origin_type` | Thêm `GOODS_RECEIPT` | Lô = mã Đơn SX, hoặc `UNTRACED` (20 BR-22) |
| `goods_receipts.status` | Thêm `CONFIRMED` (và `PENDING_APPROVAL` trong code) | Xác nhận là một bước, không phải trạng thái (03 §5) |
| `goods_receipts.receipt_type` | Thêm `PURCHASE` | `PRODUCTION`, `RETURN` (03 §1) |
| `putaway_task.status` | Thêm `IN_PROGRESS` | `Pending`, `Done`, `Cancelled` (05 §5) |
| `storage_location.kind` | Thêm `SUBCONTRACTOR` | `BIN`, `AREA` (06) |
| `warehouse.move_task` | Còn bảng, không file Java nào dùng | Di chuyển thủ công ghi thẳng sổ `MOVE`, không có nhiệm vụ (11 §1) |
| `warehouse.slotting_rule` | Còn bảng và entity, không có nghiệp vụ dùng | Gợi ý bin là quy tắc cố định (05 §4.2) |

### D. Giữ nguyên, và vì sao

- **Thể tích bin, khu, thùng và cả bảng sức chứa không lưu.** Docs 06 liệt kê `capacity_volume_m3` nhưng ghi rõ là "giá
  trị tính ra"; migration 5-4 đã chọn tính khi đọc. Giữ cách này.
- **`goods_receipt_lines.received_cartons` và `production_order_line.received_cartons` vẫn lưu**, dù đếm lại được từ
  thùng. Phiếu nhập, Đơn SX và thùng nằm ở ba module khác nhau, và quy tắc là không JOIN chéo schema.
- **`delivery_batch` không có cột trạng thái.** Trạng thái đợt là giá trị tính ra (17 §5.5).
- **Bin gợi ý không lưu** (05 §3). Chỉ bin gán cho lượt cất mới lưu.
- **`order_line.units_per_carton` và `production_order_line.units_per_carton` vẫn lưu**: số chốt trên chứng từ lúc lập
  (14 BR-08), để tính tiền và in phiếu.
- **`stock_movement.sku` và `lot_id` vẫn lưu**, dù lấy được từ thùng. Sổ cái không sửa, SKU và lô của một thùng không bao
  giờ đổi, nên hai cột này không thể lệch; chúng là khoá để tra sổ theo SKU và truy xuất mọi dòng sổ của một lô.
- **Cột chép từ module khác vẫn lưu**: `putaway_task.sku` và `goods_receipt_id`, `goods_receipt_lines.lot_number`,
  `stock_reservation.order_number`, `production_order.origin_production_order_id`. Module này không JOIN được sang
  schema của module kia.
- **Giữ hàng theo SKU và phân bổ theo thùng vẫn chung một bảng `stock_reservation`.** Docs 20 §5.6 mô tả chúng là một
  vòng đời; tách hai bảng sẽ buộc `delivery_batch_hold` trỏ tới hai nơi.
- **`stock_adjustment.location_code` vẫn lưu**: với nạp lại thùng đã mất, đây là vị trí tìm thấy thùng.

### E. Bảng `ordering.customer_order`: từ 61 cột còn 24

Rà ngày 2026-11-13, đọc entity, aggregate `Order` và docs 14, 17 §4.8–4.9, §5.1. Bảng này lớn vì nó gom dữ liệu của
nhiều đời thiết kế: đặt hàng bán lẻ (đã bỏ), sổ tiền trên đơn (đã bỏ), và các mốc được chép lên đơn cho tiện đọc. Đề
xuất: đơn chỉ giữ dữ liệu của riêng nó.

**Giữ 24 cột:**

| Nhóm | Cột |
|---|---|
| Định danh | `order_number`, `customer_id` (thành NOT NULL), `request_id`, `status`, `currency`, `placed_at` |
| Địa chỉ giao | 10 cột `shipping_*`. Là ảnh chụp từ sổ địa chỉ của khách, đổi được tới khi dán nhãn thùng đầu tiên (17 BR-16). `shipping_recipient_name`, `shipping_phone`, `shipping_line1` thành NOT NULL |
| Kho xuất | `shipping_warehouse_id`, `shipping_warehouse_chosen_at`, `shipping_warehouse_chosen_by` |
| Huỷ, treo | `cancellation_reason_code`, `status_before_hold` |
| Mốc do kho báo về | `fulfilment_started_at`, `first_labeled_at`, `goods_left_at` |

**Bỏ 37 cột, chia theo lý do:**

| Lý do | Cột | Dữ liệu nằm ở đâu |
|---|---|---|
| Của tính năng đã bỏ | `contact_name`, `contact_email`, `contact_phone` (khách vãng lai); 10 cột `billing_*` (docs 14 không có địa chỉ xuất hoá đơn trên đơn); `cancellation_retained_amount` (code luôn ghi 0); `packed_at` (mốc khoá huỷ cũ, nay là `first_labeled_at`) | Người nhận trên địa chỉ giao; bên mua trên `sales_contract` |
| Trùng với bảng khác cùng schema | `warehouse_id`, `released_at`, `released_by` | `shipping_warehouse_id`, `delivery_batch_release` |
| | `hold_reason`, `hold_note` | Dòng `order_hold` đang mở |
| | `shipped_at`, `delivered_at`, `completed_at`, `refunded_at`, `closed_at`, `cancellation_reason` | `order_status_history` |
| | `delivery_failure_reason` | `order_shipment.failure_reason` |
| | `contract_signed_at` | `sales_contract.signed_at` (và `placed_at`) |
| | `printing_started_at` | `production_order.production_started_at` sớm nhất của đơn |
| | `allow_partial_shipment` | Dòng mới nhất của `order_partial_shipment_decision` |
| Trùng với module khác | `cancellation_fee_amount`, `cancellation_fee_reason`, `cancellation_fee_detail` | `payment.order_settlement`. Đơn chỉ lưu số tiền thoả thuận mà không lưu phần trăm, nên bản trên đơn vốn đã thiếu |
| Tính được | `total_amount`, `tax_amount`, `net_amount`, `closed_amount`; `order_line.tax_amount` | Dòng đơn và dòng phí |

Dọn enum theo docs:

- `status`: bỏ `IN_PRODUCTION` (17 §5.1 có 20 trạng thái; code ghi rõ không đơn nào vào trạng thái này nữa).
- `cancellation_reason_code`: bỏ `PAYMENT_FAILED`, `REPLACEMENT_WITHDRAWN` (17 §4.8 có 8 mã).

**Ba điểm cần cân nhắc trước khi làm:**

1. **Tiền của đơn.** Code hiện giữ `total_amount` có chủ ý: comment trên entity ghi "thừa, giữ để màn hình danh sách và
   báo cáo không phải cộng các dòng". Bỏ thì danh sách đơn phải cộng dòng khi đọc (hoặc đọc qua một view). Dữ liệu đồ
   án nhỏ nên tôi vẫn đề xuất bỏ cho nhất quán với nguyên tắc không lưu thứ tính được; nếu nhóm ưu tiên tốc độ danh
   sách thì đây là cột duy nhất trong 37 cột đáng giữ lại.
2. **Mốc thời gian theo trạng thái.** Tác vụ chạy đêm (tự hoàn tất sau 7 ngày, tự đóng sau 30 ngày) đang lọc theo
   `delivered_at`, `completed_at`. Chuyển sang lọc trên `order_status_history` thì cần index `(to_status, occurred_at)`.
3. **Đây là bảng dùng chung của cả năm luồng.** Aggregate `Order` dài khoảng 1.900 dòng và `OrderResponse` đang trả các
   trường này cho giao diện. API vẫn có thể trả đủ trường (tính khi đọc), nhưng phần code phải sửa nằm ở WF2 (Minh),
   WF4 (Tú), WF5 (Thái). Không nên làm trước buổi demo.

## 4. Ràng buộc đi kèm thiết kế mới

Mỗi bất biến nói hai lần: trong aggregate và trong database. "Đã có" nghĩa là database trên develop đã có ràng buộc đó.

| Bảng | Ràng buộc |
|---|---|
| `putaway_trip` | Unique `operator_id` khi `status IN (LOADING, IN_PROGRESS)` (05 BR-15) |
| `putaway_trip_item` | Unique `putaway_task_id` khi `status = ON_CART` (05 BR-15); `assigned_location_id` bắt buộc khi lượt `IN_PROGRESS` |
| `putaway_task` | Đã có: unique `stock_package_id` khi nhiệm vụ còn mở (`uk_putaway_task_open_package`) |
| `production_order` | `kind = REPLACEMENT` ⇔ `origin_production_order_id IS NOT NULL`. Đã có: người duyệt ≠ người lập (`ck_production_order_approval`) |
| `stock_adjustment` | Đã có: người duyệt ≠ người đề nghị (`ck_stock_adjustment_four_eyes`), một phiếu chờ duyệt cho mỗi thùng (`uk_stock_adjustment_pending_package`) |
| `stock_lot` | Unique `(sku, lot_number)`. Đã có: lô của Đơn SX có `lot_number` = mã Đơn SX (`ck_stock_lot_production_order`, viết lại không còn `production_order_code`) |
| `delivery_batch_hold` | Dòng đơn phải thuộc đơn của hợp đồng có đợt giao đó |
| `production_order_damage_adjustment` | Unique `stock_adjustment_id`; phiếu phải `POSTED`, lý do `DAMAGED`, lô thuộc Đơn SX gốc (kiểm ở service) |
| `stock_package` | `stock_status`, `lot_id`, `received_at` bắt buộc khi `status = IN_STOCK` và trống khi `RECEIVING` / `VOID` |
| `stock_reservation` | `stock_package_id IS NOT NULL` ⇒ `cartons = 1`; unique `stock_package_id` khi `status = HELD` (một thùng một khoản) |
| `inventory_items` | Năm cột quy cách thùng cùng có hoặc cùng trống; bắt buộc với SKU riêng; không sửa khi SKU đã có chứng từ hoặc tồn |
| `warehouse` | `0 < utilization_pct <= 100`; `putaway_hold_minutes > 0` |
| `area` | `max_stack_height` bắt buộc khi `type <> NON_STORAGE` |
| `chat.message` | `kind = ACTION_CARD` ⇔ có `card_type` và `card_reference_id` |

## 5. Những chỗ các bản trước của tôi sai

Lần rà thứ hai (2026-11-13) đối chiếu lại từng giả định với code và migration trên develop:

- **`stock_lot.goods_receipt_id` tôi giải thích sai** là "phiếu nhập đầu tiên tạo ra lô". Thực tế database buộc cột này
  trống với lô của Đơn SX; nó chỉ dùng cho lô theo phiếu mua hàng đã bỏ. Đã đề xuất bỏ cột.
- **`order_line_reservation` và `delivery_batch_hold` trùng nhau** mà bản trước giữ cả hai. Đã gộp về một bảng.
- **`stock_movement.reference_type` bản trước để nguyên 16 giá trị** trong khi các enum khác đã được dọn. Đã đếm lại
  trong code: chỉ 8 giá trị còn được ghi.
- **`delivery_batch.window_start` / `window_end` tôi giải thích sai** là "cửa sổ hiệu lực, đổi khi dời lịch". Thực tế hai
  cột này là cửa sổ đã ký, không bao giờ đổi; cửa sổ hiệu lực là dòng mới nhất của `delivery_batch_window_change`.
- **Bản trước thiếu `design.design_artifact`**, nơi lưu file in PDF/X gửi kèm Đơn SX ở bước 4. Đã thêm.
- **`production_order_line.total_units` là cột tính sẵn** mà bản trước giữ, trong khi `carton_volume_m3` cùng loại đã bỏ.
  Đã bỏ cho nhất quán.
- Đã kiểm lại: commit `dcb0c444` sửa migration sau lần sinh `schema.dbml` chỉ đổi dữ liệu, không đổi cấu trúc, nên
  `schema.dbml` vẫn đúng với database.

Ba chỗ sai tìm ra ở lần rà đầu:

- `procurement.goods_receipt_return_cartons` là thùng của **phiếu hàng hoàn** (WF5), không phải thùng lệch số chuẩn. Docs
  03 nói thùng lệch chuẩn "không liệt kê, không nhận", tức không lưu gì (R06). Đã bỏ khỏi WF3, và bỏ luôn cột
  `counted_units` tôi đề xuất trước đó.
- `warehouse.move_task` không còn code nào dùng. X08 (dời thùng từ `OVERFLOW` lên bin) là di chuyển thủ công, ghi một dòng
  sổ `MOVE`. Đã bỏ khỏi WF3.
- `procurement.goods_receipt_photos` không có trong docs 03 cho phiếu theo Đơn SX. Đã bỏ khỏi WF3.

## 6. Thứ tự làm và ai cần biết

| Ưu tiên | Việc | Vì sao | Ảnh hưởng |
|---|---|---|---|
| 1 | A1 lượt cất | Chặn bước 10–12, X07, R10–R13. Là điểm nhấn với hội đồng | Chỉ thêm bảng; cất từng thùng không đổi |
| 2 | A2 Đơn SX bù | Chặn X05, R08 | Thêm 2 cột, 1 bảng |
| 3 | A3 sức chứa | Bước 1, bước 3, X08 đang trả "chưa tính" | Thêm 2 cột; phần tính nằm ở code |
| 4 | A4 thẻ chat, A5 đổi SKU | Bước 6 và X02 bước 3 | Chat chưa ai phụ trách (QD-18) |
| 5 | B1 quy cách thùng | Bỏ mô hình nhiều đơn vị | WF1 (Hưng): kiểm quy cách thùng khi gửi duyệt thiết kế. WF2 (Minh): `quote_line.uom_conversion_id`. WF4 (Tú): `PackagingSpecResolver` |
| 6 | B2, B3 gộp tồn, đếm theo thùng | Đúng docs nhất, nhưng đụng lõi module `inventory` | WF4 (Tú): phân bổ, lấy hàng, kiểm kê đang đọc `stock_item` |
| 7 | B4 bỏ cột chép lại, C dọn enum và bảng chết | Không chặn demo | Làm cùng PR #313. Gộp `order_line_reservation` đụng aggregate `Order` (WF2, WF4) |
| 8 | E rút gọn `customer_order` | Không chặn demo; thay đổi rộng nhất | Cả năm luồng dùng bảng này. Làm sau demo, cùng PR #313 |

- **Schema do Tú quản lý.** Toàn bộ mục 3 là đề xuất để bàn với Tú; chưa có migration nào được viết.
- Ưu tiên 1–3 chỉ thêm, không sửa cái đang chạy, nên làm được trước buổi demo. Ưu tiên 5–6 là tái cấu trúc, nên làm sau
  khi kịch bản chạy được.

## 7. Docs cần sửa cho nhất quán

| Chỗ | Đang viết | Nên sửa |
|---|---|---|
| 01 dòng 35, 68–72, 122, 156, 173, 183 | Đơn vị cơ sở, đơn vị quy đổi, đơn vị bán, kích thước một cái | Chỉ còn quy cách thùng |
| 01 BR-09, glossary dòng 36, 05 dòng 11 | "Tồn có thể ghi nội bộ theo đơn vị cơ sở (cái)" | Tồn ghi theo thùng |
| 01 dòng 327 | "Đổi đơn vị cơ sở khi đã có tồn" | "Đổi quy cách thùng khi SKU đã có chứng từ hoặc tồn: không cho đổi" |
| 05 §5 `PutawayTask` | Có `quantity` (số cái), `lot` | Bỏ: lấy từ thùng |
| 05 §5 `PutawayTripItem` | Có `stockPackageId`, `holdVolumeM3`, `holdWeightKg` | Bỏ: lấy từ nhiệm vụ và quy cách thùng; thêm trạng thái dòng |
| 17 §4.13, BR-31 | "Tham chiếu phiếu điều chỉnh ghi giảm" (một phiếu) | "Các phiếu ghi giảm" — mỗi thùng một phiếu |
| 20 §4.7 | Dòng sổ có "số lượng" | Mỗi dòng sổ là một thùng |
