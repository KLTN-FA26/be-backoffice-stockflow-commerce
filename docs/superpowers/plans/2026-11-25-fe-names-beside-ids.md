# Brief FE: BE trả kèm tên, mã, số cạnh ID

Giao cho session làm ở repo FE back-office (`fe-backoffice-stockflow-commerce`) và storefront. Đây là câu trả lời của BE cho bản
phân tích "Những chỗ BE chỉ trả ID, FE thiếu dữ liệu để hiển thị" (2026-11-19). Mục A1…B4 trong brief này theo đúng số mục của
bản phân tích đó.

## Quy ước chung

- **Chỉ thêm trường.** Không trường cũ nào bị bỏ, đổi tên hay đổi kiểu. FE cũ vẫn chạy, FE chỉ cần đọc thêm trường mới rồi bỏ
  đường vòng (tải danh sách kho, gọi `GET /customers/{id}` hay `GET /orders/{id}` cho từng dòng).
- **Trường null bị bỏ khỏi JSON** (Jackson `NON_NULL`). Với FE, "null" ở brief này nghĩa là trường không có mặt; kiểm tra
  bằng `x?.name ?? fallback`.
- **Người làm.** Mỗi trường UUID `xxxBy` có thêm `xxxByName`; `actorId` có thêm `actorName`; `assignedUserId` có thêm
  `assignedUserName`. Giá trị là họ tên, chưa có họ tên thì là username. Null khi id null hoặc tài khoản không còn.
- **Kho.** Cạnh `warehouseId` có `warehouseCode` (tiền tố kho, ví dụ `HCM`) và `warehouseName`. Người không có quyền
  `warehouse-warehouses:READ` vẫn thấy tên kho, nên **`useWarehouseDirectory` không còn cần cho việc hiện tên**.
- **Khách hàng.** Cạnh `customerId` có `customerName` và `customerCode` (mã số thuế, hoặc `KH` + 8 ký tự của id).
- **Đơn.** Cạnh `orderId` có `orderNumber` (tương tự `convertedOrderNumber`, `salesOrderNumber`).
- **Bản cho khách** (storefront): nơi nào BE vốn giấu id nhân viên khỏi bản của khách thì tên nhân viên cũng bị giấu.
- BE tra tên theo lô cho cả trang, nên FE không phải lo N+1.

---

## A. Back-office

### A1. Sản phẩm

| API | DTO | Trường mới | FE sửa |
| --- | --- | --- | --- |
| `GET /products`, `GET /products/{id}` và mọi API ghi trả `ProductResponse` | `ProductResponse` | `categoryName` | `ProductInfoSections`, `ProductList`: bỏ tra danh sách danh mục, hiện `categoryName` |
| như trên | `ProductResponse` | `submittedByName`, `approvedByName` | Dòng "ID người duyệt" hiện `approvedByName` |
| như trên | `ProductResponse` | `canApprove: boolean` (luôn có) | `ProductActionCard`: dùng `canApprove` thay cho so `submittedBy` với id người đăng nhập. True khi sản phẩm `PENDING_APPROVAL`, người gọi có `product-products:APPROVE` và (không phải người gửi duyệt, hoặc có `product-lifecycle:APPROVE`) |
| `…/variants/{variantId}/media` (đọc, ghi, publish, withdraw) | `MediaResponse` | `uploadedByName`, `publishedByName` | `VariantMediaGallery`: hiện tên, không cần so cả username lẫn id. `uploadedByName` là họ tên khi cột lưu id, còn không thì là username |
| `GET /products/{id}/versions` | `ProductVersionResponse` | `subjectSku`, `subjectLabel` | `ProductHistory`: bỏ tra danh sách biến thể. `subjectLabel` là tên biến thể hoặc tên file ảnh; cả hai null với dòng của chính sản phẩm và với đối tượng đã xóa |

### A2. Tồn kho

