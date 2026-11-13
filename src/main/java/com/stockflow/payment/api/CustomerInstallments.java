package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A customer's open contract instalments, for a module that shows them without owning them - the chat's view of the
 * customer beside a conversation (kltn-docs 16 §4.1 step 3, §4.4: Sales sees what is due before sending transfer
 * details or a reminder). Read-only, one statement.
 */
public interface CustomerInstallments {

    /**
     * An instalment not yet settled.
     *
     * @param status   {@code NOT_DUE}, {@code DUE} or {@code PARTIALLY_PAID}, the payment module's names
     * @param overdue  whether its due date has passed (overdue is derived, never stored: V20261121000100)
     * @param dueDate  null while its milestone has not been reached
     */
    record OpenInstallment(UUID id, UUID contractId, String contractNumber, UUID orderId, String orderNumber, int seq,
                           String name, BigDecimal amount, BigDecimal paidAmount, String currency, LocalDate dueDate,
                           String status, boolean overdue) {
    }

    /** The customer's open instalments, the earliest due first (those with no due date last), at most {@code limit}. */
    List<OpenInstallment> openOf(UUID customerId, int limit);
}
