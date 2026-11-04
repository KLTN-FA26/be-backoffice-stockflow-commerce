package com.stockflow.assistant.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Each document is cut along its own structure: a rule row, a bold glossary term, a state-machine section. */
class KnowledgeParserTest {

    @Test
    @DisplayName("a rule is its table row: the id as anchor, the group as title, the rule text as body")
    void rules() {
        List<KnowledgeChunk> chunks = KnowledgeParser.rules("05-business-rules", """
                # Business Rules Catalog

                ## Returns — `order-service`

                | Id | Rule | Enforced in | WBS |
                |---|---|---|---|
                | BR-RMA-002 | Custom-printed items are not returnable unless defective. | `Rma.approve()` | 3.17.5.2 |
                | BR-RMA-003 | A return must be requested within 7 days. | `Rma.request()` | 3.17.5.3 |
                """);

        assertThat(chunks).extracting(KnowledgeChunk::anchor).containsExactly("BR-RMA-002", "BR-RMA-003");
        KnowledgeChunk first = chunks.getFirst();
        assertThat(first.title()).isEqualTo("BR-RMA-002 — Returns");
        assertThat(first.body()).isEqualTo("Custom-printed items are not returnable unless defective.");
        assertThat(first.body()).as("the enforcement point is noise to staff").doesNotContain("Rma.approve");
        assertThat(first.kind()).isEqualTo(KnowledgeChunk.Kind.RULE);
    }

    @Test
    @DisplayName("a glossary term is a row with a bold first cell, with what not to confuse it with")
    void terms() {
        List<KnowledgeChunk> chunks = KnowledgeParser.terms("01-ubiquitous-language", """
                | Term | Definition | Not to be confused with |
                |---|---|---|
                | **On hand** | Physically present at a location. | Available |
                | **Reserved** | A soft, time-limited hold. | Allocated |
                | Dimension | Values | Question |
                """);

        assertThat(chunks).extracting(KnowledgeChunk::anchor).containsExactly("On hand", "Reserved");
        assertThat(chunks.getFirst().body()).isEqualTo("Physically present at a location. Not to be confused with: "
                + "Available.");
    }

    @Test
    @DisplayName("a state machine is a second-level section, cut again at its third-level headings")
    void sections() {
        List<KnowledgeChunk> chunks = KnowledgeParser.sections("03-state-machines", """
                # State Machines

                Intro that belongs to no section.

                ## 9. Order — `order-service`

                DRAFT → CONFIRMED → ...

                ### Who may cancel, and until when

                Only before packing.

                ## 10. Payment — `payment-service`

                PENDING → PAID
                """);

        assertThat(chunks).extracting(KnowledgeChunk::anchor).containsExactly("9. Order — order-service",
                "9. Order — order-service › Who may cancel, and until when", "10. Payment — payment-service");
        assertThat(chunks.get(1).body()).isEqualTo("Only before packing.");
    }

