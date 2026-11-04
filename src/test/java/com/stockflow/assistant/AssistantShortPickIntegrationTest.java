package com.stockflow.assistant;

import com.jayway.jsonpath.JsonPath;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.AlertFixtures;
import com.stockflow.support.DemoData;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The question asked most about an order, why it is held and where it is short, in one lookup (design §4.2), with the
 * real security chain, the real order and fulfillment modules and a scripted model: {@code findOrder} carries the
 * order's pick task for a coordinator, who may see that task on the pick-lines screen, and nothing of it for Sales, who
 * may not.
 */
@SecuredIntegrationTest
class AssistantShortPickIntegrationTest {

    private static final String BASE = "/api/v1/assistant/conversations";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;

    private SecuredMvc http;
    private String sku;
    private String lot;
    private String orderNumber;

    /**
     * An order of 30 held for stock at HCM, its note naming no lot, and its pick task: 20 allocated at one bin, 15
     * found, 5 short; 10 picked in full at another bin.
     */
    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        sku = "ASP-" + suffix;
        lot = "ASP-LOT-" + suffix;
        UUID releasedBy = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID picker = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Short pick fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Short pick fixture");
        });
        tx.executeWithoutResult(s -> {
            UUID order = ReferenceRows.orderWithLine(entityManager, sku, "READY_TO_FULFILL", DemoData.CUSTOMER_ID,
                    releasedBy, DemoData.WAREHOUSE_HCM);
            entityManager.flush();
            // A number findOrder can read (SO-yyyyMMdd-NNNNNN) and the address every order screen shows: the
            // reference row has neither.
            orderNumber = "SO-20991230-" + (100_000 + ThreadLocalRandom.current().nextInt(900_000));
            jdbc.update("""
                    update ordering.customer_order set order_number = ?, status = 'ON_HOLD', hold_reason = 'INVENTORY',
                        status_before_hold = 'READY_TO_FULFILL', hold_note = 'Nhặt thiếu, chờ kiểm đếm lại',
                        shipping_recipient_name = 'Nguyễn Văn An', shipping_phone = '0903123456',
                        shipping_line1 = '1 Lê Lợi', shipping_ward_code = '26734', shipping_ward_name = 'Phường Bến Nghé',
                        shipping_province_code = '79', shipping_province_name = 'Thành phố Hồ Chí Minh',
                        shipping_country_code = 'VN'
                    where id = ?""", orderNumber, order);
            jdbc.update("update ordering.order_line set quantity = 30 where order_id = ?", order);
            UUID line = jdbc.queryForObject("select id from ordering.order_line where order_id = ?", UUID.class,
                    order);
            UUID pick = Identifiers.newId();
            jdbc.update("""
                    INSERT INTO fulfillment.pick (id, order_id, order_number, warehouse_id, status, assigned_user_id,
                                                  assigned_at, created_at)
                    VALUES (?, ?, ?, ?, 'PICKING', ?, NOW(), NOW())""",
                    pick, order, orderNumber, DemoData.WAREHOUSE_HCM, picker);
            jdbc.update("""
                    INSERT INTO fulfillment.pack (id, pick_id, status, created_at)
                    VALUES (?, ?, 'PENDING', NOW())""", Identifiers.newId(), pick);
            jdbc.update("""
                    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, sku, location_code, lot_number,
                        sequence_no, allocated_qty, picked_qty, short_qty, status, version, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, 1, 20, 15, 5, 'SHORT', 0, NOW())""",
                    Identifiers.newId(), pick, line, sku, AlertFixtures.HCM_BIN, lot);
            jdbc.update("""
                    INSERT INTO fulfillment.pick_line (id, pick_id, order_line_id, sku, location_code, lot_number,
                        sequence_no, allocated_qty, picked_qty, short_qty, status, picked_by, picked_at, version,
                        created_at)
                    VALUES (?, ?, ?, ?, ?, ?, 2, 10, 10, 0, 'PICKED', ?, NOW(), 0, NOW())""",
                    Identifiers.newId(), pick, line, sku, AlertFixtures.HCM_BIN_2, lot + "-FULL", picker);
        });
        when(model.available()).thenReturn(true);
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{\"closing\":true}",
                call.getArgument(0), List.of()));
    }

    @Test
    @DisplayName("a coordinator asking why an order is held and where it is short gets the pick task from findOrder "
            + "alone: its status and the line still short, with SKU, location, lot and units")
    void coordinatorGetsTheShortPick() throws Exception {
        UUID coordinator = staff("ORDER_COORDINATOR");
        script(toolUse("findOrder", "{\"orderNumber\":\"" + orderNumber + "\"}"),
                answer("Đơn đang bị giữ vì nhặt thiếu 5."));
        String conversation = start(Role.ORDER_COORDINATOR, coordinator);

        http.asRole(Role.ORDER_COORDINATOR, coordinator,
                        ask(conversation, "Đơn " + orderNumber + " vì sao bị giữ, thiếu ở đâu?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls.length()").value(1))
                .andExpect(jsonPath("$.data.toolCalls[0].outcome").value("OK"));

        String result = result(conversation);
        assertThat(JsonPath.<String>read(result, "$.hold.reason")).isEqualTo("INVENTORY");
        assertThat(JsonPath.<String>read(result, "$.picking.taskStatus")).isEqualTo("PICKING");
        assertThat(JsonPath.<Integer>read(result, "$.picking.shortLinesTotal")).isEqualTo(1);
        assertThat(JsonPath.<Boolean>read(result, "$.picking.shortLinesTruncated")).isFalse();
        assertThat(JsonPath.<String>read(result, "$.picking.shortLines[0].sku")).isEqualTo(sku);
        assertThat(JsonPath.<String>read(result, "$.picking.shortLines[0].locationCode"))
                .isEqualTo(AlertFixtures.HCM_BIN);
        assertThat(JsonPath.<String>read(result, "$.picking.shortLines[0].lotNumber")).isEqualTo(lot);
        assertThat(JsonPath.<Integer>read(result, "$.picking.shortLines[0].shortQuantity")).isEqualTo(5);
        assertThat(result).as("a line picked in full answers nothing about the hold")
                .doesNotContain(AlertFixtures.HCM_BIN_2, lot + "-FULL");
    }

    @Test
    @DisplayName("Sales read orders but not pick tasks: the same question gets the order and its hold, and nothing of "
            + "the task, not even its lot")
    void salesGetNoPicking() throws Exception {
        UUID sales = staff("SALES_STAFF");
        script(toolUse("findOrder", "{\"orderNumber\":\"" + orderNumber + "\"}"),
                answer("Đơn đang bị giữ vì thiếu hàng."));
        String conversation = start(Role.SALES_STAFF, sales);

        http.asRole(Role.SALES_STAFF, sales, ask(conversation, "Đơn " + orderNumber + " vì sao bị giữ, thiếu ở đâu?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls[0].outcome").value("OK"));

        String result = result(conversation);
        assertThat(JsonPath.<String>read(result, "$.hold.reason")).isEqualTo("INVENTORY");
        assertThat(result).doesNotContain("\"picking\"", lot, AlertFixtures.HCM_BIN, AlertFixtures.HCM_BIN_2);
    }

    // ------------------------------------------------------------------------------------------------------------

    private String result(String conversation) {
        return jdbc.queryForObject("""
                select result from assistant.tool_call where conversation_id = ?::uuid and tool_name = 'findOrder'""",
                String.class, conversation);
    }

    private UUID staff(String role) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", user, role));
        return user;
    }

    private String start(Role role, UUID user) throws Exception {
        String body = http.asRole(role, user, post(BASE)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private static MockHttpServletRequestBuilder ask(String conversation, String text) {
        return post(BASE + "/" + conversation + "/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":" + com.fasterxml.jackson.databind.node.TextNode.valueOf(text) + "}");
    }

    private void script(LanguageModel.Reply first, LanguageModel.Reply... rest) {
        when(model.next(any())).thenReturn(first, rest);
    }

    private static LanguageModel.Reply toolUse(String tool, String arguments) {
        return new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"tool_use\"}", null,
                List.of(new ToolCall("toolu_" + UUID.randomUUID(), tool, arguments))),
                LanguageModel.StopReason.TOOL_USE, new LanguageModel.Usage(100, 20, 0, 0), "scripted");
    }

    private static LanguageModel.Reply answer(String text) {
        return new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"answer\"}", text, List.of()),
                LanguageModel.StopReason.END_TURN, new LanguageModel.Usage(120, 30, 100, 0), "scripted");
    }
}
