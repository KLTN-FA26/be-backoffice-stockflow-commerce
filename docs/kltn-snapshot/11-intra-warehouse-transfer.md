# 11 — Chuyển hàng trong nội bộ một kho (Intra-warehouse Transfer)

## 1. Mục đích (Purpose)

Di chuyển **nguyên thùng LPN** **giữa hai vị trí trong cùng một kho** (bin hoặc khu vực chứa hàng → bin hoặc khu vực chứa hàng) mà **không thay đổi tổng tồn của kho**. Dùng để:

- **gom thùng** của cùng SKU / lô về ít vị trí hơn, giải phóng bin;
- **sửa vị trí sai** sau khi cất hàng;
- **dọn vị trí** sắp ngưng hoặc bảo trì;
- đưa thùng từ bin dự trữ ra bin cho phép lấy hàng, khi người vận hành thấy cần;
- **dời thùng từ khu tạm `OVERFLOW` lên bin** khi kho có chỗ trở lại ([05](../05-putaway/README.md) BR-13, [06 §4.6](../06-warehouse-map-slotting/README.md)).

> **Đã chốt 2026-11-04:** mọi tồn kho nằm trong **thùng LPN** do đơn vị sản xuất đóng sẵn, chỉ xuất nguyên thùng. Vì vậy di chuyển trong kho là **di chuyển nguyên thùng LPN bằng tay** — đưa vào phạm vi hiện tại; không mở thùng, không di chuyển một phần thùng.

Hiện tại đây là **một thao tác di chuyển thủ công**, ghi ngay khi xác nhận; không có nhiệm vụ di chuyển (move task) qua nhiều bước.

> **Đã chốt 2026-11-05:** **không có chuyển kho** giữa các kho. Mỗi đơn bán có đúng một kho xuất; hàng không được đưa từ kho này sang kho khác trong hệ thống. Dời / đóng kho (hiếm) xử lý bằng điều chỉnh tồn có duyệt ([20](../20-inventory-control/README.md)) — ✅ đã chốt 2026-11-05, xem [open-questions C41](../../open-questions/README.md).

> ⚠️ Giả định: **tự động đưa thùng ra vùng lấy hàng** (theo ngưỡng min / max của bin), **tối ưu lại vị trí** (re-slotting theo đề xuất, có duyệt) và **nhiệm vụ di chuyển** có phân công và vòng đời thuộc phạm vi giai đoạn sau. Xem [open-questions C2, C24](../../open-questions/README.md).

> ⚠️ Giả định: một lần xác nhận có thể gồm **một hoặc nhiều thùng** cùng chuyển tới **một vị trí đích**; mỗi thùng ghi một dòng sổ `MOVE`; một thùng không hợp lệ thì cả lần đó không thực hiện. Xem [open-questions C24](../../open-questions/README.md).

Các luồng khác cũng đổi vị trí của thùng nhưng thuộc module riêng: cất hàng ([05](../05-putaway/README.md)), lấy hàng về khu chờ xuất `STAGING` và cất lại hàng của đơn huỷ ([07](../07-picking/README.md)), cách ly hàng lỗi sang khu `QUARANTINE` ([20](../20-inventory-control/README.md)).

---

## 2. Actor liên quan

| Actor | Vai trò |
|-------|---------|
| **Nhân viên kho (Warehouse Staff)** | Quét thùng LPN và vị trí đích, di chuyển thùng giữa hai vị trí trong kho được gán |
| **Quản lý kho (Warehouse Manager)** | Có đủ quyền như nhân viên kho (làm thay khi cần); dọn chỗ, dời thùng từ `OVERFLOW` lên bin khi theo dõi Bảng sức chứa kho ([06](../06-warehouse-map-slotting/README.md)); xử lý chênh lệch phát hiện khi di chuyển bằng kiểm kê / điều chỉnh tồn ([20](../20-inventory-control/README.md)) |
| **Quản trị hệ thống (System Admin)** | Có quyền di chuyển theo cấu hình phân quyền mặc định |