    @Test
    @DisplayName("a long section becomes passages that fit, and a table cut in two repeats its header")
    void longSections() {
        StringBuilder table = new StringBuilder("| From | To | Who |\n|---|---|---|\n");
        for (int i = 0; i < 60; i++) {
            table.append("| STATUS_").append(i).append(" | NEXT_").append(i).append(" | Sales or ops lead |\n");
        }
        List<KnowledgeChunk> chunks = KnowledgeParser.sections("03-state-machines",
                "## 9. Order — `order-service`\n\nIntro paragraph.\n\n" + table);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.body().length()).isLessThanOrEqualTo(KnowledgeParser.MAX_PASSAGE));
        assertThat(chunks).extracting(KnowledgeChunk::anchor)
                .startsWith("9. Order — order-service", "9. Order — order-service (2)").doesNotHaveDuplicates();
        assertThat(chunks.subList(1, chunks.size())).allSatisfy(chunk ->
                assertThat(chunk.body()).startsWith("| From | To | Who |\n|---|---|---|"));
        assertThat(String.join("\n", chunks.stream().map(KnowledgeChunk::body).toList()))
                .contains("STATUS_0 ", "STATUS_59 ");
    }

    @Test
    @DisplayName("a flow is its coded section: the code as anchor, code, name and group as title, without its diagram, "
            + "its work-breakdown line or the columns naming tables and events")
    void flows() {
        String markdown = """
                # Bản đồ toàn bộ luồng dữ liệu

                ## Mục lục

                - F-PO

                # Nhập hàng

                ## `F-PO` Đơn mua hàng gửi nhà cung cấp

                *WBS 3.2*

                Từ nháp tới lúc nhà cung cấp xác nhận.

                ```mermaid
                flowchart LR
                    N0(["NV mua hàng"])
                ```

                | # | Service | Ghi vào | Kết quả |
                |---|---|---|---|
                | 01 | `procurement` | `+ purchase_order` | status = DRAFT |
                | 05 | `procurement` | `+ qc_result` | → QC_PASSED | QC_REJECTED |

                > · Hạn mức người duyệt phải ≥ giá trị PO — BR-PO-002.

                ---

                # Nền tảng

                ## `P-PERM` Các service tự đăng ký quyền

                Mỗi service gửi catalog quyền lên Kafka.

                # Quy ước lưu trữ

                Chỉ ghi thêm.
                """;

        List<KnowledgeChunk> chunks = KnowledgeParser.flows("04c-all-flows", markdown);

        assertThat(chunks).singleElement().satisfies(flow -> {
            assertThat(flow.anchor()).isEqualTo("F-PO");
            assertThat(flow.kind()).isEqualTo(KnowledgeChunk.Kind.FLOW);
            assertThat(flow.title()).isEqualTo("F-PO — Đơn mua hàng gửi nhà cung cấp (Nhập hàng)");
            assertThat(flow.body()).contains("Từ nháp tới lúc nhà cung cấp xác nhận.", "| # | Service | Kết quả |",
                    "| 01 | `procurement` | status = DRAFT |", "| 05 | `procurement` | → QC_PASSED | QC_REJECTED |",
                    "BR-PO-002").doesNotContain("mermaid", "N0(", "WBS", "purchase_order", "qc_result", "---\n");
        });
    }

    @Test
    @DisplayName("a note on what was built is left out to its end; a step that only wrote for the system keeps what it "
            + "wrote; the system's bookkeeping leaves every kept cell; a long flow is cut repeating its table's header")
    void buildNotesBookkeepingAndLongFlows() {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            rows.append("| ").append(i).append(" | `warehouse` | `+ putaway_task` | STATUS_").append(i).append(" |\n");
        }
        String markdown = """
                # Bản đồ toàn bộ luồng dữ liệu

                # Ngoại lệ

                ## `X-SHORT` Nhặt thiếu — đơn không hỏng, đơn dừng lại

                | # | Service | Ghi vào | Kết quả |
                |---|---|---|---|
                | 01 | `fulfillment` | `~ pick_line.status = SHORT, + outbox_event` | + outbox_event |
                | 02 | `order` | `~ order.status` | → ON_HOLD(INVENTORY_ISSUE) |

                > · Đã cài (SCRUM-152): `POST /fulfillment/tasks/{id}/pick-lines/{lineId}/short`,
                > lỗi `PICK_SHORT_CONFLICT`. **Giới hạn:** người đếm vẫn tra được số sổ sách ở màn khác.
                > ⚠ Đơn KHÔNG tự huỷ.

                ## `F-PUT` Slotting và putaway

                | # | Service | Ghi vào | Kết quả |
                |---|---|---|---|
                """ + rows;

        List<KnowledgeChunk> chunks = KnowledgeParser.flows("04c-all-flows", markdown);

        KnowledgeChunk shortPick = chunks.getFirst();
        assertThat(shortPick.anchor()).isEqualTo("X-SHORT");
        assertThat(shortPick.body()).contains("| 01 | `fulfillment` | `~ pick_line.status = SHORT` |",
                "| 02 | `order` | → ON_HOLD(INVENTORY_ISSUE) |", "> ⚠ Đơn KHÔNG tự huỷ.")
                .doesNotContain("outbox_event", "Đã cài", "PICK_SHORT_CONFLICT", "sổ sách");
        List<KnowledgeChunk> putaway = chunks.subList(1, chunks.size());
        assertThat(putaway).hasSizeGreaterThan(1).allSatisfy(chunk -> {
            assertThat(chunk.body()).startsWith("| # | Service | Kết quả |\n| --- | --- | --- |")
                    .doesNotContain("putaway_task");
            assertThat(chunk.body().length()).isLessThanOrEqualTo(KnowledgeParser.MAX_PASSAGE);
        });
        assertThat(putaway.getFirst().title()).startsWith("F-PUT — Slotting và putaway (Ngoại lệ) (1/");
    }

    @Test
    @DisplayName("a title longer than its column is cut, not a reason for the document's sync to fail")
    void longTitles() {
        KnowledgeChunk chunk = new KnowledgeChunk("04c-all-flows", "F-X", KnowledgeChunk.Kind.FLOW, "t".repeat(400),
                "body");

        assertThat(chunk.title()).hasSize(KnowledgeChunk.MAX_TITLE).endsWith("…");
    }

    @Test
    @DisplayName("the real documents parse into many chunks, every rule id once")
    void realDocuments() throws IOException {
        Path docs = Path.of("docs", "business-design");
        List<KnowledgeChunk> rules = KnowledgeParser.rules("05-business-rules",
                Files.readString(docs.resolve("05-business-rules.md"), StandardCharsets.UTF_8));
        List<KnowledgeChunk> terms = KnowledgeParser.terms("01-ubiquitous-language",
                Files.readString(docs.resolve("01-ubiquitous-language.md"), StandardCharsets.UTF_8));
        List<KnowledgeChunk> machines = KnowledgeParser.sections("03-state-machines",
                Files.readString(docs.resolve("03-state-machines.md"), StandardCharsets.UTF_8));

        assertThat(rules).hasSizeGreaterThan(50);
        assertThat(rules).extracting(KnowledgeChunk::anchor).doesNotHaveDuplicates().contains("BR-RMA-002");
        assertThat(terms).hasSizeGreaterThan(5);
        assertThat(machines).hasSizeGreaterThan(18);
        assertThat(machines).extracting(KnowledgeChunk::anchor).doesNotHaveDuplicates()
                .contains("9. Order — order-service")
                .anySatisfy(anchor -> assertThat(anchor).startsWith("9. Order — order-service › Who may cancel"));
        assertThat(machines).allSatisfy(chunk ->
                assertThat(chunk.body().length()).isLessThanOrEqualTo(KnowledgeParser.MAX_PASSAGE));
        assertThat(rules).allSatisfy(chunk -> assertThat(chunk.contentHash()).matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("the real flow document: every business and exception flow and the two platform flows staff use, no "
            + "diagram, no table or event name column, no build note, every passage within a passage's size")
    void realFlows() throws IOException {
        Path docs = Path.of("docs", "business-design");
        List<KnowledgeChunk> flows = KnowledgeParser.flows("04c-all-flows",
                Files.readString(docs.resolve("04c-all-flows.md"), StandardCharsets.UTF_8));

        assertThat(flows).extracting(KnowledgeChunk::anchor).doesNotHaveDuplicates()
                .contains("F-PRD", "F-PO", "F-RCP", "F-PUT", "F-MTC", "F-DSG", "F-CAT", "F-CART", "F-PAY", "F-FUL",
                        "F-SHP", "F-SAMPLE", "F-PROD", "F-TRF", "F-REP", "F-CNT", "F-EXP", "F-RMA", "F-RTO", "F-REF",
                        "X-SHORT", "X-RESV", "X-CHK", "P-CHAT", "P-NOTI")
                .doesNotContain("P-PERM", "P-RPT");
        assertThat(flows).filteredOn(flow -> flow.anchor().equals("F-PO")).singleElement()
                .satisfies(flow -> assertThat(flow.title()).isEqualTo("F-PO — Đơn mua hàng gửi nhà cung cấp (Nhập hàng)"));
        assertThat(flows).allSatisfy(chunk -> {
            assertThat(chunk.kind()).isEqualTo(KnowledgeChunk.Kind.FLOW);
            assertThat(chunk.body()).doesNotContain("```", "flowchart", "Ghi vào", "Đã cài", "outbox_event",
                    "processed_event");
            assertThat(chunk.body().length()).isLessThanOrEqualTo(KnowledgeParser.MAX_PASSAGE);
        });
    }
}
