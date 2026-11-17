# Rà lại mục 1.3–1.17: chỉ bỏ sản xuất thì có phải bỏ những phần này không?

> Ngày 2026-11-07. Câu hỏi của chủ dự án: chỉ bỏ kho sản xuất và luồng sản xuất, vẫn giữ kho thành phẩm.
> Vậy có còn cần chuyển kho và QC không? Các mục khác có thật phải bỏ không?
>
> Nguồn đối chiếu:
> - tài liệu nghiệp vụ kltn-docs `origin/main` e17b6cc (07/11);
> - code `develop` 673c68ae;
> - tài liệu chính thức Odoo 18 (danh sách link ở §5).
>
> Phần Odoo dưới đây là diễn giải, không trích nguyên văn.

---

## 0. Kết luận nhanh

1. **Phần lớn mục 1.3–1.17 không phải hệ quả của việc bỏ sản xuất.**
   - Tài liệu 04–07/11 không chỉ bỏ sản xuất mà đổi cả mô hình kinh doanh:
     - chỉ bán sỉ B2B;
     - **làm theo đơn**: mỗi SKU là hàng in riêng của một khách, chỉ sản xuất khi có đơn;
     - bán theo hợp đồng từng đơn;
     - **xuất nguyên thùng**, **giao nguyên chuyến**.
   - Đa số mục bị bỏ là do mô hình này.
   - Chỉ vài thứ đi theo sản xuất (và mua hàng):
     - role Production Staff, Procurement Staff;
     - khu `PRODUCTION` / `SUBCONTRACTOR`;
     - chuỗi cảnh báo tồn → đề xuất nhập → PO / lệnh sản xuất;
     - trả hàng hỏng cho nhà cung cấp;
     - tài liệu PO và hoá đơn NCC.
2. **Chuyển kho: không cần, trừ khi công ty thật sự có kho này cấp hàng cho kho kia.**
   - Hàng từ xưởng về kho thành phẩm là **nhập kho theo Đơn sản xuất**, không phải chuyển kho.
   - Chương 10 cũ dùng chuyển kho để cân bằng tồn, đưa hàng gần vùng nhu cầu, gom hàng cho chiến dịch, giải phóng kho đầy. Đó đều là nhu cầu của mô hình **bán từ tồn**, không liên quan sản xuất.
   - Odoo cũng vậy: chuyển giữa hai kho là cách một kho (cửa hàng, kho vệ tinh) lấy hàng bổ sung từ kho trung tâm.
   - Mô hình làm theo đơn thì Đơn sản xuất giao thẳng về kho sẽ xuất, nên luồng chính không có gì phải chuyển.
   - **Code chuyển kho hiện mới làm một nửa.** Xuất đi được, nhưng **chưa có bước nhận ở kho đích** (SCRUM-328, đã bị dời sang "phạm vi sau" từ trước đợt thu hẹp). Hàng xuất đi sẽ treo `IN_TRANSIT` mãi.
3. **QC: không cần module QC riêng ở kho, nhưng vẫn cần kiểm nhận.**
   - Odoo dùng nhập 3 bước (có khu kiểm chất lượng) khi doanh nghiệp phải kiểm hàng **mua từ nhà cung cấp** trước khi dùng.
   - Ở mô hình mới, chất lượng được giữ bằng 5 chốt (§2). Kho phụ trách chốt 4 và 5: kiểm nhận và cách ly. Tài liệu đã có cả hai.
   - Đề xuất thêm: **giữ ảnh nhận hàng** (code S3 vừa làm) làm bằng chứng tình trạng thùng khi nhận và khi nhập hàng hoàn.
4. **Đề xuất cuối: làm theo tài liệu 07/11, kèm 3 điều chỉnh.**
   - (a) **Giữ ảnh nhận hàng.** Kế hoạch cũ (Q6) đề xuất bỏ; nay đổi thành giữ.
   - (b) Sổ cái tiền cũ chỉ bỏ khi đã có phiếu thu theo đợt hợp đồng. Kế hoạch đã xếp mục này vào nhóm C.
   - (c) **Chuyển kho: bạn xác nhận** công ty có nhu cầu chuyển hàng giữa các kho thành phẩm hay không.

### Bảng tóm tắt

Cột "Lý do bỏ" có ba giá trị:
- **SX**: đi theo việc bỏ sản xuất / mua hàng.
- **MH**: đi theo mô hình B2B làm theo đơn.
- **Cả hai**.

