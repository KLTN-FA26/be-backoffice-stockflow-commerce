# Kế hoạch thực hiện 5 đợt (BE) — 08/11

Làm tự động theo [09-removal-summary.md](09-removal-summary.md). Mỗi đợt một nhánh, nhánh sau tách từ nhánh trước. PR đợt sau lấy nhánh đợt trước làm base, nên mỗi PR chỉ chứa phần của nó. Không merge PR nào, bạn tự merge.

## Quy trình mỗi đợt

1. Tách nhánh từ đợt trước.
2. Sửa code theo thứ tự: gỡ luồng chính → xoá code → migration → seed / QA / tài liệu.
3. Kiểm tra, đủ mới commit:
   - `python -X utf8 tools/verify.py`;
   - compile, `ModularityTest`, `ArchitectureTest`;
   - test liên quan;
   - **toàn bộ `mvn verify`** (Testcontainers, DB mới + dữ liệu demo);
   - một agent review độc lập đọc diff tìm lỗi; lỗi nào đúng thì sửa rồi chạy lại.
4. Commit theo từng phần (không ghi công cụ AI), push, mở PR.
5. Ghi kết quả vào bảng tiến độ bên dưới.

Quy tắc DB (kế hoạch dọn §6): chỉ thêm migration mới, archive vào `platform.legacy_archive` rồi mới drop, viết lại hàm SQL tham chiếu bảng bị drop trong cùng migration, dọn `event_publication`, tạo lại CHECK nhiều cột, đổi quyền thì tăng `app_role.version`.

## Các đợt

| Đợt | Nhánh | Nội dung |
|---|---|---|
| 1 | `refactor/rescope-01-lock` | Tắt email khách, xoá code chết, đổi tên Sales Manager, `verify.py` |
| 2a | `refactor/rescope-02a-production` | Xoá sản xuất: release không qua `IN_PRODUCTION`, lấy hàng không bỏ qua dòng in, bỏ cổng mẫu; xoá module + phần phụ thuộc (inventory, order, procurement gia công, warehouse điểm gia công, notification, sự kiện); migration archive + drop; role `PRODUCTION_STAFF` |
| 2b | `refactor/rescope-02b-qc` | Xoá QC nhận hàng (giữ ảnh nhận hàng, bỏ ảnh QC); quyền duyệt thiết kế chuyển sang Sales Manager; role `QC_STAFF` |
| 2c | `refactor/rescope-02c-transfer` | Xoá chuyển kho (fulfillment trước, rồi inventory) |
| 2d | `refactor/rescope-02d-parcel` | Xoá gửi kiện + COD + chọn cách giao; mọi đơn giao nguyên chuyến |
| 2e | `refactor/rescope-02e-packing` | Bỏ kiểm checksum thiết kế khi đóng gói (và tải file thiết kế ở kho, treo `DESIGN_MISMATCH`). **Dựng lại "kiểm thùng & dán nhãn" (08) chuyển sang đợt 4**, sau khi chuyển tồn vào thùng và bỏ lấy lẻ (lý do: ghi chú đợt 2e) |
| 2f | `refactor/rescope-02f-design` | Thiết kế cũ, theo kế hoạch Design Studio |
| 3 | `feature/ahamove` | Tích hợp Ahamove (mục H) |
| 4 | nhiều nhánh | Xây phần thay thế rồi xoá: Đơn sản xuất → PO; hợp đồng + đợt thanh toán → sổ cái tiền; chuyển tồn vào thùng → lấy lẻ, rồi dựng lại trạm "kiểm thùng & dán nhãn" (08) thay trạm đóng gói lại; phiếu nhập hàng hoàn → đổi trả cũ |
| 5 | `refactor/rescope-05-rest` | Phần còn lại: cảnh báo tồn, hạn dùng / FEFO, bề mặt khách tự đặt, giá trên sàn, lớp lưu trữ, chính sách tồn, báo hỏng từng cái, role còn lại, seed / tài liệu |

FE làm sau khi BE xong.

## Tiến độ

| Đợt | Trạng thái | Test | PR |
|---|---|---|---|
| 1 | ✅ Đã push | 5.468 qua, 0 lỗi | #257 |
| 2a | ✅ Đã push | 5.212 chạy, 1 test đếm query chập chờn (đã sửa, chạy lại xanh) | #258 |
| 2b | ✅ Đã push | 5.182 chạy, 0 lỗi | #259 |
| 2c | ✅ Đã push | 5.014 chạy, 0 lỗi; review bắt 1 lỗi migration (đã sửa) | #260 |
| 2d | ✅ Đã push | 4.926 chạy, 0 lỗi; thử migration bắt 1 lỗi, review bắt 2 lỗi (đã sửa, chạy lại 127 test liên quan xanh) | #261 |
| 2e | ✅ Đã push | 4.920 chạy, 0 lỗi; review không có lỗi nghiêm trọng | #262 |

