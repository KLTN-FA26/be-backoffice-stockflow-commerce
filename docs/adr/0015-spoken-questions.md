# ADR-0015: Câu hỏi bằng giọng nói: chép lời rồi người nói tự sửa và gửi

Trạng thái: đề xuất (2026-11-06), chờ team chấp nhận; đã xây dựng trên nhánh `feature/ai-assistant`. Thiết kế:
[`docs/assistant-design.md`](../assistant-design.md) §8.3. Dựa trên ADR-0010 (trợ lý gọi tool), ADR-0014 (danh sách
nơi nhận dữ liệu) và câu hỏi mở AQ-01 (doanh nghiệp có đồng ý gửi dữ liệu cho nhà cung cấp cloud không).

## Bối cảnh

Nhân viên kho đang bê hàng hoặc đeo găng thì không gõ được câu hỏi. Giọng nói giải được việc đó, nhưng có ba rủi ro
mà câu hỏi gõ phím không có:

- **Nghe nhầm một chữ số là ra một mã khác có thật.** "LPN-20261105-000123" nghe thành "...000128" là một thùng khác,
  vẫn tồn tại, và câu trả lời sẽ rất tự tin về thùng sai.
- **Giọng nói là dữ liệu cá nhân.** Bản ghi âm chứa giọng của nhân viên, và có thể chứa tên khách hay số tiền họ nói
  ra. Sự đồng ý gửi kết quả tool cho nhà cung cấp model (AQ-01) không bao gồm việc này.
- **Model nghe một câu hỏi thì có xu hướng trả lời luôn**, thay vì chỉ chép lại câu hỏi.

## Quyết định

**1. Chép lời, không trả lời.** `POST /api/v1/assistant/transcriptions` chỉ trả về chữ đã nghe. Chữ đó đổ vào ô nhập
câu hỏi để người nói đọc lại, sửa, rồi tự bấm gửi như một câu hỏi bình thường. Không có gì được làm theo một câu nói mà
chưa ai đọc.

**2. Đánh dấu chỗ dễ sai.** Kết quả có `codes` (các mã viết trong câu, theo đúng bộ tách mã của `AnswerCodes`) và
`numbers` (các từ khác có chữ số, hoặc là số đọc bằng chữ như "hai", "mười"; không tính "không" vì hầu hết câu hỏi kết
thúc bằng nó). Chữ trả về đã chuẩn hóa như lúc tách mã, nên mã nào được đánh dấu cũng tìm thấy trong chữ. Giao diện tô vàng những chỗ đó kèm lời nhắc "kiểm lại hoặc quét". Prompt của trợ lý
cũng hướng mã sang công cụ quét `lookupScan` (C9): mã lấy từ máy quét, không đọc bằng miệng.

**3. Không lưu gì.**

- Không lưu bản ghi âm, không lưu chữ nghe được, không có dòng nào trong database. Upload tới 2 MB nằm trong bộ nhớ
  (`spring.servlet.multipart.file-size-threshold`), nên bản ghi không bị ghi ra file tạm. Đây là cấu hình chung cho mọi
  upload, cần báo team.
- Gửi một lần, không thử lại: lần thử lại sau một lần quá giờ sẽ gửi bản ghi lần nữa trong khi nhà cung cấp có thể vẫn
  đang xử lý bản đầu.
- Log chỉ ghi độ dài, model và kết quả, không ghi nội dung.
- Metric `assistant.voice.transcriptions{outcome}`.

**4. Tắt mặc định, và đi qua cùng ranh giới dữ liệu.**

- `stockflow.assistant.voice.enabled` mặc định `false`. Profile `local` và playground bật nó.
- `stockflow.assistant.voice.model` được đọc như một model định tuyến (`ModelCatalog`), nên `data-policy`,
  `allowed-providers` và `allowed-hosts` của `DataBoundary` (ADR-0014) quyết định được gọi hay không.
- Ở `LOCAL_ONLY`, lựa chọn duy nhất còn lại là một server Whisper trong mạng công ty, ví dụ `openai/whisper-small` trên
  server kiểu OpenAI.
- Để trống thì dùng tuyến model của chat (`routing.models`): các model Gemini được phép, theo thứ tự, và chuyển sang
  model sau khi model trước hết quota, như câu hỏi. Model chat chạy local không nghe được, Claude không nhận âm thanh.
  Trường hợp không dùng được thì ghi log một lần lúc khởi động, và endpoint trả `ASSISTANT_DISABLED`.

**5. Một định dạng, giới hạn kiểm trước khi gửi.**

- Chỉ nhận WAV 16-bit PCM, tối đa 60 giây và 2 MB.
- Độ dài đọc từ header của file (`WavAudio`), không tin kiểu file mà upload khai.
- Trình duyệt tự chuyển sang 16 kHz mono, khoảng 32 KB mỗi giây, nên không cần thư viện codec.
- Gemini tính 32 token cho mỗi giây âm thanh, nên một phút tốn dưới 2.000 token đầu vào.

**6. Cùng cửa với câu hỏi.**

- Quyền `assistant-conversations:CREATE`, cũng là quyền để hỏi.
- `requireBackOffice`: khách cổng bị từ chối, như mọi endpoint của trợ lý.
- Dùng chung giới hạn 10 lần một phút với câu hỏi, và có hạn mức riêng mỗi ngày
  (`stockflow.assistant.voice.daily-limit`, mặc định 100 lần mỗi người). Hạn mức câu hỏi trong ngày đếm câu hỏi đã
  lưu, nên không đếm được bản chép; câu hỏi tạo ra từ bản chép được tính khi gửi.

## Hệ quả

- **Adapter:**
  - Gemini gửi âm thanh `inline_data` trong `generateContent`, giống cách đọc ảnh phiếu giao.
  - Server kiểu OpenAI nhận multipart `/audio/transcriptions` qua `OpenAiCompatibleCall.postMultipart`.
  - Câu chỉ dẫn cho Gemini và gợi ý từ vựng cho Whisper nằm trong `assistant/voice-*.txt`, vì có tiếng Việt.
- **Đọc to câu trả lời** (tùy chọn trên playground) chỉ dùng giọng có sẵn trên máy (`speechSynthesis`). Không có văn
  bản nào rời trình duyệt cho việc này.
- **Frontend thật** chưa có giao diện trợ lý, nên chưa làm ở đó.
- **Độ chính xác với tiếng Việt và mã kho** mới đo bằng tay trên playground. Bộ câu hỏi chuẩn chưa có câu nói nào,
  vì cần bản ghi âm thật.
- **Whisper nội bộ** phải nằm sau cùng địa chỉ OpenAI-compatible với model chat
  (`stockflow.assistant.openai-compatible.base-url`), vì `voice.model` được định tuyến như mọi model khác. Ollama không
  có `/audio/transcriptions`, nên ở chế độ chỉ-local cần một proxy (ví dụ LiteLLM) chuyển chat sang Ollama và âm thanh
  sang faster-whisper. Chưa đo tốc độ Whisper trên CPU: việc đó cần tải một image container, chờ chủ repo đồng ý.
