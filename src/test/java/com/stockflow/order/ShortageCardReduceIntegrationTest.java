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
import com.stockflow.order.internal.service.OrderReleases;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Giảm số thùng" carried out (kltn-docs 17 §4.14 step 58, D45, D51, D52; 15 §4.6 step 19), and the card's short code
 * (UNH-CODE-1128): an order released to HCM, one carton reported short - held INVENTORY - a card sent, the customer
 * chooses to drop the missing cartons, Sales press "Thực hiện". Each test builds its own order.
 */
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {"stockflow.security.enabled=true", "stockflow.catalog.projection-retry-ms=3600000",
        "stockflow.upload-inspection.enabled=false"})
class ShortageCardReduceIntegrationTest {

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

    @BeforeEach
    void setUp() {
        confirmedOrders = new ConfirmedOrders(context);
        manager = staff("WAREHOUSE_MANAGER", HCM);
        picker = staff("WAREHOUSE_STAFF", HCM);
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

    /** The task and the line reported short. */
    private record Held(UUID orderId, UUID task, UUID shortLine) {
    }

    /**
     * A CONFIRMED order of {@code cartons} cartons over {@code batches} batches, paid on signing, its first batch released
     * to HCM; the picker reports the first carton short: ON_HOLD(INVENTORY).
     */
    private Held heldForOneMissingCarton(int cartons, int batches) throws Exception {
        CustomerSkuRows.stock(jdbc, tx, sku.sku(), cartons * sku.unitsPerCarton());
        UUID orderId = confirmedOrders.pay(confirmedOrders.signedInBatches(CUSTOMER, batches,
                ConfirmedOrders.Line.cartons(sku, cartons, UNIT_PRICE))).orderId();
        confirmedOrders.release(orderId, HCM, manager);
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

    /** Sales send the card; the customer chooses to reduce the cartons. Returns the card's chat payload. */
    private JsonNode reduceChosen(UUID orderId) {
        ChatMessageView sent = WithCurrentUser.call(confirmedOrders.sales(), DataScope.ALL,
                () -> cardActions.sendShortageCard(confirmedOrders.sales(), CONVERSATION, orderId, null, false));
        cardActions.customerAct(portal, CONVERSATION, sent.id(),
                new ChatCardActions.Action("CHOOSE", null, null, null, null, null, "REDUCE_CARTONS"));
        return sent.card().payload();
    }

    private String reduce(UUID orderId, UUID cardId) {
        return "/api/v1/orders/" + orderId + "/shortage-cards/" + cardId + "/execution/reduce-cartons";
    }

    private long version(UUID cardId) {
        return jdbc.queryForObject("SELECT version FROM ordering.shortage_card WHERE id = ?", Long.class, cardId);
    }

    private BigDecimal amountReduced(UUID cardId) throws Exception {
        for (JsonNode option : json.readTree(jdbc.queryForObject(
                "SELECT amounts::text FROM ordering.shortage_card WHERE id = ?", String.class, cardId))) {
            if ("REDUCE_CARTONS".equals(option.path("option").asText())) {
                return option.path("amountReduced").decimalValue();
            }
        }
        throw new AssertionError("the card offers no REDUCE_CARTONS");
    }

    private boolean notified(UUID user, UUID cardId) {
        return jdbc.queryForObject("SELECT count(*) FROM notification.user_notification WHERE recipient_id = ? "
                + "AND dedupe_key = ?", Long.class, user, "shortage:" + cardId + ":EXECUTED") > 0;
    }

    // ---- the card's code (UNH-CODE-1128) -----------------------------------------------------------------------------

    @Test
    @DisplayName("UNH-CODE-1128: a card carries TT-yyyyMMdd-NNNN in its payload; the steps' notes name it, never the id")
    void theCardHasAShortCodeTheNotesName() throws Exception {
        Held held = heldForOneMissingCarton(4, 2);
        JsonNode payload = reduceChosen(held.orderId());
        UUID cardId = UUID.fromString(payload.path("cardId").asText());
        String code = payload.path("code").asText();
        assertThat(code).matches("TT-\\d{8}-\\d{4,}")
                .isEqualTo(jdbc.queryForObject("SELECT code FROM ordering.shortage_card WHERE id = ?", String.class,
                        cardId));

        as(sales(), "SALES_STAFF", post(reduce(held.orderId(), cardId)).contentType("application/json")
                .content("{\"version\":" + version(cardId) + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.code").value(code));

        List<String> notes = jdbc.queryForList("SELECT resolution_note FROM ordering.order_hold WHERE order_id = ? "
                + "AND resolution_note IS NOT NULL", String.class, held.orderId());
        assertThat(notes).anyMatch(n -> n.contains(code)).noneMatch(n -> n.contains(cardId.toString()));
    }

    // ---- reduce the cartons -----------------------------------------------------------------------------------------

    @Test
    @DisplayName("D45: exactly the missing carton comes off the line and the batch - the signed contract unchanged - the "
            + "installments owe less by the card's figure (paid, so refundable), the hold is lifted, the warehouse "
            + "manager is told; a second press is refused")
    void reduceDropsExactlyTheMissingCarton() throws Exception {
        Held held = heldForOneMissingCarton(4, 2);
        UUID cardId = UUID.fromString(reduceChosen(held.orderId()).path("cardId").asText());
        BigDecimal shown = amountReduced(cardId);
        List<Integer> signedBefore = jdbc.queryForList("SELECT l.cartons FROM ordering.delivery_batch_line l "
                + "JOIN ordering.delivery_batch b ON b.id = l.delivery_batch_id JOIN ordering.sales_contract c "
                + "ON c.id = b.contract_id WHERE c.order_id = ? ORDER BY b.seq", Integer.class, held.orderId());

        as(sales(), "SALES_STAFF", post(reduce(held.orderId(), cardId)).contentType("application/json")
                .content("{\"version\":" + version(cardId) + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("EXECUTED"));

        assertThat(orders.findById(held.orderId()).orElseThrow().status()).isNotEqualTo(OrderStatus.ON_HOLD);
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.pick_line WHERE id = ?", String.class,
                held.shortLine())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT cancelled_quantity FROM ordering.order_line WHERE order_id = ?",
                Integer.class, held.orderId())).as("one carton off the line").isEqualTo(sku.unitsPerCarton());
        assertThat(jdbc.queryForList("SELECT l.cartons FROM ordering.delivery_batch_line l "
                + "JOIN ordering.delivery_batch b ON b.id = l.delivery_batch_id JOIN ordering.sales_contract c "
                + "ON c.id = b.contract_id WHERE c.order_id = ? ORDER BY b.seq", Integer.class, held.orderId()))
                .as("the signed contract keeps its cartons (no annex)").isEqualTo(signedBefore).containsExactly(2, 2);
        ShippingWarehouseService.Coverage coverage = WithCurrentUser.call(TestUsers.admin(), DataScope.ALL,
                () -> shippingWarehouses.coverage(held.orderId()));
        assertThat(coverage.batches().getFirst().lines()).singleElement()
                .satisfies(l -> assertThat(l.shortCartons()).as("the batch carries one carton less: nothing lacks, "
                        + "no production order to draw").isZero());
        Map<String, Object> settlement = jdbc.queryForMap("SELECT kind, closed_amount, refundable_amount, fee_amount "
                + "FROM payment.order_settlement WHERE source_id = ?", cardId);
        assertThat((BigDecimal) settlement.get("closed_amount")).as("the figure the card showed")
                .isEqualByComparingTo(shown);
        assertThat((BigDecimal) settlement.get("refundable_amount")).as("paid on signing: all of it is refundable")
                .isEqualByComparingTo(shown);
        assertThat((BigDecimal) settlement.get("fee_amount")).as("no fee").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.order_remainder_closure WHERE closure_id = ?",
                Long.class, cardId)).as("the batch goes on: no closure").isZero();
        Await.until("the warehouse manager told", ASYNC, () -> notified(manager, cardId));

        as(sales(), "SALES_STAFF", post(reduce(held.orderId(), cardId)).contentType("application/json").content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value("SHORTAGE_CARD_STATE_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.order_settlement WHERE source_id = ?", Long.class,
                cardId)).as("settled once").isEqualTo(1L);
    }

