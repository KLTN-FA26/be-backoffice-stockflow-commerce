# Những phần cần bỏ và phần làm thêm: tài liệu và ý kiến của mình đặt cạnh nhau (07/11)

**Cách đọc cột "Ý kiến của mình":**

| Ký hiệu | Nghĩa |
|---|---|
| ✅ **Đồng ý hoàn toàn** | Bỏ đúng như tài liệu |
| ✅ **Đồng ý, nhưng bỏ sau khi…** | Kết quả cuối giống tài liệu, chỉ khác thời điểm. Lý do và ví dụ ở mục A–D bên dưới |
| ⚠️ **Khác** | Mình đề xuất khác tài liệu. Lý do và ví dụ ở mục E bên dưới |
| ➕ **Làm thêm** | Tài liệu yêu cầu mà code chưa có. Kế hoạch ở mục H bên dưới |

Chưa sửa code, chưa sửa kế hoạch dọn. Chi tiết và nguồn Odoo: [08-keep-or-drop-review.md](08-keep-or-drop-review.md).

| # | Phần | Tài liệu yêu cầu | Ý kiến của mình | Odoo làm thế nào |
|---|---|---|---|---|
| 1 | Sản xuất trong hệ thống (lệnh sản xuất, phôi, xưởng in, gia công) | Bỏ; gửi Đơn sản xuất cho đơn vị sản xuất ngoài hệ thống | ✅ Đồng ý hoàn toàn | Sản xuất là app riêng; app kho chạy được không cần nó |
| 2 | Mua hàng (PO, nhà cung cấp, hoá đơn NCC) | Bỏ; hàng vào kho theo Đơn sản xuất | ✅ Đồng ý, nhưng xoá PO **sau khi** có Đơn sản xuất (mục A) | Mua hàng là app riêng; hàng làm theo đơn dùng tuyến MTO, sinh từ đơn bán |
| 3 | QC tại kho (khu QC, cờ QC, nhập 3 bước) | Bỏ; kho chỉ đếm thùng, hàng lỗi vào khu cách ly | ✅ Đồng ý hoàn toàn | Nhập 3 bước (có khu QC) dành cho hàng mua từ NCC cần kiểm trước |
| 4 | Chuyển hàng giữa các kho | Bỏ; Đơn sản xuất giao thẳng về kho xuất | ✅ Đồng ý hoàn toàn | Dùng khi kho vệ tinh lấy hàng từ kho trung tâm (bán từ tồn) |
| 5 | Hạn dùng, FEFO | Bỏ; xuất FIFO, lô = mã Đơn sản xuất | ✅ Đồng ý hoàn toàn | FEFO dành cho hàng dễ hỏng |
| 6 | Cảnh báo tồn thấp, điểm đặt lại | Bỏ; Đơn sản xuất chỉ sinh từ đơn bán | ✅ Đồng ý hoàn toàn | Min/max cho hàng bán từ tồn; làm theo đơn dùng MTO |
| 7 | Hàng trơn, giá hiện trên sàn | Bỏ; chỉ có SKU riêng của khách, sàn ẩn giá | ✅ Đồng ý hoàn toàn | B2B: tài khoản theo lời mời, ẩn giá, bảng giá từng khách |
| 8 | Khách tự đặt (giỏ hàng, checkout) | Bỏ; Sale tạo đơn từ báo giá | ✅ Đồng ý hoàn toàn | Báo giá xác nhận bằng ký online; "đặt lại" phải qua giỏ hàng |
| 9 | Thanh toán online (thẻ, ví), thu hộ COD, giữ hàng 30 phút chờ trả tiền | Bỏ; khách chuyển khoản theo đợt của hợp đồng | ✅ Đồng ý hoàn toàn | Điều khoản trả nhiều đợt, đặt cọc trên đơn |
| 10 | Sổ cái tiền cũ (ghi tiền chuyển khoản, phân bổ vào đơn); điều khoản trả trước / cọc / công nợ theo đơn | Bỏ; thay bằng phiếu thu theo đợt của hợp đồng | ✅ Đồng ý, nhưng bỏ **sau khi** có hợp đồng + phiếu thu theo đợt (mục B) | Ghi nhận thanh toán, đối trừ với hoá đơn |
| 11 | Gửi kiện qua hãng (GHN, GHTK…); Sale chọn cách giao cho từng đơn | Bỏ; mọi đơn thuê nguyên xe. Hãng có API đặt xe thì hệ thống tự tạo đơn ở hãng (09 BR-29) | ✅ Đồng ý hoàn toàn; ➕ **làm thêm tích hợp Ahamove** (mục H) | Kết nối hãng (FedEx, UPS, DHL…) tạo vận đơn qua API; có xe riêng thì dùng điều xe |
| 12 | Trạm đóng gói lại (mở hàng, đóng thùng mới, cân, niêm phong); kiểm mã thiết kế lúc đóng gói; báo hỏng từng cái ly | Bỏ; hàng về đã đóng thùng, kho chỉ quét đúng thùng rồi dán nhãn; hỏng thì xử lý nguyên thùng | ✅ Đồng ý hoàn toàn | Bước đóng gói chỉ thêm khi phải đóng lại hàng; hàng hỏng thì huỷ (scrap) |
| 13 | Lấy lẻ từng cái, thùng đã mở | Bỏ; chỉ xuất nguyên thùng | ✅ Đồng ý, nhưng bỏ **sau khi** chuyển tồn hiện có vào thùng (mục C) | Kiện (= LPN) + quy cách thùng, bán theo số thùng |
| 14 | Lớp lưu trữ (lạnh, nguy hiểm, dễ vỡ); sức chứa tính theo số cái; khu đóng gói riêng (`PACKING`) | Bỏ; sức chứa theo m³ tự tính, gộp vào khu xuất hàng | ✅ Đồng ý hoàn toàn | Sức chứa theo khối lượng / số lượng / số kiện |
| 15 | Chính sách tồn tuỳ chỉnh (lô / serial / cách xuất) | Bỏ; luôn theo lô, FIFO | ✅ Đồng ý hoàn toàn | Cấu hình từng sản phẩm, cần khi kho có nhiều loại hàng |
| 16 | Đổi trả cũ (QC hàng trả, tự tạo đơn thay thế miễn phí, ghi credit cho khách) | Bỏ; thùng trả vào khu cách ly, Quản lý kho quyết định; đổi hàng = báo giá mới; hoàn tiền + hoá đơn điều chỉnh | ✅ Đồng ý, nhưng bỏ **sau khi** có phiếu nhập hàng hoàn theo thùng (mục D) | Phiếu nhập ngược + credit note; ở VN là hoá đơn điều chỉnh |
| 17 | 5 role | Bỏ, còn 7 role; đổi tên thành Sales Manager | ✅ Đồng ý hoàn toàn | — |
| 18 | Thiết kế kiểu cũ | Bỏ; khách tự thiết kế, Quản lý kinh doanh duyệt | ✅ Đồng ý hoàn toàn (làm theo kế hoạch Design Studio) | — |
| 19 | Wave picking (lấy hàng theo sóng) | Để giai đoạn sau | ✅ Đồng ý: bỏ code hiện có (không ai gọi tới) | — |
| 20 | Email **tự động gửi khách** về đơn hàng và trả hàng (đơn đã đặt, trạng thái giao, đã giao, huỷ, các bước trả hàng) | Để giai đoạn sau; hiện hệ thống chỉ ghi nhật ký, không gửi | ✅ Đồng ý: **tắt bằng cấu hình, chưa xoá code**; các email khác vẫn giữ (mục F) | — |
| 21 | Ảnh nhận hàng (chụp thùng khi nhập kho, lưu trên S3) | Không nhắc tới; chỉ có ô ghi chú tình trạng thùng bằng chữ | ⚠️ **Khác: giữ** (mục E) | Có kiểu kiểm "chụp ảnh" khi nhận hàng |
| 22 | File S3 của phần đã bỏ: tài liệu PO, hoá đơn NCC, ảnh QC | Bỏ (đi cùng PO và QC) | ✅ Đồng ý hoàn toàn; **hạ tầng S3 vẫn giữ** (mục G) | — |

