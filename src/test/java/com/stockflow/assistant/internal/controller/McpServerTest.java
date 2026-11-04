package com.stockflow.assistant.internal.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.assistant.internal.service.AssistantTools;
import com.stockflow.assistant.internal.service.ToolAvailability;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.web.CorrelationIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Model Context Protocol as {@link McpServer} speaks it, without HTTP or Spring: version negotiation, what is a
 * JSON-RPC error and what is a tool result, the size bound, the tool list as the caller's grants make it, and the log
 * line of a tool call. The secured end-to-end cases are {@code AssistantMcpIntegrationTest}.
 */
class McpServerTest {

    private static final CurrentUser SALES = new CurrentUser(UUID.randomUUID(), "sales", Set.of(Role.SALES_STAFF),
            Set.of(), DataScope.ALL, Set.of());

    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

    /** The application's mapper as {@code spring.jackson.default-property-inclusion: non_null} sets it up. */
    private final ObjectMapper json = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final AssistantTools tools = mock(AssistantTools.class);
    private final ToolAvailability availability = mock(ToolAvailability.class);
    private McpServer server;

    @BeforeEach
    void setUp() {
        when(tools.specs()).thenReturn(List.of(spec("findOrder"), spec("getStockLevels"), spec("searchKnowledge")));
        when(availability.specsFor(SALES)).thenReturn(List.of(spec("findOrder"), spec("searchKnowledge")));
        server = new McpServer(tools, availability, json, build("1.2.3"));
    }

    @ParameterizedTest(name = "asked {0}, answered {1}")
    @CsvSource({"2025-06-18, 2025-06-18", "2025-03-26, 2025-03-26", "2024-11-05, 2025-06-18",
            "2099-01-01, 2025-06-18"})
    @DisplayName("initialize answers the version asked for when the server speaks it, otherwise the newest it does")
    void negotiatesTheProtocolVersion(String asked, String answered) throws Exception {
        McpServer.Reply reply = post(request(1, "initialize", "{\"protocolVersion\":\"" + asked + "\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"test-client\",\"version\":\"1.0\"}}"));

        assertThat(reply.status()).isEqualTo(HttpStatus.OK);
        assertThat(written(reply).path("result").path("protocolVersion").asText()).isEqualTo(answered);
    }

