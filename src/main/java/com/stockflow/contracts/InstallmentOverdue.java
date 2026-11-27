package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published by the <b>payment</b> module's daily overdue scan for an installment that has turned overdue: past its due
 * day and still owing (kltn-docs 15 §4.1 step 4, §4.3 step 11 - "khách phát sinh quá hạn sau khi đơn đã xác nhận").
 * Sales are told, and may hold the customer's running orders by hand (17 §4.9).
 *
 * <p>Overdue is no status but a flag worked out from the due day, so nothing stores that this was announced: the scan
 * may publish the same installment on a few consecutive runs (it reads back a few days, to survive a missed night), and
 * the consumer is idempotent per installment - it tells each person once.</p>
 *
 * <p>Ids, numbers and dates only - nothing anybody typed. Consumers receive it in-process through
 * {@code @ApplicationModuleListener}; see {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param dueDate     the day it was to be paid by; it is overdue from the next day
 * @param outstanding what it still owes when the scan ran
 */
public record InstallmentOverdue(UUID installmentId, UUID contractId, String contractNumber, UUID orderId,
                                 String orderNumber, UUID customerId, int seq, LocalDate dueDate,
                                 BigDecimal outstanding, String currency) {
}