---

## Lý do và ví dụ cho các dòng không "đồng ý hoàn toàn"

### Đồng ý bỏ, chỉ khác thời điểm (dòng 2, 10, 13, 16)

Cả 4 dòng có chung một lý do: phần cũ đang là **cách duy nhất** hệ thống làm việc đó. Xoá trước khi có phần thay thế thì hệ thống bị hụt một chức năng trong thời gian chờ.

**A. Dòng 2: xoá PO sau khi có Đơn sản xuất**
- **Lý do:** hiện PO là chứng từ nguồn duy nhất của phiếu nhập kho.
- **Ví dụ:** xưởng giao 50 thùng về kho. PO đã xoá mà Đơn sản xuất chưa làm xong, nên phiếu nhập không có chứng từ để tham chiếu. Kho không nhập được 50 thùng này.

**B. Dòng 10: bỏ sổ cái tiền sau khi có phiếu thu theo đợt**
- **Lý do:** sổ cái cũ là chỗ duy nhất đang ghi nhận tiền khách chuyển.
- **Ví dụ:** khách chuyển 15 triệu tiền đợt ký hợp đồng. Sổ cái đã xoá mà phiếu thu theo đợt chưa có, nên Kế toán không có chỗ ghi. Đơn không chuyển `Confirmed`, kho không giữ hàng cho đơn.

