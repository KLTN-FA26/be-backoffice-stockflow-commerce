# QA cụm catalog/warehouse + quyết định Phase 2 (FK thật xuyên schema) — 27/9/2026

Tài liệu này gom lại toàn bộ ghi chú, cảnh báo và quyết định phát sinh trong lúc QA bản DBML đề
xuất cho PIM (Product Information Management) và warehouse map/slotting, để không lẫn vào file
DBML dùng nộp đồ án (`v7-clean-for-thesis.dbml` — chỉ có bảng và quan hệ, không note).

Phương pháp: parse DBML bằng `@dbml/core`, xuất DDL Postgres, nạp vào Postgres thật, chạy các ca
chèn/xoá/sửa đối kháng để chứng minh từng phát hiện bằng dữ liệu thật (không chỉ đọc code).

---

## 1. Đổi tên schema PIM: `catalog` → `product`

Schema `catalog` **đã tồn tại thật trong code**, thuộc module storefront
(`catalog.catalog_entry`, `catalog.promotion`, `catalog.pricing_rule` — `V20260902000500`). Nếu
đặt PIM vào cùng tên `catalog`, hai module khác nhau bị dồn chung 1 schema — vi phạm "1 schema /
module" và sẽ bị `ModularityTest` chặn lúc build.

**Quyết định:** đổi toàn bộ PIM sang schema `product` (đúng tên module thật trong code hiện tại:
`product.product`, `product.variant`...). Tên `catalog` trả lại cho đúng chủ của nó.

---

## 2. `product.products` — 22 cột thật bị thiếu trong mọi bản DBML trước đó

`product.product` (code thật) đã có các cột này qua 6 migration (`V20260903000300` →
`V20260917000300`) mà không bản DBML nào trước đây đưa vào:

`name_en`, `description_en`, `brand`, `tax_class`, `submitted_by`, `weight_kg`, `length_cm`,
`width_cm`, `height_cm`, `package_weight_kg`, `package_length_cm`, `package_width_cm`,
`package_height_cm`, `package_count`, `hazmat`, `oversized`, `requires_adult_signature`,
`shipping_restriction_note`, `storage_class`.

**`storage_class` là cột quan trọng nhất** — warehouse cần nó để lọc vị trí putaway theo hard
constraint (doc 06-warehouse-map-slotting, BR-04). Thiếu cột này thì slotting theo lớp lưu trữ
không có dữ liệu để tra.

**Cảnh báo chưa xử lý:** hiện có **cả `brand_id`** (FK → `product.brands`) **và `brand`**
(varchar, có thật trên code) — 2 nguồn cho cùng 1 khái niệm "thương hiệu". **Team cần chọn 1**,
chưa quyết định trong QA này.

---

## 3. Gộp 3 tầng `product → variant → sku` thành 2 tầng

Code thật hiện có `product.sku` (`variant_id`, `code`, `barcode`, `lot_tracked`,
`unit_of_measure`) là bảng riêng. Bản DBML đề xuất gộp `sku` thẳng vào `variant`, nên
`barcode`/`lot_tracked`/`unit_of_measure` được đưa lên `product.variants` để không mất dữ liệu
`inventory`/`procurement` đang đọc thật:

- `lot_tracked` quyết định SKU có bắt buộc lô/hạn dùng không — lý do `inventory.stock_item.lot_number` để nullable.
- `unit_of_measure` — đơn vị cơ sở mà `stock_item` ghi nhận tồn (doc 05-putaway).
- `barcode` — dùng quét lúc nhận/putaway/picking.

**Cần team chốt:** giữ 2 tầng (như đề xuất) hay tách lại thành bảng `product.sku` riêng như code
thật đang có.

---

## 4. Bỏ FK kép (composite FK), đổi sang nối bằng 1 khoá

Theo yêu cầu QA (đợt 27/9), thay các FK kép bằng FK đơn qua id riêng của bảng trung gian:

- `product_attributes` được thêm `id` riêng.
- `product_attribute_options` được thêm `id` riêng, nối `product_attribute_id` (FK đơn tới
  `product_attributes.id`) thay vì `(product_id, attribute_id)`.
- `product_descriptive_values` nối qua `product_attribute_id` (FK đơn) thay vì
  `(product_id, attribute_id)`.
- `variant_attribute_values` chỉ còn 2 FK đơn: `variant_id` và `product_attribute_option_id`,
  bỏ hẳn `product_id`.

**CẢNH BÁO chưa giải quyết — mất 1 ràng buộc dữ liệu thật:** khi còn FK kép, DB tự chặn được việc
1 variant của sản phẩm A nhận giá trị vốn chỉ là option của sản phẩm B (đã test: ca "A1" bị chặn
khi có FK kép, được chấp nhận khi không có). Sau khi đổi sang FK đơn, **DB không còn tự kiểm tra
được điều này nữa** — phải chuyển sang trigger hoặc kiểm tra ở tầng application/aggregate. Chưa
có trigger/code nào bù lại việc này.

