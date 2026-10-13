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
     * @param printed           units printed in the period (ACTIVITY, the default: runs recorded and a subcontractor's
     *                          goods received in it), or by the production orders created in it (CREATED)
     * @param scrap             units scrapped in the period (spoilt in printing, failed QC, written off with a stopped
     *                          or cancelled job), or by the orders created in it
     * @param scrapRate         {@code scrap / printed}, four decimals; null when nothing was printed
     * @param samplesAwaitingApproval sample rounds sent to the customer and not answered yet
     * @param today             the business date the overdue count is taken on (Asia/Ho_Chi_Minh)
     * @param periodBasis       what {@code periodFrom}/{@code periodTo} filter: {@value #ACTIVITY_IN_PERIOD} (user
     *                          decision QA3, the default) - units printed and scrapped during the period, whenever their
     *                          order was created; or {@value #CREATED_IN_PERIOD} - the production orders created in the
     *                          period, with everything they printed and scrapped so far
     * @param periodFrom        the period's first day as applied, null for open-ended
     * @param periodTo          its last day, inclusive, null for open-ended
     */
    record Summary(Map<String, Long> openByStatus, long overdue, long printed, long scrap, BigDecimal scrapRate,
                   long samplesAwaitingApproval, LocalDate today, String periodBasis, LocalDate periodFrom,
                   LocalDate periodTo) {
    }

    /** {@link Summary#periodBasis()}: the period selects production orders by the day they were created. */
    String CREATED_IN_PERIOD = "PRODUCTION_ORDERS_CREATED_IN_PERIOD";

    /** {@link Summary#periodBasis()}: the period selects printing and scrapping that happened in it (QA3). */
    String ACTIVITY_IN_PERIOD = "UNITS_PRINTED_AND_SCRAPPED_IN_PERIOD";

    /** Which period the scrap rate is taken over. */
    enum Basis { ACTIVITY, CREATED }

    /** The counts, with the scrap rate of what was printed and scrapped from {@code from} to {@code to}, inclusive. */
    default Summary summary(CurrentUser user, LocalDate from, LocalDate to) {
        return summary(user, from, to, Basis.ACTIVITY);
    }

    /** The same, over the basis named; null is {@link Basis#ACTIVITY}. */
    Summary summary(CurrentUser user, LocalDate from, LocalDate to, Basis basis);

    /** Open production orders past their due date, the most overdue first. */
    PageResponse<ProductionOrders.Row> overdue(CurrentUser user, int page, int size);

    /** Sample rounds waiting for the customer's answer, the oldest round first. */
    PageResponse<SampleRequests.Row> samplesAwaitingApproval(CurrentUser user, int page, int size);
}