**C. Dòng 13: bỏ lấy lẻ sau khi chuyển tồn vào thùng**
- **Lý do:** dữ liệu tồn hiện có gần như toàn hàng lẻ (226/228 dòng trong dữ liệu mẫu), nhiều dòng chưa có lô.
- **Ví dụ:** hệ thống đang ghi 500 cái ly SKU X nằm lẻ trên kệ. Nếu xoá code lấy lẻ trước khi gom 500 cái này vào thùng LPN, chúng không lấy được cho đơn nào và cũng không kiểm kê được.

**D. Dòng 16: bỏ đổi trả cũ sau khi có phiếu nhập hàng hoàn**
- **Lý do:** luồng cũ hiện là cách duy nhất nhập hàng trả về kho.
- **Ví dụ:** xe giao 20 thùng cho quán B, quán đóng cửa, hãng chở 20 thùng về kho. Luồng cũ đã xoá mà phiếu nhập hàng hoàn chưa có, nên kho không nhập lại được. Hàng nằm trong kho nhưng hệ thống không ghi.

### Khác tài liệu (dòng 21)

**E. Giữ ảnh nhận hàng**
- **Lý do:**
  - Tài liệu chỉ có ô ghi chú tình trạng thùng bằng chữ.
  - Ảnh là bằng chứng khi từ chối thùng lỗi, hoặc khi tranh chấp với đơn vị sản xuất hay hãng vận chuyển.
  - Code đã làm xong ở nhánh S3, giữ lại gần như không tốn công.
- **Ví dụ:** xưởng giao 50 thùng, 3 thùng bị ướt. Kho từ chối 3 thùng và chụp ảnh lưu vào phiếu nhập. Hôm sau xưởng nói "đã giao đủ 50 thùng tốt": mở phiếu nhập là có ảnh đối chứng.
- **Cần làm thêm:** một dòng trong tài liệu chương 03 (chụp ảnh tình trạng thùng khi nhận hàng và khi nhập hàng hoàn).

### Ghi chú cho dòng 20 và 22

**F. Dòng 20: chỉ tắt email tự động gửi khách, không bỏ tính năng email**
- **Tắt:** email gửi khách khi đơn được đặt, đổi trạng thái giao, giao xong, bị huỷ, và các bước trả hàng.
  - Tắt bằng biến cấu hình `CUSTOMER_EMAIL_ENABLED=false`.
  - Code vẫn giữ, để bật lại khi làm giai đoạn sau.
- **Vẫn gửi:**
  - email mời khách kích hoạt tài khoản;
  - thông báo kèm email cho Sale khi khách chấp nhận hoặc yêu cầu sửa báo giá;
  - email gửi Đơn sản xuất cho đơn vị sản xuất (sẽ làm theo tài liệu 17).
- **Lý do tắt:**
  - Tài liệu ghi email cho khách thuộc giai đoạn sau.
  - Các email hiện có viết cho luồng cũ (đơn của khách vãng lai, đơn thay thế miễn phí).
  - Các mốc mới (hợp đồng chờ ký, đợt thanh toán đến hạn) chưa có email.
- **Ví dụ:** nếu để bật, khách trả hàng sẽ nhận email báo "đơn thay thế đã bị huỷ", trong khi đơn thay thế miễn phí đã bỏ. Đến hạn trả tiền đợt 2 thì khách lại không nhận email nào.

**G. Dòng 21–22: S3 vẫn giữ, chỉ bỏ ba phần nhỏ**
- **S3 vẫn giữ:** S3 là kho lưu file chung của hệ thống; tài liệu không bỏ S3. Vẫn dùng cho:
  - ảnh, logo khách tải lên khi tự thiết kế;
  - file in PDF/X, khuôn 2D, model 3D;
  - ảnh sản phẩm;
  - hợp đồng đã ký;
  - ảnh, video mẫu in thử;
  - file gửi qua chat.
- **Chỉ bỏ** phần gắn với tính năng đã bỏ:
  - ảnh QC, vì bỏ QC;
  - tài liệu đính kèm PO và hoá đơn NCC, vì bỏ mua hàng.
