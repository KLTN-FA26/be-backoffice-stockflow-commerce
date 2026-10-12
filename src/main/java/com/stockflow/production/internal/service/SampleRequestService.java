package com.stockflow.production.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.Role;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.design.api.ConfirmedDesign;
import com.stockflow.design.api.DesignService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionOrderRepository;
import com.stockflow.production.internal.domain.SampleEvent;
import com.stockflow.production.internal.domain.SampleRequest;
import com.stockflow.production.internal.domain.SampleRequestRepository;
import com.stockflow.production.internal.domain.SampleSpecification;
import com.stockflow.production.internal.repository.ProductionOrderSearch;
import com.stockflow.production.internal.repository.SampleRequestSearch;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Sample requests: the use cases of SCRUM-394/403 on one transaction each.
 *
 * <p><b>Submit</b> is the only step that writes two aggregates: the round becomes SUBMITTED and its
 * SAMPLE production order is created in the chosen workshop (SCRUM-395, BR-PRD-01), both or neither.
 * The round's row lock serialises two submits of the same draft, and the production order repository
 * returns the existing order rather than a second one.</p>
 *
 * <p><b>Approve</b> saves the round and publishes {@code SampleApproved} in the same transaction: Spring
 * Modulith records the publication with the commit, so a rolled-back approval announces nothing and a
 * committed one reaches the order module even across a restart (its listener records
 * {@code ordering.approved_sample}, which release reads for BR-PRD-08).</p>
 */
@Service
@Transactional
class SampleRequestService implements SampleRequests {