### Ghi chú đợt 2a

- **Hoãn sang lúc bỏ PO (đợt 4):** gia công ngoài (PO loại `SUBCONTRACT`, `procurement.subcontract_settlement`, cờ nhà in gia công trên NCC) và `warehouse.subcontractor_site`. Chúng nằm trong code mua hàng, sẽ xoá cả khối cùng PO; làm riêng bây giờ là sửa khoảng 40 file rồi lại xoá. Migration 2a đã gỡ mọi khoá ngoại và hàm của chúng trỏ vào bảng sản xuất, nên chúng chạy độc lập.
- **Hoãn sang đợt 5:** sinh lại seed `tools/db/seed`. Seed hiện có đã không nạp được trên `develop` từ trước (lỗi `ck_supplier_invoices_status`), và đằng nào cũng phải dựng lại gần hết khi xong các đợt xoá.
- **Dữ liệu cũ trên VPS:** đơn đang `IN_PRODUCTION` chuyển sang `READY_TO_FULFILL`; dòng in chưa có hàng trong kho thì không có gì để lấy, Sale đóng phần còn lại hoặc huỷ đơn. Khuyến nghị vẫn là reset dữ liệu test rồi nạp seed mới.

### Ghi chú đợt 2b

- **Giữ lại có chủ đích:** loại khu `QUALITY_CONTROL` (luồng trả hàng cũ còn nhận hàng vào đó, bỏ ở đợt 4); ảnh QC đã chụp (chỉ đọc, không chụp mới); mã sổ `QC_INSPECTION` và trạng thái tồn BLOCKED/QUARANTINE (lịch sử).
- **Sửa luôn lỗ hổng từ 2a:** phiếu nhập của PO gia công trước đây đóng ngay vì sản xuất nhận hàng; sản xuất đã bỏ nên hàng bị kẹt. Giờ phiếu gia công đi cất hàng như phiếu thường.
- **Hoá đơn NCC / hiệu suất NCC:** sửa tối thiểu (tính tiền theo số nhận, chỉ số QC = 0) vì cả khối bỏ cùng PO ở đợt 4.
- **Dữ liệu cũ:** phiếu đang QC → dòng chưa kiểm coi như đạt, tạo nhiệm vụ cất bằng SQL như `PutawayTaskCreator`; phiếu có hàng QC cách ly/loại → đóng. User QC → Nhân viên kho (trừ người đã là Quản lý kho), phải đăng nhập lại. Bản thiết kế đang chờ người duyệt là QC cần Sales Manager gán lại người duyệt.

### Ghi chú đợt 2c

- **Bỏ hết, không chặn:** mọi phiếu chuyển kho (kể cả đang soạn / đang lấy / đang đi đường) cùng toàn bộ phần đi kèm — giữ hàng ở kho nguồn, phiên đóng gói + kiện + dòng + lượt quét, kế hoạch xếp xe TRANSFER + xe + chuyến, nhiệm vụ cất hàng từ dòng chuyển, ước tính xe, dãy số `TO` — được lưu nguyên vào `platform.legacy_archive` rồi xoá. Hàng đã xuất đi thì đã ra khỏi tồn từ lúc xuất, không có gì để hoàn.
- **Giữ lại có chủ đích:** cột `source_type` của kế hoạch xếp xe / ước tính xe (chỉ còn `ORDER`, để FE không phải đổi hợp đồng); loại sổ `TRANSFER_OUT` / `TRANSFER_ORDER_LINE` và các CHECK của sổ kho (dòng lịch sử).
- **Siết lại sau khi bỏ:** `stock_reservation.order_id`, `pack.pick_id`, `package_line.order_line_id`, `package_scan.order_line_id`, `shipment_id` của các bảng xếp xe / chuyến thành NOT NULL; phiếu kiểm kê `TRANSFER_DISCREPANCY` cũ thành `MANUAL`.
- **Quyền:** `inventory-transfer-orders:*` bị xoá; Quản lý kho, Kế hoạch tồn kho, Nhân viên kho tăng `version`, nên quyền mới có hiệu lực ngay ở request kế tiếp.
- **Review độc lập bắt được 1 lỗi thật, đã sửa:** kế hoạch xếp xe chuyển kho đã tính (có `load_placement`) làm migration dừng vì `fk_load_placement_package` RESTRICT; giờ xoá placement trước. Đã tái hiện lỗi với thứ tự cũ và kiểm bản sửa trên DB có dữ liệu; test nâng cấp giờ nạp một kế hoạch đã tính.
- **Seed test** (`tools/db/seed`) vẫn chèn phiếu chuyển kho — như đã ghi ở 2a, seed đã hỏng từ trước và được sinh lại ở đợt 5.

### Ghi chú đợt 2d

