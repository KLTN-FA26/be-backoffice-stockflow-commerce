package com.stockflow.design.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One quote asked over approved designs of a design project (kltn-docs 12 §4.11; BR-21; 16 §4.2): a line per design,
 * the cartons of each a suggestion (14 BR-22), one delivery date for all of it. Only approved designs of an open project
 * - there is no "submit and ask" at the project's level (§4.11 step 36). Not an order, no stock held, no price (BR-15).
 *
 * <p>The sales staff are told ({@link DesignProjectQuoteRequested}) and list the requests with the project's name as
 * context (BR-17), until the chat module carries the request as a card of several lines.</p>
 *
 * <p><b>One open request per project</b>, as per design (B40): the customer who clicks "ask for a quote" twice - a
 * double click, a retried request, a second tab - gets the request already open, and the sales staff are told once.
 * Asking something else while it is open changes what it asks, and they are told what it asks now. Once they close it
 * as answered, the project asks anew. Every writer takes the project's lock first, so two asks never both find none
 * open; {@code uk_design_project_quote_request_open} is the last line of defence.</p>
 */
@Service
@Transactional
public class DesignProjectQuoteRequestService {

    private final DesignProjectService projectService;
    private final DesignProjectJpaRepository projects;
    private final DesignDraftJpaRepository drafts;
    private final DesignSnapshotJpaRepository snapshots;
    private final DesignProjectQuoteRequestJpaRepository requests;
    private final DesignProjectQuoteRequestLineJpaRepository lines;
    private final DesignDecisionJpaRepository decisions;
    private final CustomerSkus customerSkus;
    private final CustomerService customers;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public DesignProjectQuoteRequestService(DesignProjectService projectService, DesignProjectJpaRepository projects,
                                            DesignDraftJpaRepository drafts, DesignSnapshotJpaRepository snapshots,
                                            DesignProjectQuoteRequestJpaRepository requests,
                                            DesignProjectQuoteRequestLineJpaRepository lines,
                                            DesignDecisionJpaRepository decisions, CustomerSkus customerSkus,
                                            CustomerService customers, ApplicationEventPublisher events, Clock clock) {
        this.projectService = projectService;
        this.projects = projects;
        this.drafts = drafts;
        this.snapshots = snapshots;
        this.requests = requests;
        this.lines = lines;
        this.decisions = decisions;
        this.customerSkus = customerSkus;
        this.customers = customers;
        this.events = events;
        this.clock = clock;
    }

    /** One design asked, and its cartons. */
    public record LineInput(UUID designId, int cartons) {
    }

    /** One line as read: the design as it is now, its own SKU, the cartons asked. */
    public record LineView(UUID designId, String designName, DesignStatus designStatus, String customerSku,
                           int cartons) {
    }

    /** A project quote request as read; {@code projectName} is the context the sales staff see (BR-17). */
    public record ProjectQuoteRequestView(UUID id, UUID projectId, String projectName, UUID customerId,
                                          String customerName, LocalDate desiredDeliveryDate, UUID requestedBy,
                                          Instant requestedAt, QuoteRequestStatus status, Instant closedAt,
                                          UUID closedBy, List<LineView> lines) {
    }

    /** A request as asked: {@code created} when it is new, false when the open one answers (or now asks this). */
    public record Asked(ProjectQuoteRequestView request, boolean created) {
    }

