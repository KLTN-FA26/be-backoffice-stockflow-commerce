package com.stockflow.assistant.internal.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The kltn-docs analysis cut along its own structure: a chapter's rules one by one, its statuses, edge cases and flow by
 * section, the glossary term by term (design §6.1).
 */
class KnowledgeParserTest {

    private static final Path SNAPSHOT = Path.of("docs", "kltn-snapshot");

    private static final String CHAPTER = """
            # 15 — Thanh toán theo hợp đồng & Công nợ (Contract Payment & Receivables)

            ## 1. Mục đích (Purpose)

            Left out: the purpose.

            ## 4. Luồng nghiệp vụ từng bước (Step-by-step)

            ### 4.1. Sinh đợt & đến hạn

            1. **Ký hợp đồng** — hệ thống sinh các đợt ([14](../14-cart-and-order/README.md) BR-15).

            ```mermaid
            flowchart LR
                A --> B
            ```

            ### Dữ liệu tối thiểu cần lưu

            | Thực thể | Trường |
            |---|---|
            | `Installment` | `id` |

            ## 5. Trạng thái / Vòng đời (Lifecycle)

            ### Trạng thái đợt

            | Trạng thái | Ý nghĩa |
            |---|---|
            | `Due` | Đến hạn |

            ## 6. Ràng buộc nghiệp vụ (Business Rules) & Edge case

            ### Business rules

            - **BR-01:** Khách chỉ thanh toán theo lịch của hợp đồng `Signed`.
            - **BR-02:** **Nguồn kết quả giao.**
              - Tự động từ webhook ([Shipping](../../warehouse/09-shipping/README.md)).
              - Hoặc ghi tay.

              > ✅ Đã chốt 2026-11-05: xem [open-questions D23](../../open-questions/README.md).

            **Giao nguyên chuyến:**

            - **BR-03 — Bốn mắt.** Người duyệt khác người đề nghị.

            ### Đối chiếu chuẩn tham chiếu

            | Chủ đề | Odoo |
            |---|---|
            | FIFO | Khớp |

            ### Edge case

            | Tình huống | Hướng xử lý |
            |---|---|
            | Ghi trùng một dòng sao kê | Từ chối (BR-03) |

            ## 7. Liên kết với các module khác

            Left out: the links.
            """;

    @Test
    @DisplayName("a chapter's rule is one chunk anchored by its chapter, titled by its name, the chapter's English name "
            + "and its group")
    void rules() {
        List<KnowledgeChunk> rules = KnowledgeParser.chapter("15-payment", CHAPTER).stream()
                .filter(chunk -> chunk.anchor().contains("BR-")).toList();

        assertThat(rules).extracting(KnowledgeChunk::anchor).containsExactly("15 BR-01", "15 BR-02", "15 BR-03");
        assertThat(rules).allSatisfy(rule -> assertThat(rule.kind()).isEqualTo(KnowledgeChunk.Kind.RULE));
        assertThat(rules.get(0).title())
                .isEqualTo("15 BR-01 — Contract Payment & Receivables");
        assertThat(rules.get(0).body()).isEqualTo("Khách chỉ thanh toán theo lịch của hợp đồng `Signed`.");
        assertThat(rules.get(1).title()).as("a rule opening with its name in bold")
                .isEqualTo("15 BR-02 Nguồn kết quả giao — Contract Payment & Receivables");
        assertThat(rules.get(1).body()).as("its bullets and note, unindented, links as their text")
                .isEqualTo("""
                        **Nguồn kết quả giao.**
                        - Tự động từ webhook (Shipping (09)).
                        - Hoặc ghi tay.

                        > ✅ Đã chốt 2026-11-05: xem open-questions D23.""");
        assertThat(rules.get(2).title()).as("a rule named in its id, under a group")
                .isEqualTo("15 BR-03 Bốn mắt — Contract Payment & Receivables › Giao nguyên chuyến");
        assertThat(rules.get(2).body()).isEqualTo("Người duyệt khác người đề nghị.");
    }