- Cả **vị trí hiện tại của thùng và vị trí đích** phải thuộc kho người thao tác được gán (với role theo kho).

---

## 3. Input / Output

### Input

| Trường | Mô tả | Bắt buộc |
|--------|-------|----------|
| **Thùng LPN** | Một hoặc nhiều mã LPN quét được; mỗi thùng xác định SKU, lô, số lượng và **vị trí nguồn** (vị trí sổ sách của thùng) | Có |
| Vị trí đích | Mã vị trí (bin hoặc khu vực chứa hàng); khác vị trí nguồn, **cùng kho** | Có |
| Mã yêu cầu | Do màn hình sinh; gửi lại cùng mã không di chuyển lần hai | Có |

### Output

| Kết quả | Dùng bởi |
|---------|----------|
| **Vị trí của thùng** đổi sang vị trí đích; tồn theo vị trí: nguồn giảm, đích tăng; tổng tồn kho **không đổi**; trạng thái tồn và số trong thùng **giữ nguyên** | 06 Map, 07 Picking, báo cáo tồn |
| **Dòng sổ biến động `MOVE`** cho mỗi thùng (LPN, nguồn, đích, SKU, lô, số lượng, người, thời điểm) | [20 §4.7](../20-inventory-control/README.md) |

---

## 4. Luồng nghiệp vụ từng bước (Step-by-step)

### 4.1. Di chuyển nguyên thùng

1. NV kho / Quản lý kho **quét các thùng LPN** cần dời, rồi **quét vị trí đích**.
2. Hệ thống kiểm cho từng thùng:
   - thùng tồn tại và **đang chứa hàng** (`In Stock` — [20](../20-inventory-control/README.md));
   - vị trí đích **khác** vị trí hiện tại của thùng và **cùng kho**; khác kho → từ chối (không có chuyển kho — BR-01);
   - cả hai vị trí thuộc kho người thao tác được gán;
   - vị trí đích **dùng được** (trạng thái hiệu lực `Active` — [06](../06-warehouse-map-slotting/README.md)). Vị trí nguồn **không cần**: dọn thùng khỏi bin bị khoá / đang bảo trì chính là cách làm trống nó;
   - không vị trí nào đang **bị một phiếu kiểm kê giữ** cho SKU / lô của thùng ([20](../20-inventory-control/README.md) BR-10);
   - thùng **không đang bị giữ / phân bổ** cho đơn (kể cả thùng ở khu chờ xuất);
   - vị trí đích **không phải khu chờ xuất** (`STAGING`): chỉ thùng lấy cho đơn mới vào khu này, qua [07](../07-picking/README.md) (BR-09);
   - vị trí đích là **bin** thì **thùng vừa bin theo từng chiều** ([06](../06-warehouse-map-slotting/README.md) BR-01): D × R × C của thùng ≤ rộng × dài của bin × chiều cao lọt của tầng, được xoay ngang nếu thùng có cờ cho phép xoay (BR-10). Đích là khu chứa hàng thì không kiểm kích thước.

   Một thùng không hợp lệ thì **không thùng nào** trong lần đó được di chuyển; hệ thống báo lỗi theo từng thùng.
3. Hợp lệ → các thùng chuyển **ngay**, giữ nguyên SKU, lô, ngày nhận, số trong thùng và trạng thái; mỗi thùng ghi một dòng sổ `MOVE`.

> ⚠️ Giả định: ngoài việc **thùng vừa bin** (bắt buộc — BR-10), thao tác này **không kiểm** thể tích còn trống, tải trọng và cờ cho phép cất của vị trí đích (chỉ cất hàng ở [05](../05-putaway/README.md) kiểm). Người di chuyển tự chịu trách nhiệm chọn vị trí phù hợp. Xem [open-questions C24](../../open-questions/README.md).

