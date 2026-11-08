package com.stockflow.design.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.DesignQuoteRequested;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.design.internal.domain.CustomerDesignRules;
import com.stockflow.design.internal.domain.DesignDecisionType;
import com.stockflow.design.internal.domain.DesignReadRights;
import com.stockflow.design.internal.domain.DesignStatus;
import com.stockflow.design.internal.domain.QuoteRequestRules;
import com.stockflow.design.internal.domain.QuoteRequestStatus;
import com.stockflow.design.internal.entity.DesignDecisionJpaEntity;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.entity.DesignQuoteRequestJpaEntity;
import com.stockflow.design.internal.repository.DesignDecisionJpaRepository;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.design.internal.repository.DesignQuoteRequestJpaRepository;
import com.stockflow.design.internal.repository.DesignSnapshotJpaRepository;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A customer's quote request on their design (kltn-docs 12 §4.8; 16 §4.2 step 5; BR-15; B40): asked on an approved
 * design, or with a draft's submission ({@link DesignSubmissionService}). The sales staff are told
 * ({@link DesignQuoteRequested}) and list the requests; a request on a design waiting for review is quoted only once
 * the design is approved (BR-15), which tells them too. A design returned after its request keeps it open and the
 * request follows the design; deleting the draft closes it (B40).
 *
 * <p>The chat card that is to carry a request (16 §4.2, BR-11) belongs to the chat module, which is not built yet.
 * Writing the quote from a request - the customer's SKU filled in - is plan D5, outside this branch.</p>
 */
@Service
@Transactional
public class DesignQuoteRequestService {

    private final DesignDraftJpaRepository drafts;
    private final DesignQuoteRequestJpaRepository requests;
    private final DesignDecisionJpaRepository decisions;
    private final DesignSnapshotJpaRepository snapshots;
    private final CustomerSkus customerSkus;
    private final CustomerService customers;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;
    private final Clock clock;