    @Test
    @DisplayName("D52: the batch emptied with a later one to come - closed like a closure of the rest for that batch: "
            + "its money settled, an entry of the order's closures, the order waits between batches and the next "
            + "batch is released")
    void anEmptiedMiddleBatchIsClosedAndTheNextReleased() throws Exception {
        Held held = heldForOneMissingCarton(2, 2);
        UUID cardId = UUID.fromString(reduceChosen(held.orderId()).path("cardId").asText());
        BigDecimal shown = amountReduced(cardId);

        as(sales(), "SALES_STAFF", post(reduce(held.orderId(), cardId)).contentType("application/json")
                .content("{\"version\":" + version(cardId) + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("EXECUTED"));

        assertThat(orders.findById(held.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.PARTIALLY_FULFILLED);
        assertThat((BigDecimal) jdbc.queryForObject("SELECT closed_amount FROM payment.order_settlement "
                + "WHERE source_id = ?", BigDecimal.class, cardId)).isEqualByComparingTo(shown);
        assertThat(jdbc.queryForMap("SELECT reason_code, closed_by FROM ordering.order_remainder_closure "
                + "WHERE closure_id = ?", cardId)).containsEntry("reason_code", "OUT_OF_STOCK")
                .containsEntry("closed_by", sales());

        WithCurrentUser.call(confirmedOrders.sales(), DataScope.ALL,
                () -> context.getBean(OrderReleases.class).release(held.orderId(), HCM, manager));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.delivery_batch_release WHERE order_id = ?",
                Long.class, held.orderId())).as("batch 2 released after the emptied batch 1 (17 BR-29)").isEqualTo(2L);
    }

    @Test
    @DisplayName("D52 gap: the only batch emptied with nothing ever handed over would leave an order with nothing to "
            + "deliver - no rule ends it so; refused, nothing changes")
    void anOrderLeftWithNothingIsRefused() throws Exception {
        Held held = heldForOneMissingCarton(1, 1);
        UUID cardId = UUID.fromString(reduceChosen(held.orderId()).path("cardId").asText());

        as(sales(), "SALES_STAFF", post(reduce(held.orderId(), cardId)).contentType("application/json")
                .content("{\"version\":" + version(cardId) + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SHORTAGE_OPTION_NOT_AVAILABLE"));

        assertThat(jdbc.queryForObject("SELECT status FROM ordering.shortage_card WHERE id = ?", String.class, cardId))
                .isEqualTo("ANSWERED");
        assertThat(jdbc.queryForObject("SELECT status FROM fulfillment.pick_line WHERE id = ?", String.class,
                held.shortLine())).isEqualTo("SHORT");
        assertThat(orders.findById(held.orderId()).orElseThrow().status()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.order_settlement WHERE source_id = ?", Long.class,
                cardId)).isZero();
    }
}
