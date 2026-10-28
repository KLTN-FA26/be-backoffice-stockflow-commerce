# Yêu cầu thay đổi schema — SCRUM-73 (Business Rules & Validation - Product, WBS 3.1.9)

Ngày đề xuất: 28/10/2026. Người xử lý schema: **Tú**. Người làm code: Phương (SCRUM-87, SCRUM-88).
Đối chiếu với `develop` @ `8e03ef63`; migration mới nhất lúc viết là `V20261028001000`.

Tài liệu này chỉ là **đề xuất**: nhánh SCRUM-73 không tự thêm file vào `db/migration` (CLAUDE.md §7).
Version, tên file và việc gộp với migration khác do Tú quyết.

## 1. Bối cảnh

Quy tắc của repo: mỗi bất biến được nói hai lần, một lần trong aggregate và một lần bằng `CHECK`.
Rà lại `product.products` sau SCRUM-44/56/57/68/85/461 thì phần lớn đã đủ hai lần (code, SKU, brand khi
submit, approver ≠ submitter, bằng chứng của từng trạng thái, lý do ngừng/tạm dừng). Còn ba chỗ code
chặn nhưng database không chặn:

| # | Bất biến | Aggregate / DTO hiện tại | Database hiện tại |
| --- | --- | --- | --- |
| 1 | `name` không trắng | `Product.requireNonBlank(name)` | chỉ `NOT NULL` — `'   '` lưu được |
| 2 | `name_en`, `tax_class` có trước khi duyệt | DTO bắt buộc (`@NotBlank`, `@NotNull`); trước nhánh này aggregate cho null, nay `draft()`/`updateDetails()`/`submit()` chặn (§4) | cả hai cột nullable, không có `CHECK` |
| 3 | có category chính trước khi duyệt | `Product.submit()` từ chối khi thiếu | không có gì (category nằm ở bảng khác) |

Các đường ghi khác ngoài controller (bridge legacy `V20261009000100`, carry `V20261011000200`, script
seed) không qua DTO, nên mục 1 và 2 hiện chỉ được giữ bởi việc "chưa ai ghi sai".

## 2. Đề xuất

### 2.1 `ck_products_name_not_blank` — bắt buộc

```sql
ALTER TABLE product.products
    ADD CONSTRAINT ck_products_name_not_blank CHECK (btrim(name) <> '');
```

Áp dụng cho mọi trạng thái: một sản phẩm không tên không có trạng thái nào hợp lệ.

Kiểm dữ liệu trước:

```sql
SELECT id, code, status FROM product.products WHERE btrim(name) = '';
```

Nếu có dòng vi phạm, hai cách (Tú chọn):
- sửa tên rồi thêm constraint bình thường; hoặc
- thêm `NOT VALID` như `V20261017000600` (customer segment): chặn ghi mới, dòng cũ dọn sau rồi
  `VALIDATE CONSTRAINT`.

### 2.2 `ck_products_details_on_submit` — bắt buộc

```sql
ALTER TABLE product.products
    ADD CONSTRAINT ck_products_details_on_submit CHECK (status <> 'PENDING_APPROVAL'
        OR (tax_class IS NOT NULL AND name_en IS NOT NULL AND btrim(name_en) <> ''));
```

Cùng hình dạng với `ck_products_brand_on_submit` (`V20261014001100` §2), và vì cùng lý do chỉ ràng ở
`PENDING_APPROVAL`: mọi trạng thái sau đều phải đi qua bước này, còn các dòng `APPROVED`/`PUBLISHED` cũ
thiếu dữ liệu vẫn phải cập nhật được (ví dụ tạm dừng, ngừng bán). `DRAFT` được phép thiếu.

Kiểm dữ liệu trước, theo đúng khuôn của §2 trong `V20261014001100`:

```sql
DO
$$
DECLARE
    pending INTEGER;
BEGIN
    SELECT count(*) INTO pending FROM product.products
     WHERE status = 'PENDING_APPROVAL'
       AND (tax_class IS NULL OR name_en IS NULL OR btrim(name_en) = '');
    IF pending > 0 THEN
        RAISE EXCEPTION 'SCRUM-73: % product(s) are PENDING_APPROVAL without tax class or English name; reject them back to DRAFT or fill them in, then migrate again',
            pending USING ERRCODE = 'check_violation';
    END IF;
END
$$;
```

> Có thể gộp 2.2 vào `ck_products_brand_on_submit` thành một constraint. Đề xuất để **riêng**: tên
> constraint nằm trong log lỗi, và tách ra thì biết ngay thiếu brand hay thiếu chi tiết.

