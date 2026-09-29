# Kế hoạch & hồ sơ migration toàn bộ DB — 28/9/2026

**Trạng thái:** phần **expand** đã viết xong và kiểm chứng trên DB thật (20 file `V20260928*` +
1 seed demo). Phần **contract** đã viết sẵn trong `src/main/resources/db/pending/` (C0–C4), chạy
khi code từng module chuyển xong. Sau khi merge, **thành viên chỉ viết code** (entity, repository,
service, controller). Không ai phải viết migration nữa, kể cả permission.

**Bản đích:** `docs/business-design/db-design/schema.dbml` — **107 bảng, 213 FK**. File này được **sinh từ DB thật**
bằng `tools/db/gen_dbml.py` sau khi chạy hết migration + contract, không vẽ tay. Nó thay cho
v9 (83 bảng). Phần chênh lệch nằm ở §3.

Nhánh: `hotfix/db-v9-full-schema` (tách từ `develop` 80dba8f), merge vào `develop` 29/9. Quy tắc cho team: [`README.md`](README.md).

---

## 1. Ràng buộc quyết định cách làm (kiểm tra trên code thật, không giả định)

| # | Sự thật | Hệ quả |
|---|---|---|
| R1 | `ddl-auto: validate`. Entity map cột nào thì cột đó phải tồn tại. | **Expand trước, contract sau.** Bảng/cột mới thêm song song; bảng cũ chỉ bị xoá khi code không còn map. |
| R2 | `OrderServiceImpl.placeOrder` gọi `inventory.reserve()` (ghi `stock_reservation.order_id`) **trước** khi `repository.save(order)`. | FK `stock_reservation.order_id` và `order_line_reservation.reservation_id` là **`DEFERRABLE INITIALLY DEFERRED`**. Đã kiểm bằng HTTP thật (§6). |
| R3 | Code dùng `@Enumerated(STRING)` trên `varchar`. | Migration dùng **`varchar + CHECK`**, không dùng native enum. DBML vẽ Enum chỉ để dễ đọc. |
| R4 | Flyway không chạy out-of-order. Develop đang ở `V20260919001700`; PR #36 dùng `V20260925/26…`. | Chuỗi mới bắt đầu từ `V20260928…`. **PR #36 phải merge trước**, hoặc đổi số (§7). |
| R5 | DB dev của thành viên có thể có dữ liệu test "mồ côi". Trên bản sao DB local của Tú có 6 `design_draft` trỏ tới khách không tồn tại. | FK mới thêm dạng **`NOT VALID` rồi validate**. Nếu dữ liệu cũ vi phạm thì FK vẫn chặn dữ liệu mới, còn Flyway chỉ **cảnh báo** chứ không làm app chết (§5, C0). |

Quy ước cho mọi bảng mới (để thành viên kế thừa `BaseEntity` được):
- `id uuid` do app sinh (UUIDv7, `Identifiers.newId()`), không có `DEFAULT`.
- Luôn có `version`, `created_at`, `created_by varchar(100)`, `last_modified_at`, `last_modified_by`. `created_at`/`version` có default, nên entity con không kế thừa `BaseEntity` vẫn insert được.
- `created_by` là **username để audit**. Người thực hiện một bước nghiệp vụ (submit, duyệt, nhận hàng…) là **cột uuid riêng có FK tới `identity.app_user`**.
- Bảng N-N có `id` riêng và unique cặp; không dùng khoá kép, không dùng FK kép (QA §1).
- FK một cột. Quan hệ "phải cùng cha" (option phải thuộc product của variant, dòng nhận hàng phải thuộc PO của phiếu…) được giữ bằng **trigger**.
- Liên module: `ON DELETE RESTRICT`. Dữ liệu nghiệp vụ chỉ đóng/huỷ, không xoá.

---

## 2. Quyết định đã áp dụng (mặc định tôi chọn — cần team xác nhận)

