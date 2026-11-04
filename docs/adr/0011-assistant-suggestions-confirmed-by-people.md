# ADR-0011: Trợ lý đề xuất, người dùng xác nhận; model không bao giờ ghi

Trạng thái: đề xuất (2026-11-04), chờ team chấp nhận; đã xây dựng trên nhánh `feature/ai-assistant`. Thiết kế:
[`docs/assistant-design.md`](../assistant-design.md) §8.2. Bổ sung cho ADR-0010, không thay nó.

## Bối cảnh

Tới ngày 04/11 trợ lý chỉ đọc. `proposeReorder` trả các đề xuất PO, mỗi nhà cung cấp một đề xuất, và frontend mở form
tạo PO đã điền sẵn; ảnh phiếu giao hàng được đọc thành một phiếu nhập nháp để người nhận hàng gõ lại trên màn phiếu
nhập. Hai chỗ hở:

- **Đề xuất không có danh tính.** Nó được dựng lại từ `tool_call.result` mỗi lần đọc hội thoại. Không biết đề xuất nào
  đã thành PO, đề xuất nào bị bỏ, nên không đo được trợ lý có ích hay không (tỉ lệ chấp nhận), và một đề xuất mở lại
  ngày hôm sau vẫn trông như mới.
- **Từ đề xuất tới chứng từ là gõ lại tay.** Phiếu nhập nháp từ ảnh phải nhập lại từng dòng; đó là chỗ sai số.

Câu hỏi là trợ lý được làm đến đâu. Ba phương án:

1. **Tool ghi cho model** (`createPurchaseOrder` như một tool). Loại. Model quyết định khi nào ghi, nên một lần prompt
   injection trong tên hàng hay ghi chú đơn trở thành một chứng từ thật. Không có chỗ nào để người dùng sửa số trước
   khi nó thành chứng từ, và lời gọi đi qua quyền của tool, không phải quyền của màn hình chứng từ.
2. **Giữ như cũ:** frontend điền form, người dùng gửi qua endpoint của procurement. An toàn, nhưng không có danh tính,
   không đo được, và phiếu nhập vẫn phải gõ lại.
3. **Đề xuất được lưu; người dùng xác nhận.** Chọn.

## Quyết định

**Model không có tool ghi nào, và sẽ không có.** Mỗi đề xuất là một dòng `assistant.action` của chính người đã hỏi.
Chứng từ chỉ được tạo khi người đó bấm xác nhận, qua endpoint mang đúng guard của màn hình chứng từ đó.

- **Lưu cùng câu trả lời.** Đề xuất của `proposeReorder` được ghi trong cùng transaction lưu câu trả lời
  (`ConversationService.appendAndRelease`), nên một đề xuất hiện dưới câu trả lời luôn có dòng của nó. Đọc ảnh phiếu
  giao hàng ghi một đề xuất phiếu nhập không gắn với hội thoại nào.
- **Mỗi nhu cầu một đề xuất mở.** Đề xuất PO mới thay đề xuất PO còn mở của cùng người và cùng nhà cung cấp khi nó
  đặt đủ mọi SKU của đề xuất cũ; đề xuất cũ thành SUPERSEDED. Không có quy tắc này, hỏi lại cùng một câu để lại hai
  bản nháp chờ xác nhận, và vì `proposeReorder` không trừ PO nháp, xác nhận cả hai là đặt cùng một hàng hai lần. Đề
  xuất cũ có SKU mà đề xuất mới không có (hỏi ly, rồi hỏi nắp, cùng một nhà cung cấp) là một nhu cầu khác và vẫn mở.
  Hai đề xuất tạo cùng lúc ở hai hội thoại vẫn có thể cùng mở; khi đó lần xác nhận thứ hai trả về cảnh báo
  `possibleDuplicate` của chính màn PO.
- **Phiếu nhập không thay nhau.** Đọc lại phiếu giao của cùng một PO có thể là chụp lại, cũng có thể là chuyến xe
  thứ hai, và trợ lý chưa đọc số phiếu giao để phân biệt. Vì vậy bản đọc mới không thay bản cũ; xác nhận một phiếu
  nhập khi chính người đó đã xác nhận một phiếu khác cho cùng PO trong 24 giờ thì trả về `possibleDuplicate`. Bản đọc
  không khớp dòng nào của PO không được lưu thành đề xuất, vì không có gì để xác nhận.
- **Chỉ chủ sở hữu.** Mọi truy vấn có điều kiện `owner_user_id` tường minh, không dựa vào data scope: một quản trị
  viên có scope ALL vẫn không thấy và không xác nhận được đề xuất của người khác (404 như một id không có).
- **Guard là của màn hình chứng từ.** Xác nhận PO mang `@RequiresPermission(procurement-purchase-orders, CREATE)`,
  guard của `POST /api/v1/purchase-orders`; xác nhận phiếu nhập mang `procurement-goods-receipts` CREATE và kiểm thêm
  UPDATE, vì màn phiếu nhập cần cả hai để tạo rồi đếm. Data scope do aspect đặt là scope mà procurement kiểm kho
  theo. Người không tạo được PO trên màn PO thì không tạo được ở đây. Test so annotation của hai endpoint với
  annotation của màn hình.