    @Test
    @DisplayName("initialize offers tools only, a list that never changes on its own, and names the server")
    void initializeDescribesTheServer() throws Exception {
        JsonNode response = written(post(request(1, "initialize", "{\"protocolVersion\":\"2025-06-18\"}")));

        assertThat(response.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(response.path("id").asInt()).isEqualTo(1);
        assertThat(response.has("error")).isFalse();
        JsonNode result = response.path("result");
        assertThat(fieldNames(result.path("capabilities"))).containsExactly("tools");
        JsonNode listChanged = result.path("capabilities").path("tools").path("listChanged");
        assertThat(listChanged.isBoolean()).isTrue();
        assertThat(listChanged.booleanValue()).isFalse();
        assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("stockflow-assistant");
        assertThat(result.path("serverInfo").path("version").asText()).isEqualTo("1.2.3");
        assertThat(result.path("instructions").asText()).contains("Vietnam time", "total", "NOT_PERMITTED");
    }

    @Test
    @DisplayName("initialize without the client's protocol version is invalid params")
    void initializeNeedsAVersion() throws Exception {
        McpServer.Reply reply = post(request("\"init-1\"", "initialize", "{\"capabilities\":{}}"));

        assertThat(reply.status()).isEqualTo(HttpStatus.OK);
        JsonNode response = written(reply);
        assertThat(response.path("id").asText()).isEqualTo("init-1");
        assertThat(response.path("error").path("code").asInt()).isEqualTo(McpServer.INVALID_PARAMS);
        assertThat(response.has("result")).isFalse();
    }

    @Test
    @DisplayName("ping answers an empty object, and a string id comes back a string")
    void ping() throws Exception {
        JsonNode response = written(post(request("\"p-7\"", "ping", "{}")));

        assertThat(response.path("id").isTextual()).isTrue();
        assertThat(response.path("id").asText()).isEqualTo("p-7");
        assertThat(response.path("result").isObject()).isTrue();
        assertThat(response.path("result").isEmpty()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":3}}",
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":{}}",
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"error\":{\"code\":-1,\"message\":\"no\"}}"})
    @DisplayName("a notification, or a response to nothing this server asked, is accepted with 202 and no body")
    void acceptsWhatNeedsNoAnswer(String message) {
        McpServer.Reply reply = post(message, "2025-06-18");

        assertThat(reply.status()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(reply.body()).isNull();
        verify(tools, never()).execute(any());
    }

    @Test
    @DisplayName("a method this server does not have is -32601, answered 200 like every JSON-RPC response")
    void unknownMethod() throws Exception {
        McpServer.Reply reply = post(request(7, "resources/list", "{}"));

        assertThat(reply.status()).isEqualTo(HttpStatus.OK);
        JsonNode response = written(reply);
        assertThat(response.path("id").asInt()).isEqualTo(7);
        assertThat(response.path("error").path("code").asInt()).isEqualTo(McpServer.METHOD_NOT_FOUND);
        assertThat(response.path("error").path("message").asText()).contains("resources/list");
        assertThat(response.has("result")).isFalse();
    }

    static Stream<Arguments> notOneMessage() {
        return Stream.of(
                Arguments.of("not JSON", "{\"jsonrpc\":", McpServer.PARSE_ERROR),
                Arguments.of("an empty body", "", McpServer.PARSE_ERROR),
                Arguments.of("two messages in one body", PING + " " + PING, McpServer.PARSE_ERROR),
                Arguments.of("a member given twice",
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"method\":\"tools/call\"}",
                        McpServer.PARSE_ERROR),
                Arguments.of("a batch", "[" + PING + "]", McpServer.INVALID_REQUEST),
                Arguments.of("no jsonrpc member", "{\"id\":1,\"method\":\"ping\"}", McpServer.INVALID_REQUEST),
                Arguments.of("JSON-RPC 1.0", "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}",
                        McpServer.INVALID_REQUEST),
                Arguments.of("a string", "\"ping\"", McpServer.INVALID_REQUEST),
                Arguments.of("a null id", "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}",
                        McpServer.INVALID_REQUEST),
                Arguments.of("an object id", "{\"jsonrpc\":\"2.0\",\"id\":{\"a\":1},\"method\":\"ping\"}",
                        McpServer.INVALID_REQUEST),
                Arguments.of("a fractional id", "{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"ping\"}",
                        McpServer.INVALID_REQUEST),
                Arguments.of("neither a method nor a result", "{\"jsonrpc\":\"2.0\",\"id\":1}",
                        McpServer.INVALID_REQUEST));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notOneMessage")
    @DisplayName("a body that is not one JSON-RPC message is 400 with a JSON-RPC error, its id written as null")
    void refusesWhatIsNotOneMessage(String what, String body, int code) throws Exception {
        McpServer.Reply reply = post(body);

        assertThat(reply.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode response = written(reply);
        assertThat(response.path("error").path("code").asInt()).isEqualTo(code);
        assertThat(response.has("id")).as("the id member is written even when null").isTrue();
        assertThat(response.path("id").isNull()).isTrue();
        assertThat(response.has("result")).isFalse();
        verify(tools, never()).execute(any());
    }

    @Test
    @DisplayName("a method that is not a string is an invalid request, its id still echoed")
    void methodMustBeAString() throws Exception {
        McpServer.Reply reply = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":7}");

        assertThat(reply.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode response = written(reply);
        assertThat(response.path("id").asInt()).isEqualTo(1);
        assertThat(response.path("error").path("code").asInt()).isEqualTo(McpServer.INVALID_REQUEST);
    }

    @Test
    @DisplayName("params that are not an object are invalid params")
    void paramsMustBeAnObject() throws Exception {
        McpServer.Reply reply = post(request(3, "tools/list", "[1]"));

        assertThat(reply.status()).isEqualTo(HttpStatus.OK);
        assertThat(written(reply).path("error").path("code").asInt()).isEqualTo(McpServer.INVALID_PARAMS);
    }

    @Test
    @DisplayName("a body over the limit is 413 and is not read past it, whether or not its length was declared")
    void bodySizeIsBounded() throws Exception {
        McpServer.Reply declared = server.handle(new ByteArrayInputStream(new byte[0]),
                McpServer.MAX_REQUEST_BYTES + 1L, null, SALES);
        assertThat(declared.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(written(declared).path("error").path("code").asInt()).isEqualTo(McpServer.INVALID_REQUEST);

        byte[] oversized = (PING + " ".repeat(McpServer.MAX_REQUEST_BYTES)).getBytes(StandardCharsets.UTF_8);
        assertThat(server.handle(new ByteArrayInputStream(oversized), -1, null, SALES).status())
                .as("a chunked body with no declared length").isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);

        String atTheLimit = PING + " ".repeat(McpServer.MAX_REQUEST_BYTES - PING.length());
        assertThat(post(atTheLimit).status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("an MCP-Protocol-Version this server does not speak is 400, except on initialize, which negotiates it")
    void protocolVersionHeader() throws Exception {
        McpServer.Reply refused = post(request(4, "tools/list", "{}"), "2024-11-05");

        assertThat(refused.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode response = written(refused);
        assertThat(response.path("id").asInt()).isEqualTo(4);
        assertThat(response.path("error").path("code").asInt()).isEqualTo(McpServer.INVALID_REQUEST);
        assertThat(response.path("error").path("message").asText()).contains("2025-06-18");

        assertThat(post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", "1999-01-01").status())
                .as("a notification is a request of the transport too").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(request(5, "initialize", "{\"protocolVersion\":\"2025-06-18\"}"), "2099-01-01").status())
                .isEqualTo(HttpStatus.OK);
        assertThat(post(request(6, "tools/list", "{}"), "2025-03-26").status()).isEqualTo(HttpStatus.OK);
        assertThat(post(request(7, "tools/list", "{}"), null).status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("tools/list names only the tools the caller may use, each with the description and schema it declares")
    void listsTheGrantedTools() throws Exception {
        JsonNode result = written(post(request(2, "tools/list", "{}"))).path("result");

        assertThat(result.has("nextCursor")).as("one page").isFalse();
        List<String> names = new ArrayList<>();
        result.path("tools").forEach(tool -> names.add(tool.path("name").asText()));
        assertThat(names).containsExactly("findOrder", "searchKnowledge");
        JsonNode findOrder = result.path("tools").get(0);
        assertThat(findOrder.path("description").asText()).isEqualTo(spec("findOrder").description());
        assertThat(findOrder.path("inputSchema")).isEqualTo(json.valueToTree(spec("findOrder").inputSchema()));
        assertThat(findOrder.path("annotations").path("readOnlyHint").booleanValue()).isTrue();
        assertThat(findOrder.path("annotations").path("openWorldHint").isBoolean()).isTrue();
        assertThat(findOrder.path("annotations").path("openWorldHint").booleanValue()).isFalse();
    }

    @Test
    @DisplayName("tools/list takes no cursor: every tool comes in the one page")
    void noCursor() throws Exception {
        assertThat(written(post(request(2, "tools/list", "{\"cursor\":\"page-2\"}"))).path("error").path("code")
                .asInt()).isEqualTo(McpServer.INVALID_PARAMS);
    }

    @Test
    @DisplayName("tools/call runs the tool through AssistantTools and answers its result as one text block")
    void callsATool() throws Exception {
        String result = "{\"orderNumber\":\"SO-1\",\"status\":\"CONFIRMED\"}";
        when(tools.execute(any())).thenReturn(execution("findOrder", ToolOutcome.OK, result));

        JsonNode response = written(post(request(9, "tools/call",
                "{\"name\":\"findOrder\",\"arguments\":{\"orderNumber\":\"SO-1\"}}")));

        ArgumentCaptor<ToolCall> call = ArgumentCaptor.forClass(ToolCall.class);
        verify(tools).execute(call.capture());
        assertThat(call.getValue().name()).isEqualTo("findOrder");
        assertThat(json.readTree(call.getValue().arguments())).isEqualTo(json.readTree("{\"orderNumber\":\"SO-1\"}"));
        assertThat(response.path("id").asInt()).isEqualTo(9);
        JsonNode answer = response.path("result");
        assertThat(answer.path("isError").isBoolean()).as("written as isError, not error").isTrue();
        assertThat(answer.path("isError").booleanValue()).isFalse();
        assertThat(answer.path("content")).hasSize(1);
        assertThat(answer.path("content").get(0).path("type").asText()).isEqualTo("text");
        assertThat(answer.path("content").get(0).path("text").asText()).isEqualTo(result);
    }

    @Test
    @DisplayName("a tool not granted still runs through AssistantTools, whose guard refuses it: a result with isError "
            + "and the chat's NOT_PERMITTED text, not a protocol error")
    void aRefusalIsAResult() throws Exception {
        String refusal = "NOT_PERMITTED: Missing permission: inventory-stock-items:READ";
        when(tools.execute(any())).thenReturn(execution("getStockLevels", ToolOutcome.DENIED, refusal));

        JsonNode response = written(post(request(10, "tools/call",
                "{\"name\":\"getStockLevels\",\"arguments\":{\"sku\":\"EVAL-LID-90\"}}")));

        ArgumentCaptor<ToolCall> call = ArgumentCaptor.forClass(ToolCall.class);
        verify(tools).execute(call.capture());
        assertThat(call.getValue().name()).isEqualTo("getStockLevels");
        assertThat(response.has("error")).isFalse();
        assertThat(response.path("result").path("isError").booleanValue()).isTrue();
        assertThat(response.path("result").path("content").get(0).path("text").asText()).isEqualTo(refusal);
    }

    @Test
    @DisplayName("tools/call without arguments gives the tool an empty object, which it validates itself")
    void noArguments() {
        when(tools.execute(any())).thenReturn(execution("searchKnowledge", ToolOutcome.INVALID,
                "VALIDATION_FAILED: query is required"));

        post(request(8, "tools/call", "{\"name\":\"searchKnowledge\"}"));

        ArgumentCaptor<ToolCall> call = ArgumentCaptor.forClass(ToolCall.class);
        verify(tools).execute(call.capture());
        assertThat(call.getValue().arguments()).isEqualTo("{}");
    }

    static Stream<Arguments> badCalls() {
        return Stream.of(
                Arguments.of("no name", "{\"arguments\":{}}"),
                Arguments.of("a name that is not a string", "{\"name\":5}"),
                Arguments.of("arguments that are not an object", "{\"name\":\"findOrder\",\"arguments\":\"SO-1\"}"),
                Arguments.of("a tool that does not exist", "{\"name\":\"dropAllOrders\",\"arguments\":{}}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badCalls")
    @DisplayName("tools/call with parameters the protocol refuses is invalid params, and no tool runs")
    void refusesBadCalls(String what, String params) throws Exception {
        McpServer.Reply reply = post(request(11, "tools/call", params));

        assertThat(reply.status()).isEqualTo(HttpStatus.OK);
        assertThat(written(reply).path("error").path("code").asInt()).isEqualTo(McpServer.INVALID_PARAMS);
        verify(tools, never()).execute(any());
    }

    @Test
    @DisplayName("each tool call is logged with the tool, outcome, user and correlation id, never what was typed or "
            + "answered; a denied one at WARN")
    void logsEachCallWithoutWhatWasTyped() {
        Logger logger = (Logger) LoggerFactory.getLogger(McpServer.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-mcp-1");
        try {
            when(tools.execute(any())).thenReturn(
                    execution("findOrder", ToolOutcome.OK, "{\"note\":\"gọi Nguyễn Văn A\"}"),
                    execution("getStockLevels", ToolOutcome.DENIED,
                            "NOT_PERMITTED: Missing permission: inventory-stock-items:READ"));
            post(request(12, "tools/call", "{\"name\":\"findOrder\",\"arguments\":{\"orderNumber\":\"0903123456\"}}"));
            post(request(13, "tools/call", "{\"name\":\"getStockLevels\",\"arguments\":{\"sku\":\"EVAL-LID-90\"}}"));
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }

        assertThat(appender.list).hasSize(2);
        ILoggingEvent answered = appender.list.get(0);
        assertThat(answered.getLevel()).isEqualTo(Level.INFO);
        assertThat(answered.getFormattedMessage())
                .contains("findOrder", "OK", SALES.userId().toString(), "corr-mcp-1")
                .doesNotContain("0903123456", "Nguyễn");
        ILoggingEvent denied = appender.list.get(1);
        assertThat(denied.getLevel()).isEqualTo(Level.WARN);
        assertThat(denied.getFormattedMessage()).contains("getStockLevels", "DENIED", "corr-mcp-1")
                .doesNotContain("EVAL-LID-90", "inventory-stock-items");
    }

    // ------------------------------------------------------------------------------------------------------------

    private McpServer.Reply post(String body) {
        return post(body, null);
    }

    private McpServer.Reply post(String body, String protocolVersion) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return server.handle(new ByteArrayInputStream(bytes), bytes.length, protocolVersion, SALES);
    }

    /** The reply's body as a client receives it. */
    private JsonNode written(McpServer.Reply reply) throws Exception {
        return json.readTree(json.writeValueAsString(reply.body()));
    }

    private static String request(Object id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    private static ToolSpec spec(String name) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", "What the user typed");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("text", property));
        schema.put("required", List.of("text"));
        schema.put("additionalProperties", false);
        return new ToolSpec(name, "What " + name + " answers", schema);
    }

    private static ToolExecution execution(String tool, ToolOutcome outcome, String result) {
        return new ToolExecution(new ToolCall("call-1", tool, "{}"), outcome, result, 3);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BuildProperties> build(String version) {
        Properties entries = new Properties();
        entries.setProperty("version", version);
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new BuildProperties(entries));
        return provider;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
