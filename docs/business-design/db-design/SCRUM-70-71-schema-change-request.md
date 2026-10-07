# Yêu cầu schema và hợp đồng tích hợp — SCRUM-70/71

> Cập nhật 08/10/2026: nhánh hiện có adapter canonical và migration bổ sung
> `V20261008000100__canonical_inventory_and_ecommerce.sql` để owner review.
> Chi tiết triển khai và điểm còn vướng nằm trong `docs/SCRUM-70-71-backend.md`.
> Nội dung bên dưới là yêu cầu phối hợp ban đầu ngày 03/10; các mô tả "code đang dùng legacy"
> đã được thay thế cho phần 70/71. Chưa khẳng định Tú đã duyệt schema hay upstream PIM writers
> đã chuyển xong. Không kích hoạt C1/C3, không thay lịch sử Flyway.

Ngày: 03/10/2026. Người xử lý schema: **Tú**. Trạng thái: **đề xuất chờ owner duyệt**, không phải migration đã thực hiện.

## 1. Quyết định phạm vi

- PR #38 chỉ giữ cấu hình tồn (70), nội dung/hiển thị bán hàng (71).
- Kiểm kê (146, Phương), cảnh báo (147, Tú), báo giá thiết kế (298, Tú) chuyển sang phối hợp riêng.
- Không tạo thêm bảng nguồn cạnh tranh với schema develop. Không dùng `min_qty` thay `safety_stock`, không dùng `created_at` giả làm thời điểm nhận hàng.
- Không kích hoạt C1/C3 trước khi code, dữ liệu và các consumer đã chuyển xong.

## 2. Bằng chứng và điểm đang chặn merge

Schema develop được kiểm tra ở commit `6edb50b`; snapshot nhánh 70/71 trước tách là `b7d6cda`.

| Nguồn hiện có | Đã có | Vấn đề nhánh 70/71 |
| --- | --- | --- |
| V20260928003000, inventory.inventory_items | SKU FK variants, uom, lot/serial/expiry flags, reorder_point nullable, min_qty/max_qty, version/audit | Code đang ghi thêm product.sku và inventory.sku_policy; chưa dùng một nguồn chuẩn |
| V20260928001100, product.products | slug unique, seo_title, seo_description, trạng thái và bằng chứng duyệt | catalog.product_listing đang là nguồn SEO thứ hai |
| V20260928005000 | cycle_count, cycle_count_line, stock_adjustment, stock_movement | Migration kiểm kê tự tạo trùng đã được tách khỏi PR38 |

Hai migration nhánh `V20260930001000` và `V20260930001100` **vẫn là triển khai legacy tạm trong working tree**, không phải thiết kế được duyệt. Chúng cần được owner thay thế/đối soát cùng lúc chuyển adapter. Không merge PR38 với hai nguồn dữ liệu này.

## 3. Đề nghị cho inventory.inventory_items

Giữ một dòng trên SKU; giữ FK tới `product.variants(sku)`, version/audit hiện có.

| Trường | Đề nghị | Ràng buộc / ý nghĩa |
| --- | --- | --- |
| reorder_point | Dùng cột INTEGER nullable đã có | NULL = chưa cấu hình; 0 là ngưỡng thật; >= 0 |
| safety_stock | Bổ sung INTEGER nullable | >= 0; nếu cả hai ngưỡng có giá trị thì safety_stock <= reorder_point |
| removal_strategy | Bổ sung VARCHAR với FIFO/FEFO | Chọn mặc định/backfill dựa trên chính sách đã xác nhận; không tự bật FIFO khi không có lịch sử nhận |
| max_shelf_life_days | Bổ sung INTEGER nullable | 1..36500 nếu có; chỉ dùng khi expiry_tracked=true |
| lot_tracked / serial_tracked / expiry_tracked | Dùng cột hiện có | Không tạo tracking flags trên product.sku nữa |

### Cần Tú chốt trước khi sửa DTO/domain

Schema hiện tại yêu cầu expiry_tracked => lot_tracked. Code thử nghiệm dùng enum NONE/LOT/SERIAL loại trừ nhau, cho phép SERIAL+expiry: **hai bên không tương thích**.

Đề xuất ưu tiên model develop: ba cờ độc lập, expiry yêu cầu lot; lot và serial có thể cùng bật khi cần truy xuất từng chiếc trong một lô. Nếu owner chọn cấm kết hợp lot+serial, phải ghi rõ giới hạn nghiệp vụ và kiểm thử. Không tự sửa CHECK để chiều enum cũ. FIFO/FEFO và tracking là hai quyết định khác nhau; FEFO khi có hạn dùng, FIFO dùng receipt time thật.

### Ý nghĩa ngưỡng thống nhất với SCRUM-147

- Tồn dùng đánh giá = tổng on_hand có status AVAILABLE và chưa hết hạn theo ngày Việt Nam, không trừ reserved; khác ATP.
- reorderRequired = tồn <= reorder_point; belowSafetyStock = tồn < safety_stock.
- Ngưỡng NULL trả trạng thái đánh giá NULL, không coi là false hoặc 0.
- Cho phép ngưỡng cao hơn tồn: lưu thành công và trả đánh giá ngay; không chặn vì giá trị dương cao.
- Sau commit phát SkuInventoryControlChanged; 147 đọc lại policy/tồn hiện tại, đánh giá ngay sau sự kiện, có lịch quét bù. PR70 không tự gửi email.
- Chính sách hiện tại là theo SKU toàn hệ thống. Nếu cần theo kho phải mở yêu cầu riêng, không đổi ngầm khóa dữ liệu.

## 4. Dữ liệu FIFO/serial trên tồn thực tế