    @Test
    @DisplayName("a chapter's flow, statuses and edge cases are anchored by section; its purpose, links, diagrams, the "
            + "data to store and the comparison with other products are left out")
    void sections() {
        List<KnowledgeChunk> chunks = KnowledgeParser.chapter("15-payment", CHAPTER);

        assertThat(chunks).extracting(KnowledgeChunk::anchor)
                .containsExactly("15 §4.1", "15 §5", "15 BR-01", "15 BR-02", "15 BR-03", "15 §6");
        KnowledgeChunk flow = chunks.getFirst();
        assertThat(flow.kind()).isEqualTo(KnowledgeChunk.Kind.FLOW);
        assertThat(flow.title()).isEqualTo("15 §4.1 Sinh đợt & đến hạn — Contract Payment & Receivables");
        assertThat(flow.body()).isEqualTo("1. **Ký hợp đồng** — hệ thống sinh các đợt (14 BR-15).");
        assertThat(chunks.get(1).kind()).isEqualTo(KnowledgeChunk.Kind.STATE_MACHINE);
        assertThat(chunks.get(1).title()).startsWith("15 §5 Trạng thái đợt — ");
        KnowledgeChunk edgeCases = chunks.getLast();
        assertThat(edgeCases.kind()).isEqualTo(KnowledgeChunk.Kind.RULE);
        assertThat(edgeCases.title()).startsWith("15 §6 Edge case — ");
        assertThat(edgeCases.body()).contains("Ghi trùng một dòng sao kê");
        assertThat(String.join("\n", chunks.stream().map(KnowledgeChunk::body).toList()))
                .doesNotContain("Left out", "mermaid", "flowchart", "Installment", "Odoo", "](", "README.md");
    }

    @Test
    @DisplayName("a long section becomes passages that fit, numbered under one anchor, a cut table repeating its header")
    void longSections() {
        StringBuilder table = new StringBuilder("| Trạng thái | Chuyển tiếp | Ai |\n|---|---|---|\n");
        for (int i = 0; i < 60; i++) {
            table.append("| STATUS_").append(i).append(" | → NEXT_").append(i).append(" | Quản lý kho |\n");
        }
        List<KnowledgeChunk> chunks = KnowledgeParser.chapter("17-order-management",
                "# 17 — Quản lý đơn hàng\n\n## 5. Trạng thái\n\n### 5.1. Đơn\n\nMở đầu.\n\n" + table);

        assertThat(chunks).hasSizeGreaterThan(1).allSatisfy(chunk ->
                assertThat(chunk.body().length()).isLessThanOrEqualTo(KnowledgeParser.MAX_PASSAGE));
        assertThat(chunks).extracting(KnowledgeChunk::anchor).startsWith("17 §5.1", "17 §5.1 (2)")
                .doesNotHaveDuplicates();
        assertThat(chunks.getFirst().title()).as("a chapter with no English name: none in its titles")
                .isEqualTo("17 §5.1 Đơn (1/" + chunks.size() + ")");
        assertThat(chunks.subList(1, chunks.size())).allSatisfy(chunk ->
                assertThat(chunk.body()).startsWith("| Trạng thái | Chuyển tiếp | Ai |\n|---|---|---|"));
        assertThat(String.join("\n", chunks.stream().map(KnowledgeChunk::body).toList()))
                .contains("STATUS_0 ", "STATUS_59 ");
    }

    @Test
    @DisplayName("one table row longer than a passage is cut after a space, each part under the repeated header, "
            + "every word kept and the continuations marked")
    void aRowLongerThanAPassage() {
        StringBuilder guard = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            guard.append("word").append(i).append(' ');
        }
        String table = "| Từ | Sang | Điều kiện |\n|---|---|---|\n| A | B | " + guard + "|\n| C | D | ngắn |\n";
        List<KnowledgeChunk> chunks = KnowledgeParser.chapter("20-inventory-control",
                "## 5. Trạng thái\n\n### 5.2. Phiếu kiểm kê\n\n" + table);

