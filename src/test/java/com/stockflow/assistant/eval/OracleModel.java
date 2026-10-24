package com.stockflow.assistant.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolResult;
import com.stockflow.assistant.internal.domain.Turn;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A scripted stand-in for the model, for the harness: it calls exactly the tool a case names, then answers with the
 * tool results verbatim.
 *
 * <p>An answer that is nothing but the tool output makes the golden set test the layers under the model: if a fact is
 * missing, the fixture, the fact query or the tool is wrong; if a forbidden value appears, the tool showed a persona
 * data its screen would hide. Neither depends on what a real model would have said.</p>
 */
final class OracleModel {

    static final String NO_TOOL_ANSWER = "Tôi chỉ hỗ trợ tra cứu tồn kho, đơn hàng, mua hàng và doanh số, "
            + "và không thể thay đổi dữ liệu.";

    private final ObjectMapper json = new ObjectMapper();
    private GoldenCase current;

    void use(GoldenCase golden) {
        this.current = golden;
    }

    LanguageModel.Reply next(LanguageModel.Request request) throws JsonProcessingException {
        Turn last = request.history().getLast();
        if (last instanceof Turn.ToolResults results) {
            return answer(results.results().stream().map(ToolResult::content).collect(Collectors.joining("\n")));
        }
        if (current.oracle() == null) {
            return answer(NO_TOOL_ANSWER);
        }
        return new LanguageModel.Reply(new Turn.ModelTurn("{\"oracle\":\"tool_use\"}", null,
                List.of(new ToolCall("toolu_" + UUID.randomUUID(), current.oracle().tool(),
                        json.writeValueAsString(current.oracle().arguments())))),
                LanguageModel.StopReason.TOOL_USE, new LanguageModel.Usage(1, 1, 0, 0), "oracle");
    }

    private static LanguageModel.Reply answer(String text) {
        return new LanguageModel.Reply(new Turn.ModelTurn("{\"oracle\":\"answer\"}", text, List.of()),
                LanguageModel.StopReason.END_TURN, new LanguageModel.Usage(1, 1, 0, 0), "oracle");
    }
}