`attribute_values` cũng bỏ unique kép `(attribute_id, id)` vì không còn ai nối composite tới nữa.

---

## 5. Bỏ nối `product` khỏi `customization_templates` và `media`

Theo yêu cầu QA, cả hai bảng chỉ còn `variant_id NOT NULL`, bỏ hẳn `product_id` và khái niệm "NULL
= dùng chung cho cả product".

**Hệ quả nghiệp vụ chưa được BA xác nhận:**
- Mỗi variant tuỳ chỉnh giờ PHẢI có dòng `customization_templates` riêng — không còn cấu hình dùng
  chung cho cả product. Doc `12-product-design` BR-04 đang giả định có template dùng chung.
- Ảnh dùng chung cho nhiều variant của cùng 1 product (ảnh tổng thể sản phẩm) giờ phải lặp lại 1
  dòng `media` cho mỗi variant.

**Cần làm trước khi migrate:** hỏi lại BA/FE về 2 hệ quả trên.

---

## 6. `warehouse.shelf` / `warehouse.storage_location` — bỏ FK tới `warehouse`

Theo yêu cầu QA, bỏ 2 FK:
- `warehouse.shelf.warehouse_id → warehouse.warehouse.id`
- `warehouse.storage_location.warehouse_id → warehouse.warehouse.id`

**CẢNH BÁO — mở lại 1 lỗi đã từng vá:** `shelf.zone_id` là tuỳ chọn. Nếu 1 shelf được tạo không
gán zone, nó không còn đường FK nào tới bất kỳ warehouse nào — `warehouse_id` lúc đó chỉ là chuỗi
tự khai, không ai kiểm. `storage_location.warehouse_id` cũng vậy — không còn bị kiểm, có thể khai
sai kho mà không đứng chung cây với bin/area thật đang gán cho nó. Đây đúng là lỗi "rò khác kho"
đã được test và vá trong 1 vòng QA trước, giờ quay lại vì yêu cầu đổi mới. **Cần team xác nhận có
đúng là chủ đích hay không** — nếu đúng, nên bắt `zone_id` NOT NULL trên shelf để còn 1 đường liên
kết.

---

## 7. `warehouse.putaway_task` — vá SCRUM-92

Thêm FK thật `target_location_id → storage_location.id`. Trước đây (code thật, migration
`V20260902000300`), cột này FK vào bảng `warehouse.location` (flat, cũ); PR #28 (chưa merge) drop
FK đó và để `target_location_id` thành UUID trần với comment "no single FK fits — settled in
SCRUM-92". Supertype `storage_location` (đề xuất mới) chính là lời giải cho SCRUM-92 — đã kiểm tra
FK này chạy sạch trên Postgres thật.

**Việc còn thiếu:** chưa có service nào nối `goods_receipt Confirmed → tạo putaway_task → cập nhật
stock_item` — `WarehouseServiceImpl` hiện chỉ 25 dòng, rỗng. Đây là việc code riêng, ngoài phạm vi
DB design.

---

## 8. `procurement.qc_result` — rút lại đề xuất thêm `Area.type = QUALITY_CONTROL`

QA vòng trước từng đề xuất thêm loại khu vực `QUALITY_CONTROL` vào `warehouse.area.type`. Sau khi
đọc `procurement.qc_result` (code thật): QC là **1 dòng dữ liệu gắn với `goods_receipt`**, không
có cột vị trí/khu vực nào — tức QC không phải là 1 khu vực vật lý. **Rút lại đề xuất
`QUALITY_CONTROL`**, để BA xác nhận lại nếu muốn thêm.

`procurement.goods_receipt` chỉ là header — không có dòng theo SKU/vị trí; `quantity_received`
nằm trên `po_line`, kết quả QC nằm trên `qc_result`. Không có bảng `receipt_line` riêng trong code
thật (khác với 1 bản đề xuất DBML trước đó có bảng này).

---

## 9. `customer.address` — địa chỉ 2 cấp

Migration gốc (`V20260902000100`) có `city`/`district`. Migration sau
(`V20260919001600__customer_accounts_and_two_level_addresses.sql`) đã **DROP cả hai**, thay bằng
`ward`/`ward_code`/`province`/`province_code` (bắt buộc), và thêm `customer.customer.user_id`
(logic tham chiếu `identity.app_user`, ban đầu **không FK** theo ADR-0005). DBML nộp đồ án phản ánh
đúng hiện trạng này, khác với migration gốc.

---

## 10. `design.*` — RÚT GỌN, cần đọc file thật trước khi migrate

Module `design` đã qua 8 migration (`V20260902000600` + 7 file `V202609190*`), tích luỹ nhiều cột
(`owner_user_id`, `reviewer_user_id`, `current_artifacts` JSONB, `artifact_manifest` JSONB...).
DBML này liệt kê cột chính đã gộp lại nhưng **không tái tạo đủ chi tiết CHECK/index từng bước** —
trước khi dùng để viết migration thật, phải đọc lại nguyên các file gốc.

