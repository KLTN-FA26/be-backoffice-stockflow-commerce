package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.reporting.api.ReportingService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Sales of a period against the equally long period before, and the best-selling SKUs — the dashboard's figures. They
 * are the business's, not a warehouse's, which is why the reports carry scope ALL and this tool no extra guard; the
 * answer says so ({@code companyWide}), so that a question about one warehouse is not answered with them.
 *
 * <p><b>The period.</b> The dashboard takes any from..to, and so does this tool: yesterday, last week or September is
 * no window of the last N days ending today, and a model offered only that window relabels the nearest one as the
 * period asked for. {@code days} stays for "the last N days", the calls written before the dates. The days are
 * calendar days in Vietnam, the model reads them off the calendar the question context carries, and they are checked
 * here as the dashboard checks them, and against today: a period cannot end in the future, where it would set days
 * that have not happened against days that have.</p>
 */
@Component
class GetSalesSummaryTool implements AssistantTool {

    static final int MAX_DAYS = 366;
    static final int MAX_TOP = 20;

    private static final ToolSpec SPEC = new ToolSpec("getSalesSummary",
            "Sales of one period, company-wide: every warehouse and customer together. It cannot be split by "
                    + "warehouse, customer or channel; say so when asked for one. It returns the number of orders, "
                    + "revenue and average order value, the same for the previous period with the percent change, "
                    + "and the best-selling SKUs of the period by revenue. Revenue counts orders placed in the period "
                    + "less those cancelled, in VND. Give the period as from and to, the first and last day, read "
                    + "off the calendar in the question context: yesterday, this week, last week, this month and last "
                    + "month are listed there; a named month runs from its first to its last day, the latest such "
                    + "month that has started; a period still running (this week, this month) ends today, never "
                    + "later. Use days, with from and to empty, only for the last N days ending today. previous is "
                    + "the same number of days right before the period - for a month it is not the previous calendar "
                    + "month - so always state the dates of both periods; to compare with a named period instead "
                    + "(last week, last month), call again with its dates. A period still running (this week, this "
                    + "month so far) is compared with the same days of the named one (1 to 5 November against 1 to 5 "
                    + "October), never with the whole of it. When lastDayInProgress is true the period "
                    + "ends today, which is not over: say it holds only part of today, and never present a short "
                    + "day's drop as a fall in sales.",
            ToolSchema.object()
                    .string("from", "First day of the period, YYYY-MM-DD; an empty string to use days instead")
                    .string("to", "Last day of the period, YYYY-MM-DD, at most today; an empty string to use days "
                            + "instead")
                    .integer("days", "Only when from and to are empty: how many days ending today, 1 to " + MAX_DAYS
                            + "; ignored otherwise (send 0)")
                    .integer("top", "How many best-selling SKUs to list, 0 to " + MAX_TOP)
                    .build());

    private final ReportingService reports;
    private final Clock clock;

    GetSalesSummaryTool(ReportingService reports, Clock clock) {
        this.reports = reports;
        this.clock = clock;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "GET /api/v1/reports/summary";
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.REPORTS, action = Action.READ)
    public Answer run(ToolArguments arguments) {
        LocalDate today = BusinessDate.of(clock.instant());
        LocalDate from = ToolDates.optional(arguments, "from");
        LocalDate to = ToolDates.optional(arguments, "to");
        // days is read only when the dates are empty: with them it is ignored, and a 0 there is no mistake.
        Range range = from == null && to == null ? Range.last(arguments.integer("days", 1, MAX_DAYS), today)
                : Range.between(from, to, today);
        int top = arguments.integer("top", 0, MAX_TOP);
        ReportingService.SalesSummary summary = reports.summary(range.from(), range.to());
        List<TopSku> best = top == 0 ? List.of() : reports.topProducts(range.from(), range.to(), top).stream()
                .map(product -> new TopSku(product.sku(), product.productName(), product.quantitySold(),
                        product.revenue()))
                .toList();
        return new Answer(summary.from(), summary.to(), range.to().equals(today), true, summary.currency(),
                totals(summary.current()),
                new Period(summary.previousFrom(), summary.previousTo(), totals(summary.previous())),
                summary.ordersChangePercent(), summary.revenueChangePercent(),
                summary.averageOrderValueChangePercent(), best);
    }

    private static Totals totals(ReportingService.Totals totals) {
        return new Totals(totals.ordersCount(), totals.revenue(), totals.averageOrderValue());
    }

    /**
     * The days a summary covers, both inclusive, in the Vietnam calendar.
     *
     * <p>Checked here although the reports check the length and the order again: the message names what to send
     * instead, and the end is checked against today, which the reports leave alone - their dashboard never asks for
     * tomorrow, a model copying "this week" off the calendar does.</p>
     */
    record Range(LocalDate from, LocalDate to) {

        /** The last {@code days} days, today included. */
        static Range last(int days, LocalDate today) {
            return new Range(today.minusDays(days - 1L), today);
        }

        static Range between(LocalDate from, LocalDate to, LocalDate today) {
            if (from == null || to == null) {
                throw invalid("from and to go together: give both days, or leave both empty and give days");
            }
            if (from.isAfter(to)) {
                throw invalid("from must not be after to");
            }
            if (to.isAfter(today)) {
                throw invalid("to must not be after today, " + today + ": a period still running ends today");
            }
            if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
                throw invalid("the period is at most " + MAX_DAYS + " days");
            }
            return new Range(from, to);
        }

        private static BusinessException invalid(String message) {
            return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
        }
    }

    /**
     * The change percents are null when the previous period had nothing to compare with. {@code lastDayInProgress} is
     * said in the answer rather than left to the model: at 9:00 a one-day comparison sets a morning against a whole
     * day. {@code companyWide} is always true, said in the answer for the same reason: a warehouse manager asking about
     * their warehouse must not be handed the business's revenue as theirs.
     */
    record Answer(LocalDate from, LocalDate to, boolean lastDayInProgress, boolean companyWide, String currency,
                  Totals totals, Period previous,
                  BigDecimal ordersChangePercent, BigDecimal revenueChangePercent,
                  BigDecimal averageOrderValueChangePercent, List<TopSku> topSkus) {
    }

    record Totals(long orders, BigDecimal revenue, BigDecimal averageOrderValue) {
    }

    record Period(LocalDate from, LocalDate to, Totals totals) {
    }

    /** {@code productName} is null when the product module does not know the SKU. */
    record TopSku(String sku, String productName, long quantitySold, BigDecimal revenue) {
    }
}
