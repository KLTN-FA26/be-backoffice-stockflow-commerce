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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The Gemini adapter's translation to and from the API's JSON, and its retries, without a network: what a reply
 * becomes, what a history is sent as, what a tool declaration looks like, and when a failed call is tried again.
 */
class GeminiLanguageModelTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AssistantProperties PROPERTIES = new AssistantProperties(true, "test-key",
            "gemini-3.5-flash", "low", 6, 16000, 50, 90, Duration.ofSeconds(90), Duration.ofMinutes(15), true, "",
            "gemini");

    /** A reply the way the API returns one: a function call with an id and a thought signature. */
    private static final String TOOL_REPLY = """
            {"candidates":[{"content":{"role":"model","parts":[
               {"functionCall":{"name":"getStockLevels","args":{"sku":"CUP-12OZ"},"id":"call_201852"},
                "thoughtSignature":"Eu0CCuoCAWkUfRMl-signature"}]},
              "finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":1200,"candidatesTokenCount":20,"thoughtsTokenCount":81,
                              "cachedContentTokenCount":900},
             "modelVersion":"gemini-3.6-flash"}""";

    private final List<Long> slept = new ArrayList<>();

    private GeminiLanguageModel model(RestClient client) {
        return new GeminiLanguageModel(client, PROPERTIES, slept::add);
    }

    private GeminiLanguageModel offline() {
        return model(RestClient.create("http://gemini.test"));
    }

    @Test
    @DisplayName("a function call becomes TOOL_USE with the API's id, and the stored turn keeps its signature")
    void toolReply() throws Exception {
        LanguageModel.Reply reply = offline().reply(JSON.readTree(TOOL_REPLY), "gemini-3.5-flash");

        assertThat(reply.stopReason()).isEqualTo(LanguageModel.StopReason.TOOL_USE);
        assertThat(reply.turn().toolCalls())
                .containsExactly(new ToolCall("call_201852", "getStockLevels", "{\"sku\":\"CUP-12OZ\"}"));
        assertThat(reply.turn().text()).isNull();
        assertThat(reply.turn().providerContent()).contains("Eu0CCuoCAWkUfRMl-signature");
        assertThat(reply.model()).isEqualTo("gemini-3.6-flash");
        // Cached prompt tokens are counted once, as cache reads; thinking counts as output.
        assertThat(reply.usage()).isEqualTo(new LanguageModel.Usage(300, 101, 900, 0));
    }

    @Test
    @DisplayName("text answers END_TURN without the thought parts; blocked and cut-off replies are told apart")
    void finishReasons() throws Exception {
        LanguageModel.Reply answer = offline().reply(JSON.readTree("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"draft","thought":true},
                  {"text":"Còn 40 cái."}]},"finishReason":"STOP"}]}"""), "gemini-3.5-flash");
        assertThat(answer.stopReason()).isEqualTo(LanguageModel.StopReason.END_TURN);
        assertThat(answer.turn().text()).isEqualTo("Còn 40 cái.");
        assertThat(answer.model()).isEqualTo("gemini-3.5-flash");

        assertThat(offline().reply(JSON.readTree("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"Một phần"}]},"finishReason":"MAX_TOKENS"}]}"""),
                "m").stopReason()).isEqualTo(LanguageModel.StopReason.MAX_TOKENS);

        LanguageModel.Reply safety = offline().reply(JSON.readTree("""
                {"candidates":[{"finishReason":"SAFETY"}]}"""), "m");
        assertThat(safety.stopReason()).isEqualTo(LanguageModel.StopReason.REFUSAL);
        // No parts came back; the stored turn is still a valid model content.
        assertThat(JSON.readTree(safety.turn().providerContent()).path("parts")).hasSize(1);

        assertThat(offline().reply(JSON.readTree("""
                {"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"}}"""), "m").stopReason())
                .isEqualTo(LanguageModel.StopReason.REFUSAL);
    }

    @Test
    @DisplayName("history: model turns verbatim, responses named after their calls, user turns merged")
    void contents() throws Exception {
        GeminiLanguageModel model = offline();
        LanguageModel.Reply asked = model.reply(JSON.readTree(TOOL_REPLY), "m");
        ToolCall local = new ToolCall(GeminiLanguageModel.LOCAL_ID_PREFIX + "0-x", "findOrder", "{}");
        List<Turn> history = List.of(
                new Turn.Question("Còn bao nhiêu CUP-12OZ?", "ctx"),
                asked.turn(),
                new Turn.ToolResults(List.of(new ToolResult("call_201852", "{\"onHand\":40}", false))),
                new Turn.ModelTurn("{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"findOrder\","
                        + "\"args\":{}}}]}", null, List.of(local)),
                new Turn.ToolResults(List.of(new ToolResult(local.id(), "NOT_FOUND: no such order", true))),
                // The answer failed; the next question follows the tool results directly.
                new Turn.Question("Thử lại", "ctx2"));

        JsonNode contents = model.contents(history);

        // user, model, user (response), model (the second call), user (its response merged with the next question)
        assertThat(contents).hasSize(5);
        assertThat(contents.path(0).path("role").asText()).isEqualTo("user");
        assertThat(contents.path(0).path("parts")).hasSize(2);
        assertThat(contents.path(1).path("parts").path(0).path("thoughtSignature").asText())
                .isEqualTo("Eu0CCuoCAWkUfRMl-signature");
        JsonNode first = contents.path(2).path("parts").path(0).path("functionResponse");
        assertThat(first.path("id").asText()).isEqualTo("call_201852");
        assertThat(first.path("name").asText()).isEqualTo("getStockLevels");
        assertThat(first.path("response").path("result").asText()).isEqualTo("{\"onHand\":40}");
        assertThat(contents.path(3).path("role").asText()).isEqualTo("model");
        JsonNode merged = contents.path(4).path("parts");
        assertThat(merged).hasSize(3);
        assertThat(merged.path(0).path("functionResponse").has("id")).as("a locally made id is never sent").isFalse();
        assertThat(merged.path(0).path("functionResponse").path("response").path("error").asText())
                .startsWith("NOT_FOUND");
        assertThat(merged.path(2).path("text").asText()).isEqualTo("Thử lại");
    }

    @Test
    @DisplayName("tool schemas become Gemini's OpenAPI subset: upper-case types, no additionalProperties")
    void declarations() {
        ToolSpec spec = new ToolSpec("findPurchaseOrders", "POs", ToolSchema.object()
                .choice("status", "Status", List.of("ANY", "DRAFT"))
                .strings("skus", "SKUs")
                .integer("limit", "How many")
                .build());

        JsonNode declaration = offline().declaration(spec);

        JsonNode parameters = declaration.path("parameters");
        assertThat(parameters.path("type").asText()).isEqualTo("OBJECT");
        assertThat(parameters.has("additionalProperties")).isFalse();
        assertThat(parameters.path("properties").path("status").path("enum")).hasSize(2);
        assertThat(parameters.path("properties").path("skus").path("items").path("type").asText()).isEqualTo("STRING");
        assertThat(parameters.path("properties").path("limit").path("type").asText()).isEqualTo("INTEGER");
        assertThat(parameters.path("required")).hasSize(3);
    }

    @Test
    @DisplayName("a 429 waits what RetryInfo asks and tries again; the key travels in a header")
    void retriesQuota() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String path = "http://gemini.test/v1beta/models/gemini-3.5-flash:generateContent";
        server.expect(requestTo(path)).andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"quota",
                          "details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"3s"}]}}"""));
        server.expect(requestTo(path)).andRespond(withSuccess(TOOL_REPLY, MediaType.APPLICATION_JSON));

        LanguageModel.Reply reply = model(builder.build()).next(request());

        assertThat(reply.stopReason()).isEqualTo(LanguageModel.StopReason.TOOL_USE);
        assertThat(slept).containsExactly(3000L);
        server.verify();
    }

    @Test
    @DisplayName("without RetryInfo a 429 waits out the per-minute quota; a 5xx retries soon; a daily quota is not waited for")
    void retryDelays() {
        GeminiLanguageModel model = offline();
        assertThat(model.retryDelay("{}", 429, 0)).contains(Duration.ofSeconds(15));
        assertThat(model.retryDelay("{}", 429, 1)).contains(Duration.ofSeconds(30));
        assertThat(model.retryDelay("not json", 503, 1)).contains(Duration.ofSeconds(4));
        assertThat(model.retryDelay("""
                {"error":{"details":[{"retryDelay":"42s"}]}}""", 429, 0)).contains(Duration.ofSeconds(42));
        // GenerateRequestsPerDayPerProjectPerModel-FreeTier answers "retry after 74900s".
        assertThat(model.retryDelay("""
                {"error":{"details":[{"retryDelay":"74900s"}]}}""", 429, 0)).isEmpty();
    }

    @Test
    @DisplayName("a daily quota fails at once instead of waiting twice for the same 429")
    void dailyQuota() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://gemini.test/v1beta/models/gemini-3.5-flash:generateContent"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"quota",
                          "details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"74900s"}]}}"""));

        assertThatThrownBy(() -> model(builder.build()).next(request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
        assertThat(slept).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("a 400 is our bug: no retry, ASSISTANT_UNAVAILABLE, and the provider's words stay in the log")
    void rejected() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://gemini.test/v1beta/models/gemini-3.5-flash:generateContent"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"message\":\"bad schema\"}}"));

        assertThatThrownBy(() -> model(builder.build()).next(request()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
                    assertThat(e.getMessage()).doesNotContain("bad schema");
                });
        assertThat(slept).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("a closing turn is a model content holding only the text")
    void closingTurn() throws Exception {
        Turn.ModelTurn closing = offline().closingTurn("Xin hỏi hẹp hơn.");

        JsonNode content = JSON.readTree(closing.providerContent());
        assertThat(content.path("role").asText()).isEqualTo("model");
        assertThat(content.path("parts").path(0).path("text").asText()).isEqualTo("Xin hỏi hẹp hơn.");
        assertThat(closing.toolCalls()).isEmpty();
    }

    private static LanguageModel.Request request() {
        return new LanguageModel.Request("system", List.of(new ToolSpec("getStockLevels", "stock",
                ToolSchema.object().string("sku", "SKU").build())),
                List.of(new Turn.Question("Còn bao nhiêu?", "ctx")));
    }
}
