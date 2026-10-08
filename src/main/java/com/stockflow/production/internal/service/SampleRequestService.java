package com.stockflow.production.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ConflictException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.design.api.DesignService;
import com.stockflow.production.api.SampleRequests;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionOrderRepository;
import com.stockflow.production.internal.domain.SampleEvent;
import com.stockflow.production.internal.domain.SampleRequest;
import com.stockflow.production.internal.domain.SampleRequestRepository;
import com.stockflow.production.internal.domain.SampleSpecification;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Not component-scanned: there is no real repository yet. Register through Spring only after
 * SCRUM-422 so @Transactional/@Auditable actually apply. Unit tests verify orchestration only,
 * not database rollback, event durability or HTTP authorization.
 */
@Transactional
public class SampleRequestService implements SampleRequests {
    private final SampleRequestRepository samples;
    private final ProductionOrderRepository production;
    private final SampleAccessPolicy access;
    private final CustomerService customers;
    private final DesignService designs;
    private final SampleReferenceChecks references;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public SampleRequestService(SampleRequestRepository samples, ProductionOrderRepository production,
                                SampleAccessPolicy access, CustomerService customers, DesignService designs,
                                SampleReferenceChecks references, ApplicationEventPublisher events, Clock clock) {
        this.samples = samples;
        this.production = production;
        this.access = access;
        this.customers = customers;
        this.designs = designs;
        this.references = references;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public Summary create(Create command) {
        CurrentUser actor = access.require(Action.CREATE, false);
        Objects.requireNonNull(command.customerId(), "customerId");
        customers.findById(command.customerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        if (command.quoteId() != null) {
            references.requireQuoteForCustomer(command.quoteId(), command.customerId());
        }
        var sample = SampleRequest.draft(command.customerId(), command.quoteId(), actor.userId(),
                verifyDraft(command.customerId(), command.draft()));
        access.requireOwnership(actor, sample);
        samples.insert(sample);
        return summary(sample);
    }

    @Override
    public Summary get(UUID id) {
        CurrentUser actor = access.require(Action.READ, true);
        var sample = load(id);
        access.requireOwnership(actor, sample);
        return summary(sample);
    }

    @Override
    public Summary edit(Revision revision, Draft draft) {
        return change(revision, Action.UPDATE, false,
                sample -> sample.edit(verifyDraft(sample.customerId(), draft)));
    }

    @Override
    public UUID submit(Revision revision, UUID warehouseId) {
        CurrentUser actor = access.require(Action.UPDATE, false);
        var sample = load(revision.id());
        access.requireOwnership(actor, sample);
        sample.requireRevision(revision.round(), revision.version());
        // Multi-round LSX source semantics require a business decision; do not silently reuse
        // a completed LSX or invent a different uniqueness rule while that decision is pending.
        if (sample.round() != 1) {
            throw new ConflictException("Production source semantics for a revised sample are not yet agreed");
        }
        Objects.requireNonNull(warehouseId, "warehouseId");
        references.requireWarehouse(warehouseId);
        verifyFrozenDesign(sample);
        sample.submit(warehouseId);
        var requested = new ProductionOrder.Specification(
                new ProductionOrder.Source(ProductionOrder.Type.SAMPLE, sample.id()),
                sample.customerId(), warehouseId, null, null, sample.specification(), null);
        var saved = production.insertOrGet(ProductionOrder.pending(requested));
        saved.requireSameSpecification(requested);
        samples.save(sample, revision.version());
        return saved.id();
    }

    @Override
    public Summary sendToCustomer(Revision revision) {
        return change(revision, Action.UPDATE, false, SampleRequest::sendToCustomer);
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "sample-request", resourceId = "#revision.id()")
    public Summary requestChanges(Revision revision, String feedback) {
        CurrentUser actor = access.require(Action.APPROVE, true);
        return changeAs(actor, revision, sample -> sample.requestChanges(feedback, actor.userId(), clock.instant()));
    }

    @Override
    public Summary revise(Revision revision) {
        return change(revision, Action.UPDATE, false, SampleRequest::revise);
    }

    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "sample-request", resourceId = "#revision.id()")
    public Summary approve(Revision revision) {
        CurrentUser actor = access.require(Action.APPROVE, true);
        return changeAs(actor, revision, sample -> {
            verifyFrozenDesign(sample);
            sample.approve(actor.userId(), clock.instant());
        });
    }

    @Override
    public Summary cancel(Revision revision) {
        return change(revision, Action.UPDATE, false, SampleRequest::cancel);
    }

    private Summary change(Revision revision, Action action, boolean allowCustomer, Consumer<SampleRequest> mutation) {
        return changeAs(access.require(action, allowCustomer), revision, mutation);
    }

    private Summary changeAs(CurrentUser actor, Revision revision, Consumer<SampleRequest> mutation) {
        var sample = load(revision.id());
        access.requireOwnership(actor, sample);
        sample.requireRevision(revision.round(), revision.version());
        mutation.accept(sample);
        samples.save(sample, revision.version());
        sample.pullDomainEvents().forEach(event -> events.publishEvent(((SampleEvent) event).payload()));
        return summary(sample);
    }

    private SampleRequest load(UUID id) {
        return samples.findByIdForUpdate(Objects.requireNonNull(id, "id"))
                .orElseThrow(() -> new NotFoundException("sample-request", id));
    }

    private SampleSpecification verifyDraft(UUID customerId, Draft draft) {
        Objects.requireNonNull(draft, "draft");
        Sku sku = new Sku(draft.blankSku());
        var design = designs.verifySnapshotForSku(draft.designSnapshotId(), customerId, sku.code());
        if (!Objects.equals(design.snapshotId(), draft.designSnapshotId())) {
            throw new ConflictException("Verified snapshot differs from the requested design");
        }
        return new SampleSpecification(design.snapshotId(), design.checksum(), sku, draft.quantity(), draft.dueDate());
    }

    private void verifyFrozenDesign(SampleRequest sample) {
        var print = sample.specification();
        var design = designs.verifySnapshotForSku(print.designSnapshotId(), sample.customerId(), print.blankSku().code());
        if (!design.snapshotId().equals(print.designSnapshotId()) || !design.checksum().equals(print.designChecksum())) {
            throw new ConflictException("Design no longer matches the submitted sample snapshot");
        }
    }

    private Summary summary(SampleRequest sample) {
        var print = sample.specification();
        return new Summary(sample.id(), sample.customerId(), sample.quoteId(), sample.status(), sample.round(),
                sample.version(), new Draft(print.designSnapshotId(), print.blankSku().code(), print.quantity(), print.dueDate()),
                print.designChecksum(), sample.reviews().stream().map(review -> new Review(review.round(),
                        review.specification().designSnapshotId(), review.specification().designChecksum(),
                        review.specification().blankSku().code(), review.specification().quantity(),
                        review.specification().dueDate(), review.decision().name(), review.feedback(),
                        review.recordedBy(), review.recordedAt())).toList());
    }
}
