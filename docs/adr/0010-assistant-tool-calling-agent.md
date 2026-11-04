# ADR-0010: Trợ lý back-office là một agent gọi tool trên API của các module

Trạng thái: đề xuất (2026-10-24), chờ team chấp nhận; phase 1 và 2 đã xây dựng trên nhánh
`feature/ai-assistant` (2026-10-25); sửa sau vòng rà soát 28–29/10 (tài liệu thiết kế §16); sửa đổi ngày 29/10/2026
(hai mục cuối, trước "Hệ quả"). Thiết kế và lộ trình: [`docs/assistant-design.md`](../assistant-design.md).

## Bối cảnh

Ngày nào nhân viên back-office cũng trả lời những câu hỏi giống nhau bằng cách mở bốn, năm màn hình:
mã nào đang ở mức hoặc dưới điểm đặt hàng, vì sao đơn bị giữ, lô hàng đã đi tới những đơn nào, quy định
nghiệp vụ nói gì về trả hàng. Đồ án cũng cần một tính năng AI mà hội đồng xem được nó chạy, và doanh
nghiệp thật sự duy trì được.

Điều quyết định hình dạng của tính năng là câu trả lời nằm ở đâu.

- **Dữ kiện là các dòng dữ liệu, thuộc sáu module, và thay đổi từng phút.** Tồn dùng được, giữ chỗ,
  hold, biến động lô và trạng thái đơn nằm trong `inventory`, `fulfillment`, `order`, `procurement`,
  `reporting` và `catalog` (lệnh sản xuất in, thêm ngày 28/10, nằm trong `production`). Một bản sao của chúng sai
  ngay khi vừa được chụp.
- **Phần văn bản thì nhỏ.** `docs/business-design/` khoảng 200 KB, gồm mười một file. Danh mục quy định
  (`05-business-rules.md`, 23 KB, 83 mã quy định) viết bằng tiếng Anh; các luồng (`04c-all-flows.md`)
  viết bằng tiếng Việt; nhân viên hỏi bằng tiếng Việt.
- **Phân quyền chỉ nằm ở controller.** Tại `develop` `816813cd`, `@RequiresPermission` xuất hiện trong
  93 file controller và không có trong service nào. Vì vậy một lời gọi lên interface `api` của module
  khác, xét theo cấu trúc, là không được phân quyền: không gì kiểm tra người gọi, và không có data scope.
- **Data scope là một `ThreadLocal`.** `RequiresPermissionAspect` đặt nó quanh method được bảo vệ và
  khôi phục lại sau đó. `DataScopeContext` ghi rõ nó không đi sang thread khác, và điều đó là cố ý;
  `AsyncConfig` chép MDC sang worker thread nhưng không chép `SecurityContext`.

Đã cân nhắc năm phương án.

1. **Chatbot RAG trên tài liệu cộng ảnh chụp dữ liệu.** Loại. Nhúng (embed) các dòng tồn kho và đơn
   hàng là chép dữ liệu ra khỏi schema của nó và ra ngoài data scope. Mọi con số nó trả lời đều cũ bằng
   lần đánh chỉ mục gần nhất.
2. **Text-to-SQL.** Loại. SQL do model sinh ra đi vòng qua schema-per-module (ADR-0005, ADR-0007), data
   scope và nhật ký audit. Một lần prompt injection trở thành rò rỉ dữ liệu, và không có đơn vị ổn định
   nào để kiểm thử.
3. **Multi-agent: một router cộng một agent cho mỗi domain.** Hoãn, không loại. Cách này nhân số lần
   gọi model, độ trễ, chi phí và số chỗ một trace có thể đứt. Không use case nào cần nó ở số lượng tool
   dự kiến (khoảng mười lăm; ngày 29/10 là 19, và mỗi người chỉ được gửi các tool của mình, xem sửa đổi ngày
   29/10). Đây là bước tiếp theo nếu bộ đánh giá cho thấy việc chọn tool kém đi khi thêm tool. Tool được nhóm
   theo domain, nên việc tách sau này là refactor, không phải viết lại.