    public DesignQuoteRequestService(DesignDraftJpaRepository drafts, DesignQuoteRequestJpaRepository requests,
                                     DesignDecisionJpaRepository decisions, DesignSnapshotJpaRepository snapshots,
                                     CustomerSkus customerSkus, CustomerService customers,
                                     ApplicationEventPublisher events, EntityManager entityManager, Clock clock) {
        this.drafts = drafts;
        this.requests = requests;
        this.decisions = decisions;
        this.snapshots = snapshots;
        this.customerSkus = customerSkus;
        this.customers = customers;
        this.events = events;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** What a customer asks: the cartons they would like, and when they would like them delivered, if they say. */
    public record QuoteRequestInput(int cartons, LocalDate desiredDeliveryDate) {
    }

    /**
     * A request as the customer and the sales staff read it: what was asked, and the design as it is now - its status,
     * the customer's SKU once approved, and the reason it was returned while it is a draft again (16 §4.2: the card
     * shows the design's current status).
     */
    public record QuoteRequestView(UUID id, UUID designId, String designName, DesignStatus designStatus,
                                   UUID customerId, String customerName, String sku, String customerSku,
                                   String returnReason, int cartons, LocalDate desiredDeliveryDate,
                                   QuoteRequestStatus status, UUID requestedBy, Instant requestedAt, Instant closedAt) {
    }

    /**
     * The design's owner asks for a quote on it once approved (§4.8 step 23).
     *
     * @throws BusinessException 409 {@code DESIGN_NOT_APPROVED}; 409 {@code DESIGN_QUOTE_REQUEST_OPEN} with
     *         {@code details.requestId}; 400 for fewer than one carton or a delivery date before today; 409
     *         {@code CUSTOMER_NOT_ACTIVE}; 404 for anyone but the owner
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "design-quote-request", resourceId = "#designId")
    public QuoteRequestView request(UUID designId, UUID actor, QuoteRequestInput input) {
        QuoteRequestRules.requireValid(input.cartons(), input.desiredDeliveryDate(), today());
        DesignDraftJpaEntity draft = drafts.lockById(designId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // The lock read may hand back a copy this transaction loaded before: read the row as it is now.
        entityManager.refresh(draft);
        CustomerDesignRules.requireOwner(actor, draft.getOwnerUserId(), draft.getStatus());
        if (draft.getTemplateId() == null) {
            // A design of the staff workflow has no SKU of its own: nothing can be quoted on it (BR-15).
            throw new BusinessException(ErrorCode.DESIGN_NOT_APPROVED, "This design was not made in the studio");
        }
        QuoteRequestRules.requireApproved(draft.getStatus());
        requireActiveCustomer(actor, draft);
        QuoteRequestRules.requireNoneOpen(openOf(designId));
        String customerSku = snapshots.findByDraftId(designId)
                .flatMap(snapshot -> customerSkus.ofSnapshot(snapshot.getId())).map(CustomerSku::sku).orElse(null);
        return views(List.of(open(draft, actor, input, customerSku))).getFirst();
    }

    /**
     * Whether a draft about to be submitted may carry {@code input} (§4.8 step 24): checked before the preflight, so a
     * request that could not be opened refuses the submission rather than half of it.
     */
    void requireAttachable(DesignDraftJpaEntity draft, UUID actor, QuoteRequestInput input) {
        QuoteRequestRules.requireValid(input.cartons(), input.desiredDeliveryDate(), today());
        requireActiveCustomer(actor, draft);
        QuoteRequestRules.requireNoneOpen(openOf(draft.getId()));
    }

    /**
     * Opens the request a submission carries, under the design's lock once it is submitted: the sales staff have it at
     * once, and quote it once the design is approved (BR-15).
     */
    void attach(DesignDraftJpaEntity draft, UUID actor, QuoteRequestInput input) {
        QuoteRequestRules.requireNoneOpen(openOf(draft.getId()));
        open(draft, actor, input, null);
    }

    /** Closes the design's open request: its draft was deleted (B40). Under the design's lock, as the deletion. */
    void closeOpen(UUID designId, Instant at) {
        requests.findOpen(designId).ifPresent(request -> {
            request.close(at);
            requests.saveAndFlush(request);
        });
    }

    /** The design's open request, or null. */
    @Transactional(readOnly = true)
    public UUID openOf(UUID designId) {
        return requests.findOpen(designId).map(DesignQuoteRequestJpaEntity::getId).orElse(null);
    }

    /** Whether a request on the design is open: what lets the sales staff read it ({@link DesignReadRights#quotes}). */
    @Transactional(readOnly = true)
    public boolean hasOpen(UUID designId) {
        return drafts.hasOpenQuoteRequest(designId);
    }

    /** The design's requests, the newest first, to whoever reads the design. */
    @Transactional(readOnly = true)
    public List<QuoteRequestView> ofDesign(UUID designId, UUID actor, DesignReadRights rights) {
        DesignDraftJpaEntity draft = drafts.findById(designId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        CustomerDesignRules.requireReader(actor, draft.getOwnerUserId(), draft.getStatus(),
                draft.getTemplateId() != null, rights, () -> hasOpen(designId));
        return views(requests.findByDraftIdOrderByCreatedAtDescIdDesc(designId));
    }

    /** The sales staff's list, the newest first, of every customer or one, in any status or one. */
    @Transactional(readOnly = true)
    public Page<QuoteRequestView> list(UUID customerId, QuoteRequestStatus status, Pageable page) {
        Page<DesignQuoteRequestJpaEntity> found = requests.list(customerId, status, page);
        return new PageImpl<>(views(found.getContent()), found.getPageable(), found.getTotalElements());
    }

    private DesignQuoteRequestJpaEntity open(DesignDraftJpaEntity draft, UUID actor, QuoteRequestInput input,
                                             String customerSku) {
        DesignQuoteRequestJpaEntity request = requests.saveAndFlush(new DesignQuoteRequestJpaEntity(draft.getId(),
                actor, input.cartons(), input.desiredDeliveryDate()));
        decisions.save(new DesignDecisionJpaEntity(draft, actor, DesignDecisionType.QUOTE_REQUESTED.name(),
                input.cartons() + " carton(s)" + (input.desiredDeliveryDate() == null ? ""
                        : ", delivery by " + input.desiredDeliveryDate())));
        events.publishEvent(new DesignQuoteRequested(request.getId(), draft.getId(), draft.getCustomerId(),
                draft.getStatus().name(), draft.getSku(), customerSku, input.cartons(), input.desiredDeliveryDate(),
                request.getCreatedAt()));
        return request;
    }

    /** Asked by the customer's own login, while the customer may take on new work (BR-01). */
    private void requireActiveCustomer(UUID actor, DesignDraftJpaEntity draft) {
        CustomerSummary customer = customers.findByUserId(actor)
                .filter(found -> found.customerId().equals(draft.getCustomerId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!"ACTIVE".equals(customer.status())) {
            throw new BusinessException(ErrorCode.CUSTOMER_NOT_ACTIVE,
                    "The customer is " + customer.status() + "; only an active customer asks for a quote");
        }
    }

    private LocalDate today() {
        return BusinessCalendar.date(clock.instant());
    }

    /** Requests as read: their designs, customers and customers' SKUs read once for the lot. */
    private List<QuoteRequestView> views(List<DesignQuoteRequestJpaEntity> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Set<UUID> designIds = found.stream().map(DesignQuoteRequestJpaEntity::getDraftId).collect(Collectors.toSet());
        Map<UUID, DesignDraftJpaEntity> designs = drafts.findAllById(designIds).stream()
                .collect(Collectors.toMap(DesignDraftJpaEntity::getId, Function.identity()));
        List<UUID> approved = designs.values().stream().filter(d -> d.getStatus() == DesignStatus.APPROVED)
                .map(DesignDraftJpaEntity::getId).toList();
        Map<UUID, UUID> snapshotOf = approved.isEmpty() ? Map.of() : snapshots.snapshotsOf(approved).stream()
                .collect(Collectors.toMap(DesignSnapshotJpaRepository.SnapshotOf::getDraftId,
                        DesignSnapshotJpaRepository.SnapshotOf::getId));
        Map<UUID, CustomerSku> skus = customerSkus.ofSnapshots(snapshotOf.values());
        Map<UUID, String> names = customers.fullNames(designs.values().stream()
                .map(DesignDraftJpaEntity::getCustomerId).collect(Collectors.toSet()));
        return found.stream().map(request -> {
            DesignDraftJpaEntity design = designs.get(request.getDraftId());
            UUID snapshot = snapshotOf.get(design.getId());
            CustomerSku sku = snapshot == null ? null : skus.get(snapshot);
            String returnReason = design.getStatus() == DesignStatus.DRAFT && design.getReviewedAt() != null
                    ? design.getReviewNotes() : null;
            return new QuoteRequestView(request.getId(), design.getId(), design.getName(), design.getStatus(),
                    design.getCustomerId(), names.get(design.getCustomerId()), design.getSku(),
                    sku == null ? null : sku.sku(), returnReason, request.getCartons(), request.getDesiredDeliveryDate(),
                    request.getStatus(), request.getRequestedBy(), request.getCreatedAt(), request.getClosedAt());
        }).toList();
    }
}
