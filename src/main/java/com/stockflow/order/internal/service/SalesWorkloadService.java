package com.stockflow.order.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.customer.api.SalesOwnerBook;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.SalesStaff;
import com.stockflow.identity.api.StaffCovers;
import com.stockflow.identity.api.StaffMember;
import com.stockflow.order.internal.repository.SalesWorkloadFigures;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Sales Managers' workload board (sales-owner plan §10.4; {@code GET /api/v1/sales/workload}): one row per ACTIVE
 * salesperson (D42: only SALES_STAFF own customers), so a manager can share customers out fairly with figures.
 *
 * <p>Served by the order module because most figures are its documents; who owns whom, the settings and the covers are
 * read through {@code customer :: api} ({@link SalesOwnerBook}) and {@code identity :: api} ({@link SalesStaff},
 * {@link StaffCovers}). A constant number of statements whatever the number of salespeople and customers: the
 * agents, their covers and the covers' names, the customers owned, the month's intake, the settings, then two grouped
 * reads of the documents.</p>
 *
 * <ul>
 *   <li>{@code customers}: customers ASSIGNED to them now; {@code customersWithOpenOrders}: of those, how many have an
 *       order under way;</li>
 *   <li>{@code openQuotes}, {@code contractsAwaitingSignature}: of those customers' documents, whoever drew them up -
 *       the work is the owner's now;</li>
 *   <li>{@code revenue30d}: contracts signed in the last 30 days counted by the salesperson they recorded when drawn up
 *       (14 BR-26), so moving a customer does not move sales already made;</li>
 *   <li>{@code newCustomersThisMonth}: customers given to them since the first of the month (Vietnam time), what a
 *       {@code capacityPerMonth} is measured against.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class SalesWorkloadService {

    private static final Duration REVENUE_WINDOW = Duration.ofDays(30);

    private final SalesStaff salesStaff;
    private final StaffCovers covers;
    private final IdentityService identity;
    private final SalesOwnerBook book;
    private final SalesWorkloadFigures figures;
    private final Clock clock;

    SalesWorkloadService(SalesStaff salesStaff, StaffCovers covers, IdentityService identity, SalesOwnerBook book,
                         SalesWorkloadFigures figures, Clock clock) {
        this.salesStaff = salesStaff;
        this.covers = covers;
        this.identity = identity;
        this.book = book;
        this.figures = figures;
        this.clock = clock;
    }

    /** One salesperson's row. {@code coverName} and {@code coverUntil} are null unless {@code onLeave}. */
    public record Row(UUID userId, String name, boolean paused, Integer capacityPerMonth, boolean onLeave,
                      UUID coverUserId, String coverName, LocalDate coverUntil, int customers,
                      int customersWithOpenOrders, int openQuotes, int contractsAwaitingSignature, BigDecimal revenue30d,
                      int newCustomersThisMonth) {
    }

    /** Every active salesperson, by name. */
    public List<Row> workload() {
        List<StaffMember> agents = salesStaff.activeSalesAgents();
        if (agents.isEmpty()) {
            return List.of();
        }
        Instant now = clock.instant();
        LocalDate today = BusinessCalendar.date(now);
        List<UUID> ids = agents.stream().map(StaffMember::userId).toList();

        Map<UUID, StaffCovers.ActiveCover> coverOf = covers.activeCovers(ids, today);
        Map<UUID, String> coverNames = coverOf.isEmpty() ? Map.of()
                : identity.displayNames(coverOf.values().stream().map(StaffCovers.ActiveCover::coverUserId).toList());
        Map<UUID, List<UUID>> owned = new HashMap<>();
        for (SalesOwnerBook.OwnedCustomer customer : book.ownedBy(ids)) {
            owned.computeIfAbsent(customer.ownerId(), id -> new ArrayList<>()).add(customer.customerId());
        }
        Map<UUID, Integer> intake = book.assignedSince(ids,
                today.withDayOfMonth(1).atStartOfDay(BusinessCalendar.ZONE).toInstant());
        Map<UUID, SalesOwnerBook.AssignmentSetting> settings = book.assignmentSettings(ids);
        Map<UUID, SalesWorkloadFigures.CustomerFigures> documents = figures.byCustomer(
                owned.values().stream().flatMap(List::stream).toList(), now);
        Map<UUID, BigDecimal> revenue = figures.revenueSince(ids, now.minus(REVENUE_WINDOW));

        List<Row> rows = new ArrayList<>();
        for (StaffMember agent : agents) {
            UUID id = agent.userId();
            List<UUID> customers = owned.getOrDefault(id, List.of());
            int withOrders = 0;
            int quotes = 0;
            int contracts = 0;
            for (UUID customer : customers) {
                SalesWorkloadFigures.CustomerFigures f = documents.get(customer);
                if (f != null) {
                    withOrders += f.openOrders() ? 1 : 0;
                    quotes += f.openQuotes();
                    contracts += f.contractsAwaitingSignature();
                }
            }
            SalesOwnerBook.AssignmentSetting setting = settings.get(id);
            StaffCovers.ActiveCover cover = coverOf.get(id);
            rows.add(new Row(id, agent.displayName(), setting != null && setting.paused(),
                    setting == null ? null : setting.capacityPerMonth(), cover != null,
                    cover == null ? null : cover.coverUserId(),
                    cover == null ? null : coverNames.get(cover.coverUserId()), cover == null ? null : cover.to(),
                    customers.size(), withOrders, quotes, contracts, revenue.getOrDefault(id, BigDecimal.ZERO),
                    intake.getOrDefault(id, 0)));
        }
        rows.sort(Comparator.comparing(Row::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(Row::userId));
        return rows;
    }
}