- **Ảnh nhận hàng:** tài liệu không nhắc tới, không phải yêu cầu xoá. Mình đề xuất giữ (mục E).

---

## Làm thêm

### H. Tích hợp Ahamove: đặt xe qua API, mã vận đơn, webhook (dòng 11)

**Mục tiêu** (tài liệu 09: BR-29, §4.1, §4.2 bước 6, §4.5):
- Mỗi chuyến xe đặt qua Ahamove thì hệ thống tự tạo đơn ở Ahamove.
- Lấy mã vận đơn và link theo dõi, ghép với chuyến và đơn hàng.
- Nhận webhook để cập nhật tài xế, mốc hành trình và kết quả giao từng điểm.

**Code hiện có:**

| Đã có | Chưa có |
|---|---|
| Chuyến xe có mã nội bộ, hãng, biển số, tài xế | Cấu hình kết nối hãng (kiểu kết nối, khoá API, xác thực webhook) |
| Ghi nhận đặt xe tay, huỷ đặt xe (`POST /loads/{id}/booking`, `/booking-cancellation`) | Mã vận đơn, đặt xe qua API |
| Ghi kết quả giao tay (`POST /shipments/{id}/delivery-outcome`) | Nhận webhook, nhật ký sự kiện |
| Danh mục hãng (chỉ thông tin cơ bản) | Mã dịch vụ của hãng theo loại xe |

**Ahamove cung cấp** (API v3, staging `partner-apistg.ahamove.com`):

| Việc | API Ahamove |
|---|---|
| Lấy token | `POST /v3/accounts/token` (số điện thoại + `api_key`) → token Bearer |
| Dịch vụ xe tải theo thành phố | `GET /v3/services?city_id=…&delivery_type=TRUCK` |
| Tạo đơn (1 điểm lấy, nhiều điểm giao) | `POST /v3/orders` → `order_id`, `shared_link` (link theo dõi) |
| Huỷ đơn | `DELETE /v3/orders/{order_id}`, chỉ khi chưa lấy hàng (`IDLE`, `ASSIGNING`, `ACCEPTED`…) |
| Xem đơn, link theo dõi | `GET /v3/orders/{order_id}`, `GET /v3/orders/{order_id}/shared-link` |
| Webhook | Ahamove gọi URL của mình mỗi lần đổi trạng thái. Xác thực bằng header (`apikey`, Bearer hoặc Basic), **không có chữ ký** |

**Ghép dữ liệu:**

| Bên mình | Bên Ahamove |
|---|---|
| 1 chuyến xe | 1 đơn Ahamove; `order_id` = mã vận đơn |
| Kho xuất | `path[0]`, điểm lấy hàng |
| Mỗi shipment | `path[k]`, `tracking_number` = mã shipment |
| Loại xe | `service_id` theo thành phố |
| Giờ hẹn lấy hàng | `order_time` |

**Webhook đổi gì trong hệ thống:**

| Ahamove báo | Hệ thống làm |
|---|---|
| `ACCEPTED` | Ghi tên, số điện thoại tài xế vào chuyến |
| `ACCEPTED` → `ASSIGNING` (tài xế huỷ, Ahamove tìm người khác) | Xoá tài xế cũ, chờ tài xế mới |
| `IN PROCESS` | Mốc "đã lấy hàng" |
| Điểm giao `k` (`_id` = `{order_id}-{k}`) `COMPLETED` | Shipment `k` → `Delivered`, lưu bằng chứng giao (`pod_info`) |
| Điểm giao `k` `FAILED` | Shipment `k` → `Delivery Failed`, lý do ánh xạ từ `fail_comment` |
| `COMPLETED` kèm `RETURNED` | Hàng hoàn về kho → nhập hàng hoàn |
| `CANCELLED` (không có tài xế nhận, hoặc tài xế huỷ) | Báo Dispatch Staff đặt lại xe |

**API sẽ viết:**
1. **Cấu hình hãng.** Mở rộng `PUT /fulfillment/carriers/{id}`:
   - kiểu kết nối (`MANUAL` / `WEBHOOK_LOAD` / `API_BOOKING`);
   - môi trường, số điện thoại + khoá API (lưu mã hoá, không trả lại qua API);
   - header xác thực webhook;
   - mã dịch vụ theo loại xe và thành phố;
   - bảng ánh xạ lý do thất bại.

   Thêm `GET /fulfillment/carriers/{id}/services` để lấy danh sách dịch vụ từ Ahamove cho Quản lý kho chọn.