4. **Đặt toàn bộ văn bản vào một prompt được cache.** Đã cân nhắc nghiêm túc: toàn bộ `business-design`
   vừa trong context window, và prompt caching làm việc đọc lại trở nên rẻ. Không chọn làm cơ chế chính
   vì ba lý do. Phần lớn câu hỏi là câu hỏi dữ liệu, mà vẫn phải trả tiền cho phần context đó. Trích dẫn
   tới mã quy định khó kiểm tra hơn. Và kho văn bản sẽ còn lớn thêm với các quy trình riêng của doanh
   nghiệp.
5. **Một agent, mỗi tool là một lớp mỏng bọc các method đọc của `api`, cộng một tool truy xuất văn
   bản.** Chọn.

## Quyết định

### Một module lá: `assistant`

Module mới `com.stockflow.assistant`, schema `assistant`. Nó khai `allowedDependencies` tới các named
interface `api` mà nó đọc (`inventory`, `order`, `fulfillment`, `procurement`, `reporting`,
`catalog`, và từ 28/10 `production`, để đọc lệnh sản xuất qua `ProductionOrderQueries`, api đầu tiên của module
đó; từ 29/10 `customer`, `payment` và `warehouse`, cho giá và công nợ của khách và mã kho của task). Không module nào
phụ thuộc vào nó. Nếu nó bị tắt hoặc nhà cung cấp model bị sập, không luồng nào khác nhận ra.

### Mỗi tool được phân quyền đúng như màn hình hiển thị cùng dữ liệu đó

Một tool là một Spring bean trong `assistant.internal.service`, có đúng một method public. Method đó
mang `@RequiresPermission` với **cùng resource, action và scope như endpoint controller trả về cùng dữ
liệu**. `RequiresPermissionAspect` có sẵn sẽ kiểm tra quyền và đặt data scope, đúng như nó làm cho màn
hình. Các method `api` bên dưới có đọc scope (inventory, procurement, fulfillment) sẽ áp dụng nó.

Annotation chưa phải là toàn bộ những gì một màn hình thực thi. `OrderController` gọi thêm
`ScopeGuards.requireBackOffice` vì `OrderService.findById` và `list` không đọc scope. Màn cảnh báo tồn
thấp giấu các cảnh báo cấp SKU với người dùng bị giới hạn theo kho, và việc đó nằm trong một service mà
method `api` không đi qua. Vì vậy mỗi tool còn phải lặp lại **guard bổ sung** của endpoint nó soi
chiếu. Tài liệu thiết kế liệt kê guard đó cho từng tool.

Hai điều trên cùng tạo ra tính chất mà cả thiết kế dựa vào: **trợ lý không bao giờ cho người dùng thấy
thứ mà màn hình tương ứng không cho thấy.** Không có ngữ nghĩa phân quyền mới nào được đưa vào.

`PermissionCatalogValidator` chỉ quét controller và các class mang `@PermissionResource`, nên nó không
thấy bean tool. `AssistantIntegrationTest.everyToolMirrorsItsScreen` lấp khoảng trống đó. `resource:action`
của mọi tool phải có trong `PermissionCatalog`. Nó cũng phải trùng resource, action và scope với annotation
trên endpoint mà tool soi chiếu. Riêng tool không soi chiếu endpoint nào có bảo vệ (tìm quy định, tìm
catalog) thì phải dùng resource của chính trợ lý. `tools/verify.py` (kiểm tra 10) giờ so quyền theo giá trị mã
resource thay vì tên hằng, vì tool dùng mã của module khác dưới tên hằng của riêng nó.

Một lời gọi tool bị từ chối không trở thành trang lỗi. Tool trả về kết quả "không có quyền", và model nói
cho người dùng biết họ thiếu quyền nào.