Một thay đổi thật đáng chú ý: `uk_design_snapshot_checksum` (unique toàn cục) đã bị **DROP** bởi
`V20260919000300` khi cho phép sửa/duyệt lại — DBML phản ánh đúng hiện trạng, khác bản đầu.

---

## 11. Quyết định Phase 2: nối FK thật xuyên schema — thay thế ADR-0005

**Quyết định của Tú (27/9):** thay vì giữ nguyên tắc "không FK chéo schema" (modular monolith,
ADR-0005), toàn bộ 25 cột trước đây là "cột thường, không FK" được nối FK thật:

`sku` (6 bảng → `product.variants.sku`), `location_code`, `order_id` (6 bảng), `customer_id`
(3 bảng), `reservation_id`, `design_snapshot_id`/`snapshot_id`, `order_line_id`, `product_id`,
`user_id` (2 bảng), `goods_receipt_id`.

Đã nạp DDL vào Postgres thật: 68 bảng, 81 FK, sạch.

**Hệ quả kiến trúc cần ghi nhận:**

1. **Đây không còn là Modular Monolith đúng nghĩa.** Các module không thể tách ra
   database/service riêng được nữa vì FK vật lý đã ràng buộc chung — đúng điều ADR-0005 cảnh báo
   trước ("would tie the two modules' migrations together and block ever extracting one"), giờ đã
   thành cụ thể.

2. **Vòng lặp phụ thuộc thật giữa 2 module** `ordering` ↔ `inventory`:
   ```
   ordering.order_line_reservation.reservation_id → inventory.stock_reservation.id
   inventory.stock_reservation.order_id            → ordering.customer_order.id
   ```
   Chạy được (đã test — Postgres không chặn cyclic FK giữa 2 schema, và thứ tự ghi dữ liệu thực tế
   không gây deadlock: đơn hàng tạo trước, reservation tạo sau trỏ vào đơn, order_line_reservation
   tạo cuối cùng trỏ vào reservation). Nhưng về kiến trúc, 2 module này từ nay không thể tách rời.

3. **`notification.delivery_log.template_code` KHÔNG FK được** — khoá thật của `template` là cặp
   `(code, locale)`, không phải `code` một mình; đây cũng là log lịch sử nên template có thể đã bị
   đổi/xoá. Giữ nguyên là cột thường.

4. **Việc này mới ở DBML.** Áp dụng vào code thật cần: xoá/viết lại ~8 chỗ comment "no FK,
   cross-schema" trong migration hiện có; viết migration mới thêm 25 `ALTER TABLE ... ADD FOREIGN
   KEY` (đúng thứ tự phụ thuộc); đảm bảo dữ liệu hiện có sạch trước khi thêm FK; kiểm tra lại
   `verify.py`/`ModularityTest` có rule nào chặn cross-schema FK không — nếu có, phải sửa và ghi
   lại lý do (viết ADR mới thay ADR-0005, không chỉ xoá rule).

**Chưa làm trong PR này:** chưa viết migration thật, chưa sửa `verify.py`/`ModularityTest`, chưa
viết ADR mới thay thế ADR-0005. Đây là quyết định thiết kế cần cả team đồng ý trước khi đụng vào
code, vì đảo ngược nguyên tắc nền tảng của kiến trúc hiện tại.

---

## Việc cần làm tiếp (chưa làm trong PR này)

1. Chốt cách xử lý `brand_id`/`brand` trùng khái niệm trên `product.products` (mục 2).
2. Chốt 2 tầng hay 3 tầng cho `product/variant/sku` (mục 3).
3. Bù lại kiểm tra "giá trị thuộc đúng product" đã mất khi bỏ FK kép — trigger hoặc validate ở
   aggregate (mục 4).
4. Hỏi BA/FE về hệ quả "mỗi variant phải có template/media riêng" (mục 5).
5. Xác nhận có cố ý bỏ FK `shelf`/`storage_location` → `warehouse` không; nếu có, bắt `zone_id`
   NOT NULL trên shelf (mục 6).
6. Viết service nối `goods_receipt → putaway_task → stock_item` (mục 7, việc code riêng).
7. Nếu chốt theo Phase 2 (mục 11): viết ADR mới thay ADR-0005, viết migration thật, kiểm tra lại
   `verify.py`/`ModularityTest`.

File DBML liên quan (không đưa vào repo code, lưu ngoài `/Users/tusry/Documents/tmp/pr-reviews-0924/dbml-qa/`):
`v5-full-database.dbml` (bản đầy đủ, chưa Phase 2), `v6-real-fk-phase2.dbml` (bản Phase 2, đầy đủ
note), `v7-clean-for-thesis.dbml` (bản sạch dùng nộp đồ án).