    private static final Logger log = LoggerFactory.getLogger(SampleRequestService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final SortWhitelist SORT = SortWhitelist
            .of("createdAt", "dueDate", "requestNumber", "status")
            .withDefault("createdAt", Sort.Direction.DESC);

    private final SampleRequestRepository samples;
    private final SampleRequestSearch sampleSearch;
    private final ProductionOrderRepository productionOrders;
    private final ProductionOrderSearch productionSearch;
    private final CustomerService customers;
    private final DesignService designs;
    private final WarehouseService warehouses;
    private final InventoryService inventory;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    SampleRequestService(SampleRequestRepository samples, SampleRequestSearch sampleSearch,
                         ProductionOrderRepository productionOrders, ProductionOrderSearch productionSearch,
                         CustomerService customers, DesignService designs, WarehouseService warehouses,
                         InventoryService inventory, ApplicationEventPublisher events, Clock clock) {
        this.samples = samples;
        this.sampleSearch = sampleSearch;
        this.productionOrders = productionOrders;
        this.productionSearch = productionSearch;
        this.customers = customers;
        this.designs = designs;
        this.warehouses = warehouses;
        this.inventory = inventory;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "sample-request", resourceId = "#result?.id()")
    public Sample create(CurrentUser user, Create command) {
        ScopeGuards.requireBackOffice(user);
        UUID customerId = Objects.requireNonNull(command.customerId(), "customerId");
        customers.findById(customerId).orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        // quote_id has no foreign key until the quote table arrives (SCRUM-448), and no module can say
        // whose a quote is: storing an id nothing can check would be trusting the client.
        if (command.quoteId() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A sample cannot be linked to a quote yet: quotes arrive with SCRUM-448");
        }
        SampleSpecification spec = verified(customerId, command.draft());
        SampleRequest sample = SampleRequest.draft(Identifiers.newId(), samples.nextNumber(today()), customerId,
                null, spec);
        samples.save(sample);
        log.info("Sample request {} drafted for customer {}", sample.requestNumber(), customerId);
        return view(sample.id());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<Row> list(CurrentUser user, Criteria criteria) {
        UUID customerId = portalCustomer(user).orElse(criteria.customerId());
        var page = sampleSearch.search(new SampleRequestSearch.Criteria(customerId, criteria.statuses(),
                        criteria.designSnapshotId(), criteria.number(),
                        WarehouseScope.restriction().map(allowed -> (java.util.Collection<UUID>) allowed.ids())
                                .orElse(null)),
                Pages.of(criteria.page(), criteria.size(), SORT.parse(criteria.sort())));
        Map<UUID, ProductionOrderSearch.Row> made = productionSearch.forSamples(
                page.getContent().stream().map(SampleRequestSearch.Row::id).toList());
        return Pages.toResponse(page.map(row -> new Row(row.id(), row.requestNumber(), row.customerId(), row.round(),
                row.status(), row.designSnapshotId(), row.blankSku(), row.quantity(), row.dueDate(), row.sentAt(),
                row.respondedAt(), ref(made.get(row.id())), row.createdAt())));
    }

    @Override
    @Transactional(readOnly = true)
    public Sample get(CurrentUser user, UUID sampleRequestId) {
        SampleRequest sample = samples.findById(sampleRequestId)
                .orElseThrow(() -> new NotFoundException("sample-request", sampleRequestId));
        requireVisible(user, sample);
        return view(sample.id());
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "sample-request", resourceId = "#sampleRequestId")
    public Sample edit(CurrentUser user, UUID sampleRequestId, Draft draft) {
        return backOffice(user, sampleRequestId, sample -> sample.edit(verified(sample.customerId(), draft)));
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "sample-request", resourceId = "#sampleRequestId")
    public Sample submit(CurrentUser user, UUID sampleRequestId, UUID warehouseId) {
        ScopeGuards.requireBackOffice(user);
        SampleRequest sample = lockVisible(user, sampleRequestId);
        WarehouseView warehouse = warehouses.findWarehouse(Objects.requireNonNull(warehouseId, "warehouseId"))
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND, "No warehouse " + warehouseId));
        if (!warehouse.active()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Warehouse " + warehouse.prefix() + " is not in use");
        }
        WarehouseScope.requireWarehouse(warehouseId);
        SampleSpecification spec = sample.specification();
        // 03 §17: submit needs a confirmed design snapshot — re-read now, so a draft whose snapshot no
        // longer verifies does not reach the workshop — and blanks to make it from. This is a check, not
        // a hold: issuing the blanks to the PRODUCTION area is the workshop's step (SCRUM-397).
        ConfirmedDesign design = designs.verifySnapshotForSku(spec.designSnapshotId(), sample.customerId(),
                spec.blankSku().code());
        int available = inventory.availableToPromise(spec.blankSku(), warehouse.prefix());
        if (available < spec.quantity()) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK,
                    "%d of blank %s available in %s, the sample needs %d"
                            .formatted(available, spec.blankSku().code(), warehouse.prefix(), spec.quantity()));
        }
        sample.submit();
        Instant now = clock.instant();
        ProductionOrder requested = ProductionOrder.forSample(Identifiers.newId(),
                productionOrders.nextNumber(today()), sample.id(), new ProductionOrder.Work(warehouseId,
                        spec.blankSku(), design.snapshotId(), design.checksum(), spec.quantity(), spec.dueDate()));
        ProductionOrder created = productionOrders.createIfAbsent(requested, user.userId(), now);
        created.requireSameWorkAs(requested);
        samples.save(sample);
        log.info("Sample request {} submitted: production order {} in {}", sample.requestNumber(),
                created.orderNumber(), warehouse.prefix());
        return view(sample.id());
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "sample-request", resourceId = "#sampleRequestId")
    public Sample markSent(CurrentUser user, UUID sampleRequestId) {
        return backOffice(user, sampleRequestId, sample -> sample.markSent(clock.instant()));
    }

    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "sample-request", resourceId = "#sampleRequestId")
    public Sample approve(CurrentUser user, UUID sampleRequestId) {
        SampleRequest sample = lockVisible(user, sampleRequestId);
        sample.approve(user.userId(), clock.instant());
        samples.save(sample);
        // After the save: a refused write publishes nothing. The publication joins this transaction.
        sample.pullDomainEvents().forEach(event -> events.publishEvent(((SampleEvent) event).payload()));
        log.info("Sample request {} approved: design {} on {}", sample.requestNumber(),
                sample.specification().designSnapshotId(), sample.specification().blankSku().code());
        return view(sample.id());
    }

    @Override
    @Auditable(action = AuditAction.REJECT, resourceType = "sample-request", resourceId = "#sampleRequestId")
    public Sample requestChanges(CurrentUser user, UUID sampleRequestId, String feedback) {
        SampleRequest sample = lockVisible(user, sampleRequestId);
        sample.requestChanges(feedback, user.userId(), clock.instant());
        samples.save(sample);
        return view(sample.id());
    }

    /**
     * The next round is a new request (03 §17). The previous round is locked first, so two people
     * starting it at once are serialised and the second finds the first's round; the partial unique
     * index {@code uk_sample_request_next_round} is the backstop.
     */
    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "sample-request", resourceId = "#result?.id()")
    public Sample startNextRound(CurrentUser user, UUID sampleRequestId, Draft draft) {
        ScopeGuards.requireBackOffice(user);
        SampleRequest previous = lockVisible(user, sampleRequestId);
        if (samples.nextRoundOf(previous.id()).isPresent()) {
            throw new BusinessException(ErrorCode.SAMPLE_NEXT_ROUND_EXISTS,
                    "Sample request %s already has a next round".formatted(previous.requestNumber()));
        }
        SampleSpecification spec = draft == null ? previous.specification() : verified(previous.customerId(), draft);
        SampleRequest next = previous.nextRound(Identifiers.newId(), samples.nextNumber(today()), spec);
        try {
            samples.save(next);
        } catch (DataIntegrityViolationException raced) {
            throw new BusinessException(ErrorCode.SAMPLE_NEXT_ROUND_EXISTS,
                    "Sample request %s already has a next round".formatted(previous.requestNumber()));
        }
        log.info("Sample request {} round {} follows {}", next.requestNumber(), next.round(),
                previous.requestNumber());
        return view(next.id());
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "sample-request", resourceId = "#sampleRequestId")
    public Sample cancel(CurrentUser user, UUID sampleRequestId, String reason) {
        return backOffice(user, sampleRequestId, sample -> sample.cancel(reason));
    }

    private Sample backOffice(CurrentUser user, UUID sampleRequestId, Consumer<SampleRequest> change) {
        ScopeGuards.requireBackOffice(user);
        SampleRequest sample = lockVisible(user, sampleRequestId);
        change.accept(sample);
        samples.save(sample);
        return view(sample.id());
    }

    private SampleRequest lockVisible(CurrentUser user, UUID sampleRequestId) {
        SampleRequest sample = samples.findByIdForUpdate(Objects.requireNonNull(sampleRequestId, "sampleRequestId"))
                .orElseThrow(() -> new NotFoundException("sample-request", sampleRequestId));
        requireVisible(user, sample);
        return sample;
    }

    /**
     * A portal user reaches only their own customer's rounds, and another customer's answers 404 like a
     * round that does not exist. A warehouse-bound user reaches only rounds made in their warehouses.
     */
    private void requireVisible(CurrentUser user, SampleRequest sample) {
        Optional<UUID> own = portalCustomer(user);
        if (own.isPresent() && !own.get().equals(sample.customerId())) {
            throw new NotFoundException("sample-request", sample.id());
        }
        if (WarehouseScope.restriction().isPresent()) {
            UUID madeIn = productionOrders.findBySource(ProductionOrder.Type.SAMPLE, sample.id())
                    .map(order -> order.work().warehouseId()).orElse(null);
            WarehouseScope.requireWarehouse(madeIn);
        }
    }

    /** The customer a portal user acts for; empty for anyone holding a back-office role. */
    private Optional<UUID> portalCustomer(CurrentUser user) {
        Objects.requireNonNull(user, "user");
        boolean portalOnly = !user.roles().isEmpty() && user.roles().stream().allMatch(role -> role == Role.CUSTOMER);
        if (!portalOnly) {
            return Optional.empty();
        }
        return Optional.of(customers.findByUserId(user.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND)).customerId());
    }

    /** The snapshot must be this customer's confirmed design for a product the blank belongs to. */
    private SampleSpecification verified(UUID customerId, Draft draft) {
        Objects.requireNonNull(draft, "draft");
        Sku blank = new Sku(draft.blankSku());
        ConfirmedDesign design = designs.verifySnapshotForSku(draft.designSnapshotId(), customerId, blank.code());
        return new SampleSpecification(design.snapshotId(), blank, draft.quantity(), draft.dueDate());
    }

    private Sample view(UUID id) {
        SampleRequestSearch.Row row = sampleSearch.findRow(id).orElseThrow(() -> new NotFoundException("sample-request", id));
        List<Round> rounds = sampleSearch.rounds(id).stream().map(r -> new Round(r.id(), r.requestNumber(), r.round(),
                r.status(), r.designSnapshotId(), r.blankSku(), r.quantity(), r.dueDate(), r.sentAt(), r.respondedAt(),
                r.respondedBy(), r.customerFeedback(), r.cancellationReason())).toList();
        UUID next = samples.nextRoundOf(id).orElse(null);
        ProductionOrderRef made = ref(productionSearch.forSamples(List.of(id)).get(id));
        return new Sample(row.id(), row.requestNumber(), row.customerId(), row.quoteId(), row.previousRoundId(), next,
                row.round(), row.status(), new Draft(row.designSnapshotId(), row.blankSku(), row.quantity(),
                row.dueDate()), row.sentAt(), row.respondedAt(), row.respondedBy(), row.customerFeedback(),
                row.cancellationReason(), made, rounds, row.createdAt(), row.createdBy(), row.lastModifiedAt(),
                row.lastModifiedBy(), row.version());
    }

    private static ProductionOrderRef ref(ProductionOrderSearch.Row order) {
        return order == null ? null
                : new ProductionOrderRef(order.id(), order.orderNumber(), order.status(), order.warehouseId());
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE);
    }
}
