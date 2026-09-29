# Báo cáo tiến độ backend StockFlow — 29/09/2026

## 1. Kết luận điều hành

Backend đã có nền tảng và nhiều nghiệp vụ thực, nhưng chưa hoàn chỉnh chuỗi vận hành
nhập hàng → quản lý kho → bán hàng → thanh toán → xuất/giao → đổi trả.
Phần mạnh hiện nay là tài khoản, sản phẩm, khách hàng, đặt đơn/giữ tồn, thiết kế và media.
Tính thêm nhánh đang làm, đã bổ sung nhà cung cấp, gửi PO, catalog/giá, kiểm kê,
cảnh báo tồn và báo giá thiết kế. Các phần này chưa đồng nghĩa đã nằm trên develop/main.

Đây là kiểm kê code backend, không phải xác nhận trạng thái Jira, frontend, production hay UAT.
Đã fetch main/develop và kiểm tra remote, controller, service, migration, tài liệu nghiệp vụ.
Không sửa chức năng, đổi nhánh, commit, push hoặc triển khai hệ thống trong lượt báo cáo.

## 2. Ba mốc code cần phân biệt

| Mốc | Phiên bản | Tiến độ quan sát được |
|---|---|---|
| main | a879877 | Nền tảng, tài khoản/phân quyền cơ bản, sản phẩm/duyệt, PO cơ bản, tồn và đặt đơn; chưa có nhiều phần đã merge vào develop |
| develop | 80dba8f | Thêm customer/address/guest, media/design/fulfillment, session, lịch sử/hủy đơn, theo dõi PO và thông tin đóng gói/vận chuyển sản phẩm |
| Nhánh hiện tại + working tree | feature/SCRUM-70-71-inventory-control-ecommerce; HEAD d40287d | develop + SCRUM-115/118 đã commit/push trên nhánh riêng + SCRUM-70/71 và phần mở rộng chưa commit |

main có một commit CodeQL riêng ngoài develop, nên không mô tả hai nhánh như quan hệ
đơn giản chỉ cần đếm số commit phía sau. Nội dung nghiệp vụ trên main ít hơn develop.

Theo cách đếm annotation GET/POST/PUT/PATCH/DELETE ở các business controller:

| Chỉ số kiểm kê | main | develop | Tính cả local |
|---|---:|---:|---:|
| Số khai báo handler HTTP | 25 | 91 | 135 |
| Module có ít nhất một phần xử lý nghiệp vụ thực | 6/14 | 9/14 | 10/14 |

Đây không phải phần trăm hoàn thành. Một handler có thể rất nhỏ; một luồng đầy đủ cần
nhiều module. Notification không có controller vẫn có xử lý sự kiện; bảng/entity tồn tại
không có nghĩa use case đã được triển khai. Không tính actuator hoặc hạ tầng dùng chung.

## 3. Tiến độ theo nhóm chức năng

“Có BE” bên dưới nghĩa là đã có đường xử lý thực cho chức năng được nêu, không phải
toàn bộ phạm vi của module đã hoàn tất hoặc đã chạy production.

