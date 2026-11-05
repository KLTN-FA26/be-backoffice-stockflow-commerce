package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.inventory.api.TransferOrderQueries;
import com.stockflow.inventory.api.TransferOrderQueries.TransferOrderSummary;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Transfers between warehouses (design §4.2, C10) - "Phiếu chuyển kho nào đang đi đường?", "Phiếu nào chờ duyệt?",
 * "Chuyển kho nào trễ?" - as the transfer screen lists them ({@code GET /api/v1/inventory/transfer-orders}), through
 * {@link TransferOrderQueries}, which reads the list that screen reads. Its warehouse rule is therefore the screen's: a
 * warehouse-bound user sees only the transfers leaving or reaching their warehouses. Warehouses had no way to ask the
 * assistant what was coming to them, and a late transfer is stock a planner is counting on.
 *
 * <p><b>Late</b> is judged here, on today's date in Vietnam, while goods are still awaited at the destination
 * ({@link #AWAITED}: a partly received transfer still awaits the rest): the model is never asked to compare dates. A
 * late transfer that has not left yet is a different problem (the source has not picked or dispatched it) from one on
 * the road past its date, and the description says so.</p>
 *
 * <p><b>Late ones are never cut off.</b> The screen lists the newest first, so fifty rows of a busy month drop the
 * oldest transfers, the likeliest to be late. {@code EXPECTED_FIRST} lists the earliest expected date first instead;
 * {@code lateRows} counts the late rows in code, and {@code allLateListed} says whether a late transfer can have been
 * left out.</p>
 *
 * <p>No warehouse filter: the transfer list filters by warehouse id, and nothing in {@code warehouse :: api} turns a
 * code into one. Every row names both warehouses, and a warehouse-bound user's list holds only theirs already.</p>
 */
@Component
class FindTransferOrdersTool implements AssistantTool {

    static final int MAX_ROWS = TransferOrderQueries.MAX_TRANSFERS;

    static final List<String> STATUSES = Stream.concat(Stream.of("OPEN", "ANY"),
            TransferOrderQueries.STATUSES.stream()).toList();

    static final List<String> ORDERS = List.of("NEWEST_FIRST", "EXPECTED_FIRST");

    /** Goods still awaited at the destination: a past expected date makes these late. */
    static final Set<String> AWAITED = Set.of("DRAFT", "PENDING_APPROVAL", "APPROVED", "PICKING", "IN_TRANSIT",
            "PARTIALLY_RECEIVED");

    private static final ToolSpec SPEC = new ToolSpec("findTransferOrders",
            "Transfer orders moving stock from one warehouse to another: transfer number, fromWarehouseCode and "
                    + "toWarehouseCode, status, expected date at the destination, when it was created, packed and "
                    + "dispatched, delivery mode, and whether it is late. status: DRAFT being written, PENDING_APPROVAL "
                    + "waiting for an approver (its value is above the threshold), APPROVED not picked yet, PICKING "
                    + "being picked at the source, IN_TRANSIT on the road until the destination counts it in, "
                    + "PARTIALLY_RECEIVED part counted in and the rest still awaited, RECEIVED the count done (a "
                    + "shortage is settled at receipt), COMPLETED finished, CLOSED finished short, CANCELLED stopped "
                    + "before any goods left. OPEN asks for every status before COMPLETED, CLOSED and CANCELLED. late "
                    + "means goods are still awaited and the expected date is before today, by daysLate days: worked "
                    + "out here, quote it. A late transfer with a status before IN_TRANSIT has not left the source yet; "
                    + "say that, never that it is late on the road. order NEWEST_FIRST lists the most recently created "
                    + "first, as the screen does; EXPECTED_FIRST the earliest expected date first. For late transfers "
                    + "ask OPEN with EXPECTED_FIRST: lateRows counts the late rows returned, and allLateListed false "
                    + "means more late transfers may exist than are listed. A row has no lines: it does not say which "
                    + "SKUs or how many units. Only transfers leaving or reaching the warehouses the user may see. "
                    + "Filter by status and by text in the transfer number; there is no filter by warehouse, so for "
                    + "one warehouse use a list that is not truncated and count its rows, or say what the count "
                    + "covers. total counts every match; when it is above the rows returned (truncated), say how many "
                    + "were left out.",
            ToolSchema.object()
                    .choice("status", "Only transfers in this status; OPEN for every status still being worked "
                            + "on; ANY for every status", STATUSES)
                    .string("transferNumber", "Text in the transfer number, or part of it; an empty string for "
                            + "every transfer")
                    .choice("order", "NEWEST_FIRST, or EXPECTED_FIRST for the earliest expected date first",
                            ORDERS)
                    .integer("limit", "How many transfers to return, 1 to " + MAX_ROWS)
                    .build());

    private final TransferOrderQueries transfers;
    private final Clock clock;

    FindTransferOrdersTool(TransferOrderQueries transfers, Clock clock) {
        this.transfers = transfers;
        this.clock = clock;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return "GET /api/v1/inventory/transfer-orders";
    }

    @Override
    public ScopeLimit scopeLimit() {
        return ScopeLimit.PARTIAL;
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.TRANSFER_ORDERS, action = Action.READ)
    public Answer run(ToolArguments arguments) {
        String status = arguments.choice("status", STATUSES);
        String number = arguments.optionalText("transferNumber", 40);
        boolean expectedFirst = "EXPECTED_FIRST".equals(arguments.choice("order", ORDERS));
        int limit = arguments.integer("limit", 1, MAX_ROWS);
        List<String> statuses = switch (status) {
            case "OPEN" -> TransferOrderQueries.OPEN_STATUSES;
            case "ANY" -> List.of();
            default -> List.of(status);
        };
        PageResponse<TransferOrderSummary> page = transfers.transfers(statuses, number, expectedFirst, limit);
        LocalDate today = BusinessDate.of(clock.instant());
        List<Row> rows = page.items().stream().map(transfer -> Row.of(transfer, today)).toList();
        boolean truncated = page.totalElements() > rows.size();
        // Listed by expected date, a last row not yet due means every earlier-due transfer, so every late one, is here.
        boolean allLateListed = !truncated || expectedFirst && (rows.isEmpty() || rows.getLast().expectedDate() == null
                || !rows.getLast().expectedDate().isBefore(today));
        return new Answer(rows, page.totalElements(), truncated, rows.stream().filter(Row::late).count(),
                allLateListed);
    }

    /**
     * @param total         every transfer that matches in the warehouses the user may see
     * @param truncated     {@code total} is above the transfers returned
     * @param lateRows      how many of the rows returned are late
     * @param allLateListed no late transfer of the match was left out
     */
    record Answer(List<Row> transfers, long total, boolean truncated, long lateRows, boolean allLateListed) {
    }

    /**
     * @param late     goods still awaited ({@link #AWAITED}) and the expected date is before today
     * @param daysLate whole days since the expected date; null unless {@code late}
     */
    record Row(String transferNumber, String fromWarehouseCode, String toWarehouseCode, String status,
               LocalDate expectedDate, boolean late, Long daysLate, Instant createdAt, Instant packedAt,
               Instant dispatchedAt, String deliveryMode) {

        static Row of(TransferOrderSummary transfer, LocalDate today) {
            boolean late = AWAITED.contains(transfer.status()) && transfer.expectedDate() != null
                    && transfer.expectedDate().isBefore(today);
            return new Row(transfer.number(), transfer.fromWarehouseCode(), transfer.toWarehouseCode(),
                    transfer.status(), transfer.expectedDate(), late,
                    late ? ChronoUnit.DAYS.between(transfer.expectedDate(), today) : null, transfer.createdAt(),
                    transfer.packedAt(), transfer.dispatchedAt(), transfer.deliveryMode());
        }
    }
}
