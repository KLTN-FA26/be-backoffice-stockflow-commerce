# Truy vết 107 bảng → luồng nghiệp vụ

Mục đích: mỗi bảng trong `schema.dbml` trả lời được "vì sao có bảng này" bằng một bước trong docs
(`kltn-docs`: warehouse 01–11, ecommerce 12–18, 00 System Overview). Khi hội đồng hỏi "sao nhiều
bảng thế", hãy trình bày **theo luồng**, không theo con số: 107 bảng = 18 luồng + nền tảng.

Cột **Code**: ✓ = entity đã map, đang chạy · ◐ = có bảng cũ tương ứng, code sẽ chuyển sang (contract C1–C4) · ○ = bảng mới, chờ code.

Quy ước dùng chung cho mọi luồng có duyệt: người lập ≠ người duyệt (CHECK "four eyes"), lịch sử
append-only (trigger), chứng từ đánh số qua `platform.document_sequence`.

---

## 1. Nền tảng — 00 System Overview (11 bảng)

| Bảng | Vai trò | Code |
|---|---|---|
| `identity.app_user` | Tài khoản đăng nhập (staff, khách) | ✓ |
| `identity.app_role` | 10 vai trò theo Role Registry | ✓ |
| `identity.permission` | Quyền = resource × action (VIEW_PAGE/READ/CREATE/UPDATE/DELETE/APPROVE/EXPORT) | ✓ |
| `identity.user_role` | Gán vai trò cho người | ✓ |
| `identity.role_permission` | Gán quyền cho vai trò (ma trận RBAC) | ✓ |
| `identity.user_session` | Phiên đăng nhập, thu hồi token sớm (ADR-0006) | ✓ |
| `platform.audit_log` | Nhật ký thao tác nhạy cảm (ai, làm gì, kết quả) | ✓ |
| `platform.idempotency_record` | Chống gửi trùng request (Idempotency-Key) | ✓ |
| `platform.shedlock` | Khoá job định kỳ khi chạy nhiều instance | ✓ |
| `platform.document_sequence` | Số chứng từ theo ngày cho GR/TO/ADJ/CC/RMA/COD… | ○ |
| `public.event_publication` | Sự kiện giữa module chưa xử lý xong (thay outbox, ADR-0005) | ✓ |

## 2. Tạo sản phẩm — warehouse 01, ecommerce 13 (15 bảng product + 1 inventory)

| Bảng | Vai trò | Code |
|---|---|---|
| `product.brands` | Thương hiệu | ◐ |
| `product.categories` | Cây danh mục (path, depth) | ◐ |
| `product.attributes` | Từ điển thuộc tính (màu, kích thước, chất liệu) | ○ |
| `product.attribute_values` | Giá trị cho phép của thuộc tính | ○ |
| `product.category_attributes` | Danh mục yêu cầu thuộc tính nào | ○ |
| `product.products` | Sản phẩm: nội dung bán hàng, luồng duyệt (BR-PRD-003) | ◐ |
| `product.product_categories` | Sản phẩm thuộc nhiều danh mục, 1 danh mục chính | ◐ |
| `product.product_attributes` | Thuộc tính sản phẩm dùng, vai trò VARIANT_AXIS / DESCRIPTIVE | ○ |
| `product.product_attribute_options` | Các lựa chọn của trục biến thể (xám, be…) | ○ |
| `product.product_descriptive_values` | Giá trị thuộc tính mô tả | ○ |
| `product.variants` | Biến thể = SKU bán được (BR-01: SKU khoá sau DRAFT) | ◐ |
| `product.variant_attribute_values` | Biến thể chọn option nào trên mỗi trục | ○ |
| `product.media` | Ảnh/video/3D theo biến thể, có duyệt đăng | ◐ |
| `product.customization_templates` | Mẫu in theo yêu cầu (docs 12) | ◐ |
| `product.print_areas` | Vùng in trên mẫu (mm, lề an toàn, bleed) | ○ |
| `inventory.inventory_items` | Mặt kho của SKU: kích thước, trọng lượng, đóng gói, lô/HSD (BR-03, BR-07, BR-08) | ○ |

## 3. Mua hàng — warehouse 02 (16 bảng)

| Bảng | Vai trò | Code |
|---|---|---|
| `procurement.suppliers` | Nhà cung cấp, dung sai nhận hàng (BR-04) | ◐ |
| `procurement.supplier_addresses` | Địa chỉ NCC (2 cấp tỉnh–phường) | ○ |
| `procurement.supplier_contacts` | Người liên hệ | ○ |
| `procurement.supplier_items` | NCC bán mặt hàng nào, MOQ, pack size, NCC ưu tiên | ○ |
| `procurement.supplier_item_prices` | Lịch sử giá, không chồng khoảng hiệu lực | ○ |
| `procurement.replenishment_proposals` | Đề xuất bổ sung (MRP / tay) | ○ |
| `procurement.replenishment_proposal_lines` | Dòng đề xuất → dòng PO | ○ |
| `procurement.purchase_orders` | Đơn mua: DRAFT → PENDING_APPROVAL → APPROVED → CONFIRMED → RECEIVED → CLOSED | ◐ |
| `procurement.purchase_order_lines` | Dòng PO, tiền tính khớp (net = subtotal − CK; total = net + thuế) | ◐ |
| `procurement.purchase_order_revisions` | Mỗi lần sửa PO đã duyệt là 1 revision (append-only) | ○ |
| `procurement.purchase_order_line_revisions` | Giá trị từng dòng trong mỗi revision | ○ |
| `procurement.purchase_order_approvals` | Quyết định duyệt theo bước | ○ |
| `procurement.purchase_order_events` | Timeline PO | ○ |
| `procurement.purchase_order_attachments` | Báo giá, hợp đồng, xác nhận NCC | ○ |
| `procurement.approval_limits` | Hạn mức duyệt theo vai trò | ○ |
| `procurement.po_number_sequence` | Số PO theo ngày | ✓ |

