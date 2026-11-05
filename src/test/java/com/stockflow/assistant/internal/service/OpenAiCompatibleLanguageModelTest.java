package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolResult;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The OpenAI-compatible adapter, as a local Ollama serves it, without a network: what a history is sent as, what a
 * reply becomes, and which error a user gets when a call fails.
 */
class OpenAiCompatibleLanguageModelTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PATH = "http://ollama.test/v1/chat/completions";

    private final List<Long> slept = new ArrayList<>();

    private static AssistantProperties properties(String key) {
        return new AssistantProperties(true, key, "qwen3:8b", "low", 6, 16000, 50, 90, Duration.ofSeconds(90),
                Duration.ofMinutes(15), true, "", "openai", "", "",
                new AssistantProperties.OpenAiCompatible("http://ollama.test/v1", key), DataPolicy.LOCAL_ONLY);
    }

    private OpenAiCompatibleLanguageModel model(RestClient client, String key) {
        return new OpenAiCompatibleLanguageModel(client, properties(key), slept::add);
    }

    private OpenAiCompatibleLanguageModel offline() {
        return model(RestClient.create("http://ollama.test/v1"), "");
    }

    private static final ToolSpec STOCK = new ToolSpec("getStockLevels", "Stock of one SKU per warehouse",
            Map.of("type", "object", "properties", Map.of("sku", Map.of("type", "string")), "required",
                    List.of("sku")));

    @Test
    @DisplayName("a request carries the system prompt, the history, the tools and tool_choice; none on the last call")
    void body() {
        LanguageModel.Request request = new LanguageModel.Request("You are the assistant.", List.of(STOCK),
                List.of(new Turn.Question("Còn bao nhiêu CUP-12OZ?", "Role: SALES_STAFF")), true, null);
        JsonNode body = offline().body(request);

        assertThat(body.path("model").asText()).isEqualTo("qwen3:8b");
        assertThat(body.path("stream").asBoolean(true)).isFalse();
        assertThat(body.path("max_tokens").asInt()).as("the server's room, not 16000").isEqualTo(4096);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("You are the assistant.");
        assertThat(body.path("messages").get(1).path("content").asText())
                .isEqualTo("Role: SALES_STAFF\n\nCòn bao nhiêu CUP-12OZ?");
        JsonNode tool = body.path("tools").get(0);
        assertThat(tool.path("type").asText()).isEqualTo("function");
        assertThat(tool.path("function").path("name").asText()).isEqualTo("getStockLevels");
        assertThat(tool.path("function").path("parameters").path("required").get(0).asText()).isEqualTo("sku");
        assertThat(body.path("tool_choice").asText()).isEqualTo("auto");

        JsonNode last = offline().body(new LanguageModel.Request("p", List.of(STOCK), request.history(), false,
                "Note from the system, not written by the user: answer from what you found."));
        assertThat(last.path("tool_choice").asText()).isEqualTo("none");
        JsonNode note = last.path("messages").get(last.path("messages").size() - 1);
        assertThat(note.path("role").asText()).isEqualTo("user");
        assertThat(note.path("content").asText()).as("after the question, the note joins its message")
                .startsWith("Role: SALES_STAFF").endsWith("Note from the system, not written by the user: answer "
                        + "from what you found.");

        assertThat(offline().body(new LanguageModel.Request("p", List.of(), request.history())).has("tools"))
                .isFalse();
    }

    @Test
    @DisplayName("a tool call replays as the assistant message, and each result as a tool message naming its call")
    void toolRoundTrip() {
        OpenAiCompatibleLanguageModel model = offline();
        LanguageModel.Reply reply = model.reply(read("""
                {"model":"qwen3:8b","choices":[{"finish_reason":"tool_calls","message":{"role":"assistant",
                  "content":"","tool_calls":[{"id":"call_1","type":"function",
                  "function":{"name":"getStockLevels","arguments":"{\\"sku\\":\\"CUP-12OZ\\"}"}}]}}],
                 "usage":{"prompt_tokens":1500,"completion_tokens":30}}"""), "qwen3:8b");

        assertThat(reply.stopReason()).isEqualTo(LanguageModel.StopReason.TOOL_USE);
        assertThat(reply.turn().toolCalls()).containsExactly(new ToolCall("call_1", "getStockLevels",
                "{\"sku\":\"CUP-12OZ\"}"));
        assertThat(reply.usage().inputTokens()).isEqualTo(1500);
        assertThat(reply.usage().outputTokens()).isEqualTo(30);

        JsonNode messages = model.messages(List.of(new Turn.Question("q", "ctx"), reply.turn(),
                new Turn.ToolResults(List.of(new ToolResult("call_1", "{\"onHand\":40}", false)))), null);
        assertThat(messages.get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(1).path("tool_calls").get(0).path("id").asText()).isEqualTo("call_1");
        assertThat(messages.get(2).path("role").asText()).isEqualTo("tool");
        assertThat(messages.get(2).path("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(messages.get(2).path("content").asText()).isEqualTo("{\"onHand\":40}");
    }

    @Test
    @DisplayName("a note after the question goes into the question's message, so the roles alternate as strict chat "
            + "templates require; after a tool's result it is a user message of its own")
    void aNoteAfterTheQuestionJoinsIt() {
        JsonNode afterQuestion = offline().messages(List.of(new Turn.Question("q", "ctx")), "look it up");
        assertThat(afterQuestion).hasSize(1);
        assertThat(afterQuestion.get(0).path("content").asText()).isEqualTo("ctx\n\nq\n\nlook it up");

        JsonNode afterResult = offline().messages(List.of(new Turn.Question("q", "ctx"),
                new Turn.ToolResults(List.of(new ToolResult("call_1", "{}", false)))), "answer now");
        assertThat(afterResult).hasSize(3);
        assertThat(afterResult.get(2).path("role").asText()).isEqualTo("user");
        assertThat(afterResult.get(2).path("content").asText()).isEqualTo("answer now");
    }

    @Test
    @DisplayName("a call without an id gets one, stored in the replayed message too; object arguments become a string")
    void missingIdsAndObjectArguments() {
        LanguageModel.Reply reply = offline().reply(read("""
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":null,"tool_calls":[
                  {"function":{"name":"getStockLevels","arguments":{"sku":"CUP-12OZ"}}}]}}]}"""), "qwen3:8b");

        ToolCall call = reply.turn().toolCalls().getFirst();
        assertThat(call.id()).startsWith(OpenAiCompatibleLanguageModel.LOCAL_ID_PREFIX);
        assertThat(read(call.arguments()).path("sku").asText()).isEqualTo("CUP-12OZ");
        assertThat(read(reply.turn().providerContent()).path("tool_calls").get(0).path("id").asText())
                .isEqualTo(call.id());
        assertThat(reply.stopReason()).isEqualTo(LanguageModel.StopReason.TOOL_USE);
        assertThat(reply.model()).isEqualTo("qwen3:8b");
    }

    @Test
    @DisplayName("an answer is its visible text: a <think> block is dropped from the text and from what is replayed")
    void thinkingIsNotAnswer() {
        LanguageModel.Reply reply = offline().reply(read("""
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                  "content":"<think>The user asks about stock.</think>\\n\\nCòn 40 cái ở kho HCM."}}]}"""), "m");

        assertThat(reply.stopReason()).isEqualTo(LanguageModel.StopReason.END_TURN);
        assertThat(reply.turn().text()).isEqualTo("Còn 40 cái ở kho HCM.");
        assertThat(read(reply.turn().providerContent()).path("content").asText()).isEqualTo("Còn 40 cái ở kho HCM.");
    }

    @Test
    @DisplayName("nothing written is no answer; a cut-off reply and a filtered one say so")
    void stopReasons() {
        assertThat(offline().reply(read("""
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"<think>hmm</think>"}}]}
                """), "m").stopReason()).isEqualTo(LanguageModel.StopReason.OTHER);
        assertThat(offline().reply(read("""
                {"choices":[{"finish_reason":"length","message":{"role":"assistant","content":"Còn"}}]}"""), "m")
                .stopReason()).isEqualTo(LanguageModel.StopReason.MAX_TOKENS);
        assertThat(offline().reply(read("""
                {"choices":[{"finish_reason":"content_filter","message":{"role":"assistant","content":""}}]}"""), "m")
                .stopReason()).isEqualTo(LanguageModel.StopReason.REFUSAL);
        assertThat(offline().reply(read("{\"choices\":[]}"), "m").stopReason())
                .isEqualTo(LanguageModel.StopReason.OTHER);
    }

    @Test
    @DisplayName("the key, when there is one, is a bearer token; a local server without one is sent none")
    void keys() {
        RestClient.Builder withKey = RestClient.builder().baseUrl("http://ollama.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(withKey).build();
        server.expect(requestTo(PATH)).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer vllm-token"))
                .andRespond(withSuccess(answer(), MediaType.APPLICATION_JSON));
        model(withKey.build(), "vllm-token").next(request());
        server.verify();

        RestClient.Builder keyless = RestClient.builder().baseUrl("http://ollama.test/v1");
        MockRestServiceServer local = MockRestServiceServer.bindTo(keyless).build();
        local.expect(requestTo(PATH)).andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess(answer(), MediaType.APPLICATION_JSON));
        assertThat(model(keyless.build(), "").next(request()).turn().text()).isEqualTo("Còn 40 cái.");
        local.verify();
    }

    @Test
    @DisplayName("a model the server has not pulled is a misconfiguration, at once")
    void unknownModel() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ollama.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(PATH)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"model 'qwen3:8b' not found\"}"));

        assertThatThrownBy(() -> model(builder.build(), "").next(request()))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.errorCode()).isEqualTo(ErrorCode.ASSISTANT_MISCONFIGURED));
        server.verify();
        assertThat(slept).isEmpty();
    }

    @Test
    @DisplayName("a server that is not running is tried again, then unavailable")
    void notRunning() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ollama.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.times(3), requestTo(PATH))
                .andRespond(withException(new ConnectException("Connection refused")));

        assertThatThrownBy(() -> model(builder.build(), "").next(request()))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
        server.verify();
        assertThat(slept).containsExactly(2000L, 4000L);
    }

    @Test
    @DisplayName("a 5xx is tried again and may succeed; a 429 asking for an hour is a spent quota")
    void retriesAndQuota() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ollama.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(PATH)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(PATH)).andRespond(withSuccess(answer(), MediaType.APPLICATION_JSON));
        assertThat(model(builder.build(), "").next(request()).turn().text()).isEqualTo("Còn 40 cái.");
        server.verify();

        RestClient.Builder hosted = RestClient.builder().baseUrl("http://ollama.test/v1");
        MockRestServiceServer quota = MockRestServiceServer.bindTo(hosted).build();
        quota.expect(ExpectedCount.once(), requestTo(PATH)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "3600").body("{\"error\":{\"message\":\"Rate limit for sk-abcdefghijklmnopqrstu\"}}"));
        assertThatThrownBy(() -> model(hosted.build(), "").next(request()))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.errorCode()).isEqualTo(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED));
        quota.verify();
    }

    @Test
    @DisplayName("an error message is logged with keys blanked, whichever shape the server sends it in")
    void errorSummary() {
        OpenAiCompatibleCall call = new OpenAiCompatibleCall(RestClient.create("http://x"), "", millis -> { }, "");
        assertThat(call.errorSummary("{\"error\":{\"message\":\"bad key sk-abcdefghijklmnopqrstuvwx\"}}"))
                .isEqualTo("bad key [key]");
        assertThat(call.errorSummary("{\"error\":\"model 'm' not found\"}")).isEqualTo("model 'm' not found");
        assertThat(call.errorSummary("<html>502</html>")).isEqualTo("(no error message)");
        assertThat(OpenAiCompatibleCall.retryAfter("Wed, 21 Oct 2026 07:28:00 GMT")).isEmpty();
        assertThat(OpenAiCompatibleCall.retryAfter("17")).contains(Duration.ofSeconds(17));
    }

    @Test
    @DisplayName("arguments that are not JSON reach the tool as written, to be refused, but replay as {}")
    void brokenArguments() {
        LanguageModel.Reply reply = offline().reply(read("""
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"","tool_calls":[
                  {"id":"call_9","type":"function","function":{"name":"getStockLevels",
                   "arguments":"{\\"sku\\": CUP}"}}]}}]}"""), "m");

        assertThat(reply.turn().toolCalls().getFirst().arguments()).isEqualTo("{\"sku\": CUP}");
        assertThat(read(reply.turn().providerContent()).path("tool_calls").get(0).path("function").path("arguments")
                .asText()).isEqualTo("{}");
    }

    @Test
    @DisplayName("max_tokens is the smaller of the assistant's room and the server's")
    void maxTokens() {
        assertThat(offline().body(request()).path("max_tokens").asInt()).isEqualTo(4096);
        AssistantProperties roomy = new AssistantProperties(true, "", "m", "low", 6, 2048, 50, 90,
                Duration.ofSeconds(90), Duration.ofMinutes(15), true, "", "openai", "", "",
                new AssistantProperties.OpenAiCompatible("http://ollama.test/v1", "", 8192), DataPolicy.CLOUD_ALLOWED);
        assertThat(OpenAiCompatibleLanguageModel.maxTokens(roomy)).isEqualTo(2048);
    }

    @Test
    @DisplayName("a Retry-After of an hour is never waited for, even on a 5xx: unavailable at once")
    void noLongWaits() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ollama.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(PATH))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "3600"));

        assertThatThrownBy(() -> model(builder.build(), "").next(request()))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
        server.verify();
        assertThat(slept).isEmpty();
    }

    @Test
    @DisplayName("insufficient_quota is a spent quota though it names no wait; a 422 is a misconfiguration")
    void quotaAndRefusedRequests() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ollama.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(PATH)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"message\":\"You exceeded your quota\",\"code\":\"insufficient_quota\"}}"));
        assertThatThrownBy(() -> model(builder.build(), "").next(request()))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.errorCode()).isEqualTo(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED));
        server.verify();

        RestClient.Builder refused = RestClient.builder().baseUrl("http://ollama.test/v1");
        MockRestServiceServer unprocessable = MockRestServiceServer.bindTo(refused).build();
        unprocessable.expect(ExpectedCount.once(), requestTo(PATH)).andRespond(withStatus(
                HttpStatus.UNPROCESSABLE_ENTITY).body("{\"error\":{\"message\":\"prompt too long\"}}"));
        assertThatThrownBy(() -> model(refused.build(), "").next(request()))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.errorCode()).isEqualTo(ErrorCode.ASSISTANT_MISCONFIGURED));
        assertThat(slept).isEmpty();
    }

    @Test
    @DisplayName("a 200 that is not JSON is the server failing: unavailable")
    void notJson() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ollama.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(PATH)).andRespond(withSuccess("<html>proxy page</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> model(builder.build(), "").next(request()))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
    }

    @Test
    @DisplayName("a closing turn replays as a plain assistant message")
    void closingTurn() {
        Turn.ModelTurn closing = offline().closingTurn("Tôi chưa tra được.");
        assertThat(read(closing.providerContent()).path("role").asText()).isEqualTo("assistant");
        assertThat(read(closing.providerContent()).path("content").asText()).isEqualTo("Tôi chưa tra được.");
        assertThat(closing.toolCalls()).isEmpty();
    }

    private static LanguageModel.Request request() {
        return new LanguageModel.Request("p", List.of(), List.of(new Turn.Question("q", "ctx")));
    }

    private static String answer() {
        return """
                {"model":"qwen3:8b","choices":[{"finish_reason":"stop",
                  "message":{"role":"assistant","content":"Còn 40 cái."}}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5}}""";
    }

    private static JsonNode read(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception broken) {
            throw new IllegalStateException(broken);
        }
    }
}
