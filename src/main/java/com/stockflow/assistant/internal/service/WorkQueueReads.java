package com.stockflow.assistant.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.RequiresPermission;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.customer.api.CustomerLabel;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.inventory.api.StockAdjustmentQueries;
import com.stockflow.inventory.api.StockAdjustmentStatus;
import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.invoicing.api.EInvoiceListing;
import com.stockflow.order.api.ListOrdersQuery;
import com.stockflow.order.api.OrderHoldState;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.ProductionOrderQueries;
import com.stockflow.order.api.SalesQueues;
import com.stockflow.payment.api.PaymentQueues;
import com.stockflow.procurement.api.GoodsReceiptQueries;
import com.stockflow.procurement.api.GoodsReceiptSummary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The reads behind the work queue's sections ({@link WorkQueue}, contract 3.1), one public method per section.
 *
 * <p><b>Each carries the guard of the screen it counts</b>, as a chat tool carries its screen's: called through this
 * bean's proxy, the permission aspect checks the caller and puts that screen's data scope in force, so a module that
 * limits a warehouse-bound caller by the ambient scope ({@code WarehouseScope}) limits this read exactly as it limits
 * the screen. {@link WorkQueue} reads these very annotations to decide which sections a user sees, so there is no
 * second list of permissions to keep in step. Logic a screen applies outside its annotation is repeated here: the
 * back-office check, and the order list's warehouse rule, which its controller passes in rather than reading the
 * scope.</p>
 *
 * <p><b>Counts are counts.</b> Each read asks for the first {@link #ITEMS} rows of the screen's own list and takes the
 * list's total, which its module counts with a count query: nothing is loaded to be counted. Names are read once per
 * section ({@code CustomerService.labels}), never per row.</p>
 *
 * <p>Read only, and no model: the queue is worked out by code.</p>
 */
@Component
public class WorkQueueReads {

    /** Rows a section lists; its count counts every match. A widget to glance at, not a report. */
    static final int ITEMS = 5;

    private final OrderService orders;
    private final SalesQueues sales;
    private final ProductionOrderQueries productionOrders;
    private final GoodsReceiptQueries receipts;
    private final StockAdjustmentQueries adjustments;
    private final PaymentQueues payments;
    private final EInvoiceListing invoices;
    private final CustomerService customers;

    WorkQueueReads(OrderService orders, SalesQueues sales, ProductionOrderQueries productionOrders,
                   GoodsReceiptQueries receipts, StockAdjustmentQueries adjustments, PaymentQueues payments,
                   EInvoiceListing invoices, CustomerService customers) {
        this.orders = orders;
        this.sales = sales;
        this.productionOrders = productionOrders;
        this.receipts = receipts;
        this.adjustments = adjustments;
        this.payments = payments;
        this.invoices = invoices;
        this.customers = customers;
    }

    // ------------------------------------------------------------------------------------------------------ orders

    /** {@code GET /api/v1/orders?status=ON_HOLD}: each with why it is held. */
    @RequiresPermission(resource = ToolPermissions.SALES_ORDERS, action = Action.READ)
    public Read heldOrders(Narrowing narrowing) {
        PageResponse<OrderSummary> page = ordersIn(OrderStatus.ON_HOLD, narrowing);
        Map<UUID, String> reasons = orders.holdStates(page.items().stream().map(OrderSummary::orderId).toList())
                .stream().filter(state -> state.holdReason() != null)
                .collect(Collectors.toMap(OrderHoldState::orderId, state -> state.holdReason().name(), (a, b) -> a));
        Map<UUID, CustomerLabel> names = names(page.items().stream().map(OrderSummary::customerId).toList());
        return new Read(page.totalElements(), page.items().stream().map(order -> new Item(order.orderNumber(),
                join(reasons.get(order.orderId()), name(names, order.customerId())), order.placedAt(),
                WorkLinks.order(order.orderId()))).toList(), false);
    }

    /** {@code GET /api/v1/orders?status=PENDING_PAYMENT}: waiting for an installment due on signing. */
    @RequiresPermission(resource = ToolPermissions.SALES_ORDERS, action = Action.READ)
    public Read awaitingPayment(Narrowing narrowing) {
        return orderRows(ordersIn(OrderStatus.PENDING_PAYMENT, narrowing));
    }

    /** {@code GET /api/v1/orders?status=AWAITING_STOCK}: waiting for goods a producer is printing. */
    @RequiresPermission(resource = ToolPermissions.SALES_ORDERS, action = Action.READ)
    public Read awaitingStock(Narrowing narrowing) {
        return orderRows(ordersIn(OrderStatus.AWAITING_STOCK, narrowing));
    }

    /**
     * The delivery batches due to ship today ({@link SalesQueues#batchesDue}), under the delivery schedule's own
     * permission; the order list's warehouse rule. Urgent when the first, whose window ends soonest, ends today: from
     * tomorrow it is late.
     */
    @RequiresPermission(resource = ToolPermissions.DELIVERY_BATCHES, action = Action.READ)
    public Read batchesDueToday(Narrowing narrowing) {
        ScopeGuards.requireBackOffice(narrowing.user());
        PageResponse<SalesQueues.BatchDue> page = sales.batchesDue(narrowing.today(), narrowing.orderWarehouses(),
                ITEMS);
        Map<UUID, CustomerLabel> names = names(page.items().stream().map(SalesQueues.BatchDue::customerId).toList());
        List<Item> items = page.items().stream().map(batch -> new Item(batch.orderNumber() + " #" + batch.batchSeq(),
                join(batch.windowStart() + ".." + batch.windowEnd(), batch.orderStatus(),
                        name(names, batch.customerId())), null, WorkLinks.order(batch.orderId()))).toList();
        boolean endsToday = !page.items().isEmpty() && narrowing.today().equals(page.items().getFirst().windowEnd());
        return new Read(page.totalElements(), items, endsToday);
    }

    /** Contracts waiting for accounting's credit decision (15 BR-11), oldest first. */
    @RequiresPermission(resource = ToolPermissions.CREDIT_HOLDS, action = Action.READ)
    public Read creditReviewsPending(Narrowing narrowing) {
        ScopeGuards.requireBackOffice(narrowing.user());
        PageResponse<SalesQueues.CreditReviewWaiting> page = sales.creditReviewsWaiting(ITEMS);
        Map<UUID, CustomerLabel> names = names(page.items().stream()
                .map(SalesQueues.CreditReviewWaiting::customerId).toList());
        return new Read(page.totalElements(), page.items().stream().map(review -> new Item(review.contractNumber(),
                join(review.orderNumber(), name(names, review.customerId())), review.submittedAt(),
                WorkLinks.creditReviews())).toList(), false);
    }

    /** Draft quotes waiting for the Sales Manager to approve their prices (14 BR-10). */
    @RequiresPermission(resource = ToolPermissions.QUOTES, action = Action.READ)
    public Read quotesAwaitingPriceApproval(Narrowing narrowing) {
        ScopeGuards.requireBackOffice(narrowing.user());
        PageResponse<SalesQueues.QuoteWaiting> page = sales.quotesAwaitingPriceApproval(narrowing.user(), ITEMS);
        Map<UUID, CustomerLabel> names = names(page.items().stream().map(SalesQueues.QuoteWaiting::customerId)
                .toList());
        return new Read(page.totalElements(), page.items().stream().map(quote -> new Item(quote.quoteNumber(),
                name(names, quote.customerId()), quote.createdAt(), WorkLinks.quote(quote.quoteId()))).toList(),
                false);
    }

    /**
     * Production orders late or at risk ({@link ProductionOrderQueries#followUps} without the due-soon ones), the most
     * urgent first; urgent when the first is past its need-by day.
     */
    @RequiresPermission(resource = ToolPermissions.PRODUCTION_ORDERS, action = Action.READ)
    public Read productionOrdersLate(Narrowing narrowing) {
        ScopeGuards.requireBackOffice(narrowing.user());
        PageResponse<ProductionOrderQueries.FollowUp> page = productionOrders.followUps(narrowing.today(), null,
                narrowing.warehouseId(), ITEMS);
        List<Item> items = page.items().stream().map(followUp -> new Item(followUp.order().code(),
                join(followUp.reason().name(), followUp.daysLate() == null ? null : "+" + followUp.daysLate() + "d",
                        followUp.order().producerName()), null,
                WorkLinks.productionOrder(followUp.order().id()))).toList();
        boolean late = !page.items().isEmpty()
                && page.items().getFirst().reason() == ProductionOrderQueries.FollowUpReason.LATE;
        return new Read(page.totalElements(), items, late);
    }

    // --------------------------------------------------------------------------------------------------- warehouse

    /** {@code GET /api/v1/goods-receipts?status=IN_PUTAWAY}: confirmed receipts whose cartons wait to be put away. */
    @RequiresPermission(resource = ToolPermissions.GOODS_RECEIPTS, action = Action.READ)
    public Read receiptsToPutAway(Narrowing narrowing) {
        PageResponse<GoodsReceiptSummary> page = receipts.receipts(List.of("IN_PUTAWAY"), null,
                narrowing.warehouseId(), ITEMS);
        return new Read(page.totalElements(), page.items().stream().map(receipt -> new Item(receipt.number(),
                join(receipt.warehouseCode(), receipt.productionOrderCode()), receipt.confirmedAt(),
                WorkLinks.goodsReceipt(receipt.receiptId()))).toList(), false);
    }

    /**
     * {@code GET /api/v1/inventory/adjustments?status=PENDING_APPROVAL}, oldest first. The blind count is the
     * screen's: a counter is not given the pending variance of a carton a count holds.
     */
    @RequiresPermission(resource = ToolPermissions.STOCK_ADJUSTMENTS, action = Action.READ)
    public Read pendingAdjustments(Narrowing narrowing) {
        PageResponse<StockAdjustmentSummary> page = adjustments.adjustments(StockAdjustmentStatus.PENDING_APPROVAL,
                null, null, narrowing.warehousePrefix(), 0, ITEMS, "createdAt,asc");
        return new Read(page.totalElements(), page.items().stream().map(adjustment -> new Item(adjustment.number(),
                join(adjustment.sku(), adjustment.locationCode(),
                        adjustment.reason() == null ? null : adjustment.reason().name(),
                        adjustment.direction() == null ? null : adjustment.direction().name()),
                adjustment.requestedAt(), WorkLinks.adjustment(adjustment.adjustmentId()))).toList(), false);
    }

    // ------------------------------------------------------------------------------------------------------- money

    /** {@code GET /api/v1/bank-transactions?status=NEEDS_REVIEW}: transfers no receipt was recorded for. */
    @RequiresPermission(resource = ToolPermissions.BANK_TRANSACTIONS, action = Action.READ)
    public Read bankTransactionsToReview(Narrowing narrowing) {
        ScopeGuards.requireBackOffice(narrowing.user());
        PageResponse<PaymentQueues.TransferToReview> page = payments.transfersToReview(ITEMS);
        return new Read(page.totalElements(), page.items().stream().map(transfer -> new Item(
                money(transfer.amount()), transfer.reviewReason(),
                transfer.transactionAt() != null ? transfer.transactionAt() : transfer.receivedAt(),
                WorkLinks.bankTransaction(transfer.transactionId()))).toList(), false);
    }

    /** {@code GET /api/v1/installments?overdue=true}: past their due day and still owed, the earliest due first. */
    @RequiresPermission(resource = ToolPermissions.INSTALLMENTS, action = Action.READ)
    public Read overdueInstallments(Narrowing narrowing) {
        ScopeGuards.requireBackOffice(narrowing.user());
        PageResponse<PaymentQueues.OverdueInstallment> page = payments.overdueInstallments(ITEMS);
        Map<UUID, CustomerLabel> names = names(page.items().stream()
                .map(PaymentQueues.OverdueInstallment::customerId).toList());
        return new Read(page.totalElements(), page.items().stream().map(installment -> new Item(
                installment.orderNumber() + " #" + installment.seq(),
                join(installment.dueDate() == null ? null : installment.dueDate().toString(),
                        money(installment.outstanding()),
                        name(names, installment.customerId())), null,
                WorkLinks.installment(installment.installmentId()))).toList(), false);
    }

    /**
     * {@code GET /api/v1/einvoices} with the statuses still to issue (DRAFT, SUBMITTING, REJECTED): the accountant's
     * work. Urgent when one has reached its deadline to be issued: one more count query.
     */
    @RequiresPermission(resource = ToolPermissions.EINVOICES, action = Action.READ)
    public Read einvoicesToIssue(Narrowing narrowing) {
        ScopeGuards.requireBackOffice(narrowing.user());
        PageResponse<EInvoiceListing.EInvoiceRow> page = invoices.einvoices(new EInvoiceListing.Filter(
                EInvoiceListing.TO_ISSUE_STATUSES, null, null, null, null, null), narrowing.now(), ITEMS);
        boolean overdue = page.totalElements() > 0 && invoices.einvoices(new EInvoiceListing.Filter(
                EInvoiceListing.TO_ISSUE_STATUSES, null, null, null, null, narrowing.now()), narrowing.now(), 1)
                .totalElements() > 0;
        return new Read(page.totalElements(), page.items().stream().map(invoice -> new Item(invoice.code(),
                join(invoice.status(), invoice.orderNumber(), invoice.overdue() ? "OVERDUE" : null),
                // The e-invoice page is by an id the listing does not give: no link rather than a guessed one.
                invoice.issueDueAt(), null)).toList(), overdue);
    }

    // ----------------------------------------------------------------------------------------------------- helpers

    private PageResponse<OrderSummary> ordersIn(OrderStatus status, Narrowing narrowing) {
        ScopeGuards.requireBackOffice(narrowing.user());
        return orders.list(new ListOrdersQuery(0, ITEMS, null, List.of(status), null, null, null,
                "placedAt,asc;orderNumber,asc", narrowing.orderWarehouses()));
    }

    private Read orderRows(PageResponse<OrderSummary> page) {
        Map<UUID, CustomerLabel> names = names(page.items().stream().map(OrderSummary::customerId).toList());
        return new Read(page.totalElements(), page.items().stream().map(order -> new Item(order.orderNumber(),
                name(names, order.customerId()), order.placedAt(), WorkLinks.order(order.orderId()))).toList(),
                false);
    }

    /** The customers' labels, read once for a section, as the order list names its page. */
    private Map<UUID, CustomerLabel> names(Collection<UUID> customerIds) {
        List<UUID> ids = customerIds.stream().filter(Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : customers.labels(ids);
    }

    private static String name(Map<UUID, CustomerLabel> names, UUID customerId) {
        CustomerLabel label = customerId == null ? null : names.get(customerId);
        return label == null ? null : label.name();
    }

    /**
     * An amount as the screens show it: whole VND, grouped ("50.000.000 VND"). The database keeps four decimals, and
     * {@code toPlainString} showed "50000000.0000", which reads as fifty million and a fraction nobody owes.
     */
    static String money(BigDecimal amount) {
        return amount == null ? null : DocumentFormats.money(amount) + " VND";
    }

    /** The parts that are there, joined by a middle dot; null when none is. */
    static String join(String... parts) {
        String joined = java.util.Arrays.stream(parts).filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(" · "));
        return joined.isEmpty() ? null : joined;
    }

    /**
     * What a section's read is narrowed to.
     *
     * @param user           who reads
     * @param today          the business day
     * @param now            the instant the queue is read at
     * @param warehouseId    the one warehouse asked for; null for every warehouse the user may see
     * @param warehousePrefix its code prefix; null with it
     */
    public record Narrowing(CurrentUser user, LocalDate today, Instant now, UUID warehouseId, String warehousePrefix) {

        /**
         * The order list's warehouse rule ({@code OrderWarehouseVisibility.limitOf}), which its controller passes in
         * rather than the scope applying it: the warehouse asked for; else a warehouse-limited user's own warehouses,
         * none when assigned none; else null, no limit.
         */
        Set<UUID> orderWarehouses() {
            if (warehouseId != null) {
                return Set.of(warehouseId);
            }
            if (!ToolAvailability.limitedToWarehouses(user)) {
                return null;
            }
            return user.warehouseIds() == null ? Set.of() : Set.copyOf(user.warehouseIds());
        }
    }

    /**
     * @param count  every match, counted
     * @param items  the first {@link #ITEMS}, in the screen's order
     * @param urgent the section crosses its critical line (a batch due ends today, a production order is past its
     *               need-by day, an e-invoice is past its deadline)
     */
    public record Read(long count, List<Item> items, boolean urgent) {

        public Read {
            items = List.copyOf(items);
        }
    }

    /**
     * One row: a code and a line of codes, figures and a name, no free text anyone typed.
     *
     * @param at when it entered the queue, where the list says; null otherwise
     */
    public record Item(String label, String detail, Instant at, String href) {
    }
}
