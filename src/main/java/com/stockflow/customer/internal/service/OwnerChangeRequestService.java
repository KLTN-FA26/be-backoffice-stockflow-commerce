package com.stockflow.customer.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.OwnerChangeRequestDecided;
import com.stockflow.contracts.OwnerChangeRequested;
import com.stockflow.contracts.SalesOwnerChangeReason;
import com.stockflow.customer.api.AssignSalesOwnerCommand;
import com.stockflow.customer.api.SalesOwnerState;
import com.stockflow.customer.internal.domain.Customer;
import com.stockflow.customer.internal.domain.CustomerRepository;
import com.stockflow.customer.internal.domain.OwnerChangeRequest;
import com.stockflow.customer.internal.domain.OwnerChangeRequestRepository;
import com.stockflow.customer.internal.domain.SalesContacts;
import com.stockflow.customer.internal.domain.SalesOwner;
import com.stockflow.customer.internal.repository.CustomerSearchRepository;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.SalesStaff;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A customer's request to change salesperson (sales-owner plan §10.5, BR-S15, D11, D12): the customer asks from the
 * portal, the Sales Managers decide.
 *
 * <ul>
 *   <li>One PENDING request per customer, and {@link OwnerChangeRequest#COOLDOWN} after a decision before the next.
 *       The customer row is locked first, so two submissions of one customer queue up; the partial unique index
 *       {@code uk_sales_owner_change_request_pending} is the last word.</li>
 *   <li>The current owner is not told until the decision: {@link OwnerChangeRequested} goes to the managers only, and
 *       nothing a salesperson reads lists requests.</li>
 *   <li>The managers' list warns when the salesperson the customer named has been in touch with them in the last
 *       {@link #RECENT_CONTACT} ({@code preferredSalesRecentlyContacted}): a quote or contract sent, or the customer
 *       (and so its conversations) assigned to them - a sign of canvassing.</li>
 *   <li>Approval changes the owner through {@code SalesOwnership} (reason CUSTOMER_REQUEST, the customer told in the
 *       chat). Lock order: customer, then request.</li>
 *   <li>Every decision publishes {@link OwnerChangeRequestDecided} (§10.5 step 4, finding C1): on approval both
 *       salespeople are told, on rejection the customer is told to read the answer.</li>
 * </ul>
 */
@Service
@Transactional
public class OwnerChangeRequestService {

    /** §10.5 step 3: how far back "in touch lately" looks. */
    static final Duration RECENT_CONTACT = Duration.ofDays(30);

    /** How many of its own requests a customer sees. */
    static final int OWN_LISTED = 50;

    private final OwnerChangeRequestRepository requests;
    private final CustomerRepository customers;
    private final CustomerSearchRepository customerNames;
    private final SalesOwnershipService ownership;
    private final SalesContacts contacts;
    private final SalesStaff salesStaff;
    private final IdentityService identity;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    OwnerChangeRequestService(OwnerChangeRequestRepository requests, CustomerRepository customers,
                              CustomerSearchRepository customerNames, SalesOwnershipService ownership, SalesContacts contacts, SalesStaff salesStaff,
                              IdentityService identity, ApplicationEventPublisher events, Clock clock) {
        this.requests = requests;
        this.customers = customers;
        this.customerNames = customerNames;
        this.ownership = ownership;
        this.contacts = contacts;
        this.salesStaff = salesStaff;
        this.identity = identity;
        this.events = events;
        this.clock = clock;
    }

    /** A request as its customer sees it: names only, never who decided. */
    public record OwnRow(UUID id, OwnerChangeRequest.Status status, OwnerChangeRequest.Reason reason, String note,
                         String preferredSalesName, String decisionNote, Instant createdAt, Instant decidedAt) {
    }

    /**
     * A request as the managers see it. {@code currentOwner*} is the customer's owner now while PENDING, and the owner
     * when it asked once decided.
     */
    public record ManagerRow(UUID id, UUID customerId, String customerName, UUID currentOwnerId,
                             String currentOwnerName, UUID preferredSalesUserId, String preferredSalesName,
                             boolean preferredSalesRecentlyContacted, OwnerChangeRequest.Reason reason, String note,
                             OwnerChangeRequest.Status status, UUID newSalesOwnerId, String newSalesOwnerName,
                             String decisionNote, Instant createdAt, Instant decidedAt) {
    }

    /**
     * The signed-in customer asks.
     *
     * @throws BusinessException {@code CUSTOMER_NOT_FOUND} for an account with no customer;
     *         {@code OWNER_CHANGE_REQUEST_PENDING}; {@code OWNER_CHANGE_REQUEST_COOLDOWN}; {@code VALIDATION_FAILED}
     *         (no reason, or the current owner named); {@code SALES_OWNER_NOT_ASSIGNABLE} for a named salesperson who is
     *         not an active Sales agent
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "sales-owner-change-request", resourceId = "#result?.id()")
    public OwnRow ask(UUID portalUserId, OwnerChangeRequest.Reason reason, String note, UUID preferredSalesUserId) {
        Customer customer = customers.findByUserIdForUpdate(portalUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        Instant now = clock.instant();
        OwnerChangeRequest.requireMayAskAfter(requests.latestOf(customer.id()).orElse(null), now);
        if (preferredSalesUserId != null && !salesStaff.isActiveSalesAgent(preferredSalesUserId)) {
            throw new BusinessException(ErrorCode.SALES_OWNER_NOT_ASSIGNABLE,
                    "Account %s is not an active salesperson".formatted(preferredSalesUserId));
        }
        SalesOwner owner = customer.salesOwner();
        OwnerChangeRequest request = OwnerChangeRequest.ask(customer.id(), portalUserId,
                owner.state() == SalesOwnerState.UNASSIGNED ? null : owner.userId(), reason, note,
                preferredSalesUserId, now);
        try {
            requests.insert(request);
        } catch (DuplicateKeyException raced) {
            throw new BusinessException(ErrorCode.OWNER_CHANGE_REQUEST_PENDING);
        }
        events.publishEvent(new OwnerChangeRequested(request.id(), customer.id(), now));
        return own(List.of(request)).get(0);
    }

    /** The signed-in customer's requests, newest first. */
    @Transactional(readOnly = true)
    public List<OwnRow> mine(UUID portalUserId) {
        Customer customer = customers.findByUserId(portalUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        return own(requests.of(customer.id(), OWN_LISTED));
    }

    /** Every customer's requests for the managers, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<ManagerRow> list(OwnerChangeRequest.Status status, int page, int size) {
        int bounded = Math.max(1, Math.min(size, 100));
        int from = Math.max(0, page);
        List<OwnerChangeRequest> found = requests.list(status, from, bounded);
        return PageResponse.of(managerRows(found), from, bounded, requests.count(status));
    }

    /**
     * A manager agrees and names the new owner: the owner changes (reason CUSTOMER_REQUEST, BR-S03; the customer told
     * in the chat), the request is APPROVED.
     *
     * @throws BusinessException {@code OWNER_CHANGE_REQUEST_NOT_FOUND}; {@code OWNER_CHANGE_REQUEST_ALREADY_DECIDED};
     *         {@code VALIDATION_FAILED} without a new owner or naming the current one;
     *         {@code SALES_OWNER_NOT_ASSIGNABLE}
     */
    @Auditable(action = AuditAction.APPROVE, resourceType = "sales-owner-change-request", resourceId = "#requestId")
    public ManagerRow approve(UUID requestId, UUID newSalesOwnerId, String note, UUID actor) {
        OwnerChangeRequest request = lockWithCustomer(requestId);
        Customer customer = customers.findById(request.customerId()).orElseThrow();
        if (newSalesOwnerId != null && customer.salesOwner().isAssignedTo(newSalesOwnerId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "That salesperson already looks after the customer; reject the request instead");
        }
        Instant now = clock.instant();
        UUID previousOwner = customer.salesOwner().state() == SalesOwnerState.UNASSIGNED ? null
                : customer.salesOwner().userId();
        request.approve(newSalesOwnerId, note, actor, now);
        ownership.assign(new AssignSalesOwnerCommand(customer.id(), newSalesOwnerId,
                SalesOwnerChangeReason.CUSTOMER_REQUEST, request.decisionNote(), true, customer.version(), actor));
        requests.update(request);
        events.publishEvent(new OwnerChangeRequestDecided(request.id(), customer.id(), true, previousOwner,
                newSalesOwnerId, now));
        return managerRows(List.of(requests.findById(requestId).orElseThrow())).get(0);
    }

    /**
     * A manager refuses, telling the customer why.
     *
     * @throws BusinessException {@code OWNER_CHANGE_REQUEST_NOT_FOUND}; {@code OWNER_CHANGE_REQUEST_ALREADY_DECIDED};
     *         {@code VALIDATION_FAILED} without a note
     */
    @Auditable(action = AuditAction.REJECT, resourceType = "sales-owner-change-request", resourceId = "#requestId")
    public ManagerRow reject(UUID requestId, String note, UUID actor) {
        OwnerChangeRequest request = lockWithCustomer(requestId);
        Instant now = clock.instant();
        request.reject(note, actor, now);
        requests.update(request);
        SalesOwner owner = customers.findById(request.customerId()).orElseThrow().salesOwner();
        events.publishEvent(new OwnerChangeRequestDecided(request.id(), request.customerId(), false,
                owner.state() == SalesOwnerState.UNASSIGNED ? null : owner.userId(), null, now));
        return managerRows(List.of(requests.findById(requestId).orElseThrow())).get(0);
    }

    /** The customer row first, then the request: the order {@link #ask} takes them in. */
    private OwnerChangeRequest lockWithCustomer(UUID requestId) {
        OwnerChangeRequest unlocked = requests.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.OWNER_CHANGE_REQUEST_NOT_FOUND));
        customers.findForUpdate(unlocked.customerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        return requests.findForUpdate(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.OWNER_CHANGE_REQUEST_NOT_FOUND));
    }

    private List<OwnRow> own(List<OwnerChangeRequest> found) {
        Map<UUID, String> names = names(found.stream().map(OwnerChangeRequest::preferredSalesUserId).toList());
        return found.stream().map(request -> new OwnRow(request.id(), request.status(), request.reason(),
                request.note(), names.get(request.preferredSalesUserId()), request.decisionNote(),
                request.requestedAt(), request.decidedAt())).toList();
    }

    private List<ManagerRow> managerRows(List<OwnerChangeRequest> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Set<UUID> customerIds = found.stream().map(OwnerChangeRequest::customerId).collect(Collectors.toSet());
        Map<UUID, SalesOwner> owners = customers.salesOwners(customerIds);
        Map<UUID, String> nameOf = customerNames.fullNames(customerIds);
        Set<UUID> people = new HashSet<>();
        found.forEach(request -> {
            people.add(request.currentOwnerId());
            people.add(request.preferredSalesUserId());
            people.add(request.newSalesOwnerId());
        });
        owners.values().forEach(owner -> people.add(owner.userId()));
        Map<UUID, String> names = names(people);
        Set<SalesContacts.Pair> inTouch = contacts.inTouchSince(found.stream()
                .filter(request -> request.preferredSalesUserId() != null)
                .map(request -> new SalesContacts.Pair(request.customerId(), request.preferredSalesUserId()))
                .toList(), clock.instant().minus(RECENT_CONTACT));
        return found.stream().map(request -> {
            UUID current = request.currentOwnerId();
            if (request.status() == OwnerChangeRequest.Status.PENDING) {
                SalesOwner now = owners.get(request.customerId());
                current = now == null || now.state() == SalesOwnerState.UNASSIGNED ? null : now.userId();
            }
            boolean recently = request.preferredSalesUserId() != null && inTouch.contains(
                    new SalesContacts.Pair(request.customerId(), request.preferredSalesUserId()));
            return new ManagerRow(request.id(), request.customerId(), nameOf.get(request.customerId()), current,
                    names.get(current), request.preferredSalesUserId(), names.get(request.preferredSalesUserId()),
                    recently, request.reason(), request.note(), request.status(), request.newSalesOwnerId(),
                    names.get(request.newSalesOwnerId()), request.decisionNote(), request.requestedAt(),
                    request.decidedAt());
        }).toList();
    }

    private Map<UUID, String> names(Collection<UUID> userIds) {
        Set<UUID> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? new java.util.HashMap<>() : new java.util.HashMap<>(identity.displayNames(ids));
    }
}