| Mục | Lý do bỏ | Thực tế (Odoo / kho thành phẩm) | Đề xuất |
|---|---|---|---|
| 1.3 QC tại kho | Cả hai | Odoo chỉ thêm bước kiểm khi nguồn hàng chưa được kiểm (hàng mua ngoài) | **Bỏ module QC cũ, giữ kiểm nhận + cách ly + ảnh** |
| 1.3 QC hàng trả | MH | Odoo trả hàng = phiếu nhập ngược; kiểm do mình đặt thêm | Thay bằng quyết định cách ly của Quản lý kho (tài liệu đã có) |
| 1.4 Chuyển kho | MH | Odoo dùng để bổ sung hàng giữa các kho trong mô hình bán từ tồn | **Bỏ, trừ khi công ty thật sự cần** (xem §4) |
| 1.5 Hạn dùng, FEFO | MH | Odoo dành hạn dùng / FEFO cho hàng dễ hỏng | Bỏ |
| 1.5 Cảnh báo tồn, ngưỡng đặt lại | Cả hai | Odoo dùng ngưỡng min/max cho hàng bán từ tồn; hàng làm theo đơn dùng tuyến "theo đơn" | Bỏ |
| 1.6 Hàng trơn, giá trên sàn | Cả hai | Odoo B2B: khách chỉ có tài khoản khi được mời, giá ẩn, bảng giá riêng từng khách | Bỏ |
| 1.7 Khách tự đặt | MH | Odoo đặt lại từ cổng khách cần giỏ hàng; mình không có giỏ | Bỏ ("Đặt lại" = yêu cầu báo giá qua chat) |
| 1.8 Cổng thanh toán, COD | MH | Odoo: điều khoản trả nhiều đợt, đặt cọc; COD không có sẵn ở bản 18 | Bỏ |
| 1.8 Sổ cái tiền cũ | MH | Odoo: thanh toán đối trừ hoá đơn, trả theo đợt | Bỏ **sau** khi có phiếu thu theo đợt (nhóm C) |
| 1.9 Gửi kiện, chọn cách giao | MH | Kết nối hãng của Odoo thiên về gửi kiện; giao bằng xe có quản lý điều xe riêng | Bỏ |
| 1.10 Đóng gói lại, checksum, báo hỏng từng cái, wave | Cả hai | Odoo chỉ thêm bước đóng gói khi phải đóng lại hàng; hàng hỏng thì huỷ (scrap) | Bỏ, thay bằng kiểm thùng & dán nhãn, cách ly nguyên thùng |
| 1.11 Lấy lẻ, thùng mở | Cả hai | Odoo: kiện (package) + quy cách thùng, bán theo số thùng | Bỏ (nhóm C, chuyển dữ liệu tồn trước) |
| 1.12 Lớp lưu trữ, sức chứa theo đơn vị, khu PACKING | MH (khu PRODUCTION: SX) | Odoo giới hạn sức chứa theo khối lượng / số lượng / số kiện, không chia theo hàng lạnh / nguy hiểm | Bỏ |
| 1.13 Chính sách tồn cấu hình được | MH | Odoo cho cấu hình từng sản phẩm; mình chỉ có một loại hàng | Bỏ (luôn theo lô, FIFO) |
| 1.14 Đơn thay thế miễn phí, credit note | MH | Odoo: credit note; ở Việt Nam là hoá đơn điều chỉnh | Thay bằng trả + báo giá mới; hoá đơn điều chỉnh + ghi nhận đã hoàn (nhóm C) |
| 1.15 Role | Cả hai | — | Bỏ 5 role như tài liệu |
| 1.16 Code S3 | Cả hai | Odoo có kiểu kiểm "chụp ảnh" khi nhận hàng | Bỏ tài liệu PO, hoá đơn NCC, ảnh QC; **giữ ảnh nhận hàng** |
| 1.17 Thiết kế luồng cũ | MH | — | Theo kế hoạch Design Studio |

---

## 1. Vì sao phần lớn các mục không do bỏ sản xuất

Bỏ sản xuất chỉ cắt đi đoạn **phôi → in → thành phẩm**. Phần còn lại vẫn có thể là một kho bán hàng thông thường: có hàng trơn, khách tự đặt, giao kiện, chuyển kho cân bằng tồn.

Tài liệu 04–07/11 chọn thêm 5 quyết định về mô hình. Chính các quyết định này kéo theo phần lớn các mục bị bỏ:

