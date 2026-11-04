package com.stockflow.assistant.internal.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.stockflow.assistant.internal.controller.dto.McpResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.idempotency.IdempotencyFilter;
import com.stockflow.common.idempotency.IdempotencyStore;
import com.stockflow.common.ratelimit.RateLimit;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.Role;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The HTTP side of the MCP endpoint: its own guard with the chat's scope, the chat's rate-limit bucket, the back office
 * first, an Origin the CORS policy does not allow refused, the request handed to {@link McpServer} as it came, and no
 * {@code Idempotency-Key} needed. The full stack is in {@code AssistantMcpIntegrationTest}, where the idempotency
 * filter is off as in every test context.
 */
class AssistantMcpControllerTest {

    private static final CurrentUser PLANNER = new CurrentUser(UUID.randomUUID(), "planner",
            Set.of(Role.INVENTORY_PLANNER), Set.of(), DataScope.ALL, Set.of());
    private static final CurrentUser CUSTOMER = new CurrentUser(UUID.randomUUID(), "customer", Set.of(Role.CUSTOMER),
            Set.of(), DataScope.ALL, Set.of());

    private static AssistantMcpController controller(McpServer server) {
        return new AssistantMcpController(server);
    }

    private static McpOriginFilter originFilter() {
        CorsConfiguration policy = new CorsConfiguration();
        policy.setAllowedOriginPatterns(List.of("http://localhost:*", "https://*.stockflow.vn"));
        UrlBasedCorsConfigurationSource cors = new UrlBasedCorsConfigurationSource();
        cors.registerCorsConfiguration("/**", policy);
        return new McpOriginFilter(cors, new ObjectMapper());
    }

    @Test
    @DisplayName("an MCP client draws on the chat's rate-limit bucket with the chat's capacity: switching clients buys "
            + "no second allowance")
    void oneBudgetWithTheChat() {
        RateLimit chat = annotation(AssistantController.class, "ask", RateLimit.class);
        RateLimit mcp = annotation(AssistantMcpController.class, "message", RateLimit.class);

        // A bucket shared under two capacities would refill at two rates, whichever endpoint wrote it last.
        assertThat(mcp.bucket()).isNotBlank();
        assertThat(List.of(mcp.bucket(), mcp.limit(), mcp.perSeconds(), mcp.key()))
                .isEqualTo(List.of(chat.bucket(), chat.limit(), chat.perSeconds(), chat.key()));
    }

    @Test
    @DisplayName("the endpoint carries its own permission, with the chat's action and scope, on a public method, so "
            + "the proxy applies it; the token endpoints carry the same")
    void guardedApartFromTheChat() {
        RequiresPermission chat = annotation(AssistantController.class, "ask", RequiresPermission.class);
        RequiresPermission mcp = annotation(AssistantMcpController.class, "message", RequiresPermission.class);

        assertThat(List.of(mcp.resource(), mcp.action(), mcp.scope()))
                .isEqualTo(List.of(AssistantResources.MCP, chat.action(), chat.scope()))
                .isEqualTo(List.of("assistant-mcp", Action.CREATE, DataScope.OWN));
        assertThat(Modifier.isPublic(method(AssistantMcpController.class, "message").getModifiers())).isTrue();
        for (String handler : List.of("create", "mine", "revoke")) {
            RequiresPermission token = annotation(AssistantMcpTokenController.class, handler, RequiresPermission.class);
            assertThat(List.of(token.resource(), token.action(), token.scope()))
                    .isEqualTo(List.of(AssistantResources.MCP, Action.CREATE, DataScope.OWN));
            assertThat(Modifier.isPublic(method(AssistantMcpTokenController.class, handler).getModifiers())).isTrue();
        }
    }