| Nhóm | Trên develop | Bổ sung ngoài develop, tính cả local | Còn thiếu/chưa xác nhận |
|---|---|---|---|
| Identity | Login/JWT, vai trò/quyền, phạm vi dữ liệu, logout/session, đổi mật khẩu | Không có phần mới chính | Không kết luận bộ quản trị tài khoản đầy đủ; chưa thấy luồng quên mật khẩu hoàn chỉnh trong controller đã rà |
| Product | Tạo/sửa/list/detail, gửi duyệt/duyệt/từ chối/ngừng, lịch sử, thông tin đóng gói và quy tắc vận chuyển, gallery | Cấu hình tồn từng SKU, nối điều kiện publish/catalog | Chưa thấy bộ API CRUD variant/SKU đầy đủ; cần đối chiếu ticket sản phẩm còn lại |
| Customer | Đăng ký, liên kết identity, profile tự phục vụ/nhân viên, trạng thái khách | Checkout dùng giá server và replay chặt hơn ở order | Phân khúc khách hàng chưa có luồng đầy đủ; chưa xác minh FE |
| Address | CRUD, mặc định riêng shipping/billing, kiểm tra trường dữ liệu, bỏ quận/huyện | Không có phần mới chính | Chưa đối chiếu mã xã/phường thuộc đúng tỉnh bằng bộ dữ liệu hành chính chính thức |
| Media/storage | Upload/resize ảnh, gallery, artifact, snapshot/checksum, phân quyền tải; có template AWS/CDN | Kế thừa cấu hình MinIO từ nhánh 115/118 | Chưa chứng minh stack AWS/CloudFront thật đã triển khai, credential/domain/cache/UAT đã đạt |
| Design | Draft, phân công, specification, review, yêu cầu xác nhận, khách xác nhận, snapshot và phiên bản mới | Báo giá thiết kế, thương lượng bằng revision, khách chấp nhận, checkout ràng buộc giá | Đây không phải công cụ biên tập/render 2D/3D hay pipeline sản xuất đầy đủ |
| Supplier/PO | PO tạo/list/detail, chuyển trạng thái, nhận số lượng theo PO, đóng thiếu, báo cáo trạng thái/chi tiêu | SCRUM-118: hồ sơ/liên hệ/điều khoản/lead time/KPI; SCRUM-115: SMTP/API, retry/recovery, xác nhận NCC | Chưa có chuỗi phiếu nhận hàng/QC/putaway; ghi nhận số lượng trên PO chưa tự tạo tồn kho; xác nhận NCC do nhân viên ghi nhận, không phải hệ thống tự đọc email phản hồi |
| Inventory | Tồn theo vị trí/kho, ATP, giữ/nhả/trừ tồn, hết hạn giữ chỗ | FIFO/FEFO, LOT/SERIAL/expiry policy; kiểm kê/duyệt/ghi sổ; cảnh báo và email | Điều chuyển kho, bổ sung nội bộ, ledger toàn bộ movement, quy trình QC/quarantine và xử lý hàng hết hạn chưa hoàn chỉnh |
| Catalog | Service/controller starter | SEO/slug, giá cơ sở VND, publish/unpublish, projection, public list/detail, giá checkout | Chưa có đầy đủ quản trị danh mục, tìm kiếm/lọc thương mại, khuyến mãi/pricing nâng cao |
| Order | Đặt đơn/giữ tồn, guest, snapshot địa chỉ, xem/hủy đơn, lịch sử khách, nhận sự kiện payment | Đối chiếu giá catalog/báo giá, fingerprint request, chống dùng lại quote, khóa tồn toàn giỏ | Chưa có giỏ hàng lưu server/voucher hoàn chỉnh; luồng thu tiền/giao hàng/đổi trả chưa nối đủ |
| Fulfillment | Task phân công, picking/packing, file theo đơn, checksum hold và kiểm tra lại | Thừa hưởng bản sửa tồn kho | completePicking đang cập nhật task, chưa gọi inventory.consume; chưa có shipping/carrier/tracking/POD hoàn chỉnh |
| Notification | Listener đơn hàng/thanh toán/giao hàng, nhưng transport chung chỉ ghi log | Gửi thật SMTP/API cho PO, SMTP cho cảnh báo tồn; lưu retry/delivery | Không được coi mọi email/SMS/push của hệ thống đã gửi thật |
| Warehouse | Starter, chưa có endpoint vận hành | Chưa bổ sung | Quản lý kho/vị trí, slotting, putaway và các luồng vị trí kho |
| Payment | Starter, chưa có endpoint thanh toán | Chưa bổ sung | Tạo thanh toán, webhook, COD/chuyển khoản, gateway, hoàn tiền/đối soát |
| Chat | Starter | Chưa bổ sung | Hội thoại, tin nhắn, tư vấn và nối đơn |
| Reporting | Starter | Chưa bổ sung | Báo cáo tổng hợp/read model; báo cáo PO riêng không thay thế module reporting |