- **Phạm vi:** bỏ gửi kiện (mở chuyến tay, thêm/bớt shipment trên biên bản, huỷ chuyến thường, chọn hãng cho từng shipment), bỏ chọn cách giao (đơn + fulfillment, lịch sử đổi cách giao), bỏ COD (số thu hộ trên shipment/nhãn/biên bản/đơn, chính sách `ON_DELIVERY` của đơn và điều khoản khách, hoá đơn công nợ của hãng khi giao). Mọi chuyến là xe của kế hoạch xếp xe đã xác nhận.
- **Giữ lại có chủ đích:** giao nguyên đơn (đơn không ghi dòng kiện); `carrier_id` của shipment (gán khi bàn giao theo hãng đã đặt xe); `packing_started_at` (đợt 2e dùng cho khoá khi dán nhãn); phía sổ cái tiền của hãng (`collector = CARRIER`, nộp tiền thu hộ, bảng `payment.cod_remittance*`) để trả nốt khoản hãng đã thu trước đó — bỏ cùng sổ cái ở đợt 4.
- **Dữ liệu cũ:** chuyến gửi kiện đang xếp → huỷ, shipment chờ kế hoạch xếp xe; chuyến gửi kiện đã đi/đã huỷ giữ làm lịch sử; đơn và điều khoản `ON_DELIVERY` → `BEFORE_DELIVERY`; sự kiện `ShipmentStatusChanged` đang chờ được bỏ trường `deliveryMode`.
- **Quyền:** bỏ `fulfillment-load-cancellations`, `fulfillment-dispatch:CREATE`, `sales-order-delivery-modes`; SYSTEM_ADMIN, WAREHOUSE_MANAGER, WAREHOUSE_STAFF, ORDER_COORDINATOR tăng `version`.
- **Thử migration trên dữ liệu bắt được 1 lỗi, đã sửa trước khi chạy test:** đổi `balance_policy` của đơn còn `cod_amount > 0` vi phạm `ck_order_cod_amount`; giờ đặt `cod_amount = 0` cùng câu lệnh (giá trị đã lưu trữ).
- **Review độc lập bắt 2 lỗi thật, đã sửa:** (1) đơn `ON_DELIVERY` đang trên đường giao, đổi sang `BEFORE_DELIVERY`, khi giao xong sẽ thành nợ của khách (khách đã trả tiền mặt cho hãng) — migration giờ lập sẵn công nợ CARRIER cho COD hãng còn nợ (shipment đang giao, giao nguyên đơn, và lần giao đã báo mà payment chưa ghi), nên khi giao không phát sinh nợ khách; (2) sửa `event_publication` qua jsonb làm đổi text, registry không đánh dấu hoàn tất được — giờ cắt chuỗi giữ đúng định dạng Jackson và lưu trữ bản cũ trước.

### Ghi chú đợt 2e

- **Đổi phạm vi có lý do:** tài liệu 08 coi **thùng LPN chính là kiện giao** — kho không đóng lại hàng. Điều đó chỉ đúng khi mọi thứ lấy cho đơn đều là nguyên thùng, mà lấy lẻ (tồn lẻ cũ) chỉ bỏ ở đợt 4 (chuyển tồn vào thùng). Dựng trạm "kiểm thùng & dán nhãn" bây giờ buộc phải làm bản lai cho dòng lấy lẻ rồi đập lại; nên 2e chỉ làm phần độc lập: **bỏ kiểm thiết kế ở kho**. Trạm kiểm thùng dựng ở đợt 4 ngay sau khi bỏ lấy lẻ.
- **Đã bỏ:** so checksum thiết kế khi hoàn tất đóng gói (BR-DSG-003), trạng thái kiện `ON_HOLD`, treo đơn `DESIGN_MISMATCH`, quyền `fulfillment-design-reverification` (Quản lý kho, Admin tăng `version`), tải file thiết kế ở kho, bảng `fulfillment.design_verification`; fulfillment không còn phụ thuộc `design :: api`.
- **Giữ lại có chủ đích:** dòng in của đơn (`designSnapshotId`, `printLine`) — chỉ để hiển thị cho tới khi dòng đơn nêu SKU in của khách; lịch sử treo `DESIGN_MISMATCH` đã gỡ trong `ordering.order_hold`.
- **Dữ liệu cũ:** kiện đang treo → `PACKING` (đóng gói tiếp, không kiểm thiết kế); đơn đang treo `DESIGN_MISMATCH` → về trạng thái trước khi treo, ghi lịch sử; dòng treo đang mở → lưu trữ rồi xoá (CHECK bắt buộc người gỡ, mà không ai gỡ).
- **Review độc lập:** không có lỗi nghiêm trọng; đã sửa các chú thích lỗi thời và một record không dùng. Thử migration trên dữ liệu bắt 1 lỗi trước khi chạy test (dòng treo đang mở không gỡ được vì CHECK bắt buộc người gỡ) — đổi sang lưu trữ rồi xoá.
