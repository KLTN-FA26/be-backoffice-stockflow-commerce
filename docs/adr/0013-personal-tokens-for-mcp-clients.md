# ADR-0013: Token cá nhân cho client MCP, chỉ mở endpoint MCP

Trạng thái: đề xuất (2026-11-05), chờ team chấp nhận; đã xây dựng trên nhánh `feature/ai-assistant`. Thiết kế:
[`docs/assistant-design.md`](../assistant-design.md) §7, mục MCP. Dựa trên ADR-0008 (quyền tính ở server) và ADR-0012
(`RunAs`).

## Bối cảnh

`POST /api/v1/assistant/mcp` cho một AI client khác (Claude Code, Claude Desktop, Cursor) dùng các tool đọc của trợ lý,
dưới quyền của người dùng. Trước P7, client gửi chính token JWT của lần đăng nhập. Cách đó có ba vấn đề:

- **Token sống 8 giờ, còn client được cài một lần và chạy nhiều tuần.** Người dùng phải lấy lại token và sửa file cấu
  hình mỗi ngày. Backend playground còn sinh lại khóa ký mỗi lần khởi động, nên mọi token hỏng theo.
- **Token đăng nhập là cả phiên làm việc.** Nó mở mọi endpoint mà người đó được dùng, kể cả endpoint ghi. Nằm dạng chữ
  trong file cấu hình của một client AI, nó là một credential quá rộng.
- **Không thu hồi riêng được.** Muốn chặn một client thì phải đóng cả phiên của người dùng.

OAuth 2.1 với Dynamic Client Registration, như bản đặc tả MCP mô tả, là lời giải đầy đủ. Nhưng nó cần một authorization
server, trang đồng ý, và các client chưa hỗ trợ đồng đều. Nó quá lớn cho phạm vi đồ án.

## Quyết định

**Token cá nhân cho MCP, chỉ nói đó là ai.**

- Người dùng tự tạo token cho một client, qua `POST /api/v1/assistant/mcp-tokens`, đăng nhập bằng JWT. Token là
  `sfmcp_` cộng 32 byte ngẫu nhiên. Nó chỉ hiện một lần; bảng `assistant.mcp_token` giữ SHA-256 của nó, tên, 4 ký tự
  cuối, hạn dùng (tối đa 90 ngày), lần dùng cuối và lúc thu hồi.
- **Chỉ dùng được ở endpoint MCP.** Port `common.security.PersonalTokens` (assistant cài bằng `McpTokens`) khai báo
  tiền tố và đường dẫn nhận token. `ResourceServerSecurityConfig` chọn `AuthenticationManager` theo từng request: đúng
  đường dẫn và đúng tiền tố thì dùng `PersonalTokenAuthenticationProvider`; mọi trường hợp khác dùng bộ giải mã JWT như
  trước, nên token cá nhân bị 401 ở mọi nơi khác, kể cả endpoint tạo token.
- **Quyền là quyền hiện tại.** `PersonalTokenAuthenticationProvider` tìm chủ token, rồi gọi
  `RunAs.authenticationFor(chủ token, "assistant-mcp")`: cùng cách dựng quyền với lớp theo dõi và với converter của
  JWT. Đổi vai trò, khóa tài khoản hay rút quyền đều có hiệu lực ở lần gọi sau, không ai phải thu hồi token.
- **Quyền riêng `assistant-mcp:CREATE`, opt-in**, mở endpoint MCP và các endpoint token, tách khỏi khung chat.
  Migration `V20261105001100` chỉ cấp nó cho SYSTEM_ADMIN. Doanh nghiệp cấp cho vai trò nào được dùng MCP (AQ-17): một
  token sống tới 90 ngày và gửi dữ liệu tới nhà cung cấp model mà client của người dùng chọn, nên đó không phải mặc
  định đi kèm việc bật MCP.
- **Chỉ tạo được ngay sau khi đăng nhập** (15 phút, theo `iat` của token đăng nhập). Script chạy trong trang back
  office dùng được phiên của người dùng, nhưng không đổi được phiên đó thành một credential sống lâu hơn nó.
