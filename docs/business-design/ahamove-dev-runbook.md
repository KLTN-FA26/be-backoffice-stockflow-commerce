# Chạy thật Ahamove staging trên server dev — runbook (18/11/2026)

Mục tiêu: trên **server dev** (không phải máy local), đặt xe qua API thật của **Ahamove staging**, nhận callback thật,
và đi hết luồng: đặt xe → tài xế nhận → lấy hàng → giao xong → (nhánh) huỷ.

Phạm vi: chỉ cấu hình và kiểm tra. **Không** đổi code, **không** bật chế độ giả lập (`AHAMOVE_MODE` không được đặt trên dev, mặc định là `live`).
Luật nghiệp vụ: kltn-docs `warehouse/09-shipping` §4.1 (kết nối hãng), §4.2 bước 6 (tạo đơn), §4.5 (webhook).

Ký hiệu: `<DOMAIN>` là tên miền của server dev (biến `DOMAIN` trong `/opt/stockflow/.env`). Mọi bí mật do **Tú** tự nhập; runbook này
không chứa và không in bí mật nào.

## 0. Đã kiểm tra trong repo (develop `b7f7d851`)

| Việc | Kết quả |
|---|---|
| Đường callback có đi qua nginx của dev không? | **Có, không cần sửa nginx.** `deploy/nginx/templates/stockflow.conf.template`: host `api.<DOMAIN>` là host chỉ-DNS (không qua Cloudflare, không bị `cloudflare-only`), `location /` chuyển mọi đường dẫn tới app. Header `apikey` và `Authorization` được chuyển nguyên (`proxy-headers.conf` chỉ xoá các header `Forwarded*` và `X-Stockflow-*`). Giới hạn `edge_req` 20 req/s/IP, burst 40 là đủ cho callback. Vì vậy **không có PR nginx**. |
| App có đọc `STOCKFLOW_SECRETS_KEY` từ `.env` của server không? | **Có.** `deploy/docker-compose.yml` dòng `STOCKFLOW_SECRETS_KEY: ${STOCKFLOW_SECRETS_KEY:-}`; mẫu ở `deploy/.env.example`. |
| Chế độ giả lập có bật trên dev không? | **Không.** `AHAMOVE_MODE` không được compose truyền vào, `application.yml` mặc định `live`; chỉ `application-local.yml` đặt `fake`. |
| Dev có dữ liệu demo không? | **Không.** Profile `dev` chỉ chạy `db/migration`; compose chỉ thêm `db/demo` nếu `.env` của server đặt `FLYWAY_LOCATIONS` (mặc định tắt). Kể cả khi bật, seed giả lập Ahamove nằm ở thư mục riêng `db/demo-fake`, chỉ `application-local.yml` liệt kê, nên **không** bao giờ chạy trên dev và không đụng tới hãng AHAMOVE của dev. Hãng AHAMOVE, mã dịch vụ, điểm lấy hàng **chưa có** trên dev: tạo ở bước 3. Loại xe mẫu (`PICKUP`, `TRUCK-1T25`, `TRUCK-2T5`…) có sẵn từ migration. |
| Giới hạn IP cho callback | Ahamove không công bố danh sách IP; `AHAMOVE_WEBHOOK_ALLOWED_IPS` mặc định rỗng (cho mọi IP) và compose **không** truyền biến này. Để nguyên. |

## 1. Tạo và đặt `STOCKFLOW_SECRETS_KEY` (Tú làm)

Khoá này mã hoá khoá API Ahamove và thông tin xác thực callback trong database. **Mất khoá = mất các bí mật đã lưu** (phải nhập lại); đổi khoá cũng vậy. Lưu cùng chỗ với bản sao lưu database.

1. Sinh khoá (trên máy của Tú, **không** dán vào chat/commit):
   ```bash
   openssl rand -base64 32
   ```
   Kết quả là chuỗi base64 của đúng 32 byte (44 ký tự, kết thúc bằng `=`).
