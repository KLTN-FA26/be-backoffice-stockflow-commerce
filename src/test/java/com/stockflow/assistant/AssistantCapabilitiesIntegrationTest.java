package com.stockflow.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.assistant.internal.service.AssistantProperties;
import com.stockflow.assistant.internal.service.AssistantTool;
import com.stockflow.assistant.internal.service.AssistantTools;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.PermissionCatalog;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.Role;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/assistant/capabilities} and the question context against the seeded role matrix, the real
 * permission aspect and the real tools: what the panel says a role may ask is what the context tells the model, and
 * it is what the tools then enforce.
 *
 * <p>{@link #GRANTED} is the matrix of the seeded grants for the tools that existed when it was last brought up to date
 * (29/10/2026: the customer price and credit tools, the pick-task and goods-receipt queues, and the two floor roles
 * V20261105000500 opened the assistant to). A tool added later is still covered by the context and the enforcement
 * cases, which compare against the endpoint's own answer; add it to the tables when its grants are decided.</p>
 */
@SecuredIntegrationTest
class AssistantCapabilitiesIntegrationTest {

    private static final String CAPABILITIES = "/api/v1/assistant/capabilities";
    private static final String CONVERSATIONS = "/api/v1/assistant/conversations";
    /** Never created: {@link #order} draws its numbers from 100000 up. */
    private static final String NO_SUCH_ORDER = "SO-20991230-000000";
    private static final String SKU = "SOFA-3S-GREY";
    /** Matches no customer by name, e-mail, phone or tax code, so the customer tools answer with a note. */
    private static final String NO_SUCH_CUSTOMER = "No such customer of the capabilities test";
    /** {@code AssistantService.MAX_CALLS_PER_ROUND}: more in one step would come back NOT_RUN. */
    private static final int CALLS_PER_STEP = 8;

    private static final Map<String, String> ARGUMENTS = Map.ofEntries(
            Map.entry("findExpiringLots", "{\"withinDays\":30,\"limit\":5}"),
            Map.entry("findFulfillmentTasks", "{\"status\":\"ANY\",\"limit\":5}"),
            Map.entry("findGoodsReceipts", "{\"status\":\"OPEN\",\"receiptNumber\":\"\",\"limit\":5}"),
            Map.entry("findLowStock", "{\"limit\":5}"),
            Map.entry("findOrder", "{\"orderNumber\":\"" + NO_SUCH_ORDER + "\"}"),
            Map.entry("findOverdueCustomers", "{\"order\":\"LARGEST_FIRST\",\"limit\":5}"),
            Map.entry("findPendingAdjustments", "{\"sku\":\"\",\"limit\":5}"),
            Map.entry("findShipments", "{\"status\":\"ANY\",\"search\":\"\",\"limit\":5}"),
            Map.entry("findOrders", "{\"status\":\"ANY\",\"holdReason\":\"ANY\",\"placedFrom\":\"\","
                    + "\"placedTo\":\"\",\"limit\":5}"),
            Map.entry("findProductionOrders", "{\"productionOrderNumber\":\"\",\"salesOrderNumber\":\"\","
                    + "\"status\":\"OPEN\",\"due\":\"ANY\",\"limit\":5}"),
            Map.entry("findPurchaseOrders", "{\"status\":\"ANY\",\"poNumber\":\"\",\"supplier\":\"\",\"limit\":5}"),
            Map.entry("getCustomerCredit", "{\"customer\":\"" + NO_SUCH_CUSTOMER + "\"}"),
            Map.entry("getCustomerPrice", "{\"customer\":\"" + NO_SUCH_CUSTOMER + "\",\"sku\":\"" + SKU
                    + "\",\"quantity\":0}"),
            Map.entry("getOrderPicking", "{\"orderNumber\":\"" + NO_SUCH_ORDER + "\"}"),
            Map.entry("getSalesSummary", "{\"days\":7,\"top\":3}"),
            Map.entry("getStockLevels", "{\"sku\":\"" + SKU + "\"}"),
            Map.entry("getStockLots", "{\"sku\":\"" + SKU + "\"}"),
            Map.entry("getSupplierTerms", "{\"sku\":\"" + SKU + "\",\"supplier\":\"\"}"),
            Map.entry("proposeReorder", "{\"skus\":[\"" + SKU + "\"],\"salesDays\":7,\"coverDays\":30}"),
            Map.entry("searchCatalog", "{\"text\":\"sofa\",\"limit\":3}"),
            Map.entry("searchKnowledge", "{\"query\":\"custom printed return defective\"}"),
            Map.entry("traceLot", "{\"lotNumber\":\"NO-SUCH-LOT\",\"sku\":\"\"}"));

    /** The tools the tables below cover. */
    private static final Set<String> KNOWN = new TreeSet<>(ARGUMENTS.keySet());

    /** What a warehouse-limited user gets from each tool; a tool not listed answers the same for everyone. */
    private static final Map<String, String> LIMITATIONS = Map.ofEntries(
            Map.entry("findLowStock", "COMPANY_WIDE_ONLY"), Map.entry("proposeReorder", "COMPANY_WIDE_ONLY"),
            Map.entry("getStockLevels", "PARTIAL"), Map.entry("getStockLots", "PARTIAL"),
            Map.entry("findExpiringLots", "PARTIAL"), Map.entry("traceLot", "PARTIAL"),
            Map.entry("findPurchaseOrders", "PARTIAL"), Map.entry("getOrderPicking", "PARTIAL"),
            Map.entry("findProductionOrders", "PARTIAL"), Map.entry("findFulfillmentTasks", "PARTIAL"),
            Map.entry("findGoodsReceipts", "PARTIAL"), Map.entry("findPendingAdjustments", "PARTIAL"),
            Map.entry("findShipments", "PARTIAL"));

    /**
     * The tools of {@link #KNOWN} each seeded persona may use (identity.role_permission, 28/10/2026, plus
     * V20261105000500).
     */
    private static final Map<Persona, Set<String>> GRANTED = granted();

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ObjectMapper json;
    @Autowired AssistantTools tools;
    @Autowired AssistantProperties properties;
    @Autowired PermissionCatalog catalog;

    private SecuredMvc http;

    /** A seeded role, assigned to warehouse HCM or to no warehouse. */
    record Persona(Role role, boolean atHcm) {

        @Override
        public String toString() {
            return role + (atHcm ? " [HCM]" : "");
        }
    }

    private static Map<Persona, Set<String>> granted() {
        Map<Persona, Set<String>> granted = new LinkedHashMap<>();
        granted.put(new Persona(Role.SYSTEM_ADMIN, false), KNOWN);
        granted.put(new Persona(Role.INVENTORY_PLANNER, false), Set.of("findExpiringLots", "findLowStock",
                "findPendingAdjustments", "getStockLevels", "getStockLots", "searchCatalog", "searchKnowledge",
                "traceLot"));
        // findLowStock and proposeReorder give company-wide figures: refused to a manager of one warehouse.
        granted.put(new Persona(Role.WAREHOUSE_MANAGER, true), Set.of("findExpiringLots", "findFulfillmentTasks",
                "findGoodsReceipts", "findPendingAdjustments", "findProductionOrders", "findPurchaseOrders",
                "findShipments", "getOrderPicking",
                "getSalesSummary", "getStockLevels", "getStockLots", "searchCatalog", "searchKnowledge", "traceLot"));
        granted.put(new Persona(Role.SALES_STAFF, false), Set.of("findOrder", "findOrders", "findOverdueCustomers",
                "findProductionOrders", "getCustomerCredit", "getCustomerPrice", "searchCatalog", "searchKnowledge"));
        // proposeReorder: the purchase-order permission, but neither sales nor stock (design §13 AQ-08).
        granted.put(new Persona(Role.PROCUREMENT_STAFF, false), Set.of("findGoodsReceipts", "findProductionOrders",
                "findPurchaseOrders", "getSupplierTerms", "searchCatalog", "searchKnowledge"));
        // The customer tools without customer-customers:READ: a customer is named exactly, never listed.
        granted.put(new Persona(Role.ORDER_COORDINATOR, false), Set.of("findFulfillmentTasks", "findOrder",
                "findOrders", "findOverdueCustomers", "findProductionOrders", "findShipments", "getCustomerCredit",
                "getCustomerPrice", "getOrderPicking",
                "getSalesSummary", "searchCatalog", "searchKnowledge"));
        granted.put(new Persona(Role.ACCOUNTANT, false), Set.of("findOrder", "findOrders", "findOverdueCustomers",
                "getCustomerCredit", "getSalesSummary", "searchCatalog", "searchKnowledge"));
        // getOrderPicking: fulfillment shows a design re-verifier the tasks of their warehouse (TaskAccess).
        granted.put(new Persona(Role.QC_STAFF, true), Set.of("findFulfillmentTasks", "findGoodsReceipts",
                "findProductionOrders", "getOrderPicking", "getStockLevels", "getStockLots", "searchCatalog",
                "searchKnowledge"));
        // V20261105000500: a picker's own tasks, through fulfillment's rule.
        granted.put(new Persona(Role.WAREHOUSE_STAFF, true), Set.of("findExpiringLots", "findFulfillmentTasks",
                "findGoodsReceipts", "findPendingAdjustments", "findProductionOrders", "findPurchaseOrders",
                "findShipments", "getOrderPicking", "getStockLevels", "getStockLots", "searchCatalog",
                "searchKnowledge", "traceLot"));
        granted.put(new Persona(Role.PRODUCTION_STAFF, true), Set.of("findProductionOrders", "getStockLevels",
                "getStockLots", "searchCatalog", "searchKnowledge"));
        return granted;
    }

    static Stream<Persona> personas() {
        return GRANTED.keySet().stream();
    }

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        when(model.available()).thenReturn(true);
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{\"closing\":true}",
                call.getArgument(0), List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("personas")
    @DisplayName("the panel lists what the seeded grants allow, and the question context names exactly those tools")
    void panelAndContextAgree(Persona persona) throws Exception {
        UUID user = staff(persona);

        JsonNode capabilities = capabilities(persona.role(), user);
        assertThat(capabilities.path("scope").asText()).isEqualTo(persona.atHcm() ? "WAREHOUSE" : "ALL");
        assertThat(texts(capabilities.path("warehouses"))).isEqualTo(persona.atHcm() ? List.of("HCM") : List.of());
        assertThat(capabilities.path("dailyLimit").asInt()).isEqualTo(properties.dailyMessageLimit());
        assertThat(capabilities.path("askedToday").asLong()).isZero();
        Map<String, JsonNode> byName = byName(capabilities);
        assertThat(byName.keySet()).containsAll(KNOWN);
        assertThat(KNOWN.stream().filter(name -> byName.get(name).path("granted").asBoolean()))
                .containsExactlyInAnyOrderElementsOf(GRANTED.get(persona));
        for (String name : KNOWN) {
            JsonNode tool = byName.get(name);
            assertThat(tool.has("limitation")).as("%s sends its limitation, null included", name).isTrue();
            assertThat(tool.path("limitation").isNull() ? null : tool.path("limitation").asText())
                    .as("%s limitation", name).isEqualTo(LIMITATIONS.get(name));
            boolean refusedForScope = persona.atHcm() && "COMPANY_WIDE_ONLY".equals(LIMITATIONS.get(name));
            if (tool.path("granted").asBoolean()) {
                assertThat(tool.path("missing")).as("%s is open to %s", name, persona).isEmpty();
            } else if (!refusedForScope) {
                assertThat(tool.path("missing")).as("%s names what %s lacks", name, persona).isNotEmpty();
            }
        }
        List<String> granted = new ArrayList<>();
        capabilities.path("tools").forEach(tool -> {
            if (tool.path("granted").asBoolean()) {
                granted.add(tool.path("name").asText());
            }
        });

        when(model.next(any())).thenReturn(answer("Tôi giúp được những việc trong danh sách."));
        String conversation = start(persona.role(), user);
        http.asRole(persona.role(), user, ask(conversation, "Bạn giúp tôi được những gì?"))
                .andExpect(status().isOk());

        String context = questionContext();
        assertThat(context).contains(" Tools this user may use: " + String.join(", ", granted)
                + ". Every other tool refuses them.");
        if (persona.atHcm()) {
            assertThat(context).contains(" They are limited to warehouses HCM: they see no other warehouse and no "
                    + "company-wide stock figure.");
        } else {
            assertThat(context).doesNotContain("They are limited to warehouses");
        }
        assertThat(capabilities(persona.role(), user).path("askedToday").asLong()).isEqualTo(1);
    }

    /**
     * A permission the panel reports missing is refused by the tool, naming the first of them - except an either-or
     * ({@code a|b}), which {@link AssistantTool#alsoRequiresAnyOf} reports and leaves to the module behind the tool:
     * fulfillment still answers a picker who holds neither half about their own tasks, so a picker's getOrderPicking
     * is refused or answered by fulfillment's rule, never by the panel's.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("personas")
    @DisplayName("every tool does what the panel says: a granted one is not refused, one with a permission missing is "
            + "refused naming the first of them, company-wide figures give a warehouse-limited user only a note")
    void toolsEnforceWhatThePanelReports(Persona persona) throws Exception {
        UUID user = staff(persona);
        Map<String, JsonNode> byName = byName(capabilities(persona.role(), user));
        List<ToolCall> calls = KNOWN.stream()
                .map(name -> new ToolCall("toolu_" + name, name, ARGUMENTS.get(name))).toList();
        List<LanguageModel.Reply> steps = new ArrayList<>();
        for (int from = 0; from < calls.size(); from += CALLS_PER_STEP) {
            steps.add(toolUse(calls.subList(from, Math.min(from + CALLS_PER_STEP, calls.size()))));
        }
        steps.add(answer("Xong."));
        when(model.next(any())).thenReturn(steps.getFirst(),
                steps.subList(1, steps.size()).toArray(LanguageModel.Reply[]::new));
        String conversation = start(persona.role(), user);
        http.asRole(persona.role(), user, ask(conversation, "Thử mọi tool.")).andExpect(status().isOk());

        Map<String, Map<String, Object>> ran = jdbc.queryForList("""
                        select tool_name, outcome, result from assistant.tool_call where conversation_id = ?::uuid""",
                        UUID.fromString(conversation)).stream()
                .collect(Collectors.toMap(row -> (String) row.get("tool_name"), row -> row));
        assertThat(ran.keySet()).containsExactlyInAnyOrderElementsOf(KNOWN);
        for (String name : KNOWN) {
            JsonNode tool = byName.get(name);
            String outcome = (String) ran.get(name).get("outcome");
            String result = (String) ran.get(name).get("result");
            List<String> missing = texts(tool.path("missing"));
            if (tool.path("granted").asBoolean()) {
                assertThat(outcome).as("%s is open to %s: %s", name, persona, result).isNotEqualTo("DENIED");
            } else if (!missing.isEmpty() && missing.stream().allMatch(code -> code.contains("|"))) {
                assertThat(outcome).as("%s is left to its module's own rule for %s: %s", name, persona, result)
                        .isIn("DENIED", "OK");
            } else if (!missing.isEmpty()) {
                assertThat(outcome).as("%s is closed to %s", name, persona).isEqualTo("DENIED");
                if (!missing.getFirst().contains("|")) {
                    assertThat(result).as("%s names the first permission the panel says is missing", name)
                            .contains(missing.getFirst());
                }
            } else {
                assertThat(outcome).as("%s answers a warehouse-limited user with a note", name).isEqualTo("OK");
                assertThat(result).contains("company-wide");
            }
        }
    }

    @Test
    @DisplayName("QC at HCM is told nothing of an order without a pick task: no status, and the same answer as for a "
            + "number that matches no order; a coordinator, who reads orders, is told the status")
    void orderStatusStaysWithWhoMayReadOrders() throws Exception {
        String number = order();
        UUID qc = staff(new Persona(Role.QC_STAFF, true));
        when(model.next(any())).thenReturn(toolUse(List.of(
                        new ToolCall("toolu_order", "getOrderPicking", "{\"orderNumber\":\"" + number + "\"}"),
                        new ToolCall("toolu_none", "getOrderPicking", "{\"orderNumber\":\"" + NO_SUCH_ORDER + "\"}"))),
                answer("Không thấy lệnh nhặt nào."));
        String asked = start(Role.QC_STAFF, qc);
        http.asRole(Role.QC_STAFF, qc, ask(asked, "Đơn " + number + " đã có lệnh nhặt chưa?"))
                .andExpect(status().isOk());

        Map<String, Object> real = picking(asked, number);
        Map<String, Object> none = picking(asked, NO_SUCH_ORDER);
        String result = (String) real.get("result");
        assertThat(real.get("outcome")).isEqualTo("OK");
        assertThat(none.get("outcome")).isEqualTo("OK");
        assertThat(Arrays.stream(OrderStatus.values()).map(Enum::name))
                .as("no order status reaches a user without sales-orders:READ: %s", result)
                .noneMatch(result::contains);
        assertThat(result.replace(number, "#")).as("an order without a task looks like no order at all")
                .isEqualTo(((String) none.get("result")).replace(NO_SUCH_ORDER, "#"));
        assertThat(JsonPath.<String>read(result, "$.note")).contains("visible");

        UUID coordinator = staff(new Persona(Role.ORDER_COORDINATOR, false));
        when(model.next(any())).thenReturn(toolUse(List.of(
                        new ToolCall("toolu_order", "getOrderPicking", "{\"orderNumber\":\"" + number + "\"}"),
                        new ToolCall("toolu_none", "getOrderPicking", "{\"orderNumber\":\"" + NO_SUCH_ORDER + "\"}"))),
                answer("Đơn chưa có lệnh nhặt."));
        String told = start(Role.ORDER_COORDINATOR, coordinator);
        http.asRole(Role.ORDER_COORDINATOR, coordinator, ask(told, "Đơn " + number + " đã có lệnh nhặt chưa?"))
                .andExpect(status().isOk());

        assertThat((String) picking(told, number).get("result")).contains("PENDING_PAYMENT");
        assertThat(picking(told, NO_SUCH_ORDER).get("outcome")).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("every permission a tool declares besides its guard exists in the permission catalog")
    void declaredRequirementsExist() {
        for (AssistantTool tool : tools.all()) {
            for (PermissionCode permission : Stream.concat(tool.alsoRequires().stream(),
                    tool.alsoRequiresAnyOf().stream()).toList()) {
                assertThat(catalog.isDeclared(permission))
                        .as("%s declares %s, which the permission catalog must know", tool.spec().name(), permission)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("a portal customer gets no capabilities")
    void customersAreRefused() throws Exception {
        UUID account = tx.execute(s -> ReferenceRows.user(entityManager));
        http.asCustomer(account, get(CAPABILITIES)).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------------------------

    private UUID staff(Persona persona) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                    SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""",
                    user, persona.role().name());
            if (persona.atHcm()) {
                jdbc.update("""
                        INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at)
                        VALUES (gen_random_uuid(), ?, ?, 0, NOW())""", user, DemoData.WAREHOUSE_HCM);
            }
        });
        return user;
    }

    /** A placed, unpaid order: no warehouse has a pick task for it. */
    private String order() {
        String number = "SO-20991230-" + ThreadLocalRandom.current().nextInt(100_000, 1_000_000);
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                                                     currency, placed_at, version, created_at)
                VALUES (?, ?, ?, ?, 'PENDING_PAYMENT', 1000, 'VND', NOW(), 0, NOW())""",
                Identifiers.newId(), number, DemoData.CUSTOMER_ID, UUID.randomUUID()));
        return number;
    }

    private JsonNode capabilities(Role role, UUID user) throws Exception {
        String body = http.asRole(role, user, get(CAPABILITIES)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body).path("data");
    }

    private static Map<String, JsonNode> byName(JsonNode capabilities) {
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        capabilities.path("tools").forEach(tool -> byName.put(tool.path("name").asText(), tool));
        return byName;
    }

    private static List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(item -> texts.add(item.asText()));
        return texts;
    }

    private Map<String, Object> picking(String conversation, String orderNumber) {
        return jdbc.queryForMap("""
                select outcome, result from assistant.tool_call
                where conversation_id = ?::uuid and arguments ->> 'orderNumber' = ?""",
                UUID.fromString(conversation), orderNumber);
    }

    /** The context the model was given with the question just asked. */
    private String questionContext() {
        ArgumentCaptor<LanguageModel.Request> requests = ArgumentCaptor.forClass(LanguageModel.Request.class);
        verify(model, atLeastOnce()).next(requests.capture());
        return requests.getAllValues().getLast().history().stream()
                .filter(Turn.Question.class::isInstance).map(Turn.Question.class::cast)
                .reduce((earlier, later) -> later).orElseThrow().context();
    }

    private String start(Role role, UUID user) throws Exception {
        String body = http.asRole(role, user, post(CONVERSATIONS)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private static MockHttpServletRequestBuilder ask(String conversation, String text) {
        return post(CONVERSATIONS + "/" + conversation + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":" + com.fasterxml.jackson.databind.node.TextNode.valueOf(text) + "}");
    }

    private static LanguageModel.Reply toolUse(List<ToolCall> calls) {
        return new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"tool_use\"}", null, calls),
                LanguageModel.StopReason.TOOL_USE, new LanguageModel.Usage(100, 20, 0, 0), "scripted");
    }

    private static LanguageModel.Reply answer(String text) {
        return new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"answer\"}", text, List.of()),
                LanguageModel.StopReason.END_TURN, new LanguageModel.Usage(120, 30, 100, 0), "scripted");
    }
}