### 4.2. Chênh lệch phát hiện khi di chuyển

4. Thùng không nằm ở vị trí sổ sách ghi (tìm thấy ở chỗ khác, hoặc không tìm thấy), hoặc số trong thùng khác sổ → không dùng thao tác này để sửa; báo Quản lý kho lập **phiếu kiểm kê** cho vị trí đó, chênh lệch đi qua **điều chỉnh tồn có duyệt** ([20 §4.2–4.3](../20-inventory-control/README.md)).

---

## 5. Trạng thái / Vòng đời (Lifecycle)

Di chuyển thủ công **không có vòng đời**: một lần xác nhận ghi các dòng sổ `MOVE` (mỗi thùng một dòng), không có trạng thái trung gian. Thùng giữ nguyên trạng thái `In Stock`; trạng thái của hàng trong thùng (`Available`, `Quarantine`…) đi theo thùng sang vị trí mới ([20 §5](../20-inventory-control/README.md)).

---

## 6. Ràng buộc nghiệp vụ (Business Rules) & Edge case

### Business rules

- **BR-01:** Vị trí đích phải **khác** vị trí hiện tại của thùng và **cùng một kho**. Hệ thống **không có chuyển kho** *(Đã chốt 2026-11-05)*: khác kho → từ chối.
- **BR-02:** Tổng tồn của kho **không đổi**; chỉ vị trí của thùng thay đổi. SKU, lô, ngày nhận, số trong thùng và trạng thái giữ nguyên.
- **BR-03:** Vị trí đích phải **dùng được** (trạng thái hiệu lực `Active`). Vị trí nguồn thì không cần.
- **BR-04:** **Chỉ di chuyển nguyên thùng** *(Đã chốt 2026-11-04)*: xác định thùng bằng **quét mã LPN**; không mở thùng, không tách hay di chuyển một phần thùng. Chỉ thùng đang chứa hàng (`In Stock`) được di chuyển.
- **BR-05:** Thùng **đang bị giữ / phân bổ** cho đơn (kể cả ở khu chờ xuất) **không** di chuyển bằng thao tác này — tránh người lấy hàng tới vị trí đã trống.
- **BR-06:** Không di chuyển ra / vào một vị trí đang bị **phiếu kiểm kê** giữ cho SKU / lô của thùng — kiểm kê đếm trên sổ sách lúc đếm, di chuyển giữa chừng sẽ làm sai chênh lệch.
- **BR-07:** Mọi di chuyển để lại dòng sổ `MOVE` cho từng thùng (LPN, nguồn, đích, SKU, lô, số lượng, người, thời điểm). Gửi lại cùng mã yêu cầu không di chuyển lần hai. Một lần xác nhận thành công hoặc thất bại **trọn vẹn** cho mọi thùng.
- **BR-08:** Người thao tác chỉ di chuyển trong kho mình được gán (với role theo kho).
- **BR-09:** Không di chuyển thủ công **vào khu chờ xuất** (`STAGING`). Tồn ở khu này luôn gắn với một đơn ([20](../20-inventory-control/README.md) BR-20); thùng vào bằng lấy hàng và ra bằng chuyển sang khu xuất hàng (`DISPATCH`) hoặc cất lại ([07](../07-picking/README.md)).
- **BR-10:** **Thùng phải vừa bin** *(Đã chốt 2026-11-05)*: mọi lần dời thùng vào **bin** — kể cả dời từ khu `OVERFLOW` lên bin và sửa lệch vị trí phát hiện khi kiểm kê ([20](../20-inventory-control/README.md)) — đều kiểm **thùng vừa bin theo từng chiều** ([06](../06-warehouse-map-slotting/README.md) BR-01). Một thùng không vừa thì cả lần di chuyển bị từ chối. Khi sửa lệch vị trí mà thùng **đang thực tế nằm** trong bin lại bị báo không vừa, dữ liệu sai (kích thước bin, chiều cao lọt hoặc quy cách thùng): Quản lý kho sửa dữ liệu rồi ghi nhận lại.

