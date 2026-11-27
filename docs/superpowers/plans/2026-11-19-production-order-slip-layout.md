# Production order slip layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redraw the production order slip PDF as the approved v7 draft: a production paper with key facts, a goods
table and requirements - no money, no signatures, nothing unrelated to making and receiving the goods, no "demo" wording.

**Architecture:** A new block-based layout, `common/label/FormDocument`, sits next to the line-based `PrintedDocument`
(same package, same fonts and barcode drawing; the four other documents keep `PrintedDocument` and do not change).
`ProductionOrderSlipDocument` builds its blocks from slimmer `Facts`; `ProductionOrderDispatch` fills them, adding our
company's name (`SellerProperties`) and the coordinator's e-mail. Demo data and dev/test config lose the word "demo"
where it reaches the slip.

**Tech Stack:** Java 21, Spring Boot 3.5, PDFBox 3 (`PDPageContentStream`), ZXing (tests), JUnit 5 + AssertJ,
Flyway (demo migration), PostgreSQL 16 via Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-19-production-order-slip-layout-design.md`; the approved draft is
`docs/superpowers/specs/2026-11-19-production-order-slip-sample.pdf` (v7) - match it.

## Global Constraints

- A4 portrait, Noto Sans from `LabelFonts` (regular + bold), every string passed through `LabelFonts.drawable`.
- Vietnamese copy comes from `i18n/messages_vi.properties` (bundle `i18n.messages`, locale `vi`); every new key also goes
  into `messages.properties` in English (`MessageBundleTest` keeps them in step); keys no longer used leave both.
- Numbers are grouped with dots: `1.000`, `40.000`. Dates `dd/MM/yyyy`, time `HH:mm dd/MM/yyyy`, zone
  `Asia/Ho_Chi_Minh`.
- The slip prints **no** unit price, amount, currency, sales order, customer, delivery batch window, approver, totals row
  or signature caption.
- The SHA-256 is printed **whole** (wrapped), never shortened.
- The code (e.g. `PRD-20261110-0007`) is drawn as a QR (masthead, right) and a Code 128 barcode (under the title) on the
  first page only, with `LabelDrawing.qr` / `LabelDrawing.barcode`.
- Any part whose value is missing (company name, warehouse address, producer contact, coordinator e-mail, note,
  packing, carton size) is left out or uses its "not recorded" wording - never prints `null`, `-` or an empty label.
- Javadoc in English, explaining why; identifiers in English (CLAUDE.md §3).
- Never edit a merged migration or demo migration; the new demo migration is versioned above every existing one
  (newest today: `V20261203000300`).
- **Ask the repository owner before every `git commit` and before any push** (CLAUDE.md §7). Each task's last step is a
  checkpoint, not an automatic commit.

## Review Focus

1. A goods row taller than a whole page (a packing requirement of hundreds of words): it must be cut between its lines
   and continue on the next page, never loop or overflow the bottom margin. -> Task 1, `oversizedRowIsCutBetweenLines`.
2. Long text inside fixed boxes (a 200-character warehouse address, producer name or note): it wraps inside its box and
   the box grows. -> Task 1, `longTextWrapsInsideItsBox`.
3. Many goods lines: every page repeats the table header, numbers itself "Trang x / N" with the right N, and only the
   first page carries the QR and barcode. -> Task 1, `tableBreaksAcrossPagesRepeatingItsHeader`; Task 2, `manyLines`.
4. Optional data absent (no company name, no address, no producer contact, no coordinator e-mail, no note, no packing,
   no carton size): no `null`, no dangling separator. -> Task 2, `missingPartsAreLeftOut`.
5. A slip still drawn for the real path (approval -> stored PDF) after the dependency changes. -> Task 2,
   `ProductionOrderDispatchIntegrationTest` (approved, so sent).

---

### Task 1: `FormDocument` - block layout in `common/label`

**Files:**
- Create: `src/main/java/com/stockflow/common/label/FormDocument.java`
- Test: `src/test/java/com/stockflow/common/label/FormDocumentTest.java`
- Modify: `src/main/java/com/stockflow/common/label/PrintedDocument.java` (class javadoc only: name `FormDocument` as the
  block layout beside it, for documents that need boxes and tables)

**Interfaces:**
- Produces (all `public`, in `com.stockflow.common.label`):

```java
public final class FormDocument {
    public sealed interface Block permits Masthead, Title, KeyFacts, Box, Table, Bullets, Note {}
    /** Our company's name at the left, vertically centred on the code's QR at the right. */
    public record Masthead(String company) implements Block {}
    /** Centred title, a centred line under it (number and date), then the code's barcode, centred. */
    public record Title(String title, String line) implements Block {}
    /** A row of boxes: grey heading, big bold value centred, optional detail (wrapped, smaller) under it. */
    public record Fact(String label, String value, String detail, float share) {}   // share: fraction of the width
    public record KeyFacts(List<Fact> facts) implements Block {}
    /** A full-width box: grey heading row, first line bold, the rest regular. */
    public record Box(String heading, String firstLine, List<String> lines) implements Block {}
    public record Column(String heading, float width, boolean numeric) {}            // width in points
    /** One cell: its text (bold when {@code bold}) and smaller grey lines under it. */
    public record Cell(String text, boolean bold, List<String> details) {}
    public record Table(String heading, List<Column> columns, List<List<Cell>> rows) implements Block {}
    public record Bullets(String heading, List<String> items) implements Block {}
    /** A bold lead and its text on one line, then an optional grey line. */
    public record Note(String lead, String text, String grey) implements Block {}

