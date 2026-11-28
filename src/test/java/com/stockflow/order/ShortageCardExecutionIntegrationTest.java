package com.stockflow.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.chat.internal.service.ChatCardActions;
import com.stockflow.chat.internal.service.ChatMessageView;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.service.DeliveryBatchService;
import com.stockflow.order.internal.service.ShippingWarehouseService;
import com.stockflow.support.Await;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestUsers;
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

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sales carry out the customer's choice on a shortage card (kltn-docs 17 §4.14 step 58, BR-28, BR-32, BR-33; D48, D49):
 * an order of 4 cartons released to HCM, one carton reported short - held INVENTORY - a card sent, the customer chooses,
 * Sales press "Thực hiện". Each test builds its own order; requests go through the endpoints with real roles.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000",
        "stockflow.upload-inspection.enabled=false"})
class ShortageCardExecutionIntegrationTest {

    private static final UUID HCM = DemoData.WAREHOUSE_HCM;
    private static final UUID CUSTOMER = DemoData.CUSTOMER_ID;
    private static final UUID CUSTOMER_ACCOUNT = DemoData.demoId("demo:wf02:user:demo.khachhang");
    private static final UUID CONVERSATION = DemoData.demoId("demo:wf02:conversation:ds02a");
    private static final String FULFILLMENT = "/api/v1/fulfillment";
    private static final Duration ASYNC = Duration.ofSeconds(20);
    private static final long UNIT_PRICE = 6_000_000L;

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired OrderService orders;
    @Autowired ChatCardActions cardActions;
    @Autowired ShippingWarehouseService shippingWarehouses;
    @Autowired DeliveryBatchService batches;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired ApplicationContext context;
    @Autowired ObjectMapper json;

    private ConfirmedOrders confirmedOrders;
    private CustomerSkuRows.Issued sku;
    private UUID manager;
    private UUID picker;
    private UUID coordinator;

    private final CurrentUser portal = new CurrentUser(CUSTOMER_ACCOUNT, "demo.khachhang", Set.of(Role.CUSTOMER),
            Set.of(), DataScope.ALL, Set.of(), Set.of());

    @BeforeEach
    void setUp() {
        confirmedOrders = new ConfirmedOrders(context);
        manager = staff("WAREHOUSE_MANAGER", HCM);
        picker = staff("WAREHOUSE_STAFF", HCM);
        coordinator = staff("PRODUCTION_COORDINATOR");
        sku = CustomerSkuRows.issue(jdbc, tx, entityManager, CUSTOMER, 10);
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------------

    private UUID sales() {
        return confirmedOrders.sales().userId();
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

    private ResultActions as(UUID user, String role, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token(user, role)));
    }

