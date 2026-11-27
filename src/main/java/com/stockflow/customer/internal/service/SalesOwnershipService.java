package com.stockflow.customer.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.CustomerSalesOwnerChanged;
import com.stockflow.contracts.SalesOwnerChangeReason;
import com.stockflow.customer.api.AssignSalesOwnerCommand;
import com.stockflow.customer.api.ClaimOutcome;
import com.stockflow.customer.api.CustomerSalesOwner;
import com.stockflow.customer.api.Responsible;
import com.stockflow.customer.api.ResponsibleKind;
import com.stockflow.customer.api.ResponsibleSales;
import com.stockflow.customer.api.SalesOwnerState;
import com.stockflow.customer.api.SalesOwnership;
import com.stockflow.customer.internal.domain.Customer;
import com.stockflow.customer.internal.domain.CustomerRepository;
import com.stockflow.customer.internal.domain.SalesOwner;
import com.stockflow.customer.internal.domain.SalesOwnerChange;
import com.stockflow.customer.internal.domain.SalesOwnerHistory;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.SalesStaff;
import com.stockflow.identity.api.StaffCovers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A customer's Sales owner (sales-owner plan 2026-11-28): {@link SalesOwnership} for writing and checking it,
 * {@link ResponsibleSales} for "who deals with this customer today". The transaction boundary of every change.
 *
 * <h2>Every change, the same four steps</h2>
 * <ol>
 *   <li>the customer row {@code FOR UPDATE} (two managers, a claim and a transfer meet here; customers of a batch are
 *       locked in id order, as {@link #releaseCustomersOf} does);</li>
 *   <li>the aggregate decides ({@code Customer.assignSalesOwner} and siblings): version, reason, whether anything
 *       changes at all;</li>
 *   <li>the customer and its history row are written in this transaction (the history is append-only);</li>
 *   <li>{@code CustomerSalesOwnerChanged} is published, delivered after commit (a released book is told once, as
 *       {@code CustomersNeedSalesOwner}, by the deactivation listener).</li>
 * </ol>
 *
 * <p>Whether a person may look after customers (BR-S04) is asked of identity ({@link SalesStaff}), with the account
 * read {@code FOR SHARE} when it is about to be made an owner (finding C2: a lock committing between the check and the
 * write left customers assigned to a locked account for good). Whether the owner works today is read from the account
 * every time - ACTIVE and still holding SALES_STAFF (finding C3) - not trusted from the customer's state, because the
 * listener that marks a gone owner's customers runs after the lock or the revoke commits.</p>
 */
@Service
@Transactional
public class SalesOwnershipService implements SalesOwnership, ResponsibleSales {

    private static final Logger log = LoggerFactory.getLogger(SalesOwnershipService.class);

    /** The permission of a manager, who acts for any customer (BR-S12) and changes owners (BR-S01). */
    static final String MANAGES_OWNERS = "customer-sales-owners:UPDATE";

    private final CustomerRepository customers;
    private final SalesOwnerHistory history;
    private final SalesStaff salesStaff;
    private final StaffCovers covers;
    private final IdentityService identity;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    SalesOwnershipService(CustomerRepository customers, SalesOwnerHistory history, SalesStaff salesStaff,
                          StaffCovers covers, IdentityService identity, ApplicationEventPublisher events, Clock clock) {
        this.customers = customers;
        this.history = history;
        this.salesStaff = salesStaff;
        this.covers = covers;
        this.identity = identity;
        this.events = events;
        this.clock = clock;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerSalesOwner> ownerOf(UUID customerId) {
        return customerId == null ? Optional.empty() : customers.findById(customerId).map(SalesOwnershipService::view);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean mayActFor(UUID userId, UUID customerId, LocalDate day) {
        return refusalFor(userId, customerId, day).isEmpty();
    }

    @Override
    @Transactional(readOnly = true)
    public void requireMayActFor(UUID userId, UUID customerId, LocalDate day) {
        Optional<Refusal> refusal = refusalFor(userId, customerId, day);
        if (refusal.isEmpty()) {
            return;
        }
        UUID ownerId = refusal.get().ownerId();
        String name = ownerId == null ? "?" : identity.displayNames(List.of(ownerId)).getOrDefault(ownerId, "?");
        Map<String, Object> details = ownerId == null ? Map.of()
                : Map.of("salesOwnerId", ownerId, "salesOwnerName", name);
        throw new BusinessException(ErrorCode.CUSTOMER_OWNED_BY_OTHER_SALES,
                "Customer %s is looked after by %s".formatted(customerId, ownerId), List.of(), details)
                .localized("refusal.customer.ownedByOtherSales", name);
    }

    /** Why {@code userId} may not act: the owner in the way, null when there is none to name (unknown customer). */
    private record Refusal(UUID ownerId) {
    }

    /** Empty when {@code userId} may act for the customer (BR-S12). */
    private Optional<Refusal> refusalFor(UUID userId, UUID customerId, LocalDate day) {
        if (userId == null || customerId == null) {
            return Optional.of(new Refusal(null));
        }
        SalesOwner owner = customers.salesOwners(List.of(customerId)).get(customerId);
        if (owner == null) {
            return Optional.of(new Refusal(null));
        }
        if (owner.state() != SalesOwnerState.ASSIGNED || owner.userId().equals(userId)) {
            return Optional.empty();
        }
        if (!isWorking(owner.userId())) {
            return Optional.empty();
        }
        StaffCovers.ActiveCover cover = covers.activeCovers(List.of(owner.userId()), dayOr(day)).get(owner.userId());
        if (cover != null && cover.coverUserId().equals(userId)) {
            return Optional.empty();
        }
        if (salesStaff.holdsPermission(userId, MANAGES_OWNERS)) {
            return Optional.empty();
        }
        return Optional.of(new Refusal(owner.userId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Responsible> responsibleFor(Collection<UUID> customerIds, LocalDate businessDay) {
        if (customerIds == null || customerIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, SalesOwner> owners = customers.salesOwners(customerIds);
        if (owners.isEmpty()) {
            return Map.of();
        }
        Set<UUID> assigned = owners.values().stream().filter(owner -> owner.state() == SalesOwnerState.ASSIGNED)
                .map(SalesOwner::userId).collect(Collectors.toSet());
        // C3: an owner who is ACTIVE but no longer holds SALES_STAFF is gone as much as a locked one.
        Set<UUID> working = assigned.isEmpty() ? Set.of() : salesStaff.activeSalesAgentsAmong(assigned);
        Map<UUID, StaffCovers.ActiveCover> coverOf = working.isEmpty() ? Map.of()
                : covers.activeCovers(working, dayOr(businessDay));
        boolean anyWithoutOwner = owners.values().stream()
                .anyMatch(owner -> owner.state() != SalesOwnerState.ASSIGNED || !working.contains(owner.userId()));
        List<UUID> managers = anyWithoutOwner ? fallbackRecipients() : List.of();

        Map<UUID, Responsible> answer = new HashMap<>();
        owners.forEach((customerId, owner) -> {
            if (owner.state() == SalesOwnerState.ASSIGNED && working.contains(owner.userId())) {
                StaffCovers.ActiveCover cover = coverOf.get(owner.userId());
                answer.put(customerId, cover == null
                        ? new Responsible(customerId, ResponsibleKind.OWNER, owner.userId(), owner.userId(), List.of())
                        : new Responsible(customerId, ResponsibleKind.COVER, owner.userId(), cover.coverUserId(),
                                List.of()));
            } else {
                answer.put(customerId, new Responsible(customerId, ResponsibleKind.MANAGERS, owner.userId(), null,
                        managers));
            }
        });
        return answer;
    }

    /** D6 and case 25: the active Sales Managers, else the System Admins. Never every salesperson. */
    private List<UUID> fallbackRecipients() {
        List<UUID> managers = salesStaff.activeSalesManagers();
        return managers.isEmpty() ? salesStaff.activeSystemAdmins() : managers;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Writing
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    public CustomerSalesOwner assign(AssignSalesOwnerCommand command) {
        Customer customer = lock(command.customerId());
        if (command.salesOwnerId() != null && !customer.salesOwner().isAssignedTo(command.salesOwnerId())) {
            requireAssignable(command.salesOwnerId());
        }
        Optional<SalesOwnerChange> change = customer.assignSalesOwner(command.salesOwnerId(), command.reason(),
                command.note(), command.expectedVersion(), command.actorUserId(), clock.instant(), null);
        if (change.isEmpty()) {
            return view(customer);
        }
        return view(write(customer, change.get(), command.notifyCustomer()));
    }

    @Override
    public ClaimOutcome claimIfUnowned(UUID customerId, UUID claimerId, LocalDate day) {
        Customer customer = lock(customerId);
        SalesOwner owner = customer.salesOwner();
        if (owner.isAssignedTo(claimerId)) {
            return ClaimOutcome.ALREADY_OWNER;
        }
        boolean ownerActive = owner.state() == SalesOwnerState.ASSIGNED && isWorking(owner.userId());
        if (ownerActive) {
            StaffCovers.ActiveCover cover = covers.activeCovers(List.of(owner.userId()), dayOr(day))
                    .get(owner.userId());
            if (cover != null && cover.coverUserId().equals(claimerId)) {
                return ClaimOutcome.COVERING;
            }
        } else {
            requireAssignable(claimerId);
        }
        Optional<SalesOwnerChange> change = customer.claimIfUnowned(claimerId, ownerActive, clock.instant());
        change.ifPresent(claimed -> write(customer, claimed, false));
        return ClaimOutcome.CLAIMED;
    }

    /**
     * A customer is given its first owner as it is created (BR-S02): the creating Sales, a manager's choice, or a lead's
     * Sales. Joins the caller's transaction; the customer must have been saved already (the history names it).
     *
     * @return the customer as saved with its owner
     * @throws BusinessException {@code SALES_OWNER_NOT_ASSIGNABLE}
     */
    public Customer assignAtCreation(Customer customer, UUID ownerId, SalesOwnerChangeReason reason, UUID actorUserId) {
        requireAssignable(ownerId);
        Optional<SalesOwnerChange> change = customer.assignSalesOwner(ownerId, reason, null, customer.version(),
                actorUserId, clock.instant(), null);
        return change.map(assigned -> write(customer, assigned, false)).orElse(customer);
    }

    /**
     * One customer of a bulk or temporary transfer, or of its return (BR-S09, §10.1): moved to {@code toUserId} only if
     * it is still {@code fromUserId}'s (assigned to them, or waiting after they left). For the transfer service, which
     * holds the batch and locks its customers in id order; the target's eligibility is the caller's to check once.
     *
     * @return the change, or empty when the customer was skipped (someone changed it meanwhile)
     * @throws BusinessException {@code CUSTOMER_NOT_FOUND}; {@code SALES_OWNER_CHANGE_REASON_REQUIRED}
     */
    public Optional<SalesOwnerChange> transfer(UUID customerId, UUID fromUserId, UUID toUserId,
                                               SalesOwnerChangeReason reason, String note, boolean notifyCustomer,
                                               UUID actorUserId, UUID batchId) {
        Customer customer = lock(customerId);
        Optional<SalesOwnerChange> change = customer.transferSalesOwner(fromUserId, toUserId, reason, note, actorUserId,
                clock.instant(), batchId);
        change.ifPresent(moved -> write(customer, moved, notifyCustomer));
        return change;
    }

    /**
     * BR-S08: the account {@code formerOwnerId} stopped working. Every customer it owned waits for an owner (the former
     * owner kept), one history row each; the caller tells the managers once for the whole book. Customers are locked
     * one by one in id order. A retried event finds none left to mark.
     *
     * @return the customers marked, in id order
     */
    public List<UUID> releaseCustomersOf(UUID formerOwnerId, UUID byUserId) {
        List<UUID> marked = new java.util.ArrayList<>();
        for (UUID customerId : customers.idsOwnedBy(formerOwnerId, SalesOwnerState.ASSIGNED)) {
            Customer customer = customers.findForUpdate(customerId).orElse(null);
            if (customer == null) {
                continue;
            }
            Optional<SalesOwnerChange> change = customer.markNeedsSalesOwner(formerOwnerId, byUserId, clock.instant());
            if (change.isPresent()) {
                customers.save(customer);
                history.record(change.get());
                marked.add(customerId);
            }
        }
        if (!marked.isEmpty()) {
            log.info("{} customer(s) of account {} now need a sales owner", marked.size(), formerOwnerId);
        }
        return marked;
    }

    private Customer lock(UUID customerId) {
        if (customerId == null) {
            throw new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND);
        }
        return customers.findForUpdate(customerId).orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
    }

    private Customer write(Customer customer, SalesOwnerChange change, boolean notifyCustomer) {
        Customer saved = customers.save(customer);
        history.record(change);
        events.publishEvent(new CustomerSalesOwnerChanged(change.customerId(), change.fromUserId(), change.toUserId(),
                change.reason(), change.batchId(), notifyCustomer, change.changedBy(), change.changedAt()));
        return saved;
    }

    /**
     * BR-S04: an owner is an active Sales agent - read {@code FOR SHARE}, so that no lock, disable or revoke of the
     * account commits before this transaction does (finding C2; the trigger on {@code customer.customer} does the same).
     */
    private void requireAssignable(UUID userId) {
        if (!salesStaff.lockActiveSalesAgent(userId)) {
            throw new BusinessException(ErrorCode.SALES_OWNER_NOT_ASSIGNABLE,
                    "Account %s is not an active salesperson".formatted(userId));
        }
    }

    /** Whether the owner still works as a salesperson: ACTIVE and holding SALES_STAFF (findings C2, C3). */
    private boolean isWorking(UUID userId) {
        return salesStaff.isActiveSalesAgent(userId);
    }

    private LocalDate dayOr(LocalDate day) {
        return day != null ? day : BusinessCalendar.date(clock.instant());
    }

    static CustomerSalesOwner view(Customer customer) {
        SalesOwner owner = customer.salesOwner();
        return new CustomerSalesOwner(customer.id(), owner.userId(), owner.state(), owner.since(), customer.version());
    }
}
