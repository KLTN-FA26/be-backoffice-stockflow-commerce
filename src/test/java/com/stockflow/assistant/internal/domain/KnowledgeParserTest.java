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
}