| API | DTO | Trường mới | FE sửa |
| --- | --- | --- | --- |
| `GET /inventory/adjustments`, `/{id}` và các API duyệt, từ chối, rút | `StockAdjustmentResponse` | `requestedByName`, `decidedByName`, `countedByName` | `AdjustmentDetail`: "Người yêu cầu", "Người duyệt" hiện tên thay UUID |
| `GET /inventory/reservations`, `/{id}` | `StockReservationRow` | `orderNumber` | `ReservationsTable`: cột "Đơn" hiện `orderNumber` |
| `GET /inventory/cycle-counts` | `CycleCountSummaryResponse` | `assignedUserName`, `warehouseCode`, `warehouseName` | |
| `GET /inventory/cycle-counts/{id}` và các API ghi | `CycleCountResponse` | `assignedUserName`, `warehouseCode`, `warehouseName`; `lines[].countedByName` | `CycleCountHeader`: hiện tên người được giao (vẫn có thể giữ chữ "Giao cho bạn") |
| `GET /inventory/movements` | `StockMovementResponse` | `actorName`, `requestedByName`, `countedByName` | Sổ biến động: hiện người thực hiện |
| `GET /putaway-tasks`, `/{id}`, xác nhận cất | `PutawayTaskResponse` | `warehouseCode`, `warehouseName` | |
| `/putaway-trips…` | `PutawayTripResponse` | `operatorName`, `closedByName`, `warehouseCode`, `warehouseName` | |
| `GET /inventory/lots`, `/{id}` | `LotTraceResponse.lot` | `receivingWarehouseCode`, `receivingWarehouseName` | |
| `GET /inventory/lots/{id}/movements` | `LotTraceResponse.Movement` | `actorName` | |
| `GET /inventory/stock-packages/customer-custody`, kết thúc giữ hộ | `CustomerCustodyResponse` | `endedByName` | |

`POST /inventory/moves` (`movementId`) **không** có số chứng từ: FE bỏ dòng "Mã biến động" ở `MoveResult`.

### A3. Fulfillment

Task lấy hàng giờ có số riêng `taskNumber` dạng `PK-yyyyMMdd-nnnn`, luôn có. Task cũ đã được đánh số lại theo ngày tạo.

| API | DTO | Trường mới | FE sửa |
| --- | --- | --- | --- |
| `GET/POST /fulfillment/tasks`, `GET /fulfillment/tasks/{id}` và mọi API ghi task (giao, đích, bắt đầu, hoàn tất, chuyển sang xuất, hoàn tất kiểm thùng) | `FulfillmentTaskResponse` | `taskNumber`, `warehouseCode`, `warehouseName` | `primitives.tsx` + `list/columns.tsx`: cột "Task" hiện `taskNumber` thay 8 ký tự cuối UUID. `TaskInfo`, tiêu đề và breadcrumb `PickingDetail`: hiện `taskNumber`. `useTaskFilterLabels`: tên kho lấy từ dòng |
| `GET /fulfillment/packing-queue` (hàng đợi kiểm thùng) | `CartonCheckQueueRowResponse` | `taskNumber`, `warehouseCode`, `warehouseName` | `QueueBody`: bỏ tra danh sách kho |
| `GET /fulfillment/put-backs` | `PutBackRowResponse` | `taskNumber`, `warehouseCode`, `warehouseName` | `PutBackToolbar` |

### A4. Đơn hàng

| API | DTO | Trường mới | FE sửa |
| --- | --- | --- | --- |
| `GET /orders` (back-office), `GET /orders/{id}` (nhân viên), `POST /orders/{id}/release` | `OrderResponse` | `warehouseCode`, `warehouseName` (null khi chưa xuất kho, trên bản của khách và ở các API ghi khác) | `OrderInfoCard`: "Kho xuất" hiện `warehouseName`, bỏ `GET /warehouses` và câu "chưa có quyền xem tên kho" |
| `GET /orders/cancellation-requests`, từ chối yêu cầu | `OrderCancellationRequest` | `requestedByName`, `decidedByName`. **Đã có từ trước:** `orderNumber`, `customerName`, `orderTotal`, `paidTotal`, `orderStatus` | `cancellation/columns.tsx`: **bỏ `useOrder(orderId)` cho từng dòng**, dùng `orderNumber`, `customerName`. `CancellationRequestSummary`: hiện người yêu cầu và người quyết định |
| `GET/POST /orders/{id}/completion-hold`, `…/release` | `CompletionHoldResponse` | `heldByName`, `releasedByName` | |
| `GET /orders/delivery-failures`, `GET /orders/{id}/delivery-failures`, hai API hủy | `DeliveryFailureCaseResponse` | `decidedByName`, `releasedByName`, `customerName`, `customerCode`, `warehouseCode`, `warehouseName` | |

### A5. Tên khách hàng

