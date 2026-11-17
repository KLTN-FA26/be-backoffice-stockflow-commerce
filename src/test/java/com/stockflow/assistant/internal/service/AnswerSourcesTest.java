package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.assistant.internal.domain.ToolResult;
import com.stockflow.assistant.internal.domain.Turn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
                run("searchKnowledge", ToolOutcome.OK, "{\"passages\":[{\"anchor\":\"17 §4.10\"},"
                        + "{\"anchor\":\"15 BR-08\"}]}"),
                run("findOrder", ToolOutcome.OK, "{\"orderNumber\":\"17 BR-09\"}"));

        assertThat(AnswerSources.citations("Chỉ trả khi lỗi sản xuất (17 §4.10). Xem 17 BR-09 và 09 BR-08.",
                executions, json)).containsExactly("17 §4.10");
        assertThat(AnswerSources.retrieved(executions, json)).containsExactly("17 §4.10", "15 BR-08");
        assertThat(AnswerSources.citations(null, executions, json)).isEmpty();
    }

    @Test
    @DisplayName("an anchor is named whole: not inside a longer section, a rule of another chapter or a longer number")
    void namedWhole() {
        assertThat(AnswerSources.names("theo 17 §4.1.", "17 §4.1")).isTrue();
        assertThat(AnswerSources.names("theo 17 §4.12", "17 §4.1")).isFalse();
        assertThat(AnswerSources.names("theo 17 §4.1.3", "17 §4.1")).isFalse();
        assertThat(AnswerSources.names("theo 115 br-08", "15 br-08")).isFalse();
        assertThat(AnswerSources.names("theo 15 br-080", "15 br-08")).isFalse();
        assertThat(AnswerSources.names("15 br-08, rồi 15 br-08)", "15 br-08")).isTrue();
        assertThat(AnswerSources.names("giữ hàng (reservation / giữ hàng)", "reservation / giữ hàng")).isTrue();
    }

    @Test
    @DisplayName("a failed search is no source")
    void failedSearch() {
        List<ToolExecution> executions = List.of(run("searchKnowledge", ToolOutcome.FAILED, "TOOL_FAILED: ..."));
        assertThat(AnswerSources.citations("15 BR-08", executions, json)).isEmpty();
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
}
