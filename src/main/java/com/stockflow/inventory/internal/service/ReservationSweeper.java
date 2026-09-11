package com.stockflow.inventory.internal.service;

import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Releases reservations that outlived their expiry.
 *
 * <p>Without this, every abandoned checkout permanently removes stock from sale. The customer
 * closed the tab; the units are still flagged as promised to them; ATP is wrong until someone
 * notices. BR-014 gives a hold 30 minutes.</p>
 *
 * <p><b>Bounded batches.</b> Each run takes at most {@link #BATCH_SIZE} aggregates. After an
 * outage the backlog could be tens of thousands of rows, and a single transaction over all of them
 * would hold write locks long enough to stall live checkouts. Small batches every minute drain the
 * backlog without ever blocking a customer for long.</p>
 *
 * <p><b>Single instance assumed.</b> Two application instances would both sweep; the row locks
 * make that safe rather than corrupting, but it is wasted work. When the deployment grows past one
 * instance, put a ShedLock around this method — noted in {@code docs/adr/0005}.</p>
 */
@Component
class ReservationSweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationSweeper.class);
    private static final int BATCH_SIZE = 200;

    private final StockItemRepository repository;
    private final InventoryEventPublisher events;
    private final Clock clock;

    ReservationSweeper(StockItemRepository repository, InventoryEventPublisher events, Clock clock) {
        this.repository = repository;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Ordering note: ShedLock's advisor runs at {@code HIGHEST_PRECEDENCE}, so the lock is taken
     * <b>before</b> the transaction opens and released after it commits. That is the order this job
     * needs — holding a database transaction open while waiting for a lock would tie up a pool
     * connection on every instance that loses the race.
     *
     * <p>The sweep runs every minute, so {@code lockAtMostFor} is deliberately short: a lock left
     * behind by a crashed instance must expire before it costs many cycles. It still exceeds a real
     * run by a wide margin — the sweep works in bounded batches over an indexed column.</p>
     *
     * <p>Redundant sweeps are already safe (the row locks see to that), but they are not free: each
     * one takes the same pessimistic locks and publishes the same events, so two instances sweeping
     * together produce contention and duplicate {@code StockReleased} publications.</p>
     */
    @Scheduled(fixedDelayString = "${stockflow.inventory.reservation-sweep-interval:PT1M}")
    @SchedulerLock(name = "inventory.releaseExpiredReservations",
            lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    @Transactional
    public void releaseExpiredReservations() {
        Instant now = clock.instant();
        List<StockItem> stale = repository.findWithReservationsExpiredBefore(now, BATCH_SIZE);
        if (stale.isEmpty()) {
            return;
        }

        int released = 0;
        for (StockItem item : stale) {
            released += item.releaseExpired(now);
            repository.save(item);
            events.publishEventsOf(item);
        }
        log.info("Reservation sweep released {} expired hold(s) across {} stock item(s)",
                released, stale.size());
    }
}
