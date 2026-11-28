package com.stockflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.chat.internal.service.ChatCardActions;
import com.stockflow.chat.internal.service.ChatMessageView;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.ShortageCardEnd;
import com.stockflow.order.api.ShortageCardStatus;
import com.stockflow.order.api.ShortageCards;
import com.stockflow.order.api.ShortageOption;
import com.stockflow.order.internal.domain.ShortageCard;
import com.stockflow.order.internal.service.OrderOperations;
import com.stockflow.order.internal.service.ShortageCardService;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.support.Await;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The shortage choice card, end to end through the services and the chat (kltn-docs 17 §4.14, §5.6, BR-33; 16 BR-15):
 * an order of 4 cartons released to HCM, one carton reported short by the picker - the order is held INVENTORY - and
 * Sales send "Đơn thiếu 1 thùng" into the customer's conversation; the customer chooses on the card; the card ends when
 * the warehouse replaces the carton, when the order is cancelled, or when Sales lift the hold by hand.
 *
 * <p>The conversation is the demo seed's (customer demo.khachhang, in the charge of demo.sales), so the card lands where
 * a real one would. Each test builds its own order.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000",
        "stockflow.upload-inspection.enabled=false"})
class ShortageCardIntegrationTest {

    private static final UUID HCM = DemoData.WAREHOUSE_HCM;
    private static final UUID CUSTOMER = DemoData.CUSTOMER_ID;
    private static final UUID CUSTOMER_ACCOUNT = DemoData.demoId("demo:wf02:user:demo.khachhang");
    private static final UUID CONVERSATION = DemoData.demoId("demo:wf02:conversation:ds02a");
    private static final String BASE = "/api/v1/fulfillment";
    private static final Duration ASYNC = Duration.ofSeconds(20);
    private static final long UNIT_PRICE = 6_000_000L;

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired OrderService orders;
    @Autowired OrderOperations operations;
    @Autowired ShortageCardService cards;
    @Autowired ChatCardActions cardActions;
    @Autowired PaymentService payments;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationContext context;
    @Autowired ObjectMapper json;

    private ConfirmedOrders confirmedOrders;
    private CustomerSkuRows.Issued sku;
    private UUID manager;
    private UUID picker;

    private final CurrentUser portal = new CurrentUser(CUSTOMER_ACCOUNT, "demo.khachhang", Set.of(Role.CUSTOMER),
            Set.of(), DataScope.ALL, Set.of(), Set.of());

    /**
     * The customer's Sales owner of the fixture: making them owner moves the customer's open conversations to them
     * (sales-owner plan, 16 BR-12), so they are the agent in charge of the demo conversation.
     */
    private CurrentUser sales() {
        return confirmedOrders.sales();
    }

    @BeforeEach
    void setUp() {
        confirmedOrders = new ConfirmedOrders(context);
        manager = staff("WAREHOUSE_MANAGER", HCM);
        picker = staff("WAREHOUSE_STAFF", HCM);
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, CUSTOMER, 10);
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------

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

    private ResultActions as(UUID user, String role, MockHttpServletRequestBuilder request) throws Exception {
        String value = UUID.randomUUID().toString();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(user.toString()).claim("roles", List.of(role)).build());
        return mvc.perform(request.header("Authorization", "Bearer " + value));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    /** A CONFIRMED order of 4 cartons of 10 released to HCM, with 5 cartons in stock there: READY_TO_FULFILL. */
    private UUID releasedOrderOfFourCartons() {
        CustomerSkuRows.stock(jdbc, tx, sku.sku(), 5 * sku.unitsPerCarton());
        return confirmedOrders.released(CUSTOMER, HCM, manager,
                ConfirmedOrders.Line.cartons(sku, 4, UNIT_PRICE)).orderId();
    }

    /** What the order's task is, once the picker started it. */
    private record Picking(UUID task, UUID firstLine, String location) {
    }