2. SSH vào server dev, sửa file môi trường:
   ```bash
   cd /opt/stockflow
   nano .env            # thêm/sửa dòng:  STOCKFLOW_SECRETS_KEY=<chuỗi vừa sinh>
   chmod 600 .env
   scripts/check-env.sh # không in giá trị; kiểm tra thiếu/rỗng
   ```
3. Nạp lại biến vào container. **Dùng `up -d`, không dùng `restart`**: `restart` giữ nguyên môi trường cũ của container.
   ```bash
   docker compose up -d --no-deps app
   docker compose ps app          # chờ trạng thái healthy (1–2 phút)
   docker compose exec -T app sh -c 'test -n "$STOCKFLOW_SECRETS_KEY" && echo "key set (${#STOCKFLOW_SECRETS_KEY} chars)"'
   ```
   Kỳ vọng: `key set (44 chars)`. Khoá sai định dạng làm app **không khởi động** (log: `docker compose logs --tail=60 app`).
4. Việc này làm app khởi động lại 1–2 phút: người đang dùng dev sẽ bị gián đoạn ngắn (đăng nhập vẫn giữ).

## 2. Chuẩn bị thông tin (Tú có sẵn trước khi chạy)

| Thông tin | Lấy ở đâu | Ghi chú |
|---|---|---|
| Khoá API staging của Ahamove | Ahamove cấp (`api_key` của môi trường staging) | Bí mật. Chỉ nhập khi script hỏi. |
| Tài khoản đối tác (`apiAccount`) | Số điện thoại đã đăng ký với khoá trên | Hệ thống gọi `POST /v3/accounts/token` với `{mobile, api_key}`. |
| Giá trị xác thực callback | **Tú tự chọn**: chuỗi ngẫu nhiên 16–512 ký tự, ví dụ `openssl rand -hex 24` | Bí mật. Cùng một giá trị phải đưa cho Ahamove (bước 4). |
| Tên header xác thực callback | Ahamove hỗ trợ 3 kiểu: header `apikey: <khoá>`, `Authorization: Bearer <token>`, `Authorization: Basic <base64>` | Khuyến nghị `apikey`. Nếu dùng `Authorization`, giá trị lưu phải là **cả chuỗi** `Bearer <token>` hoặc `Basic <base64>`. |
| Mã thành phố Ahamove (`city_id`) và mã tỉnh của mình | Hà Nội: `HAN` ↔ tỉnh `01`; TP.HCM: `SGN` ↔ `79` | Mã tỉnh phải trùng `shipping_province_code` trong địa chỉ giao của đơn. Kiểm tra dữ liệu dev: `SELECT DISTINCT shipping_province_code FROM ordering.customer_order;` |
| Kho xuất | Tiền tố kho (`HAN`…) trong `warehouse.warehouse` | Kho xuất và **mọi** điểm giao của chuyến phải cùng tỉnh với mã dịch vụ (docs 09 §4.1 E24). |
| Số điện thoại liên hệ tại kho | Người tài xế sẽ gọi khi tới lấy hàng | Bắt buộc. |
| Tài khoản đăng nhập dev | Quản lý kho hoặc quản trị hệ thống | Cần quyền `fulfillment-carriers` (UPDATE) và `fulfillment-vehicle-types` (READ). |

## 3. Cấu hình hãng AHAMOVE

### Cách A (khuyến nghị): script `tools/ahamove/configure-dev-carrier.sh`

Script gọi chính API của hệ thống (đăng nhập → tạo/cập nhật hãng → lấy danh sách dịch vụ thật của Ahamove → ghi mã dịch vụ → ghi điểm lấy hàng).
Bí mật **không** nằm trên dòng lệnh, trong file hay trong output: nếu biến môi trường chưa có, script hỏi và không hiện ký tự; yêu cầu gửi bí mật qua stdin/descriptor của `curl`.
Chạy lại nhiều lần được (cập nhật hãng, gộp mã dịch vụ và điểm lấy hàng, không xoá của tỉnh/kho khác).

