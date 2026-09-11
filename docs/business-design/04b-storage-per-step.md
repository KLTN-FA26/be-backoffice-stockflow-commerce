# Storage per step

Cùng bốn luồng ở `04-data-flows.md`, nhưng nhìn từ góc khác: **sau mỗi bước, dòng dữ liệu nằm ở
bảng nào trong database nào.**

Ký hiệu: `+` tạo dòng mới · `~` cập nhật · `−` trừ đi hoặc xoá ·
`⇢` event qua Kafka · `→` gọi REST đồng bộ.

Mọi mũi tên `⇢` đều đi qua transactional outbox — xem ADR-0003. Không bước nào gửi thẳng
message ra Kafka.

---

## Luồng B — Bán hàng

Mười chín bước, sáu service, sáu database. Luồng dài nhất và là kịch bản demo lúc bảo vệ.

### 01. design-service — Khách hàng

`POST /api/v1/design/drafts`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_design` | `+` | `design_draft` | status = DRAFT, canvas JSON |
| `MinIO` | `+` | `design-assets/{id}/...` | ảnh khách upload |

### 02. design-service — hệ thống

`POST /api/v1/design/drafts/{id}/preflight`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_design` | `~` | `design_draft` | preflight_result = PASS | FAIL + danh sách lỗi |

> ⚠ Preflight **FAIL** thì dừng ở đây. Không snapshot nào được tạo, không đơn nào đi tiếp — BR-DSG-001.

### 03. design-service — Khách hàng

`POST /api/v1/design/drafts/{id}/confirm`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_design` | `+` | `design_snapshot` | status = CONFIRMED, checksum SHA-256 |
| `MinIO` | `+` | `design-snapshots/{id}.json` | artifact bất biến |
| `sf_design` | `+` | `outbox_event` | DesignConfirmed |

> **⇢ event** — stockflow.design.events — order-service nghe để biết snapshot đã sẵn sàng

### 04. order-service — Khách hàng

`POST /api/v1/cart/items`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_order` | `+` | `cart_item` | sku, quantity, design_snapshot_id (nếu là hàng in) |
| `Redis` | `~` | `cart:{sessionId}` | cache giỏ hàng, TTL 30 ngày |

### 05. order-service — hệ thống

`POST /api/v1/cart/price`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `Redis` | `~` | `cart:{sessionId}` | tổng tiền, thuế, phí ship đã tính |

> **→ đồng bộ** — GET /api/v1/catalog/products/{slug} — lấy giá hiện hành

### 06. inventory-service — order-service gọi

`POST /api/v1/inventory/reservations`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_inventory` | `+` | `stock_reservation` | status = HELD, expires_at = now + 15 phút |
| `sf_inventory` | `~` | `stock_item` | reserved += qty |
| `sf_inventory` | `+` | `processed_event` | chống trùng theo Idempotency-Key |
| `sf_inventory` | `+` | `outbox_event` | StockReserved |

> **→ đồng bộ** — order-service CHỜ kết quả này. Không đủ ATP thì checkout hỏng ngay, chưa có đơn nào được tạo.

> ⚠ Đây là chỗ duy nhất trong luồng bán hàng gọi **đồng bộ**. Lý do: khách đang đứng chờ trước màn hình, không thể trả lời 'để tí nữa em báo'.

### 07. order-service — Khách hàng

`POST /api/v1/checkout/place-order`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_order` | `+` | `order` | status = PENDING_PAYMENT |
| `sf_order` | `+` | `order_line × N` | CHÉP tên SP, đơn giá, địa chỉ tại thời điểm này |
| `sf_order` | `+` | `order_status_history` | → PENDING_PAYMENT |
| `sf_order` | `−` | `cart_item` | giỏ hàng được dọn |
| `sf_order` | `+` | `outbox_event` | OrderPlaced |

> **⇢ event** — stockflow.order.events — payment, notification, reporting cùng nghe

