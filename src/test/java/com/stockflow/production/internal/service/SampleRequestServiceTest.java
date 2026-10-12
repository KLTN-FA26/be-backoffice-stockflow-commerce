package com.stockflow.production.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.SampleApproved;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.design.api.ConfirmedDesign;
import com.stockflow.design.api.DesignService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionOrderRepository;
import com.stockflow.production.internal.domain.SampleRequest;
import com.stockflow.production.internal.domain.SampleRequestRepository;
import com.stockflow.production.internal.domain.SampleSpecification;
import com.stockflow.production.internal.domain.SampleStatus;
import com.stockflow.production.internal.repository.ProductionOrderSearch;
import com.stockflow.production.internal.repository.SampleRequestSearch;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * Orchestration of the sample use cases with every collaborator mocked: who may do what, what is
 * checked before anything is written, and the order of save and publish. Transactions, SQL and the
 * event registry are covered by {@code ProductionIntegrationTest}, not here.
 */
class SampleRequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
    private static final String CHECKSUM = "c".repeat(64);

    private final SampleRequestRepository samples = mock(SampleRequestRepository.class);
    private final SampleRequestSearch sampleSearch = mock(SampleRequestSearch.class);
    private final ProductionOrderRepository productionOrders = mock(ProductionOrderRepository.class);
    private final ProductionOrderSearch productionSearch = mock(ProductionOrderSearch.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final DesignService designs = mock(DesignService.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SampleRequestService service = new SampleRequestService(samples, sampleSearch, productionOrders,
            productionSearch, customers, designs, warehouses, inventory, events, Clock.fixed(NOW, ZoneOffset.UTC));

    private final UUID customer = UUID.randomUUID();
    private final UUID snapshot = UUID.randomUUID();
    private final UUID warehouse = UUID.randomUUID();
    private final CurrentUser sales = user(Role.SALES_STAFF);
    private final SampleSpecification spec = new SampleSpecification(snapshot, new Sku("CUP-PP-500"), 5, null);

    private static CurrentUser user(Role role) {
        return new CurrentUser(UUID.randomUUID(), role.name().toLowerCase(), Set.of(role), Set.of(), DataScope.ALL,
                Set.of(), Set.of());
    }

    @BeforeEach
    void stubReads() {
        when(sampleSearch.findRow(any())).thenAnswer(call -> Optional.of(new SampleRequestSearch.Row(
                call.getArgument(0), "SR-1", customer, null, null, 1, SampleStatus.DRAFT, "CUP-PP-500", snapshot, 5,
                null, null, null, null, null, null, NOW, "sales", null, null, 0)));
        when(sampleSearch.rounds(any())).thenReturn(List.of());
        when(productionSearch.forSamples(anyCollection())).thenReturn(Map.of());
        when(samples.nextNumber(any())).thenReturn("SR-20261012-0001");
        when(customers.findById(customer)).thenReturn(Optional.of(customerWith("ACTIVE")));
        when(productionOrders.nextNumber(any())).thenReturn("LSX-20261012-0001");
        when(designs.verifySnapshotForSku(eq(snapshot), eq(customer), anyString()))
                .thenReturn(new ConfirmedDesign(snapshot, UUID.randomUUID(), CHECKSUM));
    }

    private CustomerSummary customerWith(String status) {
        return new CustomerSummary(customer, null, "Customer", "c@x.vn", null, null, status, 0, NOW);
    }

    private SampleRequest stored(SampleStatus status) {
        SampleRequest sample = SampleRequest.restore(UUID.randomUUID(), "SR-1", customer, null, null, 1, spec, status,
                status.ordinal() >= SampleStatus.SENT_TO_CUSTOMER.ordinal() ? NOW : null, null, null, null, null);
        when(samples.findByIdForUpdate(sample.id())).thenReturn(Optional.of(sample));
        when(samples.findById(sample.id())).thenReturn(Optional.of(sample));
        return sample;
    }

    private static ErrorCode code(Throwable e) {
        return ((BusinessException) e).errorCode();
    }

    @Test
    void createVerifiesTheCustomerAndTheirConfirmedDesignBeforeWriting() {
        when(customers.findById(customer)).thenReturn(Optional.of(customerWith("ACTIVE")));

        service.create(sales, new SampleRequests.Create(customer, null,
                new SampleRequests.Draft(snapshot, "CUP-PP-500", 5, LocalDate.of(2026, 10, 30))));

        verify(designs).verifySnapshotForSku(snapshot, customer, "CUP-PP-500");
        verify(samples).save(any(SampleRequest.class));
    }

    @Test
    void createRefusesAnUnknownCustomerAQuoteAndAPortalCallerWithoutWriting() {
        var draft = new SampleRequests.Draft(snapshot, "CUP-PP-500", 5, null);
        when(customers.findById(customer)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(sales, new SampleRequests.Create(customer, null, draft)))
                .extracting(SampleRequestServiceTest::code).isEqualTo(ErrorCode.CUSTOMER_NOT_FOUND);

        when(customers.findById(customer)).thenReturn(Optional.of(customerWith("ACTIVE")));
        assertThatThrownBy(() -> service.create(sales, new SampleRequests.Create(customer, UUID.randomUUID(), draft)))
                .as("no quote can be verified before SCRUM-448").extracting(SampleRequestServiceTest::code)
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.create(user(Role.CUSTOMER), new SampleRequests.Create(customer, null, draft)))
                .extracting(SampleRequestServiceTest::code).isEqualTo(ErrorCode.FORBIDDEN);
        verify(samples, never()).save(any());
    }

    @Test
    void submitCreatesTheSampleProductionOrderFromTheVerifiedDesignThenSavesTheRound() {
        SampleRequest sample = stored(SampleStatus.DRAFT);
        when(warehouses.findWarehouse(warehouse)).thenReturn(Optional.of(new WarehouseView(warehouse, "HCM", "HCM", "", true)));
        when(inventory.availableToPromise(new Sku("CUP-PP-500"), "HCM")).thenReturn(5);
        when(productionOrders.createIfAbsent(any(), any(), any())).thenAnswer(call -> call.getArgument(0));

        service.submit(sales, sample.id(), warehouse);

        assertThat(sample.status()).isEqualTo(SampleStatus.SUBMITTED);
        InOrder order = inOrder(productionOrders, samples);
        order.verify(productionOrders).createIfAbsent(org.mockito.ArgumentMatchers.argThat(created ->
                created.type() == ProductionOrder.Type.SAMPLE && created.sampleRequestId().equals(sample.id())
                        && created.work().warehouseId().equals(warehouse)
                        && created.work().designChecksum().equals(CHECKSUM)
                        && created.work().plannedQuantity() == 5), eq(sales.userId()), eq(NOW));
        order.verify(samples).save(sample);
    }

    @Test
    void submitRefusesMissingOrIdleWarehouseAndTooFewBlanksBeforeCreatingAnything() {
        SampleRequest sample = stored(SampleStatus.DRAFT);
        when(warehouses.findWarehouse(warehouse)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.submit(sales, sample.id(), warehouse))
                .extracting(SampleRequestServiceTest::code).isEqualTo(ErrorCode.WAREHOUSE_NOT_FOUND);

        when(warehouses.findWarehouse(warehouse)).thenReturn(Optional.of(new WarehouseView(warehouse, "HCM", "HCM", "", false)));
        assertThatThrownBy(() -> service.submit(sales, sample.id(), warehouse))
                .extracting(SampleRequestServiceTest::code).isEqualTo(ErrorCode.CONFLICT);

        when(warehouses.findWarehouse(warehouse)).thenReturn(Optional.of(new WarehouseView(warehouse, "HCM", "HCM", "", true)));
        when(inventory.availableToPromise(new Sku("CUP-PP-500"), "HCM")).thenReturn(4);
        assertThatThrownBy(() -> service.submit(sales, sample.id(), warehouse))
                .extracting(SampleRequestServiceTest::code).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);

        assertThat(sample.status()).isEqualTo(SampleStatus.DRAFT);
        verify(productionOrders, never()).createIfAbsent(any(), any(), any());
        verify(samples, never()).save(any());
    }

    @Test
    void approvalPublishesTheContractOnlyAfterTheRoundIsSaved() {
        SampleRequest sample = stored(SampleStatus.SENT_TO_CUSTOMER);

        service.approve(sales, sample.id());

        InOrder order = inOrder(samples, events);
        order.verify(samples).save(sample);
        order.verify(events).publishEvent(new SampleApproved(sample.id(), customer, snapshot, "CUP-PP-500"));
        assertThat(sample.respondedBy()).isEqualTo(sales.userId());
    }

    @Test
    void aFailedSaveAnnouncesNoApproval() {
        SampleRequest sample = stored(SampleStatus.SENT_TO_CUSTOMER);
        doThrow(new org.springframework.dao.OptimisticLockingFailureException("raced")).when(samples).save(sample);

        assertThatThrownBy(() -> service.approve(sales, sample.id()))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void aPortalCustomerAnswersOnlyTheirOwnRoundsAndAnotherCustomersLooksAbsent() {
        CurrentUser portal = user(Role.CUSTOMER);
        SampleRequest sample = stored(SampleStatus.SENT_TO_CUSTOMER);
        CustomerSummary other = new CustomerSummary(UUID.randomUUID(), portal.userId(), "Other", "o@x.vn", null, null,
                "ACTIVE", 0, NOW);
        when(customers.findByUserId(portal.userId())).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.approve(portal, sample.id())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.get(portal, sample.id())).isInstanceOf(NotFoundException.class);
        assertThat(sample.status()).isEqualTo(SampleStatus.SENT_TO_CUSTOMER);

        CustomerSummary own = new CustomerSummary(customer, portal.userId(), "Own", "c@x.vn", null, null, "ACTIVE", 0, NOW);
        when(customers.findByUserId(portal.userId())).thenReturn(Optional.of(own));
        service.requestChanges(portal, sample.id(), "Darker blue");
        assertThat(sample.status()).isEqualTo(SampleStatus.CHANGES_REQUESTED);
        assertThat(sample.respondedBy()).isEqualTo(portal.userId());
    }

    @Test
    void aPortalCustomerCannotUseTheBackOfficeVerbs() {
        CurrentUser portal = user(Role.CUSTOMER);
        SampleRequest sample = stored(SampleStatus.READY);
        assertThatThrownBy(() -> service.markSent(portal, sample.id())).extracting(SampleRequestServiceTest::code)
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> service.cancel(portal, sample.id(), "x")).extracting(SampleRequestServiceTest::code)
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(sample.status()).isEqualTo(SampleStatus.READY);
    }

    @Test
    void theNextRoundIsStartedOnceAndRepeatsTheSpecificationWithoutABody() {
        SampleRequest previous = stored(SampleStatus.CHANGES_REQUESTED);
        when(samples.nextRoundOf(previous.id())).thenReturn(Optional.empty());

        service.startNextRound(sales, previous.id(), null);

        verify(samples).save(org.mockito.ArgumentMatchers.argThat(next -> next.round() == 2
                && previous.id().equals(next.previousRoundId()) && next.specification().equals(spec)
                && next.status() == SampleStatus.DRAFT));

        when(samples.nextRoundOf(previous.id())).thenReturn(Optional.of(UUID.randomUUID()));
        assertThatThrownBy(() -> service.startNextRound(sales, previous.id(), null))
                .extracting(SampleRequestServiceTest::code).isEqualTo(ErrorCode.SAMPLE_NEXT_ROUND_EXISTS);
    }

    @Test
    void onlyAnActiveCustomerGetsNewSampleWorkButWorkUnderWayContinues() {
        for (String status : List.of("INACTIVE", "BLOCKED")) {
            when(customers.findById(customer)).thenReturn(Optional.of(customerWith(status)));
            assertThatThrownBy(() -> service.create(sales, new SampleRequests.Create(customer, null,
                    new SampleRequests.Draft(snapshot, "CUP-PP-500", 5, null))))
                    .extracting(SampleRequestServiceTest::code).isEqualTo(ErrorCode.CUSTOMER_NOT_ACTIVE);

            SampleRequest draft = stored(SampleStatus.DRAFT);
            assertThatThrownBy(() -> service.submit(sales, draft.id(), warehouse))
                    .extracting(SampleRequestServiceTest::code).isEqualTo(ErrorCode.CUSTOMER_NOT_ACTIVE);
            assertThat(draft.status()).isEqualTo(SampleStatus.DRAFT);

            SampleRequest changed = stored(SampleStatus.CHANGES_REQUESTED);
            when(samples.nextRoundOf(changed.id())).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.startNextRound(sales, changed.id(), null))
                    .extracting(SampleRequestServiceTest::code).isEqualTo(ErrorCode.CUSTOMER_NOT_ACTIVE);

            SampleRequest sent = stored(SampleStatus.SENT_TO_CUSTOMER);
            service.approve(sales, sent.id());
            assertThat(sent.status()).as("an answer to a sample already sent is still recorded")
                    .isEqualTo(SampleStatus.APPROVED);
            SampleRequest cancellable = stored(SampleStatus.DRAFT);
            service.cancel(sales, cancellable.id(), "customer blocked");
            assertThat(cancellable.status()).isEqualTo(SampleStatus.CANCELLED);
        }
        verify(productionOrders, never()).createIfAbsent(any(), any(), any());
    }
}
