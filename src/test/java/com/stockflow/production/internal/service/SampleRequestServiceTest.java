package com.stockflow.production.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ConflictException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.SampleApproved;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.design.api.ConfirmedDesign;
import com.stockflow.design.api.DesignService;
import com.stockflow.production.api.SampleRequests;
import com.stockflow.production.api.SampleStatus;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionOrderRepository;
import com.stockflow.production.internal.domain.SampleRequest;
import com.stockflow.production.internal.domain.SampleRequestRepository;
import com.stockflow.production.internal.domain.SampleSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class SampleRequestServiceTest {
    private final UUID customer = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID snapshot = UUID.randomUUID();
    private final UUID warehouse = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-08T04:00:00Z");
    private final SampleRequestRepository samples = mock(SampleRequestRepository.class);
    private final ProductionOrderRepository production = mock(ProductionOrderRepository.class);
    private final SampleAccessPolicy access = mock(SampleAccessPolicy.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final DesignService designs = mock(DesignService.class);
    private final SampleReferenceChecks references = mock(SampleReferenceChecks.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SampleRequestService service = new SampleRequestService(samples, production, access, customers,
            designs, references, events, Clock.fixed(now, ZoneOffset.UTC));
    private final CurrentUser actor = new CurrentUser(actorId, "sales", Set.of(Role.SALES_STAFF), Set.of(), DataScope.ALL, Set.of());
    private final SampleRequest sample = SampleRequest.draft(customer, null, actorId,
            new SampleSpecification(snapshot, "a".repeat(64), new Sku("CUP-001"), 2, null));

    @BeforeEach
    void setup() {
        when(access.require(any(), anyBoolean())).thenReturn(actor);
        when(samples.findByIdForUpdate(sample.id())).thenReturn(Optional.of(sample));
        when(customers.findById(customer)).thenReturn(Optional.of(new CustomerSummary(customer, null,
                "Test", "test@example.invalid", null, null, "ACTIVE", 0, null)));
        when(designs.verifySnapshotForSku(snapshot, customer, "CUP-001"))
                .thenReturn(new ConfirmedDesign(snapshot, UUID.randomUUID(), "a".repeat(64)));
        when(production.insertOrGet(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void createVerifiesCustomerQuoteAndDesignOwnershipBeforeInsert() {
        UUID quote = UUID.randomUUID();
        var result = service.create(new SampleRequests.Create(customer, quote, draft()));
        assertThat(result.status()).isEqualTo(SampleStatus.DRAFT);
        assertThat(result.round()).isEqualTo(1);
        verify(references).requireQuoteForCustomer(quote, customer);
        verify(designs).verifySnapshotForSku(snapshot, customer, "CUP-001");
        verify(samples).insert(any());
        verifyNoInteractions(production, events);
    }

    @Test
    void missingCustomerIsRejectedBeforeInsert() {
        when(customers.findById(customer)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(new SampleRequests.Create(customer, null, draft())))
                .isInstanceOf(BusinessException.class);
        verify(samples, never()).insert(any());
        verifyNoInteractions(designs);
    }

    @Test
    void foreignQuoteIsRejectedBeforeDesignReadsOrInsert() {
        UUID quote = UUID.randomUUID();
        doThrow(new BusinessException(ErrorCode.OUT_OF_DATA_SCOPE)).when(references).requireQuoteForCustomer(quote, customer);
        assertThatThrownBy(() -> service.create(new SampleRequests.Create(customer, quote, draft())))
                .isInstanceOf(BusinessException.class);
        verify(samples, never()).insert(any());
        verifyNoInteractions(designs);
    }

    @Test
    void submitCreatesSampleProductionAndSavesWithinTheSameServiceBoundary() {
        var revision = revision();
        UUID id = service.submit(revision, warehouse);
        assertThat(id).isNotNull();
        assertThat(sample.status()).isEqualTo(SampleStatus.SUBMITTED);
        var captured = org.mockito.ArgumentCaptor.forClass(ProductionOrder.class);
        verify(production).insertOrGet(captured.capture());
        assertThat(captured.getValue().source()).isEqualTo(new ProductionOrder.Source(ProductionOrder.Type.SAMPLE, sample.id()));
        assertThat(captured.getValue().specification().print()).isEqualTo(sample.specification());
        verify(references).requireWarehouse(warehouse);
        verify(samples).save(sample, revision.version());
        assertThatThrownBy(() -> service.submit(revision, warehouse)).isInstanceOf(ConflictException.class);
        verify(production, times(1)).insertOrGet(any());
    }

    @Test
    void invalidWarehouseDoesNotSubmitOrCreateProduction() {
        doThrow(new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND)).when(references).requireWarehouse(warehouse);
        assertThatThrownBy(() -> service.submit(revision(), warehouse)).isInstanceOf(BusinessException.class);
        assertThat(sample.status()).isEqualTo(SampleStatus.DRAFT);
        verifyNoInteractions(production);
        verify(samples, never()).save(any(), anyLong());
    }

    @Test
    void pendingRoundSourceDecisionFailsClosedWithoutInventingAnotherLsx() {
        sent();
        sample.requestChanges("Larger logo", actorId, now);
        sample.revise();
        assertThatThrownBy(() -> service.submit(revision(), warehouse)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("not yet agreed");
        verifyNoInteractions(production);
        assertThat(sample.status()).isEqualTo(SampleStatus.DRAFT);
    }

    @Test
    void approvalPublishesExistingContractOnlyAfterSaveAndRecordsActorAndTime() {
        sent();
        var revision = revision();
        var result = service.approve(revision);
        assertThat(result.status()).isEqualTo(SampleStatus.APPROVED);
        assertThat(result.reviews()).singleElement().satisfies(review -> {
            assertThat(review.recordedBy()).isEqualTo(actorId);
            assertThat(review.recordedAt()).isEqualTo(now);
        });
        var ordered = inOrder(samples, events);
        ordered.verify(samples).save(sample, revision.version());
        ordered.verify(events).publishEvent(new SampleApproved(sample.id(), customer, snapshot, "CUP-001"));
        assertThatThrownBy(() -> service.approve(revision)).isInstanceOf(ConflictException.class);
        verify(events, times(1)).publishEvent(any(Object.class));
    }

    @Test
    void failedPersistenceDoesNotPublishAnApproval() {
        sent();
        doThrow(new ConflictException("CAS failed")).when(samples).save(any(), anyLong());
        assertThatThrownBy(() -> service.approve(revision())).isInstanceOf(ConflictException.class);
        verifyNoInteractions(events);
        // No rollback claim: this is a mock repository and not a database transaction test.
    }

    @Test
    void checksumMismatchDoesNotApprove() {
        sent();
        when(designs.verifySnapshotForSku(snapshot, customer, "CUP-001"))
                .thenReturn(new ConfirmedDesign(snapshot, UUID.randomUUID(), "b".repeat(64)));
        assertThatThrownBy(() -> service.approve(revision())).isInstanceOf(ConflictException.class);
        assertThat(sample.status()).isEqualTo(SampleStatus.SENT_TO_CUSTOMER);
        verifyNoInteractions(events);
        verify(samples, never()).save(any(), anyLong());
    }

    @Test
    void ownershipDenialPrecedesVersionChecksDesignReadsAndMutation() {
        doThrow(new BusinessException(ErrorCode.OUT_OF_DATA_SCOPE)).when(access).requireOwnership(actor, sample);
        assertThatThrownBy(() -> service.approve(new SampleRequests.Revision(sample.id(), 999, 999)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE);
        verifyNoInteractions(designs, events, production);
        verify(samples, never()).save(any(), anyLong());
    }

    @Test
    void deniedPermissionDoesNotEvenLoadSample() {
        when(access.require(Action.READ, true)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.get(sample.id())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(samples);
    }

    @Test
    void customerDecisionAndSalesRevisionRetainFeedbackWithoutPublishingApproval() {
        sent();
        service.requestChanges(revision(), "Use blue");
        var next = service.revise(revision());
        assertThat(next.round()).isEqualTo(2);
        assertThat(next.reviews()).singleElement().satisfies(review -> assertThat(review.feedback()).isEqualTo("Use blue"));
        verify(access).require(Action.APPROVE, true);
        verify(access).require(Action.UPDATE, false);
        verifyNoInteractions(events);
    }

    private SampleRequests.Draft draft() {
        return new SampleRequests.Draft(snapshot, "CUP-001", 2, null);
    }

    private SampleRequests.Revision revision() {
        return new SampleRequests.Revision(sample.id(), sample.round(), sample.version());
    }

    private void sent() {
        sample.submit(warehouse);
        sample.startProduction(1);
        sample.markReady(1);
        sample.sendToCustomer();
    }
}