    private Picking pickingStarted(UUID orderId) throws Exception {
        UUID task = UUID.fromString(data(as(manager, "WAREHOUSE_MANAGER", post(BASE + "/tasks")
                .contentType("application/json").content("{\"orderId\":\"" + orderId + "\",\"assignedUserId\":\""
                        + picker + "\"}")).andExpect(status().isCreated())).path("taskId").asText());
        as(picker, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + task + "/picking-start")).andExpect(status().isOk());
        Map<String, Object> line = jdbc.queryForMap("SELECT id, location_code FROM fulfillment.pick_line "
                + "WHERE pick_id = ? ORDER BY sequence_no, id LIMIT 1", task);
        return new Picking(task, (UUID) line.get("id"), (String) line.get("location_code"));
    }

    /** The order of 4 cartons with one carton reported short: held INVENTORY, a short line on its task. */
    private UUID orderHeldForOneMissingCarton() throws Exception {
        UUID orderId = releasedOrderOfFourCartons();
        Picking picking = pickingStarted(orderId);
        as(picker, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + picking.task() + "/pick-lines/" + picking.firstLine()
                + "/short").contentType("application/json").content("{\"locationCode\":\"" + picking.location()
                + "\",\"reason\":\"NOT_FOUND\"}")).andExpect(status().isOk());
        assertThat(orders.findById(orderId).orElseThrow().lifecycle().holdReason()).isEqualTo(HoldReason.INVENTORY);
        return orderId;
    }

    private ChatMessageView sendAsSales(UUID orderId, String note, boolean replace) {
        return WithCurrentUser.call(sales(), DataScope.ALL,
                () -> cardActions.sendShortageCard(sales(), CONVERSATION, orderId, note, replace));
    }

    private static ChatCardActions.Action choose(String option, Long version, String note) {
        return new ChatCardActions.Action("CHOOSE", version, null, note, null, null, option);
    }

    private UUID cardIdOf(ChatMessageView message) {
        return UUID.fromString(message.card().payload().path("cardId").asText());
    }

    private String chatState(UUID messageId) {
        return jdbc.queryForObject("SELECT card_state FROM chat.message WHERE id = ?", String.class, messageId);
    }

    private JsonNode chatPayload(UUID messageId) throws Exception {
        return json.readTree(jdbc.queryForObject("SELECT card_payload::text FROM chat.message WHERE id = ?",
                String.class, messageId));
    }

    private Map<String, Object> cardRow(UUID cardId) {
        return jdbc.queryForMap("SELECT status, ended_reason, chosen_option, superseded_by FROM ordering.shortage_card "
                + "WHERE id = ?", cardId);
    }

    private static void assertCode(Throwable thrown, ErrorCode code) {
        assertThat(thrown).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    // ---- preview, send, replace, choose -------------------------------------------------------------------------------

    @Test
    @DisplayName("17 §4.14: preview says why not while the order is not held; held for a missing carton it offers the "
            + "four ways with their figures; Sales send the card; a second one is refused unless it replaces the first; "
            + "the customer chooses once; Sales are told")
    void previewSendReplaceAndChoose() throws Exception {
        UUID orderId = releasedOrderOfFourCartons();
        assertThat(cards.preview(orderId).notSendable()).as("not held yet").isEqualTo(ShortageCardService.NotSendable.NOT_ON_HOLD);
        assertThatThrownBy(() -> sendAsSales(orderId, null, false)).satisfies(e -> assertCode(e,
                ErrorCode.SHORTAGE_CARD_NOT_SENDABLE));

        Picking picking = pickingStarted(orderId);
        assertThat(cards.preview(orderId).notSendable()).as("held for nothing").isEqualTo(ShortageCardService.NotSendable.NOT_ON_HOLD);
        as(picker, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + picking.task() + "/pick-lines/" + picking.firstLine()
                + "/short").contentType("application/json").content("{\"locationCode\":\"" + picking.location()
                + "\",\"reason\":\"NOT_FOUND\"}")).andExpect(status().isOk());

        ShortageCardService.Preview preview = cards.preview(orderId);
        assertThat(preview.sendable()).isTrue();
        assertThat(preview.batch().seq()).isEqualTo(1);
        assertThat(preview.lines()).singleElement().satisfies(line -> {
            assertThat(line.sku()).isEqualTo(sku.sku());
            assertThat(line.batchCartons()).isEqualTo(4);
            assertThat(line.availableCartons()).isEqualTo(3);
            assertThat(line.missingCartons()).isEqualTo(1);
            assertThat(line.unitsPerCarton()).isEqualTo(10);
        });
        assertThat(preview.options()).extracting(ShortageCards.Option::option).containsExactly(
                ShortageOption.WAIT_FOR_PRODUCTION, ShortageOption.REDUCE_CARTONS,
                ShortageOption.SHIP_PART_CLOSE_REST, ShortageOption.CANCEL_ORDER);
        ShortageCards.Option wait = preview.options().get(0);
        assertThat(wait.expectedDeliveryDate()).as("D48: no production order, the day plus the standard lead time")
                .isEqualTo(LocalDate.now(ConfirmedOrders.VIETNAM).plusDays(14));
        assertThat(preview.expectedDateSource()).isEqualTo(ShortageCard.DateSource.STANDARD_LEAD_TIME);
        // The closure's arithmetic: the last 10 of the line's 40 units, on its cumulative scale (15 §4.6 step 19).
        BigDecimal gross = jdbc.queryForObject("SELECT quantity * unit_price + coalesce(tax_amount, 0) FROM "
                + "ordering.order_line WHERE order_id = ?", BigDecimal.class, orderId);
        BigDecimal oneCarton = gross.subtract(gross.multiply(BigDecimal.valueOf(30)).divide(BigDecimal.valueOf(40), 0,
                RoundingMode.HALF_UP));
        assertThat(preview.options().get(1).amountReduced()).as("D51: always offered").isEqualByComparingTo(oneCarton);
        assertThat(preview.options().get(2).amountReduced()).as("the missing carton is all that closes, one batch")
                .isEqualByComparingTo(oneCarton);
        assertThat(preview.options().get(2).laterBatchesClosed()).isEmpty();
        assertThat(preview.options().get(3).refundUpTo()).isEqualByComparingTo(
                payments.paymentAmounts(List.of(orderId)).get(orderId).paid());
        assertThat(preview.options().get(3).feePossible()).as("no production started").isFalse();

        // Sales who are not in charge of the conversation send nothing (16 BR-15).
        CurrentUser stranger = new CurrentUser(staff("SALES_STAFF"), "someone.else", Set.of(Role.SALES_STAFF), Set.of(),
                DataScope.ALL, Set.of(), Set.of());
        assertThatThrownBy(() -> WithCurrentUser.call(stranger, DataScope.ALL,
                () -> cardActions.sendShortageCard(stranger, CONVERSATION, orderId, null, false)))
                .satisfies(e -> assertCode(e, ErrorCode.CHAT_NOT_ASSIGNEE));

        ChatMessageView first = sendAsSales(orderId, "Kho báo thiếu 1 thùng", false);
        UUID firstCard = cardIdOf(first);
        assertThat(first.card().kind().name()).isEqualTo("SHORTAGE_CHOICE");
        assertThat(first.card().state().name()).isEqualTo("OPEN");
        JsonNode payload = first.card().payload();
        assertThat(payload.path("status").asText()).isEqualTo("SENT");
        assertThat(payload.path("missingCartons").asInt()).isEqualTo(1);
        assertThat(payload.path("salesNote").asText()).isEqualTo("Kho báo thiếu 1 thùng");
        assertThat(payload.path("options")).hasSize(4);
        assertThat(payload.toString().toLowerCase()).as("nothing internal reaches the customer")
                .doesNotContain("lpn", "holdreason", "location");
        assertThat(jdbc.queryForObject("SELECT chat_message_id FROM ordering.shortage_card WHERE id = ?", UUID.class,
                firstCard)).as("the card knows its message").isEqualTo(first.id());
        assertThat(cards.preview(orderId).notSendable()).isEqualTo(ShortageCardService.NotSendable.LIVE_CARD);
        assertThatThrownBy(() -> sendAsSales(orderId, null, false)).satisfies(e -> assertCode(e,
                ErrorCode.SHORTAGE_CARD_NOT_SENDABLE));

        // Replacing: the old card is superseded - in the document and in the chat - and the batch has one live card.
        ChatMessageView second = sendAsSales(orderId, null, true);
        UUID secondCard = cardIdOf(second);
        assertThat(cardRow(firstCard)).containsEntry("status", "SUPERSEDED").containsEntry("ended_reason", "REPLACED")
                .containsEntry("superseded_by", secondCard);
        Await.until("the old card superseded in the chat", ASYNC, () -> "SUPERSEDED".equals(chatState(first.id())));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.shortage_card WHERE order_id = ? AND status IN "
                + "('SENT', 'ANSWERED')", Long.class, orderId)).isEqualTo(1L);
        assertThatThrownBy(() -> cardActions.customerAct(portal, CONVERSATION, first.id(),
                choose("REDUCE_CARTONS", null, null))).satisfies(e -> assertCode(e, ErrorCode.CHAT_CARD_ACTION_NOT_ALLOWED));

        // The customer chooses, on the card: an unknown option is refused, a staff press too.
        assertThatThrownBy(() -> cardActions.customerAct(portal, CONVERSATION, second.id(), choose("SWAP", null, null)))
                .satisfies(e -> assertCode(e, ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> WithCurrentUser.call(sales(), DataScope.ALL, () -> cardActions.staffAct(sales(),
                CONVERSATION, second.id(), choose("REDUCE_CARTONS", null, null))))
                .satisfies(e -> assertCode(e, ErrorCode.CHAT_CARD_ACTION_NOT_ALLOWED));
        long version = second.card().payload().path("version").asLong();
        assertThatThrownBy(() -> cardActions.customerAct(portal, CONVERSATION, second.id(),
                choose("REDUCE_CARTONS", version + 5, null))).as("a stale version").satisfies(e -> assertCode(e,
                ErrorCode.SHORTAGE_CARD_STATE_CONFLICT));
        ChatMessageView answered = cardActions.customerAct(portal, CONVERSATION, second.id(),
                choose("REDUCE_CARTONS", version, "Giảm 1 thùng giúp em"));
        assertThat(answered.card().state().name()).as("still open: what it offers follows its status").isEqualTo("OPEN");
        assertThat(answered.card().payload().path("status").asText()).isEqualTo("ANSWERED");
        assertThat(answered.card().payload().path("chosen").path("option").asText()).isEqualTo("REDUCE_CARTONS");
        assertThat(answered.card().payload().path("chosen").path("note").asText()).isEqualTo("Giảm 1 thùng giúp em");
        assertThat(cardRow(secondCard)).containsEntry("status", "ANSWERED").containsEntry("chosen_option", "REDUCE_CARTONS");
        assertThat(jdbc.queryForObject("SELECT chosen_by FROM ordering.shortage_card WHERE id = ?", UUID.class,
                secondCard)).as("who pressed").isEqualTo(CUSTOMER_ACCOUNT);
        assertThatThrownBy(() -> cardActions.customerAct(portal, CONVERSATION, second.id(),
                choose("CANCEL_ORDER", null, null))).as("one choice per card").satisfies(e -> assertCode(e,
                ErrorCode.CHAT_CARD_ACTION_NOT_ALLOWED));
        assertThatThrownBy(() -> cards.chooseOnOrder(CUSTOMER, CUSTOMER_ACCOUNT, orderId, secondCard,
                ShortageOption.CANCEL_ORDER, null, null)).as("nor on the order page").satisfies(e -> assertCode(e,
                ErrorCode.SHORTAGE_CARD_STATE_CONFLICT));
        Await.until("Sales told of the choice", ASYNC, () -> jdbc.queryForObject("SELECT count(*) FROM "
                + "notification.user_notification WHERE dedupe_key = ?", Long.class,
                "shortage:" + secondCard + ":CHOSEN") > 0);

        // Another customer's card and order are not found; the live card reads as it stands.
        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> cards.chooseOnOrder(other, CUSTOMER_ACCOUNT, orderId, secondCard,
                ShortageOption.CANCEL_ORDER, null, null)).satisfies(e -> assertCode(e, ErrorCode.SHORTAGE_CARD_NOT_FOUND));
        assertThatThrownBy(() -> cards.liveCardOf(other, orderId)).satisfies(e -> assertCode(e, ErrorCode.NOT_FOUND));
        assertThat(cards.liveCardOf(CUSTOMER, orderId)).hasValueSatisfying(card -> {
            assertThat(card.cardId()).isEqualTo(secondCard);
            assertThat(card.status()).isEqualTo(ShortageCardStatus.ANSWERED);
            assertThat(card.chosen().option()).isEqualTo(ShortageOption.REDUCE_CARTONS);
        });
        assertThat(orders.findById(orderId).orElseThrow().status()).as("the card changes no order (16 BR-03)")
                .isEqualTo(OrderStatus.ON_HOLD);
    }

    @Test
    @DisplayName("the customer chooses on the order page: the same operation as the chat's, and the chat card follows")
    void theCustomerChoosesOnTheOrderPage() throws Exception {
        UUID orderId = orderHeldForOneMissingCarton();
        ChatMessageView sent = sendAsSales(orderId, null, false);
        UUID cardId = cardIdOf(sent);
        ShortageCards.Card chosen = cards.chooseOnOrder(CUSTOMER, CUSTOMER_ACCOUNT, orderId, cardId,
                ShortageOption.WAIT_FOR_PRODUCTION, null, 0L);
        assertThat(chosen.status()).isEqualTo(ShortageCardStatus.ANSWERED);
        Await.until("the chat card shows the choice", ASYNC, () -> {
            try {
                return "ANSWERED".equals(chatPayload(sent.id()).path("status").asText());
            } catch (Exception unreadable) {
                return false;
            }
        });
        assertThat(chatPayload(sent.id()).path("chosen").path("option").asText()).isEqualTo("WAIT_FOR_PRODUCTION");
        assertThat(chatState(sent.id())).isEqualTo("OPEN");
        // The customer's own order only: a card named with another order is not found.
        assertThatThrownBy(() -> cards.chooseOnOrder(CUSTOMER, CUSTOMER_ACCOUNT, UUID.randomUUID(), cardId,
                ShortageOption.REDUCE_CARTONS, null, null)).satisfies(e -> assertCode(e,
                ErrorCode.SHORTAGE_CARD_NOT_FOUND));
    }

    // ---- ending -------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("17 §4.14 step 60: the warehouse manager replaces the missing carton - the live card expires "
            + "(REALLOCATED), in the document and in the chat")
    void expiresWhenTheWarehouseReplacesTheCarton() throws Exception {
        UUID orderId = releasedOrderOfFourCartons();
        Picking picking = pickingStarted(orderId);
        as(picker, "WAREHOUSE_STAFF", post(BASE + "/tasks/" + picking.task() + "/pick-lines/" + picking.firstLine()
                + "/short").contentType("application/json").content("{\"locationCode\":\"" + picking.location()
                + "\",\"reason\":\"NOT_FOUND\"}")).andExpect(status().isOk());
        ChatMessageView sent = sendAsSales(orderId, null, false);
        UUID cardId = cardIdOf(sent);

        as(manager, "WAREHOUSE_MANAGER", post(BASE + "/tasks/" + picking.task() + "/pick-lines/" + picking.firstLine()
                + "/reallocation")).andExpect(status().isOk());

        assertThat(cardRow(cardId)).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "REALLOCATED");
        Await.until("the chat card expired", ASYNC, () -> "EXPIRED".equals(chatState(sent.id())));
        assertThat(chatPayload(sent.id()).path("endedReason").asText()).isEqualTo("REALLOCATED");
        assertThat(cards.preview(orderId).notSendable()).as("nothing is short or held any more")
                .isEqualTo(ShortageCardService.NotSendable.NOT_ON_HOLD);
        assertThatThrownBy(() -> cards.chooseOnOrder(CUSTOMER, CUSTOMER_ACCOUNT, orderId, cardId,
                ShortageOption.REDUCE_CARTONS, null, null)).satisfies(e -> assertCode(e,
                ErrorCode.SHORTAGE_CARD_STATE_CONFLICT));
    }

    @Test
    @DisplayName("a card answered ends too when the order is cancelled by another route - the choice stays on record")
    void expiresWhenTheOrderIsCancelled() throws Exception {
        UUID orderId = orderHeldForOneMissingCarton();
        ChatMessageView sent = sendAsSales(orderId, null, false);
        UUID cardId = cardIdOf(sent);
        cardActions.customerAct(portal, CONVERSATION, sent.id(), choose("WAIT_FOR_PRODUCTION", null, null));

        WithCurrentUser.run(com.stockflow.support.TestUsers.admin(), DataScope.ALL,
                () -> orders.cancel(orderId, CancelOrderCommand.fromText("CUSTOMER_REQUEST", sales().userId())));

        assertThat(cardRow(cardId)).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "ORDER_CANCELLED")
                .containsEntry("chosen_option", "WAIT_FOR_PRODUCTION");
        Await.until("the chat card expired", ASYNC, () -> "EXPIRED".equals(chatState(sent.id())));
    }

    @Test
    @DisplayName("D47: Sales lift the hold by hand without the card - it ends as decided without the card")
    void expiresWhenSalesDecideWithoutTheCard() throws Exception {
        UUID orderId = orderHeldForOneMissingCarton();
        ChatMessageView sent = sendAsSales(orderId, null, false);
        UUID cardId = cardIdOf(sent);

        WithCurrentUser.run(sales(), DataScope.ALL, () -> operations.resolveHold(orderId,
                "Khách đồng ý giảm số thùng qua điện thoại", sales().userId()));

        assertThat(cardRow(cardId)).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "DECIDED_WITHOUT_CARD");
        Await.until("the chat card expired", ASYNC, () -> "EXPIRED".equals(chatState(sent.id())));
        assertThat(ShortageCardEnd.valueOf((String) cardRow(cardId).get("ended_reason")))
                .isEqualTo(ShortageCardEnd.DECIDED_WITHOUT_CARD);
    }

    @Test
    @DisplayName("a hold lifted by another path than the reallocation (the plain resolveHold, e.g. partial shipment "
            + "after a carton was removed) ends the card as HOLD_RESOLVED_ELSEWHERE, not REALLOCATED")
    void aHoldLiftedElsewhereIsNotAReallocation() throws Exception {
        UUID orderId = orderHeldForOneMissingCarton();
        ChatMessageView sent = sendAsSales(orderId, null, false);
        UUID cardId = cardIdOf(sent);

        WithCurrentUser.run(sales(), DataScope.ALL,
                () -> orders.resolveHold(orderId, "Sale cho giao một phần", sales().userId()));

        assertThat(cardRow(cardId)).containsEntry("status", "EXPIRED")
                .containsEntry("ended_reason", "HOLD_RESOLVED_ELSEWHERE");
        Await.until("the chat card expired", ASYNC, () -> "EXPIRED".equals(chatState(sent.id())));
        assertThat(chatPayload(sent.id()).path("endedReason").asText()).isEqualTo("HOLD_RESOLVED_ELSEWHERE");
    }
}
