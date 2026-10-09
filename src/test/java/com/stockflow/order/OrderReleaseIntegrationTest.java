package com.stockflow.order;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.OrderLinesReleasedForProduction;
import com.stockflow.contracts.OrderReleased;
import com.stockflow.contracts.ProductionCompleted;
import com.stockflow.contracts.SampleApproved;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.service.OrderReleases;
import com.stockflow.support.Await;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-423 against a real database and the real event plumbing: release checks the sample
 * (BR-PRD-08) and announces the print lines; the order module records SampleApproved and
 * ProductionCompleted through its asynchronous listeners and moves to READY_TO_FULFILL only when the
 * good units cover every print line (BR-PRD-06), counting a redelivered event once.
 *
 * <p>Orders are inserted as rows already PAID: how an order gets paid is not what is tested here, and
 * a checkout with a design line needs the whole design approval flow.</p>
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
class OrderReleaseIntegrationTest {

    private static final String CUP = "SOFA-3S-GREY";   // any demo SKU with an inventory item

    @Autowired OrderReleases releases;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired RecordedEvents.Recorder recorded;

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

    /** A PAID order: one print line of 10 with this design, one stock line. Returns {orderId, printLineId}. */
    private UUID[] paidOrder(UUID design) {
        UUID order = Identifiers.newId();
        UUID printLine = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                                                         currency, placed_at, version, created_at)
                    VALUES (?, ?, ?, ?, 'PAID', 30000, 'VND', NOW(), 0, NOW())""",
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

    /** A production order row for the line; {@code parent} null for the root, else a subcontracted child. */
    private UUID productionOrder(UUID order, UUID line, UUID design, UUID parent, int planned) {
        UUID id = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO production.production_order (id, order_number, type, mode, status, warehouse_id, blank_sku,
                    design_snapshot_id, design_checksum, planned_quantity, sales_order_id, sales_order_line_id, parent_id,
                    subcontract_method, subcontractor_id, subcontract_unit_price, subcontract_currency)
                SELECT ?, ?, 'ORDER', ?, 'PENDING_PREPRESS', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                  FROM procurement.suppliers s WHERE s.code = 'GOVIET'""",
                id, "LSX-IT-" + id.toString().substring(24), parent == null ? "IN_HOUSE" : "SUBCONTRACTED", hcm, CUP,
                design, "a".repeat(64), planned, order, line, parent,
                parent == null ? null : "SUPPLIED_BLANKS", parent == null ? null : jdbc.queryForObject(
                        "SELECT id FROM procurement.suppliers WHERE code = 'GOVIET'", UUID.class),
                parent == null ? null : new java.math.BigDecimal("800"), parent == null ? null : "VND"));
        return id;
    }

    /** SO-yyyyMMdd-nnnnnn on a day no real order uses, so the sequence never collides with these. */
    private static String orderNumber() {
        return "SO-20991231-%06d".formatted(java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000));
    }

    private void publish(Object event) {
        tx.executeWithoutResult(s -> publisher.publishEvent(event));
    }

    private String status(UUID order) {
        return jdbc.queryForObject("SELECT status FROM ordering.customer_order WHERE id = ?", String.class, order);
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    @DisplayName("no approved sample -> refused; after SampleApproved -> IN_PRODUCTION; both LSX finished -> READY_TO_FULFILL")
    void releaseThroughProduction() {
        UUID design = snapshot();
        UUID[] ids = paidOrder(design);
        UUID order = ids[0];
        UUID line = ids[1];

        assertThatThrownBy(() -> releases.release(order, hcm, coordinator))
                .extracting(OrderReleaseIntegrationTest::code).isEqualTo(ErrorCode.DESIGN_SAMPLE_NOT_APPROVED);
        assertThatThrownBy(() -> releases.release(order, UUID.randomUUID(), coordinator))
                .extracting(OrderReleaseIntegrationTest::code).isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);
        assertThat(status(order)).isEqualTo("PAID");

        // The customer approves a sample; production announces it.
        UUID sample = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO production.sample_request (id, request_number, customer_id, blank_sku, design_snapshot_id,
                    quantity, status, sent_at, responded_at, responded_by)
                VALUES (?, ?, ?, ?, ?, 3, 'APPROVED', NOW(), NOW(), ?)""",
                sample, "SR-IT-" + sample.toString().substring(24), DemoData.CUSTOMER_ID, CUP, design, coordinator));
        publish(new SampleApproved(sample, DemoData.CUSTOMER_ID, design, CUP));
        Await.until("the approved sample is recorded", Duration.ofSeconds(10), () -> jdbc.queryForObject(
                "SELECT count(*) FROM ordering.approved_sample WHERE sample_request_id = ?", Integer.class, sample) == 1);

        OrderSummary released = releases.release(order, hcm, coordinator);
        assertThat(released.status()).isEqualTo(OrderStatus.IN_PRODUCTION);
        assertThat(released.warehouseId()).isEqualTo(hcm);
        assertThat(released.releasedAt()).isNotNull();
        assertThat(recorded.ofType(OrderLinesReleasedForProduction.class)).singleElement().satisfies(e -> {
            assertThat(e.orderId()).isEqualTo(order);
            assertThat(e.lines()).singleElement().satisfies(l -> {
                assertThat(l.orderLineId()).isEqualTo(line);
                assertThat(l.approvedSampleId()).isEqualTo(sample);
                assertThat(l.designChecksum()).isEqualTo("a".repeat(64));
            });
        });
        assertThat(jdbc.queryForList("SELECT to_status FROM ordering.order_status_history WHERE order_id = ?",
                String.class, order)).contains("IN_PRODUCTION");

        // Production splits the line: 6 in house, 4 subcontracted. The first one finishing is not enough.
        UUID root = productionOrder(order, line, design, null, 6);
        UUID child = productionOrder(order, line, design, root, 4);
        publish(new ProductionCompleted(order, line, root, 6));
        Await.until("the first production order is recorded", Duration.ofSeconds(10), () -> jdbc.queryForObject(
                "SELECT count(*) FROM ordering.order_line_production WHERE order_line_id = ?", Integer.class, line) == 1);
        publish(new ProductionCompleted(order, line, root, 6));  // redelivered: counted once
        assertThat(status(order)).isEqualTo("IN_PRODUCTION");

        publish(new ProductionCompleted(order, line, child, 4));
        Await.until("the order is ready to fulfil", Duration.ofSeconds(10), () -> "READY_TO_FULFILL".equals(status(order)));
        assertThat(jdbc.queryForObject("SELECT SUM(good_quantity) FROM ordering.order_line_production WHERE order_line_id = ?",
                Integer.class, line)).isEqualTo(10);
        Await.until("OrderReleased is published", Duration.ofSeconds(10),
                () -> !recorded.matching(OrderReleased.class, e -> e.orderId().equals(order)).isEmpty());
        assertThat(recorded.matching(OrderReleased.class, e -> e.orderId().equals(order)))
                .singleElement().satisfies(e -> assertThat(e.warehouseCode()).isEqualTo("HCM"));
    }

    @Test
    @DisplayName("a design already printed to completion for another order is a repeat: no sample needed")
    void repeatDesign() {
        UUID design = snapshot();
        UUID[] earlier = paidOrder(design);
        UUID earlierProduction = productionOrder(earlier[0], earlier[1], design, null, 10);
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO ordering.order_line_production (production_order_id, order_line_id, good_quantity, completed_at)
                VALUES (?, ?, 10, NOW())""", earlierProduction, earlier[1]));

        UUID[] repeat = paidOrder(design);
        OrderSummary released = releases.release(repeat[0], hcm, coordinator);

        assertThat(released.status()).isEqualTo(OrderStatus.IN_PRODUCTION);
        assertThat(recorded.matching(OrderLinesReleasedForProduction.class, e -> e.orderId().equals(repeat[0])))
                .singleElement().satisfies(e -> assertThat(e.lines().getFirst().approvedSampleId()).isNull());
    }

    @Test
    @DisplayName("an order with nothing to print goes straight to READY_TO_FULFILL; a second release is refused")
    void stockOnly() {
        UUID order = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO ordering.customer_order (id, order_number, customer_id, request_id, status, total_amount,
                                                         currency, placed_at, version, created_at)
                    VALUES (?, ?, ?, ?, 'PAID', 10000, 'VND', NOW(), 0, NOW())""",
                    order, orderNumber(), DemoData.CUSTOMER_ID, UUID.randomUUID());
            jdbc.update("""
                    INSERT INTO ordering.order_line (id, order_id, sku, quantity, unit_price, currency)
                    VALUES (?, ?, 'TABLE-OAK-160', 1, 10000, 'VND')""", Identifiers.newId(), order);
        });

        assertThat(releases.release(order, hcm, coordinator).status()).isEqualTo(OrderStatus.READY_TO_FULFILL);
        assertThat(recorded.matching(OrderReleased.class, e -> e.orderId().equals(order))).hasSize(1);
        assertThatThrownBy(() -> releases.release(order, hcm, coordinator))
                .extracting(OrderReleaseIntegrationTest::code).isEqualTo(ErrorCode.CONFLICT);
    }
}