## 4. Nhận hàng & QC — warehouse 03 (3 bảng)

| Bảng | Vai trò | Code |
|---|---|---|
| `procurement.goods_receipts` | Phiếu nhận, chỉ trên PO CONFIRMED, đúng kho của PO | ◐ |
| `procurement.goods_receipt_lines` | Dòng nhận: đúng hàng của dòng PO, không vượt dung sai, có lô/HSD nếu cần | ○ |
| `procurement.qc_inspections` | Kết quả QC: đạt / cách ly (phải có vị trí) / loại (phải có lý do) | ◐ |

## 5. Hoá đơn NCC — warehouse 04 (2 bảng)

| Bảng | Vai trò | Code |
|---|---|---|
| `procurement.supplier_invoices` | Hoá đơn NCC, số duy nhất theo NCC | ◐ |
| `procurement.supplier_invoice_lines` | Dòng hoá đơn ↔ dòng PO ↔ dòng nhận (đối chiếu 3 chiều) | ○ |

## 6. Cất hàng — warehouse 05 (1 bảng)

| Bảng | Vai trò | Code |
|---|---|---|
| `warehouse.putaway_task` | Nhiệm vụ cất hàng: nguồn là đúng 1 trong dòng nhận / dòng chuyển kho / dòng hàng hoàn | ✓ |

## 7. Sơ đồ kho & slotting — warehouse 06 (9 bảng)

| Bảng | Vai trò | Code |
|---|---|---|
| `warehouse.warehouse` | Kho: prefix, địa chỉ, khung bản đồ | ✓ (cột mới ◐) |
| `warehouse.zone` | Nhóm hiển thị kệ | ○ |
| `warehouse.shelf` | Kệ: vị trí, xoay, mặt lấy hàng, lớp lưu trữ | ○ |
| `warehouse.shelf_level` | Tầng kệ: chiều cao, tải trọng | ○ |
| `warehouse.bin` | Ô chứa trên tầng | ○ |
| `warehouse.area` | Khu sàn: nhận, cách ly, đóng gói, chờ giao, không lưu trữ | ○ |
| `warehouse.storage_location` | Mọi chỗ để hàng (bin hoặc khu); mã vị trí mà tồn kho và máy quét dùng | ○ |
| `warehouse.boundary` | Tường / cửa, tính quãng đường đi (BR-09) | ○ |
| `warehouse.slotting_rule` | Quy tắc gợi ý vị trí | ✓ |

## 8. Tồn kho (xuyên suốt 03–11) (6 bảng)

| Bảng | Vai trò | Code |
|---|---|---|
| `inventory.stock_item` | Tồn theo SKU × vị trí × lô: on_hand, reserved, trạng thái | ✓ |
| `inventory.stock_reservation` | Giữ hàng cho đơn (docs 14), hết hạn tự nhả | ✓ |
| `inventory.stock_movement` | Sổ cái mọi thay đổi tồn (docs 11 BR-06), append-only | ○ |
| `inventory.stock_adjustment` | Điều chỉnh tồn có duyệt (hư hỏng, mất, lệch kiểm kê) | ○ |
| `inventory.cycle_count` | Đợt kiểm kê (định kỳ / do short pick / do lệch) | ○ |
| `inventory.cycle_count_line` | Kết quả đếm từng vị trí | ○ |

## 9. Lấy hàng, đóng gói, giao hàng — warehouse 07, 08, 09 (9 bảng)

| Bảng | Vai trò | Code |
|---|---|---|
| `fulfillment.wave` | Đợt lấy hàng | ✓ |
| `fulfillment.pick` | Nhiệm vụ lấy hàng cho 1 đơn | ✓ |
| `fulfillment.pick_line` | Lấy SKU nào, ở bin/lô nào, bao nhiêu, short bao nhiêu | ○ |
| `fulfillment.pack` | Nhiệm vụ đóng gói | ✓ |
| `fulfillment.package` | Kiện hàng (1 đơn nhiều kiện), cân đo, tracking | ○ |
| `fulfillment.package_line` | Trong kiện có gì (BR-04: 1 kiện chỉ 1 đơn) | ○ |
| `fulfillment.design_verification` | Đối chiếu thành phẩm in với thiết kế đã chốt (08 BR-02) | ✓ |
| `fulfillment.shipment` | Lô giao cho hãng vận chuyển | ✓ |
| `fulfillment.shipment_event` | Lịch sử tracking (webhook/poll), append-only | ○ |