| Quyết định mô hình (tài liệu 00) | Kéo theo bỏ |
|---|---|
| Không còn hàng trơn; mọi hàng có tồn là SKU in riêng của một khách | Hàng `STANDARD`, giá gốc, giá nhóm, giá trên sàn (1.6) |
| Đơn sản xuất chỉ phát sinh từ đơn bán; giao thẳng về kho xuất | Cảnh báo tồn, ngưỡng đặt lại (1.5); chuyển kho (1.4) |
| Khách không tự đặt; Sale chốt đơn; hợp đồng từng đơn, trả theo đợt, chuyển khoản | Giỏ, checkout, cổng thanh toán, COD, điều khoản theo đơn (1.7, 1.8) |
| Bán, nhập, xuất theo thùng; mọi thùng đúng số cái chuẩn | Lấy lẻ, thùng mở, đóng gói lại, báo hỏng từng cái (1.10, 1.11) |
| Mọi đơn giao nguyên chuyến | Gửi kiện, chọn cách giao, đối soát COD (1.9) |

Nếu muốn giữ một mục loại "MH", phải sửa lại tài liệu tương ứng:
- bảng §8 glossary;
- chương liên quan;
- open-questions.

Lý do: hội đồng chấm theo tài liệu, và code với tài liệu phải khớp.

---

## 2. Kiểm soát chất lượng ở mô hình mới: vì sao không cần module QC

QC không mất đi; nó được chuyển lên trước, về phía thiết kế và sản xuất. Kho chỉ giữ hai chốt cuối.

| Chốt | Ai làm | Ở đâu trong tài liệu |
|---|---|---|
| 1. Bản thiết kế qua tự kiểm (preflight) và được Quản lý kinh doanh duyệt | Hệ thống + Quản lý kinh doanh | 12 |
| 2. Khách duyệt **mẫu in thử** trước khi sản xuất hàng loạt (mặc định bật cho SKU sản xuất lần đầu) | Khách + Sale | 17 §4.3 bước 9 |
| 3. Đơn vị sản xuất tự kiểm chất lượng, đóng thùng đúng số chuẩn | Đơn vị sản xuất (ngoài hệ thống) | 00 §2.4 |
| 4. **Kiểm nhận**: đếm thùng, thùng đúng số chuẩn, **từ chối thùng lỗi thấy rõ** (móp, in sai, bẩn), ghi tình trạng | Nhân viên kho | 03 BR-07, BR-13, bảng ngoại lệ |
| 5. **Cách ly**: lỗi phát hiện sau khi nhập và hàng trả vào khu `QUARANTINE`; Quản lý kho quyết định gỡ cách ly hoặc ghi giảm có duyệt | Quản lý kho | 20 §4.4, §4.8; 17 §4.10 |

Đối chiếu Odoo:
- **Nhập 1, 2 hoặc 3 bước.** Bước kiểm chất lượng (Input → Quality Control → Stock) dành cho doanh nghiệp phải kiểm hàng từ nhà cung cấp trước khi đưa vào kho; không cần thì dùng nhập 1–2 bước. Mình nhập 2 bước (`RECEIVING` → bin) cộng khu `QUARANTINE`: đúng cấu hình Odoo khi nguồn hàng đã được kiểm từ trước.
- **Điểm kiểm soát chất lượng** (ứng dụng Quality, theo Odoo thuộc bản Enterprise):
  - gắn vào thao tác nhận hàng;
  - kiểu kiểm Đạt / Không đạt, đo, **chụp ảnh**…;
  - hàng không đạt được chuyển sang một vị trí riêng, tương tự khu cách ly.

  Ảnh nhận hàng giữ lại (1.16) chính là phần "chụp ảnh" này, không cần cả module.
- **Trả hàng.** Bấm trả trên phiếu giao sẽ sinh phiếu nhập ngược. Odoo không bắt buộc kiểm; ai cần thì gắn điểm kiểm vào phiếu nhập trả. Tài liệu của mình đã làm việc này bằng cách ly + quyết định của Quản lý kho.

**Khi nào nên thêm bước kiểm mẫu tại kho:** khi đối tác sản xuất bên ngoài hay giao lỗi.
- Cách nhẹ nhất: mỗi lô mở một thùng, so với ảnh bản thiết kế, ghi Đạt / Không đạt kèm ảnh. Không đạt thì cả lô vào `QUARANTINE`.
- Cách này đụng 20 BR-14 ("kho không mở thùng"), nên phải thêm quy tắc cho thùng đã mở.
- Với phạm vi khoá luận: **chưa cần**.