Cần `bash`, `curl`, `jq` trên máy của Tú (không cần trên server).

```bash
cd be-backoffice-stockflow-commerce      # chỗ có tools/ahamove/
export API_BASE=https://api.<DOMAIN>
export STOCKFLOW_USER=<tài khoản quản lý kho>
export AHAMOVE_API_ACCOUNT=<số điện thoại đăng ký Ahamove>
export PICKUP_CONTACT_PHONE=<số liên hệ tại kho>
export WAREHOUSE_PREFIX=HAN AHAMOVE_CITY=HAN PROVINCE_CODE=01      # đổi nếu dùng kho/thành phố khác
export WEBHOOK_AUTH_HEADER=apikey                                  # hoặc Authorization

# thử trước, không ghi gì (vẫn hỏi 3 bí mật, vẫn đăng nhập và đọc):
DRY_RUN=1 tools/ahamove/configure-dev-carrier.sh

# chạy thật; script hỏi: mật khẩu, khoá API staging, giá trị xác thực callback
tools/ahamove/configure-dev-carrier.sh
```

Khi chạy thật script in danh sách `service_id` của Ahamove cho thành phố, rồi hỏi mã dịch vụ cho từng loại xe (`PICKUP`, `TRUCK-1T25`, `TRUCK-2T5`; đổi bằng `VEHICLE_TYPES="..."`).
Có thể bỏ qua câu hỏi: `SERVICE_MAP="TRUCK-1T25=<service_id>,TRUCK-2T5=<service_id>"`. Script từ chối mã không có trong danh sách của Ahamove.
Cuối cùng script in URL và tên header cần đăng ký (bước 4), **không** in giá trị.

Biến tuỳ chọn khác: `CARRIER_CODE` (mặc định `AHAMOVE`), `PICKUP_ADDRESS`, `PICKUP_CONTACT_NAME`, `FAILURE_MAP_JSON`, `AHAMOVE_ENV` (mặc định `STAGING`). Xem phần đầu script.

### Cách B: gọi API thủ công (cùng kết quả)

Đăng nhập lấy token: `POST https://api.<DOMAIN>/api/v1/identity/auth/login` với `{"username":..., "password":...}` → `data.accessToken`. Mọi lệnh sau gửi `Authorization: Bearer <token>`.

1. **Tạo hãng** `POST /api/v1/fulfillment/carriers` (hoặc `PUT /{carrierId}` kèm `version` nếu `AHAMOVE` đã có):

   | Trường | Giá trị |
   |---|---|
   | `code` | `AHAMOVE` (mã này nằm trong URL callback) |
   | `name`, `contactName`, `contactPhone` | tuỳ ý (ví dụ `Ahamove`) |
   | `status` | `ACTIVE` |
   | `connection.connectionType` | `API_BOOKING` |
   | `connection.webhookEnabled` | `true` |
   | `connection.webhookAuthHeader` | `apikey` (hoặc `Authorization`) |
   | `connection.webhookAuthValue` | **Tú nhập**: giá trị xác thực callback (16–512 ký tự) |
   | `connection.apiEnvironment` | `STAGING` |
   | `connection.apiAccount` | **Tú nhập**: số điện thoại đối tác |
   | `connection.apiKey` | **Tú nhập**: khoá API staging (8–512 ký tự) |
   | `connection.failureReasonMap` | `{"CUSTOMER_NOT_AVAILABLE":"CUSTOMER_ABSENT","CUSTOMER_REFUSED":"REFUSED","WRONG_ADDRESS":"WRONG_ADDRESS"}` (lý do của Ahamove → mã nội bộ; lý do không có trong bảng sẽ ghi `OTHER` kèm lời gốc) |

   Khoá API và giá trị xác thực **không bao giờ trả về** (chỉ có cờ `apiKeyConfigured`, `webhookAuthValueConfigured`). Thiếu `STOCKFLOW_SECRETS_KEY` thì bước này trả **503 `SECRETS_UNAVAILABLE`**.
   Đổi `apiEnvironment` hoặc `apiAccount` bắt buộc gửi lại `apiKey`.