    /**
     * @param code      drawn as the masthead's QR and the title's barcode on page 1, or null for neither
     * @param footer    left of every page's footer, e.g. "Phiếu PRD-…"
     * @param pageLabel right of every page's footer, a format with two %d: page and page count, e.g. "Trang %d / %d"
     */
    public static byte[] draw(LabelFonts fonts, List<Block> blocks, String title, String code, String footer,
                              String pageLabel) { … }
}
```

Layout values (from the v7 draft): margin 40 pt (content width 515 pt), heading rows grey `#EDEDED`, detail text
`#595959`, borders 0.6 pt; masthead QR 22 mm; title 15 pt bold, its line 10 pt bold, barcode 62 x 9 mm; key-fact value
13 pt bold (11 pt when it has a detail), detail 8.5 pt; table text 8.5 pt, header 8.5 pt bold centred, numeric columns
right-aligned; section headings 10 pt bold; footer 7.5 pt grey with a 0.4 pt rule. Wrapping measures text with the font
(as `PrintedDocument.wrap` does), cutting at spaces, and inside a word only when the word alone is wider than its box.
A block that does not fit in the space left starts a new page; a table continues on the next page with its column header
repeated; a row taller than a fresh page is cut between its lines. Page numbers are drawn after all pages exist.

- [ ] **Step 1: Write the failing tests** in `FormDocumentTest` (render, then read back with `PDFTextStripper`;
  decode codes with ZXing `MultiFormatReader` on a 200 dpi render as `ProductionOrderSlipDocumentTest.codeIsScannable`
  does):
  - `drawsEveryBlock`: one of each block -> text contains the masthead company, title, title line, every fact
    label/value/detail, box heading and lines, column headings and cell texts and details, bullet items, note lead,
    text and grey line, `"Trang 1 / 1"`; page count 1.
  - `tableBreaksAcrossPagesRepeatingItsHeader`: 80 rows -> `pages >= 2`; on every page the text contains the first
    column heading; page 1 contains `"Trang 1 / " + pages`, the last page `"Trang " + pages + " / " + pages`.
  - `oversizedRowIsCutBetweenLines`: one row whose cell has 300 detail lines `"d001"`…`"d300"` -> renders, `pages >= 2`,
    text contains `"d001"` and `"d300"`.
  - `longTextWrapsInsideItsBox`: a fact detail and a box line of 60 words each -> every word appears in the text; the
    page count stays 1.
  - `codeOnlyOnPageOne`: with 80 rows and code `"PRD-20261110-0007"` -> page 1 decodes to the code; page 2 throws
    `NotFoundException`.
  - `noCode`: code null -> renders with no exception; the masthead company is still there.
- [ ] **Step 2: Run** `mvn -q test -Dtest=FormDocumentTest` - expected: compilation failure (`FormDocument` missing).
- [ ] **Step 3: Implement `FormDocument`** with the interface and layout values above.
- [ ] **Step 4: Run** `mvn -q test -Dtest=FormDocumentTest` - expected: 6 tests pass.
- [ ] **Step 5: Checkpoint** - `git add` the two files and the javadoc change; ask the owner, then
  `git commit -m "feat(common): a block layout for printed forms - boxes, a paging table, page numbers"`.

### Task 2: The slip drawn as a production paper