    /**
     * The project's owner asks one quote over {@code asked}: at least one, each an approved design of the project
     * named once, with 1 to {@value QuoteRequestRules#MAX_CARTONS} cartons; one delivery date, today or later. With a
     * request of the project open, the same ask answers with it and changes nothing - no history, no notice - and a
     * different one changes what it asks.
     *
     * @throws BusinessException 400 for no line, a design named twice, cartons out of range, a past date; 409
     *         {@code DESIGN_NOT_APPROVED}, {@code DESIGN_PROJECT_ARCHIVED}, {@code CUSTOMER_NOT_ACTIVE}; 404 for a
     *         design not in the project, or anyone but the owner
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "design-project", resourceId = "#projectId")
    public Asked request(UUID projectId, UUID actor, List<LineInput> asked, LocalDate desiredDeliveryDate) {
        if (asked == null || asked.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A project quote request names at least one design");
        }
        Set<UUID> named = new HashSet<>();
        LocalDate today = BusinessCalendar.date(clock.instant());
        for (int i = 0; i < asked.size(); i++) {
            LineInput line = asked.get(i);
            if (line.designId() == null || !named.add(line.designId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Each design is named once in a request");
            }
            QuoteRequestRules.requireValid(line.cartons(), desiredDeliveryDate, today, "lines[" + i + "].");
        }
        DesignProjectJpaEntity project = projectService.lockActive(projectId, actor);
        requireActiveCustomer(actor, project);
        Map<UUID, DesignDraftJpaEntity> designs = drafts.findAllById(named).stream()
                .collect(Collectors.toMap(DesignDraftJpaEntity::getId, Function.identity()));
        for (UUID id : named) {
            DesignDraftJpaEntity design = designs.get(id);
            if (design == null || !projectId.equals(design.getProjectId())) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
            QuoteRequestRules.requireApproved(design.getStatus());
        }
        Map<UUID, String> skus = customerSkusOf(designs.values());

        Optional<DesignProjectQuoteRequestJpaEntity> open = requests.findOpen(projectId);
        DesignProjectQuoteRequestJpaEntity request;
        String history;
        if (open.isPresent()) {
            request = open.get();
            List<DesignProjectQuoteRequestLineJpaEntity> before = lines.findByRequestIdIn(List.of(request.getId()));
            if (sameAsk(request, before, asked, desiredDeliveryDate)) {
                // A second click on the same ask: the request it made, told to nobody again.
                return new Asked(views(List.of(request)).getFirst(), false);
            }
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
        List<DesignProjectQuoteRequested.Line> facts = new ArrayList<>();
        for (LineInput line : asked) {
            lines.save(new DesignProjectQuoteRequestLineJpaEntity(request.getId(), line.designId(), line.cartons()));
            decisions.save(new DesignDecisionJpaEntity(designs.get(line.designId()), actor,
                    DesignDecisionType.QUOTE_REQUESTED.name(), line.cartons() + " carton(s)"
                    + (desiredDeliveryDate == null ? "" : ", delivery by " + desiredDeliveryDate)
                    + ", project request " + request.getId()));
            facts.add(new DesignProjectQuoteRequested.Line(line.designId(), skus.get(line.designId()), line.cartons()));
        }
        lines.flush();
        projectService.record(project, actor, ProjectEventType.PROJECT_QUOTE_REQUESTED, history
                + asked.stream().map(l -> l.designId() + " x" + l.cartons()).collect(Collectors.joining(", "))
                + (desiredDeliveryDate == null ? "" : "; delivery by " + desiredDeliveryDate));
        Instant now = clock.instant();
        events.publishEvent(new DesignProjectQuoteRequested(request.getId(), projectId, project.getCustomerId(), facts,
                desiredDeliveryDate, now));
        return new Asked(views(List.of(request)).getFirst(), open.isEmpty());
    }

    /**
     * A salesperson closes a request they have answered - quoted it, or settled it with the customer - so that the
     * project asks again, for its next order, with a new one. Closing a closed request changes nothing.
     *
     * @throws BusinessException 404 for no such request
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design-project-quote-request", resourceId = "#requestId")
    public ProjectQuoteRequestView close(UUID requestId, UUID actor) {
        if (actor == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        UUID projectId = requests.findById(requestId).map(DesignProjectQuoteRequestJpaEntity::getProjectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // The project's lock first, as an ask takes it: no ask slips in between reading the request and closing it.
        var project = projects.lockById(projectId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        DesignProjectQuoteRequestJpaEntity request = requests.lockById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (request.getStatus() == QuoteRequestStatus.OPEN) {
            Instant now = clock.instant();
            request.close(now, actor);
            request = requests.saveAndFlush(request);
            // Only on the move itself: closing a closed request changes nothing and tells nobody.
            events.publishEvent(new com.stockflow.contracts.DesignProjectQuoteRequestClosed(request.getId(), projectId,
                    project.getCustomerId(), actor, now));
        }
        return views(List.of(request)).getFirst();
    }

    /** One request as read, whoever asks: the chat's quote request card of several lines (16 §4.2). */
    @Transactional(readOnly = true)
    public java.util.Optional<ProjectQuoteRequestView> find(UUID requestId) {
        return requests.findById(requestId).map(request -> views(List.of(request)).getFirst());
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

    /** The project's quote requests, the newest first, to its owner. */
    @Transactional(readOnly = true)
    public List<ProjectQuoteRequestView> ofProject(UUID projectId, UUID actor) {
        projectService.own(projectId, actor);
        return views(requests.findByProjectIdOrderByCreatedAtDescIdDesc(projectId));
    }

    /** The sales staff's list, the newest first, of every customer or one. */
    @Transactional(readOnly = true)
    public Page<ProjectQuoteRequestView> list(UUID customerId, Pageable page) {
        Page<DesignProjectQuoteRequestJpaEntity> found = customerId == null
                ? requests.findAllByOrderByCreatedAtDescIdDesc(Pages.of(page.getPageNumber(), page.getPageSize()))
                : requests.ofCustomer(customerId, Pages.of(page.getPageNumber(), page.getPageSize()));
        return new PageImpl<>(views(found.getContent()), found.getPageable(), found.getTotalElements());
    }

    private void requireActiveCustomer(UUID actor, DesignProjectJpaEntity project) {
        CustomerSummary customer = customers.findByUserId(actor)
                .filter(found -> found.customerId().equals(project.getCustomerId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!"ACTIVE".equals(customer.status())) {
            throw new BusinessException(ErrorCode.CUSTOMER_NOT_ACTIVE,
                    "The customer is " + customer.status() + "; only an active customer asks for a quote");
        }
    }

    /** The customer's own SKU of each approved design, read once for the lot. */
    private Map<UUID, String> customerSkusOf(java.util.Collection<DesignDraftJpaEntity> designs) {
        List<UUID> approved = designs.stream().filter(d -> d.getStatus() == DesignStatus.APPROVED)
                .map(DesignDraftJpaEntity::getId).toList();
        if (approved.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> snapshotOf = snapshots.snapshotsOf(approved).stream()
                .collect(Collectors.toMap(DesignSnapshotJpaRepository.SnapshotOf::getDraftId,
                        DesignSnapshotJpaRepository.SnapshotOf::getId));
        Map<UUID, CustomerSku> bySnapshot = customerSkus.ofSnapshots(snapshotOf.values());
        Map<UUID, String> skus = new java.util.HashMap<>();
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
                        return new LineView(design.getId(), design.getName(), design.getStatus(),
                                skus.get(design.getId()), line.getCartons());
                    }).toList();
            return new ProjectQuoteRequestView(request.getId(), project.getId(), project.getName(),
                    project.getCustomerId(), names.get(project.getCustomerId()), request.getDesiredDeliveryDate(),
                    request.getRequestedBy(), request.getCreatedAt(), request.getStatus(), request.getClosedAt(),
                    request.getClosedBy(), inRequest);
        }).toList();
    }
}
