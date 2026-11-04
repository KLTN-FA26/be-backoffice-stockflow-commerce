package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.assistant.internal.domain.ToolResult;
import com.stockflow.assistant.internal.domain.Turn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A citation is checked, not trusted; a proposal is read from the reorder tool's own answer; a number rests on what
 * the model was given for it.
 */
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
    @DisplayName("an answer's numbers may rest on its question, its context, every result of its exchange, and the "
            + "same of the ten exchanges before it, which the model was sent again; never on an earlier answer's text")
    void numberGrounds() {
        List<Turn> turns = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            turns.add(new Turn.Question("Câu " + i, "Context " + (100 + i)));
            turns.add(new Turn.ModelTurn("{}", null, List.of(new ToolCall("toolu_" + i, "getStockLevels", "{}"))));
            turns.add(new Turn.ToolResults(List.of(new ToolResult("toolu_" + i, "{\"onHand\":" + (1000 + i) + "}",
                    false))));
            turns.add(new Turn.ModelTurn("{}", "Còn " + (5000 + i) + " cái.", List.of()));
        }
        turns.add(new Turn.Question("Còn bao nhiêu?", "Context 999"));

        AnswerNumbers.Grounds earlier = AnswerSources.earlierGrounds(turns);

        assertThat(AnswerNumbers.unverified("1011 1002 111 102", earlier)).as("the ten exchanges before it")
                .isEmpty();
        assertThat(AnswerNumbers.unverified("1001 1000 101", earlier)).as("older ones were not sent again")
                .containsExactly("1001", "1000", "101");
        assertThat(AnswerNumbers.unverified("5011", earlier)).as("an earlier answer's text grounds nothing")
                .containsExactly("5011");
        assertThat(AnswerNumbers.unverified("999", earlier)).as("the question answered is not an earlier one")
                .containsExactly("999");
        assertThat(AnswerNumbers.unverified("999", AnswerSources.groundsOf(turns.subList(turns.size() - 1,
                turns.size())))).isEmpty();
        assertThat(AnswerSources.exchanges(turns)).hasSize(13).allSatisfy(exchange ->
                assertThat(exchange.getFirst()).isInstanceOf(Turn.Question.class));
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

    @Test
    @DisplayName("proposeReorder run twice for one question (a model calling it again, or the question answered again on "
            + "a stronger model) offers one form per supplier, the last: two forms invite two purchase orders")
    void latestProposalPerSupplier() {
        String first = "{\"type\":\"PURCHASE_ORDER_DRAFT\",\"supplierCode\":\"SUP-A\",\"supplierName\":\"A\","
                + "\"generatedAt\":\"2026-10-25T03:00:00Z\",\"lines\":[{\"sku\":\"CUP-12OZ\",\"quantity\":1000}]}";
        String again = "{\"type\":\"PURCHASE_ORDER_DRAFT\",\"supplierCode\":\"SUP-A\",\"supplierName\":\"A\","
                + "\"generatedAt\":\"2026-10-25T03:01:00Z\",\"lines\":[{\"sku\":\"CUP-12OZ\",\"quantity\":1276}]}";
        List<ToolExecution> executions = List.of(
                run("proposeReorder", ToolOutcome.OK, "{\"lines\":[],\"proposals\":[" + first + "]}"),
                run("proposeReorder", ToolOutcome.OK, "{\"lines\":[],\"proposals\":[" + again + "]}"));

        assertThat(AnswerSources.proposals(executions, json)).singleElement().satisfies(proposal -> {
            assertThat(proposal.supplierCode()).isEqualTo("SUP-A");
            assertThat(proposal.lines()).containsExactly(new AnswerSources.ProposalLine("CUP-12OZ", 1276));
        });
    }
}
