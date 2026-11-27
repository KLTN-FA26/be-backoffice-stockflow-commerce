package com.stockflow.assistant;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jayway.jsonpath.JsonPath;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.security.Role;
import com.stockflow.order.internal.service.ProductionOrderService;
import com.stockflow.support.AssistantStock;
import com.stockflow.support.DemoData;
import com.stockflow.support.ProductionReceivables;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.SecuredProductionReceiptTest;
import com.stockflow.support.StaffFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Production orders through the assistant (ROLE-3, rescoped by ADR-0010): {@code findProductionOrders} end to end with
 * the real security chain, the real permission aspect, real migrations and a scripted model. Printing is done by
 * producers outside the system, one production order per delivery batch of a signed contract's order; what the tool
 * returns is what the production orders screen would show the asker: that screen's permission decides whether, their
 * warehouses decide which, and the sales order's number comes back only to a caller who may read orders or typed it.
 * And {@code findOrder} names the production order its order still waits for.
 *
 * <p>The production orders are real ones, made through the order module by {@link ProductionReceivables}: a contract
 * drawn up, sent and signed, the shipping warehouse chosen, a production order drawn up, its print sample waived,
 * approved and so SENT. The HCM one is then accepted by its producer for a day after its batch needs the goods (4A-7
 * AT_RISK). The in-house print shop the old version of this test read (prepress holds, blanks issued, spoilage) is
 * gone with the production module (V20261108000100).</p>
 */
@SecuredProductionReceiptTest
class AssistantProductionIntegrationTest {

    private static final String BASE = "/api/v1/assistant/conversations";
    private static final String TOOL = "findProductionOrders";
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    /** {@code ProductionReceivables}' producer and customer, as it registers them. */
    private static final String PRODUCER = "Receiving test workshop";
    private static final String CUSTOMER = "Receiving Test Co";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ProductionReceivables receivables;
    @Autowired ProductionOrderService productionOrders;