    private String token(UUID user, String role) {
        String value = UUID.randomUUID().toString();
        when(decoder.decode(value)).thenReturn(Jwt.withTokenValue(value).header("alg", "RS256")
                .subject(user.toString()).claim("roles", List.of(role)).build());
        return value;
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    /** Task and the line reported short. */
    private record Held(UUID orderId, UUID task, UUID shortLine) {
    }

    /** A CONFIRMED order of 4 cartons released to HCM; the picker reports the first carton short: ON_HOLD(INVENTORY). */
    private Held orderHeldForOneMissingCarton() throws Exception {
        CustomerSkuRows.stock(jdbc, tx, sku.sku(), 4 * sku.unitsPerCarton());
        UUID orderId = confirmedOrders.released(CUSTOMER, HCM, manager,
                ConfirmedOrders.Line.cartons(sku, 4, UNIT_PRICE)).orderId();
        UUID task = UUID.fromString(data(as(manager, "WAREHOUSE_MANAGER", post(FULFILLMENT + "/tasks")
                .contentType("application/json").content("{\"orderId\":\"" + orderId + "\",\"assignedUserId\":\""
                        + picker + "\"}")).andExpect(status().isCreated())).path("taskId").asText());
        as(picker, "WAREHOUSE_STAFF", post(FULFILLMENT + "/tasks/" + task + "/picking-start")).andExpect(status().isOk());
        Map<String, Object> line = jdbc.queryForMap("SELECT id, location_code FROM fulfillment.pick_line "
                + "WHERE pick_id = ? ORDER BY sequence_no, id LIMIT 1", task);
        as(picker, "WAREHOUSE_STAFF", post(FULFILLMENT + "/tasks/" + task + "/pick-lines/" + line.get("id") + "/short")
                .contentType("application/json").content("{\"locationCode\":\"" + line.get("location_code")
                        + "\",\"reason\":\"NOT_FOUND\"}")).andExpect(status().isOk());
        assertThat(orders.findById(orderId).orElseThrow().lifecycle().holdReason()).isEqualTo(HoldReason.INVENTORY);
        return new Held(orderId, task, (UUID) line.get("id"));
    }

    /** Sales send the card; the customer chooses {@code option} on it. */
    private UUID cardChosen(UUID orderId, String option) {
        ChatMessageView sent = WithCurrentUser.call(confirmedOrders.sales(), DataScope.ALL,
                () -> cardActions.sendShortageCard(confirmedOrders.sales(), CONVERSATION, orderId, null, false));
        cardActions.customerAct(portal, CONVERSATION, sent.id(),
                new ChatCardActions.Action("CHOOSE", null, null, null, null, null, option));
        return UUID.fromString(sent.card().payload().path("cardId").asText());
    }

    private String execute(UUID orderId, UUID cardId, String step) {
        return "/api/v1/orders/" + orderId + "/shortage-cards/" + cardId + "/execution/" + step;
    }

    private Map<String, Object> card(UUID cardId) {
        return jdbc.queryForMap("SELECT status, ended_reason, executed_by, execution_started_at, version "
                + "FROM ordering.shortage_card WHERE id = ?", cardId);
    }

    private long version(UUID cardId) {
        return ((Number) card(cardId).get("version")).longValue();
    }

    private String lineStatus(UUID pickLine) {
        return jdbc.queryForObject("SELECT status FROM fulfillment.pick_line WHERE id = ?", String.class, pickLine);
    }

    private UUID holdOf(UUID pickLine) {
        return jdbc.queryForObject("SELECT reservation_id FROM fulfillment.pick_line WHERE id = ?", UUID.class, pickLine);
    }

    private boolean notified(UUID user, UUID cardId, String change) {
        return jdbc.queryForObject("SELECT count(*) FROM notification.user_notification WHERE recipient_id = ? "
                + "AND dedupe_key = ?", Long.class, user, "shortage:" + cardId + ":" + change) > 0;
    }

    private long onBehalf(UUID orderId, String action) {
        return jdbc.queryForObject("SELECT count(*) FROM ordering.order_on_behalf_action WHERE order_id = ? "
                + "AND action = ?", Long.class, orderId, action);
    }

    // ---- wait for production ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("17 §4.14 step 58, D49: waiting gives the short line up - its hold released, off the order line, the "
            + "batch lacks exactly the missing carton for a production order - lifts the hold, executes the card and "
            + "tells the warehouse manager and the production coordinator")
    void waitForProduction() throws Exception {
        Held held = orderHeldForOneMissingCarton();
        UUID cardId = cardChosen(held.orderId(), "WAIT_FOR_PRODUCTION");
        UUID hold = holdOf(held.shortLine());

        as(sales(), "SALES_STAFF", post(execute(held.orderId(), cardId, "wait-for-production"))
                .contentType("application/json").content("{\"version\":" + version(cardId) + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("EXECUTED"));

        assertThat(card(cardId)).containsEntry("status", "EXECUTED").containsEntry("executed_by", sales());
        assertThat(orders.findById(held.orderId()).orElseThrow().status()).isNotEqualTo(OrderStatus.ON_HOLD);
        assertThat(lineStatus(held.shortLine())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForMap("SELECT status, release_reason FROM inventory.stock_reservation WHERE id = ?", hold))
                .as("released as a reallocation releases it; a later count approval finds no live hold to give up")
                .containsEntry("status", "RELEASED").containsEntry("release_reason", "SHORT_PICKED");
        assertThat(orders.findById(held.orderId()).orElseThrow().lines().getFirst().reservationIds())
                .doesNotContain(hold);
        ShippingWarehouseService.Coverage coverage = WithCurrentUser.call(TestUsers.admin(), DataScope.ALL,
                () -> shippingWarehouses.coverage(held.orderId()));
        assertThat(coverage.batches().getFirst().lines()).singleElement()
                .satisfies(l -> assertThat(l.shortCartons()).as("a production order makes exactly the carton missing")
                        .isEqualTo(1));
        assertThat(onBehalf(held.orderId(), "EXECUTE_SHORTAGE_CHOICE")).as("the owner's own work").isZero();
        Await.until("the warehouse manager and the coordinator told", ASYNC,
                () -> notified(manager, cardId, "EXECUTED") && notified(coordinator, cardId, "EXECUTED"));

        // A second press: the card is carried out already.
        as(sales(), "SALES_STAFF", post(execute(held.orderId(), cardId, "wait-for-production"))
                .contentType("application/json").content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("SHORTAGE_CARD_STATE_CONFLICT"));
    }

    @Test
    @DisplayName("D48: the card's date after the window - Sales confirm the move; the window starts on that date, ends "
            + "where Sales say, the history names the card; a move the card does not allow changes nothing")
    void waitMovesTheWindowWithTheCardAsEvidence() throws Exception {
        Held held = orderHeldForOneMissingCarton();
        UUID cardId = cardChosen(held.orderId(), "WAIT_FOR_PRODUCTION");
        JsonNode wait = json.readTree(jdbc.queryForObject("SELECT amounts::text FROM ordering.shortage_card WHERE id = ?",
                String.class, cardId)).get(0);
        assertThat(wait.path("afterWindow").asBoolean()).as("fixture: the lead time ends after the window").isTrue();
        LocalDate expected = jdbc.queryForObject("SELECT expected_delivery_date FROM ordering.shortage_card WHERE id = ?",
                LocalDate.class, cardId);

        // Refused before anything changes: the end before the start.
        as(sales(), "SALES_STAFF", post(execute(held.orderId(), cardId, "wait-for-production"))
                .contentType("application/json").content("{\"moveWindow\":true,\"windowEnd\":\"" + expected.minusDays(1)
                        + "\"}")).andExpect(status().isBadRequest());
        assertThat(card(cardId)).containsEntry("status", "ANSWERED");
        assertThat(lineStatus(held.shortLine())).as("nothing given up").isEqualTo("SHORT");
        assertThat(orders.findById(held.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.ON_HOLD);

        as(sales(), "SALES_STAFF", post(execute(held.orderId(), cardId, "wait-for-production"))
                .contentType("application/json").content("{\"moveWindow\":true,\"windowEnd\":\"" + expected.plusDays(3)
                        + "\",\"note\":\"Khách đồng ý nhận sau\"}")).andExpect(status().isOk());

        Map<String, Object> moved = jdbc.queryForMap("SELECT new_window_start, new_window_end, reason_code, note, "
                + "evidence_shortage_card_id, evidence_file_key, evidence_message_id FROM "
                + "ordering.delivery_batch_window_change WHERE order_id = ?", held.orderId());
        assertThat(moved).containsEntry("new_window_start", java.sql.Date.valueOf(expected))
                .containsEntry("new_window_end", java.sql.Date.valueOf(expected.plusDays(3)))
                .containsEntry("reason_code", "SELLER_DELAY").containsEntry("evidence_shortage_card_id", cardId);
        assertThat(moved.get("evidence_file_key")).isNull();
        assertThat((String) moved.get("note")).contains(cardId.toString()).contains("Khách đồng ý nhận sau");
        DeliveryBatchService.Schedule schedule = WithCurrentUser.call(TestUsers.admin(), DataScope.ALL,
                () -> batches.schedule(held.orderId()));
        assertThat(schedule.batches().getFirst().windowStart()).isEqualTo(expected);
        assertThat(schedule.batches().getFirst().windowChanges()).singleElement()
                .satisfies(c -> assertThat(c.evidenceShortageCardId()).isEqualTo(cardId));
        assertThat(card(cardId)).containsEntry("status", "EXECUTED");
    }

    // ---- ship part, close the rest -------------------------------------------------------------------------------------

    @Test
    @DisplayName("ship part: step 1 allows partial shipment and lifts the hold; close-rest before it is refused; the "
            + "partial check tells Sales to close the rest; closing executes the card")
    void shipPartThenCloseTheRest() throws Exception {
        Held held = orderHeldForOneMissingCarton();
        UUID cardId = cardChosen(held.orderId(), "SHIP_PART_CLOSE_REST");
        String closeRest = "{\"reasonCode\":\"OUT_OF_STOCK\"}";

        as(sales(), "SALES_STAFF", post(execute(held.orderId(), cardId, "close-rest"))
                .contentType("application/json").content(closeRest))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("SHORTAGE_CARD_STATE_CONFLICT"));
        as(sales(), "SALES_STAFF", post(execute(held.orderId(), cardId, "wait-for-production"))
                .contentType("application/json").content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SHORTAGE_OPTION_NOT_AVAILABLE"));

        as(sales(), "SALES_STAFF", post(execute(held.orderId(), cardId, "ship-part"))
                .contentType("application/json").content("{\"version\":" + version(cardId) + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.executionStarted").value(true));
        assertThat(card(cardId)).containsEntry("status", "ANSWERED");
        assertThat(card(cardId).get("execution_started_at")).isNotNull();
        var order = orders.findById(held.orderId()).orElseThrow();
        assertThat(order.status()).isNotEqualTo(OrderStatus.ON_HOLD);
        assertThat(order.lifecycle().allowPartialShipment()).isTrue();
        assertThat(lineStatus(held.shortLine())).isEqualTo("CANCELLED");
        Await.until("the warehouse manager told", ASYNC, () -> notified(manager, cardId, "EXECUTION_STARTED"));
        // Pressed again once started: the card as it is.
        as(sales(), "SALES_STAFF", post(execute(held.orderId(), cardId, "ship-part"))
                .contentType("application/json").content("{}")).andExpect(status().isOk());

        // The warehouse picks the three cartons there and completes the check in part: Sales are told to close the rest.
        checkInPart(held.task());
        assertThat(orders.findById(held.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        Await.until("Sales told to close the rest", ASYNC, () -> notified(sales(), cardId, "CLOSE_REST_DUE"));

        as(sales(), "SALES_STAFF", post(execute(held.orderId(), cardId, "close-rest"))
                .contentType("application/json").content(closeRest))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("EXECUTED"));
        assertThat(card(cardId)).containsEntry("status", "EXECUTED").containsEntry("executed_by", sales());
        assertThat(card(cardId).get("ended_reason")).as("carried out, not ended by the closure").isNull();
        assertThat(onBehalf(held.orderId(), "CLOSE_REMAINDER")).isZero();
    }

    /** The picker picks every open line, completes picking, checks and labels the cartons, completes in part. */
    private void checkInPart(UUID task) throws Exception {
        tx.executeWithoutResult(st -> jdbc.update("""
                UPDATE inventory.uom_conversions u SET length_cm = 40, width_cm = 30, height_cm = 30, weight_kg = 2
                  FROM inventory.inventory_items i
                 WHERE i.variant_id = u.variant_id AND i.sku = ? AND u.active""", sku.sku()));
        for (Map<String, Object> line : jdbc.queryForList("SELECT id, location_code, lpn FROM fulfillment.pick_line "
                + "WHERE pick_id = ? AND status = 'OPEN' ORDER BY sequence_no", task)) {
            as(picker, "WAREHOUSE_STAFF", post(FULFILLMENT + "/tasks/" + task + "/pick-lines/" + line.get("id")
                    + "/confirmation").contentType("application/json").content("{\"locationCode\":\""
                    + line.get("location_code") + "\",\"lpn\":\"" + line.get("lpn") + "\"}"))
                    .andExpect(status().isOk());
        }
        as(picker, "WAREHOUSE_STAFF", post(FULFILLMENT + "/tasks/" + task + "/picking-completion"))
                .andExpect(status().isOk());
        as(picker, "WAREHOUSE_STAFF", post(FULFILLMENT + "/tasks/" + task + "/packing-start")).andExpect(status().isOk());
        for (String lpn : jdbc.queryForList("SELECT lpn FROM fulfillment.pick_line WHERE pick_id = ? AND status = "
                + "'PICKED' ORDER BY sequence_no, id", String.class, task)) {
            as(picker, "WAREHOUSE_STAFF", post(FULFILLMENT + "/tasks/" + task + "/carton-checks")
                    .contentType("application/json").content("{\"lpn\":\"" + lpn + "\"}")).andExpect(status().isOk());
        }
        as(picker, "WAREHOUSE_STAFF", post(FULFILLMENT + "/tasks/" + task + "/carton-labels")).andExpect(status().isOk());
        as(picker, "WAREHOUSE_STAFF", post(FULFILLMENT + "/tasks/" + task + "/packing-completion")
                .contentType("application/json").content("{\"partial\":true,\"note\":\"Giao trước 3 thùng có sẵn\"}"))
                .andExpect(status().isOk());
    }

    // ---- cancel -------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("cancel, by Sales who are not the owner: the order cancelled OUT_OF_STOCK, the card EXECUTED (not "
            + "expired), one 'làm hộ' line for the press and none for the cancellation inside it")
    void cancelOnBehalf() throws Exception {
        Held held = orderHeldForOneMissingCarton();
        UUID cardId = cardChosen(held.orderId(), "CANCEL_ORDER");
        UUID colleague = staff("SALES_STAFF");

        as(colleague, "SALES_STAFF", post(execute(held.orderId(), cardId, "cancel")).contentType("application/json")
                .content("{\"version\":" + (version(cardId) + 3) + "}"))
                .andExpect(status().isConflict());
        as(colleague, "SALES_STAFF", post(execute(UUID.randomUUID(), cardId, "cancel")).contentType("application/json")
                .content("{}")).andExpect(status().isNotFound());

        as(colleague, "SALES_STAFF", post(execute(held.orderId(), cardId, "cancel")).contentType("application/json")
                .content("{\"version\":" + version(cardId) + ",\"note\":\"Khách huỷ vì thiếu hàng\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("EXECUTED"));

        assertThat(card(cardId)).containsEntry("status", "EXECUTED").containsEntry("executed_by", colleague);
        assertThat(card(cardId).get("ended_reason")).isNull();
        Map<String, Object> order = jdbc.queryForMap("SELECT status, cancellation_reason_code FROM "
                + "ordering.customer_order WHERE id = ?", held.orderId());
        assertThat(order).containsEntry("status", "CANCELLED").containsEntry("cancellation_reason_code", "OUT_OF_STOCK");
        assertThat(lineStatus(held.shortLine())).isEqualTo("CANCELLED");
        assertThat(onBehalf(held.orderId(), "EXECUTE_SHORTAGE_CHOICE")).isEqualTo(1L);
        assertThat(onBehalf(held.orderId(), "CANCEL_ORDER")).isZero();
        Await.until("the warehouse manager told", ASYNC, () -> notified(manager, cardId, "EXECUTED"));
        assertThat(notified(coordinator, cardId, "EXECUTED")).as("only a wait asks for production").isFalse();
    }

    // ---- locks ----------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("lock order: Sales carry out the wait while the warehouse manager reallocates the short line - no "
            + "deadlock, exactly one of them wins, the other is refused")
    void executionAndReallocationDoNotDeadlock() throws Exception {
        Held held = orderHeldForOneMissingCarton();
        UUID cardId = cardChosen(held.orderId(), "WAIT_FOR_PRODUCTION");
        // A carton to reallocate to.
        CustomerSkuRows.stock(jdbc, tx, sku.sku(), sku.unitsPerCarton());
        String salesToken = token(sales(), "SALES_STAFF");
        String managerToken = token(manager, "WAREHOUSE_MANAGER");

        CompletableFuture<Integer> execution = CompletableFuture.supplyAsync(() -> statusOf(post(execute(held.orderId(),
                cardId, "wait-for-production")).contentType("application/json").content("{}"), salesToken));
        CompletableFuture<Integer> reallocation = CompletableFuture.supplyAsync(() -> statusOf(post(FULFILLMENT
                + "/tasks/" + held.task() + "/pick-lines/" + held.shortLine() + "/reallocation"), managerToken));

        int executed = execution.get(60, TimeUnit.SECONDS);
        int reallocated = reallocation.get(60, TimeUnit.SECONDS);
        assertThat(List.of(executed, reallocated)).as("neither failed on a lock").doesNotContain(500);
        assertThat(List.of(executed, reallocated)).containsOnlyOnce(200);
        Map<String, Object> row = card(cardId);
        if (executed == 200) {
            assertThat(row).containsEntry("status", "EXECUTED");
        } else {
            assertThat(row).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "REALLOCATED");
        }
    }

    private int statusOf(MockHttpServletRequestBuilder request, String token) {
        try {
            return mvc.perform(request.header("Authorization", "Bearer " + token)).andReturn().getResponse().getStatus();
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
    }
}
