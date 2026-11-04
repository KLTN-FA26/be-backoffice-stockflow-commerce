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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The Gemini adapter's translation to and from the API's JSON, and its retries, without a network: what a reply
 * becomes, what a history is sent as, what a tool declaration looks like, when a failed call is tried again, and which
 * error a user gets when it is not.
 */
class GeminiLanguageModelTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AssistantProperties PROPERTIES = new AssistantProperties(true, "test-key",
            "gemini-3.5-flash", "low", 6, 16000, 50, 90, Duration.ofSeconds(90), Duration.ofMinutes(15), true, "",
            "gemini");
    private static final String PATH = "http://gemini.test/v1beta/models/gemini-3.5-flash:generateContent";

    /** A reply the way the API returns one: a function call with an id and a thought signature. */
    private static final String TOOL_REPLY = """
            {"candidates":[{"content":{"role":"model","parts":[
               {"functionCall":{"name":"getStockLevels","args":{"sku":"CUP-12OZ"},"id":"call_201852"},
                "thoughtSignature":"Eu0CCuoCAWkUfRMl-signature"}]},
              "finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":1200,"candidatesTokenCount":20,"thoughtsTokenCount":81,
                              "cachedContentTokenCount":900},
             "modelVersion":"gemini-3.6-flash"}""";

    /**
     * The free tier's daily refusal in the shape Google has also sent it: a per-day quota beside a {@code RetryInfo}
     * of half a minute, which read alone looks like the per-minute quota.
     */
    private static final String DAY_SPENT_SHORT_RETRY = """
            {"error":{"code":429,"status":"RESOURCE_EXHAUSTED",
              "message":"You exceeded your current quota, please check your plan and billing details.",
              "details":[
                {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
                  {"quotaMetric":"generativelanguage.googleapis.com/generate_content_free_tier_requests",
                   "quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier",
                   "quotaDimensions":{"location":"global","model":"gemini-3.5-flash"},"quotaValue":"20"}]},
                {"@type":"type.googleapis.com/google.rpc.Help","links":[
                  {"description":"Learn more about Gemini API quotas",
                   "url":"https://ai.google.dev/gemini-api/docs/rate-limits"}]},
                {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"33s"}]}}""";

    /** The daily refusal as the playground received it on 2026-10-28: "retry after 74900s", until midnight Pacific. */
    private static final String DAY_SPENT_LONG_RETRY = """
            {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"quota",
              "details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"74900s"}]}}""";

    /** The per-minute quota: it frees up within the minute, so it is waited for. */
    private static final String MINUTE_SPENT = """
            {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"quota",
              "details":[
                {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
                  {"quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]},
                {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"3s"}]}}""";

    private final List<Long> slept = new ArrayList<>();

    private GeminiLanguageModel model(RestClient client) {
        return new GeminiLanguageModel(client, PROPERTIES, slept::add);
    }

    private GeminiLanguageModel offline() {
        return model(RestClient.create("http://gemini.test"));
    }

    @Test
    @DisplayName("a 200 that is not JSON is the provider failing: unavailable, which a user may retry")
    void aBodyThatIsNotJson() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(PATH)).andRespond(withSuccess("<html>proxy</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> model(builder.build()).next(new LanguageModel.Request("p", List.of(),
                List.of(new Turn.Question("q", "ctx"))))).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
    }

    private JsonNode error(String body) {
        return GeminiCall.error(body);
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

    @ParameterizedTest
    @ValueSource(strings = {"MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL", "TOO_MANY_TOOL_CALLS", "STOP", "OTHER"})
    @DisplayName("a candidate with neither text nor a call is OTHER whatever its finish reason, never an empty "
            + "END_TURN")
    void silenceIsNotAnAnswer(String finish) throws Exception {
        LanguageModel.Reply reply = offline().reply(JSON.readTree("""
                {"candidates":[{"content":{"role":"model"},"finishReason":"%s",
                  "finishMessage":"Malformed function call: print(default_api.findExpiringLots(days=30))"}]}"""
                .formatted(finish)), "m");

        assertThat(reply.stopReason()).isEqualTo(LanguageModel.StopReason.OTHER);
        assertThat(reply.turn().text()).isNull();
        assertThat(reply.turn().toolCalls()).isEmpty();
        // Still a valid model content, so the next request's history replays.
        assertThat(JSON.readTree(reply.turn().providerContent()).path("parts")).hasSize(1);
    }

    @Test
    @DisplayName("a STOP holding only thinking answered nothing either")
    void thinkingIsNotAnAnswer() throws Exception {
        LanguageModel.Reply reply = offline().reply(JSON.readTree("""
                {"candidates":[{"content":{"role":"model","parts":[{"text":"weighing it","thought":true}]},
                  "finishReason":"STOP"}]}"""), "m");

        assertThat(reply.stopReason()).isEqualTo(LanguageModel.StopReason.OTHER);
        assertThat(reply.turn().text()).isNull();
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
    @DisplayName("a per-minute 429 waits what RetryInfo asks and tries again; the key travels in a header")
    void retriesQuota() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(PATH)).andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body(MINUTE_SPENT));
        server.expect(requestTo(PATH)).andRespond(withSuccess(TOOL_REPLY, MediaType.APPLICATION_JSON));

        LanguageModel.Reply reply = model(builder.build()).next(request());

        assertThat(reply.stopReason()).isEqualTo(LanguageModel.StopReason.TOOL_USE);
        assertThat(slept).containsExactly(3000L);
        server.verify();
    }

    @Test
    @DisplayName("without RetryInfo a 429 waits out the per-minute quota; a 5xx retries soon; a longer wait is not "
            + "taken")
    void retryDelays() {
        assertThat(GeminiLanguageModel.retryDelay(error("{}"), 429, 0)).contains(Duration.ofSeconds(15));
        assertThat(GeminiLanguageModel.retryDelay(error("{}"), 429, 1)).contains(Duration.ofSeconds(30));
        assertThat(GeminiLanguageModel.retryDelay(error("not json"), 503, 1)).contains(Duration.ofSeconds(4));
        assertThat(GeminiLanguageModel.retryDelay(error("""
                {"error":{"details":[{"retryDelay":"42s"}]}}"""), 429, 0)).contains(Duration.ofSeconds(42));
        assertThat(GeminiLanguageModel.retryDelay(error("""
                {"error":{"details":[{"retryDelay":"-5s"}]}}"""), 503, 0)).contains(Duration.ofSeconds(2));
        assertThat(GeminiLanguageModel.retryDelay(error(DAY_SPENT_LONG_RETRY), 503, 0)).isEmpty();
    }

    @Test
    @DisplayName("a 429 is a spent quota when QuotaFailure names a per-day quota or RetryInfo asks for over a minute")
    void quotaSpent() {
        assertThat(GeminiLanguageModel.quotaSpent(error(DAY_SPENT_SHORT_RETRY))).isTrue();
        assertThat(GeminiLanguageModel.quotaSpent(error(DAY_SPENT_LONG_RETRY))).isTrue();
        assertThat(GeminiLanguageModel.quotaSpent(error(MINUTE_SPENT))).isFalse();
        assertThat(GeminiLanguageModel.quotaSpent(error("{}"))).isFalse();
        assertThat(GeminiLanguageModel.quotaSpent(error("not json"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {DAY_SPENT_LONG_RETRY, DAY_SPENT_SHORT_RETRY})
    @DisplayName("a spent daily quota fails at once as ASSISTANT_QUOTA_EXHAUSTED: nothing is waited for or retried")
    void dailyQuota(String body) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(PATH))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                        .body(body));

        assertThatThrownBy(() -> model(builder.build()).next(request()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED);
                    assertThat(e.errorCode().httpStatus()).isEqualTo(429);
                    assertThat(e.errorCode().isRetryable()).as("asking again meets the same refusal").isFalse();
                    // A 4xx message reaches the client: nothing of Google's own words is in it.
                    assertThat(e.getMessage()).doesNotContain("You exceeded", "FreeTier", "gemini");
                });
        assertThat(slept).isEmpty();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404})
    @DisplayName("400, 401, 403 and 404 are the key, the model or our request: ASSISTANT_MISCONFIGURED, never retried")
    void misconfigured(int status) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.once(), requestTo(PATH))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":" + status + ",\"status\":\"INVALID_ARGUMENT\","
                                + "\"message\":\"API key not valid. Please pass a valid API key.\"}}"));

        assertThatThrownBy(() -> model(builder.build()).next(request()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_MISCONFIGURED);
                    assertThat(e.errorCode().isRetryable()).isFalse();
                    assertThat(e.getMessage()).doesNotContain("API key not valid");
                });
        assertThat(slept).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("a 5xx is tried again twice, then ASSISTANT_UNAVAILABLE, which a user may retry")
    void serverErrors() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.times(3), requestTo(PATH))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\",\"message\":\"overloaded\"}}"));

        assertThatThrownBy(() -> model(builder.build()).next(request()))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
                    assertThat(e.errorCode().isRetryable()).isTrue();
                });
        assertThat(slept).containsExactly(2000L, 4000L);
        server.verify();
    }

    @Test
    @DisplayName("a timeout is tried again twice, then ASSISTANT_UNAVAILABLE")
    void timeouts() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.times(3), requestTo(PATH))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> model(builder.build()).next(request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
        assertThat(slept).containsExactly(2000L, 4000L);
        server.verify();
    }

    @Test
    @DisplayName("the log line keeps Google's status and words, but not a key the message quotes back")
    void errorSummaryHidesKeys() {
        // Built at run time, so no key-shaped literal sits in the repository for a secret scanner to flag.
        String key = "AIza" + "x".repeat(35);
        String summary = GeminiLanguageModel.errorSummary(error("""
                {"error":{"code":403,"status":"PERMISSION_DENIED",
                  "message":"Consumer 'api_key:%s' has been suspended."}}""".formatted(key)));

        assertThat(summary).startsWith("PERMISSION_DENIED").contains("has been suspended", "[key]")
                .doesNotContain(key);
        assertThat(GeminiLanguageModel.errorSummary(error("not json"))).isEqualTo("(unreadable body)");
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

    @Test
    @DisplayName("the answer's last call offers no function, keeps the declarations, and ends with the note")
    void lastCall() throws Exception {
        GeminiLanguageModel model = offline();
        LanguageModel.Request last = new LanguageModel.Request("system", request().tools(), request().history(),
                false, "Note from the system: answer now.");

        JsonNode body = model.body(last);

        assertThat(body.path("toolConfig").path("functionCallingConfig").path("mode").asText()).isEqualTo("NONE");
        assertThat(body.path("tools").path(0).path("functionDeclarations")).hasSize(1);
        JsonNode parts = body.path("contents").path(0).path("parts");
        assertThat(parts.get(parts.size() - 1).path("text").asText()).isEqualTo("Note from the system: answer now.");
        assertThat(model.body(request()).path("toolConfig").path("functionCallingConfig").path("mode").asText())
                .isEqualTo("AUTO");
    }

    private static LanguageModel.Request request() {
        return new LanguageModel.Request("system", List.of(new ToolSpec("getStockLevels", "stock",
                ToolSchema.object().string("sku", "SKU").build())),
                List.of(new Turn.Question("Còn bao nhiêu?", "ctx")));
    }
}
