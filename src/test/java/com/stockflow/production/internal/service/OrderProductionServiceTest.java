package com.stockflow.production.internal.service;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.ConflictException;
import com.stockflow.contracts.OrderLinesReleasedForProduction;
import com.stockflow.design.api.ConfirmedDesign;
import com.stockflow.design.api.DesignService;
import com.stockflow.production.internal.domain.ApprovedSample;
import com.stockflow.production.internal.domain.ApprovedSampleRepository;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionOrderRepository;
import com.stockflow.production.internal.domain.SampleSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderProductionServiceTest {
    private final UUID customerId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID warehouseId = UUID.randomUUID();
    private final UUID snapshotId = UUID.randomUUID();
    private final UUID sampleId = UUID.randomUUID();
    private final UUID lineId = UUID.randomUUID();
    private final ProductionOrderRepository repository = mock(ProductionOrderRepository.class);
    private final ApprovedSampleRepository approvals = mock(ApprovedSampleRepository.class);
    private final ReleasedOrderLookup releases = mock(ReleasedOrderLookup.class);
    private final DesignService designs = mock(DesignService.class);
    private final OrderProductionService service = new OrderProductionService(repository, approvals, releases, designs);

    @BeforeEach
    void references() {
        when(releases.requireCustomer(orderId, warehouseId)).thenReturn(customerId);
        when(approvals.findById(sampleId)).thenReturn(Optional.of(approved(customerId, "CUP-001", "a".repeat(64))));
        when(approvals.findForDesign(customerId, snapshotId, new Sku("CUP-001")))
                .thenReturn(Optional.of(approved(customerId, "CUP-001", "a".repeat(64))));
        when(designs.verifySnapshotForSku(snapshotId, customerId, "CUP-001"))
                .thenReturn(new ConfirmedDesign(snapshotId, UUID.randomUUID(), "a".repeat(64)));
        when(repository.insertOrGet(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void consumesTheExistingSerializedContractAndCreatesOneOrderPerLine() throws Exception {
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var event = event(line(lineId, 100, sampleId), line(UUID.randomUUID(), 50, sampleId));
        var decoded = mapper.readValue(mapper.writeValueAsString(event), OrderLinesReleasedForProduction.class);
        assertThat(service.receive(decoded)).hasSize(2).doesNotHaveDuplicates();
        var captured = org.mockito.ArgumentCaptor.forClass(ProductionOrder.class);
        verify(repository, times(2)).insertOrGet(captured.capture());
        assertThat(captured.getAllValues()).allSatisfy(order -> {
            assertThat(order.status()).isEqualTo(ProductionOrder.Status.PENDING_PREPRESS);
            assertThat(order.source().type()).isEqualTo(ProductionOrder.Type.ORDER);
            assertThat(order.specification().customerId()).isEqualTo(customerId);
            assertThat(order.specification().warehouseId()).isEqualTo(warehouseId);
            assertThat(order.specification().approvedSampleId()).isEqualTo(sampleId);
        });
    }

    @Test
    void duplicateDeliveryReturnsPersistedIdsWithoutRecheckingExternalArtifacts() {
        var existing = existing(lineId, 100, warehouseId);
        when(repository.findBySource(existing.source())).thenReturn(Optional.of(existing));
        when(repository.insertOrGet(any())).thenReturn(existing);
        assertThat(service.receive(event(line(lineId, 100, sampleId)))).containsExactly(existing.id());
        verifyNoInteractions(designs);
        verify(approvals, never()).findById(any());
    }

    @Test
    void replayOfCompletedOrderKeepsItsIdAndDoesNotResetWorkflowState() {
        var created = existing(lineId, 100, warehouseId);
        var completed = ProductionOrder.restore(created.id(), created.specification(), ProductionOrder.Status.COMPLETED);
        when(repository.findBySource(completed.source())).thenReturn(Optional.of(completed));
        when(repository.insertOrGet(any())).thenReturn(completed);
        assertThat(service.receive(event(line(lineId, 100, sampleId)))).containsExactly(completed.id());
        assertThat(completed.status()).isEqualTo(ProductionOrder.Status.COMPLETED);
        verifyNoInteractions(designs);
    }

    @Test
    void concurrentInsertLoserUsesTheAtomicRepositoryWinner() {
        // Models the port's winner result only; real SQL uniqueness/races are blocked by SCRUM-422.
        var winner = existing(lineId, 100, warehouseId);
        when(repository.insertOrGet(any())).thenReturn(winner);
        assertThat(service.receive(event(line(lineId, 100, sampleId)))).containsExactly(winner.id());
    }

    @Test
    void concurrentInsertWithDifferentContentFailsInsteadOfAcknowledgingReplay() {
        when(repository.insertOrGet(any())).thenReturn(existing(lineId, 99, warehouseId));
        assertThatThrownBy(() -> service.receive(event(line(lineId, 100, sampleId))))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void replayWithDifferentQuantityFailsBeforeWriting() {
        var original = existing(lineId, 99, warehouseId);
        when(repository.findBySource(original.source())).thenReturn(Optional.of(original));
        assertThatThrownBy(() -> service.receive(event(line(lineId, 100, sampleId))))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).insertOrGet(any());
    }

    @Test
    void replayWithDifferentWarehouseFailsBeforeWriting() {
        var original = existing(lineId, 100, UUID.randomUUID());
        when(repository.findBySource(original.source())).thenReturn(Optional.of(original));
        assertThatThrownBy(() -> service.receive(event(line(lineId, 100, sampleId))))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).insertOrGet(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"customer", "order", "orderNumber", "snapshot", "checksum", "blank", "approval", "dueDate"})
    void replayCannotReplaceOwnershipOrLockedSourceContent(String changed) {
        var original = existing(lineId, 100, warehouseId);
        when(repository.findBySource(original.source())).thenReturn(Optional.of(original));
        UUID incomingOrder = changed.equals("order") ? UUID.randomUUID() : orderId;
        UUID incomingCustomer = changed.equals("customer") ? UUID.randomUUID() : customerId;
        when(releases.requireCustomer(incomingOrder, warehouseId)).thenReturn(incomingCustomer);
        var incomingLine = new OrderLinesReleasedForProduction.Line(lineId,
                changed.equals("blank") ? "BOX-001" : "CUP-001", 100,
                changed.equals("snapshot") ? UUID.randomUUID() : snapshotId,
                changed.equals("checksum") ? "b".repeat(64) : "a".repeat(64),
                changed.equals("approval") ? UUID.randomUUID() : sampleId,
                LocalDate.of(2026, 10, changed.equals("dueDate") ? 21 : 20));
        var incoming = new OrderLinesReleasedForProduction(incomingOrder,
                changed.equals("orderNumber") ? "SO-2" : "SO-1", warehouseId, List.of(incomingLine));

        assertThatThrownBy(() -> service.receive(incoming)).isInstanceOf(ConflictException.class);
        verify(repository, never()).insertOrGet(any());
        verifyNoInteractions(designs);
    }

    @Test
    void repeatedDesignResolvesItsExistingApprovalInsteadOfBypassingSampleGate() {
        service.receive(event(line(lineId, 100, null)));
        verify(approvals).findForDesign(customerId, snapshotId, new Sku("CUP-001"));
        when(approvals.findForDesign(customerId, snapshotId, new Sku("CUP-001"))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.receive(event(line(UUID.randomUUID(), 100, null))))
                .isInstanceOf(ConflictException.class);
        verify(repository, times(1)).insertOrGet(any());
    }

    @Test
    void repeatReplayUsesOriginalApprovalEvenIfANewerApprovalExists() {
        var original = existing(lineId, 100, warehouseId);
        when(repository.findBySource(original.source())).thenReturn(Optional.of(original));
        when(repository.insertOrGet(any())).thenReturn(original);
        assertThat(service.receive(event(line(lineId, 100, null)))).containsExactly(original.id());
        verify(approvals, never()).findForDesign(any(), any(), any());
    }

    @Test
    void rejectsAnApprovalForAnotherCustomer() {
        when(approvals.findById(sampleId)).thenReturn(Optional.of(approved(UUID.randomUUID(), "CUP-001", "a".repeat(64))));
        assertThatThrownBy(() -> service.receive(event(line(lineId, 100, sampleId))))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).insertOrGet(any());
    }

    @Test
    void rejectsAnotherBlankOrChecksum() {
        when(approvals.findById(sampleId)).thenReturn(Optional.of(approved(customerId, "BOX-001", "a".repeat(64))));
        assertThatThrownBy(() -> service.receive(event(line(lineId, 100, sampleId))))
                .isInstanceOf(ConflictException.class);
        when(approvals.findById(sampleId)).thenReturn(Optional.of(approved(customerId, "CUP-001", "b".repeat(64))));
        assertThatThrownBy(() -> service.receive(event(line(lineId, 100, sampleId))))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).insertOrGet(any());
    }

    @Test
    void rejectsSnapshotBytesThatChangedSinceOrderAcceptance() {
        when(designs.verifySnapshotForSku(snapshotId, customerId, "CUP-001"))
                .thenReturn(new ConfirmedDesign(snapshotId, UUID.randomUUID(), "b".repeat(64)));
        assertThatThrownBy(() -> service.receive(event(line(lineId, 100, sampleId))))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).insertOrGet(any());
    }

    @Test
    void rejectsDuplicateLineIdsEvenWhenContentIsIdentical() {
        var line = line(lineId, 100, sampleId);
        assertThatThrownBy(() -> service.receive(event(line, line))).isInstanceOf(ConflictException.class);
        verifyNoInteractions(repository, releases);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -10})
    void validatesTheEntireBatchBeforeWritingAnyLine(int invalidQuantity) {
        assertThatThrownBy(() -> service.receive(event(line(lineId, 100, sampleId),
                line(UUID.randomUUID(), invalidQuantity, sampleId)))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, releases);
    }

    @Test
    void missingApprovalOnLastLineDoesNotWriteFirstLine() {
        assertThatThrownBy(() -> service.receive(event(line(lineId, 100, sampleId),
                line(UUID.randomUUID(), 100, UUID.randomUUID())))).isInstanceOf(ConflictException.class);
        verify(repository, never()).insertOrGet(any());
    }

    @Test
    void emptyOrNullContainingBatchesAreRejected() {
        assertThatThrownBy(() -> service.receive(event())).isInstanceOf(IllegalArgumentException.class);
        var lines = new ArrayList<OrderLinesReleasedForProduction.Line>();
        lines.add(null);
        assertThatThrownBy(() -> service.receive(new OrderLinesReleasedForProduction(orderId, "SO-1", warehouseId, lines)))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(repository, releases);
    }

    private ApprovedSample approved(UUID customer, String sku, String checksum) {
        return new ApprovedSample(sampleId, customer, new SampleSpecification(snapshotId, checksum, new Sku(sku), 2, null));
    }

    private OrderLinesReleasedForProduction.Line line(UUID id, int quantity, UUID approval) {
        return new OrderLinesReleasedForProduction.Line(id, "CUP-001", quantity, snapshotId,
                "a".repeat(64), approval, LocalDate.of(2026, 10, 20));
    }

    private OrderLinesReleasedForProduction event(OrderLinesReleasedForProduction.Line... lines) {
        return new OrderLinesReleasedForProduction(orderId, "SO-1", warehouseId, List.of(lines));
    }

    private ProductionOrder existing(UUID source, int quantity, UUID warehouse) {
        return ProductionOrder.pending(new ProductionOrder.Specification(
                new ProductionOrder.Source(ProductionOrder.Type.ORDER, source), customerId, warehouse, orderId, "SO-1",
                new SampleSpecification(snapshotId, "a".repeat(64), new Sku("CUP-001"), quantity,
                        LocalDate.of(2026, 10, 20)), sampleId));
    }
}