> ⚠ **Chép, không trỏ.** Giá đổi ngày mai không được phép làm đổi hoá đơn hôm nay — BR-ORD-001 và ADR-0002.

### 08. payment-service — consumer: OrderPlaced

`POST /api/v1/payments`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_payment` | `+` | `processed_event` | eventId của OrderPlaced |
| `sf_payment` | `+` | `payment` | status = INITIATED, method, amount |

### 09. payment-service — Cổng thanh toán

`POST /api/v1/webhooks/payments/{gateway}`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_payment` | `~` | `payment` | status = AUTHORIZED → CAPTURED |
| `sf_payment` | `+` | `payment_transaction` | raw payload của gateway, để đối soát |
| `sf_payment` | `+` | `outbox_event` | PaymentCaptured |

> **⇢ event** — stockflow.payment.events — order và inventory cùng nghe

> ⚠ Chữ ký không hợp lệ thì **không đổi trạng thái gì cả**, chỉ ghi log — BR-PAY-001.

### 10. order-service — consumer: PaymentCaptured

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_order` | `+` | `processed_event` |  |
| `sf_order` | `~` | `order` | status = PENDING_PAYMENT → CONFIRMED, paid_at |
| `sf_order` | `+` | `order_status_history` | → CONFIRMED |
| `sf_order` | `+` | `outbox_event` | OrderConfirmed |

> **⇢ event** — notification-service gửi mail xác nhận cho khách

### 11. order-service — Điều phối đơn

`POST /api/v1/orders/{id}/release`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_order` | `~` | `order` | status = CONFIRMED → READY_TO_FULFILL, warehouse_code |
| `sf_order` | `+` | `order_status_history` | → READY_TO_FULFILL |
| `sf_order` | `+` | `outbox_event` | OrderReleased |

> **⇢ event** — inventory-service và fulfillment-service cùng nghe

### 12. inventory-service — consumer: OrderReleased

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_inventory` | `+` | `processed_event` |  |
| `sf_inventory` | `+` | `stock_allocation` | gắn vào location + lot cụ thể, chọn theo FEFO |
| `sf_inventory` | `~` | `stock_reservation` | status = HELD → ALLOCATED |
| `sf_inventory` | `~` | `stock_item` | reserved −= qty, allocated += qty |
| `sf_inventory` | `+` | `outbox_event` | StockAllocated |

> ⚠ Từ giây phút này reservation **hết hạn không còn ý nghĩa** — allocation không có expires_at.

### 13. fulfillment-service — consumer: OrderReleased

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_fulfillment` | `+` | `processed_event` |  |
| `sf_fulfillment` | `+` | `pick_list` | status = CREATED |
| `sf_fulfillment` | `+` | `pick_line × N` | location_code, lot_number lấy từ allocation |
| `sf_fulfillment` | `+` | `outbox_event` | PickListGenerated |

### 14. fulfillment-service — NV kho (mobile)

`POST /api/v1/fulfillment/pick-lines/{id}/confirm`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_fulfillment` | `~` | `pick_line` | picked_qty, picked_at, picked_by |
| `sf_fulfillment` | `~` | `pick_list` | status → COMPLETED khi hết dòng |
| `sf_fulfillment` | `+` | `outbox_event` | PickCompleted |

> **⇢ event** — stockflow.fulfillment.events — inventory nghe để trừ kho thật

> ⚠ Nhặt thiếu thì dòng đó → **SHORT**, đơn → ON_HOLD(INVENTORY_ISSUE). Đơn không hỏng, nhưng dừng lại chờ người xử lý — WBS 3.7.5.1.

### 15. inventory-service — consumer: PickCompleted

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_inventory` | `+` | `processed_event` |  |
| `sf_inventory` | `~` | `stock_item` | on_hand −= qty, allocated −= qty |
| `sf_inventory` | `~` | `stock_allocation` | status = CONSUMED |
| `sf_inventory` | `+` | `stock_movement` | bút toán kho, không bao giờ sửa hay xoá |
| `sf_inventory` | `+` | `outbox_event` | StockDeducted |

