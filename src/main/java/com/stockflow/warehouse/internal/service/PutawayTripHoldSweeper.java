package com.stockflow.warehouse.internal.service;

import com.stockflow.warehouse.internal.domain.PutawayTripRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Gives back the space of putaway trips nobody is working on (kltn-docs 05 §4.5 step 16, BR-16): a trip that saw no
 * scan for the hold window keeps its cart and its bins, but no longer keeps other cartons out of them. When the clerk
 * comes back the bin is checked at the scan, as it always is, and a bin taken in the meantime is assigned again.
 *
 * <p>The window is 30 minutes unless {@code stockflow.putaway.trip-hold-minutes} says otherwise (open question C46:
 * the docs assume it and leave a per-warehouse setting for later).</p>
 *
 * <p>The method is public and locked across instances: a non-public {@code @Scheduled} method is not advised, and
 * without {@code @SchedulerLock} every instance would run it.</p>
 */
@Component
public class PutawayTripHoldSweeper {

    private static final Logger log = LoggerFactory.getLogger(PutawayTripHoldSweeper.class);

    private final PutawayTripRepository trips;
    private final Clock clock;
    private final Duration holdWindow;

    public PutawayTripHoldSweeper(PutawayTripRepository trips, Clock clock,
                                  @Value("${stockflow.putaway.trip-hold-minutes:30}") long holdMinutes) {
        this.trips = trips;
        this.clock = clock;
        this.holdWindow = Duration.ofMinutes(Math.max(1, holdMinutes));
    }

    @Scheduled(fixedDelayString = "${stockflow.putaway.trip-hold-sweep-ms:60000}")
    @SchedulerLock(name = "putaway.releaseIdleTripHolds", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    @Transactional
    public void releaseIdleHolds() {
        Instant now = clock.instant();
        int released = trips.releaseHoldsOfTripsIdleSince(now.minus(holdWindow), now);
        if (released > 0) {
            log.info("Released {} bin hold(s) of putaway trips idle for {}", released, holdWindow);
        }
    }
}
