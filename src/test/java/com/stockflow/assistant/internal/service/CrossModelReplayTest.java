package com.stockflow.assistant.internal.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.stockflow.assistant.internal.domain.History;
import com.stockflow.assistant.internal.domain.ToolResult;
import com.stockflow.assistant.internal.domain.Turn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A conversation that changes model or provider, through the real adapters (design §3.4, §11): what reaches the model
 * of a new question when the earlier answers were written by another one — a cheaper model of the route, the model
 * before an escalation, the provider before a deployment switched.
 *
 * <p>Each earlier answer must arrive as a plain text turn in the format of the model called. None of the other
 * model's content may: a Claude message read by Gemini is a role it refuses, and a thought signature or a thinking
 * block is valid only for the model that wrote it. Sent once, any of them would have the provider refuse every later
 * question of the conversation.</p>
 */
class CrossModelReplayTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonMapper SDK_JSON = ObjectMappers.jsonMapper();

    /** Claude asking for a lookup: a thinking block with its signature, a line of text, the call. */
    private static final String CLAUDE_TOOL_USE = """
            {"id":"msg_01","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[
               {"type":"thinking","thinking":"","signature":"EqQBCkYIBxgCKkB-claude-1"},
               {"type":"text","text":"Để tôi kiểm tra tồn kho."},
               {"type":"tool_use","id":"toolu_01","name":"getStockLevels","input":{"sku":"CUP-12OZ"}}],
             "stop_reason":"tool_use","stop_sequence":null,
             "usage":{"input_tokens":1200,"output_tokens":80,"cache_read_input_tokens":3900,
                      "cache_creation_input_tokens":0}}""";

    /** Claude answering from the result, thinking first. */
    private static final String CLAUDE_ANSWER = """
            {"id":"msg_02","type":"message","role":"assistant","model":"claude-opus-5-5",
             "content":[
               {"type":"thinking","thinking":"","signature":"EqQBCkYIBxgCKkB-claude-2"},
               {"type":"text","text":"Còn 40 cái."}],
             "stop_reason":"end_turn","stop_sequence":null,
             "usage":{"input_tokens":1300,"output_tokens":20,"cache_read_input_tokens":3900,
                      "cache_creation_input_tokens":0}}""";

    /** flash-lite asking for a lookup: the call carries the thought signature Gemini wants back on the next turn. */
    private static final String FLASH_LITE_TOOL_USE = """
            {"candidates":[{"content":{"role":"model","parts":[
               {"functionCall":{"name":"getStockLevels","args":{"sku":"CUP-12OZ"},"id":"call_lite_1"},
                "thoughtSignature":"Eu0CCuoC-flash-lite-1"}]},
              "finishReason":"STOP"}],
             "modelVersion":"gemini-3.5-flash-lite"}""";

    /** flash-lite answering, with a signature on its text part as well. */
    private static final String FLASH_LITE_ANSWER = """
            {"candidates":[{"content":{"role":"model","parts":[
               {"text":"Còn 40 cái.","thoughtSignature":"Eu0CCuoC-flash-lite-2"}]},
              "finishReason":"STOP"}],
             "modelVersion":"gemini-3.5-flash-lite"}""";

    private final GeminiLanguageModel flashLite = gemini("gemini-3.5-flash-lite");
    private final GeminiLanguageModel flash = gemini("gemini-3.5-flash");
    private final ClaudeLanguageModel claude = new ClaudeLanguageModel(mock(AnthropicClient.class),
            properties("anthropic", "claude-opus-5-5"));

    private static AssistantProperties properties(String provider, String model) {
        return new AssistantProperties(true, "test-key", model, "low", 6, 16000, 50, 90, Duration.ofSeconds(90),
                Duration.ofMinutes(15), true, "", provider);
    }

    /** Never called: only the translation to and from the API's JSON is used. */
    private static GeminiLanguageModel gemini(String model) {
        return new GeminiLanguageModel(RestClient.create("http://gemini.test"), properties("gemini", model));
    }

    private static Turn.ModelTurn claudeTurn(String response) throws Exception {
        return ClaudeLanguageModel.reply(SDK_JSON.readValue(response, Message.class)).turn();
    }

    private Turn.ModelTurn flashLiteTurn(String response) throws Exception {
        return flashLite.reply(JSON.readTree(response), "gemini-3.5-flash-lite").turn();
    }

    /** A first question answered with one lookup, as stored, then the question being asked now. */
    private static List<Turn> conversation(Turn.ModelTurn asks, String callId, Turn.ModelTurn answers) {
        return List.of(new Turn.Question("Còn bao nhiêu CUP-12OZ?", "ctx 1"), asks,
                new Turn.ToolResults(List.of(new ToolResult(callId, "{\"onHand\":40}", false))), answers,
                new Turn.Question("Còn LID-90 không?", "ctx 2"));
    }

    @Test
    @DisplayName("Claude to Gemini: the earlier answer reaches Gemini as one model text part, and nothing of Claude's "
            + "content goes with it")
    void claudeToGemini() throws Exception {
        List<Turn> stored = conversation(claudeTurn(CLAUDE_TOOL_USE), "toolu_01", claudeTurn(CLAUDE_ANSWER));
        assertThat(flash.contents(stored).get(3).path("role").asText())
                .as("sent as stored, Claude's answer would reach Gemini in a role it refuses").isEqualTo("assistant");

        ArrayNode contents = flash.contents(History.forModel(stored, flash::closingTurn));

        assertThat(contents).hasSize(3);
        assertThat(contents.get(1)).isEqualTo(JSON.readTree("""
                {"role":"model","parts":[{"text":"Còn 40 cái."}]}"""));
        assertThat(contents.get(2).path("parts").get(1).path("text").asText()).isEqualTo("Còn LID-90 không?");
        assertThat(contents.toString()).doesNotContain("signature", "thinking", "tool_use", "toolu_01", "assistant",
                "functionCall", "functionResponse");
    }

    @Test
    @DisplayName("flash-lite to flash: the earlier answer reaches flash without flash-lite's thought signatures or "
            + "function calls")
    void flashLiteToFlash() throws Exception {
        List<Turn> stored = conversation(flashLiteTurn(FLASH_LITE_TOOL_USE), "call_lite_1",
                flashLiteTurn(FLASH_LITE_ANSWER));
        assertThat(((Turn.ModelTurn) stored.get(3)).providerContent()).as("stored as flash-lite wrote it")
                .contains("Eu0CCuoC-flash-lite-2");

        ArrayNode contents = flash.contents(History.forModel(stored, flash::closingTurn));

        assertThat(contents).hasSize(3);
        assertThat(contents.get(1)).isEqualTo(JSON.readTree("""
                {"role":"model","parts":[{"text":"Còn 40 cái."}]}"""));
        assertThat(contents.toString()).doesNotContain("thoughtSignature", "flash-lite", "functionCall",
                "functionResponse", "call_lite_1");
    }

    @Test
    @DisplayName("Gemini to Claude: the earlier answer reaches Claude as one assistant text block, and nothing of "
            + "Gemini's content goes with it")
    void geminiToClaude() throws Exception {
        List<Turn> stored = conversation(flashLiteTurn(FLASH_LITE_TOOL_USE), "call_lite_1",
                flashLiteTurn(FLASH_LITE_ANSWER));

        List<MessageParam> messages = ClaudeLanguageModel.messages(History.forModel(stored, claude::closingTurn));

        assertThat(messages).extracting(MessageParam::role).containsExactly(MessageParam.Role.USER,
                MessageParam.Role.ASSISTANT, MessageParam.Role.USER);
        JsonNode json = SDK_JSON.readTree(SDK_JSON.writeValueAsString(messages));
        assertThat(json.get(1).path("content")).hasSize(1);
        assertThat(json.get(1).path("content").get(0).path("type").asText()).isEqualTo("text");
        assertThat(json.get(1).path("content").get(0).path("text").asText()).isEqualTo("Còn 40 cái.");
        assertThat(json.toString()).doesNotContain("thoughtSignature", "parts", "functionCall", "call_lite_1",
                "tool_use", "tool_result");
    }
}
