package com.stockflow.order;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.OrderReleased;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.support.ConfirmedOrders;
import com.stockflow.support.CustomerSkuRows;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RecordedEvents;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-423 against a real database and the real event plumbing: release allocates every line and moves the order to
 * READY_TO_FULFILL, announcing {@code OrderReleased}; an order with a print line waits until its printed goods are
 * received from the producer.
 *
 * <p>Orders are inserted as rows already CONFIRMED (paid in full): how an order gets paid is not what is tested here, and
 * a checkout with a design line needs the whole design approval flow.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
@org.springframework.test.context.TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class OrderReleaseIntegrationTest {

    private static final String CUP = "SOFA-3S-GREY";   // any demo SKU with an inventory item

    @Autowired OrderReleases releases;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired RecordedEvents.Recorder recorded;
    @Autowired org.springframework.context.ApplicationContext context;

    private UUID hcm;
    private UUID coordinator;

    @BeforeEach
    void setUp() {
        hcm = jdbc.queryForObject("SELECT id FROM warehouse.warehouse WHERE prefix = 'HCM'", UUID.class);
        coordinator = tx.execute(s -> ReferenceRows.user(entityManager));
        recorded.clear();
    }

    /** A confirmed design snapshot of the demo customer. */
    private UUID snapshot() {
        UUID draft = Identifiers.newId();
        UUID snapshot = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
                    VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", draft, DemoData.CUSTOMER_ID, DemoData.PRODUCT_SOFA);
            jdbc.update("""
                    INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at,
                                                        artifact_manifest)
                    VALUES (?, ?, ?, 'https://example.com/print.pdf', NOW(), NOW(), '[]'::jsonb)""",
                    snapshot, draft, "a".repeat(64));
        });
        return snapshot;
    }

    /** A CONFIRMED, paid order: one print line of 10 with this design, one stock line. Returns {orderId, printLineId}. */
    private UUID[] confirmedOrder(UUID design) {
        UUID order = Identifiers.newId();
        UUID printLine = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                                                         currency, placed_at, version, created_at,
                                                         contract_signed_at)
                    VALUES (?, ?, ?, ?, 'CONFIRMED', 30000, 'VND', NOW(), 0, NOW(), NOW())""",
                    order, orderNumber(), DemoData.CUSTOMER_ID, UUID.randomUUID());
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency, design_snapshot_id,
                                                     design_checksum) VALUES (?, ?, ?, 10, 2000, 'VND', ?, ?)""",
                    printLine, order, CUP, design, "a".repeat(64));
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
                    VALUES (?, ?, 'TABLE-OAK-160', 1, 10000, 'VND')""", Identifiers.newId(), order);
        });
        return new UUID[] {order, printLine};
    }

    /** SO-yyyyMMdd-nnnnnn on a day no real order uses, so the sequence never collides with these. */
    private static String orderNumber() {
        return "SO-20991231-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000));
    }

    private String status(UUID order) {
        return jdbc.queryForObject("SELECT status FROM ordering.customer_order WHERE id = ?", String.class, order);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("an order with a print line stays CONFIRMED: its printed goods come from a producer and are not received "
            + "yet (ORDER_PRINT_GOODS_NOT_RECEIVED)")
    void printLineWaitsForItsGoods() {
        UUID[] order = confirmedOrder(snapshot());

        assertThatThrownBy(() -> releases.release(order[0], hcm, coordinator))
                .extracting(OrderReleaseIntegrationTest::code).isEqualTo(ErrorCode.ORDER_PRINT_GOODS_NOT_RECEIVED);

        assertThat(status(order[0])).isEqualTo("CONFIRMED");
        assertThat(recorded.matching(OrderReleased.class, e -> e.orderId().equals(order[0]))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.stock_reservation WHERE order_id = ?"
                + " AND allocated_from_id IS NOT NULL", Integer.class, order[0])).isZero();
    }

    @Test
    @DisplayName("an order with nothing to print goes straight to READY_TO_FULFILL; a second release is refused")
    void stockOnly() {
        // A contract's order of the customer's own SKU, its stock held at the shipping warehouse (4B-7), as production has it.
        String sku = CustomerSkuRows.issue(jdbc, tx, entityManager, DemoData.CUSTOMER_ID, 50).sku();
        CustomerSkuRows.stock(jdbc, tx, sku, 10);
        ConfirmedOrders confirmedOrders = new ConfirmedOrders(context);
        UUID order = confirmedOrders.confirmed(DemoData.CUSTOMER_ID, ConfirmedOrders.Line.units(sku, 2, 10_000)).orderId();
        confirmedOrders.ship(order, hcm);
        recorded.clear();

        assertThat(releases.release(order, hcm, coordinator).status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
        assertThat(recorded.matching(OrderReleased.class, e -> e.orderId().equals(order))).hasSize(1);
        assertThatThrownBy(() -> releases.release(order, hcm, coordinator))
                .extracting(OrderReleaseIntegrationTest::code).isEqualTo(ErrorCode.ORDER_INVALID_TRANSITION);
    }
}
