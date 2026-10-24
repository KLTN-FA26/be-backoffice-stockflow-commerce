package com.stockflow.assistant.internal.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolResult;
import com.stockflow.assistant.internal.domain.Turn;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The JSON shape of {@code assistant.message.content}, written and read in this one place.
 *
 * <p>The provider's content and a tool call's arguments are kept as JSON <b>strings</b>, not as nested objects.
 * {@code jsonb} reorders an object's keys and drops its whitespace, but it never touches the characters of a string,
 * so a string is the only way to get back exactly the bytes the provider sent — and a replayed history must be
 * exactly what the model saw (design §3.4).</p>
 */
@Component
class TurnCodec {

    private final ObjectMapper json;

    TurnCodec(ObjectMapper json) {
        this.json = json;
    }

    String write(Turn turn) {
        ObjectNode node = json.createObjectNode();
        switch (turn) {
            case Turn.Question question -> node.put("type", "question")
                    .put("text", question.text())
                    .put("context", question.context());
            case Turn.ModelTurn model -> {
                node.put("type", "model").put("provider", model.providerContent()).put("text", model.text());
                ArrayNode calls = node.putArray("toolCalls");
                model.toolCalls().forEach(call -> calls.addObject()
                        .put("id", call.id()).put("name", call.name()).put("arguments", call.arguments()));
            }
            case Turn.ToolResults results -> {
                node.put("type", "toolResults");
                ArrayNode list = node.putArray("results");
                results.results().forEach(result -> list.addObject()
                        .put("callId", result.callId()).put("content", result.content()).put("error", result.error()));
            }
        }
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("an object node always serialises", impossible);
        }
    }

    Turn read(String content) {
        JsonNode node;
        try {
            node = json.readTree(content);
        } catch (JsonProcessingException corrupt) {
            throw new IllegalStateException("assistant.message.content is not JSON", corrupt);
        }
        return switch (node.path("type").asText()) {
            case "question" -> new Turn.Question(node.path("text").asText(), node.path("context").asText());
            case "model" -> {
                List<ToolCall> calls = new ArrayList<>();
                node.path("toolCalls").forEach(call -> calls.add(new ToolCall(call.path("id").asText(),
                        call.path("name").asText(), call.path("arguments").asText())));
                JsonNode text = node.path("text");
                yield new Turn.ModelTurn(node.path("provider").asText(), text.isNull() || text.isMissingNode()
                        ? null : text.asText(), calls);
            }
            case "toolResults" -> {
                List<ToolResult> results = new ArrayList<>();
                node.path("results").forEach(result -> results.add(new ToolResult(result.path("callId").asText(),
                        result.path("content").asText(), result.path("error").asBoolean())));
                yield new Turn.ToolResults(results);
            }
            default -> throw new IllegalStateException("unknown turn type in assistant.message.content: "
                    + node.path("type").asText());
        };
    }
}