### Edge case

| Tình huống | Hướng xử lý |
|------------|-------------|
| Thùng không vừa bin đích (kể cả khi xoay ngang được) | Từ chối cả lần di chuyển (BR-10); chọn bin lớn hơn hoặc để thùng ở khu `OVERFLOW` |
| Sửa lệch vị trí khi kiểm kê nhưng thùng thực tế đang nằm trong bin lại bị báo không vừa | Dữ liệu sai — Quản lý kho sửa kích thước bin / chiều cao lọt / quy cách thùng ([06](../06-warehouse-map-slotting/README.md), [01](../01-product-creation/README.md)) rồi ghi nhận lại (BR-10) |
| Thùng đang giữ cho đơn | Bị từ chối; muốn dời thì nhả / phân bổ lại trước |
| Vị trí đích thuộc kho khác | Bị từ chối (BR-01); không có chuyển kho. Dời / đóng kho xử lý bằng điều chỉnh tồn có duyệt ([20](../20-inventory-control/README.md), ✅ đã chốt 2026-11-05) |
| Kho có chỗ trở lại sau khi đã cất thùng vào `OVERFLOW` | Quét thùng ở `OVERFLOW`, quét bin đích để dời lên bin; người di chuyển tự chọn bin phù hợp (⚠️ giả định §4.1) |
| Vị trí đích đang `Blocked` / `Maintenance` / `Inactive` | Bị từ chối |
| Thùng đang ở vị trí `Blocked` / `Maintenance` | Được di chuyển ra (dọn bin) |
| Vị trí đang có phiếu kiểm kê mở cho SKU / lô của thùng | Bị từ chối tới khi phiếu xong và chênh lệch có quyết định |
| Quét mã LPN không tồn tại, thùng đã huỷ, đã xuất hết hoặc đang thất lạc | Bị từ chối |
| Quét nhiều thùng, một thùng không hợp lệ | Không thùng nào được di chuyển; bỏ thùng lỗi rồi xác nhận lại |
| Muốn lấy ra một phần hàng trong thùng | Không hỗ trợ (BR-04); chỉ dời nguyên thùng |
| Thùng thực tế không nằm ở vị trí sổ sách | Không di chuyển; kiểm kê + điều chỉnh có duyệt ([20](../20-inventory-control/README.md)) |
| Di chuyển nhầm thùng / vị trí | Di chuyển thùng ngược lại; không sửa tồn trực tiếp |
| Chọn vị trí đích là khu chờ xuất `STAGING` | Bị từ chối (BR-09); muốn giữ hàng cho đơn thì dùng lấy hàng với đích `STAGING` ([07](../07-picking/README.md)) |

---

## 7. Liên kết với các module khác

| Module | Quan hệ |
|--------|---------|
| [06 · Warehouse Map & Slotting](../06-warehouse-map-slotting/README.md) | Vị trí, trạng thái hiệu lực; khu `OVERFLOW` và Bảng sức chứa kho; re-slotting là phạm vi sau |
| [07 · Picking](../07-picking/README.md) | Thùng đã phân bổ và thùng ở khu chờ xuất không bị dời; tự động đưa thùng ra vùng lấy hàng là phạm vi sau |
| [05 · Putaway](../05-putaway/README.md) | Cất nguyên thùng có kiểm ràng buộc cứng; di chuyển nguyên thùng dùng để sửa vị trí sau khi cất và dời thùng từ khu `OVERFLOW` lên bin |
| [20 · Inventory Control](../20-inventory-control/README.md) | Thùng LPN và vòng đời thùng; dòng sổ `MOVE`; phiếu kiểm kê chặn di chuyển; chênh lệch xử lý bằng kiểm kê / điều chỉnh có duyệt |
| [00 · System Overview](../../00-system-overview/README.md) | Luồng di chuyển hàng trong kho |