> **⇢ event** — catalog cập nhật ATP hiển thị, reporting tính vòng quay tồn

> ⚠ **Đây mới là lúc tồn kho thật sự giảm.** Chín bước trước đó chỉ giữ chỗ.

### 16. fulfillment-service — NV kho

`POST /api/v1/packing/{orderId}/design-check`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_fulfillment` | `+` | `design_verification` | checksum trên order_line vs artifact trong MinIO |

> **→ đồng bộ** — GET /api/v1/design/snapshots/{id}/verify

> ⚠ Lệch checksum thì **dừng đóng gói**, đơn → ON_HOLD. Nghĩa là kiện hàng không chứa đúng thứ khách đã duyệt — BR-DSG-003.

### 17. fulfillment-service — NV kho

`POST /api/v1/packing/{orderId}/complete`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_fulfillment` | `+` | `package` | weight, dimensions, carton_type |
| `sf_fulfillment` | `+` | `outbox_event` | OrderPacked |

> **⇢ event** — order-service cập nhật trạng thái

### 18. fulfillment-service — NV kho

`POST /api/v1/shipping/shipments/{id}/label`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_fulfillment` | `+` | `shipment` | status = LABELLED, tracking_number, AWB |
| `sf_fulfillment` | `+` | `manifest_entry` | gom vào manifest trong ngày |
| `sf_fulfillment` | `+` | `outbox_event` | ShipmentDispatched |
| `MinIO` | `+` | `labels/{awb}.pdf` | file nhãn vận đơn |

> **⇢ event** — order → SHIPPED, notification gửi mã vận đơn cho khách

### 19. fulfillment-service — Webhook hãng vận chuyển

`POST /api/v1/webhooks/carriers/{carrier}`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_fulfillment` | `~` | `shipment` | tracking_status đã chuẩn hoá về máy trạng thái của mình |
| `sf_fulfillment` | `+` | `tracking_event` | nhật ký thô từ hãng vận chuyển |
| `sf_fulfillment` | `+` | `pod` | ảnh và chữ ký khi giao thành công |
| `sf_fulfillment` | `+` | `outbox_event` | OrderDelivered |

> **⇢ event** — order → DELIVERED; sau 7 ngày không có RMA thì → COMPLETED


---

## Luồng A — Nhập kho

Mười ba bước. Chú ý bước 12: trước đó hàng đã tồn tại trong hệ thống nhưng chưa bán được.

### 01. procurement-service — NV mua hàng

`POST /api/v1/purchase-orders`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_procurement` | `+` | `purchase_order` | status = DRAFT, supplier_id, warehouse_code |
| `sf_procurement` | `+` | `po_line × N` | sku, quantity_ordered, unit_price |

### 02. procurement-service — Người duyệt

`POST /api/v1/purchase-orders/{id}/approve`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_procurement` | `~` | `purchase_order` | status → APPROVED, approved_by, approved_at |
| `sf_procurement` | `+` | `po_approval` | dấu vết của từng cấp duyệt |
| `sf_procurement` | `+` | `outbox_event` | PurchaseOrderApproved |

> ⚠ Hạn mức người duyệt phải ≥ giá trị PO — BR-PO-002. Kiểm trong aggregate, không kiểm ở controller.

### 03. procurement-service — NV mua hàng

`POST /api/v1/purchase-orders/{id}/send`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_procurement` | `~` | `purchase_order` | status → SENT, sent_at |
| `sf_procurement` | `+` | `outbox_event` | PurchaseOrderSent |

> **⇢ event** — notification-service gửi PO cho nhà cung cấp

### 04. procurement-service — NV kho

`POST /api/v1/goods-receipts/from-po/{poId}`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_procurement` | `+` | `goods_receipt` | status = DRAFT, tham chiếu PO |
| `sf_procurement` | `+` | `receipt_line × N` | quantity_expected chép từ PO |

