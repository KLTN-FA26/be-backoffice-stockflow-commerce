package com.stockflow.production.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.production.api.ProductionOrderDetail;
import com.stockflow.production.api.ProductionOrderQueries;
import com.stockflow.production.api.ProductionOrderView;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionOrderRepository;
import com.stockflow.production.internal.repository.SampleRequestSearch;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link ProductionOrderQueries} over {@link ProductionOrders}, the read side of the production order screens: the
 * back-office check and the warehouse rule are applied there, once, for the screens and for this alike.
 *
 * <p>One order by number reads the aggregate as well, for what it states directly: the hold note and the status a hold
 * returns to (the detail screen shows both only inside the status history) and the blanks still needed, by the
 * aggregate's own rule (BR-PRD-03) rather than a copy of it. It is read after {@code get} has checked the caller's
 * warehouse, so it shows nothing the detail screen would not.</p>
 *
 * <p>A view names its warehouse, sales order and sample request by number, so each is looked up once per distinct id
 * in a call, through the owning module's {@code api}. The worst case is a list of
 * {@value ProductionOrderQueries#MAX_ORDERS} production orders for as many sales orders: one order read each, since
 * {@code order :: api} has no lookup of many order numbers at once.</p>
 */
@Service
@Transactional(readOnly = true)
class ProductionOrderQueriesImpl implements ProductionOrderQueries {

    /** "Overdue" is counted on the business's calendar, as on the workshop dashboard. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** The print shop's queue order, the dashboard's overdue list: the earliest due date first, then the oldest. */
    private static final String QUEUE_ORDER = "dueDate,asc;createdAt,asc";

    /**
     * Rows of the number search to look through for the exact number. The search matches part of a number, and every
     * number of a day has the same length, so the exact one is among the first few.
     */
    private static final int NUMBER_CANDIDATES = 20;

    private static final List<ProductionOrder.Status> OPEN = OPEN_STATUSES.stream()
            .map(ProductionOrder.Status::valueOf).toList();

    private final ProductionOrders orders;
    private final ProductionOrderRepository aggregates;
    private final SampleRequestSearch samples;
    private final OrderService salesOrders;
    private final WarehouseService warehouses;
    private final Clock clock;

    ProductionOrderQueriesImpl(ProductionOrders orders, ProductionOrderRepository aggregates,
                               SampleRequestSearch samples, OrderService salesOrders, WarehouseService warehouses,
                               Clock clock) {
        // The api's vocabulary is a copy of the domain's, so another module can name a status without importing it. A
        // status added to one and not the other would be refused or shown wrongly; it stops the start instead.
        if (!Set.copyOf(STATUSES).equals(names(ProductionOrder.Status.values()))
                || !Set.copyOf(HOLD_REASONS).equals(names(ProductionOrder.HoldReason.values()))) {
            throw new IllegalStateException("ProductionOrderQueries.STATUSES or HOLD_REASONS no longer match "
                    + "ProductionOrder.Status and ProductionOrder.HoldReason");
        }
        this.orders = orders;
        this.aggregates = aggregates;
        this.samples = samples;
        this.salesOrders = salesOrders;
        this.warehouses = warehouses;
        this.clock = clock;
    }

    @Override
    public Optional<ProductionOrderDetail> byNumber(String orderNumber, CurrentUser user) {
        String number = orderNumber == null ? "" : orderNumber.strip();
        if (number.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A production order number is required");
        }
        Optional<ProductionOrders.Row> match = orders.list(user, new ProductionOrders.Criteria(null, null, null, null,
                        null, null, null, number, 0, NUMBER_CANDIDATES, null)).items().stream()
                .filter(row -> row.orderNumber().equalsIgnoreCase(number))
                .findFirst();
        if (match.isEmpty()) {
            return Optional.empty();
        }
        ProductionOrders.Detail detail = orders.get(user, match.get().id());
        ProductionOrder order = aggregates.findById(match.get().id())
                .orElseThrow(() -> new IllegalStateException("Production order " + number + " vanished while read"));
        // The print shop asks for blanks only for its own open work: a subcontractor's blanks leave with the order (or
        // are the subcontractor's to buy), and a finished or cancelled order needs none, whatever the sum says.
        Integer blanksStillNeeded = order.mode() == ProductionOrder.Mode.SUBCONTRACTED || !OPEN.contains(order.status())
                ? null : order.outstandingBlanks();
        return Optional.of(new ProductionOrderDetail(view(detail.summary(), new Names(), today()), detail.origin(),
                detail.issuedQuantity(), detail.printedQuantity(), detail.scrapQuantity(),
                blanksStillNeeded, hold(order, detail.history()), detail.prepressCheckedAt(),
                detail.completedAt(), detail.cancelledAt(), detail.cancellationReason(),
                detail.history().stream().map(change -> new ProductionOrderDetail.StatusChange(change.fromStatus(),
                        change.toStatus(), change.reason(), change.changedAt())).toList()));
    }

    @Override
    public Found find(Criteria criteria, CurrentUser user) {
        if (criteria.limit() < 1 || criteria.limit() > MAX_ORDERS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The limit must be between 1 and " + MAX_ORDERS);
        }
        List<ProductionOrder.Status> statuses = criteria.statuses().stream().map(ProductionOrderQueriesImpl::status)
                .toList();
        LocalDate today = today();
        LocalDate dueTo = null;
        if (criteria.overdueOnly()) {
            // Only open work can be late: a COMPLETED order is not overdue however late it finished.
            statuses = statuses.isEmpty() ? OPEN : statuses.stream().filter(OPEN::contains).toList();
            if (statuses.isEmpty()) {
                return new Found(List.of(), 0);
            }
            dueTo = today.minusDays(1);
        }
        PageResponse<ProductionOrders.Row> page = orders.list(user, new ProductionOrders.Criteria(null, statuses,
                null, null, dueTo, null, criteria.salesOrderId(), null, 0, criteria.limit(), QUEUE_ORDER));
        Names names = new Names();
        return new Found(page.items().stream().map(row -> view(row, names, today)).toList(), page.totalElements());
    }

    private ProductionOrderView view(ProductionOrders.Row row, Names names, LocalDate today) {
        SampleRequestSearch.Row sample = row.sampleRequestId() == null ? null : names.sample(row.sampleRequestId());
        return new ProductionOrderView(row.orderNumber(), row.type().name(), row.mode(), row.status().name(),
                row.holdReason(), names.warehouse(row.warehouseId()), row.blankSku(), row.plannedQuantity(),
                row.goodQuantity(), row.dueDate(),
                OPEN.contains(row.status()) && row.dueDate() != null && row.dueDate().isBefore(today),
                row.salesOrderId() == null ? null : names.salesOrder(row.salesOrderId()),
                sample == null ? null : sample.requestNumber(), sample == null ? null : sample.round(),
                row.createdAt());
    }

    /**
     * The hold as the order carries it. {@code since} is the last time work was stopped: a change into ON_HOLD from
     * anything else, because a subcontractor's goods arriving during a hold is recorded ON_HOLD to ON_HOLD.
     */
    private static ProductionOrderDetail.Hold hold(ProductionOrder order, List<ProductionOrders.StatusChange> history) {
        if (order.status() != ProductionOrder.Status.ON_HOLD) {
            return null;
        }
        String held = ProductionOrder.Status.ON_HOLD.name();
        Instant since = history.stream()
                .filter(change -> held.equals(change.toStatus()) && !held.equals(change.fromStatus()))
                .map(ProductionOrders.StatusChange::changedAt)
                .reduce((earlier, later) -> later)
                .orElse(null);
        return new ProductionOrderDetail.Hold(order.holdReason() == null ? null : order.holdReason().name(),
                order.holdNote(), order.statusBeforeHold() == null ? null : order.statusBeforeHold().name(), since);
    }

    private static ProductionOrder.Status status(String name) {
        if (name == null || !STATUSES.contains(name)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A production order status is one of " + STATUSES + ", not " + name);
        }
        return ProductionOrder.Status.valueOf(name);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(BUSINESS_ZONE));
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toSet());
    }

    /** The numbers of one call's rows, each looked up once. */
    private final class Names {

        private final Map<UUID, String> warehouseCodes = new HashMap<>();
        private final Map<UUID, String> orderNumbers = new HashMap<>();
        private final Map<UUID, Optional<SampleRequestSearch.Row>> sampleRows = new HashMap<>();

        String warehouse(UUID id) {
            return warehouseCodes.computeIfAbsent(id, key -> warehouses.findWarehouse(key)
                    .map(WarehouseView::prefix).orElse(null));
        }

        String salesOrder(UUID id) {
            return orderNumbers.computeIfAbsent(id, key -> salesOrders.findById(key)
                    .map(OrderSummary::orderNumber).orElse(null));
        }

        SampleRequestSearch.Row sample(UUID id) {
            return sampleRows.computeIfAbsent(id, samples::findRow).orElse(null);
        }
    }
}