## 10. Chuyển kho — warehouse 10, 11 (3 bảng)

| Bảng | Vai trò | Code |
|---|---|---|
| `inventory.transfer_order` | Lệnh chuyển giữa 2 kho, huỷ chỉ trước khi xuất (BR-04) | ○ |
| `inventory.transfer_order_line` | Yêu cầu / đã xuất / đã nhận / hỏng; lệch phải ghi chú (BR-05) | ○ |
| `warehouse.move_task` | Di chuyển trong kho: replenishment, re-slotting, thủ công | ○ |

## 11. Thiết kế in theo yêu cầu — ecommerce 12 (4 bảng)

| Bảng | Vai trò | Code |
|---|---|---|
| `design.design_draft` | Bản thiết kế của khách, luồng duyệt kỹ thuật | ✓ |
| `design.design_artifact` | File thiết kế (preview, print-ready…) | ✓ |
| `design.design_snapshot` | Bản chốt bất biến gắn vào đơn | ✓ |
| `design.design_decision` | Lịch sử quyết định duyệt/yêu cầu sửa | ✓ |

## 12. Danh mục bán hàng — ecommerce 13 (3 bảng)

| Bảng | Vai trò | Code |
|---|---|---|
| `catalog.catalog_entry` | SKU hiển thị trên storefront: giá, ATP, SEO | ✓ |
| `catalog.pricing_rule` | Giá theo phân khúc khách, ưu tiên | ✓ |
| `catalog.promotion` | Khuyến mãi % / số tiền | ✓ |

## 13. Giỏ hàng & đặt hàng — ecommerce 14 (6 bảng)

| Bảng | Vai trò | Code |
|---|---|---|
| `ordering.cart` | Giỏ (khách / khách vãng lai), gộp khi đăng nhập | ○ |
| `ordering.cart_line` | Dòng giỏ, kèm design snapshot nếu in theo yêu cầu | ○ |
| `ordering.customer_order` | Đơn hàng, snapshot địa chỉ, khách vãng lai | ✓ |
| `ordering.order_line` | Dòng đơn, giá chốt, checksum thiết kế | ✓ |
| `ordering.order_line_reservation` | Dòng đơn giữ hàng ở những lô nào | ✓ |
| `ordering.order_number_sequence` | Số đơn theo ngày | ✓ |

## 14. Thanh toán — ecommerce 15 (4 bảng)

| Bảng | Vai trò | Code |
|---|---|---|
| `payment.payment` | Giao dịch thanh toán | ✓ |
| `payment.refund` | Hoàn tiền | ✓ |
| `payment.cod_remittance` | Đợt hãng vận chuyển chuyển tiền COD | ○ |
| `payment.cod_remittance_line` | Đối soát từng lô giao (khớp / thiếu / thừa / mất) | ○ |

## 15. Chat bán hàng — ecommerce 16 (2 bảng)

| Bảng | Vai trò | Code |
|---|---|---|
| `chat.conversation` | Hội thoại khách ↔ sale, gắn đơn | ✓ |
| `chat.message` | Tin nhắn | ✓ |

## 16. Quản lý đơn — ecommerce 17 (4 bảng)

| Bảng | Vai trò | Code |
|---|---|---|
| `ordering.order_hold` | Tạm giữ đơn (nghi gian lận, chờ khách) | ✓ |
| `ordering.order_status_history` | Timeline đơn, append-only | ○ |
| `ordering.return_request` | Yêu cầu đổi/trả (RMA) | ○ |
| `ordering.return_request_line` | Trả dòng nào, bao nhiêu (không quá số đã mua), xử lý hàng về | ○ |

## 17. Thông tin khách — ecommerce 18 (3 bảng)

| Bảng | Vai trò | Code |
|---|---|---|
| `customer.customer` | Hồ sơ khách, gắn tài khoản | ✓ |
| `customer.address` | Sổ địa chỉ 2 cấp, 1 mặc định mỗi loại | ✓ |
| `customer.segment` | Phân khúc khách (giá theo phân khúc) | ✓ |

## 18. Thông báo & báo cáo (5 bảng)

| Bảng | Vai trò | Code |
|---|---|---|
| `notification.template` | Mẫu email/SMS theo ngôn ngữ | ✓ |
| `notification.preference` | Người dùng bật/tắt kênh | ✓ |
| `notification.delivery_log` | Nhật ký gửi | ✓ |
| `reporting.product_sales_summary` | Doanh số theo SKU × tháng (read model) | ✓ |
| `reporting.sales_daily_summary` | Doanh thu theo ngày (read model) | ✓ |

---

**Tổng:** 107 bảng. ✓ 45 đang chạy · ◐ 13 có code cũ sẽ chuyển · ○ 49 chờ code. Không bảng nào nằm ngoài một luồng docs hoặc một nhu cầu nền tảng.