---

## 3. Phân tích từng mục

### 1.3 QC tại kho (và QC hàng trả)

- **Có do bỏ sản xuất không?** Một phần.
  - QC cũ gắn với phiếu nhập theo PO: hàng mua từ nhà cung cấp (phôi, hàng trơn), là nguồn chưa được kiểm.
  - Bỏ PO và sản xuất thì nguồn hàng duy nhất là đơn vị sản xuất, bên đã tự kiểm.
- **Tài liệu:** 03 BR-07 không QC tại kho; kho đếm thùng; thùng lỗi thấy rõ thì trả ngay khi giao; có ô "Ghi chú tình trạng".
- **Code hiện có:**
  - QC phiếu nhập: `QcInspection`, `moveToQc` / `inspect`, trạng thái `IN_QC`, tồn `BLOCKED`, khu `QUALITY_CONTROL`, quyền `procurement-qc-tasks`. Toàn bộ gắn với phiếu nhập theo PO.
  - FE QC: ~10 file / ~1.270 dòng.
  - QC hàng trả: `ReturnInspectionController`, quyền `sales-rma-inspections`.
- **Đề xuất:**
  - Bỏ module QC cũ và QC hàng trả.
  - Giữ kiểm nhận + cách ly (tài liệu đã có).
  - Giữ ảnh nhận hàng làm bằng chứng tình trạng thùng (1.16).
- **Nếu vẫn muốn giữ QC đầy đủ:** phải
  - chuyển QC từ phiếu nhập theo PO sang phiếu nhập theo Đơn sản xuất;
  - giữ role QC Staff;
  - thêm lại khu QC và trạng thái `IN_QC` vào tài liệu 03, 06, 20;
  - viết lại câu "đơn vị sản xuất đã kiểm".

  Tốn công mà trùng với chốt 3.

### 1.4 Chuyển kho giữa các kho

- **Có do bỏ sản xuất không?** **Không.** Chương 10 cũ nêu 4 mục đích:
  1. cân bằng tồn giữa các kho;
  2. đưa hàng gần vùng nhu cầu;
  3. gom hàng cho chiến dịch;
  4. giải phóng kho quá tải.

  Cả 4 là nhu cầu của mô hình bán từ tồn.
- **Tài liệu 07/11:**
  - Vẫn **nhiều kho** (một pháp nhân, nhiều kho vật lý), nhưng mỗi đơn xuất từ đúng một kho, và Đơn sản xuất ghi kho nhận = kho xuất (00 §6.6, 20 BR-17).
  - Hệ thống gợi ý kho xuất còn đủ hàng (open-questions C3).
  - Dời / đóng kho (hiếm) làm bằng điều chỉnh tồn có duyệt (C41; còn mở lý do điều chỉnh và việc giữ LPN / lô).
- **Odoo:**
  - Một kho được cấu hình "lấy hàng bổ sung từ kho khác". Odoo sinh **hai chứng từ**: phiếu xuất ở kho nguồn và **phiếu nhập ở kho đích**.
  - Hàng đang đi nằm ở vị trí "Inter-warehouse transit".
  - Dành cho doanh nghiệp nhiều địa điểm (kho, cửa hàng, xưởng) lấy hàng từ một kho trung tâm.
- **Code hiện có:**
  - BE inventory 22 file / ~2.150 dòng (11 endpoint).
  - BE fulfillment 8 file / ~950 dòng (12 endpoint phiên kiểm thùng chuyển kho), cộng ~250 dòng trong kế hoạch xếp xe / bàn giao.
  - FE ~123 file / ~14.400 dòng.
  - **Thiếu nửa sau:**
    - Vòng đời có `PARTIALLY_RECEIVED` / `RECEIVED` / `COMPLETED` / `CLOSED`, nhưng **không có code nào chuyển sang các trạng thái đó** và không có endpoint nhận.
    - `TransferOrders.java` ghi rõ "Receiving at the destination is SCRUM-328"; commit 99ed0bc của kltn-docs đã dời bước nhận chuyển kho sang phạm vi sau.
    - Khi xuất, tồn kho nguồn bị trừ (dòng sổ `TRANSFER_OUT`) rồi hàng treo `IN_TRANSIT`, không bao giờ vào kho đích.
  - Dòng lệnh tính theo **SKU + lô + số cái** (`TransferOrders.NewLine`), chưa theo thùng LPN.
  - Phiên kiểm thùng chuyển kho dựa trên trạm đóng gói cũ (đang bị thay) và dùng `StorageClass.FRAGILE` (đang bị bỏ).
