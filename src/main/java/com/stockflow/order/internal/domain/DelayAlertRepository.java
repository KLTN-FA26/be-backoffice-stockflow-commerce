package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Persistence port of the delay alerts (4A-7). */
public interface DelayAlertRepository {

    /**
     * Holds, until the transaction ends, the right to recompute the alerts of {@code salesOrderId}: a recompute after an
     * operation and the nightly sweep of the same order run one after the other, so the later one reads what the
     * earlier wrote and an alert is never closed on facts older than the ones that opened it.
     */
    void lockSalesOrder(UUID salesOrderId);

    /** The open alerts on subjects of the sales order. */
    List<DelayAlert> openOf(UUID salesOrderId);

    /**
     * Writes {@code alert} unless an alert of its type is open on its subject already ({@code uk_delay_alert_open}).
     *
     * @return whether it was written - false when another one is open
     */
    boolean open(DelayAlert alert);

    /** @return whether it was open and is now closed */
    boolean close(UUID id, Instant at);

    /**
     * The sales orders the nightly sweep looks at: those in one of {@code statuses} with a signed contract, those with
     * a production order still open, and those with an alert still open (to close it).
     */
    List<UUID> salesOrdersToSweep(Collection<String> statuses);

    /** The open alerts on these production orders and delivery batches. */
    List<DelayAlert> openOn(Collection<UUID> subjectIds);
}