Từ 28/10, người dùng và model còn được báo trước. Những gì `run()` kiểm thêm ngoài annotation được khai báo ngay
cạnh chỗ kiểm (quyền bắt buộc thêm, quyền "một trong các", và người bị giới hạn theo kho nhận gì). Từ annotation và
các khai báo đó, `ToolAvailability` tính ra tool nào người dùng được dùng. Kết quả đi tới ba nơi: endpoint
`GET /api/v1/assistant/capabilities` cho panel, ngữ cảnh của mỗi câu hỏi, và từ 29/10 chính danh sách định nghĩa tool
gửi cho model (xem "Sửa đổi ngày 29/10/2026"). System prompt kèm danh sách quyền của từng tool, nên model nói được
quyền còn thiếu mà không phải gọi tool. Việc kiểm quyền vẫn nằm ở từng tool khi chạy.

Có một chỗ tool phải hẹp hơn màn hình nó soi chiếu: một tool tìm đơn bán theo số để đọc dữ liệu của module khác (dòng
nhặt, lệnh sản xuất) không được cho biết trạng thái đơn, hay đơn có tồn tại không, với người không có
`sales-orders:READ`. Rà soát 28/10 tìm thấy đúng lỗi đó ở `getOrderPicking` và đã sửa.

### Model chỉ đọc; không bao giờ ghi

Không tool nào thay đổi trạng thái. Có những use case kết thúc bằng một hành động, ví dụ "lập PO cho
các mã này". Khi đó tool trả về một **đề xuất (proposal)**: một record mà frontend đổ vào form có sẵn.
Người dùng gửi nó qua endpoint có sẵn, dưới `@RequiresPermission`, `@Auditable` và idempotency của chính
endpoint đó. Vậy điều tệ nhất một output sai hoặc bị injection có thể gây ra là một gợi ý sai, và luôn
có một người đọc nó trước khi hành động.

Từ 29/10 trợ lý còn đọc ảnh phiếu giao hàng thành một phiếu nhập nháp, dưới đúng quyền của màn phiếu nhập
(`procurement-goods-receipts:CREATE`). Đó cũng là một đề xuất, không ghi gì và không lưu ảnh. Endpoint mặc định tắt,
vì cả tấm ảnh rời hệ thống (tài liệu thiết kế §8.1).

### Vòng lặp chạy trên thread của request

Vòng lặp agent và mọi lời gọi tool chạy trên chính thread phục vụ request. Virtual thread đang bật
(`spring.threads.virtual.enabled`), nên giữ thread đó vài giây không tốn gì, và `SecurityContext` cùng
data scope nằm đúng chỗ aspect cần. Phần streaming (phase 2) chỉ được đưa vòng lặp sang thread khác qua
một executor chép `SecurityContext` một cách tường minh. Khi đó aspect tự đặt scope trên thread mới.

### Truy xuất bắt đầu bằng từ khóa, trong PostgreSQL

Có một tool, `searchKnowledge`. Nó tìm trong `assistant.knowledge_chunk` bằng đúng bộ máy mà catalog
đang dùng (ADR-0009): một `tsvector` sinh tự động trên `immutable_unaccent(lower(text))`, độ tương đồng
`pg_trgm`, và một index GIN. Mô tả của tool cho model biết danh mục quy định viết bằng tiếng Anh còn các
luồng viết bằng tiếng Việt, để model viết từ khóa tìm kiếm bằng cả hai thứ tiếng. Mỗi chunk là một quy
định, một thuật ngữ, hoặc một mục, và giữ nguyên anchor (`BR-RMA-002`). Nhờ đó câu trả lời trích dẫn
được, và bộ đánh giá kiểm tra được trích dẫn.

Tìm theo nghĩa được thêm ngày 29/10/2026 mà không đợi số đo recall: câu hỏi tiếng Việt và quy định tiếng Anh
thường không chung chữ nào. Vector nằm trong một cột `real[]` của `assistant.knowledge_chunk` và được so trong Java,
không dùng pgvector: kho chỉ khoảng 170 đoạn, còn pgvector buộc đổi image Postgres ở mọi nơi ghi
`postgres:16-alpine` (`docker-compose.yml`, `deploy/docker-compose.yml`, các test Testcontainers). Chuyển sang pgvector
khi kho vượt vài nghìn đoạn hoặc phần đọc vector làm chậm thấy được một lần tìm. Embedding chỉ có với provider Gemini
(`gemini-embedding-001`); Anthropic không có API embedding, nên trợ lý chạy Claude vẫn tìm theo từ khóa. Hai xếp hạng
được gộp bằng reciprocal rank fusion, k = 60 (tài liệu thiết kế §6.4).

