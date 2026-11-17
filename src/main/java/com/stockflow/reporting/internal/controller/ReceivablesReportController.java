package com.stockflow.reporting.internal.controller;

import com.stockflow.common.api.ApiResponse;
import com.stockflow.common.export.ExportResponses;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionResource;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.reporting.internal.controller.dto.ReceivablesReportResponse;
import com.stockflow.reporting.internal.domain.ReceivableLine;
import com.stockflow.reporting.internal.domain.ReceivableTotals;
import com.stockflow.reporting.internal.service.ReceivablesReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The receivables report (kltn-docs 15 §3, §4.3 step 8; 2026-11-14 plan, BE2-B): who owes what after delivery, how
 * late, and how much credit each has left. {@code reporting-receivables:READ} for the accountant, Sales and the Sales
 * Manager; the Excel file is {@code :EXPORT}, the accountant's and the Sales Manager's. Scope ALL: the debts are the
 * business's, not a warehouse's.
 *
 * <p>As of today only: there is no ledger of payments to date the figures back (15 §0), so the endpoint takes no day.
 * A row links to the customer's installments ({@code GET /api/v1/installments?customerId=&overdue=true}).</p>
 */
@RestController
@RequestMapping("/api/v1/reports/receivables")
@Tag(name = "Receivables report", description = "Receivables per customer aged by due date, as of today "
        + "(kltn-docs 15 §4.3)")
@PermissionResource(
        code = ReportingResources.RECEIVABLES,
        group = "Reports",
        label = "Receivables",
        route = "/reports/receivables",
        apiPath = "/api/v1/reports/receivables",
        actions = {Action.VIEW_PAGE, Action.READ, Action.EXPORT})
class ReceivablesReportController {

    private static final String CUSTOMER = "One customer; 404 CUSTOMER_NOT_FOUND for an unknown one";
    private static final String OVERDUE_ONLY = "Only the customers with something overdue: receivable overdue, or an "
            + "installment due on signing past its due day (signingOverdue)";
    private static final String SORT = "overdue (default, most first: overdue plus signingOverdue, what the credit "
            + "check counts), receivable, customerName or oldestDueDate, with ,asc or ,desc";
    private static final String AS_OF_TODAY = "Figures of today (Asia/Ho_Chi_Minh): what each installment still owes "
            + "now, aged against today. No other day can be asked for - the system keeps no ledger of payments (15 §0). ";

    private final ReceivablesReportService reports;

    ReceivablesReportController(ReceivablesReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    @Operation(summary = "Receivables per customer, aged by due date, with credit limit and credit left",
            description = AS_OF_TODAY + "Needs reporting-receivables:READ. Receivable = installments after delivery "
                    + "whose goods were delivered and unpaid fee items; committedNotReached = installments after "
                    + "delivery not reached yet; the two add up to the credit exposure. signingOverdue = installments "
                    + "due on signing past their due day: not receivable (15 §4.3) and in no other figure, shown apart "
                    + "because the contract's credit check counts them as overdue. totals sum every row of the "
                    + "filter, not only the page.")
    @RequiresPermission(resource = ReportingResources.RECEIVABLES, action = Action.READ, scope = DataScope.ALL)
    public ApiResponse<ReceivablesReportResponse> report(
            @Parameter(description = CUSTOMER) @RequestParam(required = false) UUID customerId,
            @Parameter(description = OVERDUE_ONLY) @RequestParam(defaultValue = "false") boolean overdueOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @Parameter(description = SORT) @RequestParam(required = false) String sort) {
        var report = reports.report(customerId, overdueOnly, page, size == null ? Pages.DEFAULT_PAGE_SIZE : size,
                sort);
        return ApiResponse.ok(new ReceivablesReportResponse(report.asOf(),
                report.rows().map(ReceivablesReportController::toRow),
                report.totals().stream().map(ReceivablesReportController::toTotals).toList()));
    }

    @GetMapping("/export")
    @Operation(summary = "The receivables report as XLSX, every row of the filter and a total per currency",
            description = AS_OF_TODAY + "Needs reporting-receivables:EXPORT. Same filters and sort as the report. "
                    + "422 EXPORT_TOO_LARGE past 50,000 rows.")
    @RequiresPermission(resource = ReportingResources.RECEIVABLES, action = Action.EXPORT, scope = DataScope.ALL)
    public ResponseEntity<byte[]> export(
            @Parameter(description = CUSTOMER) @RequestParam(required = false) UUID customerId,
            @Parameter(description = OVERDUE_ONLY) @RequestParam(defaultValue = "false") boolean overdueOnly,
            @Parameter(description = SORT) @RequestParam(required = false) String sort) {
        return ExportResponses.xlsx(reports.export(customerId, overdueOnly, sort));
    }

    private static ReceivablesReportResponse.Row toRow(ReceivableLine line) {
        return new ReceivablesReportResponse.Row(line.customerId(), line.customerCode(), line.customerName(),
                line.currency(), line.receivable(), line.notYetDue(), line.overdue1To30(), line.overdue31To60(),
                line.overdue61To90(), line.overdueOver90(), line.overdue(), line.committedNotReached(),
                line.exposure(), line.signingOverdue(), line.creditLimit(), line.creditTermDays(), line.available(),
                line.oldestDueDate(), line.openItems());
    }

    private static ReceivablesReportResponse.Totals toTotals(ReceivableTotals totals) {
        return new ReceivablesReportResponse.Totals(totals.currency(), totals.customers(), totals.receivable(),
                totals.notYetDue(), totals.overdue1To30(), totals.overdue31To60(), totals.overdue61To90(),
                totals.overdueOver90(), totals.overdue(), totals.committedNotReached(), totals.exposure(),
                totals.signingOverdue());
    }
}