| Mã | Câu hỏi | Đã làm | Đổi nếu team không đồng ý |
|---|---|---|---|
| D1 | SKU 2 tầng hay 3 tầng | 2 tầng: `product.variants.sku` là SKU | Viết lại 1100 trước khi merge |
| D2 | `products.brand` (chữ) hay `brand_id` | **Chỉ `brand_id`**, bỏ cột chữ `brand` | Thêm cột bằng 1 migration |
| D3 | Chứng từ trỏ SKU bằng gì | Procurement dùng `inventory_item_id`; inventory/warehouse/fulfillment dùng `sku` (giống `stock_item`, máy quét). Cả hai đều trỏ về `inventory.inventory_items` | — |
| D4 | Trạng thái PO | Theo po-schema: `CONFIRMED`, không có `SENT` | Báo Võ (chủ SCRUM-115/118, PR #36) |
| D5 | Ảnh/template gắn đâu | Gắn **variant**. `media` giữ đủ metadata lưu file của PR #29 (storage_key, checksum, renditions, upload_key) + `is_published` thay cho gallery JSON | — |
| D6 | FK xuyên schema | Có. **ADR-0007** thay luật FK của ADR-0005; README, `adding-a-module.md`, CLAUDE.md đã sửa theo | — |
| D7 | PR #36 | Không đụng bảng cũ. **Merge #36 trước PR này** (R4). C4 chuyển FK của `po_delivery_decision`/`po_delivery_control` sang `purchase_orders`, không xoá tính năng | — |
| D8 | PR #28 | Migration của #28 **bỏ** (số version đã thấp hơn develop, không chạy được). Map kho do `V20260928000100/0200` tạo. Phương giữ entity, sửa theo §8 | — |
| D-W1 | `shelf.warehouse_id`, `storage_location.warehouse_id` không có FK (v9) | **Đã thêm FK** (28/9, theo khuyến nghị): 2 cột `*_id` duy nhất không có FK. QA W16/W17 | Bỏ 2 dòng `fk_*_warehouse` trong `V20260928000100` |
| D-G | Luồng 07–11, 14, 15, 17 thiếu bảng trong v9 | **Thêm 17 bảng** (phase G, §3) theo docs. Để ở file riêng `V202609280050xx` | Muốn bỏ nhóm nào thì xoá đúng file đó trước khi merge |

---

## 3. Bản đích so với v9

**Giữ nguyên v9:** 83 bảng, thêm các chỉnh sửa sau:
- Cột audit theo `BaseEntity` cho mọi bảng mới. Bỏ `updated_at/updated_by` và các FK `created_by → app_user` của v9 (xem quy ước §1).
- `product_categories`, `category_attributes`, `variant_attribute_values` có `id` riêng (v9 còn khoá kép).
- `inventory_items.package_count`: giữ lại từ bảng cũ (SCRUM-68). Nội thất thường giao nhiều kiện/1 sản phẩm.
- `media` thêm metadata file (D5).
- Bỏ unique `(id, kind)`, `(id, warehouse_id)` trên `storage_location`: chúng chỉ phục vụ FK kép, mà FK kép đã bị bỏ.
- Bảng thật v9 thiếu, nay có mặt trong DBML (vì DBML sinh từ DB): `ordering.order_hold`, `platform.idempotency_record`, `platform.shedlock`, `public.event_publication`, `warehouse.slotting_rule`, 23 cột snapshot của guest checkout, `order_line.design_checksum`, các cột `fulfillment.pick.*`, `design_artifact.upload_key`, đủ cột `catalog.*`. Kiểu `timestamptz` của `identity` cũng được ghi đúng.

**Thêm mới (phase G, 17 bảng + 1 bảng hạ tầng):**

| Bảng | Luồng docs | Vì sao cần |
|---|---|---|
| `inventory.stock_movement` | 11 BR-06 | Sổ cái mọi thay đổi tồn: ai, khi nào, từ đâu tới đâu, vì sao. Append-only |
| `inventory.stock_adjustment` | 07/08/11 | Điều chỉnh tồn có duyệt 4 mắt |
| `inventory.cycle_count`, `cycle_count_line` | 07/11 | Short pick / lệch khi di chuyển thì sinh kiểm kê |
| `inventory.transfer_order`, `transfer_order_line` | 10 | Chuyển kho: requested / shipped / received / damaged |
| `warehouse.move_task` | 11 | Replenishment, re-slotting, di chuyển tay |
| `fulfillment.pick_line` | 07 | Phân bổ theo bin & lô, short từng dòng |
| `fulfillment.package`, `package_line` | 08/09 | Một đơn nhiều kiện, mỗi kiện có tracking riêng |
| `fulfillment.shipment_event` | 09 | Lịch sử tracking (webhook idempotent) |
| `ordering.cart`, `cart_line` | 14 | Giỏ khách/khách vãng lai, gộp giỏ khi đăng nhập |
| `ordering.order_status_history` | 17 | Timeline đơn hàng. Append-only |
| `ordering.return_request`, `return_request_line` | 17 | RMA: không trả quá số đã mua |
| `payment.cod_remittance`, `cod_remittance_line` | 09/15 | Đối soát tiền COD với hãng vận chuyển |
| `platform.document_sequence` | — | Số chứng từ theo ngày cho mọi loại phiếu mới (GR, TO, ADJ…), thay vì mỗi loại một bảng |

Permission cho các bảng này **đã có sẵn** trong DB từ trước (`inventory-stock-movements`, `sales-carts`, `sales-rmas`, `payment-cod-remittances`…). Bằng chứng là docs đã dự tính chúng.

`putaway_task` có thêm `transfer_order_line_id` và `return_request_line_id`. Nhận từ PO, từ chuyển kho hay từ hàng hoàn đều đi qua putaway (docs 09 BR-06). C4 thêm CHECK "đúng 1 nguồn".

---

## 4. Các file đã viết

### 4.1 `db/migration` — chạy ngay khi merge

| File | Nội dung | Ảnh hưởng code hiện tại |
|---|---|---|
| `V20260928000100__warehouse_map_expand` | `warehouse` thêm `prefix/address/return_address/map_*` (nullable); tạo `zone, storage_location, shelf, shelf_level, bin, area, boundary`; `putaway_task.goods_receipt_line_id` | Không. Cột cũ `code/address_line/city` và bảng `location` vẫn còn |
| `…000200__warehouse_map_integrity` | `platform.forbid_update_of()` (BR-13: prefix/code/level/location_code không đổi); trigger bin/area ↔ storage_location (đúng loại, đúng kho, mã ghép đúng); shelf–zone cùng kho; location không được mồ côi (deferred) | Không |
| `…001000__pim_dictionary` | `brands, categories, attributes, attribute_values, category_attributes` | Không. Bảng cũ số ít vẫn còn |
| `…001100__pim_products_variants` | `products, product_categories, product_attributes, product_attribute_options, product_descriptive_values, variants, variant_attribute_values` | Không |
| `…001200__pim_media_customization` | `media, customization_templates, print_areas` | Không |
| `…001300__pim_integrity` | Cây category (depth/path, chống vòng); axis chỉ SELECT/COLOR; option đúng attribute; descriptive value đúng kiểu; variant chỉ lấy option của product mình, 1 option/axis; SKU khoá khi rời DRAFT; template chỉ cho CUSTOMIZABLE | Không |
| `…002000__proc_supplier_master` | `suppliers, supplier_addresses, supplier_contacts, supplier_items, supplier_item_prices` (+ `btree_gist`, chống chồng giá) | Không. Bảng cũ `supplier` vẫn còn |
| `…002100__proc_replenishment` | `replenishment_proposals, …_lines` | Không |
| `…002200__proc_purchase_orders` | `purchase_orders, …_revisions, …_lines, …_line_revisions, …_approvals, …_events, …_attachments, approval_limits`; FK revision deferred; 4 mắt | Không. `po_number_sequence` dùng lại |
| `…002300__proc_receiving_invoicing` | `goods_receipts, goods_receipt_lines, qc_inspections, supplier_invoices, supplier_invoice_lines` | Không |
| `…002400__proc_integrity` | `platform.append_only()`; revision/line/PO/phiếu nhận/hoá đơn phải cùng PO; nhận hàng chỉ khi PO `CONFIRMED`; **BR-04 dung sai nhận** (khoá dòng PO `FOR UPDATE`); QC ≤ số nhận | Không |
| `…003000__inventory_items` | `inventory_items` (FK `sku → variants.sku`, `default_supplier_id`); FK `inventory_item_id` của 5 bảng procurement; **BR-03** (thiếu cân nặng/kích thước, thiếu lô/HSD thì không cho nhận hàng) | Không |
| `…004000__cross_schema_fk_live` | 28 FK giữa các bảng đang chạy (order↔stock deferred, customer, payment, fulfillment, chat, design, user…) + index. `NOT VALID` → validate → cảnh báo nếu dữ liệu cũ bẩn | **Có**: xem §6 |
| `…005000…005500` | Phase G (§3) | Không (bảng mới) |
| `…006000__permissions_new_resources` | 13 resource mới + gán role (§8) | Không |
| `db/demo/V20260928009000__demo_master_data` | Chỉ local/test: 2 user tác giả (DISABLED, không đăng nhập được), khách demo `c0000000-…-0001`, brand/category/attribute/2 product/2 variant/2 inventory item, NCC + bảng giá, kho HCM đủ map (3 kệ × 2 tầng × 2 bin + 5 khu), **đổi mã vị trí 4 dòng tồn demo sang định dạng mới**, 1 PO nháp | Test dùng `DemoData.CUSTOMER_ID` |

Kèm theo: `src/test/.../support/DemoData.java`, sửa 2 integration test đặt hàng (trước đó dùng `customerId` ngẫu nhiên, FK mới sẽ từ chối).

### 4.2 `db/pending` — contract, chưa chạy

| File | Chạy khi | Làm gì | Ai kích hoạt |
|---|---|---|---|
| `C0__validate_foreign_keys` | `orphan_check.sql` báo 0 ở mọi môi trường | Validate FK nào 004000 còn để `NOT VALID` | Tú |
| `C1__contract_product` | Code product **và design** chỉ còn dùng bảng PIM mới (design đang kiểm `productId` ở bảng cũ) | FK `sku → variants` từ order_line/catalog/pricing/reporting; `design_draft.product_id → products`; xoá 8 bảng product cũ | Tú, sau khi chủ module product báo xong |
| `C2__contract_warehouse` | Entity warehouse map theo model mới; mọi `stock_item` nằm ở location có thật | Cột map `NOT NULL`, xoá `code/address_line/city`, xoá `warehouse.location`; FK putaway target, `stock_item.location_code → storage_location` | Tú, sau khi Phương báo xong |
| `C3__contract_inventory_items` | C1 xong; tạo variant thì tạo luôn inventory item (BR-07) | FK `stock_item.sku`, `putaway_task.sku → inventory_items` | Tú |
| `C4__contract_procurement` | Code PO chỉ còn map bảng mới; putaway tạo từ `goods_receipt_lines` | FK nguồn putaway + CHECK đúng 1 nguồn; chuyển FK bảng #36; xoá 6 bảng procurement cũ | Tú, sau khi Võ/Phương báo xong |

Cách kích hoạt: đổi tên thành `V<yyyyMMddHHmm00>__…` (lớn hơn file mới nhất lúc đó), chuyển sang `db/migration`, chạy checklist §9. Mỗi file mở đầu bằng khối kiểm tra mồ côi. Nếu còn dữ liệu sai, file dừng lại và báo bảng + số dòng.

---

## 5. Công cụ đi kèm (`tools/db/`)

| File | Dùng để |
|---|---|
| `orphan_check.sql` | Báo số dòng mồ côi cho **mọi** FK: loại "live" (đã có) và C1–C4 (sắp thêm), kèm danh sách FK đang `NOT VALID` |
| `qa/01..04_*.sql` + `qa/run.sh <db>` | **166 ca** test đối kháng + luồng đúng, tự rollback. Chạy lại sau mỗi thay đổi schema |
| `gen_dbml.py <db> <out>` | Sinh lại `schema.dbml` từ DB thật |

---

## 6. Đã kiểm chứng như thế nào (28/9, trên Postgres 16 của docker compose)

1. **DB trắng, profile local** (`db/migration` + `db/demo`): cả 67 migration chạy qua. Test các ca `NOT VALID`: 0 FK bị để lại.
2. **DB trắng, profile prod** (không có demo) → C0..C4: qua. Kết quả cuối **107 bảng, 213 FK**.
3. **Có PR #36 merge trước** (4 file `V20260925/26`) → toàn bộ + C0..C4: qua. `po_delivery_decision` và `po_delivery_control` trỏ sang `purchase_orders`.
4. **Bản sao DB local của Tú** (có dữ liệu): 25 migration chạy qua. Flyway cảnh báo đúng 1 FK (`design_draft.customer_id`, 6 dòng rác), app vẫn boot. `orphan_check.sql` chỉ ra đúng 6 dòng đó.
5. **Test đối kháng** `tools/db/qa/run.sh`: 166/166 ca đúng, mỗi ca bị từ chối đúng lý do (thông báo lỗi in kèm).
6. **App thật**: `mvn -o clean package`, `java -jar`, security bật, DB trắng + DB có dữ liệu. Hibernate `validate` qua mọi entity. Curl:
   - `POST /api/v1/customers/registrations` → 201
   - `POST /customers/{id}/addresses` → 201
   - `POST /api/v1/orders` 27 sofa (trải 2 bin) → **201**; DB: 2 reservation (27), 2 link, `customer_id` đúng. FK deferred qua được.
   - Gửi lại cùng `requestId` → 201, vẫn 1 đơn
   - `GET /orders/{id}` chủ đơn → 200
   - 500 sofa → **409**, không để lại reservation
   - Đặt hộ `customerId` người khác → 404
   - `POST /orders/{id}/cancellation` → 200, 2 reservation `RELEASED`
   - `POST /orders/guest-checkout` → **201**, `customer_id` NULL (FK nullable)
7. `python3 tools/verify.py` sạch (122 bảng khớp entity). `ModularityTest`, `ArchitectureTest`, `MessageBundleTest`, `BaseLayerRegressionTest`: 34/34. `mvn -o test`: 355 pass, 14 skip. 10 lỗi còn lại là 3 class Testcontainers không tìm được Docker trên máy Tú (đã biết), CI sẽ chạy chúng.

8. **Luồng staff** (user test gán SALES_STAFF + ECOMMERCE_ADMIN + ORDER_COORDINATOR + WAREHOUSE_MANAGER, đăng nhập thật):
   - Đặt hộ khách không tồn tại → 404 `CUSTOMER_NOT_FOUND` (service chặn trước FK)
   - Đặt hộ khách demo → 201
   - Tạo design draft hợp lệ → 200; cho khách không tồn tại → **409 `DUPLICATE_KEY`** (chỉ FK chặn, service design không kiểm; xem §10)
   - Tạo task fulfillment → 201; giao cho người không phải nhân viên kho → 400 (service chặn)
   - Huỷ đơn bằng quyền admin → 200, reservation `RELEASED`
9. **Integration test (Testcontainers) chạy được trên máy Tú.** Nguyên nhân cũ: Docker Engine 29 từ chối API 1.32 của docker-java. Đã sửa bằng `src/test/resources/docker-java.properties` (`api.version=1.44`). `mvn -o test`: **366/366 pass, 0 skip**. 11 test hỏng vì FK mới (design, media, customer tạo dữ liệu với id ngẫu nhiên) đã sửa sang dòng thật qua `support/ReferenceRows`.
10. QA chạy trên **cả 2 trạng thái**: sau expand và sau C0–C4. 166/166 ca, lý do từ chối giống nhau, trừ W15 (sau C2, NOT NULL thay cho CHECK, đúng thiết kế).

---

## 7. Thứ tự merge (đã chốt 29/9)

```
1. PR "DB v9 full schema" (nhánh này)  merge vào develop NGAY
2. PR #36 (Võ)       đổi số 4 file migration lên V20260929…, rebase, rồi merge (README §3)
3. PR #28 (Phương)   xoá file V20260918000100, sửa entity theo storage_location, rebase (README §3)
4. Từng module chuyển code sang bảng mới  →  Tú kích hoạt C1 / C2 / C3 / C4 / C0
```

Hai nhánh `hotfix/*` cũ trên remote (`modulith-observability-filter-crash`, `order-cancel-conflict-409`) đã nằm trong develop từ trước.

## 8. Bàn giao theo module

Người phụ trách lấy theo story Jira (tra ngày 28/9):

| Nhóm bảng | Story Jira → người | Resource permission (khai báo đúng tên này) | Contract |
|---|---|---|---|
| Map kho: `warehouse` (cột mới), `zone, shelf, shelf_level, bin, area, boundary, storage_location`; putaway | SCRUM-89/92 → **Phương** | `warehouse-warehouses`, `warehouse-locations`, `warehouse-putaway-tasks` | C2 |
| `move_task` | SCRUM-335 → **Võ** (ngưỡng), SCRUM-336/93 → **Phương** (thực hiện, re-slotting) | `warehouse-replenishment` | — |
| `products`, `brands`, luồng duyệt, `media` | SCRUM-44/72 → **Tú** | `product-products`, `-brands`, `-media` | C1 |
| `variants`, thuộc tính, `customization_templates`, `print_areas` | SCRUM-45 → **Phương**; SCRUM-297 (3D/tuỳ biến) → **Phương** | `product-variants`, `-attributes`, `-customization-templates` | C1 |
| `categories` | SCRUM-171 → **Võ** | `product-categories` | C1 |
| `inventory_items` | SCRUM-70 → **Võ** (lô/HSD/serial/ngưỡng); SCRUM-68 (logistics) → **Tú** | `inventory-inventory-items` | C3 |
| `stock_movement` | SCRUM-9/16 → **Tú** | `inventory-stock-movements` | — |
| `stock_adjustment` | SCRUM-145 → **Võ** | `inventory-stock-adjustments` | — |
| `cycle_count(_line)` | SCRUM-146 → **Phương** | `inventory-cycle-counts` | — |
| `transfer_order(_line)` | SCRUM-326 → **Tú** (tạo), SCRUM-328 → **Phương** (nhận tại kho đích) | `inventory-transfer-orders` | — |
| Nhà cung cấp, bảng giá | SCRUM-118 → **Võ** | `procurement-suppliers`, `-supplier-items` | C4 |
| PO, revision, duyệt, sự kiện | SCRUM-113/116 → **Tú**; 114/117 (duyệt, sửa đổi) → **Phương**; 115 (xác nhận NCC) → **Võ** | `procurement-purchase-orders`, `-approval-limits` | C4 |
| Đề xuất bổ sung | *(chưa có story riêng)* | `procurement-replenishment-proposals` | C4 |
| Nhận hàng, QC | *(không thấy story WBS 3.3 trên Jira — cần tạo)* | `procurement-goods-receipts`, `-qc-tasks` | C4 |
| Hoá đơn NCC | SCRUM-308 → **Phương**; 309 (đối chiếu 3 chiều) → **Võ** | `procurement-supplier-invoices` | C4 |
| `pick_line` | SCRUM-265/268 → **Võ**, 266 → **Phương**, 267 → **Tú** | `fulfillment-pick-lists` | — |
| `package(_line)` | SCRUM-278 → **Tú**, 279 → **Võ**, 280 → **Phương** | `fulfillment-packages` | — |
| `shipment_event` | SCRUM-288 → **Võ** | `fulfillment-shipments` | — |
| `cart(_line)` | SCRUM-190/193 → **Tú** | `sales-carts` | — |
| `order_status_history` | SCRUM-239 → **Tú** | `sales-orders` | — |
| `return_request(_line)` | SCRUM-243 → **Võ** | `sales-rmas` | — |
| `cod_remittance(_line)` | SCRUM-218 → **Tú** | `payment-cod-remittances` | — |

Hưng và Thái Vũ phụ trách frontend (SCRUM-372…389): không đụng schema.

Mọi resource trên **đã có trong DB** (seed sẵn hoặc có từ trước), đã gán role theo docs 00.

**Quy tắc cho thành viên:**
- Không thêm file vào `db/migration`. Cần đổi schema thì báo Tú.
- Entity kế thừa `BaseEntity`, enum dùng `EnumType.STRING`, id là `Identifiers.newId()`.
- Kiểm tham chiếu trong service (trả 404/409 có mã lỗi riêng) **trước khi** DB phải chặn. Vi phạm FK hiện rơi vào `DUPLICATE_KEY` 409 kèm log ERROR (ADR-0007).
- Số chứng từ mới lấy từ `platform.document_sequence` (`SELECT … FOR UPDATE` theo `(type, ngày)`).
- Ghi `inventory.stock_movement` ở **mọi** chỗ làm thay đổi tồn.

---

## 9. Checklist cho mỗi PR có migration (kể cả khi kích hoạt contract)

- [ ] `python3 tools/verify.py`
- [ ] DB trắng: migrate toàn bộ (local + prod path)
- [ ] DB có dữ liệu (bản sao DB dev): migrate; đọc cảnh báo; chạy `tools/db/orphan_check.sql`
- [ ] `tools/db/qa/run.sh <db>`: tất cả PASS
- [ ] `mvn -o clean test`: toàn bộ suite, kể cả integration test Testcontainers (chạy được trên Docker Desktop mới nhờ `docker-java.properties`)
- [ ] Boot `java -jar` với security bật (`--stockflow.security.enabled=true`, `JWT_JWK_SET_URI` đúng cổng), curl luồng bị ảnh hưởng
- [ ] Sinh lại `docs/business-design/db-design/schema.dbml` bằng `tools/db/gen_dbml.py`

---

## 10. Rủi ro và việc còn mở

| # | Vấn đề | Trạng thái |
|---|---|---|
| 1 | DB local của Tú lệch Flyway (thiếu `V20260917000200/000300`, `V20260919001600/1700`) | **Đã sửa 28/9**: backup ở `~/Documents/tmp/db-backups/`, áp bù 4 migration bằng code develop (`out-of-order` một lần). Develop boot bình thường |
| 2 | 6 `design_draft` rác ("Mug", tạo bởi user test `alice` 18–19/9, khách không tồn tại) trên DB local | **Chờ Tú xoá** (xoá dữ liệu phải do chủ DB làm; lệnh ở cuối file) |
| 3 | Số bảng tăng 83 → 107 | Có `2026-09-28-traceability.md`: mỗi bảng ↔ một bước trong docs |
| 4 | Phase G cần người làm | **Đã gán theo Jira** (§8). Nhận hàng/QC chưa có story WBS 3.3 → cần tạo |
| 5 | D-W1 | **Đã thêm FK** |
| 6 | Demo `TABLE-OAK-160` bật theo dõi HSD (giữ demo FEFO cũ) | Chấp nhận cho demo |
| 7 | QA insert `warehouse.code` | **Đã sửa**: helper tự nhận cột, QA chạy cả trước và sau C2 |
| 8 | Tài liệu tiếng Việt ngoài `docs/business-design/**` | **Đã chuyển** vào `docs/business-design/db-design/` |
| 9 | **Module design còn kiểm `productId` trong bảng product CŨ** (`ProductService` → `product.product`) | Phải chuyển cùng lúc với product trước khi chạy C1 (C1 xoá bảng cũ) |
| 10 | Vi phạm FK trả 409 `DUPLICATE_KEY`: sai nghĩa. Ví dụ tạo design draft cho khách không tồn tại | Đề xuất: (a) design service kiểm khách → 404 `CUSTOMER_NOT_FOUND`; (b) `GlobalExceptionHandler` tách SQLState 23503 thành mã riêng (`REFERENCE_NOT_FOUND`) |

Lệnh dọn #2 (chạy trong `psql` DB local, đã có backup):

```sql
BEGIN;
CREATE TEMP TABLE junk AS SELECT id FROM design.design_draft
 WHERE name = 'Mug' AND created_by = 'alice'
   AND NOT EXISTS (SELECT 1 FROM customer.customer c WHERE c.id = design_draft.customer_id);
UPDATE design.design_draft SET current_artifact_id = NULL, parent_snapshot_id = NULL WHERE id IN (SELECT id FROM junk);
DELETE FROM design.design_decision WHERE draft_id IN (SELECT id FROM junk);
DELETE FROM design.design_snapshot WHERE draft_id IN (SELECT id FROM junk);
DELETE FROM design.design_artifact WHERE draft_id IN (SELECT id FROM junk);
DELETE FROM design.design_draft WHERE id IN (SELECT id FROM junk);
COMMIT;
```
