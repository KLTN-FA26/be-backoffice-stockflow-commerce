package com.stockflow.customer.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.validation.VietnamPhoneValidator;
import com.stockflow.contracts.SalesOwnerChangeReason;
import com.stockflow.customer.api.SalesOwnerState;
import com.stockflow.customer.internal.domain.Customer;
import com.stockflow.customer.internal.domain.CustomerDuplicates;
import com.stockflow.customer.internal.domain.CustomerRepository;
import com.stockflow.customer.internal.domain.LeadRepository;
import com.stockflow.customer.internal.domain.SalesAssignmentSettings;
import com.stockflow.customer.internal.domain.SalesOwner;
import com.stockflow.customer.internal.domain.SalesOwnerChange;
import com.stockflow.customer.internal.domain.SalesOwnerHistory;
import com.stockflow.customer.internal.domain.TaxCode;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.SalesStaff;
import com.stockflow.identity.api.StaffCovers;
import com.stockflow.identity.api.StaffMember;
import com.stockflow.identity.api.UserLabel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What the screens of the sales-owner plan read: a customer's owner card and history, the owner columns of the customer
 * list, the salespeople a manager picks from, the lock dialog's handover summary, the portal's "your salesperson" card
 * and the duplicate warning of the create form. Read-only; each answer is a fixed handful of statements whatever the
 * number of rows (names and covers are read in one batch).
 */
@Service
@Transactional(readOnly = true)
public class SalesOwnerViews {

    /** The duplicate warning shows at most this many customers. */
    static final int MAX_DUPLICATES = 20;

    private final CustomerRepository customers;
    private final SalesOwnerHistory history;
    private final LeadRepository leads;
    private final SalesAssignmentSettings settings;
    private final CustomerDuplicates duplicates;
    private final SalesStaff salesStaff;
    private final StaffCovers covers;
    private final IdentityService identity;
    private final Clock clock;

    SalesOwnerViews(CustomerRepository customers, SalesOwnerHistory history, LeadRepository leads,
                    SalesAssignmentSettings settings, CustomerDuplicates duplicates, SalesStaff salesStaff,
                    StaffCovers covers, IdentityService identity, Clock clock) {
        this.customers = customers;
        this.history = history;
        this.leads = leads;
        this.settings = settings;
        this.duplicates = duplicates;
        this.salesStaff = salesStaff;
        this.covers = covers;
        this.identity = identity;
        this.clock = clock;
    }

    /** Who stands in for the owner today, and until when. */
    public record Cover(UUID userId, String name, LocalDate until) {
    }

    /** {@code GET /customers/{id}/sales-owner}: {@code active} is whether the owner's account works now. */
    public record OwnerView(UUID customerId, UUID salesOwnerId, String salesOwnerName, boolean active,
                            SalesOwnerState state, Instant since, Cover cover, long version) {
    }

    /** One change of owner, with the people named. */
    public record HistoryRow(UUID id, UUID fromUserId, String fromName, UUID toUserId, String toName,
                             SalesOwnerChangeReason reason, String note, UUID changedById, String changedByName,
                             Instant changedAt, UUID batchId) {
    }

    /** The owner columns of a customer list row. */
    public record OwnerColumns(UUID salesOwnerId, String salesOwnerName, Boolean salesOwnerActive,
                               SalesOwnerState salesOwnerState, Instant salesOwnerSince) {
    }

    /** A salesperson a manager may pick as owner or cover (D42: active SALES_STAFF only). */
    public record SalesAgent(UUID userId, String name, boolean paused, boolean onLeave, LocalDate coverUntil) {
    }

    /**
     * The lock dialog: what a salesperson leaves behind. {@code openConversations} is null: the chat counts them (the
     * customer module may not depend on chat), so the screen reads them from {@code GET /chat/agents/workload}.
     */
    public record HandoverSummary(UUID userId, long customers, long openLeads, Long openConversations) {
    }

    /** The portal card "your salesperson": a name only, never a staff e-mail or phone. */
    public record PortalOwner(String name, boolean active, String coverName) {
    }

    /** A salesperson the customer may name in a change request. */
    public record SalesOption(UUID userId, String name) {
    }

    /** A customer the new one may duplicate, and who looks after it. */
    public record Duplicate(UUID customerId, String fullName, List<CustomerDuplicates.On> matchedOn,
                            String salesOwnerName) {
    }

    /** @throws BusinessException {@code CUSTOMER_NOT_FOUND} */
    public OwnerView owner(UUID customerId) {
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        SalesOwner owner = customer.salesOwner();
        if (owner.userId() == null) {
            return new OwnerView(customerId, null, null, false, owner.state(), owner.since(), null, customer.version());
        }
        UserLabel label = identity.userLabels(List.of(owner.userId())).get(owner.userId());
        boolean active = label != null && label.active();
        Cover cover = null;
        if (active && owner.state() == SalesOwnerState.ASSIGNED) {
            StaffCovers.ActiveCover inForce = covers.activeCovers(List.of(owner.userId()), today()).get(owner.userId());
            if (inForce != null) {
                cover = new Cover(inForce.coverUserId(), names(List.of(inForce.coverUserId()))
                        .get(inForce.coverUserId()), inForce.to());
            }
        }
        return new OwnerView(customerId, owner.userId(), label == null ? null : label.displayName(), active,
                owner.state(), owner.since(), cover, customer.version());
    }