| API | DTO | Trường mới | FE sửa |
| --- | --- | --- | --- |
| `GET /quotes` | `QuoteRowResponse` | `customerName`, `customerCode` | `list-columns.tsx`: **bỏ `CustomerName` (gọi `GET /customers/{id}` cho từng dòng)**, hiện thẳng `customerName` |
| `GET /quotes/{id}` và các API ghi báo giá | `QuoteResponse` | `customerName`, `customerCode` | `QuoteDetail`: như trên |
| `GET /catalog/customer-prices?customerId=`, `…/{customerId}/{sku}` | `CustomerPriceResponse` | `customerName`, `customerCode` | `SelectedCustomer`: bỏ id thô và bỏ `GET /customers/{id}` |
| `GET /returns` | `ReturnRequestRowResponse` | `customerName`, `customerCode` (null trên `GET /returns/me`) | |
| `GET /returns/{id}` (nhân viên) | `ReturnRequestResponse` | `customerName`, `customerCode` | `ReturnSummary`: "Khách hàng" hiện tên cạnh link |

### A6. Báo giá

| API | DTO | Trường mới | FE sửa |
| --- | --- | --- | --- |
| `GET /quotes` | `QuoteRowResponse` | `convertedOrderNumber` (trên bản của khách thì null khi đơn còn DRAFT) | Cột "Đơn đã tạo" hiện số đơn |
| `GET /quotes/{id}` | `QuoteResponse` | `sentByName`, `respondedByName` (null trên bản của khách). **Đã có từ trước:** `transitions[].changedByName` | Hiện ai gửi, ai trả lời, ai đổi trạng thái |

### A7, A8. Vận chuyển, kế hoạch xếp xe

| API | DTO | Trường mới | FE sửa |
| --- | --- | --- | --- |
| `GET /fulfillment/loads/{id}` và mọi API ghi chuyến | `LoadResponse` | `loadingStartedByName`, `bookedByName`, `warehouseCode`; `shipments[].packages[].loadedByName` | `LoadInfoCard`: bỏ tra kho, hiện người bắt đầu xếp và người đặt chuyến |
| `GET /fulfillment/loads` | `LoadRowResponse` | `warehouseCode`, `warehouseName` | |
| `GET/POST /fulfillment/shipments/{id}/pod-files` | `PodFileResponse` | `uploadedByName` (null với file do webhook hãng vận chuyển gửi) | |
| `GET/POST /fulfillment/loads/{id}/waybill-files` | `WaybillFileResponse` | `uploadedByName` | |
| `/fulfillment/waybill-imports` | `WaybillImportResponse` và dòng của nó | `uploadedByName` (null ở bản xem trước) | |
| `/fulfillment/delivery-outcome-imports` | `ImportView`, `ImportRow` | `uploadedByName` | |
| `GET /fulfillment/load-plans` | dòng kế hoạch | `warehouseCode`, `warehouseName` | |
| `GET /fulfillment/load-plans/{id}` và các API ghi kế hoạch | chi tiết kế hoạch | `warehouseCode`, `warehouseName`; `unplacedAcknowledgement.byName` | `LoadPlanDetail`: bỏ `GET /warehouses` và chữ "kho không xác định" |

Kế hoạch xếp xe không còn kho đích (`transferOrder.toWarehouseId`): chuyển kho đã bị bỏ khi rescope, `sourceType` luôn là `ORDER`.
FE có thể bỏ `TransferFixed`, `TransferPicker`.

### Nhận hàng trả (procurement)

| API | DTO | Trường mới |
| --- | --- | --- |
| `GET /goods-receipts/returns` | dòng phiếu nhận trả | `shipmentCode` (null với phiếu mở theo yêu cầu trả hàng), `warehouseCode`, `warehouseName`, `receivedByName` |
| `GET /goods-receipts/returns/{id}` và các API ghi | `ReturnReceiptView` | `receivedByName`, `confirmedByName`, `warehouseCode`, `warehouseName`; `cartons[].scannedByName`, `countedByName`; `looseLines[].recordedByName`. **Đã có từ trước:** `shipmentCode`, `orderNumber` |
| Ảnh phiếu nhận hàng | `PhotoView` | `takenByName` |

`ReturnReceiptDetail.tsx:124`: hiện `shipmentCode` thay `shipmentId`.

**Đã có từ trước:** `LoadResponse.warehouseName`, `dispatchedByName`; `ShipmentResponse.warehouseName`, `recordedByName`, `correctedByName`. FE bỏ tra kho ở `LoadInfoCard`, `ShipmentInfoCard`.

### A9. Người dùng

| API | DTO | Trường mới | FE sửa |
| --- | --- | --- | --- |
| `GET /identity/users`, `/{id}` và các API ghi người dùng | `UserResponse` | `warehouses: [{ warehouseId, code, name }]`, cùng thứ tự với `warehouseIds` (vẫn giữ); mảng rỗng khi không có kho | `user-list/columns.tsx`, `WarehousesCard`: bỏ `GET /warehouses`, hiện `code`/`name` |

### A10. Thanh toán, hóa đơn điện tử, tín dụng