- **Đề xuất:** **Bỏ**, trừ khi công ty thật sự có một trong các tình huống sau một cách thường xuyên:
  - một kho cấp hàng cho kho khác;
  - hàng dư của khách nằm ở kho A mà đơn sau của khách bắt buộc xuất ở kho B;
  - kho đầy phải dồn hàng sang kho khác.

  Trường hợp hiếm (dời / đóng kho) dùng điều chỉnh có duyệt như tài liệu.
- **Nếu giữ:** xem §4. Tối thiểu phải làm thêm bước nhận ở kho đích và đổi sang thùng LPN.

### 1.5 Hạn dùng, FEFO, cảnh báo tồn

- **Hạn dùng / FEFO**
  - **Có do bỏ sản xuất không?** Không.
  - **Tài liệu:** ly / bao bì không có hạn dùng; xuất FIFO theo ngày nhập; lô = mã Đơn sản xuất để truy vết.
  - **Odoo:** hạn dùng chỉ áp cho hàng theo dõi lô / serial, đi kèm cách xuất FEFO. Odoo nói FEFO hợp với hàng dễ hỏng.
  - **Đề xuất:** **Bỏ.** Ngoại lệ: công ty bán ly PLA / phân huỷ sinh học có hạn khuyến nghị và muốn truy theo hạn. Khi đó vẫn có thể chỉ dùng lô + ngày nhập.
- **Cảnh báo tồn / ngưỡng đặt lại / tồn an toàn**
  - **Có do bỏ sản xuất không?** Có một phần: đầu ra của cảnh báo là đề xuất nhập → PO / lệnh sản xuất.
  - **Odoo:** quy tắc min/max để giữ tồn dự báo trên một ngưỡng, dành cho hàng bán từ tồn. Hàng làm theo đơn dùng tuyến "theo đơn" (MTO): xác nhận đơn bán thì sinh lệnh mua / sản xuất gắn với đơn đó.
  - Mô hình mới của bạn chính là MTO: Đơn sản xuất sinh từ đơn bán.
  - **Đề xuất:** **Bỏ** (22 file / ~1.560 dòng, 2 job định kỳ; FE ~10 file).

### 1.6 Hàng trơn và giá trên sàn

- **Có do bỏ sản xuất không?** Một phần. Hàng trơn bán lại thì phải mua từ nhà cung cấp (PO), mà PO đã bỏ.
- **Odoo B2B:**
  - tài khoản khách chỉ tạo khi được mời (Odoo nói phù hợp B2B muốn ẩn giá);
  - chặn bán sản phẩm giá 0, thay nút mua bằng "Liên hệ";
  - bảng giá riêng gán cho từng khách.

  Khớp với tài liệu: khách không tự đăng ký, sàn ẩn giá, giá nội bộ theo (khách, SKU riêng) chỉ nhân viên xem.
- **Đề xuất:** **Bỏ.** Odoo không có sẵn khái niệm "sản phẩm riêng của một khách"; SKU riêng của khách là thiết kế riêng của dự án, nên giữ.

### 1.7 Khách tự đặt hàng

- **Có do bỏ sản xuất không?** Không.
- **Odoo:** "đặt lại từ cổng khách" thêm các dòng của đơn cũ vào **giỏ hàng** rồi đi checkout. Báo giá thì khách ký / thanh toán online trên cổng để xác nhận.
- **Tài liệu:** không có giỏ. Đơn do Sale tạo từ báo giá khách đã chấp nhận, mỗi đơn có hợp đồng. "Đặt lại" mở chat (13 §4.6.3, 14 §4.1 bước 6).
- **Đề xuất:** **Bỏ** checkout, giỏ, API giá checkout, kiểm đơn trùng. Giữ tạm `OrderService.placeOrder` cho 31 file test như kế hoạch.

### 1.8 Thanh toán online, điều khoản theo đơn, COD, sổ cái tiền cũ

- **Có do bỏ sản xuất không?** Không.
- **Odoo:**
  - điều khoản thanh toán chia nhiều đợt (ví dụ 30% / 70%);
  - đặt cọc trên đơn bán;
  - ghi nhận thanh toán và đối trừ với hoá đơn.

  COD không có trong tài liệu Odoo 18; chỉ có ứng dụng bên thứ ba.