    @Test
    @DisplayName("an Origin the CORS policy does not allow is 403 with a JSON-RPC error without an id, and the request "
            + "goes no further, even when it is the request's own host (DNS rebinding) or 'null'")
    void aForeignOriginIsRefused() throws Exception {
        McpOriginFilter filter = originFilter();
        for (String origin : List.of("http://attacker.example", "null")) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/assistant/mcp");
            request.setServerName("attacker.example");
            request.addHeader(HttpHeaders.ORIGIN, origin);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<ServletRequest> passed = new AtomicReference<>();

            filter.doFilter(request, response, (req, res) -> passed.set(req));

            assertThat(passed.get()).as(origin).isNull();
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
            var body = new ObjectMapper().readTree(response.getContentAsString(StandardCharsets.UTF_8));
            assertThat(body.path("error").path("code").asInt()).isEqualTo(McpServer.INVALID_REQUEST);
            assertThat(body.has("id")).isTrue();
            assertThat(body.path("id").isNull()).isTrue();
        }
    }

    @Test
    @DisplayName("an allowed Origin, no Origin at all, a preflight and any other path pass the Origin filter")
    void otherRequestsPass() throws Exception {
        McpOriginFilter filter = originFilter();
        MockHttpServletRequest panel = new MockHttpServletRequest("POST", "/api/v1/assistant/mcp");
        panel.addHeader(HttpHeaders.ORIGIN, "http://localhost:3100");
        MockHttpServletRequest cli = new MockHttpServletRequest("POST", "/api/v1/assistant/mcp");
        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/api/v1/assistant/mcp");
        preflight.addHeader(HttpHeaders.ORIGIN, "http://attacker.example");
        MockHttpServletRequest elsewhere = new MockHttpServletRequest("POST", "/api/v1/assistant/conversations");
        elsewhere.addHeader(HttpHeaders.ORIGIN, "http://attacker.example");

        for (MockHttpServletRequest request : List.of(panel, cli, preflight, elsewhere)) {
            AtomicReference<ServletRequest> passed = new AtomicReference<>();
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> passed.set(req));
            assertThat(passed.get()).as("%s %s", request.getMethod(), request.getRequestURI()).isSameAs(request);
        }
    }

    @Test
    @DisplayName("a portal customer is refused before the body is read")
    void customersAreRefusedFirst() {
        McpServer server = mock(McpServer.class);
        AssistantMcpController controller = controller(server);

        assertThatThrownBy(() -> controller.message(CUSTOMER, new MockHttpServletRequest("POST", "/api/v1/assistant/mcp")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(server);
    }

    @Test
    @DisplayName("the body, its declared length and the protocol version header reach the protocol, whose status is the "
            + "response's: 202 with no body for a notification")
    void handsTheRequestOver() throws Exception {
        McpServer server = mock(McpServer.class);
        McpResponse pong = new McpResponse("2.0", IntNode.valueOf(1), Map.of(), null);
        when(server.handle(any(), anyLong(), any(), any()))
                .thenReturn(new McpServer.Reply(HttpStatus.OK, pong), McpServer.Reply.ACCEPTED);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/assistant/mcp");
        request.setContent("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}".getBytes(StandardCharsets.UTF_8));
        request.addHeader("mcp-protocol-version", "2025-06-18");
        AssistantMcpController controller = controller(server);

        ResponseEntity<McpResponse> answered = controller.message(PLANNER, request);
        ResponseEntity<McpResponse> accepted = controller.message(PLANNER, request);

        verify(server, times(2)).handle(any(), eq((long) request.getContentLength()),
                eq("2025-06-18"), eq(PLANNER));
        assertThat(answered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(answered.getBody()).isSameAs(pong);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody()).isNull();
    }

    @Test
    @DisplayName("a JSON-RPC POST with no Idempotency-Key, as MCP clients send it, passes the idempotency filter "
            + "untouched: nothing is claimed, nothing is buffered")
    void passesTheIdempotencyFilterUntouched() throws Exception {
        IdempotencyStore store = mock(IdempotencyStore.class);
        IdempotencyFilter filter = new IdempotencyFilter(store, mock(CurrentUserProvider.class), new ObjectMapper(),
                Clock.systemUTC());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/assistant/mcp");
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.addHeader(HttpHeaders.ACCEPT, "application/json, text/event-stream");
        request.setContent("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> seenRequest = new AtomicReference<>();
        AtomicReference<ServletResponse> seenResponse = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            seenRequest.set(req);
            seenResponse.set(res);
        });

        assertThat(seenRequest.get()).isSameAs(request);
        assertThat(seenResponse.get()).as("the handler writes to the real response").isSameAs(response);
        verifyNoInteractions(store);
    }

    private static <A extends Annotation> A annotation(Class<?> controller, String name, Class<A> type) {
        A found = method(controller, name).getAnnotation(type);
        assertThat(found).as("%s.%s carries @%s", controller.getSimpleName(), name, type.getSimpleName()).isNotNull();
        return found;
    }

    private static Method method(Class<?> controller, String name) {
        return Arrays.stream(controller.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no handler " + controller.getSimpleName() + "." + name));
    }
}