| API | DTO | Trường mới | FE sửa |
| --- | --- | --- | --- |
| `GET /einvoices/{id}` và các API ghi của nhân viên | `EInvoiceResponse` | `originalEInvoiceCode`, `originalEInvoiceSymbol`, `originalEInvoiceNumber: number` (null trên hóa đơn gốc), `returnRmaNumber` (null khi không gắn trả hàng) | `EInvoiceInfoCard`: "Hóa đơn gốc" hiện ký hiệu + số cạnh link |
| `GET/POST /einvoices/{id}/error-notices`, gửi thông báo | `EInvoiceErrorNoticeResponse` | `recordedByName` | |
| `GET /refunds/records`, `records[]` trong `GET /refunds/orders/{orderId}`, ghi và hủy | `RefundRecordResponse` | `recordedByName`, `voidedByName`, `customerName`, `customerCode` (tất cả null trên bản của khách) | `features/payment/schemas.ts`: hiện người ghi nhận / hủy |
| `GET /payment-receipts/{id}` | `PaymentReceiptResponse` | **Đã có từ trước:** `recordedByName`, `voidedByName` | FE chỉ cần hiện |
| `GET/PUT /customers/{customerId}/credit-profile` | `CreditProfileResponse` | `approvedByName` | |

### A11. Trả hàng và các trường người làm khác

| API | DTO | Trường mới |
| --- | --- | --- |
| `GET /returns/{id}` và mọi API ghi trả `ReturnRequestResponse` (bản nhân viên) | `ReturnRequestResponse` | `requestedByName`, `cancelledByName`, `custodyChoiceByName`; `review.decidedByName`; `receipt.receivedByName`; `parcels[].receivedByName`; `resolution.proposedByName`, `resolution.settledByName`; `evidence[].uploadedByName`; `custodyOutcomes[].chosenByName`, `endedByName`; `discrepancies[].recordedByName`; `history[].actorName` (null khi hệ thống làm) |
| `GET /returns/custody-notes` | `CustodyNoteResponse` | `customerName`, `customerCode`, `warehouseCode`, `warehouseName`, `keptByName`, `chosenByName`, `endedByName` |
| `GET /design-snapshots?customerId=` | `ConfirmedSnapshotResponse` | `confirmedByName` |

FE sửa: `ReturnHistoryCard`, `ReturnSummary` hiện ai duyệt, nhận, đề xuất, chốt.

### A12. Dùng chung

Mọi DTO có `warehouseId` mà brief này liệt kê đều đã có `warehouseCode`/`warehouseName`. FE có thể thôi gọi
`useWarehouseDirectory` (`lib/references/warehouse-options.ts`) ở những chỗ chỉ cần tên kho. Vẫn giữ nó cho **bộ lọc**
(dropdown chọn kho).

### A13. Đơn sản xuất

`GET /production-orders`, `GET /production-orders/{id}` và mọi API ghi (`ProductionOrderResponse`, list và detail dùng chung):

| Trường mới | Ghi chú |
| --- | --- |
| `salesOrderNumber`, `customerName`, `customerCode` | `OverviewCards`: "Đơn bán" hiện số đơn thay `salesOrderId.slice(0,8)` |
| `producerName` | Tên đơn vị sản xuất |
| `receivingWarehouseCode`, `receivingWarehouseName` | |
| `drawnUpByName`, `sentByName`, `approvedByName` | `approvedByName` chỉ có ở đơn trước 2026-11-20 |
| `history[].byName` | null khi hệ thống làm |

`GET/POST /production-orders/{id}/sample-rounds…` (`SampleRound`): `openedByName`, `answeredByName`.

Các trường nhà gia công / đơn đặt gia công trong bản phân tích **không còn**: sau rescope, đơn vị sản xuất là bên ngoài duy nhất,
và đã có `producerName`. Chuyển kho và yêu cầu mẫu không còn ở BE `develop`, nên brief này không có phần cho chúng.

---

## B. Storefront

### B1. Thiết kế

