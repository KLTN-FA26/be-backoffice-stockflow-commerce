package com.stockflow.payment.api;

import java.math.BigDecimal;

/**
 * What a customer owes on delivered credit orders, and whether any of it is overdue (kltn-docs 15
 * §4.3; SCRUM-431). The order module adds it to the credit exposure and blocks new credit while
 * something is overdue.
 */
public record CreditPosition(BigDecimal outstandingReceivables, boolean hasOverdue) {
}
