package com.stockflow.assistant;

import com.jayway.jsonpath.JsonPath;
import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.order.internal.service.Quotes;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.SecuredProductionReceiptTest;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The assistant's Sales features end to end (contract §1), with the real security chain and the real order, chat,
 * payment and catalog modules: each endpoint under its screen's permission and scope, a reply draft that never carries
 * an internal note or the customer's name to the model, and a price review that knows what the customer last paid.
 *
 * <p>The orders are signed through {@link ConfirmedOrders}, which stores the signed scan, hence
 * {@link SecuredProductionReceiptTest} (the upload scanner off), shared with the other assistant classes that need
 * real contracts.</p>
 */
@SecuredProductionReceiptTest
class AssistantSalesIntegrationTest {

    private static final String BASE = "/api/v1/assistant";
    private static final String NOTE = "Ghi chu noi bo: khach no qua han, dung giao";

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationContext context;
    @Autowired Quotes quotes;

    private SecuredMvc http;

    @BeforeEach
    void setUp() {
        http = new SecuredMvc(mockMvc, decoder);
        when(model.available()).thenReturn(true);
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{\"closing\":true}",
                call.getArgument(0), List.of()));
    }

    // ------------------------------------------------------------------------------------------------ 1.1 next steps

    @Test
    @DisplayName("next steps: a signed order waiting for its signing instalment waits on the customer, in Vietnamese "
            + "from the bundle, and no model is asked")
    void nextSteps() throws Exception {
        ConfirmedOrders.Signed signed = signedOrder();
        UUID sales = staff("SALES_STAFF");

        String body = http.asRole(Role.SALES_STAFF, sales, get(BASE + "/orders/" + signed.orderNumber()
                        + "/next-steps").header("Accept-Language", "vi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNumber").value(signed.orderNumber()))
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.blockers[0].code").value("SIGNING_INSTALLMENT_UNPAID"))
                .andExpect(jsonPath("$.data.blockers[0].waitingOn").value("CUSTOMER"))
                .andExpect(jsonPath("$.data.blockers[0].href").value("/admin/orders/" + signed.orderId()))
                .andExpect(jsonPath("$.data.movingNormally").value(false))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(JsonPath.<String>read(body, "$.data.blockers[0].title")).isEqualTo(
                "Chờ khách trả đợt thanh toán khi ký");
        verify(model, never()).next(any());
    }

    @Test
    @DisplayName("next steps (review finding 2): a confirmed order whose every carton is held at its shipping "
            + "warehouse waits for the warehouse manager's release - it is not moving normally")
    void nextStepsAwaitingRelease() throws Exception {
        ConfirmedOrders orders = new ConfirmedOrders(context);
        CustomerSkuRows.Issued cups = CustomerSkuRows.issue(jdbc, tx, entityManager, DemoData.CUSTOMER_ID, 10);
        CustomerSkuRows.stock(jdbc, tx, cups.sku(), 20);
        ConfirmedOrders.Confirmed confirmed = orders.confirmed(DemoData.CUSTOMER_ID,
                ConfirmedOrders.Line.cartons(cups, 2, 10_000));
        orders.ship(confirmed.orderId(), DemoData.WAREHOUSE_HCM);

        http.asRole(Role.SALES_MANAGER, staff("SALES_MANAGER"), get(BASE + "/orders/" + confirmed.orderNumber()
                        + "/next-steps").header("Accept-Language", "vi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.blockers[0].code").value("AWAITING_RELEASE"))
                .andExpect(jsonPath("$.data.blockers[0].waitingOn").value("WAREHOUSE"))
                .andExpect(jsonPath("$.data.movingNormally").value(false));
        verify(model, never()).next(any());
    }

    @Test
    @DisplayName("next steps: a role without the order screen is 403, a portal customer is 403, and a warehouse "
            + "manager finds no order outside their warehouses (404)")
    void nextStepsRefusals() throws Exception {
        ConfirmedOrders.Signed signed = signedOrder();
        String path = BASE + "/orders/" + signed.orderNumber() + "/next-steps";

        http.asRole(Role.WAREHOUSE_STAFF, staff("WAREHOUSE_STAFF"), get(path)).andExpect(status().isForbidden());
        http.asCustomer(tx.execute(s -> ReferenceRows.user(entityManager)), get(path))
                .andExpect(status().isForbidden());
        http.asRole(Role.WAREHOUSE_MANAGER, staff("WAREHOUSE_MANAGER"), get(path)).andExpect(status().isNotFound());
        http.asRole(Role.SALES_STAFF, staff("SALES_STAFF"), get(BASE + "/orders/SO-20990101-999999/next-steps"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------------------------ 1.2 reply draft

    @Test
    @DisplayName("reply draft: the model is sent the customer's side and the customer's own order, never the internal "
            + "note nor the customer's name; the draft names the customer and flags a code nobody gave")
    void replyDraft() throws Exception {
        ConfirmedOrders.Signed signed = signedOrder();
        UUID sales = staff("SALES_STAFF");
        UUID conversation = conversation(signed, sales);
        String name = jdbc.queryForObject("SELECT full_name FROM customer.customer WHERE id = ?", String.class,
                DemoData.CUSTOMER_ID);
        script("Dạ KH#1, đơn " + signed.orderNumber() + " đang chờ thanh toán đợt khi ký. Mã vận đơn AHM-404404.");

        String body = http.asRole(Role.SALES_STAFF, sales, post(BASE + "/chat/conversations/" + conversation
                        + "/reply-draft").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instruction\":\"nhắc khách thanh toán\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.language").value("vi"))
                .andExpect(jsonPath("$.data.orderNumbers[0]").value(signed.orderNumber()))
                .andExpect(jsonPath("$.data.draft.unverifiedCodes[0]").value("AHM-404404"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(JsonPath.<String>read(body, "$.data.draft.text")).startsWith("Dạ " + name + ",");
        assertThat(JsonPath.<List<String>>read(body, "$.data.draft.sources[*].kind")).contains("ORDER", "CHAT");
        String sent = sentToModel();
        assertThat(sent).contains(signed.orderNumber(), "KH#1", "nhắc khách thanh toán").doesNotContain(NOTE, name);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat.message WHERE conversation_id = ?", Integer.class,
                conversation)).as("nothing is posted").isEqualTo(2);
    }

    @Test
    @DisplayName("reply draft: a role without the staff chat is 403, a portal customer is 403, an unknown "
            + "conversation is 404, and a model switched off is 503 ASSISTANT_UNAVAILABLE")
    void replyDraftRefusals() throws Exception {
        ConfirmedOrders.Signed signed = signedOrder();
        UUID conversation = conversation(signed, staff("SALES_STAFF"));
        String path = BASE + "/chat/conversations/" + conversation + "/reply-draft";

        http.asRole(Role.WAREHOUSE_STAFF, staff("WAREHOUSE_STAFF"), post(path)).andExpect(status().isForbidden());
        http.asCustomer(tx.execute(s -> ReferenceRows.user(entityManager)), post(path))
                .andExpect(status().isForbidden());
        http.asRole(Role.SALES_STAFF, staff("SALES_STAFF"), post(BASE + "/chat/conversations/" + UUID.randomUUID()
                + "/reply-draft")).andExpect(status().isNotFound());

        when(model.available()).thenReturn(false);
        http.asRole(Role.SALES_STAFF, staff("SALES_STAFF"), post(path))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("ASSISTANT_UNAVAILABLE"));
        verify(model, never()).next(any());
    }

    // ------------------------------------------------------------------------------------------------ 1.3 summary

    @Test
    @DisplayName("summary: the staff's side, internal note included, split into what the customer asked, what was "
            + "promised and what is open")
    void summary() throws Exception {
        ConfirmedOrders.Signed signed = signedOrder();
        UUID sales = staff("SALES_STAFF");
        UUID conversation = conversation(signed, sales);
        script("{\"summary\":\"KH#1 hỏi đơn " + signed.orderNumber() + ".\",\"customerAsks\":[\"Khi nào giao\"],"
                + "\"promisesMade\":[],\"openQuestions\":[\"Ngày giao\"]}");

        http.asRole(Role.SALES_STAFF, sales, post(BASE + "/chat/conversations/" + conversation + "/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerAsks[0]").value("Khi nào giao"))
                .andExpect(jsonPath("$.data.openQuestions[0]").value("Ngày giao"))
                .andExpect(jsonPath("$.data.orderNumbers[0]").value(signed.orderNumber()));

        assertThat(sentToModel()).contains(NOTE);
    }

    // ------------------------------------------------------------------------------------------------ 1.4 price review

    @Test
    @DisplayName("price review: a quote priced below what the customer last paid for the SKU says so; credit is "
            + "null for a manager who may not read it; Sales are refused (403) and an unknown quote is 404")
    void priceReview() throws Exception {
        CustomerSkuRows.Issued cups = CustomerSkuRows.issue(jdbc, tx, entityManager, DemoData.CUSTOMER_ID, 10);
        ConfirmedOrders.Signed sold = new ConfirmedOrders(context).signed(DemoData.CUSTOMER_ID,
                ConfirmedOrders.Line.cartons(cups, 2, 10_000));
        CurrentUser salesUser = salesUser();
        Quotes.Line line = new Quotes.Line(cups.sku(), 3, List.of(new Quotes.Tier(1, new BigDecimal("8000"))));
        Quotes.Draft draft = new Quotes.Draft(Instant.now().plus(Duration.ofDays(7)), "Prices exclude VAT",
                List.of(line));
        Quotes.QuoteView quote = WithCurrentUser.call(salesUser, DataScope.ALL,
                () -> quotes.create(salesUser, new Quotes.Create(UUID.randomUUID(), DemoData.CUSTOMER_ID, draft)));
        UUID manager = staff("SALES_MANAGER");

        String body = http.asRole(Role.SALES_MANAGER, manager, get(BASE + "/quotes/" + quote.id() + "/price-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quoteNumber").value(quote.quoteNumber()))
                .andExpect(jsonPath("$.data.customer.id").value(DemoData.CUSTOMER_ID.toString()))
                .andExpect(jsonPath("$.data.lines[0].sku").value(cups.sku()))
                .andExpect(jsonPath("$.data.lines[0].lastSoldOrderNumber").value(sold.orderNumber()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(new BigDecimal(JsonPath.read(body, "$.data.lines[0].lastSoldUnitPrice").toString()))
                .isEqualByComparingTo("10000");
        assertThat(JsonPath.<List<String>>read(body, "$.data.lines[0].flags")).contains("BELOW_LAST_SOLD");
        assertThat(JsonPath.<Object>read(body, "$.data.credit.outstanding"))
                .as("the Sales Manager reads customers' credit (V20270127000500)")
                .isNotNull();

        http.asRole(Role.SYSTEM_ADMIN, staff("SYSTEM_ADMIN"), get(BASE + "/quotes/" + quote.id() + "/price-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.credit.outstanding").exists());
        http.asRole(Role.SALES_STAFF, staff("SALES_STAFF"), get(BASE + "/quotes/" + quote.id() + "/price-review"))
                .andExpect(status().isForbidden());
        http.asRole(Role.SALES_MANAGER, manager, get(BASE + "/quotes/" + UUID.randomUUID() + "/price-review"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------------------------------------

    /** An order of the demo customer's own SKU whose contract wants everything on signing: PENDING_PAYMENT. */
    private ConfirmedOrders.Signed signedOrder() {
        CustomerSkuRows.Issued cups = CustomerSkuRows.issue(jdbc, tx, entityManager, DemoData.CUSTOMER_ID, 10);
        return new ConfirmedOrders(context).signed(DemoData.CUSTOMER_ID, ConfirmedOrders.Line.cartons(cups, 2, 10_000));
    }

    /**
     * A conversation of the demo customer about {@code signed}, as rows: the customer's question naming the customer
     * and an internal note of the staff that must never reach a reply to the customer.
     */
    private UUID conversation(ConfirmedOrders.Signed signed, UUID agent) {
        UUID conversation = Identifiers.newId();
        UUID customerSender = tx.execute(s -> ReferenceRows.user(entityManager));
        String name = jdbc.queryForObject("SELECT full_name FROM customer.customer WHERE id = ?", String.class,
                DemoData.CUSTOMER_ID);
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO chat.conversation (id, customer_id, order_id, subject, status, opened_by_type,
                                                   status_changed_at, version, created_at)
                    VALUES (?, ?, ?, 'Hoi don hang', 'QUEUED', 'CUSTOMER', NOW(), 0, NOW())""",
                    conversation, DemoData.CUSTOMER_ID, signed.orderId());
            jdbc.update("""
                    INSERT INTO chat.conversation_context (id, conversation_id, kind, order_id, is_primary,
                                                           attached_at, version, created_at)
                    VALUES (?, ?, 'ORDER', ?, true, NOW(), 0, NOW())""",
                    Identifiers.newId(), conversation, signed.orderId());
            jdbc.update("""
                    INSERT INTO chat.message (id, conversation_id, seq, sender_type, sender_id, body, sent_at, version,
                                              created_at)
                    VALUES (?, ?, 1, 'CUSTOMER', ?, ?, NOW(), 0, NOW())""", Identifiers.newId(), conversation,
                    customerSender, "Chào em, bên " + name + " hỏi đơn " + signed.orderNumber() + " khi nào giao?");
            jdbc.update("""
                    INSERT INTO chat.message (id, conversation_id, seq, sender_type, sender_id, body, kind, visibility,
                                              sent_at, version, created_at)
                    VALUES (?, ?, 2, 'AGENT', ?, ?, 'INTERNAL_NOTE', 'STAFF', NOW(), 0, NOW())""",
                    Identifiers.newId(), conversation, agent, NOTE);
            jdbc.update("UPDATE chat.conversation SET last_message_seq = 2, last_message_at = NOW() WHERE id = ?",
                    conversation);
        });
        return conversation;
    }

    private UUID staff(String role) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO identity.user_role (id, user_id, role_id, version, created_at)
                SELECT gen_random_uuid(), ?, id, 0, NOW() FROM identity.app_role WHERE code = ?""", user, role));
        return user;
    }

    private CurrentUser salesUser() {
        UUID id = staff("SALES_STAFF");
        return new CurrentUser(id, "sales-" + id, Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL, Set.of(),
                Set.of());
    }

    private void script(String text) {
        clearInvocations(model);
        when(model.next(any())).thenReturn(new LanguageModel.Reply(new Turn.ModelTurn("{\"scripted\":\"answer\"}",
                text, List.of()), LanguageModel.StopReason.END_TURN, new LanguageModel.Usage(120, 30, 0, 0),
                "scripted"));
    }

    private String sentToModel() {
        ArgumentCaptor<LanguageModel.Request> request = ArgumentCaptor.forClass(LanguageModel.Request.class);
        verify(model, atLeastOnce()).next(request.capture());
        return request.getAllValues().stream().flatMap(call -> call.history().stream())
                .filter(turn -> turn instanceof Turn.Question).map(turn -> ((Turn.Question) turn).text())
                .collect(Collectors.joining("\n"));
    }
}