Media là nhóm xuyên module, nên bảng chức năng có nhiều dòng hơn 14 module.
SKU tracking flags đã có kiểm tra trong inventory nhưng không thay thế quy trình quét mã,
nhận lô và nhập hàng thực tế tại kho.

## 4. Các Scrum đã trao đổi và nơi đang nằm

| Ticket/cụm | Kết quả kiểm kê BE | Vị trí bàn giao |
|---|---|---|
| SCRUM-43, 53, 54 | Có luồng ảnh/artifact và các bổ sung kiểm soát đã mô tả; nghiệm thu AWS/UAT riêng | Đã merge develop, chưa có trên main |
| SCRUM-38, 46, 47 | Có guest, đăng ký/login/profile/address và validation cấu trúc; lưu ý dữ liệu địa giới | Đã merge develop, chưa có trên main |
| SCRUM-118 | Có supplier profile/contact/default terms/lead time/performance | Commit/push ở nhánh 115/118, chưa merge develop |
| SCRUM-115 | Có gửi PO email/API, tracking, retry/recovery và ghi nhận xác nhận | Commit/push ở nhánh 115/118, chưa merge develop |
| SCRUM-70 | Có threshold nullable, FIFO/FEFO, tracking flags và expiry rules | Working tree chưa commit/push |
| SCRUM-71 | Có catalog source/projection, SEO/slug/price/publish và public read | Working tree chưa commit/push |
| Kiểm kê + cảnh báo liên quan SCRUM-147 | Có triển khai BE, được đưa vào bộ test hiện tại | Working tree chưa commit; chưa có toàn bộ AC SCRUM-147 để xác nhận đóng ticket |
| Báo giá thiết kế + sửa lỗi checkout/concurrency | Có triển khai và test | Working tree chưa commit/push |

Lịch sử develop còn ghi nhận các phần của SCRUM-75/76/86 (đóng gói, quy tắc vận chuyển,
lịch sử sản phẩm), SCRUM-242/245 (hủy/lịch sử đơn), và cụm SCRUM-116/119/128/129/130/138
(theo dõi PO/báo cáo). Commit có nhắc số ticket không đủ chứng minh tất cả acceptance
criteria của từng ticket đã hoàn tất; báo cáo này xác nhận chức năng code quan sát được.

Guest checkout có code nhưng application.yml mặc định `guest-enabled: false`.
Code tồn tại và endpoint được bật ở môi trường triển khai là hai trạng thái khác nhau.

## 5. Những chỗ đang ngắt trong luồng sử dụng thực tế

### Nhập hàng

Nhà cung cấp → tạo/duyệt/gửi PO → ghi nhận phản hồi/số lượng nhận: có BE khi tính cả nhánh 115/118.
Phiếu nhận hàng → QC → putaway → tăng tồn đúng lô/vị trí: chưa có chuỗi hoàn chỉnh.
Ví dụ: đánh dấu đã nhận 10 ghế trên PO chưa có nghĩa inventory tự xuất hiện 10 ghế bán được.

### Bán hàng

Sản phẩm duyệt → catalog/giá → khách/địa chỉ → checkout/giữ tồn: có BE khi tính cả local.
Thanh toán thật → webhook xác nhận → paid: thiếu payment module.
Order có listener PaymentCaptured không có nghĩa đã tích hợp được cổng thanh toán.

### Xuất và giao hàng

Có task và thao tác picking/packing, kiểm tra đúng thiết kế.
Cần nối xác nhận picking với tiêu thụ đúng reservation; làm shipping, hãng vận chuyển,
tracking và bằng chứng giao hàng. Quy tắc vận chuyển trên sản phẩm không phải tích hợp giao hàng.

### Sau bán