- **Chỉ tạo bản nháp.** PO là DRAFT; gửi, duyệt và gửi nhà cung cấp vẫn ở màn PO, dưới quy tắc bốn mắt của nó.
  Phiếu nhập là DRAFT đã đếm; xác nhận nhập kho vẫn ở màn phiếu nhập.
- **Đúng một lần.** Dòng action bị khóa (`FOR UPDATE`); chứng từ và trạng thái CONFIRMED được ghi trong cùng
  transaction. Xác nhận lần hai trả đúng chứng từ lần đầu. Một lần bị procurement từ chối (dưới MOQ, sai quy cách,
  kho ngoài scope, thiếu lô hay hạn dùng) cuộn lại toàn bộ và để đề xuất mở, để sửa rồi xác nhận lại; không có
  trạng thái FAILED.
- **Nhật ký audit nói đúng.** Tạo PO và tạo phiếu nhập là các method có `@Auditable` của procurement, ở đây chạy bên
  trong transaction của lần xác nhận. `AuditAspect` vì vậy được sửa (lớp nền dùng chung): khi method có audit tham gia
  một transaction do nơi gọi mở, entry SUCCESS chỉ được ghi khi transaction đó commit; nếu nó rollback, entry được ghi
  là FAILURE "rolled back". Trước đây, một phiếu nhập bị từ chối ở bước ghi dòng vẫn để lại "goods-receipt CREATE
  SUCCESS" cho một phiếu không tồn tại. Method không có transaction bao ngoài, và method tự khai báo transaction riêng
  (`REQUIRES_NEW`) hoặc không chạy trong transaction (`NOT_SUPPORTED`, `NEVER`), vẫn được ghi ngay như cũ.
- **Xác nhận đúng cái đã đề xuất.** Cùng nhà cung cấp, chỉ các SKU đã đề xuất (đổi số lượng hay bỏ dòng được, thêm
  SKU thì làm trên màn PO); phiếu nhập chỉ trên PO mà ảnh đã được đọc cho.
- **Quy tắc của chứng từ do module của nó áp.** Giá là giá hiện hành trong điều khoản nhà cung cấp (`unitPrice` để
  trống), MOQ và quy cách, nhà cung cấp còn hoạt động, dung sai nhận, lô, hạn dùng, khu nhận hàng: procurement kiểm
  như với màn hình. Trợ lý không làm tròn lại số người dùng đã bấm; số sai bị từ chối kèm lý do của procurement.
- **Hết hạn sau 24 giờ.** Số trong đề xuất là số của lúc hỏi. EXPIRED được tính lúc đọc, không ghi; quá hạn thì
  xác nhận trả 409 `ASSISTANT_ACTION_EXPIRED` và người dùng hỏi lại. Đã xác nhận hay đã bỏ thì không bao giờ hết hạn.
- **Đo được.** Counter `assistant.actions{kind, outcome, edited}`: suggested, confirmed (kèm người dùng có sửa trước
  khi xác nhận không), refused, dismissed, superseded. Tỉ lệ chấp nhận là confirmed / suggested.
- **Lời dặn model.** System prompt cấm nói rằng đã tạo, gửi hay duyệt chứng từ: model nói cái được đề xuất, và rằng
  người dùng xác nhận nó.

## Hệ quả

- Phạm vi ghi của trợ lý là hai chứng từ nháp, mỗi chứng từ qua đúng một service của procurement, dưới đúng quyền
  của màn hình. Thêm loại đề xuất thứ ba là thêm một `Kind`, một endpoint có guard của màn hình tương ứng và một test
  so guard.
- `procurement :: api` có thêm `GoodsReceiptDrafting` (tạo phiếu nhập nháp có dòng), chỉ thêm, không sửa hàm cũ.
- Một đề xuất bị bỏ, hết hạn hay bị từ chối không để lại gì ngoài dòng của nó. Dòng của đề xuất trong hội thoại bị
  xóa cùng hội thoại; dòng không gắn hội thoại (phiếu nhập từ ảnh) bị job retention xóa theo cùng thời hạn.
- PO tạo từ đề xuất là một PO bình thường; nó không ghi nguồn gốc. Chứng từ được tham chiếu bằng khóa ngoại thật
  (`purchase_order_id`, `goods_receipt_id`, ADR-0007), nhưng dòng đề xuất chỉ sống bằng hội thoại của nó (mặc định 90
  ngày), nên sau đó không còn biết PO nào do trợ lý đề xuất. Người tạo PO vẫn có trong nhật ký audit. Muốn màn PO hiện
  "tạo từ trợ lý" lâu dài thì cần một cột trong `procurement`, việc của người phụ trách module.
- Phần chạy nền (P6) dùng lại đúng cơ chế này: một đề xuất do agent chủ động tạo cũng chỉ là SUGGESTED của chính
  người nhận, chờ người đó xác nhận.
