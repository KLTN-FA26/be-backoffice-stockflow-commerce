package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.ForeignCartonScanned;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Reports a foreign carton scanned at the carton check station (08 §4.2 edge case, WF4-LPN-FOREIGN) so the warehouse
 * manager is told. A separate bean with a transaction of its own: the scan is refused with an exception that rolls the
 * caller's transaction back, and an event published inside it would be rolled back with it.
 */
@Component
class ForeignCartonReports {

    private final ApplicationEventPublisher publisher;

    ForeignCartonReports(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void report(PickJpaEntity pick, String lpn, UUID scannedBy, Instant at) {
        String scanned = lpn == null ? "" : lpn.strip();
        if (scanned.length() > ForeignCartonScanned.LPN_MAX) {
            scanned = scanned.substring(0, ForeignCartonScanned.LPN_MAX);
        }
        publisher.publishEvent(new ForeignCartonScanned(Identifiers.newId(), pick.getId(), pick.getOrderId(),
                pick.getOrderNumber(), pick.getWarehouseId(), scanned, scannedBy, at));
    }
}