Chưa có RMA/đổi trả, QC hàng hoàn, RTO và hoàn tiền/đối soát hoàn chỉnh.
Không nên xem “hủy đơn” là đã làm “trả hàng/hoàn tiền”.

## 6. Kiểm thử và mức độ sẵn sàng

- Working tree hiện tại: lần `mvn verify` gần nhất kết thúc 29/09/2026 19:44 +07:00,
  **480 tests, 0 failures, 0 errors, 0 skipped**, đóng gói JAR thành công.
- Log: `target/business-fixes-verify.log`; đây là kết quả local kết hợp toàn bộ tính năng,
  không phải kết quả CI riêng của main hoặc develop. Lượt báo cáo này không chạy lại bộ test.
- `tools/verify.py` vẫn có 15 finding dependency trong test đã biết; không gọi static check là sạch.
- Test transport mock và container không xác nhận nhà cung cấp thật/AWS/SMTP/gateway production.
- FE nằm ngoài phạm vi lần rà này. Chưa chấm điểm màn hình, tích hợp BE–FE hay UAT người dùng.
- Tài liệu `CLAUDE.md`, `api-audit.md` có số liệu skeleton cũ; không dùng trạng thái ghi trong
  các tài liệu đó làm tiến độ hiện tại. Một số “open questions” cũng chưa phản ánh quyết định VND đã chốt.

## 7. Thứ tự đề xuất để tiến tới demo và vận hành trọn luồng

1. Chốt tích hợp: merge 115/118, commit/push/PR 70/71 và phần mở rộng, CI trên develop.
   Chưa tự tạo commit/push trong lần báo cáo này.
2. Hoàn thiện đầu vào tồn: warehouse/vị trí → receipt/QC → putaway → stock movement.
   Chốt ranh giới với ticket nhận hàng đang do thành viên khác thực hiện trước khi code.
3. Hoàn thiện thu tiền: chọn phương thức trong phạm vi, payment + webhook/reconciliation,
   nối trạng thái order. Không dùng việc tự đổi trạng thái DB thành bằng chứng luồng thanh toán.
4. Hoàn thiện đầu ra: reservation → picking/consume → packing → shipment/delivery.
5. Thêm đổi trả/hoàn tiền và báo cáo; ưu tiên chat theo phạm vi demo đã chốt.
6. Tích hợp FE, seed dữ liệu thực tế, kiểm tra vai trò, staging/UAT, AWS/CDN/SMTP,
   sao lưu và phục hồi; chỉ đưa bản đã nghiệm thu lên main theo quy trình nhóm.

Không công bố một con số như “xong 80% toàn dự án”: overview nói 133 chức năng/392 leaf task,
nhưng repo không cung cấp ma trận AC–implementation–test–UAT đủ để chấm từng mục.
Muốn tính % chính thức cần Jira export gồm toàn bộ story/subtask, AC, trạng thái và trọng số.
Hiện có thể kết luận chắc: **nền tảng và nhiều use case BE đã có; WMS/e-commerce end-to-end chưa hoàn tất**.

## 8. Dấu vết kiểm chứng trong repo

- `src/main/java/com/stockflow/*/internal/controller/`: các handler được đếm cho mỗi ref.
- `warehouse/payment/chat/reporting/internal/service/*ServiceImpl.java`: starter chưa có use case.
- `procurement/internal/service/ProcurementServiceImpl.java`: receiveGoods cập nhật PO.
- `fulfillment/internal/service/FulfillmentServiceImpl.java`: completePicking cập nhật task.
- `notification/internal/service/NotificationSender.java`: SMTP/API thật và nhánh ghi log.
- `docs/business-design/04c-all-flows.md`: phạm vi 25 luồng dùng để xác định các đoạn còn thiếu.
- `docs/SCRUM-70-71-backend.md`, `docs/SCRUM-115-118-backend.md`: hợp đồng và giới hạn tính năng mới.
- `infra/aws/media-cloudfront.yaml`: template triển khai, không phải bằng chứng đã triển khai AWS.