        assertThat(chunks).hasSizeGreaterThan(1).allSatisfy(chunk -> {
            assertThat(chunk.body().length()).isLessThanOrEqualTo(KnowledgeParser.MAX_PASSAGE);
            assertThat(chunk.body()).startsWith("| Từ | Sang | Điều kiện |\n|---|---|---|");
        });
        String all = String.join("\n", chunks.stream().map(KnowledgeChunk::body).toList());
        assertThat(all).contains("word0 ", "word150 ", "word299 ", "| C | D | ngắn |")
                .contains("\n" + KnowledgeParser.CONTINUED + "word");
    }

    @Test
    @DisplayName("a line is cut after its last space that fits, hard where there is none, never inside a surrogate pair")
    void cut() {
        assertThat(KnowledgeParser.cut("short line", 20)).containsExactly("short line");
        assertThat(KnowledgeParser.cut("aaaa bbbb cccc dddd eeee ffff", 16))
                .containsExactly("aaaa bbbb cccc", KnowledgeParser.CONTINUED + "dddd eeee ffff");
        assertThat(KnowledgeParser.cut("x".repeat(40), 16)).allSatisfy(part ->
                assertThat(part.length()).isLessThanOrEqualTo(16)).hasSize(3);
        assertThat(KnowledgeParser.cut("abcdefghijklmno😀ij", 16)).first().isEqualTo("abcdefghijklmno");
        assertThat(KnowledgeParser.cut("a".repeat(100), 1)).as("a tiny room still moves on")
                .allSatisfy(part -> assertThat(part.length()).isLessThanOrEqualTo(KnowledgeParser.MIN_ROOM));
    }

    @Test
    @DisplayName("a glossary term is a row with a bold first cell, anchored and titled by the term as written; the "
            + "rest of a group is passages; the log of decisions opening the document is left out")
    void glossary() {
        List<KnowledgeChunk> chunks = KnowledgeParser.glossary("glossary", """
                # Bảng thuật ngữ (Glossary)

                > Đã chốt 2026-11-04: kho chỉ là kho thành phẩm.

                ---

                ## 1. Thuật ngữ chung về hàng hoá & tồn kho

                | Thuật ngữ | Tiếng Việt / Giải thích |
                |-----------|--------------------------|
                | **Reservation / Giữ hàng** | Tạm giữ một số thùng theo SKU ([20 §5.6](../warehouse/20-inventory-control/README.md)). |
                | **Carrier connection type / Kiểu kết nối hãng** *(✅ 2026-11-06)* | Webhook hoặc API. |

                ## 8. Thuật ngữ không còn áp dụng (đã bỏ 2026-11-04)

                | Thuật ngữ cũ | Thay bằng / ghi chú |
                |--------------|---------------------|
                | FEFO; quét hết hạn hằng đêm | Không có hạn dùng: FIFO. |
                """);

        assertThat(chunks).extracting(KnowledgeChunk::anchor).containsExactly("Reservation / Giữ hàng",
                "Carrier connection type / Kiểu kết nối hãng", "glossary §8");
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.kind()).isEqualTo(KnowledgeChunk.Kind.TERM));
        assertThat(chunks.getFirst().title()).isEqualTo("Reservation / Giữ hàng");
        assertThat(chunks.getFirst().body()).isEqualTo("Tạm giữ một số thùng theo SKU (20 §5.6).");
        assertThat(chunks).noneSatisfy(chunk ->
                assertThat(chunk.body()).contains("kho chỉ là kho thành phẩm"));
        assertThat(chunks.getLast().title()).isEqualTo("glossary §8 Thuật ngữ không còn áp dụng (đã bỏ 2026-11-04)");
        assertThat(chunks.getLast().body()).startsWith("| Thuật ngữ cũ |").contains("FEFO; quét hết hạn hằng đêm");
    }

    @Test
    @DisplayName("a title longer than its column is cut, not a reason for the document's sync to fail")
    void longTitles() {
        KnowledgeChunk chunk = new KnowledgeChunk("17-order-management", "17 §4.1", KnowledgeChunk.Kind.FLOW,
                "t".repeat(400), "body");

        assertThat(chunk.title()).hasSize(KnowledgeChunk.MAX_TITLE).endsWith("…");
    }

    @Test
    @DisplayName("the snapshot: every rule of every chapter once, anchored by its chapter; every passage within a "
            + "passage's size; no link, diagram or chapter left unparsed")
    void realSnapshot() throws IOException {
        List<Path> chapters;
        try (Stream<Path> files = Files.list(SNAPSHOT)) {
            chapters = files.filter(file -> file.getFileName().toString().matches("\\d{2}-.+\\.md")).sorted().toList();
        }
        assertThat(chapters).hasSizeGreaterThanOrEqualTo(14);
        Pattern ruleLine = Pattern.compile("(?m)^[-*]\\s+\\*\\*BR-(\\d+[a-z]?)");
        for (Path file : chapters) {
            String name = file.getFileName().toString().replace(".md", "");
            String markdown = Files.readString(file, StandardCharsets.UTF_8);
            List<KnowledgeChunk> chunks = KnowledgeParser.chapter(name, markdown);
            List<String> written = ruleLine.matcher(markdown).results()
                    .map(rule -> name.substring(0, 2) + " BR-" + rule.group(1)).toList();

            assertThat(written).as(name + " has rules").isNotEmpty();
            assertThat(chunks).extracting(KnowledgeChunk::anchor).as(name + ": every rule, once")
                    .containsAll(written).doesNotHaveDuplicates();
            assertThat(chunks).as(name + ": statuses and a flow")
                    .anyMatch(chunk -> chunk.kind() == KnowledgeChunk.Kind.STATE_MACHINE)
                    .anyMatch(chunk -> chunk.kind() == KnowledgeChunk.Kind.FLOW);
            assertThat(chunks).allSatisfy(chunk -> {
                assertThat(chunk.anchor()).startsWith(name.substring(0, 2) + " ");
                assertThat(chunk.body().length()).isLessThanOrEqualTo(KnowledgeParser.MAX_PASSAGE);
                assertThat(chunk.body()).doesNotContain("```mermaid", "README.md");
            });
        }
        List<KnowledgeChunk> glossary = KnowledgeParser.glossary("glossary",
                Files.readString(SNAPSHOT.resolve("glossary.md"), StandardCharsets.UTF_8));
        assertThat(glossary).hasSizeGreaterThan(200).extracting(KnowledgeChunk::anchor).doesNotHaveDuplicates()
                .contains("Reservation / Giữ hàng", "Allocation / Phân bổ", "glossary §8");
        assertThat(glossary).allSatisfy(chunk ->
                assertThat(chunk.body().length()).isLessThanOrEqualTo(KnowledgeParser.MAX_PASSAGE));
    }

    @Test
    @DisplayName("every anchor the golden set expects a search to return is in the snapshot: a re-sync that renumbers "
            + "a rule fails here, not in an eval")
    void theGoldenSetsAnchorsExist() throws IOException {
        Set<String> anchors;
        try (Stream<Path> files = Files.list(SNAPSHOT)) {
            anchors = files.filter(file -> file.toString().endsWith(".md") && !file.endsWith("README.md"))
                    .flatMap(file -> {
                        String name = file.getFileName().toString().replace(".md", "");
                        try {
                            String markdown = Files.readString(file, StandardCharsets.UTF_8);
                            return (name.equals(KnowledgeParser.GLOSSARY) ? KnowledgeParser.glossary(name, markdown)
                                    : KnowledgeParser.chapter(name, markdown)).stream();
                        } catch (IOException unreadable) {
                            throw new IllegalStateException(unreadable);
                        }
                    }).map(KnowledgeChunk::anchor).collect(Collectors.toSet());
        }
        List<String> expected = new ArrayList<>();
        for (JsonNode golden : new ObjectMapper().readTree(
                Path.of("src", "test", "resources", "assistant", "golden-set.json").toFile())) {
            golden.path("expectRetrieved").forEach(anchor -> expected.add(anchor.asText()));
        }

        assertThat(expected).isNotEmpty();
        assertThat(anchors).containsAll(expected);
    }
}