2. **Lấy `service_id` thật**: `GET /api/v1/fulfillment/carriers/{carrierId}/services?cityId=HAN` → danh sách `{serviceCode, name, cityId}` (hệ thống gọi `GET /v3/services?city_id=HAN&delivery_type=TRUCK` của Ahamove). Chọn dịch vụ xe tải phù hợp từng loại xe.
3. **Mã dịch vụ theo loại xe**: `GET /api/v1/fulfillment/vehicle-types` để lấy `vehicleTypeId`, rồi
   `PUT /api/v1/fulfillment/carriers/{carrierId}/service-codes` với
   `{"codes":[{"vehicleTypeId":"<id>","provinceCode":"01","carrierCityCode":"HAN","serviceCode":"<service_id>"}, ...]}`
   (ghi **thay toàn bộ** danh sách; muốn giữ mã tỉnh khác thì gửi kèm).
4. **Điểm lấy hàng**: `GET /api/v1/warehouses` lấy `id` kho, rồi
   `PUT /api/v1/fulfillment/carriers/{carrierId}/pickup-points` với
   `{"pickupPoints":[{"warehouseId":"<id>","provinceCode":"01","address":"<địa chỉ kho như Ahamove đọc được>","contactName":"...","contactPhone":"..."}]}`
   (cũng thay toàn bộ; mỗi kho một điểm).

## 4. Đăng ký callback tại Ahamove

Theo tài liệu Ahamove (developers.ahamove.com/docs/webhook): đối tác **gửi URL webhook cho Ahamove** để họ cài đặt (không có màn hình tự đăng ký đã biết; liên hệ đầu mối Ahamove đã cấp khoá).

| Thông tin gửi Ahamove | Giá trị |
|---|---|
| URL | `https://api.<DOMAIN>/api/v1/fulfillment/carrier-webhooks/AHAMOVE` (đúng mã hãng ở bước 3) |
| Phương thức | `POST`, JSON |
| Xác thực | header `apikey` = giá trị xác thực callback ở bước 3 (hoặc `Authorization: Bearer …` / `Basic …` nếu đã chọn kiểu đó). **Giá trị gửi qua kênh riêng, không qua chat công khai.** |

Kiểm tra đường đi tới app **trước khi** báo Ahamove (không cần bí mật):
```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' -d '{}' \
  https://api.<DOMAIN>/api/v1/fulfillment/carrier-webhooks/AHAMOVE
```
Kỳ vọng `401` (route tới được app, thiếu thông tin xác thực → `WEBHOOK_SIGNATURE_INVALID`). `404`/lỗi TLS/timeout nghĩa là chưa tới app. Sau lệnh này `GET /api/v1/fulfillment/carrier-events` có 1 sự kiện `INVALID_SIGNATURE`: bình thường.

Lưu ý: callback của Ahamove có kèm `api_key` của đối tác trong thân; hệ thống che trường này khi lưu nhật ký.

## 5. Smoke test: đặt xe → ACCEPTED → IN PROCESS → COMPLETED

Chuẩn bị: một đơn đã lấy hàng và dán nhãn → kế hoạch xếp xe đã xác nhận, loại xe đã có mã dịch vụ, **địa chỉ giao cùng tỉnh** với điểm lấy hàng.
Theo dõi bằng: màn chuyến (`/admin/loads/<id>`); API `GET /api/v1/fulfillment/carrier-events?carrierId=<id>`; danh sách lỗi `?result=ERRORS&open=true` (kỳ vọng **trống** khi mọi thứ đúng). Log gọi API Ahamove nằm ở bảng `fulfillment.carrier_api_call` (BOOK / CANCEL / STATUS / SERVICES, `outcome`, `http_status`; không có khoá).

