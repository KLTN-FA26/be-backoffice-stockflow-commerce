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
 * A scripted stand-in for the model, for the harness: for each question of a case it makes exactly the calls the case
 * names, step by step — a step's {@code together} calls in the same reply — then answers with every tool result of
 * that question verbatim.
 *
 * <p>An answer that is nothing but the tool output makes the golden set test the layers under the model: if a fact is
 * missing, the fixture, the fact query or the tool is wrong; if a forbidden value appears, the tool showed a persona
 * data its screen would hide. Neither depends on what a real model would have said.</p>
 *
 * <p>Which question is being answered is read from the history the loop sends: the questions in it, this one
 * included. {@link GoldenSet} keeps a case within the ten earlier questions the loop sends, so the count is exact.</p>
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
        List<Turn> history = request.history();
        int questions = 0;
        int asked = 0;
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i) instanceof Turn.Question) {
                questions++;
                asked = i;
            }
        }
        List<Turn.ToolResults> results = history.subList(asked + 1, history.size()).stream()
                .filter(Turn.ToolResults.class::isInstance).map(Turn.ToolResults.class::cast).toList();
        List<GoldenCase.Oracle> steps = questions >= 1 && questions <= current.earlier().size()
                ? current.earlier().get(questions - 1).oracle() : current.oracle();
        if (results.size() < steps.size()) {
            GoldenCase.Oracle step = steps.get(results.size());
            List<ToolCall> calls = new java.util.ArrayList<>();
            for (GoldenCase.Oracle call : java.util.stream.Stream.concat(java.util.stream.Stream.of(step),
                    step.together().stream()).toList()) {
                calls.add(new ToolCall("toolu_" + UUID.randomUUID(), call.tool(),
                        json.writeValueAsString(call.arguments())));
            }
            return new LanguageModel.Reply(new Turn.ModelTurn("{\"oracle\":\"tool_use\"}", null, calls),
                    LanguageModel.StopReason.TOOL_USE, new LanguageModel.Usage(1, 1, 0, 0), "oracle");
        }
        if (results.isEmpty()) {
            return answer(NO_TOOL_ANSWER);
        }
        return answer(results.stream().flatMap(turn -> turn.results().stream()).map(ToolResult::content)
                .collect(Collectors.joining("\n")));
    }

    private static LanguageModel.Reply answer(String text) {
        return new LanguageModel.Reply(new Turn.ModelTurn("{\"oracle\":\"answer\"}", text, List.of()),
                LanguageModel.StopReason.END_TURN, new LanguageModel.Usage(1, 1, 0, 0), "oracle");
    }
}
