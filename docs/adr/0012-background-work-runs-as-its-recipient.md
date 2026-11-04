# ADR-0012: Việc chạy nền của trợ lý chạy bằng quyền của chính người nhận

Trạng thái: đề xuất (2026-11-05), chờ team chấp nhận; đã xây dựng trên nhánh `feature/ai-assistant`. Thiết kế:
[`docs/assistant-design.md`](../assistant-design.md) §9.1. Bổ sung cho ADR-0008 (quyền tính ở server) và ADR-0011.

## Bối cảnh

Trợ lý muốn tự lên tiếng: báo cho một người khi danh sách của họ có mục mới (đơn vừa bị giữ, PO vừa trễ), và tùy chọn
tự trả lời một câu hỏi cố định về mục đó. Việc này chạy theo lịch, không có request nào, nên không có token, quyền hay
data scope nào.

Hai cách hiển nhiên đều sai:

- **Chạy bằng quyền hệ thống.** `WarehouseScope` không giới hạn một caller không có scope, nên một job như vậy thấy mọi
  kho. Quản lý một kho sẽ được báo về kho khác, và số liệu toàn công ty lộ cho người mà màn hình của họ giấu đi. Bản tin
  buổi sáng ban đầu bị bỏ chính vì lý do này (thiết kế §9).
- **Mang theo quyền của lần đăng nhập gần nhất.** Quyền bị rút hay tài khoản bị khóa sau lần đó vẫn còn hiệu lực, đúng
  thứ ADR-0008 đã loại bỏ.

## Quyết định

**`common.security.RunAs` là nơi duy nhất dựng quyền ngoài request.** Nó dựng đúng quyền mà một request của người đó sẽ
có vào lúc ấy:

- vai trò và trạng thái tài khoản đọc từ database (`UserAccountLookup`, do identity cài);
- quyền và scope qua `RoleAuthorizationLookup`;
- kho qua `UserWarehouseLookup` khi scope là kho;
- token có claim `act = {"sub": "assistant-watch"}` (actor claim của RFC 8693). `CurrentUserProvider.auditName` đọc claim
  này, nên cột `created_by` và nhật ký audit ghi "`username via assistant-watch`" cho mọi thứ được ghi trong lúc đó.
  Token không có chữ ký và không bao giờ được gửi đi.

**Từ chối trước khi chạy:** người dùng không tồn tại, tài khoản không ACTIVE hoặc đang bị khóa, tài khoản không có vai
trò nào, tài khoản khách hàng (`CurrentUser.isPortalCustomer`), và khi không đọc được quyền. Không biết quyền của ai thì
coi như họ không có quyền.

**Chạy trên thread gọi rồi trả lại nguyên trạng.** Security context và data scope của nơi gọi được khôi phục, kể cả khi
việc chạy ném lỗi.

**Chỉ lớp theo dõi của trợ lý được gọi `RunAs`.** Mỗi nơi gọi mới là một cách mới để hành động thay ai đó, nên phải được
thêm vào rule một cách có chủ ý và được review. Bốn rule của `ArchitectureTest` giữ điều này:
- `onlyTheAssistantsWatchActsForAUser`: chỉ `AssistantWatch` phụ thuộc `RunAs`;
- `onlyTheConverterAndRunAsBuildAnAuthorization`: chỉ bộ chuyển đổi JWT và `RunAs` tạo `StockflowAuthenticationToken`
  (rule không chặn được mọi cách đặt security context, chỉ cách dựng quyền của hệ thống);
- `onlyItsJobsUseTheWatch`: chỉ job của lớp theo dõi giao người dùng cho nó; `AssistantWatch.look` là package-private;
- `onlyTheWatchAsksProactively`: chỉ lớp theo dõi gọi `askProactively`, câu hỏi không tính vào giới hạn của người
  dùng.

`RunAs` lấy ba lookup của identity khi dùng (không phải lúc khởi động), vì `common` dùng chung cho mọi module và test
của một module không có identity vẫn phải chạy được.

**Lớp theo dõi chỉ dùng những gì người đó tự dùng được, và chỉ việc của họ.**

- Mỗi danh sách là một tool của khung chat, chạy qua đúng guard của nó, và được đọc trọn chứ không lấy mười dòng của
  thẻ "hôm nay".
- Một danh sách chỉ được đọc cho người vừa có tool, vừa có một trong các vai trò của danh sách (`watch.roles`): có quyền
  đọc chưa chắc đó là việc của mình.
- Người không có quyền dùng trợ lý (`assistant-conversations:CREATE`) không được xem hộ.
- Câu trả lời chủ động đi qua `AssistantService.askProactively`, có `@RequiresPermission` như một câu hỏi, và chỉ được
  đưa các tool câu đó cần. Lời gọi tới tool khác bị từ chối mà không chạy (`NOT_OFFERED`). Đề xuất nhập hàng trong đó
  thành đề xuất của chính người đó và chờ họ xác nhận (ADR-0011).
- Tầm nhìn của người dùng (scope, kho) được lưu cùng trạng thái theo dõi. Khi nó đổi, lần nhìn sau chỉ ghi nhận, nên quản
  lý chuyển kho không nhận tin cũ của kho mới.
- Job không bao giờ chạy khi tắt security: lúc đó không có data scope nào, và quyền đọc được không phải quyền thật.

## Hệ quả

- Một người bị rút vai trò, bị khóa hay bị chuyển kho thì từ lần quét kế tiếp không nhận gì về những thứ họ không còn
  thấy. Không có cache quyền nào phải xóa; lớp theo dõi chỉ giữ những gì nó đã thấy, và bỏ đi khi tầm nhìn đổi.
- Mỗi lần quét đọc quyền của từng người từ Redis (hoặc Postgres khi Redis mất), như một request.
- Lời gọi tool của lớp theo dõi được ghi vào `assistant.tool_call` với kênh PROACTIVE nhưng không kèm kết quả, vì cùng
  danh sách được đọc lại vài phút một lần. Những gì lớp theo dõi rút ra nằm trong `assistant.watch_item` và hộp thư.
- Một job khác muốn hành động thay người dùng (ví dụ token MCP ở P7) phải đi qua `RunAs` và được thêm vào rule kiến trúc
  có chủ ý.
