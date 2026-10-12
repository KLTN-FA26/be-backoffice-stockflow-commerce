package com.stockflow.production.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.CurrentUser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * The workshop overview (SCRUM-402/415, WBS 3.25.9): production orders in progress by status, overdue ones,
 * the scrap rate, and sample rounds waiting for the customer's answer. A warehouse-bound caller sees their
 * warehouses only.
 */
public interface ProductionDashboard {

    /**
     * @param openByStatus      open production orders (not COMPLETED, not CANCELLED) per status
     * @param overdue           open ones whose due date is before {@code today}
     * @param printed           units printed by the production orders created in the period
     * @param scrap             of which scrapped (spoilt in printing or failed QC)
     * @param scrapRate         {@code scrap / printed}, four decimals; null when nothing was printed
     * @param samplesAwaitingApproval sample rounds sent to the customer and not answered yet
     * @param today             the business date the overdue count is taken on (Asia/Ho_Chi_Minh)
     */
    record Summary(Map<String, Long> openByStatus, long overdue, long printed, long scrap, BigDecimal scrapRate,
                   long samplesAwaitingApproval, LocalDate today) {
    }

    /** The counts; the scrap rate over production orders created from {@code from} to {@code to}, inclusive. */
    Summary summary(CurrentUser user, LocalDate from, LocalDate to);

    /** Open production orders past their due date, the most overdue first. */
    PageResponse<ProductionOrders.Row> overdue(CurrentUser user, int page, int size);

    /** Sample rounds waiting for the customer's answer, the oldest round first. */
    PageResponse<SampleRequests.Row> samplesAwaitingApproval(CurrentUser user, int page, int size);
}
