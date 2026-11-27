package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.PermissionDeniedException;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;

/**
 * The work queue (contract 3.1): what waits on the caller today, section by section, for the dashboard widget "Việc
 * cần xử lý" every role sees, and - narrowed to one warehouse - for the warehouse manager's shift brief, who otherwise
 * opens five screens to learn what ships, arrives and is stuck today. The same sections go into the morning digest
 * ({@link WorkQueueDigestJob}).
 *
 * <p><b>A section is a screen's list, counted.</b> Each is one read of {@link WorkQueueReads}, which carries the guard
 * of the screen it counts; a section appears only when the caller holds that permission, read from that very
 * annotation (as {@link ToolAvailability} reads a tool's), so a grant changed in the role matrix shows here at once and
 * a user is never shown a count of what their screens hide. A warehouse-bound user's sections are their warehouses',
 * by each module's own rule. It is built like the "today" card ({@link TodayCard}): fixed reads, no model, one
 * failure is one section - but it reads the modules' lists directly rather than through the chat's tools, because a
 * section needs a count, an href and five rows of every queue, most of which no tool lists.</p>
 *
 * <p><b>A queue someone else works is shown to who works it.</b> Most lists are read under a READ guard that people
 * who only follow them also hold: Sales Staff read the price-approval and credit-review lists, the Accountant reads
 * orders, stock adjustments and goods receipts. A section whose work is one role's step also needs the permission that
 * acts on it: {@code sales-quote-price-approvals:APPROVE}, {@code sales-order-credit-holds:APPROVE},
 * {@code inventory-stock-adjustments:APPROVE}, {@code sales-order-releases:UPDATE} (batches to ship today),
 * {@code warehouse-putaway-tasks:UPDATE} (receipts to put away), {@code production-orders:UPDATE} (late production
 * orders). A WARN "to do" on the widget of someone who cannot do it is a call to act they cannot answer, and it teaches
 * them to ignore the widget (rehearsal 27/11: the Accountant's queue showed seven batches to ship and two adjustments
 * to approve). The queues several roles work (held orders, instalments due, payment and stock waits) need no more than
 * their list's guard.</p>
 *
 * <p><b>Narrowed to a warehouse</b> ({@code warehouse}, its code prefix): the sections that belong to a warehouse are
 * read for that one only, and the others (money, quotes, credit) are left out rather than shown company-wide under a
 * warehouse's name. A warehouse the caller is not assigned to is not found, as a screen does not reveal it.</p>
 *
 * <p><b>Severity</b> says how loudly the widget shows a section: INFO when nothing waits or the queue is informational,
 * WARN when someone has to act, CRITICAL when a section crosses its line (a batch due ends today, a production order
 * is past its need-by day, an e-invoice is past its deadline to be issued).</p>
 */
@Service
public class WorkQueue {

    private static final Logger log = LoggerFactory.getLogger(WorkQueue.class);

    /** How loudly a section is shown. */
    public enum Severity { INFO, WARN, CRITICAL }

    /**
     * The sections, in the widget's order.
     *
     * @param warehouseBound it belongs to a warehouse, so it is read for the one asked for; the others are left out
     *                       when a warehouse is asked for
     * @param base           its severity when something waits and nothing is urgent
     * @param acting         the permission that acts on the queue, needed besides the read's guard; null when reading
     *                       the list is the work (class javadoc)
     */
    private record Definition(String key, String method, boolean warehouseBound, Severity base,
                              BiFunction<WorkQueueReads, WorkQueueReads.Narrowing, WorkQueueReads.Read> read,
                              String href, PermissionCode acting) {

        Definition(String key, String method, boolean warehouseBound, Severity base,
                   BiFunction<WorkQueueReads, WorkQueueReads.Narrowing, WorkQueueReads.Read> read, String href) {
            this(key, method, warehouseBound, base, read, href, null);
        }
    }

