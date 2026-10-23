package com.stockflow.reporting.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.ListOrdersQuery;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.product.api.ProductService;
import com.stockflow.reporting.api.ReportingService;
import com.stockflow.reporting.internal.domain.BusinessDay;
import com.stockflow.reporting.internal.domain.ReportPeriod;
import com.stockflow.reporting.internal.repository.SalesRollupQueries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The only implementation of {@link ReportingService}.
 *
 * <p>Package-private class, public interface: Spring injects it by the interface, so nobody can bypass the port by
 * autowiring the concrete class. The reads are aggregate queries over the two rollup tables
 * ({@link SalesRollupQueries}); only the status counts and the product names go through another module's {@code api}.</p>
 */
@Service
@Transactional(readOnly = true)
class ReportingServiceImpl implements ReportingService {

    static final int DEFAULT_TOP_LIMIT = 10;
    static final int MAX_TOP_LIMIT = 100;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final SalesRollupQueries queries;
    private final SalesRollupRebuilder rebuilder;
    private final OrderService orders;
    private final ProductService products;
    private final Clock clock;

    ReportingServiceImpl(SalesRollupQueries queries, SalesRollupRebuilder rebuilder, OrderService orders,
                         ProductService products, Clock clock) {
        this.queries = queries;
        this.rebuilder = rebuilder;
        this.orders = orders;
        this.products = products;
        this.clock = clock;
    }

    @Override
    public List<SalesDay> salesDaily(LocalDate from, LocalDate to) {
        ReportPeriod period = period(from, to);
        Map<LocalDate, SalesRollupQueries.DayRow> rows = new HashMap<>();
        queries.days(period.from(), period.to(), SalesRollupService.CURRENCY)
                .forEach(row -> rows.put(row.day(), row));
        List<SalesDay> days = new ArrayList<>();
        for (LocalDate day = period.from(); !day.isAfter(period.to()); day = day.plusDays(1)) {
            SalesRollupQueries.DayRow row = rows.get(day);
            days.add(new SalesDay(day, row == null ? 0 : row.ordersCount(),
                    vnd(row == null ? BigDecimal.ZERO : row.revenue()), SalesRollupService.CURRENCY));
        }
        return days;
    }

    @Override
    public List<TopProduct> topProducts(LocalDate from, LocalDate to, Integer limit) {
        ReportPeriod period = period(from, to);
        int top = limit == null ? DEFAULT_TOP_LIMIT : limit;
        if (top < 1 || top > MAX_TOP_LIMIT) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "limit must be between 1 and " + MAX_TOP_LIMIT,
                    List.of(new FieldError("limit", "must be between 1 and " + MAX_TOP_LIMIT, "OutOfRange")));
        }
        return queries.topProducts(period.from(), period.to(), top).stream()
                .map(row -> new TopProduct(row.sku(), products.nameForSku(row.sku()).orElse(null),
                        row.quantitySold(), vnd(row.revenue())))
                .toList();
    }

    @Override
    public List<OrderStatusCount> orderStatusCounts() {
        return Stream.of(OrderStatus.values())
                .map(status -> new OrderStatusCount(status.name(), orders.list(
                        new ListOrdersQuery(0, 1, null, List.of(status), null, null, null, null)).totalElements()))
                .toList();
    }

    @Override
    public SalesSummary summary(LocalDate from, LocalDate to) {
        ReportPeriod period = period(from, to);
        ReportPeriod before = period.previous();
        Totals current = totals(period);
        Totals previous = totals(before);
        return new SalesSummary(period.from(), period.to(), before.from(), before.to(), SalesRollupService.CURRENCY,
                current, previous,
                change(BigDecimal.valueOf(current.ordersCount()), BigDecimal.valueOf(previous.ordersCount())),
                change(current.revenue(), previous.revenue()),
                change(current.averageOrderValue(), previous.averageOrderValue()));
    }

    /** Not read-only: it writes. The rebuild opens its own transaction per day. */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RebuildResult rebuild(LocalDate from, LocalDate to) {
        ReportPeriod period = period(from, to);
        return rebuilder.rebuild(period.from(), period.to());
    }

    private Totals totals(ReportPeriod period) {
        SalesRollupQueries.Totals totals = queries.totals(period.from(), period.to(), SalesRollupService.CURRENCY);
        BigDecimal revenue = vnd(totals.revenue());
        BigDecimal average = totals.ordersCount() == 0 ? vnd(BigDecimal.ZERO)
                : revenue.divide(BigDecimal.valueOf(totals.ordersCount()), 0, RoundingMode.HALF_UP);
        return new Totals(totals.ordersCount(), revenue, average);
    }

    /** (current - previous) / previous in percent, one decimal; null when there is no previous to compare with. */
    private static BigDecimal change(BigDecimal current, BigDecimal previous) {
        if (previous.signum() == 0) {
            return null;
        }
        return current.subtract(previous).multiply(HUNDRED).divide(previous, 1, RoundingMode.HALF_UP);
    }

    private ReportPeriod period(LocalDate from, LocalDate to) {
        return ReportPeriod.of(from, to, BusinessDay.today(clock));
    }

    /** VND has no minor unit: whole dong, whatever scale the column carries. */
    private static BigDecimal vnd(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP);
    }
}
