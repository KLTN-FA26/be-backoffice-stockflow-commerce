package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.reporting.api.ReportingService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Sales of the last N days against the N days before, and the best-selling SKUs — the dashboard's figures. They are
 * the business's, not a warehouse's, which is why the reports carry scope ALL and this tool no extra guard.
 */
@Component
class GetSalesSummaryTool implements AssistantTool {

    static final int MAX_DAYS = 366;
    static final int MAX_TOP = 20;

    private static final ToolSpec SPEC = new ToolSpec("getSalesSummary",
            "Sales of the last N days ending today (Vietnam time): number of orders, revenue and average order value, "
                    + "the same for the N days before with the percent change, and the best-selling SKUs of the period "
                    + "by revenue. Revenue counts orders placed less cancelled ones, in VND.",
            ToolSchema.object()
                    .integer("days", "How many days, ending today, 1 to " + MAX_DAYS)
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
        int days = arguments.integer("days", 1, MAX_DAYS);
        int top = arguments.integer("top", 0, MAX_TOP);
        LocalDate to = BusinessDate.of(clock.instant());
        LocalDate from = to.minusDays(days - 1L);
        ReportingService.SalesSummary summary = reports.summary(from, to);
        List<TopSku> best = top == 0 ? List.of() : reports.topProducts(from, to, top).stream()
                .map(product -> new TopSku(product.sku(), product.productName(), product.quantitySold(),
                        product.revenue()))
                .toList();
        return new Answer(summary.from(), summary.to(), summary.currency(), totals(summary.current()),
                new Period(summary.previousFrom(), summary.previousTo(), totals(summary.previous())),
                summary.ordersChangePercent(), summary.revenueChangePercent(),
                summary.averageOrderValueChangePercent(), best);
    }

    private static Totals totals(ReportingService.Totals totals) {
        return new Totals(totals.ordersCount(), totals.revenue(), totals.averageOrderValue());
    }

    /** The change percents are null when the previous period had nothing to compare with. */
    record Answer(LocalDate from, LocalDate to, String currency, Totals totals, Period previous,
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
