package com.stockflow.assistant.eval;

import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolResult;
import com.stockflow.assistant.internal.domain.Turn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The harness's scripted model follows a case question by question and step by step. */
class OracleModelTest {

    private static final GoldenCase.Oracle CATALOG = new GoldenCase.Oracle("searchCatalog", Map.of("text", "kraft"));
    private static final GoldenCase.Oracle KRAFT = new GoldenCase.Oracle("getStockLevels",
            Map.of("sku", "EVAL-KRAFT-12OZ"));
    private static final GoldenCase.Oracle LEVELS = new GoldenCase.Oracle("getStockLevels",
            Map.of("sku", "EVAL-LID-90"));
    private static final GoldenCase.Oracle LOTS = new GoldenCase.Oracle("getStockLots", Map.of("sku", "EVAL-LID-90"));

    @Test
    @DisplayName("a chain makes one call per step, then answers with every result of the question")
    void chain() throws Exception {
        OracleModel oracle = oracle(List.of(), List.of(CATALOG, KRAFT));
        List<Turn> history = new ArrayList<>(List.of(question("Cốc kraft 12oz còn bao nhiêu?")));

        LanguageModel.Reply first = oracle.next(request(history));
        assertThat(first.stopReason()).isEqualTo(LanguageModel.StopReason.TOOL_USE);
        assertThat(first.turn().toolCalls()).extracting(ToolCall::name).containsExactly("searchCatalog");
        answerCalls(history, first, "{\"sku\":\"EVAL-KRAFT-12OZ\"}");

        LanguageModel.Reply second = oracle.next(request(history));
        assertThat(second.turn().toolCalls()).extracting(ToolCall::name).containsExactly("getStockLevels");
        assertThat(second.turn().toolCalls().getFirst().arguments()).contains("EVAL-KRAFT-12OZ");
        answerCalls(history, second, "{\"onHand\":120}");

        LanguageModel.Reply answer = oracle.next(request(history));
        assertThat(answer.stopReason()).isEqualTo(LanguageModel.StopReason.END_TURN);
        assertThat(answer.turn().text()).isEqualTo("{\"sku\":\"EVAL-KRAFT-12OZ\"}\n{\"onHand\":120}");
    }

    @Test
    @DisplayName("an earlier question gets its own calls and the case's question its own")
    void followUp() throws Exception {
        OracleModel oracle = oracle(List.of(new GoldenCase.Earlier("Tồn kho EVAL-LID-90?", List.of(LEVELS))),
                List.of(LOTS));

        List<Turn> earlier = new ArrayList<>(List.of(question("Tồn kho EVAL-LID-90?")));
        LanguageModel.Reply call = oracle.next(request(earlier));
        assertThat(call.turn().toolCalls()).extracting(ToolCall::name).containsExactly("getStockLevels");
        answerCalls(earlier, call, "{\"onHand\":43}");
        assertThat(oracle.next(request(earlier)).turn().text()).isEqualTo("{\"onHand\":43}");

        // What the loop sends with the follow-up: the earlier question with its answer only, then the new question.
        List<Turn> followUp = new ArrayList<>(List.of(question("Tồn kho EVAL-LID-90?"),
                new Turn.ModelTurn("{}", "{\"onHand\":43}", List.of()),
                question("Lô nào của mã đó hết hạn sớm nhất?")));
        LanguageModel.Reply lots = oracle.next(request(followUp));
        assertThat(lots.turn().toolCalls()).extracting(ToolCall::name).containsExactly("getStockLots");
        answerCalls(followUp, lots, "{\"lotNumber\":\"EVAL-LID-A\"}");
        assertThat(oracle.next(request(followUp)).turn().text()).isEqualTo("{\"lotNumber\":\"EVAL-LID-A\"}");
    }

    @Test
    @DisplayName("a case with no oracle call answers without tools")
    void noTool() throws Exception {
        LanguageModel.Reply answer = oracle(List.of(), List.of()).next(request(List.of(question("Thời tiết?"))));

        assertThat(answer.turn().toolCalls()).isEmpty();
        assertThat(answer.turn().text()).isEqualTo(OracleModel.NO_TOOL_ANSWER);
    }

    private static OracleModel oracle(List<GoldenCase.Earlier> earlier, List<GoldenCase.Oracle> steps) {
        OracleModel oracle = new OracleModel();
        oracle.use(new GoldenCase("T-01", "S", "formal", new GoldenCase.Persona("INVENTORY_PLANNER", List.of()),
                "?", List.of(), List.of(), false, List.of(), null, List.of(), null, false, null, List.of(), earlier,
                steps, false));
        return oracle;
    }

    private static Turn.Question question(String text) {
        return new Turn.Question(text, "Context for this question");
    }

    private static LanguageModel.Request request(List<Turn> history) {
        return new LanguageModel.Request("system", List.of(), history);
    }

    /** What the loop appends after a tool step: the model's turn, then one result per call. */
    private static void answerCalls(List<Turn> history, LanguageModel.Reply reply, String content) {
        history.add(reply.turn());
        history.add(new Turn.ToolResults(reply.turn().toolCalls().stream()
                .map(call -> new ToolResult(call.id(), content, false)).toList()));
    }
}