### Nhà cung cấp model nằm sau một port

Model là Claude của Anthropic, gọi qua SDK Java chính thức (`com.anthropic:anthropic-java`). Model mặc
định là `claude-opus-5-5`, cấu hình được qua `stockflow.assistant.model`. Chỉ module `assistant` gọi tới
model, qua một interface mà test thay bằng một bản giả chạy theo kịch bản. Định nghĩa tool và system
prompt được gửi đầu tiên và được đánh dấu để dùng prompt caching.

Từ 28/10 có adapter thứ hai sau cùng port, Gemini của Google (`stockflow.assistant.provider=gemini`), để test và chạy
bộ câu hỏi chuẩn bằng key miễn phí; Claude vẫn là mặc định. Vòng lặp, tool và lịch sử lưu trữ không phụ thuộc vào
adapter. Key của mỗi nhà cung cấp chỉ được gửi tới chính nhà cung cấp đó: không có key chung thì lấy biến của
provider đã chọn, và một key có dạng của nhà cung cấp kia thì không được dùng.

Module tắt trừ khi `stockflow.assistant.enabled=true`, và phải khởi động được khi không có API key. Ứng
dụng không bao giờ được từ chối khởi động chỉ vì một nhà cung cấp tùy chọn chưa được cấu hình.

### Mọi thứ đều được ghi lại, và có giới hạn

Mọi hội thoại, tin nhắn và lời gọi tool của khung chat đều được lưu: tên tool, tham số, thời gian chạy, kết quả và
lượng token đã dùng. Lời gọi tool của MCP và của thẻ "hôm nay" (từ 29/10) không thuộc hội thoại nào, nên chỉ được ghi
log, không có dữ liệu. Một câu hỏi lỗi cũng để lại một lượt đóng mang thông điệp lỗi, để transcript nói được chuyện
gì đã xảy ra. Việc gửi tin nhắn bị giới hạn tần suất theo người dùng (`@RateLimit`), một bucket chung cho endpoint
đồng bộ, endpoint streaming, MCP và việc đọc phiếu giao hàng. Có một trần số tin nhắn mỗi ngày cho mỗi người, cấu
hình được (câu hỏi mà nhà cung cấp lỗi ngay ở lần gọi đầu không bị tính), và vòng lặp có số vòng gọi tool tối đa cho
mỗi tin nhắn; lần gọi cuối không cho gọi tool, để model trả lời từ những gì đã tra. Kết quả tool chỉ mang những
trường mà câu trả lời cần. Các trường có cấu trúc không bao giờ chứa số điện thoại, địa chỉ hay e-mail của khách hàng. Chữ tự do do người gõ (ghi
chú, lý do) thì được che số điện thoại, e-mail và mã số thuế theo kiểu best-effort; tên và địa chỉ gõ trong đó không
bị che, và có gửi chúng hay không là quyết định của doanh nghiệp (tài liệu thiết kế, AQ-01).

### Chất lượng được đo, không được khẳng định suông

