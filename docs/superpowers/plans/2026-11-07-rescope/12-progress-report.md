# Báo cáo tiến độ rescope BE — 08/11/2026

Rescope theo tài liệu nghiệp vụ 04–05/11: kho thành phẩm + bán B2B. In do nhà sản xuất bên ngoài làm, hàng về đã in sẵn và đóng thùng. Mọi đơn giao nguyên chuyến xe. Khách trả bằng chuyển khoản theo các đợt của hợp đồng.

Báo cáo này nói về những gì **đã làm xong** (đợt 1, 2a–2e). Phần còn lại (2f, 3, 4, 5) nằm trong kế hoạch nhóm: [11-team-plan.md](11-team-plan.md).

---

## 1. Tóm tắt

- **Xong 6 đợt:** 1, 2a, 2b, 2c, 2d, 2e. Mỗi đợt là một PR, đã push. **Chưa merge PR nào.**
- **Các PR xếp chồng**, mỗi PR lấy PR trước làm base:

  > develop ← #257 ← #258 ← #259 ← #260 ← #261 ← #262

  Phải review và merge **đúng thứ tự** từ #257. Merge xong một PR thì đổi base của PR kế tiếp sang `develop`; GitHub tự làm việc này khi nhánh base bị xoá.
- **Kích thước:** khoảng **+6.900 / −41.900 dòng**, gồm code, test, migration và tài liệu. Phần lớn là xoá: module sản xuất, QC, chuyển kho, gửi kiện, COD, kiểm thiết kế.
- **Test:** develop có 5.486 test. Sau 2e còn **4.920 test, 0 lỗi**. Số test giảm vì test của tính năng đã bỏ bị xoá theo, không có test nào bị nới lỏng.
- **Migration mới (6 file):**
  - `V20261107000100`: đổi tên role.
  - `V20261108000100` → `V20261108000500`: mỗi đợt xoá một file.
  - Migration nào cũng lưu nguyên dữ liệu bị xoá hoặc bị đổi vào `platform.legacy_archive` trước, rồi mới chuyển dữ liệu và drop.
- **Role:**
  - `ECOMMERCE_ADMIN` → `SALES_MANAGER` (giữ id và quyền).
  - Bỏ `PRODUCTION_STAFF` và `QC_STAFF`.
  - Còn `ORDER_COORDINATOR`, `INVENTORY_PLANNER`, `PROCUREMENT_STAFF`, bỏ ở đợt 5.