    private SecuredMvc http;
    private LocalDate today;
    /** Received at HCM, 20 cartons of 50: accepted by the producer for {@link #expected}, after it is needed. */
    private ProductionReceivables.Receivable hcm;
    private String hcmOrder;
    private LocalDate expected;
    /** Received at a warehouse of its own, still SENT: a user limited to HCM must not see it. */
    private ProductionReceivables.Receivable away;
    private String awayOrder;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        when(model.available()).thenReturn(true);
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{\"closing\":true}",
                call.getArgument(0), List.of()));
        today = LocalDate.now(VIETNAM);
        expected = today.plusDays(30);
        hcm = receivables.sent(DemoData.WAREHOUSE_HCM, 20, 50);
        UUID coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        productionOrders.accept(hcm.productionOrderId(),
                productionOrders.detail(hcm.productionOrderId()).order().version(), expected, null, coordinator);
        away = receivables.sent(new StaffFixtures(jdbc, tx, entityManager).otherWarehouse().id(), 5, 50);
        hcmOrder = orderNumber(hcm.salesOrderId());
        awayOrder = orderNumber(away.salesOrderId());
    }

    @Test
    @DisplayName("Sales get the production order of a customer order: its producer, dates, lateness worked out, cartons "
            + "and print sample, and of the order only its number")
    void readsTheProductionOfAnOrder() throws Exception {
        assertThat(granted("SALES_STAFF", "production-orders:READ")).as("the seeded grant this case relies on")
                .isTrue();
        UUID sales = staff("SALES_STAFF");
        script(toolUse("toolu_order", arguments("ANY", hcmOrder, "", 30)), answer("Một lệnh sản xuất, có rủi ro trễ."));
        String conversation = start(Role.SALES_STAFF, sales);

        http.asRole(Role.SALES_STAFF, sales, ask(conversation, "LSX của đơn " + hcmOrder + " tới đâu rồi?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls[0].outcome").value("OK"));

        String result = result(conversation, "toolu_order");
        assertThat(JsonPath.<Integer>read(result, "$.total")).isEqualTo(1);
        assertThat(JsonPath.<Boolean>read(result, "$.truncated")).isFalse();
        Map<String, Object> row = JsonPath.read(result, "$.productionOrders[0]");
        assertThat(row).containsEntry("code", hcm.productionCode())
                .containsEntry("status", "ACCEPTED")
                .containsEntry("salesOrderNumber", hcmOrder)
                .containsEntry("deliveryBatchSeq", 1)
                .containsEntry("producerName", PRODUCER)
                .containsEntry("producerLeadDays", 14)
                .containsEntry("warehouseCode", "HCM")
                .containsEntry("needByDate", needBy(hcm).toString())
                .containsEntry("expectedCompletionDate", expected.toString())
                .containsEntry("overdue", false)
                .containsEntry("cartons", 20)
                .containsEntry("receivedCartons", 0)
                .containsEntry("skus", hcm.sku())
                .containsEntry("sampleRequired", false)
                .containsEntry("sampleRounds", 0)
                .doesNotContainKey("sampleStatus");
        assertThat(row).as("the producer expects the goods after the batch needs them (4A-7)")
                .containsEntry("atRisk", true);
        assertThat(result).as("nothing of the customer, nor of another order").doesNotContain(CUSTOMER,
                hcm.salesOrderId().toString(), away.productionCode(), awayOrder);
    }

    /**
     * QD14 (V20261227000400) gave the Sales Manager the orders, so every seeded role reading production orders now
     * reads orders too, and the order number reaches all of them; the rule that keeps it from a caller who may not read
     * orders and did not type it is {@code FindProductionOrdersToolTest}'s, since no seeded role exercises it any more.
     */
    @Test
    @DisplayName("the Sales Manager, who reads orders since QD14, gets a production order by its code with its order's "
            + "number")
    void orderNumberForTheSalesManager() throws Exception {
        assertThat(granted("SALES_MANAGER", "production-orders:READ")).isTrue();
        assertThat(granted("SALES_MANAGER", "sales-orders:READ")).isTrue();
        UUID manager = staff("SALES_MANAGER");
        script(toolUse("toolu_code", arguments("OPEN", "", hcm.productionCode(), 30),
                        "toolu_typed", arguments("ANY", hcmOrder, "", 30)),
                answer("Lệnh đã được nhà sản xuất nhận."));
        String conversation = start(Role.SALES_MANAGER, manager);

        http.asRole(Role.SALES_MANAGER, manager, ask(conversation, "Lệnh " + hcm.productionCode() + " tới đâu?"))
                .andExpect(status().isOk());

        String byCode = result(conversation, "toolu_code");
        assertThat(outcome(conversation, "toolu_code")).isEqualTo("OK");
        assertThat(JsonPath.<List<String>>read(byCode, "$.productionOrders[*].code"))
                .containsExactly(hcm.productionCode());
        assertThat(JsonPath.<List<String>>read(byCode, "$.productionOrders[*].salesOrderNumber"))
                .containsExactly(hcmOrder);
        assertThat(byCode).doesNotContain(awayOrder);
        assertThat(JsonPath.<List<String>>read(result(conversation, "toolu_typed"),
                "$.productionOrders[*].salesOrderNumber")).containsExactly(hcmOrder);
    }

    @Test
    @DisplayName("a role without production-orders:READ is DENIED, told which permission, and sees no production "
            + "order")
    void deniedWithoutTheScreensPermission() throws Exception {
        assertThat(granted("ACCOUNTANT", "production-orders:READ")).isFalse();
        UUID accountant = staff("ACCOUNTANT");
        script(toolUse("toolu_denied", arguments("ANY", hcmOrder, "", 30)), answer("Bạn chưa có quyền xem LSX."));
        String conversation = start(Role.ACCOUNTANT, accountant);

        http.asRole(Role.ACCOUNTANT, accountant, ask(conversation, "LSX của đơn " + hcmOrder + "?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls[0].outcome").value("DENIED"));

        assertThat(result(conversation, "toolu_denied")).startsWith("NOT_PERMITTED")
                .contains("production-orders:READ").doesNotContain(hcm.productionCode(), PRODUCER);
    }

    @Test
    @DisplayName("a user limited to HCM sees the production orders received at HCM only: another warehouse's order "
            + "and code list none")
    void warehouseLimitedUserSeesTheirWarehousesOnly() throws Exception {
        UUID manager = staff("WAREHOUSE_MANAGER", DemoData.WAREHOUSE_HCM);
        script(toolUse("toolu_hcm", arguments("ANY", hcmOrder, "", 30),
                        "toolu_away", arguments("ANY", awayOrder, "", 30),
                        "toolu_code", arguments("OPEN", "", away.productionCode(), 30)),
                answer("Chỉ thấy lệnh của kho HCM."));
        String conversation = start(Role.WAREHOUSE_MANAGER, manager);

        http.asRole(Role.WAREHOUSE_MANAGER, manager, ask(conversation, "LSX nào về kho mình?"))
                .andExpect(status().isOk());

        assertThat(JsonPath.<List<String>>read(result(conversation, "toolu_hcm"), "$.productionOrders[*].warehouseCode"))
                .containsExactly("HCM");
        for (String call : List.of("toolu_away", "toolu_code")) {
            String none = result(conversation, call);
            assertThat(outcome(conversation, call)).isEqualTo("OK");
            assertThat(JsonPath.<Integer>read(none, "$.total")).as(call).isZero();
            assertThat(none).doesNotContain(away.productionCode());
        }
    }

    @Test
    @DisplayName("findOrder names the production order its order still waits for, with its status and cartons, for a "
            + "coordinator who may read production orders")
    void findOrderNamesTheProductionItWaitsFor() throws Exception {
        UUID coordinator = staff("PRODUCTION_COORDINATOR");
        script(toolUse(new ToolCall("toolu_find", "findOrder", "{\"orderNumber\":\"" + hcmOrder + "\"}")),
                answer("Đơn chờ nhà sản xuất giao hàng."));
        String conversation = start(Role.PRODUCTION_COORDINATOR, coordinator);

        http.asRole(Role.PRODUCTION_COORDINATOR, coordinator, ask(conversation, "Đơn " + hcmOrder + " chờ gì?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls[0].outcome").value("OK"));

        String result = result(conversation, "toolu_find");
        List<Map<String, Object>> production = JsonPath.read(result,
                "$.blockers.reasons[?(@.code == 'PRODUCTION_NOT_RECEIVED')]");
        assertThat(production).singleElement().satisfies(reason -> assertThat(reason)
                .containsEntry("productionOrderCode", hcm.productionCode())
                .containsEntry("productionOrderStatus", "ACCEPTED")
                .containsEntry("batchSeq", 1)
                .containsEntry("cartons", 20)
                .containsEntry("receivedCartons", 0)
                .containsEntry("expectedCompletionDate", expected.toString()));
        assertThat(JsonPath.<List<String>>read(result, "$.blockers.notChecked")).doesNotContain("PRODUCTION_ORDERS");
    }

    // ------------------------------------------------------------------------------------------------------------

    private String orderNumber(UUID salesOrderId) {
        return jdbc.queryForObject("select order_number from ordering.customer_order where id = ?", String.class,
                salesOrderId);
    }

    /** When the production order's batch needs the goods, as it was drawn up. */
    private LocalDate needBy(ProductionReceivables.Receivable receivable) {
        return jdbc.queryForObject("select need_by_date from ordering.production_order where id = ?", LocalDate.class,
                receivable.productionOrderId());
    }

    private boolean granted(String role, String permission) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from identity.role_permission rp
                               join identity.app_role r on r.id = rp.role_id
                               join identity.permission p on p.id = rp.permission_id
                               where r.code = ? and p.code = ?)""", Boolean.class, role, permission));
    }

    private UUID staff(String role, UUID... warehouses) {
        return new AssistantStock(jdbc, tx, entityManager).staff(role, warehouses);
    }

    private String start(Role role, UUID user) throws Exception {
        String body = http.asRole(role, user, post(BASE)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private String result(String conversation, String callId) {
        return jdbc.queryForObject("select result from assistant.tool_call where conversation_id = ?::uuid "
                + "and call_id = ?", String.class, conversation, callId);
    }

    private String outcome(String conversation, String callId) {
        return jdbc.queryForObject("select outcome from assistant.tool_call where conversation_id = ?::uuid "
                + "and call_id = ?", String.class, conversation, callId);
    }

    private static MockHttpServletRequestBuilder ask(String conversation, String text) {
        return post(BASE + "/" + conversation + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":" + com.fasterxml.jackson.databind.node.TextNode.valueOf(text) + "}");
    }

    private static String arguments(String status, String salesOrderNumber, String code, int limit) {
        return JsonNodeFactory.instance.objectNode().put("status", status).put("salesOrderNumber", salesOrderNumber)
                .put("code", code).put("limit", limit).toString();
    }

    private void script(LanguageModel.Reply first, LanguageModel.Reply... rest) {
        when(model.next(any())).thenReturn(first, rest);
    }

    private static LanguageModel.Reply toolUse(String callId, String arguments) {
        return toolUse(new ToolCall(callId, TOOL, arguments));
    }

    private static LanguageModel.Reply toolUse(String firstId, String firstArguments, String secondId,
                                               String secondArguments) {
        return toolUse(new ToolCall(firstId, TOOL, firstArguments), new ToolCall(secondId, TOOL, secondArguments));
    }

    private static LanguageModel.Reply toolUse(String firstId, String firstArguments, String secondId,
                                               String secondArguments, String thirdId, String thirdArguments) {
        return toolUse(new ToolCall(firstId, TOOL, firstArguments), new ToolCall(secondId, TOOL, secondArguments),
                new ToolCall(thirdId, TOOL, thirdArguments));
    }

    private static LanguageModel.Reply toolUse(ToolCall... calls) {
        return new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"tool_use\"}", null, List.of(calls)),
                LanguageModel.StopReason.TOOL_USE, new LanguageModel.Usage(100, 20, 0, 0), "scripted");
    }

    private static LanguageModel.Reply answer(String text) {
        return new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"answer\"}", text, List.of()),
                LanguageModel.StopReason.END_TURN, new LanguageModel.Usage(120, 30, 100, 0), "scripted");
    }
}
