package com.stockflow.assistant;

import com.jayway.jsonpath.JsonPath;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Role;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.SecuredProductionReceiptTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * The order tools end to end, with the real security chain and the real order and payment modules (design §4.2,
 * §5.4): {@code findOrders} serves the work queues of the roles that read orders and no one else, and its rows carry
 * no money, since an order owns none of its own (ADR-0010, kltn-docs 15); {@code findOrder} says what keeps a signed
 * order from moving on, and shows its contract's instalments only to a caller who may see them. Neither lets a
 * contact - in the contact block or typed into a note - reach the model.
 *
 * <p>The list cases insert their orders as rows, on a day of their own; the instalment cases sign a real contract
 * through {@link ConfirmedOrders}, which stores the signed scan, hence {@link SecuredProductionReceiptTest} (the upload
 * scanner off), shared with the other assistant classes that need real contracts or production orders.</p>
 */
@SecuredProductionReceiptTest
class AssistantOrderToolsIntegrationTest {

    private static final String BASE = "/api/v1/assistant/conversations";
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String CONTACT_NAME = "Trần Thị Liên Hệ";
    private static final String CONTACT_EMAIL = "lienhe@khach-hang.vn";
    private static final String CONTACT_PHONE = "0987654321";
    private static final String ADDRESS = "99 Đường Thử Nghiệm";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationContext context;

