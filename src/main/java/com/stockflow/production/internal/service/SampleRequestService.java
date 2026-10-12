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
import com.stockflow.inventory.api.ReserveProductionStockCommand;
import com.stockflow.order.api.QuoteDirectory;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.SampleStatus;
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

import java.nio.charset.StandardCharsets;
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
    private static final java.util.Set<String> OPEN_QUOTE_STATUSES = java.util.Set.of("DRAFT", "SENT",
            "CHANGES_REQUESTED");
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
    private final QuoteDirectory quoteDirectory;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    SampleRequestService(SampleRequestRepository samples, SampleRequestSearch sampleSearch,
                         ProductionOrderRepository productionOrders, ProductionOrderSearch productionSearch,
                         CustomerService customers, DesignService designs, WarehouseService warehouses,
                         InventoryService inventory, QuoteDirectory quoteDirectory, ApplicationEventPublisher events,
                         Clock clock) {
        this.samples = samples;
        this.sampleSearch = sampleSearch;
        this.productionOrders = productionOrders;
        this.productionSearch = productionSearch;
        this.customers = customers;
        this.designs = designs;
        this.warehouses = warehouses;
        this.inventory = inventory;
        this.quoteDirectory = quoteDirectory;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "sample-request", resourceId = "#result?.id()")
    public Sample create(CurrentUser user, Create command) {
        ScopeGuards.requireBackOffice(user);
        UUID customerId = Objects.requireNonNull(command.customerId(), "customerId");
        requireActiveCustomer(customerId);
        SampleSpecification spec = verified(customerId, command.draft());
        requireQuoteCovers(command.quoteId(), customerId, spec);
        SampleRequest sample = SampleRequest.draft(Identifiers.newId(), samples.nextNumber(today()), customerId,
                command.quoteId(), spec);
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
        return backOffice(user, sampleRequestId, sample -> {
            SampleSpecification spec = verified(sample.customerId(), draft);
            requireQuoteCovers(sample.quoteId(), sample.customerId(), spec);
            sample.edit(spec);
        });
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "sample-request", resourceId = "#sampleRequestId")
    public Sample submit(CurrentUser user, UUID sampleRequestId, UUID warehouseId) {
        ScopeGuards.requireBackOffice(user);
        SampleRequest sample = lockVisible(user, sampleRequestId);
        requireActiveCustomer(sample.customerId());
        WarehouseView warehouse = warehouses.findWarehouse(Objects.requireNonNull(warehouseId, "warehouseId"))
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND, "No warehouse " + warehouseId));
        if (!warehouse.active()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Warehouse " + warehouse.prefix() + " is not in use");
        }
        WarehouseScope.requireWarehouse(warehouseId);
        SampleSpecification spec = sample.specification();
        // 03 §17: submit needs a confirmed design snapshot — re-read now, so a draft whose snapshot no
        // longer verifies does not reach the workshop — and blanks to make it from, held for the sample's
        // production order from now on (decision Q1, 2026-10-12): no other order can take them before the
        // workshop issues them. The count first, for a clear refusal; the hold re-checks under the locks.
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
        // Same transaction: a submit that fails after this holds nothing; a retry holds nothing twice.
        inventory.reserveForProduction(new ReserveProductionStockCommand(
                UUID.nameUUIDFromBytes(("sample-blanks:" + created.id()).getBytes(StandardCharsets.UTF_8)),
                spec.blankSku(), spec.quantity(), created.id(), warehouse.prefix()));
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
        requireActiveCustomer(previous.customerId());
        if (samples.nextRoundOf(previous.id()).isPresent()) {
            throw new BusinessException(ErrorCode.SAMPLE_NEXT_ROUND_EXISTS,
                    "Sample request %s already has a next round".formatted(previous.requestNumber()));
        }
        SampleSpecification spec = draft == null ? previous.specification() : verified(previous.customerId(), draft);
        requireQuoteCovers(previous.quoteId(), previous.customerId(), spec);
        // Decision Q5: a round whose production order was cancelled may be started again too.
        boolean productionCancelled = previous.status() == SampleStatus.CANCELLED
                && productionOrders.findBySource(ProductionOrder.Type.SAMPLE, previous.id())
                .map(order -> order.status() == ProductionOrder.Status.CANCELLED).orElse(false);
        SampleRequest next = previous.nextRound(Identifiers.newId(), samples.nextNumber(today()), spec,
                productionCancelled);
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

    /**
     * Eligibility for new work. BR-ORD-007 gives quotes and orders only to an ACTIVE B2B customer and
     * BR-CUS-003 keeps a blocked customer from starting new business while leaving what exists alone. A
     * sample round is the first step of a quote, so drafting one, starting the next round and sending a
     * round to the workshop (which spends blanks and press time) require ACTIVE: INACTIVE — which is also
     * how a Sales-created customer who has not activated the portal is stored until a dedicated status
     * exists (SCRUM-429) — and BLOCKED are refused. Work already under way is not undone: editing or
     * cancelling a draft, recording that a sample went out and recording the customer's answer stay
     * possible, as BR-CUS-003 leaves existing orders unaffected.
     */
    private void requireActiveCustomer(UUID customerId) {
        String status = customers.findById(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND)).status();
        if (!"ACTIVE".equals(status)) {
            throw new BusinessException(ErrorCode.CUSTOMER_NOT_ACTIVE,
                    "Customer " + customerId + " is " + status + "; only an ACTIVE customer gets new sample work");
        }
    }

    /**
     * A sample linked to a quote (SCRUM-394 "linked quote") must be for that quote's customer and print one
     * of its lines' design on that line's blank — that is what makes its approval count for the quote's
     * acceptance (BR-ORD-008). The quote must still be under negotiation: DRAFT, SENT or CHANGES_REQUESTED.
     * Once accepted it needs no sample; expired, cancelled or converted it takes no new work. A next round
     * with a new design therefore needs the quote revised to that design first, which keeps the quote and
     * its samples about the same artwork. Read through {@code order :: api}, never the {@code ordering}
     * schema; checked again on every edit and next round, because the specification or the quote may
     * have moved since.
     */
    private void requireQuoteCovers(UUID quoteId, UUID customerId, SampleSpecification spec) {
        if (quoteId == null) {
            return;
        }
        QuoteDirectory.QuoteReference quote = quoteDirectory.findQuote(quoteId)
                .orElseThrow(() -> new NotFoundException("quote", quoteId));
        if (!OPEN_QUOTE_STATUSES.contains(quote.status())) {
            throw new BusinessException(ErrorCode.QUOTE_NOT_OPEN,
                    "Quote %s is %s; a sample is linked only to a quote still being negotiated"
                            .formatted(quote.quoteNumber(), quote.status()));
        }
        boolean covered = quote.customerId().equals(customerId) && quote.designs().contains(
                new QuoteDirectory.QuotedDesign(spec.designSnapshotId(), spec.blankSku().code()));
        if (!covered) {
            throw new BusinessException(ErrorCode.SAMPLE_QUOTE_MISMATCH,
                    "Quote %s does not quote design %s on %s for this customer"
                            .formatted(quote.quoteNumber(), spec.designSnapshotId(), spec.blankSku().code()));
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