- **Tài liệu:**
  - lịch thanh toán theo đợt của hợp đồng, với mốc `ON_SIGNING` / `BEFORE_DELIVERY` / `AFTER_DELIVERY`;
  - chỉ chuyển khoản;
  - Kế toán lập phiếu thu theo đợt, ghi nhận đã hoàn (15, rút gọn 06/11).
- **Đề xuất:**
  - Bỏ tàn dư cổng thanh toán và COD.
  - Sổ cái tiền cũ (chuyển khoản / phân bổ / credit / đảo) **chỉ bỏ sau khi có phiếu thu theo đợt**; bỏ trước thì không ghi nhận được tiền (nhóm C, như kế hoạch).

### 1.9 Gửi kiện qua hãng, chọn cách giao

- **Có do bỏ sản xuất không?** Không.
- **Odoo:**
  - phương thức giao: giá cố định, theo quy tắc, hoặc kết nối hãng (FedEx, UPS, DHL…) — thiên về gửi kiện;
  - bản 18 có thêm **quản lý điều xe**: gom phiếu giao theo xe / cửa xuất, kiểm khối lượng và thể tích xe — dành cho doanh nghiệp tự có xe.
- **Tài liệu:**
  - mọi đơn giao nguyên chuyến qua kế hoạch xếp hàng 3D;
  - đơn nhỏ nội thành đặt xe tải / van qua API (09 BR-29);
  - có mã vận đơn và webhook.
- **Đề xuất:** **Bỏ.** Code và migration đi chung một PR như kế hoạch: cột `delivery_mode` default `PARCEL`, trong khi CHECK của chuyến đòi `FULL_TRUCKLOAD`.

### 1.10 Đóng gói lại, checksum thiết kế, báo hỏng từng cái, wave

- **Có do bỏ sản xuất không?** Một phần.
  - Checksum thiết kế lúc đóng gói tồn tại để bắt lỗi xưởng in sai mẫu; nay SKU đã là thiết kế.
  - Trả hàng hỏng cho NCC đi theo việc bỏ NCC.
- **Odoo:**
  - giao 3 bước (lấy → đóng gói → xuất) chỉ khi phải đóng lại hàng trước khi giao;
  - hàng hỏng không trả được thì huỷ (scrap) vào vị trí ảo;
  - lấy theo đợt / theo sóng cho kho nhiều đơn.
- **Tài liệu:**
  - hàng về đã đóng thùng; thùng LPN là kiện giao;
  - 08 thu gọn thành kiểm thùng & dán nhãn;
  - hỏng thì cách ly nguyên thùng hoặc điều chỉnh `DAMAGED` có duyệt.
- **Đề xuất:** **Bỏ.** Wave picking là code chết, bỏ ngay. Báo hỏng có ảnh hiện tính theo **từng cái** và gắn trả NCC (29 file / ~3.550 dòng), không hợp mô hình nguyên thùng.

### 1.11 Lấy lẻ, thùng đã mở, thùng lẻ

- **Có do bỏ sản xuất không?** Một phần. Hàng lẻ trong kho chủ yếu sinh từ cấp phôi, xuất xưởng và phần lẻ khi nhập.
- **Odoo:**
  - "kiện" (package) = thùng vật lý, tương đương LPN;
  - "quy cách đóng gói" (packaging) = số cái / thùng; bán và mua theo số thùng;
  - có cách xuất "ít kiện nhất".

  Khớp mô hình nguyên thùng.
- **Đề xuất:** **Bỏ** (nhóm C). Seed hiện có 226/228 dòng tồn là hàng lẻ, 212 dòng không có lô, nên phải chuyển dữ liệu tồn vào thùng LPN có lô trước.

### 1.12 Lớp lưu trữ, sức chứa theo đơn vị, khu PACKING

- **Có do bỏ sản xuất không?** Không. Riêng các vị trí `PRODUCTION` / `SUBCONTRACTOR` thì có.
- **Odoo:** "nhóm lưu trữ" giới hạn sức chứa theo khối lượng tối đa, số lượng mỗi sản phẩm, hoặc số kiện; quy tắc cất hàng theo sản phẩm / nhóm / loại kiện. Không xoay quanh hàng lạnh / nguy hiểm.
- **Tài liệu:**
  - sức chứa m³ tự tính + tải trọng;
  - kiểm thùng vừa bin theo kích thước; không vừa thì cất `OVERFLOW`;
  - khu `PACKING` gộp vào `DISPATCH`.