Bộ câu hỏi chuẩn (golden set) gồm các câu hỏi tiếng Việt, dựng trên demo seed. Với mỗi câu, nó ghi lại
các tool, dữ kiện, trích dẫn và lời từ chối kỳ vọng. Với model thật, bộ này chạy khi cần (bật bằng thuộc
tính hệ thống `assistant.eval=true`), không nằm trong `mvn test`, vì nó tốn tiền và không tất định. `mvn test` bao phủ
những gì tất định: quyền của tool, vòng lặp với model theo kịch bản, sự cô lập scope, và chính bộ câu hỏi
chuẩn chạy với một model giả (oracle) gọi đúng tool kỳ vọng, để kiểm dữ kiện và việc không lộ dữ liệu ở
các lớp bên dưới model. Từ 28/10 bản chạy với model thật chạy được theo từng phần trên một hạn mức miễn phí: chọn ca,
ghi kết quả từng ca để chạy tiếp hôm sau, và ca không chạy được vì hết hạn mức hay lỗi nhà cung cấp không vào chỉ số
nào, để một ngày hết hạn mức không bị báo thành chất lượng kém. Từ 29/10 cùng phép kiểm số không căn cứ chạy trên mọi
câu trả lời lúc chạy, chỉ để báo, và các dòng của một tra cứu hiện thành bảng lấy thẳng từ kết quả tool thay vì do
model chép lại (tài liệu thiết kế §7).

## Sửa đổi ngày 29/10/2026: chỉ gửi cho model các tool người dùng được dùng (AQ-11)

**Quyết định.** Mỗi request gửi model chỉ mang định nghĩa của các tool người hỏi được dùng
(`ToolAvailability.specsFor`), sắp theo tên. Trước đó là mọi tool cho mọi người. Ngữ cảnh câu hỏi vẫn liệt kê các
tool đó, và system prompt vẫn kết bằng danh sách quyền của mọi tool.

**Vì sao đổi.**

- Định nghĩa tool là phần lớn nhất của request trước lịch sử, và được gửi lại ở mỗi lần gọi của một câu trả lời. Đo
  lúc quyết, trên 15 tool: cả 15 định nghĩa khoảng 24.800 ký tự, gần gấp đôi system prompt lúc đó; bảy vai trò seed
  không phải quản trị chỉ dùng 33–63% số đó, nên phần tiền tố cố định (tool + system prompt) giảm 24–44%, khoảng
  2.300–4.100 token mỗi lần gọi. SYSTEM_ADMIN vẫn nhận mọi tool.
- Một model nhỏ không có strict mode (gói miễn phí của Gemini) vẫn gọi tool có định nghĩa dù ngữ cảnh nói tool đó từ
  chối người dùng, rồi tốn một vòng để nhận `NOT_PERMITTED`. Không có định nghĩa thì không có lời gọi đó. Với strict
  tool use của Claude, model chỉ gọi được tool có trong request.
- Một chỉ thị lọt vào qua kết quả tool (prompt injection) không còn khiến model gọi được một tool người dùng không có
  quyền. Đây là thêm một lớp, không thay lớp kiểm quyền.

**Những gì không đổi.**

- Quyền vẫn do từng tool kiểm khi chạy, sau proxy của nó. `ToolAvailability` quyết định model được thấy tool nào,
  không quyết định tool nào chạy.
- Một lời gọi tới tool không được gửi (model không có strict mode, model giả của test) vẫn đi qua `AssistantTools`, bị
  guard của tool đó từ chối `NOT_PERMITTED` và được ghi vào `assistant.tool_call` như mọi lời gọi khác. Đây là điều
  kiện mà AQ-11 đặt ra.
- Cách từ chối mà quyết định này mô tả vẫn đúng: model nói quyền còn thiếu, lấy từ danh sách quyền cuối system prompt.

**Cái giá: cache theo tập quyền.**

- Danh sách tool chỉ phụ thuộc tập quyền và việc người hỏi có bị giới hạn theo kho không, không phụ thuộc kho nào. Nó
  sắp theo tên và dùng đúng các object định nghĩa đọc lúc khởi động, nên cùng một tập quyền cho cùng một chuỗi byte.
- Nhà cung cấp cache tool + system prompt theo từng tập quyền thay vì một mục chung: một mục cho mỗi tập quyền, tối đa
  mười với mười vai trò seed.
- Danh sách được tính một lần cho mỗi câu hỏi, nên mọi lần gọi của câu trả lời, kể cả lần gọi cuối, đọc lại cùng một
  mục cache.
- Ghi cache xảy ra nhiều hơn khi nhiều vai trò hỏi xen kẽ, nhưng phần được ghi nhỏ hơn.

