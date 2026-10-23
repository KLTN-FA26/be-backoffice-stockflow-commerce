package com.stockflow.reporting.internal.service;

import com.stockflow.order.api.ListOrdersQuery;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.reporting.internal.domain.BusinessDay;
import com.stockflow.reporting.internal.repository.SalesRollupQueries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Fills the rollups with history once, on the first start after deploy: the orders placed before the listeners
 * existed raised events nobody counted, so the dashboard would otherwise start from zero.
 *
 * <p>It runs when there are orders older than anything the ledger knows - which includes the very first start, when the
 * ledger is empty - and rebuilds from the first order's day up to the ledger's earliest day (or up to today when the
 * ledger is empty). Once done, the next start finds nothing older and does nothing. The rebuild itself is idempotent
 * and serialised against the listeners, so two instances starting together only repeat work.</p>
 *
 * <p>Off with {@code stockflow.reporting.backfill-on-startup=false} (the test profile). A failure is logged and
 * leaves the app running: {@code POST /api/v1/reports/rebuild} does the same job by hand.</p>
 */
@Component
@ConditionalOnProperty(prefix = "stockflow.reporting", name = "backfill-on-startup", havingValue = "true",
        matchIfMissing = true)
class SalesRollupBackfill {

    private static final Logger LOG = LoggerFactory.getLogger(SalesRollupBackfill.class);
    private static final List<OrderStatus> COUNTABLE = Stream.of(OrderStatus.values())
            .filter(status -> status != OrderStatus.DRAFT).toList();

    private final OrderService orders;
    private final SalesRollupQueries queries;
    private final SalesRollupRebuilder rebuilder;
    private final Clock clock;

    SalesRollupBackfill(OrderService orders, SalesRollupQueries queries, SalesRollupRebuilder rebuilder, Clock clock) {
        this.orders = orders;
        this.queries = queries;
        this.rebuilder = rebuilder;
        this.clock = clock;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            backfill();
        } catch (RuntimeException e) {
            LOG.error("Sales rollup backfill failed; POST /api/v1/reports/rebuild can run it by hand", e);
        }
    }

    /** @return the days rebuilt; empty when nothing needed doing */
    Optional<LocalDate[]> backfill() {
        Optional<LocalDate> firstOrderDay = orders.list(
                        new ListOrdersQuery(0, 1, null, COUNTABLE, null, null, null, "placedAt,asc"))
                .items().stream().findFirst().map(order -> BusinessDay.of(order.placedAt()));
        if (firstOrderDay.isEmpty()) {
            LOG.info("Sales rollup backfill: no orders yet, nothing to do");
            return Optional.empty();
        }
        Optional<LocalDate> earliestRolledUp = queries.earliestRolledUpDay();
        if (earliestRolledUp.isPresent() && !firstOrderDay.get().isBefore(earliestRolledUp.get())) {
            LOG.info("Sales rollup backfill: history already rolled up (first order {}, ledger from {})",
                    firstOrderDay.get(), earliestRolledUp.get());
            return Optional.empty();
        }
        LocalDate from = firstOrderDay.get();
        LocalDate to = earliestRolledUp.orElse(BusinessDay.today(clock));
        LOG.info("Sales rollup backfill: rebuilding {}..{} (ledger {})", from, to,
                earliestRolledUp.map(day -> "starts " + day).orElse("is empty"));
        rebuilder.rebuild(from, to);
        return Optional.of(new LocalDate[] {from, to});
    }
}