- **Đề xuất:** **Bỏ.** Không xoá được các bản ghi vị trí loại cũ (sổ cái có FK trỏ tới); đổi sang loại hợp lệ rồi để `INACTIVE`.

### 1.13 Chính sách tồn cấu hình được (lô / serial / cách xuất)

- **Có do bỏ sản xuất không?** Không.
- **Odoo:** bật theo dõi lô hoặc serial cho từng sản phẩm; cách xuất (FIFO, LIFO, FEFO, gần nhất, ít kiện nhất) đặt theo vị trí hoặc nhóm sản phẩm.
- **Tài liệu:** chỉ có một loại hàng, nên chính sách cố định: luôn theo lô (lô = mã Đơn sản xuất), FIFO.
- **Đề xuất:** **Bỏ** endpoint `PUT /inventory/items/{id}/policy` và serial.

### 1.14 Đổi trả: QC trả hàng, đơn thay thế miễn phí, credit note

- **Có do bỏ sản xuất không?** Không.
- **Odoo:** trả hàng → phiếu nhập ngược; đã xuất hoá đơn thì lập credit note.
- **Việt Nam:** sai sót hoặc trả hàng xử lý bằng **hoá đơn điều chỉnh / thay thế**. Odoo 18 bản Việt Nam tích hợp SInvoice (Viettel), có cả hoá đơn điều chỉnh và thay thế.
- **Tài liệu:**
  - chỉ đổi / trả khi lỗi sản xuất;
  - RMA → phiếu nhập hàng hoàn quét LPN → `QUARANTINE` → Quản lý kho quyết định;
  - hoàn tiền: hệ thống tính số phải hoàn, Kế toán ghi nhận đã hoàn;
  - hoá đơn điều chỉnh giảm (21 §4.4);
  - đổi hàng = trả + báo giá / đơn mới (17 §4.10).
- **Đề xuất:** **Thay** (nhóm C), không bỏ trống. Chỉ bỏ code cũ sau khi có phiếu nhập hàng hoàn theo LPN.

### 1.15 Role

- **Có do bỏ sản xuất không?**
  - Production Staff, Procurement Staff: có.
  - QC Staff: theo quyết định ở 1.3.
  - Inventory Planner: việc của role này là lập kế hoạch bổ sung tồn (cảnh báo, ngưỡng đặt lại, chuyển kho, yêu cầu sản xuất bổ sung). Mô hình làm theo đơn thì role này hết việc.
  - Order Coordinator: tổ chức nhân sự. Tài liệu chuyển việc cho Sale và Quản lý kho.
- **Đề xuất:** **Bỏ 5 role như tài liệu.** Nếu giữ chuyển kho thì Quản lý kho tạo và duyệt, không cần Planner.

### 1.16 Code S3 vừa làm

| Phần | Đề xuất | Lý do |
|---|---|---|
| Tài liệu đính kèm PO, hoá đơn NCC | Bỏ | Đi cùng PO |
| Ảnh giai đoạn QC | Bỏ | Đi cùng QC |
| **Ảnh nhận hàng** (`goods_receipt_photos`, giai đoạn RECEIPT) | **Giữ** (đổi so với Q6 cũ) | Bằng chứng khi từ chối thùng lỗi hoặc ghi tình trạng (móp, ướt, đã mở); dùng cho phiếu hàng hoàn vào cách ly. Bảng phiếu nhập được giữ nên gần như không tốn công. Cần thêm một dòng vào tài liệu 03. |
| Storage DOCUMENT | Giữ | Lưu hợp đồng đã ký (14) |
| Upload thẳng S3, preflight PDF, ảnh hàng trả, quét orphan, Object Lock | Giữ | Vẫn dùng |

### 1.17 Thiết kế theo luồng cũ

- **Có do bỏ sản xuất không?** Không. Tài liệu 12 (05/11) đổi sang khách tự thiết kế, Quản lý kinh doanh duyệt.
- **Đề xuất:** Theo kế hoạch Design Studio. Kế hoạch dọn chỉ đụng phần hard-code QC (`DesignWorkflowService.java:334`).

---

## 4. Nếu vẫn muốn giữ chuyển kho

Bản tối thiểu khớp với mô hình mới:

1. **Tài liệu:** khôi phục chương 10 bản rút gọn.
   - Lệnh chuyển **theo thùng LPN**, kho nguồn ≠ kho đích.
   - Quản lý kho tạo và duyệt.
   - Bỏ ngưỡng duyệt và gửi kiện.
   - Sửa 00 §6.6, 20 BR-17, glossary §8, C4 / C14 / C26 / C35 / C41.
2. **Xuất ở kho nguồn:** lấy nguyên thùng bằng pick task như đơn bán. Thay phiên kiểm thùng chuyển kho cũ bằng kiểm thùng & dán nhãn. Kế hoạch xếp xe đã nhận nguồn là lệnh chuyển kho, giữ lại.
3. **Nhận ở kho đích (SCRUM-328, hiện chưa có):**
   - Phiếu nhập loại `TRANSFER` tham chiếu lệnh; quét LPN.
   - Vào `RECEIVING` rồi cất hàng, giống phiếu nhập theo Đơn sản xuất.
   - Xử lý thiếu / thừa; đóng lệnh.
4. **Đổi dòng lệnh** từ SKU + lô + số cái sang danh sách thùng LPN.
5. **Dữ liệu và quyền:**
   - giữ bảng `transfer_order(_line)` và quyền `inventory-transfer-orders`;
   - bỏ cột `transfer_*` ở bảng đóng gói cũ;
   - thêm loại phiếu nhập.

Đây là một tính năng mới cỡ vừa, không phải "giữ nguyên code cũ". Chỉ nên làm khi công ty thật sự cần.

---

## 5. Nguồn Odoo 18 đã đối chiếu

Gốc: `https://www.odoo.com/documentation/18.0/applications/`

- Chuyển giữa các kho: `inventory_and_mrp/inventory/warehouses_storage/replenishment/resupply_warehouses.html`
- Nhập 3 bước: `inventory_and_mrp/inventory/shipping_receiving/daily_operations/receipts_three_steps.html`
- Điểm kiểm soát chất lượng, vị trí hàng không đạt:
  - `inventory_and_mrp/quality/quality_management/quality_control_points.html`
  - `.../failure_locations.html`
- Lô, hạn dùng, cách xuất:
  - `inventory_and_mrp/inventory/product_management/product_tracking/lots.html`
  - `.../expiration_dates.html`
  - `inventory_and_mrp/inventory/shipping_receiving/removal_strategies.html`
- Ngưỡng đặt lại, tuyến theo đơn:
  - `inventory_and_mrp/inventory/warehouses_storage/replenishment/reordering_rules.html`
  - `.../mto.html`
- Kiện và quy cách đóng gói:
  - `inventory_and_mrp/inventory/product_management/configure/package.html`
  - `.../packaging.html`
- Nhóm lưu trữ, quy tắc cất hàng:
  - `inventory_and_mrp/inventory/shipping_receiving/daily_operations/storage_category.html`
  - `.../putaway.html`
- Giao 3 bước, lấy theo đợt / sóng, điều xe:
  - `.../daily_operations/delivery_three_steps.html`
  - `.../picking_methods/batch.html`
  - `.../picking_methods/wave.html`
  - `.../setup_configuration/dispatch.html`
- Huỷ hàng hỏng: `inventory_and_mrp/inventory/warehouses_storage/inventory_management/scrap_inventory.html`
- Trả hàng: `sales/sales/products_prices/returns.html`
- B2B: tài khoản khách và ẩn giá:
  - `websites/ecommerce/customer_accounts.html`
  - `websites/ecommerce/products/price_management.html`
- Bảng giá theo khách: `sales/sales/products_prices/prices/pricing.html`
- Điều khoản trả nhiều đợt, đặt cọc, credit note:
  - `finance/accounting/customer_invoices/payment_terms.html`
  - `sales/sales/invoicing/down_payment.html`
  - `finance/accounting/customer_invoices/credit_notes.html`
- Phương thức giao: `inventory_and_mrp/inventory/shipping_receiving/setup_configuration/new_delivery_method.html`
- Hoá đơn điện tử Việt Nam (SInvoice): `finance/fiscal_localizations/vietnam.html`

Giới hạn khi đối chiếu:
- Một số trang chỉ đọc được qua bản tóm tắt của công cụ.
- Odoo không ghi rõ trên từng trang tính năng nào thuộc Community hay Enterprise.
- Các chi tiết đã ghi "theo Odoo" ở trên là phần đọc được và khớp giữa hai lần tra độc lập.
