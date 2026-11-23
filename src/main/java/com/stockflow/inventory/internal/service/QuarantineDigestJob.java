package com.stockflow.inventory.internal.service;

import com.stockflow.contracts.ReturnedCartonsInQuarantine;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * Once a day, each warehouse's manager is told how many returned cartons wait in its quarantine (WF-05 BE-24; decision
 * TH-6: "Hàng trong khu cách ly ai lo?"). A carton that came back on a return receipt sits in quarantine, out of ATP,
 * until a warehouse manager releases it, writes it off, or gives it back to the customer - and nothing reminded anyone,
 * so it could sit there for good: stock nobody sells and nobody writes off.
 *
 * <p>It only counts and announces ({@link ReturnedCartonsInQuarantine}, published in this transaction and delivered to
 * the notification module after commit); the inbox dedupes on warehouse and day, so a run repeated the same day tells
 * nobody twice. One instance at a time ({@code @SchedulerLock}); public, so the scheduling proxy applies.</p>
 */
@Component
public class QuarantineDigestJob {

    private static final Logger log = LoggerFactory.getLogger(QuarantineDigestJob.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final StockPackageRepository stockPackages;
    private final WarehouseService warehouses;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public QuarantineDigestJob(StockPackageRepository stockPackages, WarehouseService warehouses,
                               ApplicationEventPublisher events, Clock clock) {
        this.stockPackages = stockPackages;
        this.warehouses = warehouses;
        this.events = events;
        this.clock = clock;
    }

    @Scheduled(cron = "${stockflow.inventory.quarantine-digest-cron:0 0 8 * * *}", zone = "Asia/Ho_Chi_Minh")
    @SchedulerLock(name = "inventory.quarantineDigest", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void announce() {
        Map<String, Integer> waiting = stockPackages.returnedCartonsInQuarantine();
        if (waiting.isEmpty()) {
            return;
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE);
        for (WarehouseView warehouse : warehouses.activeWarehouses()) {
            Integer cartons = waiting.get(warehouse.prefix());
            if (cartons != null && cartons > 0) {
                events.publishEvent(new ReturnedCartonsInQuarantine(warehouse.id(), warehouse.prefix(), cartons, today));
                log.info("Quarantine digest: {} returned carton(s) wait at {}", cartons, warehouse.prefix());
            }
        }
    }
}
