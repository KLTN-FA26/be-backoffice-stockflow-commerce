# Kế hoạch dọn phạm vi theo tài liệu mới — bỏ luồng sản xuất và các phần thừa

- **Ngày lập:** 2026-11-07.
- **Trạng thái:** ⏳ **CHỜ BẠN DUYỆT.** Chưa sửa dòng code nào.
- **Code đối chiếu:** `develop` @ `673c68ae` (đã gồm PR #256 S3), nhánh làm việc `chore/rescope-cleanup`.
- **Quy mô code hiện tại:** 2.137 file chính / 169.827 dòng; 562 file test / 134.685 dòng; 206 migration.
- **Tài liệu đối chiếu:** kltn-docs `origin/main` @ `e17b6cc` (07/11/2026).
  - Chốt phạm vi trong các lượt 04–07/11.
  - Đã xoá các chương 02 (PO), 04 (hoá đơn NCC), 10 (chuyển kho), 19 (sản xuất); thêm chương 21 (hoá đơn điện tử).
  - **Danh sách chuẩn các khái niệm bị bỏ:** glossary §8 "Thuật ngữ không còn áp dụng".
- **Cách làm:**
  - 6 agent chỉ đọc, mỗi agent đối chiếu một mảng tài liệu với code: sản xuất, mua hàng/nhập kho, tồn kho/kho bãi, bán hàng/thanh toán, lấy–đóng–giao hàng, phần dùng chung.
  - 1 agent đối chiếu hai repo FE.
  - Mình tự kiểm lại các điểm nguy hiểm.
  - Báo cáo gốc chi tiết (có số dòng code) nằm trong thư mục [2026-11-07-rescope/](2026-11-07-rescope/).

---

## 0. Tóm tắt để duyệt

### Phạm vi mới, một đoạn

Kho chỉ chứa **thành phẩm** in theo mẫu của khách, do đơn vị sản xuất **ngoài hệ thống** đóng thùng sẵn rồi giao về. Hệ thống chỉ gửi **Đơn sản xuất**, nhập kho theo thùng (LPN) và xuất **nguyên thùng**.

Những thứ không còn trong hệ thống:
- **Trong kho:** không mua hàng từ NCC; không QC tại kho; không chuyển hàng giữa các kho; không hạn dùng/FEFO (chỉ FIFO); không hàng trơn.
- **Phía khách:** khách không tự đặt hàng (không giỏ, không checkout, sàn ẩn giá); không cổng thanh toán online/QR, không COD.
- **Giao hàng và đóng gói:** không gửi kiện qua hãng (mọi đơn giao nguyên chuyến); không đóng gói lại.

Giữ lại và đổi cách làm:
- Bán hàng theo **báo giá → đơn → hợp đồng** có **lịch thanh toán theo đợt** (chuyển khoản).
- Hoá đơn điện tử theo lần giao.
- Còn 7 role: System Admin, Warehouse Staff, Warehouse Manager, Accountant, **Sales Manager** (đổi tên từ E-commerce Admin), Sales Staff, Customer.

### Con số (ước lượng, có trùng lặp nhỏ giữa các mảng)

| | Bỏ | Tỷ lệ |
|---|---|---|
| Code chính BE | ~500 file, ~40–45 nghìn dòng | ~25% |
| Code test BE | ~40 nghìn dòng | ~30% |
| Bảng DB | ~60 bảng (archive rồi drop) | — |
| Endpoint | ~150 endpoint | — |
| Role | 5 / 12 | — |
| FE backoffice | ~596 file, ~67 nghìn dòng (cộng ~2,5–3 nghìn dòng dữ liệu mock) | — |
| FE storefront | ~66 file, ~16 nghìn dòng (~10 nghìn là bản sao dữ liệu mock của backoffice) | — |

### Ba nhóm việc

| Nhóm | Ý nghĩa | Ví dụ |
|---|---|---|
| **A — Bỏ ngay, an toàn** | Code chết hoặc lá, không ai dùng | Waves, guest checkout, stub đăng ký, sự kiện `PaymentFailed`, stub khuyến mãi |
| **B — Bỏ được, nhưng phải sửa phần giữ lại trong cùng đợt** | Phần giữ lại đang gọi tới, hoặc DB có trigger/hàm tham chiếu | Sản xuất, chuyển kho, QC, cảnh báo tồn, hạn dùng, gửi kiện + COD, đóng gói lại |
| **C — Chỉ bỏ được sau khi xây cái thay thế** | Bỏ trước thì hệ thống mất chức năng sống còn | PO (nguồn nhập kho duy nhất hiện nay), sổ cái tiền + điều khoản thanh toán (trước khi có hợp đồng + đợt thanh toán), đổi trả có QC, tồn lẻ/thùng mở |

### Quyết định cần bạn duyệt

Có **8 quyết định chính** và **10 quyết định phụ**, đầy đủ ở §9. Quyết định phụ thì mình làm theo đề xuất nếu bạn không có ý kiến. Tám quyết định chính:

1. **Thứ tự:** dọn theo đợt (§7), mỗi đợt build và test xanh; nhóm C làm sau cùng.
2. **Dữ liệu trên VPS** (`develop` tự deploy với profile `prod`): reset sạch rồi nạp seed mới (đề xuất), hay chuyển đổi dữ liệu cũ?
3. **Đơn sản xuất mới:** xây trong module `order` (đề xuất), hay module riêng?
4. **Bỏ PO khi nào:** giữ PO làm nguồn nhập kho tạm cho tới khi có Đơn sản xuất (đề xuất), hay bỏ ngay và tạm không nhập được hàng?
5. **Email cho khách:** giữ code nhưng tắt mặc định (đề xuất), hay xoá?
6. **Ảnh nhận hàng/QC vừa làm ở nhánh S3:** bỏ cả hai giai đoạn (đề xuất)?
7. **Người đang mang 5 role bị bỏ:** chuyển sang role tương ứng, riêng nhân viên mua hàng/sản xuất thì vô hiệu hoá (đề xuất)?
8. **Chuyển mọi sản phẩm `STANDARD` → `CUSTOMIZABLE`:** làm sau khi có SKU riêng (đề xuất; 94 file test đang dựa trên 2 sản phẩm demo `STANDARD`)?

---

## 1. Những thứ bị bỏ — theo từng khái niệm của glossary §8

Ký hiệu cột "Nhóm": A / B / C như §0. Đường dẫn chi tiết, số dòng và phụ thuộc nằm trong các báo cáo ở [2026-11-07-rescope/](2026-11-07-rescope/).

### 1.1 Sản xuất nội bộ — trọng tâm của bạn ([01-production.md](2026-11-07-rescope/01-production.md))

Khái niệm bị bỏ (glossary §8): xưởng in, phôi, lệnh sản xuất nội bộ (LSX), prepress, trạm in, phế phẩm, QC hàng in, sản xuất mẫu / mẫu đã duyệt, gia công ngoài. **Nhóm B.**

| Phần bị xoá | Quy mô |
|---|---|
| Module `production` | **Toàn bộ:** 86 file / 8.126 dòng, 28 file test / 9.036 dòng, 43 endpoint `/api/v1/production/**`, 4 listener. Không module nào import nó: `api` rỗng. |
| `contracts` | `OrderLinesReleasedForProduction`, `ProductionCompleted`, `ProductionOrderHeld`, `SampleApproved`, hằng `OrderRemainderClosed.PRODUCTION_SHORTFALL`. |
| `order` | Xem danh sách bên dưới. |
| `inventory` | API giữ phôi cho sản xuất: 8 type + 17 method, khoảng 1,4 nghìn dòng (cấp phát, trả, phế, tiêu thụ thành phẩm…). |
| `procurement` | Gia công ngoài: 10 file / 1.201 dòng, cộng các nhánh trong PO/hoá đơn. |
| `warehouse` | Điểm gia công (`subcontractor_site`). |
| `notification` | `ProductionNotificationListener`. |
| Role, quyền | Role `PRODUCTION_STAFF`; 15 mã quyền `production-*`. |
| DB | Schema `production` (10 bảng); `ordering.approved_sample`, `ordering.order_line_production`; `procurement.subcontract_settlement`; `warehouse.subcontractor_site`. |

Phần trong `order` bị xoá:
- `ProductionRecords` (+adapter, +listener);
- `QuoteDirectory`;
- `addProductionHolds`, `lockedLineWithdrawn`;
- endpoint "thoả thuận thiếu hàng sản xuất" (S6);
- cổng "mẫu đã duyệt" ở chấp nhận báo giá / release / đơn thay thế;
- chờ in PACK-EARLY (`awaiting_production`).

**Khuyến nghị (agent sản xuất, mình đồng ý): xoá hẳn module, sau đó xây "Đơn sản xuất" mới trong `order`.**
- Đơn sản xuất mới (17 §4.3/§5.3) khác hoàn toàn mô hình cũ: chỉ khoảng 7/41 cột khớp; trạng thái, CHECK và bảng con đều khác.
- Đặt trong `order` để các luật cần khoá cùng đơn bán làm được đồng bộ:
  - đổi kho xuất trước khi có Đơn sản xuất `Sent`;
  - đổi SKU dòng khi Đơn sản xuất chưa `In Production`;
  - phí huỷ;
  - điều kiện `Awaiting Stock`.
- Một module riêng sẽ tạo vòng phụ thuộc với `order`.

### 1.2 Mua hàng từ NCC và hoá đơn NCC ([02-procurement-receiving.md](2026-11-07-rescope/02-procurement-receiving.md))

Khái niệm bị bỏ (glossary §8): PO, NCC, catalog NCC, xác nhận NCC, hạn mức duyệt PO, lead time, MOQ; hoá đơn NCC, đối chiếu ba chiều, công nợ phải trả, RTV; đề xuất nhập hàng.

**Trong `procurement` (bỏ 183/212 file, 15.575 dòng; bỏ 67/73 endpoint):**

| Phần | Quy mô | Nhóm |
|---|---|---|
| PO | 92 file / 8.009 dòng | **C**: hiện là nguồn duy nhất của phiếu nhập kho |
| NCC | 42 file / 2.729 dòng | **C** |
| Hoá đơn NCC + đối chiếu 3 chiều | 19 file / 2.477 dòng | **B**: là lá, bỏ sớm được |
| Gia công ngoài | — | Xem §1.1 |

**Ngoài `procurement`:**
- product: `SkuPurchasing*`, `SupplierProbe`;
- inventory: `ItemPurchasing*` + 8 cột mua hàng trên Inventory Item;
- notification: gửi PO qua email/API (listener + retry + điều khiển);
- contracts: `PurchaseOrderSent`;
- common: storage `SUPPLIER_INVOICE`, cache `SUPPLIERS`;
- 8 resource quyền / 35 dòng quyền; role `PROCUREMENT_STAFF`;
- cấu hình `stockflow.procurement.*`;
- biến deploy `PO_BUYER_*` (hiện đang bắt buộc).

**DB:** bỏ 23 bảng `procurement` + `notification.po_delivery_control`. Giữ 2 bảng `goods_receipts`, `goods_receipt_lines` (sửa).

### 1.3 QC tại kho

Khái niệm bị bỏ (glossary §8): QC, khu `QUALITY_CONTROL`, cờ "Yêu cầu QC khi nhận", nhập 3 bước, kết luận QC, phiếu `In QC`. **Nhóm B.**

Phần bị xoá:
- QC phiếu nhận: `QcInspection`, trạng thái `IN_QC` / `CONFIRMED`, `moveToQc` / `inspect`;
- trạng thái tồn `BLOCKED`; cờ `qc_required`;
- khu `QUALITY_CONTROL`; `move_task` nguồn `RECEIPT_QC`;
- **ảnh QC** (vừa làm ở S3);
- quyền `procurement-qc-tasks`; role `QC_STAFF`;
- QC hàng trả (`ReturnInspectionController`, quyền `sales-rma-inspections`).

Lưu ý: phần thiết kế đang hard-code người duyệt là QC (`DesignWorkflowService.java:334`). Phần này được thay theo [kế hoạch Design Studio](2026-11-06-design-studio.md): người duyệt là Quản lý kinh doanh.

### 1.4 Chuyển hàng giữa các kho (chương 10 đã xoá) — Nhóm B

| Nơi | Phần bị xoá |
|---|---|
| inventory | 22 file / 2.149 dòng (lệnh chuyển kho, 10 endpoint) |
| fulfillment | 8 file / 955 dòng (đóng gói chuyển kho, 12 endpoint) + nhánh chuyển kho trong kế hoạch xếp xe / bàn giao (~250 dòng) |
| DB | `transfer_order`, `transfer_order_line`, cùng rất nhiều FK/CHECK/trigger/hàm trong `fulfillment` trỏ vào chúng (bỏ trước) |
| Quyền | `inventory-transfer-orders` |

**Không xoá** 3 mã lỗi `TRANSFER_*` của thanh toán: đó là chuyển khoản ngân hàng, không phải chuyển kho.

### 1.5 Hạn dùng, FEFO, cảnh báo tồn (thấp / đặt lại / an toàn) — Nhóm B

Phần bị xoá:
- inventory:
  - cảnh báo tồn: 22 file / 1.556 dòng, 2 job định kỳ;
  - hạn dùng: 4 file / 335 dòng, job quét hạn hằng đêm;
  - nhánh FEFO trong `StockAllocator`;
- product: `ProductReplenishmentEligibility`;
- notification: `InventoryAlertNotificationListener`;
- contracts: `InventoryAlertsRaised`, `SkuInventoryControlChanged`;
- quyền `inventory-alerts`;
- DB: `stock_alert`, `stock_alert_dirty` + 3 trigger trên `stock_item`; các cột hạn dùng / ngưỡng trên `inventory_items`, `stock_item`, `stock_lot`, `goods_receipt_lines`.

### 1.6 Hàng trơn và giá trên sàn — Nhóm B

Phần bị xoá:
- loại sản phẩm `STANDARD` và các nhánh code của nó (mọi sản phẩm là `CUSTOMIZABLE`);
- **giá gốc**, **giá nhóm khách**, endpoint giá trên sàn `GET /catalog/products/prices`;
- stub khuyến mãi / luật giá;
- DB `catalog.pricing_rule`, `catalog.promotion`, cột giá trên `catalog_entry`.

Giữ lại:
- **Giá nội bộ theo (khách, SKU riêng):** thành giá gợi ý cho Sale, tính theo thùng.
- **Nhóm khách:** chỉ để phân loại.

### 1.7 Khách tự đặt hàng — Nhóm A/B

Phần bị xoá:
- **Đặt và tạo đơn:**
  - checkout khách vãng lai, stub tự đăng ký;
  - endpoint tự checkout `POST /api/v1/orders`, kiểm đơn trùng;
  - "đặt lại" tự sinh báo giá nháp;
  - API giá checkout, danh sách dựng lại giỏ.
- **Thanh toán phía khách:** endpoint đọc điều khoản thanh toán lúc checkout.
- **Dữ liệu và quyền:** bảng `cart`, `cart_line` (đã không có code Java); quyền `sales-carts`.

Lưu ý: giữ tạm `OrderService.placeOrder`. Có 31 file test dùng nó để dựng đơn; sẽ thay bằng helper test khi có hợp đồng.

### 1.8 Thanh toán online, điều khoản theo đơn, COD, sổ cái tiền cũ

| Phần | Nhóm |
|---|---|
| Tàn dư cổng thanh toán (`PaymentFailed` không ai phát; `payment.payment` thẻ/ví) | **A** |
| COD, chính sách thu phần còn lại, chọn cách giao | **B**: cùng đợt với giao hàng (§1.9) |
| Sổ cái tiền cũ (chuyển khoản / phân bổ / credit / đảo), điều khoản trả trước / cọc / công nợ trên đơn và báo giá, huỷ tự động khi hết hạn giữ hàng | **C**: chỉ bỏ sau khi có **hợp đồng + đợt thanh toán + phiếu thu** (15). Bỏ trước thì không ghi nhận được tiền |

### 1.9 Gửi kiện qua hãng và chọn cách giao — Nhóm B, code và migration cùng một PR

Phần bị xoá:
- **fulfillment:** chuyến kiện, gán hãng theo shipment, COD, giao cả đơn;
- **order:** `DeliveryMode` + endpoint đổi cách giao + lịch sử;
- **payment:** đối soát COD (`cod_remittance`);
- **DB:** các cột `delivery_mode` / `cod_*`;
- **dữ liệu:** tắt 4 hãng gửi kiện GHN / GHTK / VTP / JTE.

**Phải đi chung code với migration:** cột `delivery_mode` có default `'PARCEL'`, trong khi CHECK của chuyến xe và hàm `check_load_plan_package` đòi `FULL_TRUCKLOAD`. Tách code và migration ra hai PR sẽ hỏng ghi dữ liệu.

### 1.10 Đóng gói lại, kiểm checksum thiết kế khi đóng gói, báo hỏng từng cái ([05-fulfillment.md](2026-11-07-rescope/05-fulfillment.md))

| Phần | Nhóm |
|---|---|
| Trạm đóng gói lại: mở/niêm phong kiện, quét từng cái, cân đo, loại kiện, kit, nhãn n/N + COD, nhãn lỗi thời | **B**: thay bằng "kiểm thùng & dán nhãn" (08) |
| Kiểm checksum thiết kế khi đóng gói: bảng `design_verification`, treo đơn `DESIGN_MISMATCH`, quyền `fulfillment-design-reverification` | **B** (glossary §8 ghi rõ đã bỏ) |
| Báo hỏng từng cái khi lấy/đóng: fulfillment 5 endpoint + inventory **báo hỏng có ảnh** 29 file / 3.546 dòng, 4 bảng `stock_damage_*`, trả NCC | **B**: thay bằng "báo thiếu + cách ly nguyên thùng" và điều chỉnh `DAMAGED` |
| Wave picking: code chết, không ai gọi | **A** |

### 1.11 Lấy lẻ, thùng đã mở, thùng lẻ, số lượng theo cái — Nhóm C

Phần bị xoá trong inventory (khoảng 1 nghìn dòng):
- đường di chuyển / cất hàng / đếm **hàng lẻ**;
- mở thùng;
- `CountVariance`;
- chuỗi quy cách đóng gói 4 cấp;
- endpoint `POST /inventory/movements` theo SKU + số lượng.

**Phải chuyển dữ liệu tồn trước:** seed hiện có 226/228 dòng tồn là hàng lẻ, 212 dòng không có lô. Mọi tồn phải nằm trong thùng LPN và có lô.

### 1.12 Lớp lưu trữ, hàng lạnh/nguy hiểm, sức chứa theo đơn vị, khu `PACKING` — Nhóm B

Phần bị xoá:
- `StorageClass` (inventory + warehouse; khoảng 43 file warehouse có đụng tới);
- cờ "dễ vỡ" suy ra từ lớp lưu trữ;
- cờ chữ ký người lớn / ghi chú hạn chế giao;
- `capacity_units` → thay bằng thể tích m³ tự tính;
- khu `PACKING` → gộp vào `DISPATCH`.

Các bản ghi vị trí loại cũ (`QUALITY_CONTROL`, `PACKING`, `PRODUCTION`, `SUBCONTRACTOR`) **không xoá được**, vì sổ cái tồn có FK trỏ tới. Thay vào đó, đổi sang loại hợp lệ rồi để `INACTIVE`.

### 1.13 Chính sách tồn cấu hình được (lô / serial / cách xuất) — Nhóm B

Bỏ endpoint `PUT /inventory/items/{id}/policy` và serial. Chính sách cố định là **luôn theo lô, FIFO**.

### 1.14 Đổi trả: QC trả hàng, đơn thay thế miễn phí, credit note — Nhóm C

Chỉ bỏ sau khi có **phiếu nhập Hàng hoàn theo LPN** vào khu `QUARANTINE` (03), thay cho `receiveReturn` theo SKU + số lượng như hiện nay.

### 1.15 Role

Bỏ 5 role:
- `PROCUREMENT_STAFF`, `PRODUCTION_STAFF`, `QC_STAFF`: bỏ cùng các phần tương ứng ở trên.
- `ORDER_COORDINATOR`: quyền chuyển cho Sale (huỷ, treo, đổi trả, giao một phần, chuyển báo giá thành đơn) và Quản lý kho (phân việc, kết quả giao, kế hoạch xe).
- `INVENTORY_PLANNER`: quyền chuyển cho Quản lý kho / Sale.

Đổi tên `ECOMMERCE_ADMIN` → `SALES_MANAGER`. Chi tiết role/quyền/user đang mang role ở §8.

### 1.16 Code mình vừa thêm ở nhánh S3 mà tài liệu mới không có

| Phần | Việc |
|---|---|
| **Ảnh nhận hàng + ảnh QC** (`GoodsReceiptPhoto*`, 8 endpoint, bảng `goods_receipt_photos`) | Chương 03 không có ảnh; QC bị bỏ → đề xuất bỏ cả hai giai đoạn |
| Tài liệu đính kèm PO, hoá đơn NCC | Bỏ cùng mua hàng |
| Storage `DOCUMENT` | Có thể giữ để lưu hợp đồng đã ký (14) |
| Các phần S3 khác (upload thẳng S3, preflight PDF, ảnh hỏng hàng ở trả hàng, quét orphan, Object Lock cho thiết kế) | Vẫn dùng được |

### 1.17 Thiết kế theo luồng cũ (nhân viên thiết kế, QC duyệt, khách xác nhận)

Đã có [kế hoạch Design Studio](2026-11-06-design-studio.md) riêng, theo tài liệu 12 bản 05/11. Kế hoạch dọn này **không** đụng vào module `design`, trừ phần quyền QC.

---

## 2. Bảy lỗi sẽ "hỏng âm thầm" nếu chỉ xoá mà không sửa đúng chỗ

Mình đã tự đọc code xác nhận 4 điểm đầu; 3 điểm sau lấy từ báo cáo agent, có số dòng cụ thể.

| # | Chỗ | Chuyện gì xảy ra | Cách tránh |
|---|---|---|---|
| 1 | `PickList.plan` bỏ qua mọi dòng có design snapshot ("dòng in") | SKU riêng của khách luôn mang snapshot → **không dòng nào được lấy hàng**, không báo lỗi | Đợt 2: bỏ nhánh này trước khi làm gì khác |
| 2 | `OrderAllocator.PAYABLE` là tập **chuỗi**, có `"IN_PRODUCTION"` | Đổi tên trạng thái vẫn compile, nhưng **tiền ngừng được phân bổ** | Sửa chuỗi cùng lúc đổi trạng thái |
| 3 | Hàm SQL `inventory.item_in_use`, `unit_in_use`, `warehouse.check_storage_location_owned`, `fulfillment.check_load_*` đọc thẳng bảng sắp xoá | Postgres không theo dõi phụ thuộc trong thân hàm → `DROP TABLE` chạy qua, rồi **lỗi lúc chạy**. Riêng `check_storage_location_owned` chạy mỗi lần tạo bin/khu mới | Viết lại hàm **trong cùng migration** với lệnh drop |
| 4 | `republish-outstanding-events-on-restart: true` | Event dở dang của class đã xoá trong `event_publication` → lỗi mỗi lần khởi động | Migration dọn `event_publication` trước khi xoá class |
| 5 | `DROP COLUMN` một cột nằm trong CHECK nhiều cột (`ck_stock_reservation_owner/_kind`) | Postgres **tự xoá luôn cả CHECK** → mất ràng buộc mà không ai biết | Tạo lại CHECK ngay sau |
| 6 | Enum map `EnumType.STRING` trên dữ liệu lịch sử chỉ-thêm (sổ cái tồn, lịch sử đơn) | Xoá hằng enum → **đọc dữ liệu cũ là lỗi 500** | Giữ hằng enum cũ ở dạng chỉ-đọc |
| 7 | `OrderStatus.valueOf` trên chuỗi lịch sử (`DeliveryModeService:59`, `PartialShipmentService:64`) | Đổi tên trạng thái → endpoint lịch sử ném lỗi | Dùng ánh xạ chịu lỗi `statusOrNull` |

Thêm một điểm về cách viết migration: Hibernate chạy `ddl-auto: validate`, nên **bỏ map một cột trong entity phải đi cùng PR với migration** của cột đó, nếu default của cột vi phạm CHECK mới.

---

## 3. Giữ nhưng phải sửa (tóm tắt — chi tiết từng file trong báo cáo)

| Module | Bắt buộc sửa khi xoá (không sửa là hỏng build/test) | Sửa để khớp tài liệu (làm sau, theo §5) |
|---|---|---|
| `order` | `release` bỏ nhánh sản xuất; bỏ cổng mẫu; bỏ S6, PACK-EARLY; bỏ map cột thanh toán/cách giao trước khi drop; trạng thái `IN_PRODUCTION` ở 7 nơi ngoài module | Báo giá theo SKU riêng + số thùng + VAT + phí; đơn → hợp đồng; `PENDING_CONTRACT`, `AWAITING_STOCK`; chọn kho xuất; release theo đợt giao |
| `fulfillment` | Bỏ nhánh chuyển kho, kit, COD, parcel; bỏ `design :: api`; quyền | Một tác vụ mỗi đợt giao; đích `STAGING` / `DISPATCH`; kiểm thùng & dán nhãn; mã vận đơn, webhook |
| `inventory` | Gỡ API sản xuất / mua hàng / QC / chuyển kho khỏi `InventoryService`; enum mất giá trị (giữ giá trị lịch sử); `InventoryControlService` chỉ còn `logistics()` | Giữ hàng theo kho xuất, theo thùng, không hạn; phân bổ k<N thùng khi thiếu; lô = mã Đơn sản xuất; nhập hàng hoàn theo LPN |
| `warehouse` | Bỏ `storageClass` khỏi API cất hàng; bỏ điểm gia công | Khu `STAGING`; thể tích m³; thùng vừa bin; `OVERFLOW`; bảng sức chứa |
| `procurement` (phần nhận hàng) | Nhận hàng vẫn dựa PO cho tới khi có Đơn sản xuất | Nguồn = Đơn sản xuất; loại `RETURN`; chặn nhận vượt (đếm cả phiếu nháp) |
| `payment` | Bỏ chuỗi `IN_PRODUCTION`; bỏ phần COD | Đợt thanh toán, phiếu thu, hoàn tiền thay cho sổ cái |
| `catalog`, `customer` | Chiếu catalog không cần giá; bỏ `register`, `segmentExists` | Giá nội bộ theo thùng; hạn mức + kỳ hạn + lịch thanh toán mặc định |
| `notification` | Bỏ 3 listener chết (sản xuất, PO, cảnh báo tồn) + 3 hàm gửi; bỏ nhánh "đề xuất nhập hàng" trong thông báo hỏng hàng | Thêm các thông báo 00 §6 còn thiếu: Đơn sản xuất đã gửi, kho sắp hết chỗ, đã bàn giao (in-app cho khách, theo xe), hoá đơn điện tử; 4 cảnh báo trễ (17 §4.12) |
| `reporting` | Bỏ nhánh "đóng thiếu do sản xuất" | Doanh thu trước thuế gồm dòng phí; SKU bán chạy xếp theo doanh thu rồi số thùng |
| `contracts` | Xoá event chết và event của phần bị bỏ (khoảng 12 record); `PaymentMethod` chỉ còn `BANK_TRANSFER` | Event mới: Đơn sản xuất đã gửi, bàn giao theo xe, hoá đơn điện tử |
| `identity` | Đổi tên role, chuyển quyền, xoá 5 role (§8) | Quyền mới cho Đơn sản xuất, release theo kho, duyệt mẫu in, hoá đơn điện tử |
| `product` | Bỏ `SkuPurchasing*`, `SupplierProbe`, `ProductReplenishmentEligibility`; cảnh báo ngừng kinh doanh hết nguồn "dòng PO đang mở" | Mọi sản phẩm `CUSTOMIZABLE` (làm sau khi có SKU riêng: 94 test đang dựa trên 2 sản phẩm demo `STANDARD`) |
| `common` | Bỏ `CommercialTerms`, storage `SUPPLIER_INVOICE`, khoảng 70 mã lỗi chỉ PO/sản xuất dùng (+ bản dịch) | `DOCUMENT` dùng lại cho hợp đồng đã ký, phiếu Đơn sản xuất; thêm loại lưu trữ cho hoá đơn điện tử |
| Công cụ, test kiến trúc | `tools/verify.py`: danh sách module đang **hard-code** (thiếu luôn `production`) và allow-list event → sửa cùng commit; `NoRoleNameAccessTest`, `SystemAdminRoleIntegrationTest`, `PermissionMatrixTest`, `MessageBundleTest`, 3 `*ModuleTest` | Cho `verify.py` tự lấy danh sách module từ các `package-info` |
| Test | `PermissionMatrixTest`, `ReferenceRows`, `DemoData`, khoảng 40 test dùng `IN_PRODUCTION`, khoảng 66 test inventory / warehouse | |

---

## 4. Giữ nguyên (phần lõi tốt, không đụng)

- **Lớp nền:** `common` (bảo mật, idempotency, lưu trữ S3…), `platform.legacy_archive`, `platform.document_sequence`.
- **Kho bãi:** bản đồ kho (kho/khu/kệ/tầng/bin/biên, hình học, mã vị trí, nhãn vị trí).
- **Tồn kho:**
  - sổ cái tồn chỉ-thêm;
  - điều chỉnh tồn duyệt 4 mắt;
  - kiểm kê đếm mù;
  - thùng LPN (sinh mã, nhãn, tra cứu);
  - ranh giới `inventory :: consumption` khi lấy hàng.
- **Lấy và giao hàng:**
  - xác nhận lấy hàng chỉ bằng LPN, báo thiếu + đếm lại + phân bổ lại;
  - **kế hoạch xếp xe 3D** (38 file / 4.555 dòng), huỷ có kiểm soát, danh mục loại xe;
  - vòng đời shipment, dòng thời gian shipment, lý do giao thất bại (khớp 09 §4.3).
- **Bán hàng:**
  - số đơn, lịch sử trạng thái;
  - treo đơn, yêu cầu huỷ, giao một phần;
  - báo giá (khung), danh sách và tìm kiếm.
- **Catalog:** duyệt / tìm kiếm / SEO / xuất bản (bỏ phần giá).
- **Khách hàng:** khách, mời và kích hoạt 72h, địa chỉ, lịch sử thay đổi.
- **Thông báo:** hộp thông báo trong app.
- **Module `design`:** đi theo kế hoạch riêng.

---

## 5. Những gì tài liệu mới cần mà code chưa có

Để bạn biết khoảng trống; không thuộc đợt dọn.

| Chức năng | Chương | Ghi chú |
|---|---|---|
| **Đơn sản xuất** + danh mục đơn vị sản xuất + vòng duyệt mẫu in + lịch sản xuất + cảnh báo trễ | 17 §4.3, §4.12 | Xây trong `order` (§1.1). Là nguồn nhập kho mới |
| **Hợp đồng mua bán** + **lịch thanh toán theo đợt** + phiếu thu + hoàn tiền + phí huỷ | 14, 15 | Thay sổ cái tiền cũ |
| **Lịch giao** (đợt giao, cửa sổ giao, dời cửa sổ) | 14 BR-23, 17 §4.5 | Mới nhất 07/11 |
| Dòng báo giá/đơn theo **số thùng**, **VAT từng dòng**, dòng phí vận chuyển, phí khuôn | 14 | |
| **Hoá đơn điện tử** theo lần giao | 21 (mới) | Chưa có gì |
| Khách tự thiết kế, SKU riêng, dự án thiết kế, yêu cầu báo giá | 12, 13, 16 | Kế hoạch Design Studio |
| Chat với Sale + thẻ yêu cầu báo giá / duyệt mẫu in | 16 | Module `chat` chỉ là khung rỗng |
| Kiểm thùng & dán nhãn | 08 | Viết lại từ trạm đóng gói cũ |
| Mã vận đơn, webhook hãng, nhập hàng loạt, đặt xe qua API, mốc hành trình | 09 | |
| Khu `STAGING`, thể tích m³ tự tính, thùng vừa bin, `OVERFLOW`, bảng sức chứa | 06 | |
| Cách ly / gỡ cách ly thùng; nhập hàng hoàn theo LPN | 20, 03 | |

---

## 6. Cơ sở dữ liệu — nguyên tắc chung cho mọi đợt

1. **Chỉ viết migration mới, không sửa/xoá migration cũ.** Ba lý do:
   - Flyway `validate-on-migrate`;
   - có manifest checksum;
   - `develop` tự deploy lên VPS.
2. **Archive rồi mới drop**, theo mẫu `V20261011000200`. Mỗi bảng bị xoá, và mỗi dòng của bảng giữ lại nhưng mất cột, được chép nguyên dòng vào `platform.legacy_archive` trước.
3. **Thứ tự trong một migration:**
   1. kiểm điều kiện, nêu tên bảng + số dòng nếu còn dữ liệu dở dang;
   2. archive;
   3. dọn `event_publication`;
   4. viết lại các hàm SQL tham chiếu bảng sắp xoá;
   5. drop FK / CHECK / trigger;
   6. drop cột, rồi bảng (con trước, không `CASCADE`);
   7. tạo lại CHECK nhiều cột;
   8. sửa quyền + tăng `app_role.version`.
4. **Không sửa migration demo cũ** (bị test nạp, có checksum). Thay bằng một migration demo mới, đổi/vô hiệu các dòng cũ.
5. **Seed `tools/db/seed`:** sửa file sinh `gen_seed_test_data.py` rồi sinh lại `seed_test_data.sql`. Seed hiện toàn tồn lẻ, không lô, có PO / sản xuất / chuyển kho nên phải dựng lại gần hết. Song song:
   - sửa `tools/db/qa/*`;
   - sửa `orphan_check.sql`;
   - sinh lại `schema.dbml`.
6. Migration mới đánh số sau `V20261106000100`, và sau migration mới nhất trên `develop` lúc merge.

---

## 7. Thứ tự thực hiện

Mỗi đợt là một hoặc vài PR. Mỗi PR chạy:
- `python tools/verify.py`;
- `ModularityTest`, `ArchitectureTest`, `PermissionMatrixTest`, `MessageBundleTest`;
- các test được nêu riêng ở từng đợt.

Đợt có migration chạy thêm toàn bộ `mvn test` (DB mới + dữ liệu demo), `tools/db/qa/run.sh` và `orphan_check.sql`.

| Đợt | Nội dung | Nhóm | Cỡ | Điều kiện |
|---|---|---|---|---|
| **0. Chuẩn bị** | Chốt các quyết định §9. Chạy baseline. Cho `verify.py` tự lấy danh sách module từ `package-info`. Kiểm `event_publication` dở dang trên VPS. Chạy 2 truy vấn: quyền nào chỉ role sắp bỏ đang giữ, và user nào đang mang role sắp bỏ. Báo team các nhánh đang mở đụng vào phần bị bỏ | — | S | — |
| **1. Xoá code chết + đổi tên Sales Manager** | **1a, xoá code chết:** waves; `PaymentFailed` + 2 listener; `DesignConfirmed`; `lowStockBatch`; stub slotting; `GoodsReceived` / `QcResult`; `CacheNames.SUPPLIERS`; checkout khách vãng lai; stub đăng ký; `releaseToFulfillment` cũ; stub khuyến mãi / luật giá; mã lỗi không dùng (kèm allow-list `verify.py`). **1b, đổi tên** `ECOMMERCE_ADMIN` → `SALES_MANAGER`, giữ nguyên id và quyền: enum, test, seed, deploy (người dùng đầu tiên là `SYSTEM_ADMIN`), migration đổi tên + huỷ phiên của người mang role. Làm sớm để mọi migration sau dùng tên mới. Báo FE nếu FE kiểm mã role | A | S | — |
| **2. Gỡ đường đi qua sản xuất** (chỉ code) | `Order.release` bỏ nhánh `IN_PRODUCTION`; bỏ cổng "mẫu đã duyệt" ở báo giá / release / đơn thay thế; lấy hàng và đóng gói **không bỏ qua dòng in**; sửa chuỗi trong `OrderAllocator`; đọc lịch sử trạng thái chịu lỗi; bỏ phế hàng in khi huỷ. Tạm thời đơn thiếu hàng nằm ở `CONFIRMED` | B | M | Đợt 1 |
| **3. Xoá sản xuất** | **3a, code:** module `production` + mọi phần phụ thuộc ở §1.1; giữ hằng enum lịch sử. **3b, migration:** archive + drop schema `production`, 2 bảng `ordering`, bảng gia công; viết lại `item_in_use`, `check_storage_location_owned`; tạo lại CHECK giữ hàng; xoá 15 mã quyền; dọn `event_publication` | B | L | Đợt 2 |
| **4. Kho bãi** | **4a:** cảnh báo tồn + hạn dùng / FEFO (+ job, bảng, cột). **4b:** chuyển kho — fulfillment trước rồi inventory; code + migration cùng PR (FK, CHECK, hàm `check_load_*`). **4c:** báo hỏng có ảnh + báo hỏng từng cái khi lấy/đóng | B | L | Đợt 1 |
| **5. Nhập kho** (giữ nguồn PO tạm) | **5a:** bỏ QC, ảnh nhận hàng/QC, nhận không PO, duyệt vượt dung sai (thay bằng chặn cứng, đếm cả phiếu nháp), hạn dùng khi nhận. **5b:** hoá đơn NCC + đối chiếu 3 chiều. **5c:** lớp lưu trữ + chính sách tồn cấu hình | B | L | Đợt 3 (gia công), 4 |
| **6. Bán hàng — bề mặt tự phục vụ** | Endpoint tự checkout (giữ `placeOrder` cho test); đặt lại tự động; dựng lại giỏ; đọc điều khoản lúc checkout; giá trên sàn; giá gốc / giá nhóm; bảng `cart` + quyền | A/B | M | Đợt 1 |
| **7. Giao hàng** | **7a:** gửi kiện + COD + chọn cách giao, ở fulfillment / order / payment / contracts / notification, code + migration cùng PR; tắt 4 hãng gửi kiện. **7b:** bỏ trạm đóng gói lại + kiểm checksum thiết kế → dựng "kiểm thùng & dán nhãn" (08) | B | L | Đợt 2, 4b |
| **8. Role & quyền** | Chuyển 3 quyền chỉ role sắp bỏ đang giữ (§8). Gán lại user đang mang 5 role bị bỏ. Huỷ phiên của họ. Xoá role. Xoá khoảng 15 dòng quyền chỉ còn trong DB. Tăng version. Sửa `TestUsers`, `PermissionMatrixTest`, `NoRoleNameAccessTest`, `SystemAdminRoleIntegrationTest` và khoảng 84 file test | B | M | Đợt 3, 5a; `QC_STAFF` sau khi phần thiết kế đổi người duyệt |
| **9. Nhóm C** (chỉ khi đã có cái thay thế) | **9a:** PO + NCC, sau khi có **Đơn sản xuất** và nhập kho theo nó; có thể đổi tên `procurement` → `receiving`. **9b:** sổ cái tiền + điều khoản theo đơn + huỷ khi hết hạn giữ hàng, sau khi có **hợp đồng + đợt thanh toán**. **9c:** QC trả hàng + đơn thay thế + credit note, sau khi có **phiếu nhập hàng hoàn theo LPN**. **9d:** hàng lẻ / thùng mở, sau khi **chuyển tồn sang thùng + lô** | C | XL | Các chức năng mới §5 |
| **10. Dữ liệu mẫu, công cụ, tài liệu** | Sinh lại seed; QA scripts; migration demo mới; `schema.dbml`; `CLAUDE.md`, `README`, `docs/adding-a-module.md` (đang lấy procurement làm ví dụ); đánh dấu `docs/business-design` đã thay thế; `deploy/.env.example` + `docker-compose` (bỏ biến `PO_BUYER_*` đang bắt buộc); key trong `application.yml` | — | M | Rải theo từng đợt, chốt ở cuối |

Ghi chú về các đợt:
- **Đợt 1–8** làm được ngay, theo đúng thứ tự trên. Một số đợt chạy song song được nếu không đụng cùng file, ví dụ 4 và 6.
- **Đợt 9** gắn với việc xây chức năng mới:
  - hợp đồng / đợt thanh toán / lịch giao;
  - Đơn sản xuất;
  - phiếu nhập hàng hoàn.
  
  Mấy việc này nên lên kế hoạch riêng sau khi bạn duyệt bản này.
- **FE** dọn song song theo từng đợt BE (§10).

---

## 8. Role, quyền, event, thông báo ([06-platform.md](2026-11-07-rescope/06-platform.md))

### 8.1 Role: 12 → 7

| Role | Việc | Người đang mang (seed) | Đề xuất cho người đang mang |
|---|---|---|---|
| `ECOMMERCE_ADMIN` | **Đổi tên → `SALES_MANAGER`**, giữ nguyên id và quyền (đợt 1). Thêm "Duyệt kỹ thuật thiết kế", "Duyệt Đơn sản xuất" | `ecom`, `ecom2` | Giữ nguyên |
| `ORDER_COORDINATOR` | Bỏ | `dieuphoi` | → Sales Staff (thêm Warehouse Manager nếu làm trong kho) |
| `INVENTORY_PLANNER` | Bỏ | `kehoach` | → Warehouse Manager + gán kho (không gán kho thì không thấy gì) |
| `QC_STAFF` | Bỏ | `qc.hn`, `qc.bd` | → Warehouse Staff (họ đã được gán kho) |
| `PROCUREMENT_STAFF` | Bỏ | `muahang1`, `muahang2` | → **Vô hiệu hoá** tài khoản (kèm lý do) |
| `PRODUCTION_STAFF` | Bỏ | `sanxuat.hn`, `sanxuat.bd`, `kiemduyet` | → **Vô hiệu hoá** tài khoản (kèm lý do) |

### 8.2 Ba quyền hiện chỉ role sắp bỏ đang giữ

Ngoài System Admin, ba quyền dưới đây chỉ nằm trên role sắp bỏ. **Phải chuyển trước khi xoá role**, nếu không sẽ không ai làm được các việc này.

| Quyền | Việc | Hiện ở | Chuyển sang |
|---|---|---|---|
| `sales-orders:APPROVE` | Release đơn, treo / gỡ treo, ghi giao thành công / thất bại | `ORDER_COORDINATOR` | Tách thành quyền mới: release + kết quả giao → **Warehouse Manager** (theo kho); treo đơn → **Sales Staff**. Không cấp nguyên `sales-orders:APPROVE` cho cả hai vì sẽ thừa quyền |
| `fulfillment-delivery-outcomes:APPROVE` | Duyệt / sửa kết quả giao | `ORDER_COORDINATOR` | **Warehouse Manager** |
| `design-designs:APPROVE` | Duyệt thiết kế | `QC_STAFF` | **Sales Manager** (kế hoạch Design Studio) |

### 8.3 Migration identity — thứ tự bắt buộc

1. Dừng nếu đã có role tự tạo mang mã `SALES_MANAGER`.
2. Đổi tên role, tăng version.
3. Cấp quyền mới cho role nhận việc (`ON CONFLICT DO NOTHING RETURNING`), tăng version các role đó.
4. **Xoá `role_permission` trước, tăng version, rồi mới xoá `permission`.** FK `role_permission → permission` có `CASCADE`, nên xoá `permission` trước sẽ âm thầm mất quyền mà không tăng version. Hệ quả: người đang đăng nhập giữ quyền cũ trong cache tới 1 giờ.
5. Huỷ phiên đang sống của người mang role bị đổi hoặc bỏ.
6. Chuyển hoặc vô hiệu hoá người mang role bị bỏ (§8.1), rồi xoá role. Tài liệu §2.5.1 không cho tài khoản nhân viên không có role.
7. Quyền mới nào cũng phải cấp cho System Admin trong cùng file (test `SystemAdminRoleIntegrationTest` kiểm điều này).

Ngoài ra, xoá **khoảng 15 dòng quyền chỉ còn trong DB** (không còn code dùng):

| Nhóm | Mã quyền |
|---|---|
| Bán hàng, thanh toán | `sales-carts`, `payment-payments`, `payment-refunds`, `payment-settlements`, `payment-cod-remittances` |
| Mua hàng, kho | `procurement-approval-limits`, `procurement-replenishment-proposals`, `warehouse-replenishment`, `inventory-allocations`, `fulfillment-return-shipments` |
| Sản phẩm (trùng lặp) | `product-*` |

Riêng `product-customization-templates` để lại cho kế hoạch Design Studio, và `chat-conversations` giữ nguyên.

### 8.4 Event, thông báo, báo cáo

| Mảng | Nội dung |
|---|---|
| **Event bỏ (khoảng 12)** | Đã chết: `DesignConfirmed`, `GoodsReceived`, `QcResult`, `PaymentFailed`. Đi cùng sản xuất: 4 event. Đi cùng PO: `PurchaseOrderSent`. Đi cùng cảnh báo tồn: 2 event. Có thể bỏ: `OrderCancellationReferred` |
| **Không được tạo phụ thuộc mới** (sẽ thành vòng) | `warehouse → order`, `inventory → order`, `order → e-invoice`. Phần "hàng sắp về" của bảng sức chứa tính ở `order` hoặc đẩy qua event; nhập kho không chuyển vào `inventory` |
| **Thông báo** | Thiếu 6/7 dòng bảng 00 §6 và cả 4 cảnh báo trễ (17 §4.12). 12 handler email cho khách đang bật mặc định (`CUSTOMER_EMAIL_ENABLED=true`), trong khi tài liệu ghi "giai đoạn sau" |
| **Báo cáo (17 §4.11)** | Khung đã khớp. Đổi công thức doanh thu sang trước thuế gồm dòng phí, xếp hạng SKU theo doanh thu |

Quy tắc bắt buộc: mọi thay đổi `identity.role_permission` phải **tăng `app_role.version` trong cùng migration**.

---

## 9. Quyết định cần bạn duyệt

### 9.1 Quyết định chính (cần bạn chọn)

| # | Quyết định | Đề xuất của mình | Phương án khác |
|---|---|---|---|
| Q1 | Thứ tự làm | Theo §7: dọn nhóm A/B trước (đợt 1–8), nhóm C chỉ khi có cái thay thế. Mỗi đợt build + test xanh | Xây chức năng mới trước rồi mới dọn: lâu thấy kết quả, code thừa cản việc xây |
| Q2 | Dữ liệu trên VPS (`develop` tự deploy với profile `prod`) | Nếu chỉ là dữ liệu test: **reset sạch** rồi nạp seed mới. Migration vẫn viết đủ bước archive để môi trường nào có dữ liệu cũng chạy an toàn | Chuyển đổi dữ liệu cũ: rất tốn công (tồn là hàng lẻ, không lô, nhiều trạng thái bị bỏ) |
| Q3 | Đơn sản xuất mới ở đâu | Trong module **`order`**: tránh vòng phụ thuộc với đơn bán | Module riêng mỏng: phải nuôi bản sao dữ liệu qua event như cũ |
| Q4 | Bỏ PO khi nào | **Giữ PO làm nguồn nhập kho tạm** cho tới khi có Đơn sản xuất (nhóm C, đợt 9a) | Bỏ PO ngay sau sản xuất: gọn sớm, nhưng hệ thống **không nhập được hàng** cho tới khi có Đơn sản xuất (chỉ còn nhập hàng hoàn + điều chỉnh tồn) |
| Q5 | Email cho khách (đơn, trả hàng; 12 handler) | **Giữ code, tắt mặc định**: tài liệu ghi "phạm vi giai đoạn sau", không phải "bỏ" | Xoá (8 file, khoảng 1,4 nghìn dòng); sau này cần thì viết lại |
| Q6 | Ảnh nhận hàng / QC vừa làm ở nhánh S3 | **Bỏ cả hai giai đoạn**: chương 03 không có ảnh, QC bị bỏ | Giữ ảnh lúc nhận làm bằng chứng thiếu/thừa (rẻ, nhưng ngoài tài liệu) |
| Q7 | Người đang mang 5 role bị bỏ | Theo §8.1: chuyển sang role tương ứng; nhân viên mua hàng / sản xuất thì vô hiệu hoá | Đặt role thành không-hệ-thống rồi để System Admin tự dọn sau |
| Q8 | Chuyển mọi sản phẩm `STANDARD` → `CUSTOMIZABLE` | **Làm sau** khi có SKU riêng của khách (kế hoạch Design Studio): 94 file test đang dựa trên 2 sản phẩm demo `STANDARD` | Làm ngay: phải dựng lại dữ liệu test cho 94 file |

### 9.2 Quyết định phụ

Mình làm theo đề xuất này nếu bạn không có ý kiến khác.

| # | Vấn đề | Đề xuất |
|---|---|---|
| P1 | Hằng enum mà dữ liệu lịch sử còn mang (sổ cái tồn, lịch sử đơn) | Giữ ở dạng chỉ-đọc, `@Deprecated`, không ghi mới |
| P2 | Tài liệu trong repo BE (`docs/business-design`, `CLAUDE.md` §2/§4, `README`, ADR 0005, `docs/adding-a-module.md`) | Đánh dấu "đã thay thế", trỏ sang kltn-docs. Viết lại các mục `CLAUDE.md` mô tả luồng đã bỏ. Thêm một ADR ghi lại đợt đổi phạm vi |
| P3 | Mâu thuẫn tài liệu: 17 §4.2 bước 3 ("chọn đủ N thùng LPN" khi `Confirmed`) với 20 §5.6 (giữ theo SKU, phân bổ LPN khi release) | Theo **20 §5.6**: khớp code hiện tại. Báo người viết tài liệu sửa 17 |
| P4 | Giữ hàng hết hạn sau 30 phút (chỉ dành cho checkout) | Bỏ: tài liệu ghi giữ hàng "không có hạn" |
| P5 | Bảng chưa có code `warehouse.move_task`, `slotting_rule` (phạm vi sau) | Giữ bảng; chỉ bỏ phần QC của `move_task` |
| P6 | Quyền thừa mà Sales Manager thừa hưởng từ E-commerce Admin: `design-administration` (thiết kế hộ, đã bỏ), khuyến mãi | Thu hồi. Giữ `platform-actuator` |
| P7 | Cơ chế gửi email PO (nhật ký gửi, retry) | Giữ phần chung (transport, nhật ký, retry) để dùng lại cho **gửi Đơn sản xuất** cho đơn vị sản xuất; xoá phần riêng của PO |
| P8 | Thông báo gửi cho ai | Theo **người giữ quyền** (quyết định 22/10), không theo tên role |
| P9 | Phạm vi dữ liệu của role Customer: DB là `ALL` + kiểm chủ sở hữu ở từng handler; tài liệu ghi `OWN` | Giữ cách làm hiện tại, ghi chú vào tài liệu |
| P10 | Đổi tên module `procurement` → `receiving` | Làm ở đợt 9a, sau khi đã dọn xong; giữ mã quyền `procurement-goods-receipts` để khỏi phải chuyển quyền |

---

## 10. Front-end ([07-frontend.md](2026-11-07-rescope/07-frontend.md))

Hai repo FE dọn **song song theo từng đợt BE**: đợt BE nào xoá endpoint thì FE gỡ màn hình gọi endpoint đó trong cùng đợt, để không có màn hình gọi vào API đã mất.

### 10.1 Backoffice (`develop` @ `6e6a0de6`): xoá khoảng 596 file / 67 nghìn dòng

| Khái niệm bị bỏ | Thư mục / route xoá hẳn | Màn hình giữ nhưng phải sửa |
|---|---|---|
| Mua hàng NCC | `purchase-order` (118 file), `supplier` (78), `invoice` (7), trang `replenishment` + route `admin/purchase-orders`, `suppliers`, `invoices`. Tổng **267 file / ~30,6 nghìn dòng** | Phiếu nhận hàng (tạo từ PO, nhận không PO); tab mua hàng ở biến thể sản phẩm |
| Sản xuất cũ, mẫu, gia công | `production-order` (56), `sample-request` (22) + route `production-orders`, `workshop`, `sample-requests`, portal `samples`. Tổng **101 file / ~8,7 nghìn dòng** | Danh sách lệnh sản xuất trên chi tiết đơn; cổng mẫu ở báo giá (cả portal); nút mẫu ở khách hàng |
| QC tại kho | 10 file QC ở phiếu nhận + mock | Phiếu nhận: dialog QC, trạng thái "In QC" |
| Chuyển kho | `transfer` (54) + đóng gói chuyển kho (41) + route. Tổng **123 file / ~14,4 nghìn dòng** | Nhánh chuyển kho ở kế hoạch xe, giao hàng |
| Cảnh báo tồn, hạn dùng | Trang cảnh báo tồn + widget dashboard | Cột hạn dùng, cấu hình FEFO / ngưỡng ở hàng tồn kho |
| Đóng gói lại, kiểm checksum thiết kế, kit | Trạm đóng gói cũ: 69 file / ~10 nghìn dòng (giữ menu "Đóng gói", **viết lại thành "Kiểm thùng & dán nhãn"**) | Nút "kiểm lại thiết kế" ở tác vụ lấy hàng |
| Gửi kiện, COD, chọn cách giao | 12 file | Mở chuyến kiện; COD ở chuyến / phiếu giao / bàn giao; chọn cách giao ở release đơn |
| Điều khoản thanh toán, cọc | 3 file | Thẻ tài chính đơn, onboarding khách (đổi thành lịch thanh toán mặc định), báo giá |
| Lớp lưu trữ, sức chứa theo đơn vị, khu `PACKING` / QC / `PRODUCTION` | — | Bản đồ kho (khoảng 25 file); đổi nhãn `DISPATCH` thành "Khu xuất hàng" |
| Sản phẩm `STANDARD`, giá gốc | 1 file | Form sản phẩm; giá gốc ở biến thể |
| Role | — (FE kiểm theo mã quyền, không theo tên role) | Mock và test role; `lib/auth/roles.ts` chưa có Sales Manager |

**Bẫy khi xoá ở backoffice:**
- **Helper mock dùng chung nằm trong file mock của PO:** `beOk`, `beError`, `bePage`… đang được khoảng 33 file mock khác dùng, gồm cả phần giữ lại. Phải **chuyển ra file chung trước** khi xoá mock PO.
- **Màn hình lấy hàng (giữ) đang import** `ReverifyDialog` của trạm đóng gói cũ.
- **`releaseOrder` (giữ) nằm trong file** `delivery-mode-api.ts` (bỏ). Dialog release đang nhúng ô chọn cách giao.
- **`SHARED_QUERY_ROOTS.skuPurchasing` vẫn còn được gọi** từ code giữ lại (hàng tồn kho, biến thể).
- **Xoá sản xuất làm `LocationField` thành component mồ côi.**

### 10.2 Storefront (`dev` @ `556aa57`): xoá khoảng 66 file / 16 nghìn dòng

| Khái niệm bị bỏ | Xoá | Sửa |
|---|---|---|
| Khách tự đặt hàng | `features/cart` (32 file) + route `cart`, `checkout`, store giỏ hàng: **40 file / ~4,4 nghìn dòng** | Icon giỏ ở header, liên kết giỏ ở menu/footer, phần gắn giỏ khi đăng nhập/đăng xuất. Nút **"Đặt lại" chỉ mở chat với Sale** (13 §4.6.3, 14 §4.1 bước 6): bỏ cả nhánh giỏ lẫn nhánh tự sinh báo giá `POST /orders/{id}/reorder` |
| Giá trên sàn | `price-tiers`, `price-display` | Trang sản phẩm: bỏ giá / bậc giá / MOQ / "Thêm vào giỏ", giữ "Bắt đầu thiết kế"; lưới catalog bỏ "Từ … đ" |
| Điều khoản thanh toán, QR/Visa/COD | `lib/payment-terms` | Thẻ thanh toán của đơn → đợt thanh toán của hợp đồng. Thẻ hạn mức (giữ) đang đọc từ `payment-terms`. Footer đang ghi "VietQR, Visa, Mastercard, COD" |
| Luồng thiết kế cũ | 4 file studio (chốt / yêu cầu sửa / thẻ quy trình) | `lifecycle`, tab "Chờ bạn xác nhận", chữ "QC"; nút "Yêu cầu báo giá" phải gọi API thật (kế hoạch Design Studio) |
| Code backoffice sót lại | `lib/mock-data.ts` (bản sao 10 nghìn dòng, giữ `staffUsers`), các route mock PO / NCC / hoá đơn / chuyển kho, `lib/domain`, `lib/references` | — |

### 10.3 Câu hỏi FE chờ BE trả lời

| Câu hỏi | Trả lời khi |
|---|---|
| Hướng dẫn chuyển khoản theo đợt thay cho theo đơn | Đợt 9b |
| Catalog còn chỉ báo còn hàng không | Theo SKU riêng của khách (13 BR-02) |
| Endpoint hàng chờ của "kiểm thùng & dán nhãn" | Đợt 7b |
| Từ chối tín dụng làm gì khi không còn điều khoản thanh toán | Đợt 9b |
| Thẻ hạn mức ở storefront đọc từ đâu | Đợt 9b |

---

## 11. Rủi ro còn lại

- **Phạm vi rất lớn:** khoảng 25% code chính. Phải làm theo đợt nhỏ và chạy đủ test sau mỗi đợt; không gộp nhiều mảng vào một PR.
- **Các nhánh đang mở của team** đụng vào phần bị bỏ sẽ xung đột hoặc trở nên vô nghĩa. Cần báo team trước đợt 1.
- **Phụ thuộc chéo giữa các đợt:** 4b phải trước 7a (chuyển kho trong kế hoạch xe); 3 phải trước 5 (gia công); nhóm C phụ thuộc chức năng mới. Thứ tự ở §7 đã tính các điểm này; lệch thứ tự thì build có thể đỏ.
- **Tài liệu còn chỗ chưa chốt** (dấu ⚠️ giả định trong kltn-docs). Một số câu hỏi mở có thể làm đổi chi tiết, nhưng không đổi danh sách bỏ ở §1.
