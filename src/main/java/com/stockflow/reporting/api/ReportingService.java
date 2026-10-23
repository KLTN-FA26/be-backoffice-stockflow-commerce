package com.stockflow.reporting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * THE public API of the reporting module - the sales figures behind the back-office dashboard (SCRUM-344).
 *
 * <p>Reporting is a read-model module. It keeps two rollups, maintained from {@code OrderPlaced} and
 * {@code OrderCancelled}: the orders and revenue of each Asia/Ho_Chi_Minh day, and the units and revenue of each SKU on
 * each day. <b>Revenue is the orders placed, less the ones cancelled</b>: an order counts on the day it was placed, an
 * ON_HOLD order counts, a DRAFT does not, and a cancelled order is taken out again on the day it was placed. One
 * currency, VND.</p>
 *
 * <p>Every range is {@code from}..{@code to}, both days inclusive; both optional, defaulting to the last 30 days
 * ending today. A range longer than 366 days, or one that ends before it starts, is a 400 ({@code VALIDATION_FAILED}).</p>
 */
public interface ReportingService {

    /** One entry per day of the range, zero-filled for the days with no orders. */
    List<SalesDay> salesDaily(LocalDate from, LocalDate to);

    /**
     * The best-selling SKUs of the range, by revenue (then units).
     *
     * @param limit how many to return; null means 10, at most 100
     */
    List<TopProduct> topProducts(LocalDate from, LocalDate to, Integer limit);

    /** How many orders are in each status right now - every status, zero included. Not tied to a date range. */
    List<OrderStatusCount> orderStatusCounts();

    /** The range's totals beside those of the equally long period right before it. */
    SalesSummary summary(LocalDate from, LocalDate to);

    /**
     * Recomputes the rollups of every day of the range from the orders themselves. Idempotent, and the same numbers
     * the listeners produce; use it to backfill history or to repair a drift.
     */
    RebuildResult rebuild(LocalDate from, LocalDate to);

    record SalesDay(LocalDate day, int ordersCount, BigDecimal revenue, String currency) {
    }

    /** {@code productName} is null when the product module does not know the SKU any more. */
    record TopProduct(String sku, String productName, long quantitySold, BigDecimal revenue) {
    }

    record OrderStatusCount(String status, long count) {
    }

    /**
     * @param averageOrderValue revenue / orders, zero for a period with no orders
     * @param ordersChangePercent   this period against {@code previous}, in percent rounded to one decimal; null when the
     *                              previous period had none (an infinite increase is not a number)
     */
    record SalesSummary(LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo, String currency,
                        Totals current, Totals previous, BigDecimal ordersChangePercent,
                        BigDecimal revenueChangePercent, BigDecimal averageOrderValueChangePercent) {
    }

    record Totals(long ordersCount, BigDecimal revenue, BigDecimal averageOrderValue) {
    }

    /**
     * @param days          days recomputed
     * @param ordersSeen    orders found on those days (cancelled ones included, DRAFT not)
     * @param ordersCounted of those, the orders that count: not cancelled
     */
    record RebuildResult(LocalDate from, LocalDate to, int days, long ordersSeen, long ordersCounted) {
    }
}