    private SecuredMvc http;
    private String sku;
    private LocalDate placedOn;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        sku = "AOT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        // A day of this test's own, far from every other test's orders: the list filters on it see only these.
        placedOn = LocalDate.of(2091, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(20_000));
        tx.executeWithoutResult(s -> {
            UUID product = Identifiers.newId();
            jdbc.update("insert into product.products(id,code,name,slug) values (?,?,?,?)", product, sku,
                    "Order tools fixture", sku.toLowerCase(Locale.ROOT));
            jdbc.update("insert into product.variants(id,product_id,sku,name,attribute_signature) values (?,?,?,?,'')",
                    Identifiers.newId(), product, sku, "Order tools fixture");
        });
        when(model.available()).thenReturn(true);
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{\"closing\":true}",
                call.getArgument(0), List.of()));
    }

    @Test
    @DisplayName("findOrders lists the orders held for one reason, newest first, with masked notes and no contact")
    void holdQueue() throws Exception {
        String older = held("PAYMENT", "Chờ kế toán xác nhận, gọi 0903 123 456 hoặc ketoan@khach.vn", 9);
        String newer = held("PAYMENT", "Chờ đối soát sao kê", 11);
        held("INVENTORY", "Nhặt thiếu 5", 10);
        pending(8);
        UUID sales = staff("SALES_STAFF");
        script(toolUse("findOrders", "{\"status\":\"ON_HOLD\",\"holdReason\":\"PAYMENT\",\"placedFrom\":\""
                + placedOn + "\",\"placedTo\":\"" + placedOn + "\",\"limit\":10}"), answer("Có 2 đơn."));
        String conversation = start(Role.SALES_STAFF, sales);

        http.asRole(Role.SALES_STAFF, sales, ask(conversation, "Đơn nào đang bị giữ vì thanh toán?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls[0].tool").value("findOrders"))
                .andExpect(jsonPath("$.data.toolCalls[0].outcome").value("OK"));

        String result = result(conversation, "findOrders");
        assertThat(JsonPath.<List<String>>read(result, "$.orders[*].orderNumber")).containsExactly(newer, older);
        assertThat(JsonPath.<Integer>read(result, "$.total")).isEqualTo(2);
        assertThat(JsonPath.<Boolean>read(result, "$.truncated")).isFalse();
        assertThat(JsonPath.<String>read(result, "$.orders[1].hold.reason")).isEqualTo("PAYMENT");
        assertThat(JsonPath.<String>read(result, "$.orders[1].hold.statusBeforeHold")).isEqualTo("CONFIRMED");
        assertThat(JsonPath.<String>read(result, "$.orders[1].hold.note")).contains("[phone]", "[e-mail]");
        assertThat(result).doesNotContain("0903 123 456", "ketoan@khach.vn", CONTACT_NAME, CONTACT_EMAIL,
                CONTACT_PHONE, ADDRESS);
    }

    @Test
    @DisplayName("findOrders by status and day placed: what waits for its signing instalment that day, each row with "
            + "its total and no money of its own, its customer by alias for Sales, who may list customers")
    void statusAndDayPlaced() throws Exception {
        String waiting = pending(8);
        held("PAYMENT", null, 9);
        UUID sales = staff("SALES_STAFF");
        script(toolUse("findOrders", "{\"status\":\"PENDING_PAYMENT\",\"holdReason\":\"ANY\",\"placedFrom\":\""
                + placedOn + "\",\"placedTo\":\"" + placedOn + "\",\"limit\":50}"), answer("Có 1 đơn chờ thanh toán."));
        String conversation = start(Role.SALES_STAFF, sales);

        http.asRole(Role.SALES_STAFF, sales, ask(conversation, "Hôm đó có đơn nào chờ thanh toán?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls[0].outcome").value("OK"));

        String result = result(conversation, "findOrders");
        assertThat(JsonPath.<List<String>>read(result, "$.orders[*].orderNumber")).containsExactly(waiting);
        assertThat(JsonPath.<String>read(result, "$.orders[0].status")).isEqualTo("PENDING_PAYMENT");
        assertThat(amount(result, "$.orders[0].total")).isEqualByComparingTo("1000000");
        assertThat(JsonPath.<String>read(result, "$.orders[0].currency")).isEqualTo("VND");
        assertThat(JsonPath.<String>read(result, "$.orders[0].customer")).matches("KH#\\d+");
        assertThat(JsonPath.<Map<String, Object>>read(result, "$.orders[0]"))
                .as("the instalments are findOrder's, for one order").doesNotContainKeys("paymentTerm",
                        "paymentStatus", "paid", "amountDue");
        assertThat(JsonPath.<Integer>read(result, "$.total")).isEqualTo(1);
    }

    @Test
    @DisplayName("a role that may ask but may not read orders is DENIED findOrders, and the model is told which "
            + "permission is missing")
    void deniedWithoutSalesOrders() throws Exception {
        String held = held("INVENTORY", null, 9);
        String role = jdbc.queryForObject("""
                select r.code from identity.app_role r
                where exists (select 1 from identity.role_permission rp
                              join identity.permission p on p.id = rp.permission_id
                              where rp.role_id = r.id and p.code = 'assistant-conversations:CREATE')
                  and not exists (select 1 from identity.role_permission rp
                                  join identity.permission p on p.id = rp.permission_id
                                  where rp.role_id = r.id and p.code = 'sales-orders:READ')
                order by r.code limit 1""", String.class);
        assertThat(role).as("a role that may ask but may not read orders").isNotNull();
        UUID asker = staff(role);
        script(toolUse("findOrders", "{\"status\":\"ANY\",\"holdReason\":\"ANY\",\"placedFrom\":\"" + placedOn
                + "\",\"placedTo\":\"" + placedOn + "\",\"limit\":50}"), answer("Bạn chưa có quyền xem đơn hàng."));
        String conversation = start(Role.valueOf(role), asker);

        http.asRole(Role.valueOf(role), asker, ask(conversation, "Có những đơn nào đang bị giữ?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls[0].outcome").value("DENIED"));

        assertThat(result(conversation, "findOrders")).startsWith("NOT_PERMITTED").contains("sales-orders:READ")
                .doesNotContain(held);
    }

    @Test
    @DisplayName("findOrder says what keeps a signed order: its instalment due on signing, unpaid, with the "
            + "contract's figures for the accountant; production orders it was not allowed to check are named")
    void signedOrderWaitsForItsSigningInstalment() throws Exception {
        ConfirmedOrders.Signed signed = signedOrder();
        UUID accountant = staff("ACCOUNTANT");
        script(toolUse("findOrder", "{\"orderNumber\":\"" + signed.orderNumber() + "\"}"),
                answer("Đơn chờ khách trả đợt ký hợp đồng."));
        String conversation = start(Role.ACCOUNTANT, accountant);

        http.asRole(Role.ACCOUNTANT, accountant, ask(conversation, "Đơn " + signed.orderNumber()
                        + " sao chưa chạy?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls[0].outcome").value("OK"));

        String result = result(conversation, "findOrder");
        assertThat(JsonPath.<String>read(result, "$.status")).isEqualTo("PENDING_PAYMENT");
        assertThat(JsonPath.<List<String>>read(result, "$.blockers.reasons[*].code"))
                .contains("SIGNING_INSTALLMENT_UNPAID");
        assertThat(JsonPath.<List<String>>read(result, "$.blockers.notChecked"))
                .as("the accountant holds no production-orders:READ").contains("PRODUCTION_ORDERS");
        assertThat(JsonPath.<Boolean>read(result, "$.payment.installmentsShown")).isTrue();
        assertThat(JsonPath.<Boolean>read(result, "$.payment.contractSigned")).isTrue();
        assertThat(JsonPath.<String>read(result, "$.payment.status")).isEqualTo("UNPAID");
        assertThat(JsonPath.<Boolean>read(result, "$.payment.onSigningPaid")).isFalse();
        assertThat(amount(result, "$.payment.installmentsTotal")).isEqualByComparingTo(signed.total());
        assertThat(amount(result, "$.payment.paid")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(amount(result, "$.payment.dueBeforeHandover")).isEqualByComparingTo(signed.total());
        assertThat(JsonPath.<List<String>>read(result, "$.history[*].reason"))
                .anySatisfy(reason -> assertThat(reason).contains("[e-mail]"));
        assertThat(result).doesNotContain("ketoan@khach.vn", CONTACT_NAME, CONTACT_EMAIL, CONTACT_PHONE, ADDRESS);
    }

    @Test
    @DisplayName("a role that reads orders but not instalments gets the order and its totals, installmentsShown false, "
            + "and no figure of what is paid or owed")
    void noInstalmentsWithoutTheirPermission() throws Exception {
        ConfirmedOrders.Signed signed = signedOrder();
        UUID coordinator = staff("PRODUCTION_COORDINATOR");
        script(toolUse("findOrder", "{\"orderNumber\":\"" + signed.orderNumber() + "\"}"),
                answer("Đơn chờ thanh toán đợt ký."));
        String conversation = start(Role.PRODUCTION_COORDINATOR, coordinator);

        http.asRole(Role.PRODUCTION_COORDINATOR, coordinator, ask(conversation, "Đơn " + signed.orderNumber()
                        + " tới đâu rồi?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCalls[0].outcome").value("OK"));

        String result = result(conversation, "findOrder");
        assertThat(JsonPath.<String>read(result, "$.status")).isEqualTo("PENDING_PAYMENT");
        assertThat(amount(result, "$.payment.total")).isEqualByComparingTo(signed.total());
        assertThat(JsonPath.<Boolean>read(result, "$.payment.installmentsShown")).isFalse();
        assertThat(JsonPath.<Map<String, Object>>read(result, "$.payment")).doesNotContainKeys("contractSigned",
                "status", "installmentsTotal", "paid", "onSigningPaid", "dueBeforeHandover");
    }

    // ------------------------------------------------------------------------------------------------------------

    /**
     * An order of the demo customer's own SKU, 2 cartons of 10 at 10 000 VND a piece, whose contract wants everything
     * on signing: signed, not paid, so PENDING_PAYMENT. Its contact block and address are filled, and its history holds
     * a reason with an e-mail address, none of which may reach the model.
     */
    private ConfirmedOrders.Signed signedOrder() {
        ConfirmedOrders orders = new ConfirmedOrders(context);
        CustomerSkuRows.Issued cups = CustomerSkuRows.issue(jdbc, tx, entityManager, DemoData.CUSTOMER_ID, 10);
        ConfirmedOrders.Signed signed = orders.signed(DemoData.CUSTOMER_ID,
                ConfirmedOrders.Line.cartons(cups, 2, 10_000));
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    UPDATE ordering.customer_order SET contact_name = ?, contact_email = ?, contact_phone = ?,
                        shipping_recipient_name = ?, shipping_phone = ?, shipping_line1 = ?, shipping_ward_code = '26734',
                        shipping_ward_name = 'Phường Bến Nghé', shipping_province_code = '79',
                        shipping_province_name = 'Thành phố Hồ Chí Minh', shipping_country_code = 'VN'
                    WHERE id = ?""", CONTACT_NAME, CONTACT_EMAIL, CONTACT_PHONE, CONTACT_NAME, CONTACT_PHONE, ADDRESS,
                    signed.orderId());
            jdbc.update("""
                    INSERT INTO ordering.order_status_history (id, order_id, from_status, to_status, reason,
                        occurred_at, version, created_at) VALUES (?, ?, 'PENDING_CONTRACT', 'PENDING_PAYMENT', ?, ?, 0,
                        NOW())""", Identifiers.newId(), signed.orderId(),
                    "Khách hẹn chuyển khoản, đối soát với ketoan@khach.vn", Timestamp.from(Instant.now()));
        });
        return signed;
    }

    /** Signed and paid in full, then held from CONFIRMED. */
    private String held(String reason, String note, int hour) {
        return order("ON_HOLD", hour, reason, "CONFIRMED", note);
    }

    /** Signed, its instalment on signing not paid yet. */
    private String pending(int hour) {
        return order("PENDING_PAYMENT", hour, null, null, null);
    }

    /**
     * One order of 100 x 10,000 VND under a signed contract, placed on this test's day at {@code hour} Vietnam time,
     * with a contact block and a shipping address that must never reach the model. No money columns: an order owns
     * none since the rescope, and the list rows do not read the contract's instalments.
     */
    private String order(String status, int hour, String holdReason, String statusBeforeHold, String holdNote) {
        UUID id = Identifiers.newId();
        String number = "SO-" + placedOn.format(DateTimeFormatter.BASIC_ISO_DATE) + "-"
                + (1_000_000 + ThreadLocalRandom.current().nextInt(9_000_000));
        Timestamp placed = Timestamp.from(placedOn.atTime(hour, 0).atZone(VIETNAM).toInstant());
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status,
                        total_amount, currency, placed_at, contract_signed_at, hold_reason, status_before_hold,
                        hold_note, contact_name, contact_email, contact_phone, shipping_recipient_name, shipping_phone,
                        shipping_line1, shipping_ward_code, shipping_ward_name, shipping_province_code,
                        shipping_province_name, shipping_country_code, version, created_at)
                    VALUES (?, ?, ?, ?, ?, 1000000, 'VND', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        '26734', 'Phường Bến Nghé', '79', 'Thành phố Hồ Chí Minh', 'VN', 0, NOW())""",
                    id, number, DemoData.CUSTOMER_ID, Identifiers.newId(), status, placed, placed, holdReason,
                    statusBeforeHold, holdNote, CONTACT_NAME, CONTACT_EMAIL, CONTACT_PHONE, CONTACT_NAME,
                    CONTACT_PHONE, ADDRESS);
            jdbc.update("INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency) "
                    + "VALUES (?, ?, ?, 100, 10000, 'VND')", Identifiers.newId(), id, sku);
        });
        return number;
    }

    private String result(String conversation, String tool) {
        return jdbc.queryForObject("select result from assistant.tool_call where conversation_id = ?::uuid "
                + "and tool_name = ?", String.class, conversation, tool);
    }

    private static BigDecimal amount(String json, String path) {
        return new BigDecimal(JsonPath.read(json, path).toString());
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
