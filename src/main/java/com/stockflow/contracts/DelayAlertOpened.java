package com.stockflow.contracts;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when a delay alert of the production schedule opens (4A-7, kltn-docs 17 §4.12,
 * BR-30) - once per alert: an alert stays open while its condition holds, and holding again after it closed is a new
 * alert. The <b>notification</b> module tells the people it concerns in the in-app inbox: AT_RISK, PRODUCTION_LATE and
 * PO_NEEDED to those who follow production, DELIVERY_LATE to those who deliver, at {@code warehouseId}. Nothing
 * listens for a close: the inbox message stays, the alert shows closed on the schedule.
 *
 * <p>Codes, ids and dates only - nothing anybody typed. Consumers receive it in-process through
 * {@code @ApplicationModuleListener}; see {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param type                   {@code AT_RISK}, {@code PRODUCTION_LATE}, {@code PO_NEEDED} or {@code DELIVERY_LATE}
 * @param productionOrderId      the subject of AT_RISK and PRODUCTION_LATE, else null
 * @param productionOrderCode    its code, else null
 * @param deliveryBatchId        the subject of PO_NEEDED and DELIVERY_LATE, else null
 * @param batchSeq               that batch's number in the order's schedule, else null
 * @param warehouseId            the production order's receiving warehouse, or the order's shipping warehouse; null
 *                               while the order has none
 * @param dueDate                the day the condition is measured against: the need-by day (AT_RISK, PRODUCTION_LATE),
 *                               the window's first day (PO_NEEDED) or last day (DELIVERY_LATE)
 * @param expectedCompletionDate AT_RISK: the producer's expected completion day; else null
 */
public record DelayAlertOpened(UUID alertId, String type, UUID salesOrderId, String orderNumber,
                               UUID productionOrderId, String productionOrderCode, UUID deliveryBatchId,
                               Integer batchSeq, UUID warehouseId, LocalDate dueDate,
                               LocalDate expectedCompletionDate, Instant openedAt) {
}