## Sửa đổi khác ngày 29/10/2026

- **Định tuyến model.** Có thể khai báo một danh sách model của cùng một nhà cung cấp, rẻ trước
  (`stockflow.assistant.routing.models`). Lần gọi đầu gặp hạn mức đã hết, nhà cung cấp không kết nối được hay request
  bị từ chối thì câu hỏi sang model kế tiếp; model không ra câu trả lời dùng được thì câu hỏi được trả lời lại một lần
  trên model kế tiếp. Đã có lượt trả lời thì câu hỏi ở lại với model đó, và lịch sử gửi cho mỗi model được dựng lại
  cho chính nó, vì nội dung của nhà cung cấp chỉ hợp lệ với model đã viết ra nó. Danh sách không trộn hai nhà cung cấp,
  để key của bên này không bao giờ tới bên kia (tài liệu thiết kế §11).
- **MCP.** `POST /api/v1/assistant/mcp` mở cùng các tool đọc cho một AI client khác qua Model Context Protocol, dưới
  quyền và kho của người đăng nhập, qua cùng `AssistantTools.execute` và cùng guard; ứng dụng không gọi model cho nó.
  Kết quả tool đi tới nhà cung cấp model mà client chọn, không phải nhà cung cấp doanh nghiệp đã chấp nhận, nên
  endpoint mặc định tắt (`ASSISTANT_MCP_ENABLED`) và việc bật nó ở môi trường thật là quyết định của doanh nghiệp (tài
  liệu thiết kế §7, AQ-17).

## Sửa đổi ngày 04/11/2026: model trong mạng công ty, danh sách trộn nhà cung cấp, chính sách dữ liệu

- **Adapter thứ ba sau cùng port:** `provider=openai`, bất kỳ máy chủ nào nói API chat-completions của OpenAI (Ollama,
  vLLM, LM Studio), thường chạy trên máy của doanh nghiệp. Embedding và đọc ảnh có adapter cùng loại, nên tìm theo nghĩa
  không còn phụ thuộc vào nhà cung cấp của khung chat: một trợ lý Claude dùng được `bge-m3` chạy nội bộ.
- **Danh sách định tuyến được trộn nhà cung cấp** (`provider/model`). Bất biến cũ được giữ, nay ở một chỗ
  (`ModelCatalog`): `ASSISTANT_API_KEY` chỉ thuộc nhà cung cấp của trợ lý, model của nhà cung cấp khác chỉ nhận biến
  key riêng của nhà cung cấp đó hoặc không gì cả, và `base-url` chỉ áp cho nhà cung cấp của trợ lý. Thay cho câu "danh
  sách không trộn hai nhà cung cấp" của sửa đổi 29/10.
- **`stockflow.assistant.data-policy=LOCAL_ONLY`:** không adapter nào được dựng cho endpoint ngoài mạng công ty. Đây là
  cách thi hành câu trả lời của AQ-01 nếu doanh nghiệp không cho dữ liệu rời hệ thống; nó không thay cho câu trả lời đó.
- Vòng gọi HTTP của Gemini (trước đó chép ở ba adapter) gom về `GeminiCall`, hành vi giữ nguyên.

## Hệ quả

- Không module nào khác phải đổi. Ngoại lệ là các method `api` được thêm khi một use case cần dữ liệu
  mà hôm nay chỉ một controller nội bộ chạm tới được (liệt kê trong tài liệu thiết kế). Ngày 29/10 có thêm một sửa ở
  tầng nền, tìm thấy khi rà soát trợ lý: `Specs.contains` hạ chữ thường dưới collation ICU, nên mọi lần tìm qua nó, ở
  mọi module, tìm được tên viết hoa có dấu (tài liệu thiết kế §16).
- Phân quyền chỉ có một định nghĩa. Khi một quyền đổi trong ma trận phân quyền, trợ lý đổi theo cùng lúc
  với các màn hình: trong giới hạn 60 giây của con trỏ role-version (ADR-0008).