    /** {@code OrderResources.RELEASES}: releasing an order's batch to the warehouse, the warehouse manager's step. */
    private static final String RELEASES = "sales-order-releases";
    /** {@code InventoryResources.PUTAWAY_TASKS}: confirming a carton put away. */
    private static final String PUTAWAY_TASKS = "warehouse-putaway-tasks";

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition("heldOrders", "heldOrders", true, Severity.WARN, WorkQueueReads::heldOrders,
                    WorkLinks.orders("ON_HOLD")),
            new Definition("awaitingPayment", "awaitingPayment", true, Severity.INFO, WorkQueueReads::awaitingPayment,
                    WorkLinks.orders("PENDING_PAYMENT")),
            new Definition("awaitingStock", "awaitingStock", true, Severity.INFO, WorkQueueReads::awaitingStock,
                    WorkLinks.orders("AWAITING_STOCK")),
            new Definition("batchesDueToday", "batchesDueToday", true, Severity.WARN,
                    WorkQueueReads::batchesDueToday, null, PermissionCode.of(RELEASES, Action.UPDATE)),
            new Definition("receiptsToPutAway", "receiptsToPutAway", true, Severity.INFO,
                    WorkQueueReads::receiptsToPutAway, null, PermissionCode.of(PUTAWAY_TASKS, Action.UPDATE)),
            new Definition("pendingAdjustments", "pendingAdjustments", true, Severity.WARN,
                    WorkQueueReads::pendingAdjustments, WorkLinks.pendingAdjustments(),
                    PermissionCode.of(ToolPermissions.STOCK_ADJUSTMENTS, Action.APPROVE)),
            new Definition("bankTransactionsToReview", "bankTransactionsToReview", false, Severity.WARN,
                    WorkQueueReads::bankTransactionsToReview, WorkLinks.bankTransactionsToReview()),
            new Definition("overdueInstallments", "overdueInstallments", false, Severity.WARN,
                    WorkQueueReads::overdueInstallments, WorkLinks.overdueInstallments()),
            new Definition("creditReviewsPending", "creditReviewsPending", false, Severity.WARN,
                    WorkQueueReads::creditReviewsPending, WorkLinks.creditReviews(),
                    PermissionCode.of(ToolPermissions.CREDIT_HOLDS, Action.APPROVE)),
            new Definition("quotesAwaitingPriceApproval", "quotesAwaitingPriceApproval", false, Severity.WARN,
                    WorkQueueReads::quotesAwaitingPriceApproval, WorkLinks.quotesAwaitingPriceApproval(),
                    PermissionCode.of(ToolPermissions.QUOTE_PRICE_APPROVALS, Action.APPROVE)),
            new Definition("productionOrdersLate", "productionOrdersLate", true, Severity.WARN,
                    // No screen lists this set (the schedule's delay alerts are another one): no link for the
                    // section, and each item opens its production order.
                    WorkQueueReads::productionOrdersLate, null,
                    PermissionCode.of(ToolPermissions.PRODUCTION_ORDERS, Action.UPDATE)),
            new Definition("einvoicesToIssue", "einvoicesToIssue", false, Severity.INFO,
                    WorkQueueReads::einvoicesToIssue, WorkLinks.einvoicesToIssue()));

    private final WorkQueueReads reads;
    private final List<PermissionCode> guards;
    private final WarehouseService warehouses;
    private final Messages messages;
    private final Clock clock;

    WorkQueue(WorkQueueReads reads, WarehouseService warehouses, Messages messages, Clock clock) {
        this.reads = reads;
        this.guards = DEFINITIONS.stream().map(definition -> guardOf(reads, definition.method())).toList();
        this.warehouses = warehouses;
        this.messages = messages;
        this.clock = clock;
    }

    /**
     * The queue of {@code user}, read now, every section whose screen they may read.
     *
     * @param warehousePrefix one warehouse's code prefix, any case; null or blank for every warehouse the user sees
     * @throws BusinessException {@code WAREHOUSE_NOT_FOUND} for a prefix that is no active warehouse, or one of a
     *         warehouse the user is not assigned to
     */
    public Queue of(CurrentUser user, String warehousePrefix) {
        Instant now = clock.instant();
        WarehouseView warehouse = warehouseOf(user, warehousePrefix);
        WorkQueueReads.Narrowing narrowing = new WorkQueueReads.Narrowing(user, BusinessDate.of(now), now,
                warehouse == null ? null : warehouse.id(), warehouse == null ? null : warehouse.prefix());
        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < DEFINITIONS.size(); i++) {
            Definition definition = DEFINITIONS.get(i);
            PermissionCode guard = guards.get(i);
            if ((warehouse != null && !definition.warehouseBound()) || !user.can(guard.resource(), guard.action())
                    || (definition.acting() != null
                    && !user.can(definition.acting().resource(), definition.acting().action()))) {
                continue;
            }
            Section section = read(definition, narrowing);
            if (section != null) {
                sections.add(section);
            }
        }
        return new Queue(now, warehouse == null ? null : warehouse.prefix(), List.copyOf(sections));
    }

    private Section read(Definition definition, WorkQueueReads.Narrowing narrowing) {
        String title = messages.get("assistant.workQueue." + definition.key(), definition.key());
        try {
            WorkQueueReads.Read read = definition.read().apply(reads, narrowing);
            Severity severity = read.count() == 0 ? Severity.INFO : read.urgent() ? Severity.CRITICAL
                    : definition.base();
            return new Section(definition.key(), title, read.count(), severity, hrefOf(definition, narrowing),
                    read.items(), false);
        } catch (PermissionDeniedException refused) {
            // The guard holds more than the permission (a back-office check): the section is not the user's.
            return null;
        } catch (RuntimeException failed) {
            log.error("Assistant work queue: section {} could not be read for user {}", definition.key(),
                    narrowing.user().userId(), failed);
            return new Section(definition.key(), title, null, Severity.INFO, hrefOf(definition, narrowing), List.of(),
                    true);
        }
    }

    /** The screen a section opens, with the warehouse asked for where the screen filters by it. */
    private static String hrefOf(Definition definition, WorkQueueReads.Narrowing narrowing) {
        return switch (definition.key()) {
            case "receiptsToPutAway" -> WorkLinks.goodsReceipts("IN_PUTAWAY", narrowing.warehouseId());
            // No screen lists batches across orders; each item opens its order, whose page shows the schedule.
            case "batchesDueToday" -> WorkLinks.ordersList();
            default -> definition.href();
        };
    }

    private WarehouseView warehouseOf(CurrentUser user, String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return null;
        }
        String wanted = prefix.strip().toUpperCase(Locale.ROOT);
        WarehouseView warehouse = warehouses.activeWarehouses().stream()
                .filter(view -> view.prefix() != null && view.prefix().equalsIgnoreCase(wanted)).findFirst()
                .orElse(null);
        boolean outside = warehouse != null && ToolAvailability.limitedToWarehouses(user)
                && (user.warehouseIds() == null || !user.warehouseIds().contains(warehouse.id()));
        if (warehouse == null || outside) {
            // Another warehouse's is not revealed to exist: not found, as for one that does not.
            throw new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND, "No warehouse " + wanted);
        }
        return warehouse;
    }

    private static PermissionCode guardOf(WorkQueueReads reads, String method) {
        Method read;
        try {
            read = AopUtils.getTargetClass(reads).getMethod(method, WorkQueueReads.Narrowing.class);
        } catch (NoSuchMethodException missing) {
            throw new IllegalStateException("The work queue names a read WorkQueueReads." + method
                    + " that does not exist", missing);
        }
        RequiresPermission guard = AnnotatedElementUtils.findMergedAnnotation(read, RequiresPermission.class);
        if (guard == null) {
            throw new IllegalStateException("WorkQueueReads." + method + " carries no @RequiresPermission: its section "
                    + "would be read for anyone, with no data scope. Give it the guard of the screen it counts.");
        }
        return PermissionCode.of(guard.resource(), guard.action());
    }

    /**
     * @param generatedAt when it was read
     * @param warehouse   the warehouse it was narrowed to, by its prefix; null for every warehouse the user sees
     */
    public record Queue(Instant generatedAt, String warehouse, List<Section> sections) {
    }

    /**
     * @param title  in the user's language
     * @param count  every item waiting; null when the section could not be read ({@code failed})
     * @param href   the screen listing them all
     * @param items  the first few, each linking to its own screen
     * @param failed the read failed, the error is logged; the other sections are not affected
     */
    public record Section(String key, String title, Long count, Severity severity, String href,
                          List<WorkQueueReads.Item> items, boolean failed) {
    }
}