| API | DTO | Trường mới | FE sửa |
| --- | --- | --- | --- |
| `GET /designs`, `GET /designs/{id}` và mọi API trả thiết kế | `DesignDraftResponse` | `projectName`, `projectStatus` (`ACTIVE`/`ARCHIVED`; null khi thiết kế không thuộc dự án), `reviewedByName` | `api-designs.ts`: **bỏ `readProjects`/`withProjects`** (`GET /design-projects?size=200` và gọi từng dự án). Thông báo trả lại / đã duyệt hiện người duyệt |
| `GET /designs/{id}/history` | `DesignDecisionResponse` | `actorName` | `decision-history.tsx`: hiện ai làm; giữ lại `actorId` trong `design-dtos.ts` nếu cần |
| `GET /design-projects/{id}/history` | `DesignProjectEventResponse` | `actorName` | Timeline dự án |
| `GET /catalog/products/{slug}` | `CatalogProductResponse.variants[]` | `name` | `variant-choice.tsx`, `product-purchase-panel.tsx`: hiện tên biến thể cạnh SKU |
| `GET /catalog/products/{slug}/customization` | `CatalogCustomizationResponse` | `productTitle` (cùng giá trị với `title` của trang sản phẩm) | `api-print-config.ts` (`readProductTitle`): bỏ gọi thêm `GET /catalog/products/{slug}` |

`POST /designs/mine` vẫn cần `productId` (chưa nhận `productSlug`, ưu tiên thấp).

### B2. Báo giá (khách)

| API | DTO | Trường mới | FE sửa |
| --- | --- | --- | --- |
| `GET /quotes/{id}` | `QuoteResponse.lines[]` | `productName`, `variantName`. **Đã có từ trước:** `designName`, `previewUrl` | `quote-lines.tsx`, `quote-accept-line.tsx`: hiện tên thay "Dòng 3 · SKU" |
| `GET /quotes/{id}` | `QuoteResponse.transitions[]` | `changedByName` đã có từ trước, null trên bản của khách | |

Bản của khách vẫn có `sentBy` (UUID nhân viên); BE chưa giấu id này để không đổi trường cũ. `sentByName`/`respondedByName` null trên bản của khách.

### B3. Đơn, trả hàng, hóa đơn, địa chỉ

| API | DTO | Trường mới | FE sửa |
| --- | --- | --- | --- |
| `GET /orders/me/{orderId}` | `CustomerOrderDetailResponse.lines[]` | `productName`, `variantName` (đã có `designName`, `previewUrl`) | `order-detail-sections.tsx` |
| như trên | `CustomerOrderDetailResponse` dòng của vận đơn (`shipments[].lines[]`) | `productName`, `variantName` | `return-request-form.tsx` (nguồn của form trả hàng) |
| `GET /returns/me/{id}`, bản của khách của các API ghi trả hàng | `CustomerReturnResponse.lines[]` | `productName`, `variantName` | `return-detail-sections.tsx` |
| `GET /orders/me/{orderId}/contract` (và `GET /orders/{orderId}/contract` của nhân viên) | `deliverySchedule[].lines[]` | `productName`, `variantName` | `order-contract-schedule.tsx` |
| **Đã có từ trước:** `GET /einvoices/me/{id}` | | | `lib/einvoices/queries.ts`: **bỏ `useMyInvoiceOrder`** (tải 200 hóa đơn rồi tìm). Hóa đơn của khách khác hoặc bản nháp trả 404 `EINVOICE_NOT_FOUND` |
| **MỚI** `GET /customers/me/addresses` | `List<CustomerAddressResponse>` (giống `GET /customers/{id}/addresses`) | | `address/api.ts`: bỏ `getMyCustomerId`. Quyền `customer-addresses:READ`; tài khoản không có hồ sơ khách trả 404 `CUSTOMER_NOT_FOUND` |

### B4. Chat và catalog

- Catalog: xem `variants[].name` ở B1.
- **Chat, chưa làm:** danh sách hội thoại (`GET /chat/conversations`) vẫn trả `context.label = null`. Muốn điền nhãn mà không tra từng
  dòng thì BE phải thêm API tra theo lô ở module order và product. Việc này ghi thành việc tồn. FE giữ cách hiện "Trao đổi về đơn hàng"
  như hiện nay.

---

## C. Còn lại, chưa làm ở BE

- `context.label` trên danh sách hội thoại chat (xem B4).
- `POST /designs/mine` nhận `productSlug` (ưu tiên thấp).
- Bản báo giá của khách vẫn chứa `sentBy` (UUID nhân viên).
- `orderNumber` trên dòng danh sách phiếu nhận trả (bản chi tiết đã có).
- Vài DTO nhỏ chưa có tên: `DelayAlertController.AlertResponse.warehouseId`,
  `ProductionScheduleController.ShortBatchResponse.customerId`.

## D. FE tự sửa (không phụ thuộc BE)

Giữ nguyên như bản phân tích mục D: breadcrumb dùng `number`/`countNumber`; chip kho ở `InventoryAlertList`; chip "Đơn" ở
installments, einvoices, production-orders dùng `orderNumber`/`salesOrderNumber` đã có trên dòng; giữ `CardSku.name` ở hộp nhân bản.
