package com.stockflow.design.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Label;
import com.stockflow.common.persistence.Pages;
import com.stockflow.contracts.DesignProjectQuoteRequestClosed;
import com.stockflow.contracts.DesignProjectQuoteRequestReady;
import com.stockflow.contracts.DesignProjectQuoteRequested;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.design.internal.domain.DesignDecisionType;
import com.stockflow.design.internal.domain.DesignStatus;
import com.stockflow.design.internal.domain.ProjectEventType;
import com.stockflow.design.internal.domain.QuoteRequestRules;
import com.stockflow.design.internal.domain.QuoteRequestStatus;
import com.stockflow.design.internal.entity.DesignDecisionJpaEntity;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.entity.DesignProjectJpaEntity;
import com.stockflow.design.internal.entity.DesignProjectQuoteRequestJpaEntity;
import com.stockflow.design.internal.entity.DesignProjectQuoteRequestLineJpaEntity;
import com.stockflow.design.internal.repository.DesignDecisionJpaRepository;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.design.internal.repository.DesignProjectJpaRepository;
import com.stockflow.design.internal.repository.DesignProjectQuoteRequestJpaRepository;
import com.stockflow.design.internal.repository.DesignProjectQuoteRequestLineJpaRepository;
import com.stockflow.design.internal.repository.DesignSnapshotJpaRepository;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One quote asked over designs of a design project (kltn-docs 12 §4.11; BR-21, BR-25; 16 §4.2): a line per design, the
 * cartons of each a suggestion (14 BR-22), one delivery date for all of it. Not an order, no stock held, no price
 * (BR-15).
 *
 * <p><b>Asked before every design is approved</b> (BR-25, 2026-11-25): a line names a design waiting for review, one
 * returned for changes, or an approved one - asked with a batch submission ({@link #openFromBatch}) or on its own. The
 * sales staff draw the quote only once every line is approved ({@code QuoteFromRequestService}, under the request's
 * lock); the approval of the last one tells them so ({@link DesignProjectQuoteRequestReady}). A line that hangs - a
 * returned design never fixed - the customer drops (asking again without it; an empty ask closes the request), or a
 * salesperson closes the request with a reason ({@link #closeByStaff}). A deleted design leaves the request by itself
 * ({@link #dropDeleted}).</p>
 *
 * <p>The sales staff are told ({@link DesignProjectQuoteRequested}) and list the requests with the project's name as
 * context (BR-17); the chat carries the request as a card of several lines.</p>
 *
 * <p><b>One open request per project</b>, as per design (B40): the customer who clicks "ask for a quote" twice - a
 * double click, a retried request, a second tab - gets the request already open, and the sales staff are told once.
 * Asking something else while it is open changes what it asks, and they are told what it asks now. Once it is closed,
 * the project asks anew; a request a quote answered takes no change ({@code QUOTE_REQUEST_ALREADY_QUOTED}).</p>
 *
 * <p><b>Locks, always in this order:</b> the project, then the designs named (id order), then the request. The
 * customer changes a request, and a salesperson closes it or draws its quote, under the project's lock; a design
 * approved or deleted reaches its request from the design's own lock (design, then request) and writes no project
 * history - a project event's foreign key would wait on the project a customer's ask holds while that ask waits on the
 * design. Inserting a line takes a key-share lock on its design, which is why the customer's ask locks its designs
 * before the request. {@code uk_design_project_quote_request_open} is the last line of defence.</p>
 */
@Service
@Transactional
public class DesignProjectQuoteRequestService {

    /** The longest reason a salesperson gives for closing a request without a quote (the column's own length). */
    public static final int MAX_CLOSE_REASON = 1000;

    private final DesignProjectService projectService;
    private final DesignProjectJpaRepository projects;
    private final DesignDraftJpaRepository drafts;
    private final DesignSnapshotJpaRepository snapshots;
    private final DesignProjectQuoteRequestJpaRepository requests;
    private final DesignProjectQuoteRequestLineJpaRepository lines;
    private final DesignDecisionJpaRepository decisions;
    private final CustomerSkus customerSkus;
    private final CustomerService customers;
    private final RequestOutcomes outcomes;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public DesignProjectQuoteRequestService(DesignProjectService projectService, DesignProjectJpaRepository projects,
                                            DesignDraftJpaRepository drafts, DesignSnapshotJpaRepository snapshots,
                                            DesignProjectQuoteRequestJpaRepository requests,
                                            DesignProjectQuoteRequestLineJpaRepository lines,
                                            DesignDecisionJpaRepository decisions, CustomerSkus customerSkus,
                                            CustomerService customers, RequestOutcomes outcomes,
                                            ApplicationEventPublisher events, EntityManager entityManager,
                                            Clock clock) {
        this.projectService = projectService;
        this.projects = projects;
        this.drafts = drafts;
        this.snapshots = snapshots;
        this.requests = requests;
        this.lines = lines;
        this.decisions = decisions;
        this.customerSkus = customerSkus;
        this.customers = customers;
        this.outcomes = outcomes;
        this.events = events;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** One design asked, and its cartons. */
    public record LineInput(UUID designId, int cartons) {
    }

    /**
     * One line as read: the design as it is now, its own SKU once approved, the cartons asked, and - for a design
     * returned for changes - why, so the customer knows what to fix or drop (BR-25).
     */
    public record LineView(UUID designId, String designName, DesignStatus designStatus, String customerSku,
                           int cartons, String returnReason) {
    }

    /**
     * A project quote request as read; {@code projectName} is the context the sales staff see (BR-17);
     * {@code closeReason} is why a salesperson closed it without a quote (BR-25.3).
     */
    public record ProjectQuoteRequestView(UUID id, UUID projectId, String projectName, UUID customerId,
                                          String customerName, LocalDate desiredDeliveryDate, UUID requestedBy,
                                          Instant requestedAt, QuoteRequestStatus status, Instant closedAt,
                                          UUID closedBy, String closeReason, List<LineView> lines) {

        /** How many of its lines are approved: the chat card's "approved x/y". */
        public int approvedCount() {
            return (int) lines.stream().filter(l -> l.designStatus() == DesignStatus.APPROVED).count();
        }
    }

    /** A request as asked: {@code created} when it is new, false when the open one answers (or now asks this). */
    public record Asked(ProjectQuoteRequestView request, boolean created) {
    }

    /** A request locked after its project, both read as they are now. */
    record LockedRequest(DesignProjectJpaEntity project, DesignProjectQuoteRequestJpaEntity request) {
    }

    /** As {@link #request(UUID, UUID, List, LocalDate, UUID)}, naming no request: the project's open one, or a new one. */
    public Asked request(UUID projectId, UUID actor, List<LineInput> asked, LocalDate desiredDeliveryDate) {
        return request(projectId, actor, asked, desiredDeliveryDate, null);
    }

    /**
     * The project's owner asks one quote over {@code asked} - or changes the open request to ask that: each a design
     * of the project named once, waiting for review, returned for changes or approved, with 1 to
     * {@value QuoteRequestRules#MAX_CARTONS} cartons; one delivery date, today or later. With a request of the project
     * open, the same ask answers with it and changes nothing - no history, no notice - and a different one changes what
     * it asks: a line left out is dropped (BR-25.4). An empty ask closes the open request, by nobody.
     *
     * @param requestId the request the customer is changing, as they read it; null for the project's open one. Named,
     *                  it must still be open: a request a quote answered is not changed, and no other one is opened in
     *                  its place behind the customer's back
     * @throws BusinessException 400 for no line and no request open, a design named twice, cartons out of range, a
     *         past date; 409 {@code DESIGN_NOT_APPROVED} (with {@code details.designIds}) for a design that cannot be
     *         asked - a draft never reviewed, one deleted -, {@code QUOTE_REQUEST_ALREADY_QUOTED},
     *         {@code DESIGN_QUOTE_REQUEST_CLOSED}, {@code DESIGN_PROJECT_ARCHIVED}, {@code CUSTOMER_NOT_ACTIVE}; 404 for
     *         a design or request not of the project, or anyone but the owner
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "design-project", resourceId = "#projectId")
    public Asked request(UUID projectId, UUID actor, List<LineInput> asked, LocalDate desiredDeliveryDate,
                         UUID requestId) {
        List<LineInput> wanted = asked == null ? List.of() : asked;
        Set<UUID> named = new HashSet<>();
        LocalDate today = BusinessCalendar.date(clock.instant());
        for (int i = 0; i < wanted.size(); i++) {
            LineInput line = wanted.get(i);
            if (line == null || line.designId() == null || !named.add(line.designId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Each design is named once in a request")
                        .localized("refusal.design.projectDesignTwice");
            }
            QuoteRequestRules.requireValid(line.cartons(), null, today, "lines[" + i + "].");
        }
        // One date for every line, at the top of the body: refused under its own name, not under a line's.
        QuoteRequestRules.requireValid(1, desiredDeliveryDate, today, "");
        DesignProjectJpaEntity project = projectService.lockActive(projectId, actor);
        if (requestId != null) {
            requireOpenAsNamed(requests.findById(requestId).filter(r -> projectId.equals(r.getProjectId()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)));
        }
        if (wanted.isEmpty()) {
            return emptied(project, requestId);
        }
        requireActiveCustomer(actor, project);
        // The designs before the request: a line's insert takes a key-share lock on its design, which an approval or
        // a deletion holds while it waits on this request.
        Map<UUID, DesignDraftJpaEntity> designs = new LinkedHashMap<>();
        for (UUID id : named.stream().sorted().toList()) {
            DesignDraftJpaEntity design = drafts.lockById(id)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
            entityManager.refresh(design);
            if (!projectId.equals(design.getProjectId())) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
            requireAskable(design);
            designs.put(id, design);
        }
        Map<UUID, String> skus = customerSkusOf(designs.values());

        Optional<DesignProjectQuoteRequestJpaEntity> open = lockOpen(projectId);
        if (requestId != null && open.map(r -> !r.getId().equals(requestId)).orElse(true)) {
            // Closed while the customer was asking: a quote answered it, or its last design was deleted.
            requireOpenAsNamed(lockFresh(requestId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)));
        }
        DesignProjectQuoteRequestJpaEntity request;
        String history;
        boolean wasReady = false;
        if (open.isPresent()) {
            request = open.get();
            List<DesignProjectQuoteRequestLineJpaEntity> before = lines.findByRequestIdIn(List.of(request.getId()));
            if (sameAsk(request, before, wanted, desiredDeliveryDate)) {
                // A second click on the same ask: the request it made, told to nobody again.
                return new Asked(views(List.of(request)).getFirst(), false);
            }
            wasReady = requests.unapprovedDesigns(request.getId()).isEmpty();
            // Deleted and flushed before the new lines go in: Hibernate would otherwise insert first and meet the key.
            lines.deleteAll(before);
            lines.flush();
            request.askAgain(desiredDeliveryDate);
            request = requests.saveAndFlush(request);
            history = "Request " + request.getId() + " asked again: ";
        } else {
            request = requests.saveAndFlush(new DesignProjectQuoteRequestJpaEntity(projectId, actor,
                    desiredDeliveryDate));
            history = "Request " + request.getId() + ": ";
        }
        Map<UUID, Integer> cartons = new LinkedHashMap<>();
        wanted.forEach(line -> cartons.put(line.designId(), line.cartons()));
        List<DesignProjectQuoteRequested.Line> facts = writeLines(request, cartons, designs, skus, actor,
                desiredDeliveryDate, designs.keySet());
        projectService.record(project, actor, ProjectEventType.PROJECT_QUOTE_REQUESTED, history
                + wanted.stream().map(l -> l.designId() + " x" + l.cartons()).collect(Collectors.joining(", "))
                + (desiredDeliveryDate == null ? "" : "; delivery by " + desiredDeliveryDate));
        Instant now = clock.instant();
        events.publishEvent(new DesignProjectQuoteRequested(request.getId(), projectId, project.getCustomerId(), facts,
                desiredDeliveryDate, now));
        if (open.isPresent() && !wasReady) {
            // The customer dropped the last line still waiting: Sales can quote the rest now (BR-25.4).
            publishIfReady(request, project.getCustomerId(), now);
        }
        return new Asked(views(List.of(request)).getFirst(), open.isEmpty());
    }

    /**
     * What a batch submission asks with its designs (BR-25.1), checked before anything is read: each line a design of
     * the batch, named once, with 1 to {@value QuoteRequestRules#MAX_CARTONS} cartons; the date today or later. Every
     * fault is named at once, under {@code quoteRequest.}, as the client sent it.
     *
     * @throws BusinessException 400 {@code VALIDATION_FAILED} with {@code fieldErrors} on
     *         {@code quoteRequest.lines[i].designId}, {@code quoteRequest.lines[i].cartons},
     *         {@code quoteRequest.desiredDeliveryDate}
     */
    public void requireValidBatchAsk(List<LineInput> asked, LocalDate desiredDeliveryDate, Collection<UUID> batch) {
        LocalDate today = BusinessCalendar.date(clock.instant());
        List<FieldError> faults = new ArrayList<>();
        if (asked == null || asked.isEmpty()) {
            faults.add(FieldError.localized("quoteRequest.lines", "A quote request names at least one design",
                    "Size", "refusal.design.projectNoLine"));
        } else {
            Set<UUID> named = new HashSet<>();
            for (int i = 0; i < asked.size(); i++) {
                LineInput line = asked.get(i);
                String field = "quoteRequest.lines[" + i + "].";
                if (line == null || line.designId() == null) {
                    faults.add(FieldError.localized(field + "designId", "designId is required", "NotNull",
                            "refusal.design.quoteLineNotInBatch"));
                    continue;
                }
                if (!batch.contains(line.designId())) {
                    faults.add(FieldError.localized(field + "designId", "This design is not in the batch",
                            "NotInBatch", "refusal.design.quoteLineNotInBatch"));
                } else if (!named.add(line.designId())) {
                    faults.add(FieldError.localized(field + "designId", "Each design is named once in a request",
                            "Duplicate", "refusal.design.projectDesignTwice"));
                }
                collect(faults, () -> QuoteRequestRules.requireValid(line.cartons(), null, today, field));
            }
        }
        collect(faults, () -> QuoteRequestRules.requireValid(1, desiredDeliveryDate, today, "quoteRequest."));
        if (!faults.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, faults.size()
                    + " fault(s) in the quote request; nothing was submitted", faults);
        }
    }

    private static void collect(List<FieldError> faults, Runnable check) {
        try {
            check.run();
        } catch (BusinessException e) {
            faults.addAll(e.fieldErrors());
        }
    }

    /**
     * A batch submission asks a quote over its designs too (BR-25.1), in its own transaction, under the project's lock
     * and its designs' (the caller took them, in that order): the project's open request takes these lines - a design
     * already on it at the cartons asked now, the others kept - and the date, when one is given; or a new request
     * opens. Already checked by {@link #requireValidBatchAsk}.
     *
     * @param designs the batch's designs, locked
     * @return the request asked
     */
    UUID openFromBatch(DesignProjectJpaEntity project, UUID actor, List<LineInput> asked,
                       LocalDate desiredDeliveryDate, Map<UUID, DesignDraftJpaEntity> designs) {
        Map<UUID, DesignDraftJpaEntity> onRequest = new HashMap<>(designs);
        // The designs the open request keeps, locked before it as the batch's are: their lines go in again, and each
        // insert takes a key-share lock on its design, which an approval holds while it waits on the request.
        requests.findOpen(project.getId()).ifPresent(found -> lines.findByRequestIdIn(List.of(found.getId())).stream()
                .map(DesignProjectQuoteRequestLineJpaEntity::getDraftId).filter(id -> !onRequest.containsKey(id))
                .sorted().forEach(id -> drafts.lockById(id).ifPresent(design -> {
                    entityManager.refresh(design);
                    onRequest.put(id, design);
                })));
        Optional<DesignProjectQuoteRequestJpaEntity> open = lockOpen(project.getId());
        DesignProjectQuoteRequestJpaEntity request;
        Map<UUID, Integer> cartons = new LinkedHashMap<>();
        String history;
        if (open.isPresent()) {
            request = open.get();
            List<DesignProjectQuoteRequestLineJpaEntity> before = lines.findByRequestIdIn(List.of(request.getId()));
            // Under the project's lock its open request only loses lines meanwhile (a design deleted), never gains one.
            before.stream().filter(line -> onRequest.containsKey(line.getDraftId()))
                    .sorted(Comparator.comparing(DesignProjectQuoteRequestLineJpaEntity::getDraftId))
                    .forEach(line -> cartons.put(line.getDraftId(), line.getCartons()));
            lines.deleteAll(before);
            lines.flush();
            request.askAgain(desiredDeliveryDate != null ? desiredDeliveryDate : request.getDesiredDeliveryDate());
            request = requests.saveAndFlush(request);
            history = "Request " + request.getId() + " asked again with a submission: ";
        } else {
            request = requests.saveAndFlush(new DesignProjectQuoteRequestJpaEntity(project.getId(), actor,
                    desiredDeliveryDate));
            history = "Request " + request.getId() + " asked with a submission: ";
        }
        asked.forEach(line -> cartons.put(line.designId(), line.cartons()));
        LocalDate date = request.getDesiredDeliveryDate();
        List<DesignProjectQuoteRequested.Line> facts = writeLines(request, cartons, onRequest,
                customerSkusOf(onRequest.values()), actor, date,
                asked.stream().map(LineInput::designId).collect(Collectors.toSet()));
        projectService.record(project, actor, ProjectEventType.PROJECT_QUOTE_REQUESTED, history
                + asked.stream().map(l -> l.designId() + " x" + l.cartons()).collect(Collectors.joining(", "))
                + (date == null ? "" : "; delivery by " + date));
        events.publishEvent(new DesignProjectQuoteRequested(request.getId(), project.getId(), project.getCustomerId(),
                facts, date, clock.instant()));
        return request.getId();
    }

    /**
     * A design was approved (12 §4.5 step 17), under its lock: when it was the last design of its project's open
     * request not approved yet, the sales staff are told they can quote it (BR-25.2). Of two approvals of the last two
     * designs, queued on the request's lock, only the second finds none left: it reads the first's as committed.
     */
    void designApproved(UUID designId, Instant at) {
        Optional<DesignProjectQuoteRequestJpaEntity> request = lockOpenContaining(designId);
        if (request.isEmpty()) {
            return;
        }
        // This approval itself must be in the database the check reads.
        drafts.flush();
        UUID customerId = projects.findById(request.get().getProjectId())
                .map(DesignProjectJpaEntity::getCustomerId).orElseThrow();
        publishIfReady(request.get(), customerId, at);
    }

    /**
     * A design was deleted (archived) by its customer, under its lock (BR-25.5): it leaves its project's open request.
     * A request left with no line closes, by nobody; otherwise the staff are told what it asks now - and, when the
     * design deleted was the last one waiting, that the rest can be quoted. No project history: see the class comment.
     */
    void dropDeleted(UUID designId, Instant at) {
        Optional<DesignProjectQuoteRequestJpaEntity> found = lockOpenContaining(designId);
        if (found.isEmpty()) {
            return;
        }
        DesignProjectQuoteRequestJpaEntity request = found.get();
        UUID customerId = projects.findById(request.getProjectId()).map(DesignProjectJpaEntity::getCustomerId)
                .orElseThrow();
        List<DesignProjectQuoteRequestLineJpaEntity> before = lines.findByRequestIdIn(List.of(request.getId()));
        List<DesignProjectQuoteRequestLineJpaEntity> left = before.stream()
                .filter(l -> !l.getDraftId().equals(designId)).toList();
        // Ready before only if every other design was approved and so was this one - never, as it was a draft.
        boolean wasReady = requests.unapprovedDesigns(request.getId()).isEmpty();
        lines.deleteAll(before.stream().filter(l -> l.getDraftId().equals(designId)).toList());
        lines.flush();
        if (left.isEmpty()) {
            request.close(at, null);
            requests.saveAndFlush(request);
            events.publishEvent(new DesignProjectQuoteRequestClosed(request.getId(), request.getProjectId(), customerId,
                    null, at));
            return;
        }
        Map<UUID, String> skus = customerSkusOf(drafts.findAllById(left.stream()
                .map(DesignProjectQuoteRequestLineJpaEntity::getDraftId).toList()));
        events.publishEvent(new DesignProjectQuoteRequested(request.getId(), request.getProjectId(), customerId,
                left.stream().map(l -> new DesignProjectQuoteRequested.Line(l.getDraftId(), skus.get(l.getDraftId()),
                        l.getCartons())).toList(), request.getDesiredDeliveryDate(), at));
        if (!wasReady) {
            publishIfReady(request, customerId, at);
        }
    }

    /**
     * A salesperson closes a request with the quote drawn from it ({@code QuoteFromRequestService}): it no longer
     * reads as waiting, and the project asks again for its next order. Closing a closed request changes nothing.
     *
     * @throws BusinessException 404 for no such request
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design-project-quote-request", resourceId = "#requestId")
    public ProjectQuoteRequestView close(UUID requestId, UUID actor) {
        return closeAs(requestId, actor, null);
    }

    /**
     * A salesperson closes a request without a quote, and says why (BR-25.3): one hung on a design returned and never
     * fixed, or settled otherwise with the customer. The customer reads the reason on the request and its card.
     * Closing a closed request changes nothing, its reason included.
     *
     * @throws BusinessException 400 for a blank reason or one over {@value #MAX_CLOSE_REASON} characters; 404 for no
     *         such request
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design-project-quote-request", resourceId = "#requestId")
    public ProjectQuoteRequestView closeByStaff(UUID requestId, UUID actor, String reason) {
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > MAX_CLOSE_REASON) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Say why the request is closed",
                    List.of(FieldError.localized("reason", "reason must be 1 to " + MAX_CLOSE_REASON + " characters",
                            "Size", "refusal.design.closeReasonRequired", MAX_CLOSE_REASON)), Map.of(),
                    "refusal.design.closeReasonRequired", MAX_CLOSE_REASON);
        }
        return closeAs(requestId, actor, why);
    }

    private ProjectQuoteRequestView closeAs(UUID requestId, UUID actor, String reason) {
        if (actor == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        LockedRequest locked = lockProjectRequest(requestId);
        DesignProjectQuoteRequestJpaEntity request = locked.request();
        if (request.getStatus() == QuoteRequestStatus.OPEN) {
            Instant now = clock.instant();
            if (reason == null) {
                request.close(now, actor);
            } else {
                request.closeByStaff(now, actor, reason);
            }
            request = requests.saveAndFlush(request);
            // Only on the move itself: closing a closed request changes nothing and tells nobody.
            events.publishEvent(new DesignProjectQuoteRequestClosed(request.getId(), request.getProjectId(),
                    locked.project().getCustomerId(), actor, now));
        }
        return views(List.of(request)).getFirst();
    }

    /**
     * The request, locked after its project - the order every writer of it under the project takes - and read as it
     * is now: what a salesperson's quote is drawn from, so that no line is added, changed or dropped meanwhile.
     *
     * @throws BusinessException 404 for no such request
     */
    LockedRequest lockProjectRequest(UUID requestId) {
        UUID projectId = requests.findById(requestId).map(DesignProjectQuoteRequestJpaEntity::getProjectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        DesignProjectJpaEntity project = projects.lockById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        DesignProjectQuoteRequestJpaEntity request = lockFresh(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return new LockedRequest(project, request);
    }

    /** The request as read under {@link #lockProjectRequest}: what a quote is drawn from. */
    ProjectQuoteRequestView lockedView(UUID requestId) {
        return views(List.of(lockProjectRequest(requestId).request())).getFirst();
    }

    /** One request as read, whoever asks: the chat's quote request card of several lines (16 §4.2). */
    @Transactional(readOnly = true)
    public Optional<ProjectQuoteRequestView> find(UUID requestId) {
        return requests.findById(requestId).map(request -> views(List.of(request)).getFirst());
    }

    /** The open project request naming the design, if any: its chat card follows the design (B40). */
    @Transactional(readOnly = true)
    public Optional<UUID> openRequestOf(UUID designId) {
        return requests.findOpenContaining(designId).map(DesignProjectQuoteRequestJpaEntity::getId);
    }

    /** The project's quote requests, the newest first, to its owner. */
    @Transactional(readOnly = true)
    public List<ProjectQuoteRequestView> ofProject(UUID projectId, UUID actor) {
        projectService.own(projectId, actor);
        return views(requests.findByProjectIdOrderByCreatedAtDescIdDesc(projectId));
    }

    /** The sales staff's list, the newest first, of every customer or one, in any status or {@code status}. */
    @Transactional(readOnly = true)
    public Page<ProjectQuoteRequestView> list(UUID customerId, QuoteRequestStatus status, Pageable page) {
        Page<DesignProjectQuoteRequestJpaEntity> found = requests.findAll(
                DesignProjectQuoteRequestJpaRepository.listed(customerId, status),
                Pages.of(page.getPageNumber(), page.getPageSize(),
                        Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return new PageImpl<>(views(found.getContent()), found.getPageable(), found.getTotalElements());
    }

    /** The customer of the project, active: what a batch submission asking a quote requires, read in its plan. */
    void requireActiveCustomer(UUID actor, UUID projectId) {
        requireActiveCustomer(actor, projectService.own(projectId, actor));
    }

    /** Whether {@code asked} on {@code date} is what the open request already asks: the same designs, cartons, day. */
    private static boolean sameAsk(DesignProjectQuoteRequestJpaEntity request,
                                   List<DesignProjectQuoteRequestLineJpaEntity> before, List<LineInput> asked,
                                   LocalDate date) {
        Map<UUID, Integer> was = before.stream().collect(Collectors.toMap(
                DesignProjectQuoteRequestLineJpaEntity::getDraftId, DesignProjectQuoteRequestLineJpaEntity::getCartons));
        Map<UUID, Integer> now = asked.stream().collect(Collectors.toMap(LineInput::designId, LineInput::cartons));
        return was.equals(now) && Objects.equals(request.getDesiredDeliveryDate(), date);
    }

    /** The customer emptied the request (BR-25.4): it closes, by nobody, with no reason. */
    private Asked emptied(DesignProjectJpaEntity project, UUID requestId) {
        Optional<DesignProjectQuoteRequestJpaEntity> open = lockOpen(project.getId());
        if (requestId != null && open.map(r -> !r.getId().equals(requestId)).orElse(true)) {
            requireOpenAsNamed(lockFresh(requestId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)));
        }
        if (open.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A project quote request names at least one design").localized("refusal.design.projectNoLine");
        }
        DesignProjectQuoteRequestJpaEntity request = open.get();
        Instant now = clock.instant();
        request.close(now, null);
        request = requests.saveAndFlush(request);
        events.publishEvent(new DesignProjectQuoteRequestClosed(request.getId(), project.getId(),
                project.getCustomerId(), null, now));
        return new Asked(views(List.of(request)).getFirst(), false);
    }

    /**
     * A request the customer names must still be open: one a quote answered says so - the quote is the answer, the
     * customer asks anew - and one closed otherwise says it is closed.
     */
    private void requireOpenAsNamed(DesignProjectQuoteRequestJpaEntity request) {
        if (request.getStatus() == QuoteRequestStatus.OPEN) {
            return;
        }
        RequestOutcomes.Outcome outcome = outcomes.ofProjectRequests(List.of(new RequestOutcomes.Closed(
                request.getId(), request.getStatus(), request.getClosedBy())), true)
                .getOrDefault(request.getId(), RequestOutcomes.Outcome.NONE);
        if (RequestOutcomes.QUOTED.equals(outcome.closedOutcome())) {
            throw new BusinessException(ErrorCode.QUOTE_REQUEST_ALREADY_QUOTED,
                    "A quote already answered this request; ask a new one", List.of(),
                    outcome.quoteId() == null ? Map.of() : Map.of("quoteId", outcome.quoteId().toString()),
                    "refusal.design.requestAlreadyQuoted");
        }
        throw new BusinessException(ErrorCode.DESIGN_QUOTE_REQUEST_CLOSED, "This request is closed; ask a new one")
                .localized("refusal.design.requestNotOpen");
    }

    /**
     * What a project request may name (BR-25): a design waiting for review, one returned for changes - a draft once
     * reviewed - or an approved one. A draft never submitted is asked with its submission; a deleted one not at all.
     */
    private static void requireAskable(DesignDraftJpaEntity design) {
        DesignStatus status = design.getStatus();
        boolean returned = status == DesignStatus.DRAFT && design.getReviewedAt() != null;
        if (status != DesignStatus.APPROVED && status != DesignStatus.PENDING_REVIEW && !returned) {
            throw new BusinessException(ErrorCode.DESIGN_NOT_APPROVED,
                    "This design is " + status + "; a project request takes a design waiting for review, returned or "
                            + "approved", List.of(), Map.of("designIds", List.of(design.getId().toString())),
                    "refusal.design.notEligibleForProjectQuote", new Label("DesignStatus", status.name()));
        }
    }

    /** The project's open request, locked and read as it is now; none if it closed meanwhile. */
    private Optional<DesignProjectQuoteRequestJpaEntity> lockOpen(UUID projectId) {
        return requests.findOpen(projectId).flatMap(found -> lockFresh(found.getId()))
                .filter(r -> r.getStatus() == QuoteRequestStatus.OPEN);
    }

    /** The open request naming the design, locked and checked again under the lock: still open, still naming it. */
    private Optional<DesignProjectQuoteRequestJpaEntity> lockOpenContaining(UUID designId) {
        return requests.findOpenContaining(designId).flatMap(found -> lockFresh(found.getId()))
                .filter(r -> r.getStatus() == QuoteRequestStatus.OPEN)
                .filter(r -> lines.findByRequestIdIn(List.of(r.getId())).stream()
                        .anyMatch(l -> l.getDraftId().equals(designId)));
    }

    private Optional<DesignProjectQuoteRequestJpaEntity> lockFresh(UUID requestId) {
        return requests.lockById(requestId).map(request -> {
            entityManager.refresh(request);
            return request;
        });
    }

    /** Tells the staff the request can be quoted, when every design on it is approved as committed now. */
    private void publishIfReady(DesignProjectQuoteRequestJpaEntity request, UUID customerId, Instant at) {
        if (!requests.unapprovedDesigns(request.getId()).isEmpty()) {
            return;
        }
        int count = lines.findByRequestIdIn(List.of(request.getId())).size();
        if (count > 0) {
            events.publishEvent(new DesignProjectQuoteRequestReady(request.getId(), request.getProjectId(), customerId,
                    count, at));
        }
    }

    /**
     * The request's lines, one per design at its cartons, flushed; the designs {@code asked} now get a line of history
     * each - one a batch only kept on the request was asked before. The event's lines back.
     */
    private List<DesignProjectQuoteRequested.Line> writeLines(DesignProjectQuoteRequestJpaEntity request,
                                                              Map<UUID, Integer> cartons,
                                                              Map<UUID, DesignDraftJpaEntity> designs,
                                                              Map<UUID, String> skus, UUID actor, LocalDate date,
                                                              Set<UUID> asked) {
        List<DesignProjectQuoteRequested.Line> facts = new ArrayList<>();
        cartons.forEach((designId, count) -> {
            lines.save(new DesignProjectQuoteRequestLineJpaEntity(request.getId(), designId, count));
            if (asked.contains(designId)) {
                decisions.save(new DesignDecisionJpaEntity(designs.get(designId), actor,
                        DesignDecisionType.QUOTE_REQUESTED.name(), count + " carton(s)"
                        + (date == null ? "" : ", delivery by " + date) + ", project request " + request.getId()));
            }
            facts.add(new DesignProjectQuoteRequested.Line(designId, skus.get(designId), count));
        });
        lines.flush();
        return facts;
    }

    private void requireActiveCustomer(UUID actor, DesignProjectJpaEntity project) {
        CustomerSummary customer = customers.findByUserId(actor)
                .filter(found -> found.customerId().equals(project.getCustomerId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!"ACTIVE".equals(customer.status())) {
            throw new BusinessException(ErrorCode.CUSTOMER_NOT_ACTIVE,
                    "The customer is " + customer.status() + "; only an active customer asks for a quote")
                    .localized("refusal.design.customerNotActive", new Label("CustomerStatus", customer.status()));
        }
    }

    /** The customer's own SKU of each approved design, read once for the lot. */
    private Map<UUID, String> customerSkusOf(Collection<DesignDraftJpaEntity> designs) {
        List<UUID> approved = designs.stream().filter(d -> d.getStatus() == DesignStatus.APPROVED)
                .map(DesignDraftJpaEntity::getId).toList();
        if (approved.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> snapshotOf = snapshots.snapshotsOf(approved).stream()
                .collect(Collectors.toMap(DesignSnapshotJpaRepository.SnapshotOf::getDraftId,
                        DesignSnapshotJpaRepository.SnapshotOf::getId));
        Map<UUID, CustomerSku> bySnapshot = customerSkus.ofSnapshots(snapshotOf.values());
        Map<UUID, String> skus = new HashMap<>();
        snapshotOf.forEach((design, snapshot) -> {
            CustomerSku sku = bySnapshot.get(snapshot);
            if (sku != null) {
                skus.put(design, sku.sku());
            }
        });
        return skus;
    }

    /** Requests as read: their lines, designs, projects, customers and SKUs read once for the lot. */
    private List<ProjectQuoteRequestView> views(List<DesignProjectQuoteRequestJpaEntity> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<DesignProjectQuoteRequestLineJpaEntity>> linesOf = lines.findByRequestIdIn(found.stream()
                .map(DesignProjectQuoteRequestJpaEntity::getId).toList()).stream()
                .collect(Collectors.groupingBy(DesignProjectQuoteRequestLineJpaEntity::getRequestId));
        Map<UUID, DesignDraftJpaEntity> designs = drafts.findAllById(linesOf.values().stream().flatMap(List::stream)
                        .map(DesignProjectQuoteRequestLineJpaEntity::getDraftId).distinct().toList()).stream()
                .collect(Collectors.toMap(DesignDraftJpaEntity::getId, Function.identity()));
        Map<UUID, String> skus = customerSkusOf(designs.values());
        Map<UUID, DesignProjectJpaEntity> projectsById = projects.findAllById(found.stream()
                        .map(DesignProjectQuoteRequestJpaEntity::getProjectId).distinct().toList()).stream()
                .collect(Collectors.toMap(DesignProjectJpaEntity::getId, Function.identity()));
        Map<UUID, String> names = customers.fullNames(projectsById.values().stream()
                .map(DesignProjectJpaEntity::getCustomerId).collect(Collectors.toSet()));
        return found.stream().map(request -> {
            DesignProjectJpaEntity project = projectsById.get(request.getProjectId());
            List<LineView> inRequest = linesOf.getOrDefault(request.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(DesignProjectQuoteRequestLineJpaEntity::getDraftId))
                    .map(line -> {
                        DesignDraftJpaEntity design = designs.get(line.getDraftId());
                        // As a single design's request reads it: the reviewer's notes of a design returned to draft.
                        boolean returned = design.getStatus() == DesignStatus.DRAFT && design.getReviewedAt() != null;
                        return new LineView(design.getId(), design.getName(), design.getStatus(),
                                skus.get(design.getId()), line.getCartons(), returned ? design.getReviewNotes() : null);
                    }).toList();
            return new ProjectQuoteRequestView(request.getId(), project.getId(), project.getName(),
                    project.getCustomerId(), names.get(project.getCustomerId()), request.getDesiredDeliveryDate(),
                    request.getRequestedBy(), request.getCreatedAt(), request.getStatus(), request.getClosedAt(),
                    request.getClosedBy(), request.getCloseReason(), inRequest);
        }).toList();
    }
}