- Mỗi tool lặp lại annotation phân quyền của endpoint nó soi chiếu. Test nói ở trên là thứ giữ cho hai
  bên không lệch nhau.
- Một tool tổng hợp nhiều nguồn đòi đủ quyền của từng nguồn. Đề xuất nhập hàng hiển thị doanh số, tồn kho
  và điều khoản nhà cung cấp, nên với quyền seed hiện tại chỉ SYSTEM_ADMIN dùng được. Cấp thêm quyền cho
  PROCUREMENT_STAFF là quyết định của doanh nghiệp (tài liệu thiết kế, AQ-08), không phải của trợ lý.
- Một câu trả lời mất vài giây và mỗi câu hỏi đều tốn tiền. Rate limit, trần mỗi ngày, giới hạn số vòng,
  tối đa 8 tool một bước, ngân sách kết quả tool mỗi câu hỏi và caching chặn cả hai. Câu hỏi cũ được gửi lại
  chỉ kèm câu trả lời, không kèm kết quả tool, nên chi phí không tăng theo độ dài hội thoại và dữ liệu cũ không
  sống lâu hơn quyền của người hỏi. Tài liệu thiết kế có phép tính.
- Một trợ lý đọc dữ liệu thật có thể đưa ra lời khuyên sai mà không lỗi kỹ thuật nào: đề xuất nhập hàng
  từng bỏ qua hàng đã đặt và lead time cho tới khi một vòng rà soát nghiệp vụ độc lập tìm ra (tài liệu thiết
  kế §15); vòng sau tìm thêm giờ UTC bị đọc như giờ Việt Nam, SKU gõ sai bị báo là hết hàng, và PO bị nhà cung cấp từ
  chối vẫn tính là đang về (§16). Mỗi tool mới cần một lần rà soát như vậy, không chỉ test phân quyền.
- Câu trả lời không tất định. Cách giảm thiểu: con số chỉ lấy từ kết quả tool; mọi câu trả lời cho thấy
  nó đã dùng tool nào và trích dẫn gì; bảng lấy thẳng từ kết quả tool và số không tìm thấy trong dữ liệu được báo
  ngay dưới câu trả lời; bộ câu hỏi chuẩn được chạy lại mỗi khi đổi prompt, tool hoặc model.
- Một nhà cung cấp bên ngoài trở thành phụ thuộc lúc chạy của một module lá. Khi nhà cung cấp lỗi, trợ
  lý trả 503 `ASSISTANT_UNAVAILABLE` (thử lại được), 429 `ASSISTANT_QUOTA_EXHAUSTED` khi hạn mức đã hết (với định
  tuyến: hạn mức của mọi model trong danh sách), hoặc 503 `ASSISTANT_MISCONFIGURED` khi nhà cung cấp từ chối key hay
  tên model; hai mã sau báo ngay, không chờ, vì thử lại không đổi được gì. Không gì khác bị ảnh hưởng.
- Dữ liệu rời khỏi hệ thống: kết quả tool của người đang hỏi được gửi tới nhà cung cấp model. Từ 29/10 còn ảnh phiếu
  giao hàng khi việc đọc ảnh được bật, các đoạn của kho tri thức và từ khóa tìm kiếm khi bật embedding Gemini, và qua
  MCP là kết quả tool tới nhà cung cấp mà client chọn. Chủ doanh nghiệp phải chấp nhận điều đó trước khi bật tính năng
  ngoài môi trường phát triển (tài liệu thiết kế, phần câu hỏi mở: AQ-01, AQ-17, AQ-18).
- Các resource phân quyền mới (`assistant-conversations`; nếu doanh nghiệp quyết, `assistant-feedback` hay
  `assistant-mcp`) được đăng ký và cấp quyền bằng migration, như mọi resource khác, có tăng `app_role.version`. Bản tin
  buổi sáng với resource `assistant-briefings` không làm; thẻ "hôm nay" dùng `assistant-conversations:READ` và chỉ chạy
  tool người xem vốn được dùng.
