package com.stockflow.production.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.AssignedWarehouses;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.SampleStatus;
import com.stockflow.production.internal.repository.ProductionDashboardQueries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * {@link ProductionDashboard}: the counts from aggregate queries, the two lists through the production order
 * and sample request lists the rest of the module serves, so the dashboard shows the same rows and the same
 * warehouse scope as the screens it links to.
 */
@Service
@Transactional(readOnly = true)
class ProductionDashboardService implements ProductionDashboard {

    /** "Overdue" and the period's days are the business's calendar, not UTC. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final List<ProductionOrder.Status> OPEN = List.of(ProductionOrder.Status.PENDING_PREPRESS,
            ProductionOrder.Status.READY, ProductionOrder.Status.MATERIAL_ISSUED, ProductionOrder.Status.PRINTING,
            ProductionOrder.Status.SUBCONTRACTED, ProductionOrder.Status.QC, ProductionOrder.Status.ON_HOLD);

    private final ProductionDashboardQueries queries;
    private final ProductionOrders orders;
    private final SampleRequests samples;
    private final Clock clock;

    ProductionDashboardService(ProductionDashboardQueries queries, ProductionOrders orders, SampleRequests samples,
                               Clock clock) {
        this.queries = queries;
        this.orders = orders;
        this.samples = samples;
        this.clock = clock;
    }

    @Override
    public Summary summary(CurrentUser user, LocalDate from, LocalDate to) {
        ScopeGuards.requireBackOffice(user);
        if (from != null && to != null && to.isBefore(from)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The period ends before it starts");
        }
        Collection<UUID> warehouses = WarehouseScope.restriction().map(AssignedWarehouses::ids).orElse(null);
        LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        Instant start = from == null ? null : from.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant end = to == null ? null : to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        if (warehouses != null && warehouses.isEmpty()) {
            return new Summary(java.util.Map.of(), 0, 0, 0, null, 0, today, CREATED_IN_PERIOD, from, to);
        }
        long[] printedAndScrap = queries.printedAndScrap(warehouses, start, end);
        BigDecimal rate = printedAndScrap[0] == 0 ? null
                : BigDecimal.valueOf(printedAndScrap[1]).divide(BigDecimal.valueOf(printedAndScrap[0]), 4,
                RoundingMode.HALF_UP);
        return new Summary(queries.openByStatus(warehouses), queries.overdue(warehouses, today), printedAndScrap[0],
                printedAndScrap[1], rate, queries.samplesAwaitingApproval(warehouses), today, CREATED_IN_PERIOD, from,
                to);
    }

    @Override
    public PageResponse<ProductionOrders.Row> overdue(CurrentUser user, int page, int size) {
        LocalDate yesterday = LocalDate.now(clock.withZone(BUSINESS_ZONE)).minusDays(1);
        return orders.list(user, new ProductionOrders.Criteria(null, OPEN, null, null, yesterday, null, null, null,
                page, size, "dueDate,asc;createdAt,asc"));
    }

    @Override
    public PageResponse<SampleRequests.Row> samplesAwaitingApproval(CurrentUser user, int page, int size) {
        return samples.list(user, new SampleRequests.Criteria(null, List.of(SampleStatus.SENT_TO_CUSTOMER), null, null,
                page, size, "createdAt,asc"));
    }
}
