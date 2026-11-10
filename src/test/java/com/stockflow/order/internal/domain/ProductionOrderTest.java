package com.stockflow.order.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4A-2 (kltn-docs 17 §5.3, BR-06, BR-23, BR-24, B33): a production order's life, decided by the aggregate; 4A-5
 * (kltn-docs 03 BR-01, BR-02, BR-10, BR-15; 17 §4.3 step 11): what can be received against it, and what a receipt
 * does to it.
 */
class ProductionOrderTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final UUID COORDINATOR = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();
    private static final UUID LINE = UUID.randomUUID();
    private static final UUID LINE_A = UUID.randomUUID();
    private static final UUID LINE_B = UUID.randomUUID();
    private static final UUID RECEIVER = UUID.randomUUID();

    private static ProductionOrder drawn(boolean firstTime) {
        return ProductionOrder.drawUp("PRD-20261109-0001", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 12, 1), firstTime,
                List.of(new ProductionOrder.Line(UUID.randomUUID(), LINE, "CUP-12OZ-ACME", 8, 50, UUID.randomUUID(),
                        "a".repeat(64), null, 0)), "VND", COORDINATOR, NOW);
    }

    private static ErrorCode code(Throwable thrown) {
        return ((BusinessException) thrown).errorCode();
    }

    private static ProductionOrder.Draft draft(ProductionOrder order, int cartons, boolean sample, String waiver) {
        return new ProductionOrder.Draft(order.producerId(), LocalDate.of(2026, 11, 28), new BigDecimal("1200"),
                "deliver before noon", sample, waiver, Map.of(LINE, new ProductionOrder.LineEdit(cartons, "label A")));
    }

    @Test
    @DisplayName("17 §5.3: the edges, and nothing more")
    void edges() {
        assertThat(ProductionOrderStatus.DRAFT.next()).containsExactlyInAnyOrder(ProductionOrderStatus.PENDING_APPROVAL,
                ProductionOrderStatus.CANCELLED);
        assertThat(ProductionOrderStatus.IN_PRODUCTION.cancellable()).as("BR-06: not once mass printing started")
                .isFalse();
        for (ProductionOrderStatus terminal : EnumSet.of(ProductionOrderStatus.RECEIVED,
                ProductionOrderStatus.CANCELLED, ProductionOrderStatus.CLOSED)) {
            assertThat(terminal.next()).isEmpty();
            assertThat(terminal.open()).isFalse();
        }
        assertThat(ProductionOrderStatus.edges()).hasSize(18).contains("SAMPLE_PENDING>IN_PRODUCTION",
                "PARTIALLY_RECEIVED>CLOSED");
    }

    @Test
    @DisplayName("drawn up as a DRAFT with its history; a SKU never produced wants a print sample by default (BR-24)")
    void drawnUp() {
        ProductionOrder order = drawn(true);
        assertThat(order.status()).isEqualTo(ProductionOrderStatus.DRAFT);
        assertThat(order.sampleApprovalRequired()).isTrue();
        assertThat(order.lines().getFirst().totalUnits()).isEqualTo(400);
        assertThat(order.outstandingCartons(LINE)).isEqualTo(8);
        assertThat(order.pullChanges()).singleElement().satisfies(change -> {
            assertThat(change.from()).isNull();
            assertThat(change.to()).isEqualTo(ProductionOrderStatus.DRAFT);
        });
        assertThat(drawn(false).sampleApprovalRequired()).isFalse();
    }

    @Test
    @DisplayName("edited while a draft: never above what the batch lacks; a default sample waived only with a reason")
    void edited() {
        ProductionOrder order = drawn(true);
        assertThatThrownBy(() -> order.edit(draft(order, 9, true, null), Map.of(LINE, 8)))
                .extracting(ProductionOrderTest::code).isEqualTo(ErrorCode.PRODUCTION_ORDER_EXCEEDS_SHORTAGE);
        assertThatThrownBy(() -> order.edit(draft(order, 8, false, " "), Map.of(LINE, 8)))
                .extracting(ProductionOrderTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        order.edit(draft(order, 5, false, "the customer saw the same print last month"), Map.of(LINE, 8));
        assertThat(order.lines().getFirst().cartons()).isEqualTo(5);
        assertThat(order.lines().getFirst().packingRequirements()).isEqualTo("label A");
        assertThat(order.sampleApprovalRequired()).isFalse();
        assertThat(order.needByDate()).isEqualTo(LocalDate.of(2026, 11, 28));

        order.submit(COORDINATOR, NOW);
        assertThatThrownBy(() -> order.edit(draft(order, 5, true, null), Map.of(LINE, 8)))
                .as("B33: settled once submitted").extracting(ProductionOrderTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID);
    }

    @Test
    @DisplayName("BR-23: approved by someone else, which sends it; returned with a reason")
    void approvedBySomeoneElse() {
        ProductionOrder order = drawn(false);
        assertThatThrownBy(() -> order.approve(MANAGER, NOW)).extracting(ProductionOrderTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID);
        order.submit(COORDINATOR, NOW);
        assertThatThrownBy(() -> order.approve(COORDINATOR, NOW)).extracting(ProductionOrderTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_SELF_APPROVAL);
        assertThatThrownBy(() -> order.returnToDraft("  ", MANAGER, NOW)).extracting(ProductionOrderTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        order.returnToDraft("wrong producer", MANAGER, NOW);
        assertThat(order.status()).isEqualTo(ProductionOrderStatus.DRAFT);
        assertThat(order.returnedReason()).isEqualTo("wrong producer");

        order.submit(COORDINATOR, NOW.plusSeconds(60));
        order.approve(MANAGER, NOW.plusSeconds(120));
        assertThat(order.status()).isEqualTo(ProductionOrderStatus.SENT);
        assertThat(order.approvedBy()).isEqualTo(MANAGER);
        assertThat(order.sentAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(order.sentAndOpen()).isTrue();
    }

    @Test
    @DisplayName("BR-06: cancelled before mass printing, closed after it - both with a reason")
    void cancelledOrClosed() {
        ProductionOrder sent = drawn(false);
        sent.submit(COORDINATOR, NOW);
        sent.approve(MANAGER, NOW);
        assertThatThrownBy(() -> sent.close("stopped", COORDINATOR, NOW)).extracting(ProductionOrderTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID);
        assertThatThrownBy(() -> sent.cancel(null, COORDINATOR, NOW)).extracting(ProductionOrderTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        sent.cancel("the customer cancelled the order", COORDINATOR, NOW);
        assertThat(sent.status()).isEqualTo(ProductionOrderStatus.CANCELLED);
        assertThat(sent.outstandingCartons(LINE)).as("nothing more on its way").isZero();

        ProductionOrder printing = new ProductionOrder(UUID.randomUUID(), "PRD-20261109-0002", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 12, 1),
                ProductionOrderStatus.IN_PRODUCTION, false, false, null, null, "VND", null, COORDINATOR, NOW, MANAGER,
                NOW, null, NOW, NOW, null, null, null, null, null, null, List.of(), 3L);
        assertThatThrownBy(() -> printing.cancel("too late", COORDINATOR, NOW)).extracting(ProductionOrderTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID);
        printing.close("the producer stopped before delivering anything", COORDINATOR, NOW);
        assertThat(printing.status()).isEqualTo(ProductionOrderStatus.CLOSED);
        assertThat(printing.closingReason()).isEqualTo("the producer stopped before delivering anything");
    }

    /** A production order as stored in {@code status}, two lines: 8 cartons of 50 and 2 of 50, none received. */
    private static ProductionOrder stored(ProductionOrderStatus status, boolean sample) {
        boolean sent = status != ProductionOrderStatus.DRAFT && status != ProductionOrderStatus.PENDING_APPROVAL;
        boolean started = status == ProductionOrderStatus.IN_PRODUCTION
                || status == ProductionOrderStatus.PARTIALLY_RECEIVED || status == ProductionOrderStatus.RECEIVED;
        return new ProductionOrder(UUID.randomUUID(), "PRD-20261109-0003", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 12, 1), status, sample,
                sample, null, null, "VND", null, COORDINATOR, sent ? NOW : null, sent ? MANAGER : null,
                sent ? NOW : null, null, sent ? NOW : null, started ? NOW : null, null, null, null, null, null, null,
                List.of(new ProductionOrder.Line(LINE_A, LINE, "CUP-12OZ-ACME", 8, 50, UUID.randomUUID(),
                                "a".repeat(64), null, 0),
                        new ProductionOrder.Line(LINE_B, UUID.randomUUID(), "LID-12OZ-ACME", 2, 50, UUID.randomUUID(),
                                "b".repeat(64), null, 0)), 4L);
    }

    @Test
    @DisplayName("03 BR-01, BR-15: received while in production, or sent / accepted with no print sample to approve")
    void receivableRule() {
        assertThat(stored(ProductionOrderStatus.IN_PRODUCTION, true).receivable()).isTrue();
        assertThat(stored(ProductionOrderStatus.PARTIALLY_RECEIVED, true).receivable()).isTrue();
        assertThat(stored(ProductionOrderStatus.SENT, false).receivable()).as("the goods came before the start")
                .isTrue();
        assertThat(stored(ProductionOrderStatus.ACCEPTED, false).receivable()).isTrue();
        for (ProductionOrderStatus waiting : EnumSet.of(ProductionOrderStatus.SENT, ProductionOrderStatus.ACCEPTED,
                ProductionOrderStatus.SAMPLE_PENDING)) {
            ProductionOrder sample = stored(waiting, true);
            assertThat(sample.receivable()).as("%s with a print sample to approve", waiting).isFalse();
            assertThat(sample.whyNotReceivable()).get().asString().contains("print sample", "BR-15");
        }
        for (ProductionOrderStatus closed : EnumSet.of(ProductionOrderStatus.DRAFT,
                ProductionOrderStatus.PENDING_APPROVAL, ProductionOrderStatus.RECEIVED, ProductionOrderStatus.CLOSED,
                ProductionOrderStatus.CANCELLED)) {
            ProductionOrder order = stored(closed, false);
            assertThat(order.receivable()).as(closed.name()).isFalse();
            assertThat(order.whyNotReceivable()).get().asString().contains("BR-01");
        }
        assertThat(stored(ProductionOrderStatus.IN_PRODUCTION, false).whyNotReceivable()).isEmpty();
    }

    @Test
    @DisplayName("03 BR-15, BR-10: goods on a SENT order put it in production first, then PARTIALLY_RECEIVED")
    void receivingPutsASentOrderInProductionFirst() {
        ProductionOrder order = stored(ProductionOrderStatus.SENT, false);
        Instant at = NOW.plusSeconds(3600);

        order.receive(Map.of(LINE_A, 5), "GR-20261109-0001", RECEIVER, at);

        assertThat(order.status()).isEqualTo(ProductionOrderStatus.PARTIALLY_RECEIVED);
        assertThat(order.productionStartedAt()).as("mass printing started when the goods were received").isEqualTo(at);
        assertThat(order.lines()).extracting(ProductionOrder.Line::receivedCartons).containsExactly(5, 0);
        assertThat(order.outstandingCartons(LINE)).isEqualTo(3);
        assertThat(order.pullChanges()).satisfiesExactly(
                change -> {
                    assertThat(change.from()).isEqualTo(ProductionOrderStatus.SENT);
                    assertThat(change.to()).isEqualTo(ProductionOrderStatus.IN_PRODUCTION);
                    assertThat(change.reason()).contains("GR-20261109-0001", "before mass printing", "BR-15");
                    assertThat(change.by()).isEqualTo(RECEIVER);
                    assertThat(change.at()).isEqualTo(at);
                },
                change -> {
                    assertThat(change.from()).isEqualTo(ProductionOrderStatus.IN_PRODUCTION);
                    assertThat(change.to()).isEqualTo(ProductionOrderStatus.PARTIALLY_RECEIVED);
                    assertThat(change.reason()).contains("GR-20261109-0001", "BR-10");
                    assertThat(change.by()).isEqualTo(RECEIVER);
                });

        ProductionOrder accepted = stored(ProductionOrderStatus.ACCEPTED, false);
        accepted.receive(Map.of(LINE_A, 8, LINE_B, 2), "GR-20261109-0002", RECEIVER, at);
        assertThat(accepted.status()).as("everything at once").isEqualTo(ProductionOrderStatus.RECEIVED);
        assertThat(accepted.pullChanges()).extracting(ProductionOrder.Change::to)
                .containsExactly(ProductionOrderStatus.IN_PRODUCTION, ProductionOrderStatus.RECEIVED);
    }

    @Test
    @DisplayName("17 §4.3 step 11: partly, partly again (no new status), then in full; a short receipt never closes it")
    void receivedPartlyThenInFull() {
        ProductionOrder order = stored(ProductionOrderStatus.IN_PRODUCTION, true);
        Instant started = order.productionStartedAt();

        order.receive(Map.of(LINE_A, 3), "GR-1", RECEIVER, NOW);
        assertThat(order.status()).isEqualTo(ProductionOrderStatus.PARTIALLY_RECEIVED);
        assertThat(order.pullChanges()).singleElement().satisfies(change ->
                assertThat(change.from()).isEqualTo(ProductionOrderStatus.IN_PRODUCTION));

        order.receive(Map.of(LINE_A, 4, LINE_B, 1), "GR-2", RECEIVER, NOW);
        assertThat(order.status()).isEqualTo(ProductionOrderStatus.PARTIALLY_RECEIVED);
        assertThat(order.pullChanges()).as("the status did not change: nothing to keep").isEmpty();
        assertThat(order.lines()).extracting(ProductionOrder.Line::receivedCartons).containsExactly(7, 1);
        assertThat(order.sentAndOpen()).isTrue();

        order.receive(Map.of(LINE_A, 1, LINE_B, 1), "GR-3", RECEIVER, NOW);
        assertThat(order.status()).isEqualTo(ProductionOrderStatus.RECEIVED);
        assertThat(order.pullChanges()).singleElement().satisfies(change -> {
            assertThat(change.from()).isEqualTo(ProductionOrderStatus.PARTIALLY_RECEIVED);
            assertThat(change.to()).isEqualTo(ProductionOrderStatus.RECEIVED);
            assertThat(change.reason()).contains("GR-3");
        });
        assertThat(order.lines()).allMatch(ProductionOrder.Line::fullyReceived);
        assertThat(order.productionStartedAt()).as("set once, when mass printing started").isEqualTo(started);
        assertThat(order.outstandingCartons(LINE)).isZero();
        assertThat(order.receivable()).as("received in full: nothing more comes").isFalse();
        assertThatThrownBy(() -> order.receive(Map.of(LINE_A, 1), "GR-4", RECEIVER, NOW))
                .extracting(ProductionOrderTest::code).isEqualTo(ErrorCode.PRODUCTION_ORDER_NOT_RECEIVABLE);
    }

    @Test
    @DisplayName("03 BR-02: never more cartons than a line orders - every line over is named, nothing changes")
    void neverReceivedBeyondWhatIsOrdered() {
        ProductionOrder order = stored(ProductionOrderStatus.SENT, false);
        assertThatThrownBy(() -> order.receive(Map.of(LINE_A, 9, LINE_B, 3), "GR-1", RECEIVER, NOW))
                .satisfies(e -> assertThat(code(e)).isEqualTo(ErrorCode.RECEIPT_EXCEEDS_PRODUCTION_ORDER))
                .hasMessageContaining("CUP-12OZ-ACME").hasMessageContaining("LID-12OZ-ACME");
        assertThat(order.status()).as("refused before anything moved").isEqualTo(ProductionOrderStatus.SENT);
        assertThat(order.productionStartedAt()).isNull();
        assertThat(order.lines()).extracting(ProductionOrder.Line::receivedCartons).containsExactly(0, 0);
        assertThat(order.pullChanges()).isEmpty();

        order.receive(Map.of(LINE_A, 8), "GR-2", RECEIVER, NOW);
        assertThatThrownBy(() -> order.receive(Map.of(LINE_A, 1), "GR-3", RECEIVER, NOW))
                .extracting(ProductionOrderTest::code).isEqualTo(ErrorCode.RECEIPT_EXCEEDS_PRODUCTION_ORDER);
    }

    @Test
    @DisplayName("03 BR-15: nothing received against a sample still to approve, a draft, or a closed order")
    void notReceivableIsRefused() {
        for (ProductionOrder refused : List.of(stored(ProductionOrderStatus.SENT, true),
                stored(ProductionOrderStatus.SAMPLE_PENDING, true), stored(ProductionOrderStatus.DRAFT, false),
                stored(ProductionOrderStatus.PENDING_APPROVAL, false), stored(ProductionOrderStatus.CLOSED, false),
                stored(ProductionOrderStatus.CANCELLED, false))) {
            ProductionOrderStatus before = refused.status();
            assertThatThrownBy(() -> refused.receive(Map.of(LINE_A, 1), "GR-1", RECEIVER, NOW))
                    .as(before.name()).extracting(ProductionOrderTest::code)
                    .isEqualTo(ErrorCode.PRODUCTION_ORDER_NOT_RECEIVABLE);
            assertThat(refused.status()).isEqualTo(before);
        }
        ProductionOrder order = stored(ProductionOrderStatus.IN_PRODUCTION, false);
        assertThatThrownBy(() -> order.receive(Map.of(), "GR-1", RECEIVER, NOW))
                .extracting(ProductionOrderTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> order.receive(Map.of(UUID.randomUUID(), 1), "GR-1", RECEIVER, NOW))
                .as("not one of its lines").extracting(ProductionOrderTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> order.receive(Map.of(LINE_A, 0), "GR-1", RECEIVER, NOW))
                .extracting(ProductionOrderTest::code).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(order.status()).isEqualTo(ProductionOrderStatus.IN_PRODUCTION);
    }

    @Test
    @DisplayName("the order's shipping warehouse moved: received there while not yet sent")
    void receivedWhereTheOrderShipsFrom() {
        ProductionOrder order = drawn(false);
        UUID elsewhere = UUID.randomUUID();
        assertThat(order.receiveAt(elsewhere)).isTrue();
        assertThat(order.receivingWarehouseId()).isEqualTo(elsewhere);
        order.submit(COORDINATOR, NOW);
        order.approve(MANAGER, NOW);
        assertThat(order.receiveAt(UUID.randomUUID())).as("sent: delivered where it was sent to").isFalse();
        assertThat(order.receivingWarehouseId()).isEqualTo(elsewhere);
    }
}