- **Có kiểm toán và có đường cho quản trị viên.** Tạo và thu hồi là GRANT và REVOKE trong nhật ký audit. Quản trị viên
  xem và thu hồi mọi token của một người dưới quyền của màn hình người dùng (`identity-users`).

## Hệ quả

- Client cấu hình một lần cho tới 90 ngày, và sống qua các lần backend khởi động lại.
- Token bị lộ chỉ mở được các tool đọc, dưới quyền của chủ nó, cho tới khi bị thu hồi hoặc hết hạn. Nó không tự tạo
  được token mới.
- Hai cách gọi MCP cùng tồn tại: JWT vẫn được nhận, để client đang dùng không hỏng.
- Token chết và tài khoản bị khóa trả cùng một câu 401, nên người dò không biết được token nào có thật. Không kiểm
  được (database, Redis) là 503, vì không biết thì không phải là từ chối.
- Đổi mật khẩu không làm token dừng, như personal access token của GitHub hay GitLab. Thứ làm token dừng là: chủ nó
  thu hồi, hết hạn, quản trị viên thu hồi, khóa hay vô hiệu tài khoản, rút quyền khỏi vai trò.
- **Sửa đổi ngày 5/11/2026, khi gộp `develop`.** Hai trường hợp nay **thu hồi hẳn** mọi token của tài khoản:
  - quản trị viên **đặt lại mật khẩu**;
  - chủ tài khoản **đổi mật khẩu kèm "đăng xuất các thiết bị khác"** (lần đổi bắt buộc sau khi bị đặt lại luôn tính là
    có đăng xuất).

  Cơ chế: identity phát `contracts.CredentialsReset` trong cùng transaction đổi mật khẩu; `PersonalTokenRevocationListener`
  của trợ lý thu hồi sau khi commit, và được thử lại từ event publication registry nếu lỗi. Lý do: đặt lại mật khẩu nghĩa
  là mật khẩu cũ có thể đã lộ. Token do người biết mật khẩu cũ tạo ra phải chết theo các phiên đăng nhập, không được sống
  lại khi chủ thật đặt mật khẩu mới.

  Trong khoảng ngắn trước khi listener chạy, `PasswordChangeGateFilter` vẫn chặn token bằng
  `403 PASSWORD_CHANGE_REQUIRED`, vì cổng này xét mọi xác thực có chủ là một tài khoản. Đổi mật khẩu bình thường, không
  đăng xuất nơi khác, vẫn giữ token như trước.

  Kiểm bởi `AssistantMcpTokenIntegrationTest.aPasswordResetRevokesTheTokens` và
  `.theForcedChangeGateRefusesATokenWhilePending`, cùng `IdentitySessionServiceTest`.
- Rủi ro còn lại: một chuỗi đúng dạng `sfmcp_…` gửi ẩn danh tốn một lần đọc database theo hash, như endpoint đăng
  nhập. Có thể thêm checksum hay HMAC sau.
- `ArchitectureTest.onlyTheAssistantsWatchActsForAUser` cho thêm `PersonalTokenAuthenticationProvider` (và cấu hình
  dựng nó) phụ thuộc `RunAs`. `onlyTheConverterAndRunAsBuildAnAuthorization` không đổi: vẫn chỉ `RunAs` dựng quyền.
- Mỗi lần gọi MCP bằng token tốn thêm một truy vấn theo hash (có index unique) và một lần đọc vai trò. Với 10 lần gọi
  một phút mỗi người, chi phí này không đáng kể.

## Phương án đã cân nhắc

- **OAuth 2.1 cho MCP.** Đúng chuẩn, nhưng cần authorization server và trang đồng ý; để sau đồ án. Khi làm, token cá
  nhân vẫn có thể giữ cho client không hỗ trợ OAuth.
- **JWT sống lâu riêng cho MCP.** Không thu hồi được từng cái nếu không có bảng, và mang theo quyền lúc phát hành,
  trái với ADR-0008.
- **Một filter chain riêng cho `/api/v1/assistant/mcp`.** Phải chép lại CORS, header bảo mật, entry point và xử lý
  lỗi của chain chính. Chọn `AuthenticationManager` theo request giữ một chain duy nhất.