| # | Làm | Kỳ vọng |
|---|---|---|
| 1 | Chuyến `Planned`: **Đặt xe** → chọn hãng Ahamove (UI hoặc `POST /api/v1/fulfillment/loads/{id}/booking` với `{"carrierId":"..."}`) | Chuyến → `VEHICLE_BOOKED`; **mã vận đơn = mã đơn Ahamove** (không nhập tay); có link theo dõi. `carrier_api_call`: dòng `BOOK` `OK` 200. Lỗi → chuyến giữ `Planned`, báo lý do của Ahamove. |
| 2 | Cần có tài xế nhận đơn trên staging (xem *Chưa rõ* bên dưới). Ahamove gọi callback `ACCEPTED` | Sự kiện `DRIVER_ASSIGNED`, kết quả `APPLIED`; chuyến hiện **tên và số điện thoại tài xế**. |
| 3 | Bắt đầu xếp hàng, quét đủ kiện, **ký bàn giao** (nhập biển số tại bước ký; hệ thống không nhận biển số từ Ahamove) | Chuyến → `DISPATCHED`. Ký bàn giao bắt buộc có tên tài xế: nếu callback ở bước 2 chưa về, nhập tay tên tài xế ở màn chuyến. |
| 4 | Tài xế lấy hàng: callback `IN PROCESS` | `PICKED_UP` → mốc "đã lấy hàng" (`APPLIED`). Callback tới **trước** khi ký bàn giao → `NEEDS_REVIEW` (đúng thiết kế, vào danh sách lỗi). |
| 5 | Giao xong điểm giao: callback điểm `COMPLETED` (`_id` = `<mã đơn>-1`) | Hệ thống **hỏi lại** `GET /v3/orders/{id}` rồi mới áp dụng (verify-outcomes). Shipment + đơn → `Delivered`, mốc "đã giao". Hỏi lại không khớp → `UNVERIFIED` (không áp dụng). |
| 6 | Đơn `COMPLETED` | `ORDER_COMPLETED` (áp phần điểm giao chưa áp), kết quả `APPLIED`/`IGNORED`. |
| 7 | Nhánh giao thất bại: điểm `FAILED` kèm `fail_comment` | `Delivery Failed`, lý do ánh xạ theo bảng ở bước 3 (không có trong bảng → `OTHER` kèm lời gốc). |
| 8 | Nhánh huỷ: chuyến mới đặt xe, chưa lấy hàng → **Huỷ đặt xe** | Hệ thống gọi `DELETE /v3/orders/{id}` trước, rồi mới huỷ ở hệ thống; chuyến → `Planned`; `carrier_api_call` có `CANCEL`. Huỷ sau khi lấy hàng bị Ahamove từ chối → `409 CARRIER_CANCEL_REFUSED`, không đổi gì. |

**Tự thử đường callback mà không cần tài xế thật** (chỉ cho các mốc **không** bị hỏi lại, tức `ACCEPTED`, `IN PROCESS`): gửi tay một callback có giá trị xác thực, với `<orderId>` là mã vận đơn của chuyến và thời gian là hiện tại:
```bash
read -rs CB; curl -s -X POST -H 'Content-Type: application/json' -H "apikey: $CB" \
  --data "{\"_id\":\"<orderId>\",\"status\":\"ACCEPTED\",\"supplier_name\":\"Tai xe thu\",\"supplier_id\":\"0900000000\",\"accept_time\":$(date +%s)}" \
  https://api.<DOMAIN>/api/v1/fulfillment/carrier-webhooks/AHAMOVE; unset CB
```
Kỳ vọng `{"data":{"eventId":"...","result":"APPLIED"}}` và tên tài xế hiện trên chuyến. Các kết quả cuối (giao xong/thất bại/huỷ/hoàn tất) **không** thử được kiểu này: Ahamove staging sẽ trả trạng thái thật của đơn và hệ thống từ chối (`UNVERIFIED`) callback tự chế — đó là phòng vệ có chủ đích.