**Files:**
- Modify: `src/main/java/com/stockflow/order/internal/service/ProductionOrderSlipDocument.java`
- Modify: `src/main/resources/i18n/messages_vi.properties`, `src/main/resources/i18n/messages.properties`
  (`production-order.pdf.*`)
- Test: `src/test/java/com/stockflow/order/internal/service/ProductionOrderSlipDocumentTest.java` (rewrite)

**Interfaces:**
- Consumes: Task 1's `FormDocument` and its blocks.
- Produces (package-private, used by Task 3):

```java
record Facts(String code, Instant approvedAt, String companyName, String producerCode, String producerName,
             String producerContact, String warehouseName, String warehouseAddress, LocalDate needByDate,
             boolean sampleApprovalRequired, String note, String drawnUpByName, String drawnUpByEmail,
             List<SlipLine> lines) {}
record SlipLine(String sku, String name, int cartons, int unitsPerCarton, int totalUnits, String cartonSize,
                String packingRequirements, UUID designSnapshotId, String printFileChecksum) {}   // unchanged
byte[] render(Facts facts)
```

Blocks, in order (Vietnamese copy as in the v7 draft; keys replace today's `production-order.pdf.*`):

| Block | Content |
|---|---|
| `Masthead` | `companyName` (null/blank -> `null`, the masthead draws the QR only) |
| `Title` | `PHIẾU ĐẶT HÀNG SẢN XUẤT`; `Số: <code>     Ngày: <approvedAt dd/MM/yyyy>` |
| `KeyFacts` | `CẦN HÀNG TRƯỚC NGÀY` / needBy (0.22) · `GIAO VỀ` / warehouseName, detail warehouseAddress (0.56) · `DUYỆT MẪU IN TRƯỚC` / `CÓ` or `KHÔNG` (0.22) |
| `Box` | `ĐƠN VỊ SẢN XUẤT`; `<producerName> — mã <producerCode>`; `Liên hệ: <producerContact>` when present |
| `Table` | `I. HÀNG CẦN SẢN XUẤT`; columns `STT` 26, `Mã hàng` 86, `Tên hàng / quy cách` 233, `Số thùng` 54 num, `Cái / thùng` 56 num, `Tổng số cái` 60 num; name cell bold with details `Thùng <cartonSize>` or `Kích thước thùng: chưa khai báo`, `Đóng gói: <packing>` when present, `File in: <designSnapshotId> · SHA-256 <checksum>`; **no totals row** |
| `Bullets` | `II. YÊU CẦU SẢN XUẤT VÀ GIAO HÀNG`: `Giao hàng tại: <wh> — <address>, trước ngày <d>.` (without ` — <address>` when absent) · `Duyệt mẫu in: CÓ — in mẫu thử theo file in, chờ duyệt mẫu rồi mới in hàng loạt.` or `Duyệt mẫu in: KHÔNG — in hàng loạt sau khi xác nhận nhận đơn.` · `Chỉ in đúng bộ file in có mã kiểm tra (SHA-256) ghi ở từng dòng hàng.` · `Mỗi thùng đúng số cái ghi ở cột "Cái / thùng"; thùng sai số cái sẽ bị trả lại.` · `Ghi chú: <note>` when present · `Ghi số phiếu <code> trên phiếu giao hàng — đây là mã lô khi kho nhận hàng.` |
| `Note` | lead `Liên hệ khi cần hỏi về phiếu:`; text `<drawnUpByName> (Điều phối sản xuất)` + ` · <email>` when present; grey `Phiếu được lập và duyệt trên hệ thống StockFlow lúc <HH:mm dd/MM/yyyy>; không cần chữ ký.` |
| footer / page | `Phiếu <code>` / `Trang %d / %d`; PDF title `PHIẾU ĐẶT HÀNG SẢN XUẤT <code>` |

Rewrite the class javadoc: what the slip is for (producing and receiving), and why it carries no money, no signatures
and no sales data (spec, Non-goals).

- [ ] **Step 1: Rewrite the tests** (keep the `facts(...)`/`line(...)` helpers' style, new `Facts` shape; company
  `CÔNG TY TNHH STOCKFLOW`, coordinator `Nguyễn Văn Điều Phối` / `dieuphoi@stockflow.vn`; `text(pdf)` as today, plus
  `flat(pdf)` = text with all whitespace removed):
  - `everyField`: contains `PHIẾU ĐẶT HÀNG SẢN XUẤT`, `PRD-20261110-0007`, `Ngày: 10/11/2026`, `CÔNG TY TNHH STOCKFLOW`,
    `CẦN HÀNG TRƯỚC NGÀY`, `30/11/2026`, `GIAO VỀ`, `Kho Hồ Chí Minh`, `12 Nguyễn Huệ, TP HCM`, `DUYỆT MẪU IN TRƯỚC`,
    `CÓ`, `ĐƠN VỊ SẢN XUẤT`, `Xưởng in Bình Dương — mã XUONG-IN`, `Liên hệ: Chị Lan - 0909123456 - don@xuong.vn`,
    `CUP-CAFE-001`, `Ly giấy in logo`, `Thùng 600 x 400 x 350 mm`, `Kích thước thùng: chưa khai báo`,
    `Đóng gói: Dán nhãn mã đơn`, `Ghi chú: Giao trước 10 giờ`, `Nguyễn Văn Điều Phối (Điều phối sản xuất)`,
    `dieuphoi@stockflow.vn`, `không cần chữ ký`, `Trang 1 / 1`; `flat(pdf)` contains the whole 64-character `CHECKSUM`
    and `DESIGN.toString()`; quantities `12`, `50`, `600`.
  - `leavesOutWhatIsNotProduction`: text does not contain `Đơn giá`, `Thành tiền`, `VND`, `Cộng`, `Tổng cộng`,
    `Khách hàng`, `Đơn bán`, `Đợt giao`, `Người duyệt`, `Ký`.
  - `missingPartsAreLeftOut`: blank company, null address, null producer contact, null e-mail, null note, sample false,
    line without size/packing -> contains `KHÔNG`, `Giao hàng tại: Kho HCM, trước ngày 01/12/2026.`,
    `Kích thước thùng: chưa khai báo`, `A (Điều phối sản xuất)`; does not contain `null`, `Liên hệ: `, `Ghi chú`,
    `Đóng gói`, ` · ` directly after `(Điều phối sản xuất)`.
  - `codeIsScannable`: unchanged assertion (decodes to `PRD-20261110-0007`).
  - `manyLines`: 40 lines -> `pages > 1`, contains `CUP-CAFE-039`, `"Trang " + pages + " / " + pages`, the column
    heading `Tên hàng / quy cách` occurs `pages` times when the table spans them all.
#### Filling the slip in `ProductionOrderDispatch` (same task: the new `Facts` and its only caller compile together)

**Also modify:** `src/main/java/com/stockflow/order/internal/service/ProductionOrderDispatch.java` (`slipFacts`,
constructor) and test `src/test/java/com/stockflow/order/ProductionOrderDispatchIntegrationTest.java` (the "approved, so
sent" test).

- Consumes: the new `Facts`; `com.stockflow.common.config.SellerProperties#name()`;
  `IdentityService#user(UUID)` -> `StaffUser#fullName()`, `#username()`, `#email()` (no phone on accounts: the slip
  prints name and e-mail only); `Producer` contact via the existing `contact(producer)`;
  `WarehouseView#name()`, `#address()`.

Changes: `slipFacts` builds the new `Facts` - `companyName` = `seller.name()` (blank -> null), coordinator =
`person(production.drawnUpBy())` and that account's e-mail (blank -> null); no approver, sales order, customer, batch
window, price or currency. Drop the constructor parameters and fields that only the slip used: `OrderRepository orders`
and `CustomerService customers`. **Keep** the `contracts.findLiveByOrderId(...)` delivery-batch lookup as a guard (it
refuses approval with `PRODUCTION_ORDER_NOT_ALLOWED` when the batch is gone - behaviour this change must not lose); its
result is no longer printed, so keep only the existence check with a comment saying why. Inject `SellerProperties`.

- [ ] **Step 2: Update the dispatch test**: in `ProductionOrderDispatchIntegrationTest` "approved, so sent", the slip
  text contains `PHIẾU ĐẶT HÀNG SẢN XUẤT` (was `PHIẾU ĐƠN SẢN XUẤT`), the code, the SKU, `CẦN HÀNG TRƯỚC NGÀY`, and
  `seller.name()` from an `@Autowired SellerProperties seller`; and does not contain `Đơn giá`.
- [ ] **Step 3: Run** `mvn -q test -Dtest='ProductionOrderSlipDocumentTest,ProductionOrderDispatchIntegrationTest'` -
  expected: compilation failure (new `Facts` not there yet).
- [ ] **Step 4: Implement** the new `Facts` and `render` with the blocks above; replace the `production-order.pdf.*`
  keys in both bundles (English copy is the builder's, same meaning); change `slipFacts` and the constructor as above.
- [ ] **Step 5: Run** `PYTHONUTF8=1 python3 -X utf8 tools/verify.py` (all static checks pass) and
  `mvn -q test -Dtest='ProductionOrderSlipDocumentTest,ProductionOrderDispatchIntegrationTest,ProductionOrderIntegrationTest,ProductionOrderFileLinksSecurityIntegrationTest,ModularityTest,ArchitectureTest,MessageBundleTest'`
  - expected: all pass.
- [ ] **Step 6: Checkpoint** - ask the owner, then
  `git commit -m "feat(order): the production order slip as a production paper - key facts, goods, requirements"`.

### Task 3: No "demo" wording where it reaches the slip

**Files:**
- Modify: `src/main/resources/application-local.yml`, `application-dev.yml`, `application-test.yml` -
  `stockflow.seller.name` default `${SELLER_NAME:DEMO STOCKFLOW CO LTD}` -> `${SELLER_NAME:CÔNG TY TNHH STOCKFLOW}`
  (only `seller.name`; the bank `account-name` and the other seller fields are out of scope)
- Create: `src/main/resources/db/demo/V20261205000100__demo_producer_plain_names.sql`
- Modify: `src/test/java/com/stockflow/order/SalesContractIntegrationTest.java:169`,
  `src/test/java/com/stockflow/invoicing/internal/service/EInvoiceIntegrationTest.java:309` - expect the new name
  (unit tests that build their own seller party, `QuoteDocumentTest`, `SalesContractDocumentTest`, stay as they are)

The demo migration updates `ordering.producer` by id (`pg_temp.hid`-style ids are not available outside their file:
match on the current `code`), and bumps `version`:

| code now | new code | new name | new email |
|---|---|---|---|
| `DEMO-INDN` | `INDN` | `Công ty TNHH In Bao Bì Đồng Nai` | `sanxuat@inbaobidongnai.vn` |
| `DEMO-INLX` | `INLX` | `Xưởng In Ly Giấy Long Xuyên` | `donhang@inlylongxuyen.vn` |

Header comment in the file: why (capstone - printed papers must not say "demo"; owner, 2026-11-19), and that the other
"(demo)" names of the demo data are left for a later change.

- [ ] **Step 1: Update the two integration assertions** to `"CÔNG TY TNHH STOCKFLOW"`.
- [ ] **Step 2: Run** `mvn -q test -Dtest='SalesContractIntegrationTest,EInvoiceIntegrationTest'` - expected: FAIL
  (config still says DEMO).
- [ ] **Step 3: Change the three configs and add the demo migration.**
- [ ] **Step 4: Run** `PYTHONUTF8=1 python3 -X utf8 tools/verify.py` (no Flyway collision) and
  `mvn -q test -Dtest='SalesContractIntegrationTest,EInvoiceIntegrationTest,ProductionOrderDispatchIntegrationTest,RepositorySqlQaTest'`
  - expected: all pass. The test profile loads `db/demo`, so every Testcontainers database runs the new migration;
  add to `ProductionOrderDispatchIntegrationTest` (or the nearest test that reads producers) an assertion that no row
  of `ordering.producer` has a `code` starting `DEMO-` or a `name` containing `(demo)`.
- [ ] **Step 5: Checkpoint** - ask the owner, then
  `git commit -m "chore(demo): no demo wording on the company name and the demo producers"`.

### Task 4: Whole-branch verification and the sample

**Files:**
- Modify: `docs/superpowers/specs/2026-11-19-production-order-slip-sample.pdf` / `.png` (replace with a slip drawn by
  the real code)

- [ ] **Step 1: Run** the full suite in the background: `mvn test` - expected: `BUILD SUCCESS`, 0 failures, 0 errors
  (skipped count unchanged from develop's 61).
- [ ] **Step 2: Draw a sample with the real renderer** (a throwaway test outside the repo, as done for v1, with the v7
  draft's data) and compare it with the v7 draft side by side; any visible difference other than the full checksum and
  `mm` carton sizes is a defect of Task 1/2.
- [ ] **Step 3: Replace** the sample PDF/PNG next to the spec with the real one.
- [ ] **Step 4: Checkpoint** - ask the owner, then commit the spec, the plan and the sample:
  `git commit -m "docs(order): the production order slip's design, plan and sample"`; push and open the PR into
  `develop` only when the owner says so.
