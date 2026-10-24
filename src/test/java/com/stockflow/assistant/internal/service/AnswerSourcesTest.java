package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A citation is checked, not trusted; a proposal is read from the reorder tool's own answer. */
class AnswerSourcesTest {

    private final ObjectMapper json = new ObjectMapper();

    private static ToolExecution run(String tool, ToolOutcome outcome, String result) {
        return new ToolExecution(new ToolCall("toolu_" + tool, tool, "{}"), outcome, result, 1);
    }

    @Test
    @DisplayName("an anchor is a citation only when the answer names it and a search returned it")
    void citations() {
        List<ToolExecution> executions = List.of(
                run("searchKnowledge", ToolOutcome.OK, "{\"passages\":[{\"anchor\":\"BR-RMA-002\"},"
                        + "{\"anchor\":\"BR-RMA-003\"}]}"),
                run("findOrder", ToolOutcome.OK, "{\"orderNumber\":\"BR-RMA-009\"}"));

        assertThat(AnswerSources.citations("Không trả được, trừ khi lỗi (br-rma-002). Xem BR-ZZZ-001.", executions,
                json)).containsExactly("BR-RMA-002");
        assertThat(AnswerSources.retrieved(executions, json)).containsExactly("BR-RMA-002", "BR-RMA-003");
        assertThat(AnswerSources.citations(null, executions, json)).isEmpty();
    }

    @Test
    @DisplayName("a failed search is no source")
    void failedSearch() {
        List<ToolExecution> executions = List.of(run("searchKnowledge", ToolOutcome.FAILED, "TOOL_FAILED: ..."));
        assertThat(AnswerSources.citations("BR-RMA-002", executions, json)).isEmpty();
    }

    @Test
    @DisplayName("each supplier's proposal is carried to the panel with when it was computed; none carries none")
    void proposals() {
        List<ToolExecution> executions = List.of(
                run("proposeReorder", ToolOutcome.OK, "{\"lines\":[],\"proposals\":["
                        + "{\"type\":\"PURCHASE_ORDER_DRAFT\",\"supplierCode\":\"SUP-A\",\"supplierName\":\"A\","
                        + "\"generatedAt\":\"2026-10-25T03:00:00Z\",\"lines\":[{\"sku\":\"CUP-12OZ\",\"quantity\":1276}]},"
                        + "{\"type\":\"PURCHASE_ORDER_DRAFT\",\"supplierCode\":\"SUP-B\",\"supplierName\":\"B\","
                        + "\"generatedAt\":1761361200.000000000,\"lines\":[{\"sku\":\"LID-90\",\"quantity\":500}]}]}"),
                run("proposeReorder", ToolOutcome.OK, "{\"lines\":[],\"proposals\":[]}"));

        assertThat(AnswerSources.proposals(executions, json)).satisfiesExactly(
                first -> {
                    assertThat(first.supplierCode()).isEqualTo("SUP-A");
                    assertThat(first.generatedAt()).isEqualTo(Instant.parse("2026-10-25T03:00:00Z"));
                    assertThat(first.lines()).containsExactly(new AnswerSources.ProposalLine("CUP-12OZ", 1276));
                },
                second -> {
                    assertThat(second.supplierCode()).isEqualTo("SUP-B");
                    assertThat(second.generatedAt()).isEqualTo(Instant.ofEpochSecond(1761361200L));
                    assertThat(second.lines()).containsExactly(new AnswerSources.ProposalLine("LID-90", 500));
                });
    }
}
