package com.stockflow.assistant.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Design §3.1: what of a conversation is sent with a new question. */
class HistoryTest {

    private static Turn.ModelTurn text(String text) {
        return new Turn.ModelTurn("{\"text\":\"" + text + "\"}", text, List.of());
    }

    private static List<Turn> exchange(int n) {
        ToolCall call = new ToolCall("toolu_" + n, "getStockLevels", "{}");
        return List.of(new Turn.Question("Q" + n, "ctx"), new Turn.ModelTurn("{}", null, List.of(call)),
                new Turn.ToolResults(List.of(new ToolResult("toolu_" + n, "{\"rows\":" + n + "}", false))),
                text("A" + n));
    }

    @Test
    @DisplayName("earlier questions keep their final answer only; the current one keeps every turn")
    void earlierAnswersOnly() {
        List<Turn> turns = new ArrayList<>(exchange(1));
        turns.add(new Turn.Question("Q2", "ctx"));
        ToolCall pending = new ToolCall("toolu_2", "findOrder", "{}");
        turns.add(new Turn.ModelTurn("{}", null, List.of(pending)));
        turns.add(new Turn.ToolResults(List.of(new ToolResult("toolu_2", "{}", false))));

        List<Turn> sent = History.forModel(turns, HistoryTest::text);

        assertThat(sent).hasSize(5);
        assertThat(sent.get(0)).isEqualTo(turns.get(0));
        assertThat(((Turn.ModelTurn) sent.get(1)).text()).isEqualTo("A1");
        assertThat(((Turn.ModelTurn) sent.get(1)).toolCalls()).isEmpty();
        assertThat(sent.subList(2, 5)).containsExactlyElementsOf(turns.subList(4, 7));
    }

    @Test
    @DisplayName("only the last ten earlier questions are sent, and a long or missing answer is marked")
    void bounded() {
        List<Turn> turns = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            turns.addAll(exchange(i));
        }
        turns.add(new Turn.Question("broken", "ctx"));
        turns.add(new Turn.ModelTurn("{}", null, List.of(new ToolCall("toolu_x", "findOrder", "{}"))));
        turns.add(new Turn.Question("long", "ctx"));
        turns.add(text("x".repeat(History.EARLIER_ANSWER_LENGTH + 50)));
        turns.add(new Turn.Question("now", "ctx"));

        List<Turn> sent = History.forModel(turns, HistoryTest::text);

        assertThat(sent).hasSize(2 * History.EARLIER_QUESTIONS + 1);
        assertThat(((Turn.Question) sent.getFirst()).text()).isEqualTo("Q5");
        assertThat(((Turn.ModelTurn) sent.get(sent.size() - 4)).text()).isEqualTo(History.NOT_ANSWERED);
        assertThat(((Turn.ModelTurn) sent.get(sent.size() - 2)).text())
                .hasSize(History.EARLIER_ANSWER_LENGTH + 4).endsWith(" […]");
        assertThat(((Turn.Question) sent.getLast()).text()).isEqualTo("now");
    }
}