**Chưa rõ, cần Tú hỏi Ahamove hoặc kiểm trên staging**: cách khiến một đơn staging được tài xế *nhận / lấy hàng / giao xong* (công cụ giả lập tài xế của Ahamove staging, hay họ làm hộ). Tài liệu công khai không nói.

## 6. Cần xác minh về `supplier_name` / `supplier_id` (thông tin tài xế)

Code (`AhamoveWebhookReader`) đọc tài xế từ callback `ACCEPTED` như sau: tên = `supplier_name`; **điện thoại = `supplier_id`** (chuỗi dài hơn 32 ký tự bị bỏ, không cắt); **biển số: không đọc** (`DispatchLoad.carrierAssignedDriver`: "the plate is not touched: the carrier sends none", biển số nhập khi ký bàn giao).
Tài liệu Ahamove chỉ có mẫu callback với `supplier_id` và `supplier_name` **rỗng**; không nói `supplier_id` là gì và không có trường biển số.
Docs của mình (09 §4.5) ghi "Điền biển số, tài xế vào chuyến", lệch với code ở phần biển số.

Khi có một callback `ACCEPTED` thật (bước 5.2):

1. Lấy nguyên thân callback: `GET /api/v1/fulfillment/carrier-events?carrierId=<id>` → lấy `eventId` của sự kiện `DRIVER_ASSIGNED` → `GET /api/v1/fulfillment/carrier-events/{eventId}` → `payload` (khoá API đã được che).
2. Kiểm: `supplier_name` có là tên người? `supplier_id` có là **số điện thoại** (số hay mã nội bộ của Ahamove)? Có trường nào khác mang biển số/phương tiện (tên trường là gì)?
3. Đối chiếu chuyến: tên và điện thoại tài xế trên màn chuyến có đúng không (nếu `supplier_id` là mã nội bộ, điện thoại trên chuyến sẽ **sai**).
4. Ghi kết quả (kèm payload đã che) gửi lại để sửa docs 09 §4.5 / code: nếu `supplier_id` không phải số điện thoại hoặc Ahamove có trả biển số.

Cũng kiểm khi có callback điểm giao: `path[k].tracking_number` có được trả lại không? (mẫu công khai không có). Hệ thống ghép shipment theo **số thứ tự điểm** (`_id` = `<mã đơn>-k`), nên thiếu `tracking_number` vẫn đúng; nếu có mà **khác** mã shipment đã gửi lúc tạo đơn thì sự kiện vào `NEEDS_REVIEW`.
Và: tài liệu không nói huỷ đơn **sau** khi lấy hàng có được không; thử ở bước 5.8 và ghi lại.

## 7. Quay lại / dọn dẹp

- Ngừng nhận callback: `PUT /carriers/{id}` với `connection.webhookEnabled=false` (giữ cấu hình), hoặc đặt hãng `INACTIVE`; báo Ahamove gỡ webhook.
- Đổi giá trị xác thực callback không gián đoạn: `POST /carriers/{id}/webhook-credential-rotation` (hai giá trị cùng hợp lệ trong lúc đổi).
- Đổi sang production: `apiEnvironment=PRODUCTION` **bắt buộc** nhập lại `apiKey`; không làm trong đợt này.

## 8. Phụ lục: demo local với Ahamove giả lập (không gọi mạng)

Khác với chạy thật ở trên: trên máy local, profile `local` mặc định `stockflow.fulfillment.ahamove.mode=fake` (đặt `AHAMOVE_MODE=live` để tắt). Chạy luồng đặt xe giả lập **không cần** `STOCKFLOW_SECRETS_KEY`, **nhưng tạo hoặc sửa hãng kiểu `API_BOOKING` trên giao diện (hay `POST/PUT /carriers` có `apiKey`) vẫn cần**: không có khoá thì API trả **503 `SECRETS_UNAVAILABLE`**, kể cả ở chế độ giả lập, vì lúc lưu khoá API và giá trị xác thực hệ thống phải mã hoá chúng.
Hãng `AHAMOVE` do seed demo (`db/demo-fake/V20261203000400__demo_fake_ahamove.sql`) tạo sẵn thì không cần khoá; chỉ cần khoá khi tạo hãng mới qua UI/API.

