package com.stockflow.assistant.internal.service;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolResult;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.assistant.internal.domain.Turn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The adapter's translation to and from the provider's types, without a network: what a reply becomes, what a
 * history is sent as, and what a tool definition looks like on the wire.
 */
class ClaudeLanguageModelTest {

    /** A reply the way the API returns one: a thinking block with a signature, text, then a tool call. */
    private static final String RESPONSE = """
            {"id":"msg_01","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[
               {"type":"thinking","thinking":"","signature":"EqQBCkYIBxgCKkB-signature"},
               {"type":"text","text":"Để tôi kiểm tra tồn kho."},
               {"type":"tool_use","id":"toolu_01","name":"getStockLevels","input":{"sku":"CUP-12OZ-WHT"}}],
             "stop_reason":"tool_use","stop_sequence":null,
             "usage":{"input_tokens":1200,"output_tokens":80,"cache_read_input_tokens":3900,
                      "cache_creation_input_tokens":0}}""";

    @Test
    @DisplayName("a reply lifts its text, tool calls, stop reason and usage, and keeps the provider content")
    void reply() throws Exception {
        LanguageModel.Reply reply = ClaudeLanguageModel.reply(
                ObjectMappers.jsonMapper().readValue(RESPONSE, Message.class));

        assertThat(reply.stopReason()).isEqualTo(LanguageModel.StopReason.TOOL_USE);
        assertThat(reply.turn().text()).isEqualTo("Để tôi kiểm tra tồn kho.");
        assertThat(reply.turn().toolCalls()).containsExactly(
                new ToolCall("toolu_01", "getStockLevels", "{\"sku\":\"CUP-12OZ-WHT\"}"));
        assertThat(reply.usage()).isEqualTo(new LanguageModel.Usage(1200, 80, 3900, 0));
        assertThat(reply.model()).isEqualTo("claude-opus-5-5");
    }

    @Test
    @DisplayName("the stored content replays as the same assistant message, thinking signature included")
    void replayIsFaithful() throws Exception {
        LanguageModel.Reply reply = ClaudeLanguageModel.reply(
                ObjectMappers.jsonMapper().readValue(RESPONSE, Message.class));
        String stored = reply.turn().providerContent();

        MessageParam replayed = ClaudeLanguageModel.read(stored);
        JsonNode original = ObjectMappers.jsonMapper().readTree(stored);
        JsonNode again = ObjectMappers.jsonMapper().readTree(ObjectMappers.jsonMapper().writeValueAsString(replayed));

        assertThat(again).isEqualTo(original);
        assertThat(original.path("role").asText()).isEqualTo("assistant");
        assertThat(original.path("content").get(0).path("signature").asText()).isEqualTo("EqQBCkYIBxgCKkB-signature");
        assertThat(original.path("content")).hasSize(3);
    }

    @Test
    @DisplayName("consecutive user-side turns are one user message, tool results first")
    void historyIsWellFormed() throws Exception {
        Turn.ModelTurn asksForTools = ClaudeLanguageModel.reply(
                ObjectMappers.jsonMapper().readValue(RESPONSE, Message.class)).turn();
        Turn.ModelTurn answers = new Turn.ModelTurn(
                "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Còn 40 cái.\"}]}",
                "Còn 40 cái.", List.of());

        List<MessageParam> messages = ClaudeLanguageModel.messages(List.of(
                new Turn.Question("Còn bao nhiêu cốc?", "today is 2026-10-25"),
                asksForTools,
                new Turn.ToolResults(List.of(new ToolResult("toolu_01", "{\"warehouses\":[]}", false))),
                answers,
                // A question whose answer failed, then the next one: sent together.
                new Turn.Question("Kho HCM?", "today is 2026-10-25"),
                new Turn.Question("Kho HCM thì sao?", "today is 2026-10-25")));

        assertThat(messages).extracting(MessageParam::role).containsExactly(MessageParam.Role.USER,
                MessageParam.Role.ASSISTANT, MessageParam.Role.USER, MessageParam.Role.ASSISTANT,
                MessageParam.Role.USER);
        JsonNode json = ObjectMappers.jsonMapper().readTree(ObjectMappers.jsonMapper().writeValueAsString(messages));
        assertThat(json.get(0).path("content")).hasSize(2);
        assertThat(json.get(2).path("content").get(0).path("type").asText()).isEqualTo("tool_result");
        assertThat(json.get(2).path("content").get(0).path("tool_use_id").asText()).isEqualTo("toolu_01");
        assertThat(json.get(4).path("content")).hasSize(4);
    }

    @Test
    @DisplayName("a tool is strict: every property required, no other allowed, properties in declared order")
    void toolDefinition() throws Exception {
        ToolSpec spec = new ToolSpec("findPurchaseOrders", "POs", ToolSchema.object()
                .choice("status", "status", List.of("ANY", "DRAFT"))
                .string("search", "text")
                .integer("limit", "1 to 50")
                .build());

        JsonNode tool = ObjectMappers.jsonMapper().readTree(
                ObjectMappers.jsonMapper().writeValueAsString(ClaudeLanguageModel.tool(spec)));

        assertThat(tool.path("strict").asBoolean()).isTrue();
        assertThat(tool.path("input_schema").path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(tool.path("input_schema").path("required")).extracting(JsonNode::asText)
                .containsExactly("status", "search", "limit");
        assertThat(tool.path("input_schema").path("properties").fieldNames()).toIterable()
                .containsExactly("status", "search", "limit");
        assertThat(tool.path("input_schema").path("properties").path("status").path("enum")).hasSize(2);
        // Strict mode rejects numeric and length constraints; they must never be sent.
        assertThat(tool.toString()).doesNotContain("minimum", "maximum", "minLength", "maxLength");
    }
}