### 05. procurement-service — NV kho (mobile)

`PUT /api/v1/receipt-lines/{id}/lot`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_procurement` | `~` | `receipt_line` | quantity_received, lot_number, expiry_date, serials |
| `MinIO` | `+` | `receipts/{id}/photo-*.jpg` | ảnh bằng chứng nhận hàng |

> ⚠ SKU có theo dõi lô mà thiếu lot_number thì **không đóng được bước đếm** — BR-RCP-003.

### 06. procurement-service — hệ thống

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_procurement` | `+` | `qc_task` | status = PENDING, cho SKU có cờ QC |

> **⇢ event** — QcTaskCreated — hiện lên hàng đợi của QC

### 07. procurement-service — NV QC

`POST /api/v1/qc-tasks/{id}/accept`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_procurement` | `+` | `qc_result` | ACCEPTED | QUARANTINED | REJECTED |
| `sf_procurement` | `~` | `goods_receipt` | status → QC_PASSED |
| `MinIO` | `+` | `qc/{id}/evidence-*.jpg` | ảnh kiểm tra |

> ⚠ REJECTED thì đi nhánh RTV và **không dòng tồn kho nào được tạo** — WBS 3.3.4.4.

### 08. procurement-service — NV kho

`POST /api/v1/goods-receipts/{id}/post`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_procurement` | `~` | `goods_receipt` | status → POSTED, posted_at |
| `sf_procurement` | `~` | `purchase_order` | open_quantity tính lại → PARTIALLY_RECEIVED | RECEIVED |
| `sf_procurement` | `+` | `outbox_event` | GoodsReceived |

> **⇢ event** — stockflow.procurement.events — inventory và warehouse cùng nghe

> ⚠ Nhận vượt quá dung sai (mặc định 5%) thì **không post được** nếu chưa có duyệt của quản lý kho — BR-RCP-001.

### 09. inventory-service — consumer: GoodsReceived

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_inventory` | `+` | `processed_event` |  |
| `sf_inventory` | `+` | `stock_item` | location = khu vực nhận hàng, condition = GOOD hoặc QUARANTINE |
| `sf_inventory` | `+` | `stock_movement` | bút toán nhập |
| `sf_inventory` | `+` | `outbox_event` | StockReceived |

> ⚠ Hàng ở khu nhận hàng **chưa nhặt được**. Phải qua putaway mới vào kệ thật.

### 10. warehouse-service — consumer: GoodsReceived

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_warehouse` | `+` | `processed_event` |  |
| `sf_warehouse` | `+` | `putaway_task` | status = CREATED |
| `sf_warehouse` | `+` | `putaway_suggestion × N` | vị trí xếp hạng + LÝ DO của từng gợi ý |

> ⚠ Vị trí vi phạm ràng buộc cứng bị **loại hẳn** trước khi chấm điểm, không phải bị hạ hạng — BR-SLT-001.

### 11. warehouse-service — NV kho (mobile)

`POST /api/v1/putaway-tasks/{id}/confirm`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_warehouse` | `~` | `putaway_task` | status → COMPLETED, actual_location, completed_by |
| `sf_warehouse` | `~` | `location` | occupied_weight +=, occupied_volume += |
| `sf_warehouse` | `+` | `outbox_event` | PutawayCompleted |

> **⇢ event** — inventory-service dời hàng khỏi khu nhận hàng

### 12. inventory-service — consumer: PutawayCompleted

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_inventory` | `~` | `stock_item` | location_code = vị trí thật trên kệ |
| `sf_inventory` | `+` | `stock_movement` | bút toán chuyển vị trí |
| `sf_inventory` | `+` | `outbox_event` | StockLevelChanged |

> **⇢ event** — catalog cập nhật ATP — <b>đây là lúc hàng bắt đầu bán được</b>

