package com.stockflow.assistant.internal.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.Sleeper;
import com.anthropic.core.http.Headers;
import com.anthropic.core.http.HttpClient;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.errors.UnexpectedStatusCodeException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.services.blocking.MessageService;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The adapter's translation to and from the provider's types, without a network: what a reply becomes, what a
 * history is sent as, what a tool definition looks like on the wire, and which error a refusal becomes.
 */
class ClaudeLanguageModelTest {

    /** What the API says when it refuses; it must reach the log only, never the exception a client may read. */
    private static final String PROVIDER_WORDS = "Your credit balance is too low to access the Anthropic API.";

    private static final AssistantProperties PROPERTIES = new AssistantProperties(true, "sk-ant-" + "test",
            "claude-opus-5-5", "low", 6, 16000, 50, 90, Duration.ofSeconds(90), Duration.ofMinutes(15), true, "",
            "anthropic");

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
    @DisplayName("the answer's last call keeps the tools declared but lets none be called, and the note comes after "
            + "the tool results")
    void lastCall() throws Exception {
        Turn.ModelTurn asksForTools = ClaudeLanguageModel.reply(
                ObjectMappers.jsonMapper().readValue(RESPONSE, Message.class)).turn();
        List<Turn> history = List.of(new Turn.Question("Còn bao nhiêu cốc?", "today is 2026-10-25"), asksForTools,
                new Turn.ToolResults(List.of(new ToolResult("toolu_01", "{\"warehouses\":[]}", false))));
        List<ToolSpec> tools = List.of(new ToolSpec("getStockLevels", "stock",
                ToolSchema.object().string("sku", "SKU").build()));
        ClaudeLanguageModel model = new ClaudeLanguageModel(null, new AssistantProperties(true, "key",
                "claude-opus-5-5", "low", 6, 16000, 50, 90, Duration.ofSeconds(90), Duration.ofMinutes(15), false, "",
                "anthropic"));

        MessageCreateParams last = model.params(new LanguageModel.Request("system", tools, history, false,
                "Note from the system: answer now."));
        MessageCreateParams ordinary = model.params(new LanguageModel.Request("system", tools, history));

        assertThat(last.toolChoice()).hasValueSatisfying(choice -> assertThat(choice.isNone()).isTrue());
        assertThat(last.tools()).hasValueSatisfying(declared -> assertThat(declared).hasSize(1));
        JsonNode json = ObjectMappers.jsonMapper().readTree(ObjectMappers.jsonMapper()
                .writeValueAsString(last.messages()));
        JsonNode closing = json.get(2).path("content");
        assertThat(closing.get(0).path("type").asText()).isEqualTo("tool_result");
        assertThat(closing.get(closing.size() - 1).path("text").asText())
                .isEqualTo("Note from the system: answer now.");
        assertThat(ordinary.toolChoice()).isEmpty();
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

    @Test
    @DisplayName("an end_turn holding nothing but thinking did not answer: OTHER, so END_TURN always has text")
    void silenceIsNotAnAnswer() throws Exception {
        LanguageModel.Reply silent = ClaudeLanguageModel.reply(ObjectMappers.jsonMapper().readValue("""
                {"id":"msg_02","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[{"type":"thinking","thinking":"","signature":"EqQBCkYIBxgCKkB-signature"}],
                 "stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":900,"output_tokens":12,"cache_read_input_tokens":0,
                          "cache_creation_input_tokens":0}}""", Message.class));
        LanguageModel.Reply answered = ClaudeLanguageModel.reply(ObjectMappers.jsonMapper().readValue("""
                {"id":"msg_03","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[{"type":"text","text":"Còn 40 cái."}],
                 "stop_reason":"end_turn","stop_sequence":null,
                 "usage":{"input_tokens":900,"output_tokens":12,"cache_read_input_tokens":0,
                          "cache_creation_input_tokens":0}}""", Message.class));

        assertThat(silent.stopReason()).isEqualTo(LanguageModel.StopReason.OTHER);
        assertThat(silent.turn().text()).isNull();
        assertThat(answered.stopReason()).isEqualTo(LanguageModel.StopReason.END_TURN);
        assertThat(answered.turn().text()).isEqualTo("Còn 40 cái.");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 402, 403, 404})
    @DisplayName("the request, the key, the credit, the grant or the model refused: ASSISTANT_MISCONFIGURED, "
            + "not retryable")
    void misconfigured(int status) {
        BusinessException failure = ClaudeLanguageModel.failure(refusal(status, Headers.builder().build()));

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.ASSISTANT_MISCONFIGURED);
        assertThat(failure.errorCode().isRetryable()).isFalse();
        assertThat(failure.getMessage()).doesNotContain(PROVIDER_WORDS);
    }

    @Test
    @DisplayName("a 429 asking to wait over a minute is a spent quota; a shorter one, or none, is a burst worth "
            + "retrying")
    void rateLimits() {
        // Header names are case-insensitive: HTTP/2 sends them lower-case, HTTP/1.1 as the server wrote them.
        assertThat(ClaudeLanguageModel.failure(rateLimited("Retry-After", "3600")).errorCode())
                .isEqualTo(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED);
        assertThat(ClaudeLanguageModel.failure(rateLimited("retry-after-ms", "90000")).errorCode())
                .isEqualTo(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED);
        assertThat(ClaudeLanguageModel.failure(rateLimited("retry-after", "20")).errorCode())
                .isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
        assertThat(ClaudeLanguageModel.failure(rateLimited("retry-after", "Wed, 28 Oct 2026 07:28:00 GMT")).errorCode())
                .as("an HTTP date is not read as a wait").isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
        assertThat(ClaudeLanguageModel.failure(RateLimitException.builder().headers(Headers.builder().build())
                .body(body()).build()).errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);

        BusinessException spent = ClaudeLanguageModel.failure(rateLimited("retry-after", "3600"));
        assertThat(spent.errorCode().httpStatus()).isEqualTo(429);
        assertThat(spent.errorCode().isRetryable()).isFalse();
        // A 4xx message is what the client reads.
        assertThat(spent.getMessage()).doesNotContain(PROVIDER_WORDS);
    }

    @Test
    @DisplayName("a 5xx or 529 overloaded, after the SDK's retries, is ASSISTANT_UNAVAILABLE, which a user may retry")
    void serverErrors() {
        for (int status : new int[] {500, 503, 529}) {
            BusinessException failure = ClaudeLanguageModel.failure(refusal(status, Headers.builder().build()));
            assertThat(failure.errorCode()).as("HTTP %d", status).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE);
            assertThat(failure.errorCode().isRetryable()).isTrue();
        }
    }

    @Test
    @DisplayName("next() maps what the SDK throws: a refused key is MISCONFIGURED, a timeout UNAVAILABLE")
    void nextMapsWhatTheSdkThrows() {
        MessageService messages = mock(MessageService.class);
        AnthropicClient client = mock(AnthropicClient.class);
        when(client.messages()).thenReturn(messages);
        // One stubbing for both calls: re-stubbing would call the mock, which already throws.
        when(messages.create(any(MessageCreateParams.class))).thenThrow(
                UnauthorizedException.builder().headers(Headers.builder().build()).body(body()).build(),
                new AnthropicIoException("Request timed out"));
        ClaudeLanguageModel model = new ClaudeLanguageModel(client, PROPERTIES);

        assertThatThrownBy(() -> model.next(request())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_MISCONFIGURED));
        assertThatThrownBy(() -> model.next(request())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
    }

    @Test
    @DisplayName("through the SDK: a 429 asking to wait an hour fails at once as a spent quota, with no sleep and no "
            + "second request")
    void aSpentQuotaIsNotSleptOn() {
        AtomicInteger calls = new AtomicInteger();
        List<Duration> slept = new ArrayList<>();

        try (ClaudeLanguageModel model = new ClaudeLanguageModel(client(answering(429, "3600", calls), slept),
                PROPERTIES)) {
            assertThatThrownBy(() -> model.next(request())).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED));
        }
        assertThat(calls).hasValue(1);
        assertThat(slept).isEmpty();
    }

    @Test
    @DisplayName("through the SDK: a 429 asking for seconds is waited for and tried again, then ASSISTANT_UNAVAILABLE")
    void aBurstIsWaitedFor() {
        AtomicInteger calls = new AtomicInteger();
        List<Duration> slept = new ArrayList<>();

        try (ClaudeLanguageModel model = new ClaudeLanguageModel(client(answering(429, "2", calls), slept),
                PROPERTIES)) {
            assertThatThrownBy(() -> model.next(request())).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ASSISTANT_UNAVAILABLE));
        }
        assertThat(calls).hasValue(AssistantProperties.MAX_RETRIES + 1);
        assertThat(slept).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    /** The real SDK client and its retries, over an HTTP layer that answers without a network. */
    private static AnthropicClient client(HttpClient http, List<Duration> slept) {
        Sleeper recording = new Sleeper() {
            @Override
            public void sleep(Duration duration) {
                slept.add(duration);
            }

            @Override
            public CompletableFuture<Void> sleepAsync(Duration duration) {
                slept.add(duration);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void close() {
            }
        };
        return AnthropicOkHttpClient.builder()
                .apiKey("sk-ant-" + "test")
                .maxRetries(AssistantProperties.MAX_RETRIES)
                .sleeper(new ClaudeLanguageModel.BoundedSleeper(recording))
                .build()
                .withOptions(options -> options.httpClient(http));
    }

    private static HttpClient answering(int status, String retryAfter, AtomicInteger calls) {
        return new HttpClient() {
            @Override
            public HttpResponse execute(HttpRequest request, RequestOptions options) {
                calls.incrementAndGet();
                return new HttpResponse() {
                    @Override
                    public int statusCode() {
                        return status;
                    }

                    @Override
                    public Headers headers() {
                        return Headers.builder().put("content-type", "application/json")
                                .put("retry-after", retryAfter).build();
                    }

                    @Override
                    public InputStream body() {
                        return new ByteArrayInputStream(("{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\","
                                + "\"message\":\"" + PROVIDER_WORDS + "\"}}").getBytes(StandardCharsets.UTF_8));
                    }

                    @Override
                    public void close() {
                    }
                };
            }

            @Override
            public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
                return CompletableFuture.completedFuture(execute(request, options));
            }

            @Override
            public void close() {
            }
        };
    }

    private static AnthropicServiceException refusal(int status, Headers headers) {
        return UnexpectedStatusCodeException.builder().statusCode(status).headers(headers).body(body()).build();
    }

    private static AnthropicServiceException rateLimited(String header, String value) {
        return RateLimitException.builder().headers(Headers.builder().put(header, value).build()).body(body())
                .build();
    }

    private static JsonValue body() {
        return JsonValue.from(Map.of("type", "error",
                "error", Map.of("type", "invalid_request_error", "message", PROVIDER_WORDS)));
    }

    private static LanguageModel.Request request() {
        return new LanguageModel.Request("system", List.of(new ToolSpec("getStockLevels", "stock",
                ToolSchema.object().string("sku", "SKU").build())),
                List.of(new Turn.Question("Còn bao nhiêu?", "ctx")));
    }
}