| Đợt | PR | Nhánh | Nội dung | Migration | Test (toàn bộ) |
|---|---|---|---|---|---|
| 1 | [#257](https://github.com/KLTN-FA26/be-backoffice-stockflow-commerce/pull/257) | `refactor/rescope-01-lock` | Tắt mail khách, xoá code chết, Sales Manager | `V20261107000100` | 5.468 qua, 0 lỗi |
| 2a | [#258](https://github.com/KLTN-FA26/be-backoffice-stockflow-commerce/pull/258) | `refactor/rescope-02a-production` | Bỏ sản xuất nội bộ | `V20261108000100` | 5.212; 1 test đếm query chập chờn → đã sửa, chạy lại xanh |
| 2b | [#259](https://github.com/KLTN-FA26/be-backoffice-stockflow-commerce/pull/259) | `refactor/rescope-02b-qc` | Bỏ QC nhận hàng | `V20261108000200` | 5.182, 0 lỗi |
| 2c | [#260](https://github.com/KLTN-FA26/be-backoffice-stockflow-commerce/pull/260) | `refactor/rescope-02c-transfer` | Bỏ chuyển kho | `V20261108000300` | 5.014, 0 lỗi |
| 2d | [#261](https://github.com/KLTN-FA26/be-backoffice-stockflow-commerce/pull/261) | `refactor/rescope-02d-parcel` | Bỏ gửi kiện, chọn cách giao, COD | `V20261108000400` | 4.926, 0 lỗi |
| 2e | [#262](https://github.com/KLTN-FA26/be-backoffice-stockflow-commerce/pull/262) | `refactor/rescope-02e-packing` | Bỏ kiểm thiết kế khi đóng gói | `V20261108000500` | 4.920, 0 lỗi |

### Mỗi đợt đều được kiểm tra như nhau

1. `python tools/verify.py`: 16 kiểm tra tĩnh, đều qua.
2. `ModularityTest`, `ArchitectureTest`, rồi **toàn bộ `mvn test`** (Testcontainers: Postgres + Redis).
3. **Test nâng cấp migration** cho mỗi migration chuyển dữ liệu (`*RemovalMigrationUpgradeTest`). Các bước:
   - dựng DB ở migration ngay trước đó;
   - nạp dữ liệu cũ "khó";
   - chạy migration;
   - kiểm từng kết quả: dữ liệu được chuyển, bản lưu trữ, bảng/cột đã drop, CHECK mới chặn dữ liệu kiểu cũ, `app_role.version` tăng đúng role.
4. Chạy thử migration trên bản sao schema có dữ liệu cài sẵn, **trước khi** viết test. Bước này bắt được lỗi ở 2d và 2e.
5. Một **review độc lập** đọc toàn bộ diff tìm lỗi. Lỗi thật đều đã sửa và ghi trong PR:
   - 2a: 2 lỗi;
   - 2b: 1 lỗi;
   - 2c: 1 lỗi;
   - 2d: 2 lỗi;
   - 2e: không có lỗi nghiêm trọng.
6. Script QA SQL (`tools/db/qa`) và `orphan_check.sql` được cập nhật theo.
7. Commit chia 3 phần: code / `feat(db)` (migration + test + QA) / tài liệu (`schema.dbml` sinh lại).

---

## 2. Chi tiết từng đợt

### Đợt 1 — Khoá phạm vi (#257)

**Đã đổi**
- Mail đơn hàng gửi khách **tắt mặc định** (`CUSTOMER_EMAIL_ENABLED=false`, tài liệu 00 §6). Mail mời vào portal và mail cho nhân viên vẫn gửi.
- `ECOMMERCE_ADMIN` → **`SALES_MANAGER`** (tài liệu 00 §2). Migration đổi tên tại chỗ, giữ id và quyền, tăng `version`, thu hồi phiên đăng nhập của người đang giữ role.
- `tools/verify.py` đọc danh sách module từ cây mã nguồn. Danh sách viết tay cũ thiếu `production`, nên 2 kiểm tra đã bỏ sót module này.

**Đã xoá (code chết)**
- Sự kiện `PaymentFailed` (không ai phát; khách trả bằng chuyển khoản, tài liệu 15) và 2 listener của nó.
- Sự kiện `DesignConfirmed`, `GoodsReceived`, `QcResult`.
- Entity/repository wave, cache `SUPPLIERS`, hàm cũ `OrderService.releaseToFulfillment`.

**FE cần sửa:** mọi chỗ so mã role `ECOMMERCE_ADMIN` → `SALES_MANAGER`.

### Đợt 2a — Bỏ sản xuất nội bộ (#258)

**Đã đổi trong luồng đơn**
- Release đơn luôn sang `READY_TO_FULFILL`, không qua `IN_PRODUCTION`. Trạng thái này vẫn có trong enum để đọc lịch sử, nhưng không đơn mới nào vào.
- Lấy hàng coi dòng in như mọi dòng khác.
- Chấp nhận báo giá không còn chờ mẫu được duyệt.
- Bỏ thoả thuận thiếu hàng sản xuất (S6).

**Chặn tạm thời:** đơn có dòng in **chưa release được** (`ORDER_PRINT_GOODS_NOT_RECEIVED`, 409).
- Lý do: dòng in vẫn trỏ vào phôi trắng, mà hàng in chỉ vào kho qua Đơn sản xuất (đợt 4). Release bây giờ sẽ lấy và giao phôi trắng.
- Chặn này được gỡ khi dòng đơn nêu SKU in của khách (đợt 2f/4).

**Đã xoá**
- Module `production` và toàn bộ endpoint `/api/v1/production/**`: dashboard, orders, material-issues, qc-results, sample-requests.
- Sự kiện `OrderLinesReleasedForProduction`, `ProductionCompleted`, `ProductionOrderHeld`, `SampleApproved`.
- Phần sản xuất trong inventory (giữ hàng sản xuất, xuất phôi, huỷ hàng đang giữ), order, notification, reporting, warehouse.
- Role `PRODUCTION_STAFF` và 13 mã lỗi.

**Giữ tương thích API**
- `OrderResponse.printLinesReady` luôn `true` (deprecated).
- `QuoteResponse.Sample` chỉ còn `NOT_REQUIRED`.

**Dữ liệu cũ trên server**
- Đơn `IN_PRODUCTION` → `READY_TO_FULFILL`, hoặc `PICKING` nếu đang có task lấy hàng. Mỗi đơn có một dòng lịch sử.
- Giữ hàng của dòng in được trả lại.
- User chỉ có role sản xuất bị vô hiệu hoá.
- Dòng `event_publication` chưa xong của các sự kiện đã bỏ bị xoá.

**FE cần sửa:** bỏ các màn sản xuất và yêu cầu mẫu; xử lý lỗi 409 khi release đơn có dòng in.

### Đợt 2b — Bỏ QC nhận hàng (#259)

**Đã đổi**
- Phiếu nhập xác nhận xong là `IN_PUTAWAY`, mọi dòng đi cất từ khu RECEIVING. Phiếu đóng khi mọi đơn vị đã nhận được cất xong.
- Phiếu nhập của PO gia công cũng đi cất như phiếu thường. Đây là lỗ hổng do 2a để lại: sản xuất từng nhận hàng của phiếu này, nên khi bỏ sản xuất hàng bị kẹt.
- **Duyệt thiết kế chuyển sang Sales Manager** (tài liệu 12): người duyệt phải có `SALES_MANAGER`, role này được cấp `design-designs:APPROVE`.

**Đã xoá**
- `QcInspection`, `QcOutcome`.
- Các endpoint `/lines/{id}/qc-transfer`, `/lines/{id}/inspection`, `/lines/{id}/qc-photos*`.
- Cờ `qcRequired`: trên mặt hàng, dòng PO, dòng phiếu nhập và yêu cầu logistics.
- Quyền `procurement-qc-tasks`, role `QC_STAFF`, 6 mã lỗi.

**Giữ có chủ đích**
- Loại khu `QUALITY_CONTROL`: hàng trả cũ còn nhận vào đó, bỏ ở 4D.
- Ảnh QC đã chụp: chỉ đọc.
- Mã sổ `QC_INSPECTION` và trạng thái BLOCKED/QUARANTINE: dữ liệu lịch sử.

**Dữ liệu cũ trên server**
- Phiếu đang QC: đơn vị chưa kiểm coi như đạt, mở task cất như `PutawayTaskCreator`.
- Phiếu có hàng bị cách ly/loại → đóng.
- User QC → Nhân viên kho, trừ người đã là Quản lý kho; phải đăng nhập lại.

**FE cần sửa:** bỏ màn QC, trạng thái `IN_QC`, field `qcRequired`; người duyệt thiết kế chọn trong danh sách Sales Manager.

### Đợt 2c — Bỏ chuyển kho (#260)

**Đã xoá**
- Phiếu chuyển kho: `/api/v1/inventory/transfer-orders/**`, api `TransferFulfillment` / `TransferDispatchGuard` / `TransferDeliveryMode`, `StockItem.reserveForTransfer`.
- Đóng gói chuyển kho: `/api/v1/fulfillment/transfer-packs/**`.
- Kiểm tra quyền kho nguồn-hoặc-đích `WarehouseScope.requireEither`.
- Quyền `inventory-transfer-orders`.

Mọi giữ hàng giờ đều thuộc một đơn.

**Đã đổi**
- Kế hoạch xếp xe, ước tính xe, chuyến chỉ nhận đơn khách.
- `sourceType` chỉ được là `ORDER` hoặc bỏ trống; giá trị khác bị từ chối (`VALIDATION_FAILED`).
- Response bỏ field `transferOrderId`.

**Giữ có chủ đích**
- Cột `source_type`, để FE không phải đổi hợp đồng.
- Loại sổ `TRANSFER_OUT` / `TRANSFER_ORDER_LINE`: dòng lịch sử.
- Di chuyển trong cùng một kho (tài liệu 11).

**Dữ liệu cũ trên server**
- Mọi phiếu chuyển kho, ở trạng thái nào, cùng phần đi kèm, đều lưu trữ rồi xoá. Phần đi kèm gồm: giữ hàng, phiên đóng gói, kiện, kế hoạch xếp xe TRANSFER, xe, chuyến, task cất, ước tính, dãy số `TO`.
- Hàng đã xuất thì đã ra khỏi tồn từ lúc xuất.
- Siết lại `NOT NULL` ở các cột mà chuyển kho từng nới.

**FE cần sửa:** bỏ màn chuyển kho và đóng gói chuyển kho; bỏ field `transferOrderId`.

### Đợt 2d — Bỏ gửi kiện, chọn cách giao, COD (#261)

**Đã xoá endpoint**
- `POST /api/v1/fulfillment/loads`: mở chuyến tay.
- `POST /loads/{id}/shipments`, `DELETE /loads/{id}/shipments/{shipmentId}`.
- `POST /loads/{id}/cancellation`.
- `PUT /fulfillment/shipments/{id}/carrier`.
- `PUT /orders/{id}/delivery-mode`, field `deliveryMode` trong request release, lịch sử đổi cách giao.

**Đã đổi**
- Một chuyến luôn là **xe của kế hoạch xếp xe đã xác nhận**: đặt xe → xếp đúng hàng kế hoạch đã đặt lên xe → ký biên bản.
- Hãng vận chuyển của shipment là hãng đã đặt xe, gán khi bàn giao.

**Đã bỏ COD**
- Chính sách `ON_DELIVERY` của đơn và điều khoản khách.
- Số thu hộ trên đơn, shipment, nhãn, biên bản, chuyến.
- Hoá đơn công nợ hãng lập khi giao.

**Response mất field:** `mode` / `deliveryMode`, `codAmount` / `codTotal` / `labelCodAmount`, `currency` đi kèm, `amountDueOnDelivery`, `deliveryModeLocked`. Sự kiện `ShipmentStatusChanged` mất `deliveryMode`.

**Quyền đã bỏ:** `fulfillment-load-cancellations`, `fulfillment-dispatch:CREATE`, `sales-order-delivery-modes`; 6 mã lỗi.

**Giữ có chủ đích**
- Bàn giao nguyên đơn, `shipment.carrier_id`, `customer_order.packing_started_at`.
- Phía hãng của sổ cái tiền (`Collector.CARRIER`, `POST /receivables/{id}/carrier-remittances`, bảng `payment.cod_remittance*`), để thu nốt tiền hãng đã thu trước đó. Bỏ cùng sổ cái ở 4B.

**Dữ liệu cũ trên server**
- Chuyến gửi kiện đang xếp → huỷ; shipment chờ kế hoạch xếp xe.
- Chuyến đã đi hoặc đã huỷ giữ làm lịch sử.
- COD hãng còn nợ được lập thành công nợ CARRIER. Nhờ vậy khi giao xong không phát sinh nợ cho khách, vì khách đã trả tiền cho hãng.
- `ON_DELIVERY` → `BEFORE_DELIVERY`.
- Sự kiện `ShipmentStatusChanged` đang chờ được cắt field `deliveryMode` khỏi chuỗi JSON. Phải cắt chuỗi giữ đúng định dạng: registry so khớp đúng từng ký tự.

**FE cần sửa:** bỏ màn và hành động gửi kiện, chọn hãng, chọn cách giao, mọi chỗ hiển thị COD.

### Đợt 2e — Bỏ kiểm thiết kế khi đóng gói (#262)

**Đã xoá**
- So checksum thiết kế khi hoàn tất đóng gói (BR-DSG-003).
- Trạng thái kiện `ON_HOLD` và treo đơn `DESIGN_MISMATCH`.
- Kiểm lại của Quản lý kho: `POST /fulfillment/tasks/{id}/design-integrity-reverification`.
- Tải file thiết kế ở kho: `GET /fulfillment/tasks/{id}/lines/{lineId}/design-artifacts/{role}/download-url`.
- Field `integrityEvidence` của task.
- Quyền `fulfillment-design-reverification`, mã lỗi `ORDER_DESIGN_HOLD_USE_REVERIFICATION`.
- Bảng `fulfillment.design_verification`.

Fulfillment không còn phụ thuộc `design :: api`.

**Đổi phạm vi có lý do:** chưa dựng lại trạm "kiểm thùng & dán nhãn" (tài liệu 08), chuyển sang **4C**.
- Tài liệu 08 coi thùng LPN là kiện giao. Điều đó chỉ đúng khi lấy hàng chỉ lấy nguyên thùng.
- Bỏ lấy lẻ đi cùng việc chuyển tồn lẻ vào thùng ở đợt 4.
- Dựng trạm bây giờ thì phải làm bản lai cho dòng lấy lẻ rồi lại đập đi.

**Giữ có chủ đích**
- `designSnapshotId` / `printLine` của dòng đơn, chỉ để hiển thị.
- Lịch sử treo `DESIGN_MISMATCH` đã gỡ trong `ordering.order_hold`.

**Dữ liệu cũ trên server**
- Kiện đang treo → `PACKING`.
- Đơn treo `DESIGN_MISMATCH` → về trạng thái trước khi treo, có ghi lịch sử.
- Dòng treo đang mở → lưu trữ rồi xoá: CHECK bắt buộc ghi người gỡ, mà không ai gỡ.

**FE cần sửa:** bỏ hành động kiểm lại, tải file thiết kế ở kho, phần bằng chứng kiểm thiết kế.

---

## 3. Sau khi merge — việc trên server

**Khuyến nghị: reset dữ liệu test rồi nạp seed mới.** Seed `tools/db/seed` đã hỏng từ trước trên develop (`ck_supplier_invoices_status`) và chưa theo kịp các đợt xoá. Sinh lại seed là gói 5-x trong kế hoạch.

Nếu giữ dữ liệu cũ, các migration tự chuyển dữ liệu như ở mục 2. Còn phải làm tay:
- Tài khoản Sales Manager và QC (nay là Nhân viên kho) **đăng nhập lại**.
- Tài khoản chỉ có role Sản xuất bị vô hiệu hoá.
- Bản thiết kế đang chờ người duyệt là user QC: Sales Manager gán lại người duyệt.
- Dòng in không có hàng trong kho: Sale đóng phần còn lại hoặc huỷ đơn.
- Chuyến gửi kiện đang xếp bị huỷ: shipment cần kế hoạch xếp xe.

---

## 4. Những phần cố ý để lại cho đợt sau

Mỗi mục đều có gói tương ứng trong [11-team-plan.md](11-team-plan.md).

| Còn lại | Lý do để lại | Làm ở |
|---|---|---|
| Thiết kế cũ (nhân viên thiết kế, khách xác nhận, yêu cầu sửa, review kỹ thuật) | Design Studio là việc xây mới 3–5 ngày, không phải xoá | 2F |
| Dòng in của đơn (`designSnapshotId`, `printLine`), chặn `ORDER_PRINT_GOODS_NOT_RECEIVED` | Chờ dòng đơn nêu SKU in của khách | 2F + 4A |
| Gia công ngoài (PO `SUBCONTRACT`, `procurement.subcontract_settlement`, `warehouse.subcontractor_site`), hoá đơn / hiệu suất NCC | Nằm trong code mua hàng, xoá cả khối cùng PO | 4A |
| Phía hãng của sổ cái tiền (`Collector.CARRIER`, `payment.cod_remittance*`), sổ cái tiền | Bỏ khi có hợp đồng + đợt thanh toán | 4B |
| Lấy lẻ, đóng gói lại; trạm "kiểm thùng & dán nhãn" | Phải chuyển tồn lẻ vào thùng trước | 4C |
| Loại khu `QUALITY_CONTROL`, đổi trả cũ (RMA, QC hàng trả) | Chờ phiếu nhập hàng hoàn | 4D |
| Role `ORDER_COORDINATOR`, `INVENTORY_PLANNER`, `PROCUREMENT_STAFF` | Quyền chuyển cho Sales Staff / Quản lý kho | 5 |
| Seed `tools/db/seed`, `docs/business-design`, `docs/api-audit`, CLAUDE.md, README | Sinh lại một lần khi xong các đợt xoá | 5 |

---

## 5. Kiểm tra lại trên máy

```bash
git checkout refactor/rescope-02e-packing     # nhánh chứa đủ 6 đợt
python tools/verify.py
mvn test -Dtest=ModularityTest
mvn test                                      # cần Docker (Testcontainers)
```

Test nâng cấp của từng migration:
- `ProductionRemovalMigrationUpgradeTest`
- `QcRemovalMigrationUpgradeTest`
- `TransferRemovalMigrationUpgradeTest`
- `ParcelShippingRemovalMigrationUpgradeTest`
- `DesignCheckRemovalMigrationUpgradeTest`
