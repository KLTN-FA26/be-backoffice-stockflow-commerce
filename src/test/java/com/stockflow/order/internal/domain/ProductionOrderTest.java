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

/** 4A-2 (kltn-docs 17 §5.3, BR-06, BR-23, BR-24, B33): a production order's life, decided by the aggregate. */
class ProductionOrderTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final UUID COORDINATOR = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();
    private static final UUID LINE = UUID.randomUUID();

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