Cách đặt (chỉ máy local, **giá trị thử bất kỳ, không dùng khoá thật**; khoá thật của server dev ở bước 1 không bao giờ nằm trên máy local):

- Đặt biến môi trường `STOCKFLOW_SECRETS_KEY` **trước khi chạy BE** (trong cùng terminal hoặc cấu hình chạy của IDE), rồi khởi động lại BE. Khoá phải là base64 của đúng 32 byte; sinh một giá trị thử và để shell giữ, không in ra:
  ```bash
  export STOCKFLOW_SECRETS_KEY="$(openssl rand -base64 32)"
  java -jar target/stockflow-monolith-1.0.0-SNAPSHOT.jar --spring.profiles.active=local   # hoặc mvn spring-boot:run
  ```
  Nếu chạy BE bằng `docker compose --profile app up`, đặt một dòng `STOCKFLOW_SECRETS_KEY=<giá trị thử>` trong `.env` ở thư mục gốc repo (file bị git bỏ qua; mẫu `.env.example`). Nếu chạy bằng IDE, thêm biến này vào Environment variables của cấu hình chạy.
- Khoá thử chỉ mã hoá dữ liệu local. Đổi khoá thì các bí mật đã lưu bằng khoá cũ không đọc được (nhập lại); ở chế độ giả lập điều này không ảnh hưởng đặt xe vì khoá API không được mở.
- Không dán khoá vào chat, commit hay log. Không dùng lại khoá của server dev/prod.

Kiểm tra: tạo hãng `API_BOOKING` mới trên UI → 201 thay vì 503. Sau đó chạy demo như phần mô tả của PR #372 (đặt xe, `…/dev/fake-ahamove/orders/{FK…}/events`, `play`).

## 9. Sự cố thường gặp

| Triệu chứng | Nguyên nhân / cách xử lý |
|---|---|
| `503 SECRETS_UNAVAILABLE` khi lưu hãng | Chưa có `STOCKFLOW_SECRETS_KEY` trong container: làm lại bước 1.3 (`up -d`, không phải `restart`). |
| App không khởi động sau khi đặt khoá | Khoá không phải base64 của 32 byte: sinh lại bằng `openssl rand -base64 32`. |
| Callback trả `401 WEBHOOK_SIGNATURE_INVALID` | Sai tên header hoặc giá trị; mã hãng trong URL sai; hãng `webhookEnabled=false`. Sự kiện `INVALID_SIGNATURE` trong danh sách lỗi có lý do (không nêu giá trị). |
| Sự kiện "credential could not be opened on this server" | Khoá `STOCKFLOW_SECRETS_KEY` đã đổi sau khi lưu hãng: nhập lại khoá API và giá trị xác thực. |
| Đặt xe lỗi `CARRIER_PICKUP_NOT_CONFIGURED` / `CARRIER_SERVICE_NOT_CONFIGURED` / `CARRIER_STOP_OUT_OF_AREA` | Thiếu điểm lấy hàng của kho, thiếu mã dịch vụ của loại xe + tỉnh, hoặc điểm giao khác tỉnh với kho: xem bước 3. |
| Callback giao xong ra `UNVERIFIED` | Ahamove staging chưa xác nhận kết quả đó khi hệ thống hỏi lại; Ahamove gửi lại thì áp dụng. Xem ghi chú sự kiện. |
| Đặt xe báo "did not answer" (`UNCONFIRMED`) | Không có hồi âm từ Ahamove: kiểm tra đơn có remark = mã chuyến trên cổng Ahamove, huỷ ở đó nếu có, rồi huỷ đặt xe ở hệ thống (docs 09 BR-29). |