### 13. procurement-service — Kế toán

`POST /api/v1/supplier-invoices/{id}/match`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_procurement` | `+` | `supplier_invoice` | status = MATCHING |
| `sf_procurement` | `+` | `match_result` | so PO ↔ Receipt ↔ Invoice theo dung sai |
| `sf_procurement` | `~` | `purchase_order` | status → CLOSED khi đã duyệt chi |

> ⚠ Ngoài dung sai thì → EXCEPTION, phải người có hạn mức ≥ chênh lệch mới override được — BR-INV-003.


---

## Luồng C — Điều chuyển kho

Sáu bước, tất cả trong một database. Ngắn nhất nhưng dễ làm hỏng dữ liệu tồn kho nhất.

### 01. inventory-service — Kế hoạch tồn kho

`POST /api/v1/transfers`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_inventory` | `+` | `transfer_order` | status = DRAFT, source_wh, dest_wh |
| `sf_inventory` | `+` | `transfer_line × N` | sku, quantity_requested |

### 02. inventory-service — Quản lý kho

`POST /api/v1/transfers/{id}/approve`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_inventory` | `~` | `transfer_order` | status → APPROVED |
| `sf_inventory` | `+` | `transfer_approval` | dấu vết duyệt theo ngưỡng giá trị |

### 03. inventory-service — NV kho nguồn

`POST /api/v1/transfers/{id}/pick`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_inventory` | `+` | `stock_reservation` | giữ chỗ tại kho nguồn, chọn theo FEFO |
| `sf_inventory` | `~` | `stock_item` | reserved += qty tại kho nguồn |

### 04. inventory-service — NV kho nguồn

`POST /api/v1/transfers/{id}/issue`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_inventory` | `~` | `stock_item` | on_hand −= qty, reserved −= qty  (kho NGUỒN) |
| `sf_inventory` | `+` | `in_transit_stock` | sở hữu bởi transfer_order, không thuộc kho nào |
| `sf_inventory` | `+` | `stock_movement` | bút toán xuất |
| `sf_inventory` | `+` | `outbox_event` | TransferInTransit |

> ⚠ **Đây là chỗ dữ liệu dễ hỏng nhất trong toàn hệ thống.** Để hàng lại ở on_hand kho nguồn là kho nguồn bán vượt; cộng sớm vào kho đích là kho đích bán hàng đang nằm trên xe tải.

### 05. inventory-service — NV kho đích

`POST /api/v1/transfers/{id}/receive`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_inventory` | `~` | `transfer_line` | quantity_received |
| `sf_inventory` | `−` | `in_transit_stock` | xoá khỏi bucket trung chuyển |
| `sf_inventory` | `+` | `stock_item` | tạo tại kho ĐÍCH, condition = GOOD |
| `sf_inventory` | `+` | `stock_movement` | bút toán nhập |
| `sf_inventory` | `~` | `transfer_order` | status → COMPLETED |

### 06. inventory-service — Quản lý kho

`POST /api/v1/transfers/{id}/resolve-discrepancy`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_inventory` | `+` | `stock_adjustment` | ghi nhận hao hụt, cần duyệt |
| `sf_inventory` | `~` | `transfer_order` | DISCREPANCY → COMPLETED |

> ⚠ Bất biến phải đúng ở **mọi thời điểm**: `on_hand(nguồn) + in_transit + on_hand(đích) = hằng số`. Nên viết hẳn một integration test khẳng định điều này — BR-TRF-002.


---

## Luồng D — Trả hàng

Tám bước. Khác luồng nhập kho thường ở chỗ hàng trả về luôn vào trạng thái cách ly.

### 01. order-service — Khách hàng

`POST /api/v1/rma`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_order` | `+` | `rma` | status = REQUESTED, reason |
| `sf_order` | `+` | `rma_line × N` | order_line_id, quantity |
| `sf_order` | `~` | `order` | status → RETURN_REQUESTED |
| `sf_order` | `+` | `outbox_event` | RmaRequested |