    /** @throws BusinessException {@code CUSTOMER_NOT_FOUND} */
    public PageResponse<HistoryRow> history(UUID customerId, int page, int size) {
        if (!customers.existsById(customerId)) {
            throw new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND);
        }
        int bounded = Math.max(1, Math.min(size, 100));
        List<SalesOwnerChange> changes = history.of(customerId, Math.max(0, page), bounded);
        Set<UUID> people = new HashSet<>();
        changes.forEach(change -> {
            people.add(change.fromUserId());
            people.add(change.toUserId());
            people.add(change.changedBy());
        });
        Map<UUID, String> names = names(people);
        List<HistoryRow> rows = changes.stream().map(change -> new HistoryRow(change.id(), change.fromUserId(),
                names.get(change.fromUserId()), change.toUserId(), names.get(change.toUserId()), change.reason(),
                change.note(), change.changedBy(), names.get(change.changedBy()), change.changedAt(),
                change.batchId())).toList();
        return PageResponse.of(rows, Math.max(0, page), bounded, history.countOf(customerId));
    }

    /** The owner columns of each of these customers that exists. Two statements. */
    public Map<UUID, OwnerColumns> columns(Collection<UUID> customerIds) {
        Map<UUID, SalesOwner> owners = customers.salesOwners(customerIds);
        Set<UUID> people = owners.values().stream().map(SalesOwner::userId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, UserLabel> labels = people.isEmpty() ? new java.util.HashMap<>() : new java.util.HashMap<>(identity.userLabels(people));
        Map<UUID, OwnerColumns> columns = new HashMap<>();
        owners.forEach((customerId, owner) -> {
            UserLabel label = owner.userId() == null ? null : labels.get(owner.userId());
            columns.put(customerId, new OwnerColumns(owner.userId(), label == null ? null : label.displayName(),
                    owner.userId() == null ? null : label != null && label.active(), owner.state(), owner.since()));
        });
        return columns;
    }

    /** The active Sales agents by name, with their assignment setting and whether they are away today. */
    public List<SalesAgent> salesAgents() {
        List<StaffMember> agents = salesStaff.activeSalesAgents();
        List<UUID> ids = agents.stream().map(StaffMember::userId).toList();
        Map<UUID, SalesAssignmentSettings.Setting> settingOf = settings.of(ids);
        Map<UUID, StaffCovers.ActiveCover> away = covers.activeCovers(ids, today());
        return agents.stream().sorted(Comparator.comparing(StaffMember::displayName, String.CASE_INSENSITIVE_ORDER))
                .map(agent -> {
                    StaffCovers.ActiveCover cover = away.get(agent.userId());
                    SalesAssignmentSettings.Setting setting = settingOf.get(agent.userId());
                    return new SalesAgent(agent.userId(), agent.displayName(), setting != null && setting.paused(),
                            cover != null, cover == null ? null : cover.to());
                }).toList();
    }

    /** @throws BusinessException {@code USER_NOT_FOUND} */
    public HandoverSummary handover(UUID userId) {
        if (userId == null || identity.userLabels(List.of(userId)).isEmpty()) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return new HandoverSummary(userId, customers.idsOwnedBy(userId, SalesOwnerState.ASSIGNED).size(),
                leads.countOpenOf(userId), null);
    }

    /**
     * The portal account's own salesperson: the stored owner's name (null when it has none), whether that account works
     * now, and the cover's name while one stands in.
     *
     * @throws BusinessException {@code CUSTOMER_NOT_FOUND} for an account with no customer
     */
    public PortalOwner ofCustomerAccount(UUID portalUserId) {
        Customer customer = ownCustomer(portalUserId);
        OwnerView owner = owner(customer.id());
        boolean active = owner.active() && owner.state() == SalesOwnerState.ASSIGNED;
        return new PortalOwner(owner.salesOwnerName(), active, owner.cover() == null ? null : owner.cover().name());
    }

    /** The active salespeople the customer may name, its current owner left out (plan §10.5, D11). Names only. */
    public List<SalesOption> salesOptions(UUID portalUserId) {
        Customer customer = ownCustomer(portalUserId);
        UUID current = customer.salesOwner().userId();
        return salesStaff.activeSalesAgents().stream().filter(agent -> !agent.userId().equals(current))
                .sorted(Comparator.comparing(StaffMember::displayName, String.CASE_INSENSITIVE_ORDER))
                .map(agent -> new SalesOption(agent.userId(), agent.displayName())).toList();
    }

    /**
     * BR-S14 / case 16: the customers a new one may duplicate. Values that cannot be what is stored (a malformed tax
     * code or phone) are not compared; nothing given answers nothing.
     */
    public List<Duplicate> duplicates(String taxCode, String phone, String email, String name) {
        String code = normalised(taxCode, TaxCode::normalise);
        String number = normalised(phone, VietnamPhoneValidator::normalise);
        String mail = email == null || email.isBlank() ? null : email.strip().toLowerCase(Locale.ROOT);
        String company = name == null || name.isBlank() ? null : name.strip();
        if (code == null && number == null && mail == null && company == null) {
            return List.of();
        }
        List<CustomerDuplicates.Match> matches = duplicates.find(code, number, mail, company, MAX_DUPLICATES);
        Map<UUID, String> names = names(matches.stream().map(CustomerDuplicates.Match::salesOwnerId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return matches.stream().map(match -> new Duplicate(match.customerId(), match.fullName(),
                List.copyOf(match.matchedOn()),
                match.state() == SalesOwnerState.ASSIGNED ? names.get(match.salesOwnerId()) : null)).toList();
    }

    Customer ownCustomer(UUID portalUserId) {
        return customers.findByUserId(portalUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
    }

    private Map<UUID, String> names(Collection<UUID> userIds) {
        Set<UUID> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        return ids.isEmpty() ? new java.util.HashMap<>() : new java.util.HashMap<>(identity.displayNames(ids));
    }

    private static String normalised(String raw, java.util.function.UnaryOperator<String> normaliser) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return normaliser.apply(raw.strip());
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private LocalDate today() {
        return BusinessCalendar.date(clock.instant());
    }
}
