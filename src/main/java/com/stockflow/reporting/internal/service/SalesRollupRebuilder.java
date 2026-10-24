package com.stockflow.reporting.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.order.api.ListOrdersQuery;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.reporting.api.ReportingService.RebuildResult;
import com.stockflow.reporting.internal.domain.OrderContribution;
import com.stockflow.reporting.internal.domain.SalesRollupLedger;
import com.stockflow.reporting.internal.domain.SalesRollups;
import com.stockflow.reporting.internal.repository.SalesRollupLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Recomputes the rollups of a range of days from the orders themselves.
 *
 * <p>One transaction per day, holding the exclusive rollup lock: the day's rows (both summaries and the ledger) are
 * deleted and every order placed on it is replayed through {@link SalesRollups} - placed, and cancelled too when it
 * is - which is the very code the listeners run, so the result is the one the listeners would have produced. Orders are
 * read a page at a time, ordered by order number so a page boundary cannot move under the loop.</p>
 *
 * <p>Idempotent: run twice, the second run deletes and recomputes the same figures. An event for one of these orders
 * delivered afterwards finds it recorded in the ledger and changes nothing.</p>
 */
@Service
class SalesRollupRebuilder {

    private static final Logger LOG = LoggerFactory.getLogger(SalesRollupRebuilder.class);
    static final int PAGE_SIZE = 100;
    /** Every status but DRAFT: a draft is not a sale. */
    private static final List<OrderStatus> COUNTABLE = Stream.of(OrderStatus.values())
            .filter(status -> status != OrderStatus.DRAFT).toList();

    private final OrderService orders;
    private final SalesRollupLedger ledger;
    private final SalesRollups rollups;
    private final SalesRollupLock lock;
    private final TransactionTemplate tx;

    SalesRollupRebuilder(OrderService orders, SalesRollupLedger ledger, SalesRollupLock lock,
                         PlatformTransactionManager transactions) {
        this.orders = orders;
        this.ledger = ledger;
        this.rollups = new SalesRollups(ledger);
        this.lock = lock;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Both days inclusive. Not limited in length: the caller that takes user input has already limited it. */
    RebuildResult rebuild(LocalDate from, LocalDate to) {
        long seen = 0;
        long counted = 0;
        int days = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            LocalDate current = day;
            long[] result = tx.execute(status -> rebuildDay(current));
            seen += result[0];
            counted += result[1];
            days++;
        }
        LOG.info("Sales rollup rebuilt for {}..{}: {} days, {} orders seen, {} counted", from, to, days, seen, counted);
        return new RebuildResult(from, to, days, seen, counted);
    }

    /** @return {orders seen, orders counted} */
    private long[] rebuildDay(LocalDate day) {
        lock.exclusive();
        ledger.clearDay(day);
        long seen = 0;
        long counted = 0;
        int page = 0;
        PageResponse<OrderSummary> rows;
        do {
            rows = orders.list(new ListOrdersQuery(page++, PAGE_SIZE, null, COUNTABLE, null, day, day,
                    "orderNumber,asc"));
            for (OrderSummary row : rows.items()) {
                OrderSummary order = orders.findById(row.orderId()).orElse(null);
                Optional<OrderContribution> contribution = Optional.ofNullable(order)
                        .flatMap(SalesRollupService::contributionOf);
                if (contribution.isEmpty()) {
                    continue;
                }
                seen++;
                rollups.place(contribution.get());
                if (order.status() == OrderStatus.CANCELLED) {
                    rollups.cancel(order.orderId(), contribution::get);
                } else {
                    counted++;
                }
            }
        } while (rows.hasNext());
        return new long[] {seen, counted};
    }
}
