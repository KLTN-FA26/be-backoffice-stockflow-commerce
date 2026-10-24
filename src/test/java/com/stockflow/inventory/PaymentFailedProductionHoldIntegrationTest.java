package com.stockflow.inventory;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.contracts.PaymentFailed;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PlaceOrderCommand;
import com.stockflow.production.internal.service.SampleRequests;
import com.stockflow.support.Await;
import com.stockflow.support.ConfirmedDesigns;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review of PR #109, item 1: a production hold (a sample's blanks, held at submission, decision Q1) has no order id.
 * When it shares a stock layer with a checkout hold, {@code PaymentFailed} for that checkout must release the
 * checkout hold only, leave the production hold whole, and complete its event publication - first time and on a
 * replay. Before the fix the listener called {@code orderId().equals(...)} on the production hold and threw.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class PaymentFailedProductionHoldIntegrationTest {

    private static final Sku SOFA = new Sku(ConfirmedDesigns.BLANK);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired OrderService orders;
    @Autowired SampleRequests samples;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileStorage storage;
    @Autowired EntityManager entityManager;

    @Test
    @DisplayName("PaymentFailed on a layer that also carries a production hold releases only the order's hold, once")
    void releasesOnlyTheCheckoutHold() throws Exception {
        // The oldest layer of the SKU in HCM, so FIFO hands both holds out of it.
        UUID layer = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO inventory.stock_item (id, sku, location_code, on_hand, reserved, status, version, created_at,
                    received_at)
                VALUES (?, ?, 'HCM-A02-2-A', 20, 0, 'AVAILABLE', 0, NOW(), NOW() - interval '4000 days')""",
                layer, SOFA.code()));
        UUID hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        UUID salesId = tx.execute(s -> ReferenceRows.user(entityManager));
        CurrentUser sales = new CurrentUser(salesId, "sales-" + salesId, Set.of(Role.SALES_STAFF), Set.of(),
                DataScope.ALL, Set.of(), Set.of());
        ConfirmedDesigns.Design design = ConfirmedDesigns.confirmed(storage, jdbc, tx, entityManager,
                DemoData.CUSTOMER_ID);
        SampleRequests.Sample sample = samples.create(sales, new SampleRequests.Create(DemoData.CUSTOMER_ID, null,
                new SampleRequests.Draft(design.snapshotId(), SOFA.code(), 2, null)));
        UUID sampleOrder = samples.submit(sales, sample.id(), hcm).productionOrder().id();
        OrderSummary checkout = orders.placeOrder(new PlaceOrderCommand(UUID.randomUUID(), DemoData.CUSTOMER_ID,
                List.of(new PlaceOrderCommand.Line(SOFA, 3, Money.vnd(12_000_000), null))));

        // SCRUM-152: the production hold sits on the layer; the checkout holds the SKU, on no layer, until release.
        assertThat(jdbc.queryForList("""
                SELECT COALESCE(order_id::text, 'production') AS owner FROM inventory.stock_reservation
                 WHERE stock_item_id = ? AND status = 'HELD'""", String.class, layer))
                .as("the production hold on the layer").containsExactly("production");
        assertThat(status(checkout.orderId())).isEqualTo("HELD");

        PaymentFailed failed = new PaymentFailed(checkout.orderId(), UUID.randomUUID(), "CARD_DECLINED", "declined");
        tx.executeWithoutResult(s -> publisher.publishEvent(failed));
        Await.until("the checkout hold is released", TIMEOUT, () -> status(checkout.orderId()).equals("RELEASED"));
        Await.until("the inventory listener's publication completes", TIMEOUT, () -> openPublications(checkout) == 0);

        // A replay of the same event is a no-op and completes too.
        tx.executeWithoutResult(s -> publisher.publishEvent(failed));
        Await.until("the replay's publication completes", TIMEOUT, () -> openPublications(checkout) == 0);

        Map<String, Object> production = jdbc.queryForMap("""
                SELECT status, quantity, production_order_id FROM inventory.stock_reservation
                 WHERE stock_item_id = ? AND production_order_id IS NOT NULL""", layer);
        assertThat(production).containsEntry("status", "HELD").containsEntry("quantity", 2)
                .containsEntry("production_order_id", sampleOrder);
        assertThat(jdbc.queryForObject("SELECT reserved FROM inventory.stock_item WHERE id = ?", Integer.class, layer))
                .as("only the production hold is left on the layer").isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory.stock_reservation WHERE order_id = ? AND status = 'RELEASED'""",
                Integer.class, checkout.orderId())).as("released once, not twice").isEqualTo(1);
    }

    /** The order's SKU reservation (stock_item_id NULL): what a checkout holds since SCRUM-152. */
    private String status(UUID orderId) {
        return jdbc.queryForObject(
                "SELECT status FROM inventory.stock_reservation WHERE stock_item_id IS NULL AND order_id = ?",
                String.class, orderId);
    }

    private int openPublications(OrderSummary order) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM event_publication
                 WHERE event_type LIKE '%PaymentFailed' AND listener_id LIKE '%inventory%'
                   AND serialized_event LIKE ? AND completion_date IS NULL""", Integer.class,
                "%" + order.orderId() + "%");
    }
}
