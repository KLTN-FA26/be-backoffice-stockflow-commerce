package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One episode of a delay condition on one subject (4A-7, kltn-docs 17 §4.12): opened by the system when the condition
 * starts to hold, closed by it when the condition no longer does (step 49), never reopened - the condition holding
 * again is a new alert. The same rules are {@code ck_delay_alert_subject}, {@code ck_delay_alert_closed} and
 * {@code tg_delay_alert} in the database.
 *
 * <p>No warehouse is kept on it: the warehouse it concerns is the production order's receiving warehouse or the
 * order's shipping warehouse, read where they live - the latter may still be chosen while a PO_NEEDED is open.</p>
 *
 * @param productionOrderId the subject of AT_RISK and PRODUCTION_LATE, else null
 * @param deliveryBatchId   the subject of PO_NEEDED and DELIVERY_LATE, else null
 */
public record DelayAlert(UUID id, DelayAlertType type, UUID productionOrderId, UUID deliveryBatchId,
                         UUID salesOrderId, Instant openedAt, Instant closedAt) {

    public DelayAlert {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(salesOrderId, "salesOrderId");
        Objects.requireNonNull(openedAt, "openedAt");
        boolean aboutProduction = type.subject() == DelayAlertType.Subject.PRODUCTION_ORDER;
        if (aboutProduction != (productionOrderId != null) || aboutProduction == (deliveryBatchId != null)) {
            throw new IllegalArgumentException("A %s alert is about a %s and nothing else".formatted(type,
                    type.subject()));
        }
        if (closedAt != null && closedAt.isBefore(openedAt)) {
            throw new IllegalArgumentException("An alert is closed after it was opened");
        }
    }

    /** A new open alert of {@code condition}, on a subject of {@code salesOrderId}. */
    public static DelayAlert open(UUID id, DelayAlertEvaluator.Condition condition, UUID salesOrderId, Instant at) {
        boolean aboutProduction = condition.type().subject() == DelayAlertType.Subject.PRODUCTION_ORDER;
        return new DelayAlert(id, condition.type(), aboutProduction ? condition.subjectId() : null,
                aboutProduction ? null : condition.subjectId(), salesOrderId, at, null);
    }

    /** The production order or delivery batch it is about. */
    public UUID subjectId() {
        return productionOrderId != null ? productionOrderId : deliveryBatchId;
    }

    public boolean isOpen() {
        return closedAt == null;
    }
}
