package com.stockflow.production.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.OrderLinesReleasedForProduction;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionOrderRepository;
import com.stockflow.production.internal.domain.SampleRequest;
import com.stockflow.production.internal.domain.SampleRequestRepository;
import com.stockflow.production.internal.domain.SampleSpecification;
import com.stockflow.production.internal.domain.SampleStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The release listener's decisions with its collaborators mocked: the event read back against the order,
 * BR-PRD-08 from this module's sample rounds, BR-PRD-01 on replay, and nothing written when any line is
 * refused. Real concurrency and rollback are in {@code ProductionIntegrationTest}.
 */
class OrderProductionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
    private static final String CHECKSUM = "a".repeat(64);

    private final ProductionOrderRepository productionOrders = mock(ProductionOrderRepository.class);
    private final SampleRequestRepository samples = mock(SampleRequestRepository.class);
    private final OrderService orders = mock(OrderService.class);
    private final OrderProductionService service = new OrderProductionService(productionOrders, samples, orders,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID customer = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID warehouse = UUID.randomUUID();
    private final UUID design = UUID.randomUUID();
    private final UUID lineA = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private final UUID lineB = UUID.fromString("00000000-0000-7000-8000-00000000000b");
    private SampleRequest approved;

    @BeforeEach
    void setUp() {
        approved = sample(customer, design, "CUP-PP-500", SampleStatus.APPROVED);
        when(productionOrders.nextNumber(any())).thenReturn("LSX-20261012-0001", "LSX-20261012-0002");
        when(productionOrders.findBySource(eq(ProductionOrder.Type.ORDER), any())).thenReturn(Optional.empty());
        when(productionOrders.createIfAbsent(any(), any(), any())).thenAnswer(call -> call.getArgument(0));
        released(OrderStatus.IN_PRODUCTION, warehouse, line(lineB), line(lineA));
    }

    private SampleRequest sample(UUID owner, UUID snapshot, String blank, SampleStatus status) {
        SampleRequest sample = SampleRequest.restore(UUID.randomUUID(), "SR-1", owner, null, null, 1,
                new SampleSpecification(snapshot, new Sku(blank), 3, null), status, NOW, NOW, UUID.randomUUID(), null,
                null);
        when(samples.findById(sample.id())).thenReturn(Optional.of(sample));
        return sample;
    }

    private OrderSummary.LineSummary line(UUID id) {
        return new OrderSummary.LineSummary(id, "CUP-PP-500", 10, Money.vnd(2000),
                Money.vnd(20000), List.of(), design, CHECKSUM);
    }

    private void released(OrderStatus status, UUID from, OrderSummary.LineSummary... lines) {
        when(orders.findById(orderId)).thenReturn(Optional.of(new OrderSummary(orderId, "SO-1", customer, status,
                Money.vnd(40000), List.of(lines), NOW, null, null, null, null, null, null, null,
                null, null, from, from == null ? null : NOW)));
    }

    private OrderLinesReleasedForProduction.Line event(UUID line, UUID approvedSample) {
        return new OrderLinesReleasedForProduction.Line(line, "CUP-PP-500", 10, design, CHECKSUM, approvedSample, null);
    }

    private OrderLinesReleasedForProduction release(OrderLinesReleasedForProduction.Line... lines) {
        return new OrderLinesReleasedForProduction(orderId, "SO-1", warehouse, List.of(lines));
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    void createsOnePendingOrderPerLineInLineIdOrderAgainstTheApprovedSample() {
        List<ProductionOrder> created = service.createFor(release(event(lineB, approved.id()), event(lineA, approved.id())));

        assertThat(created).extracting(ProductionOrder::salesOrderLineId).containsExactly(lineA, lineB);
        assertThat(created).allSatisfy(order -> {
            assertThat(order.type()).isEqualTo(ProductionOrder.Type.ORDER);
            assertThat(order.status()).isEqualTo(ProductionOrder.Status.PENDING_PREPRESS);
            assertThat(order.salesOrderId()).isEqualTo(orderId);
            assertThat(order.approvedSampleId()).isEqualTo(approved.id());
            assertThat(order.work().warehouseId()).isEqualTo(warehouse);
            assertThat(order.work().plannedQuantity()).isEqualTo(10);
            assertThat(order.work().designChecksum()).isEqualTo(CHECKSUM);
        });
        verify(productionOrders, times(2)).createIfAbsent(any(), eq(null), eq(NOW));
    }

    @Test
    void aSampleThatIsNotAnApprovedRoundOfThisCustomerDesignAndBlankIsRefused() {
        List<SampleRequest> wrong = List.of(
                sample(UUID.randomUUID(), design, "CUP-PP-500", SampleStatus.APPROVED),
                sample(customer, UUID.randomUUID(), "CUP-PP-500", SampleStatus.APPROVED),
                sample(customer, design, "BOX-KRAFT-01", SampleStatus.APPROVED),
                sample(customer, design, "CUP-PP-500", SampleStatus.SENT_TO_CUSTOMER));
        for (SampleRequest sample : wrong) {
            assertThatThrownBy(() -> service.createFor(release(event(lineA, sample.id()))))
                    .extracting(OrderProductionServiceTest::code).isEqualTo(ErrorCode.DESIGN_SAMPLE_NOT_APPROVED);
        }
        assertThatThrownBy(() -> service.createFor(release(event(lineA, UUID.randomUUID()))))
                .as("unknown sample").extracting(OrderProductionServiceTest::code)
                .isEqualTo(ErrorCode.DESIGN_SAMPLE_NOT_APPROVED);
        verify(productionOrders, never()).createIfAbsent(any(), any(), any());
    }

    @Test
    void aRepeatDesignNeedsACompletedOrderOfItAndKeepsTheLatestApprovalAsReference() {
        when(samples.latestApproved(design, new Sku("CUP-PP-500"))).thenReturn(Optional.empty());
        when(productionOrders.completedForAnotherOrder(design, orderId)).thenReturn(false);
        assertThatThrownBy(() -> service.createFor(release(event(lineA, null))))
                .extracting(OrderProductionServiceTest::code).isEqualTo(ErrorCode.DESIGN_SAMPLE_NOT_APPROVED);

        when(productionOrders.completedForAnotherOrder(design, orderId)).thenReturn(true);
        assertThat(service.createFor(release(event(lineA, null)))).singleElement()
                .satisfies(order -> assertThat(order.approvedSampleId()).isNull());

        when(samples.latestApproved(design, new Sku("CUP-PP-500"))).thenReturn(Optional.of(approved));
        assertThat(service.createFor(release(event(lineA, null)))).singleElement()
                .satisfies(order -> assertThat(order.approvedSampleId()).isEqualTo(approved.id()));
    }

    @Test
    void theEventIsReadBackAgainstTheOrderBeforeAnythingIsCreated() {
        assertThatThrownBy(() -> service.createFor(release(event(UUID.randomUUID(), approved.id()))))
                .as("a line of another order").extracting(OrderProductionServiceTest::code).isEqualTo(ErrorCode.CONFLICT);
        assertThatThrownBy(() -> service.createFor(release(new OrderLinesReleasedForProduction.Line(lineA,
                "CUP-PP-500", 11, design, CHECKSUM, approved.id(), null))))
                .as("another quantity").extracting(OrderProductionServiceTest::code).isEqualTo(ErrorCode.CONFLICT);
        assertThatThrownBy(() -> service.createFor(release(new OrderLinesReleasedForProduction.Line(lineA,
                "CUP-PP-500", 10, design, "b".repeat(64), approved.id(), null))))
                .as("another checksum").extracting(OrderProductionServiceTest::code).isEqualTo(ErrorCode.CONFLICT);

        released(OrderStatus.IN_PRODUCTION, UUID.randomUUID(), line(lineA));
        assertThatThrownBy(() -> service.createFor(release(event(lineA, approved.id()))))
                .as("released from another warehouse").extracting(OrderProductionServiceTest::code)
                .isEqualTo(ErrorCode.CONFLICT);
        released(OrderStatus.CONFIRMED, null, line(lineA));
        assertThatThrownBy(() -> service.createFor(release(event(lineA, approved.id()))))
                .as("never released").extracting(OrderProductionServiceTest::code).isEqualTo(ErrorCode.CONFLICT);

        when(orders.findById(orderId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createFor(release(event(lineA, approved.id()))))
                .extracting(OrderProductionServiceTest::code).isEqualTo(ErrorCode.NOT_FOUND);
        verify(productionOrders, never()).createIfAbsent(any(), any(), any());
    }

    @Test
    void malformedReleasesAreRefused() {
        assertThatThrownBy(() -> service.createFor(release())).extracting(OrderProductionServiceTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.createFor(release(event(lineA, approved.id()), event(lineA, approved.id()))))
                .as("the same line twice").extracting(OrderProductionServiceTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(orders, never()).findById(any());
    }

    @Test
    void aRefusedLastLineLeavesNoFirstLineBehind() {
        SampleRequest notYet = sample(customer, design, "CUP-PP-500", SampleStatus.SENT_TO_CUSTOMER);
        assertThatThrownBy(() -> service.createFor(release(event(lineA, approved.id()), event(lineB, notYet.id()))))
                .extracting(OrderProductionServiceTest::code).isEqualTo(ErrorCode.DESIGN_SAMPLE_NOT_APPROVED);
        verify(productionOrders, never()).createIfAbsent(any(), any(), any());
        verify(productionOrders, never()).nextNumber(any());
    }

    @Test
    void aCancelledOrderGetsNoProductionOrders() {
        released(OrderStatus.CANCELLED, warehouse, line(lineA));
        assertThat(service.createFor(release(event(lineA, approved.id())))).isEmpty();
        verify(productionOrders, never()).createIfAbsent(any(), any(), any());
    }

    @Test
    void aRedeliveryCreatesNothingAndLeavesAPrintedOrderAsItIs() {
        ProductionOrder printed = ProductionOrder.restore(UUID.randomUUID(), "LSX-OLD", ProductionOrder.Type.ORDER,
                ProductionOrder.Status.PRINTING, null, orderId, lineA, approved.id(),
                new ProductionOrder.Work(warehouse, new Sku("CUP-PP-500"), design, CHECKSUM, 10, null));
        when(productionOrders.findBySource(ProductionOrder.Type.ORDER, lineA)).thenReturn(Optional.of(printed));
        when(samples.latestApproved(any(), any())).thenThrow(new AssertionError("a replay re-decides nothing"));

        assertThat(service.createFor(release(event(lineA, null)))).containsExactly(printed);
        assertThat(service.createFor(release(event(lineA, approved.id())))).containsExactly(printed);
        verify(productionOrders, never()).createIfAbsent(any(), any(), any());
        verify(productionOrders, never()).nextNumber(any());
    }

    @Test
    void aRedeliveryThatDisagreesWithWhatWasCreatedIsRefused() {
        ProductionOrder stored = ProductionOrder.restore(UUID.randomUUID(), "LSX-OLD", ProductionOrder.Type.ORDER,
                ProductionOrder.Status.PENDING_PREPRESS, null, orderId, lineA, approved.id(),
                new ProductionOrder.Work(UUID.randomUUID(), new Sku("CUP-PP-500"), design, CHECKSUM, 10, null));
        when(productionOrders.findBySource(ProductionOrder.Type.ORDER, lineA)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.createFor(release(event(lineA, approved.id()))))
                .extracting(OrderProductionServiceTest::code).isEqualTo(ErrorCode.PRODUCTION_SOURCE_CONFLICT);
    }

    @Test
    void aConcurrentWinnerReturnedByTheRepositoryIsComparedNotDuplicated() {
        ArgumentCaptor<ProductionOrder> asked = ArgumentCaptor.forClass(ProductionOrder.class);
        List<ProductionOrder> winners = new ArrayList<>();
        when(productionOrders.createIfAbsent(asked.capture(), any(), any())).thenAnswer(call -> {
            ProductionOrder mine = call.getArgument(0);
            ProductionOrder winner = ProductionOrder.restore(UUID.randomUUID(), "LSX-WINNER",
                    ProductionOrder.Type.ORDER, ProductionOrder.Status.PENDING_PREPRESS, null, orderId,
                    mine.salesOrderLineId(), mine.approvedSampleId(), mine.work());
            winners.add(winner);
            return winner;
        });

        List<ProductionOrder> created = service.createFor(release(event(lineA, approved.id()), event(lineB, approved.id())));

        assertThat(created).containsExactlyElementsOf(winners);
        assertThat(asked.getAllValues()).isSortedAccordingTo(Comparator.comparing(ProductionOrder::sourceId));
    }
}