### 2.3 Category chính trước khi duyệt — **không đề xuất**

Quyết định (28/10/2026): **không làm**; quy tắc category chỉ được nói một lần, trong
`Product.submit()`, và javadoc của nó ghi lý do. Phần dưới giữ lại để biết đã cân nhắc gì.

Lý do không làm:
- Thân hàm trigger là SQL dạng chữ. Một migration sau này đổi tên `product_categories` hoặc
  `is_primary` vẫn chạy qua, Postgres không sửa thân hàm cũng không báo lỗi; đến lúc chạy thật thì
  mọi lần gửi duyệt lỗi 500. Một `CHECK` thì khác: `RENAME COLUMN` được Postgres cập nhật theo.
- Trigger không bắt việc xoá category chính sau khi đã gửi duyệt (xem dưới), nên vẫn không kín.
- Chỉ aggregate `Product` ghi category chính, và chỉ khi `DRAFT`: trigger canh một đường không ai đi.

Phương án đã cân nhắc: `CHECK` không nhìn sang `product.product_categories`, nên muốn database chặn thì
cần trigger, dạng constraint trigger hoãn đến commit để thứ tự flush của Hibernate trong cùng transaction
không ảnh hưởng:

```sql
CREATE FUNCTION product.check_primary_category_on_submit() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.status = 'PENDING_APPROVAL' AND NOT EXISTS (
            SELECT 1 FROM product.product_categories pc
             WHERE pc.product_id = NEW.id AND pc.is_primary) THEN
        RAISE EXCEPTION 'product % cannot wait for approval without a primary category', NEW.code
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER tg_products_category_on_submit
    AFTER INSERT OR UPDATE OF status ON product.products
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION product.check_primary_category_on_submit();
```

Lỗi ở commit đến ứng dụng dưới dạng `TransactionSystemException`/`JpaSystemException`;
`GlobalExceptionHandler.handleFailedCommit` đã trả 409 cho SQLSTATE lớp `23`, nên không cần đổi gì phía
web.

Trigger này chỉ bắt lúc chuyển trạng thái. Xoá category chính của một sản phẩm đã submit thì không bắt;
ứng dụng hiện chỉ cho sửa ở `DRAFT` nên đường đó không có. Nếu muốn kín cả phía
`product_categories` thì cần thêm trigger trên bảng đó.

## 3. Không cần đổi schema

| Việc | Lý do |
| --- | --- |
| Chặn brand/category inactive khi tạo hoặc đổi | `brands.is_active`, `categories.is_active` đã có; service đọc thêm |
| Phát hiện trùng nghiệp vụ (SCRUM-88) | so trong Java trên tập ứng viên lọc theo brand + category; không cần index, không gọi `catalog.immutable_unaccent` từ SQL của `product` |
| Error code mới (`BRAND_INACTIVE`, `CATEGORY_INACTIVE`, …) | nằm trong `ErrorCode.java` và bundle i18n |
| Sửa enforcement point BR-PRD-001/004/006 | chỉ là `05-business-rules.md` |

## 4. Phía code đi kèm (nhánh SCRUM-73, không phải việc của Tú)

- `Product.draft()` / `updateDetails()` bắt buộc `nameEn`, `taxClass`; constructor dựng lại từ DB vẫn
  chấp nhận null của dòng cũ; `submit()` từ chối khi thiếu, trả field error như brand.
- Javadoc của `Product.submit()` đã nhắc `ck_products_details_on_submit` và trỏ về tài liệu này, và ghi
  vì sao quy tắc category chỉ nói một lần (§2.3). Khi Tú thêm constraint, javadoc được bổ sung tên
  `ck_products_name_not_blank` cùng lúc với test tích hợp của từng constraint (§5).

## 5. Kiểm chứng đề xuất

- `ProductLifecycleIntegrationTest`: mỗi constraint mới một test ghi thẳng bằng SQL và mong lỗi có tên
  constraint, như test hiện có cho `ck_products_brand_on_submit`.
- `tools/db/qa/17_product_rules.sql` (số tiếp theo sau `16_money_ledger.sql`): INSERT/UPDATE đối nghịch
  cho từng constraint, chạy qua `tools/db/qa/run.sh`.
- `schema.dbml` sinh lại bằng `tools/db/gen_dbml.py`.

## 6. Cần Tú quyết

1. 2.1 thêm bình thường hay `NOT VALID`, tuỳ dữ liệu thật.
2. Version và tên file migration (phải mới hơn migration mới nhất trên `develop` lúc merge).

(2.3 đã chốt là không làm; nếu Tú thấy cần thì mở lại.)