### 02. order-service — NV kinh doanh

`POST /api/v1/rma/{id}/approve`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_order` | `~` | `rma` | status → APPROVED, approved_by |

> ⚠ Hàng in theo yêu cầu **không được trả** trừ khi lỗi — BR-RMA-002. Cái cốc in ảnh của khách không bán lại cho ai được.

### 03. fulfillment-service — hệ thống

`POST /api/v1/rma/{id}/return-label`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_fulfillment` | `+` | `return_shipment` | tracking_number chiều về |
| `MinIO` | `+` | `labels/return-{rma}.pdf` | nhãn gửi cho khách |

### 04. procurement-service — NV kho

`POST /api/v1/rma/{id}/receive`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_procurement` | `+` | `return_receipt` | đối chiếu số RMA trên kiện hàng |
| `sf_procurement` | `+` | `qc_task` | status = PENDING |

> **⇢ event** — RmaGoodsReceived — inventory tạo tồn ở trạng thái cách ly

### 05. inventory-service — consumer: RmaGoodsReceived

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_inventory` | `+` | `stock_item` | condition = QUARANTINE, KHÔNG BAO GIỜ là GOOD |
| `sf_inventory` | `+` | `stock_movement` | bút toán nhập hàng trả |

> ⚠ Hàng trả về có bán lại được hay không là **quyết định của QC**, không phải mặc định của hệ thống.

### 06. procurement-service — NV QC

`POST /api/v1/rma/{id}/inspect`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_procurement` | `+` | `qc_result` | ACCEPTED → hàng sang GOOD; REJECTED → DAMAGED |
| `sf_procurement` | `+` | `outbox_event` | RmaInspected |

### 07. payment-service — Kế toán

`POST /api/v1/refunds/{id}/approve`

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_payment` | `+` | `refund` | amount, method |
| `sf_payment` | `~` | `payment` | status → PARTIALLY_REFUNDED | REFUNDED |
| `sf_payment` | `+` | `outbox_event` | RefundCompleted |

> ⚠ Vượt hạn mức người duyệt thì **không hoàn được**, phải chuyển lên cấp trên — BR-PAY-003.

### 08. order-service — consumer: RefundCompleted

| Nơi lưu | | Bảng | Ghi gì |
|---|---|---|---|
| `sf_order` | `~` | `rma` | status → REFUNDED |
| `sf_order` | `~` | `order` | status → RETURNED |
| `sf_order` | `+` | `order_status_history` | → RETURNED |


---

## Quy ước lưu trữ

**Chỉ ghi thêm, không bao giờ UPDATE hay DELETE**

`stock_movement`, `order_status_history`, `payment_transaction`, `tracking_event`, `qc_result`,
`outbox_event`, `audit_log`. Sai thì ghi một dòng đảo ngược, không sửa dòng cũ. Đây là cách duy
nhất trả lời được câu "tối thứ ba tuần trước tồn kho là bao nhiêu".

**Bất biến sau khi tạo**

`design_snapshot` và artifact JSON của nó, `order_line` sau khi đơn rời trạng thái CONFIRMED, và
mọi dòng chứng từ đã post. Muốn đổi thì tạo phiên bản mới và trỏ lại, không sửa tại chỗ.

**Sửa thoải mái**

`stock_item` (on_hand, reserved, allocated), `cart_item`, `design_draft` khi còn DRAFT, và các
bảng cấu hình. Nhưng mọi thay đổi số lượng tồn **đều phải kèm một dòng `stock_movement`** — một
con số đổi mà không có bút toán giải thích là dấu hiệu của bug.

**Job đối chiếu nên có**

Tổng các dòng `stock_movement` của một SKU tại một vị trí phải bằng đúng `stock_item.on_hand`
hiện tại. Chạy hằng đêm; nó phát hiện sai sót trước khi kiểm kê thực tế phát hiện.
