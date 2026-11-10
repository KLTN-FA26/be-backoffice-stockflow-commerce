package com.stockflow.order;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.PaymentCaptured;
import com.stockflow.contracts.PaymentMethod;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.support.Await;
import com.stockflow.support.DemoData;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import com.stockflow.support.TestUsers;
import com.stockflow.support.WithCurrentUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Team plan 5-2: nothing cancels an unpaid order when its stock hold runs out, so money can arrive for an order whose
 * holds expired. It must not be confirmed holding nothing: it goes ON_HOLD for INVENTORY and Sales decides. Sales can
 * also cancel an unpaid order whose holds expired, by hand. Each test puts its stock back.
 */
@SecuredIntegrationTest
class PaidAfterHoldExpiredIntegrationTest {

    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final Duration ASYNC = Duration.ofSeconds(15);

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired OrderService orders;
    @Autowired InventoryService inventory;
    @Autowired TransactionTemplate tx;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private UUID place(int quantity) {
        return orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), DemoData.CUSTOMER_ID, null, null, true,
                List.of(new PlaceOrderCommand.Line(SOFA, quantity, Money.vnd(12_000_000), null)),
                PaymentTerm.PREPAID, null, null)).orderId();
    }

    private OrderStatus statusOf(UUID orderId) {
        return orders.findById(orderId).map(OrderSummary::status).orElseThrow();
    }

    private void holdsExpired(UUID orderId) {
        tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE inventory.stock_reservation SET expires_at = now() - interval '1 minute' "
                        + "WHERE order_id = ? AND status = 'HELD' AND expires_at IS NOT NULL", orderId));
    }

    /** One sweep, now, on the target (ShedLock would skip a second one within seconds); as PaidOrderHoldIntegrationTest. */
    private void sweep() {
        Object sweeper = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
                context.getBean("reservationSweeper"));
        tx.executeWithoutResult(s -> {
            try {
                var method = sweeper.getClass().getDeclaredMethod("releaseExpiredReservations");
                method.setAccessible(true);
                method.invoke(sweeper);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private void pay(UUID orderId) {
        BigDecimal total = orders.findById(orderId).orElseThrow().total().amount();
        tx.executeWithoutResult(s -> publisher.publishEvent(new PaymentCaptured(orderId, UUID.randomUUID(), total,
                "VND", PaymentMethod.BANK_TRANSFER)));
        Await.until("the payment was handled", ASYNC, () -> statusOf(orderId) != OrderStatus.PENDING_PAYMENT);
    }

    private ResultActions adminCancel(UUID orderId) throws Exception {
        UUID staff = tx.execute(s -> ReferenceRows.user(entityManager));
        return new SecuredMvc(mvc, decoder).asRole(Role.SYSTEM_ADMIN, staff,
                MockMvcRequestBuilders.post("/api/v1/orders/" + orderId + "/admin-cancellation")
                        .contentType("application/json").content("{\"reasonCode\":\"OUT_OF_STOCK\"}"));
    }

    private void cleanUp(UUID orderId) {
        if (statusOf(orderId) != OrderStatus.CANCELLED) {
            WithCurrentUser.run(TestUsers.admin(), DataScope.ALL, () -> orders.cancel(orderId, "TEST_CLEANUP"));
        }
    }

    @Test
    @DisplayName("paid after its holds expired: ON_HOLD for INVENTORY, one order hold, nothing held, and Sales can cancel it")
    void paidAfterTheHoldsExpiredGoesOnHold() throws Exception {
        int before = inventory.availableToPromise(SOFA);
        UUID orderId = place(2);
        holdsExpired(orderId);
        sweep();
        assertThat(inventory.holdsOf(orderId)).as("the sweep closed them").isEmpty();

        pay(orderId);

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.ON_HOLD);
        var row = jdbc.queryForMap("SELECT hold_reason, hold_note FROM ordering.customer_order WHERE id = ?", orderId);
        assertThat(row.get("hold_reason")).isEqualTo("INVENTORY");
        assertThat((String) row.get("hold_note")).contains("0 of 2");
        assertThat(jdbc.queryForList("SELECT reason FROM ordering.order_hold WHERE order_id = ?", String.class, orderId))
                .containsExactly("INVENTORY");
        assertThat(inventory.holdsOf(orderId)).as("no stock is held for it").isEmpty();

        // Sales cancels it by hand; the expired holds are skipped, not refused.
        adminCancel(orderId).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(inventory.availableToPromise(SOFA)).isEqualTo(before);
    }

    @Test
    @DisplayName("paid within the deadline: CONFIRMED with its holds pinned, no order hold")
    void paidInTimeIsConfirmedAndPinned() throws Exception {
        int before = inventory.availableToPromise(SOFA);
        UUID orderId = place(2);

        pay(orderId);

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(jdbc.queryForList("SELECT status || ':' || (expires_at IS NULL) FROM inventory.stock_reservation "
                + "WHERE order_id = ?", String.class, orderId)).isNotEmpty().allMatch("HELD:true"::equals);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.order_hold WHERE order_id = ?", Integer.class,
                orderId)).isZero();
        cleanUp(orderId);
        assertThat(inventory.availableToPromise(SOFA)).isEqualTo(before);
    }

    @Test
    @DisplayName("an unpaid order whose holds expired is cancelled by Sales: 200, CANCELLED, the holds stay EXPIRED")
    void salesCancelsAnUnpaidOrderWhoseHoldsExpired() throws Exception {
        int before = inventory.availableToPromise(SOFA);
        UUID orderId = place(2);
        holdsExpired(orderId);
        sweep();
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING_PAYMENT);

        adminCancel(orderId).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));

        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(jdbc.queryForList("SELECT status FROM inventory.stock_reservation WHERE order_id = ?", String.class,
                orderId)).isNotEmpty().allMatch("EXPIRED"::equals);
        assertThat(inventory.availableToPromise(SOFA)).isEqualTo(before);
    }
}
