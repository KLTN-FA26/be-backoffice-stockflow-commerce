package com.stockflow.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.assistant.internal.service.AssistantTools;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.stockflow.support.DemoData.WAREHOUSE_HCM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The MCP endpoint (contract C8) end to end, with the real security chain, the real permission aspect, the real tools
 * and the real rate limiter: another AI client is offered exactly the tools the signed-in user may use, each call is
 * enforced as the chat's is, and nothing answers a client without a token.
 *
 * <p>Only the token decoder is replaced, so this class shares the application context of the other secured tests.
 * Every case signs in as a user of its own: the rate-limit bucket is per user, and one case spends it on purpose.</p>
 */
@SecuredIntegrationTest
class AssistantMcpIntegrationTest {

    private static final String MCP = "/api/v1/assistant/mcp";
    private static final String BIN = "HCM-A01-1-B";
    private static final String VERSION = "2025-06-18";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;
    @Autowired AssistantTools tools;

    private SecuredMvc http;
    private String sku;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        sku = "MCP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "MCP fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "MCP fixture");
            jdbc.update("""
                    insert into inventory.stock_item (id, sku, location_code, expiry_date, received_at, on_hand,
                        reserved, status, version, created_at) values (?, ?, ?, ?, ?, 10, 0, 'AVAILABLE', 0, now())""",
                    Identifiers.newId(), sku, BIN, Date.valueOf(LocalDate.now().plusYears(1)),
                    Timestamp.from(Instant.parse("2026-01-02T00:00:00Z")));
        });
    }

    @Test
    @DisplayName("initialize answers the version asked for and offers tools only, with no session; the initialized "
            + "notification is 202 with no body; a GET opens no stream")
    void handshake() throws Exception {
        UUID sales = staff("SALES_STAFF");

        http.asRole(Role.SALES_STAFF, sales, mcp(initialize(VERSION)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist("Mcp-Session-Id"))
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.result.protocolVersion").value(VERSION))
                .andExpect(jsonPath("$.result.capabilities.tools.listChanged").value(false))
                .andExpect(jsonPath("$.result.serverInfo.name").value("stockflow-assistant"))
                .andExpect(jsonPath("$.error").doesNotExist());

        http.asRole(Role.SALES_STAFF, sales, mcp("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                        .header("MCP-Protocol-Version", VERSION))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        http.asRole(Role.SALES_STAFF, sales, get(MCP).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("tools/list is the caller's own: SALES_STAFF sees findOrder and not getStockLevels, a planner the "
            + "reverse, each exactly what the capabilities panel grants, with the description and schema the tool "
            + "declares")
    void theToolListIsTheCallersOwn() throws Exception {
        UUID sales = staff("SALES_STAFF");
        UUID planner = staff("INVENTORY_PLANNER");

        JsonNode salesTools = toolList(Role.SALES_STAFF, sales);
        JsonNode plannerTools = toolList(Role.INVENTORY_PLANNER, planner);

        assertThat(names(salesTools)).contains("findOrder").doesNotContain("getStockLevels")
                .containsExactlyElementsOf(granted(Role.SALES_STAFF, sales));
        assertThat(names(plannerTools)).contains("getStockLevels").doesNotContain("findOrder")
                .containsExactlyElementsOf(granted(Role.INVENTORY_PLANNER, planner));
        Map<String, ToolSpec> specs = tools.specs().stream()
                .collect(Collectors.toMap(ToolSpec::name, Function.identity()));
        for (JsonNode tool : salesTools) {
            ToolSpec spec = specs.get(tool.path("name").asText());
            assertThat(tool.path("description").asText()).isEqualTo(spec.description());
            assertThat(tool.path("inputSchema")).isEqualTo(json.valueToTree(spec.inputSchema()));
            assertThat(tool.path("inputSchema").path("type").asText()).isEqualTo("object");
            assertThat(tool.path("annotations").path("readOnlyHint").asBoolean()).isTrue();
        }
    }

    @Test
    @DisplayName("a granted tool answers under the caller's own permissions, as the text the chat's model receives")
    void aGrantedToolAnswers() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");

        JsonNode result = call(Role.INVENTORY_PLANNER, planner, "getStockLevels", "{\"sku\":\"" + sku + "\"}");

        assertThat(result.path("isError").isBoolean()).isTrue();
        assertThat(result.path("isError").booleanValue()).isFalse();
        assertThat(text(result)).contains(sku, "\"warehouseCode\":\"HCM\"", "\"onHand\":10");
    }

    @Test
    @DisplayName("every MCP call is recorded as an MCP run of its user, with no conversation; a refused one too")
    void callsAreRecorded() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        UUID sales = staff("SALES_STAFF");

        call(Role.INVENTORY_PLANNER, planner, "getStockLevels", "{\"sku\":\"" + sku + "\"}");
        call(Role.SALES_STAFF, sales, "getStockLevels", "{\"sku\":\"" + sku + "\"}");

        assertThat(jdbc.queryForList("""
                SELECT outcome FROM assistant.tool_call
                WHERE channel = 'MCP' AND user_id = ? AND conversation_id IS NULL AND tool_name = 'getStockLevels'""",
                String.class, planner)).containsExactly("OK");
        assertThat(jdbc.queryForList("SELECT outcome FROM assistant.tool_call WHERE channel = 'MCP' AND user_id = ?",
                String.class, sales)).containsExactly("DENIED");
    }

    @Test
    @DisplayName("a JSON-RPC id of any length is answered and recorded: the record's call id is the server's own")
    void aLongRequestIdIsRecorded() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        String id = "\"" + "x".repeat(300) + "\"";

        String body = http.asRole(Role.INVENTORY_PLANNER, planner, mcp("{\"jsonrpc\":\"2.0\",\"id\":" + id
                        + ",\"method\":\"tools/call\",\"params\":{\"name\":\"getStockLevels\",\"arguments\":{\"sku\":\""
                        + sku + "\"}}}").header("MCP-Protocol-Version", VERSION))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(json.readTree(body).path("result").path("isError").booleanValue()).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT call_id FROM assistant.tool_call WHERE channel = 'MCP' AND user_id = ?""", String.class,
                planner)).startsWith("mcp-").hasSizeLessThan(100);
    }

    @Test
    @DisplayName("a tool the caller may not use, called anyway, is refused by its own guard: isError, NOT_PERMITTED "
            + "naming the permission, and none of the data")
    void aToolNotGrantedIsRefused() throws Exception {
        UUID sales = staff("SALES_STAFF");

        JsonNode result = call(Role.SALES_STAFF, sales, "getStockLevels", "{\"sku\":\"" + sku + "\"}");

        assertThat(result.path("isError").booleanValue()).isTrue();
        assertThat(text(result)).startsWith("NOT_PERMITTED").contains("inventory-stock-items:READ")
                .doesNotContain("onHand", "HCM");
    }

    @Test
    @DisplayName("a manager limited to HCM is not offered company-wide figures and gets none by calling for them: the "
            + "tool's own scope rule holds over MCP as in the chat")
    void aWarehouseLimitedCallerGetsNoCompanyWideFigures() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", WAREHOUSE_HCM);

        assertThat(names(toolList(Role.WAREHOUSE_MANAGER, manager))).contains("getStockLevels")
                .doesNotContain("findLowStock");
        JsonNode result = call(Role.WAREHOUSE_MANAGER, manager, "findLowStock", "{\"limit\":10}");

        assertThat(result.path("isError").booleanValue()).isFalse();
        JsonNode answer = json.readTree(text(result));
        assertThat(answer.path("skus").isArray()).isTrue();
        assertThat(answer.path("skus")).isEmpty();
        assertThat(answer.path("note").asText()).contains("company-wide");
    }

    @Test
    @DisplayName("a method the server does not have is the JSON-RPC error -32601, with the request's id")
    void anUnknownMethodIsMethodNotFound() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");

        http.asRole(Role.INVENTORY_PLANNER, planner, mcp(request(7, "resources/list", "{}"))
                        .header("MCP-Protocol-Version", VERSION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.error.code").value(-32601))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    @DisplayName("without a token nothing answers: 401 before the endpoint runs")
    void noTokenNoTools() throws Exception {
        http.anonymous(mcp(initialize(VERSION))).andExpect(status().isUnauthorized());
        http.anonymous(mcp(request(2, "tools/list", "{}"))).andExpect(status().isUnauthorized());
        http.anonymous(mcp(request(3, "tools/call",
                        "{\"name\":\"searchKnowledge\",\"arguments\":{\"query\":\"custom printed return\"}}")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a portal customer cannot reach the tools")
    void customersAreRefused() throws Exception {
        UUID account = tx.execute(s -> ReferenceRows.user(entityManager));

        http.asCustomer(account, mcp(request(2, "tools/list", "{}"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a page of another site cannot drive it, token or not: the shared CORS policy refuses its Origin "
            + "(the transport's defence against DNS rebinding)")
    void aPageOfAnotherSiteIsRefused() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");

        http.asRole(Role.INVENTORY_PLANNER, planner, mcp(request(1, "ping", "{}")).header("Origin",
                        "https://evil.example"))
                .andExpect(status().isForbidden());
        http.asRole(Role.INVENTORY_PLANNER, planner, mcp(request(2, "ping", "{}")).header("Origin",
                        "http://localhost:3100"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a body over 64 KiB is 413 with a JSON-RPC error, and nothing runs")
    void aBodyTooLargeIsRefused() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        String query = "x".repeat(70_000);

        http.asRole(Role.INVENTORY_PLANNER, planner, mcp(request(4, "tools/call",
                        "{\"name\":\"searchKnowledge\",\"arguments\":{\"query\":\"" + query + "\"}}")))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value(-32600));
    }

    @Test
    @DisplayName("an MCP client and the chat draw on one rate-limit budget: once MCP requests have spent it, the "
            + "chat's next question is refused too")
    void oneBudgetWithTheChat() throws Exception {
        UUID planner = staff("INVENTORY_PLANNER");
        int answered = 0;
        boolean limited = false;
        for (int i = 0; i < 40 && !limited; i++) {
            MockHttpServletResponse response = http.asRole(Role.INVENTORY_PLANNER, planner,
                    mcp(request(i, "ping", "{}"))).andReturn().getResponse();
            if (response.getStatus() == 200) {
                answered++;
            } else {
                assertThat(response.getStatus()).isEqualTo(429);
                limited = true;
            }
        }
        // A token bucket: a slow machine may refill a token or two while the requests run.
        assertThat(limited).as("a 429 within 40 rapid requests").isTrue();
        assertThat(answered).isBetween(10, 20);

        http.asRole(Role.INVENTORY_PLANNER, planner,
                        post("/api/v1/assistant/conversations/" + UUID.randomUUID() + "/messages")
                                .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Xin chào\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"));
    }

    // ------------------------------------------------------------------------------------------------------------

    /** A POST as an MCP client sends it; no Idempotency-Key, which the endpoint does not need. */
    private static MockHttpServletRequestBuilder mcp(String body) {
        return post(MCP).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(body);
    }

    private static String request(int id, String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    private static String initialize(String version) {
        return request(1, "initialize", "{\"protocolVersion\":\"" + version + "\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"integration-test\",\"version\":\"1.0\"}}");
    }

    private JsonNode toolList(Role role, UUID user) throws Exception {
        return result(http.asRole(role, user, mcp(request(2, "tools/list", "{}"))
                .header("MCP-Protocol-Version", VERSION))).path("tools");
    }

    private JsonNode call(Role role, UUID user, String tool, String arguments) throws Exception {
        return result(http.asRole(role, user, mcp(request(3, "tools/call",
                "{\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}")).header("MCP-Protocol-Version", VERSION)));
    }

    /** The JSON-RPC result of a request that must have succeeded at the protocol level. */
    private JsonNode result(ResultActions actions) throws Exception {
        String body = actions.andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        JsonNode response = json.readTree(body);
        assertThat(response.has("error")).as("a JSON-RPC result, not an error: %s", body).isFalse();
        return response.path("result");
    }

    /** The names of the tools the capabilities panel reports as granted, in its order. */
    private List<String> granted(Role role, UUID user) throws Exception {
        String body = http.asRole(role, user, get("/api/v1/assistant/capabilities")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(body, "$.data.tools[?(@.granted == true)].name");
    }

    private static List<String> names(JsonNode listed) {
        List<String> names = new ArrayList<>();
        listed.forEach(tool -> names.add(tool.path("name").asText()));
        return names;
    }

    private static String text(JsonNode result) {
        assertThat(result.path("content")).hasSize(1);
        assertThat(result.path("content").get(0).path("type").asText()).isEqualTo("text");
        return result.path("content").get(0).path("text").asText();
    }

    private UUID staff(String role, UUID... warehouses) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", user, role);
            for (UUID warehouse : warehouses) {
                jdbc.update("""
                        INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                        VALUES (gen_random_uuid(), ?, ?, 0, NOW())""", user, warehouse);
            }
        });
        return user;
    }
}