Nhờ Tú chỉ định bảng/lớp tồn chuẩn và bổ sung metadata còn thiếu, không tạo sổ tồn thứ hai:

- Thời điểm nhận thực tế (TIMESTAMPTZ), liên kết nguồn nhận hàng/layer; chuyển vị trí không làm mới tuổi FIFO.
- Serial chuẩn hóa, không rỗng; duy nhất theo SKU đối với tồn đang hoạt động; một serial không đại diện nhiều chiếc.
- Lot, expiry và nhận hàng phải nhất quán với chính sách; ngày hết hạn tối đa phải có mốc ngày nhận thật để đối chiếu.
- Khóa nhận dạng layer không gộp hai lần nhận khác tuổi hoặc hai serial; giữ FK đúng schema mới.
- Đổi tracking/expiry/FIFO phải chặn nếu tồn hoặc reservation hiện hữu không tương thích; không âm thầm xóa lot/serial/expiry.
- Lô cũ thiếu ngày nhận: báo để đối soát, chưa cho chuyển FIFO; không backfill NOW()/created_at làm ngày nhận giả.
- Khóa policy và ghi tồn dùng chung thứ tự khóa theo SKU, sau đó stock id; 146/nhận hàng phải tuân thủ cùng hợp đồng để tránh race.
- Mọi tăng/giảm tồn phải có stock_movement cùng transaction; metadata/cấu hình không tự tạo movement số lượng.

## 5. SCRUM-71: nguồn product, projection catalog

Không đề nghị tạo lại slug/SEO: **dùng product.products.slug/seo_title/seo_description đã có**.

1. Product API chịu trách nhiệm đọc/ghi nguồn, version check, slug normalization + uniqueness, ràng buộc sửa sau xuất bản.
2. Catalog chỉ giữ snapshot đọc, trạng thái chiếu và source version. Không có endpoint ghi SEO độc lập vào catalog.
3. Nhờ Tú xác nhận nơi lưu publication revision/projected revision, dấu ever_published (để ẩn sản phẩm rồi vẫn không đổi URL), published snapshot và retry cursor. Chỉ bổ sung trường thực sự thiếu; không sao chép bảng product_listing cũ.
4. CatalogEntry hiện tại cần mapping product id và SKU chuẩn, FK/revision phù hợp. Không JOIN chéo schema từ repository; lấy nguồn qua product.api.
5. Slug của product mới cho phép 255, SEO title 255, description TEXT; DTO cũ 140/300/500 phải căn lại sau khi chốt giới hạn nghiệp vụ, không truncate dữ liệu.
6. Chỉ chiếu product PUBLISHED với master/gallery được duyệt, có danh mục, SKU và giá VND hợp lệ. Draft/unapproved luôn ẩn.
7. Cho phép SEO projection trễ ngắn; publish/unpublish và giá checkout kiểm tra nguồn hiện tại. Sự kiện cũ không được làm sống lại sản phẩm đã ẩn.
8. Giá dùng nguồn pricing đã thống nhất; VND nguyên, >0; không tự thêm nguồn giá trên product. Giá thiếu trả PRICE_NOT_AVAILABLE; không lấy giá client làm fallback.

## 6. Kế hoạch chuyển dữ liệu (Tú sở hữu migration)

1. Xác nhận mới nhất trên develop và các DB đã chạy migration nào; số migration mới phải lớn hơn bản mới nhất khi merge.
2. Export/backup và đối chiếu SKU/id legacy với product.products/variants/inventory_items. Không suy ra mapping bằng tên sản phẩm.
3. Liệt kê xung đột slug, ngưỡng, tracking và metadata thiếu; chốt nguồn thắng từng trường. Không ghi đè dữ liệu mới bằng bản legacy.
4. Expand schema và backfill có bằng chứng; CHECK/FK/unique + audit/version; quyền thay đổi phải tăng role version trong cùng transaction.
5. Code chuyển adapter product/inventory/catalog và fixtures sang nguồn mới; dừng dual write, xác minh không còn đọc sku_policy/product.sku/product_listing như nguồn.
6. Chạy fresh migration, upgrade có dữ liệu, orphan check, DB QA, module/architecture và API regression.
7. Tú kích hoạt contract C1/C3 đúng điều kiện, không xóa bảng khi consumer còn dùng.

Nếu DB đã chạy các migration vừa tách: không tự xóa flyway_schema_history, không repair/checksum tùy tiện. Owner chuẩn bị đường nâng cấp bảo toàn dữ liệu; DB test dùng container mới.

## 7. Điều kiện nghiệm thu và câu trả lời cần từ Tú

- [ ] Chốt tên/type/default ba trường thiếu và model lot+serial+expiry.
- [ ] Chốt receipt layer/serial metadata, khóa và mapping dữ liệu cũ.
- [ ] Chốt storage publication/revision/retry và API product nguồn mới.
- [ ] Xác nhận đối tượng quyền cấu hình tồn là inventory item hay product; seed theo role + version, FE biết route/permission mới.
- [ ] Phản hồi số migration/PR, thứ tự merge, chính sách với DB đã chạy migration nhánh.
- [ ] Test NULL/0/âm/ngưỡng cao, stock conflict, concurrent policy/receipt/count, FIFO cùng lot khác ngày, serial trùng.
- [ ] Test draft không lộ, slug collision đồng thời, immutable slug sau ẩn, out-of-order events, thiếu giá, giả giá, VND lẻ/ngoại tệ.
- [ ] Fresh/upgrade schema cùng pass, không bảng nguồn trùng và không mất ledger.

Đây là yêu cầu đã soạn để gửi Tú, **chưa đồng nghĩa Tú đã nhận hoặc phê duyệt**.