2. **Đặt xe qua API:** `POST /fulfillment/loads/{id}/booking`. Hãng kiểu `API_BOOKING` thì hệ thống gọi Ahamove tạo đơn và lưu mã vận đơn + link theo dõi; chuyến → `VEHICLE_BOOKED`. Lỗi thì chuyến giữ `PLANNED`, trả lý do cho Dispatch Staff.
3. **Huỷ đặt xe:** `POST /fulfillment/loads/{id}/booking-cancellation` gọi Ahamove huỷ đơn. Ahamove từ chối (đã lấy hàng) thì báo lỗi.
4. **Nhận webhook:** `POST /api/v1/fulfillment/carrier-webhooks/{carrierCode}`.
   - Không cần đăng nhập; kiểm header xác thực đã cấu hình.
   - Lưu nhật ký sự kiện, ghép chuyến và shipment.
   - Cùng sự kiện gửi lại chỉ áp dụng một lần.
5. **Sự kiện lỗi cho Quản lý kho:** `GET /fulfillment/carrier-webhook-events?status=FAILED` (sai xác thực, không ghép được chuyến), xử lý lại hoặc bỏ qua.
6. **Cổng khách:** trang đơn hiện tên hãng, mã vận đơn, tài xế, các mốc hành trình. Link theo dõi của Ahamove là tuỳ chọn, tài liệu chưa có.

**Dữ liệu:** một migration cho
- cấu hình kết nối hãng;
- mã vận đơn và link theo dõi trên chuyến;
- đơn đặt xe ở hãng;
- nhật ký sự kiện webhook.

**Kiểm thử:**
- Test tự động giả lập Ahamove: tạo đơn, huỷ đơn, gửi webhook mẫu.
- Test tay trên staging bằng app tài xế AhaDriver STG (Android): tạo đơn gần tài xế, nhận đơn, đổi trạng thái để Ahamove bắn webhook.

**Cần bạn chuẩn bị / lưu ý:**
- **Tài khoản Ahamove staging:** số điện thoại + API key do Ahamove cấp khi đăng ký đối tác. Gửi Ahamove URL webhook của mình để họ cài.
- **Webhook không có chữ ký**, chỉ có header xác thực. Vì vậy URL phải là HTTPS và khoá header phải đủ dài.
- **Tài liệu Ahamove không có trường biển số xe** (chỉ có tên và số điện thoại tài xế), trong khi tài liệu 09 muốn biển số về qua webhook. Đề xuất: Dispatch Staff nhập biển số khi xe tới kho, và hỏi lại Ahamove khi có tài khoản.
- **Hỏi trạng thái chủ động** (gọi `GET /v3/orders/{id}` khi webhook không về) tài liệu để giai đoạn sau. Webhook lỗi thì Quản lý kho ghi kết quả tay như hiện tại.

---

## Thứ tự làm

1. **Chuẩn bị và dọn an toàn** (PR 1, nhánh `refactor/rescope-01-lock`):
   - tắt email tự động gửi khách;
   - xoá code chết không ai gọi (sự kiện `PaymentFailed`, `DesignConfirmed`, `GoodsReceived`, `QcResult`, wave picking, cache `SUPPLIERS`, `releaseToFulfillment`);
   - đổi tên role `ECOMMERCE_ADMIN` → `SALES_MANAGER`;
   - `verify.py` tự lấy danh sách module.

   Đã bỏ ý "thu quyền trước": thu quyền của phần còn nối vào luồng chính thì luồng bị kẹt. Ví dụ: thu quyền QC trong khi phiếu nhập vẫn đẩy hàng có cờ QC sang QC, thì phiếu nhập kẹt vì không ai làm QC được. Quyền sẽ bỏ cùng lúc với phần code của nó.
2. **Xoá phần cản việc làm mới:**
   - sản xuất, gồm cả sửa luồng chính: release không đẩy sang `IN_PRODUCTION`, lấy hàng không bỏ qua dòng in, bỏ chặn mẫu khi chấp nhận báo giá. Phải làm chung một PR, vì khoảng 45 file test đang dựng đơn đi qua sản xuất;
   - QC;
   - gửi kiện + COD;
   - đóng gói lại + chuyển kho (dùng chung phần đóng gói);
   - thiết kế cũ.
3. **Làm thêm tích hợp Ahamove** (mục H): làm sau khi đã bỏ gửi kiện + COD, vì cùng sửa phần chuyến xe.
4. **Xoá khi đã có phần thay thế:** dòng 2, 10, 13, 16.
5. **Phần còn lại xoá dần trước buổi bảo vệ.**
