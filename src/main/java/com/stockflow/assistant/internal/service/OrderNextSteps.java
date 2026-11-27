package com.stockflow.assistant.internal.service;

import com.stockflow.common.i18n.Messages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * "Why is this order not moving" (contract §1.1): the order screen's panel that tells Sales, in one look, what holds an
 * order up,
 * <b>who must act</b> and what they do next - the question customers ask Sales many times a day, and the one an order
 * stalls on when nobody knows who holds it.
 *
 * <p><b>Deterministic.</b> The reasons are {@link OrderBlockers}' - the same reading the chat's {@code findOrder}
 * gives the model - and every sentence is from the message bundle, never from a model: a user is told what the
 * {@code api}s decide, in words the business wrote. Nothing here calls a model.</p>
 *
 * <p><b>Who must act</b> ({@code waitingOn}) is decided per reason, from who resolves it in the business rules: the
 * contract drawn up and sent by Sales, signed by the customer; an instalment paid by the customer (accounting records
 * it); a CREDIT hold decided by accounting (15 §4.3), a FRAUD hold by the Sales Manager, the other holds by Sales with
 * a note, an INVENTORY hold by the warehouse's count; the shipping warehouse chosen by Sales; stock not held and
 * production not received waiting for the production coordinator's producers; an order held in full released by the
 * warehouse manager, and a released one picked by the warehouse. A delivery window not open yet is a date, not a
 * person: Sales are named because only they may move it, with the customer's consent (17 BR-28).</p>
 *
 * <p><b>Not checked is not "nothing".</b> {@code notChecked} carries {@link OrderBlockers}' unchecked parts, plus
 * {@code INSTALLMENTS} when a payment reason was found whose amount the caller may not see
 * ({@code payment-installments:READ}): the reason stands, its figure is not shown. {@code movingNormally} is true only
 * for a live order with no reason found and nothing left unchecked, so an empty list never reads as "all is well"
 * when half of it was not looked at.</p>
 */
@Component
public class OrderNextSteps {

    /** Who resolves a reason: the contract's {@code waitingOn}. */
    public enum WaitingOn { CUSTOMER, SALES, ACCOUNTANT, WAREHOUSE, PRODUCTION, CARRIER, SALES_MANAGER }

    static final String INSTALLMENTS = "INSTALLMENTS";

    private static final String KEY = "assistant.nextSteps.";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String CREDIT_HOLDS_PAGE = "/admin/orders/credit-holds";

    private final OrderBlockers blockers;
    private final Messages messages;

    OrderNextSteps(OrderBlockers blockers, Messages messages) {
        this.blockers = blockers;
        this.messages = messages;
    }

    /**
     * The order numbered {@code orderNumber}, as the order list shows it to {@code user}, and what holds it up.
     *
     * @throws com.stockflow.common.error.NotFoundException for an order the caller may not read
     */
    public NextSteps of(String orderNumber, CurrentUser user) {
        Optional<CurrentUser> caller = Optional.of(user);
        OrderSummary order = blockers.find(orderNumber.strip(), caller);
        boolean seesInstallments = user.can(ToolPermissions.INSTALLMENTS, Action.READ);
        OrderBlockers.Blockers found = blockers.of(order, caller, seesInstallments);
        String status = order.status().name();
        String statusLabel = messages.get("label.OrderStatus." + status, status);
        if (found == null) {
            return new NextSteps(order.orderId(), order.orderNumber(), status, statusLabel, List.of(), List.of(),
                    false, false);
        }
        List<Step> steps = found.reasons().stream().map(reason -> step(order, reason)).toList();
        List<String> notChecked = new ArrayList<>(found.notChecked());
        boolean amountHidden = found.reasons().stream()
                .anyMatch(reason -> "INSTALLMENT_DUE".equals(reason.code()) && reason.amountDue() == null);
        if (!seesInstallments && amountHidden) {
            notChecked.add(INSTALLMENTS);
        }
        return new NextSteps(order.orderId(), order.orderNumber(), status, statusLabel, steps, List.copyOf(notChecked),
                steps.isEmpty() && notChecked.isEmpty(), found.reasonsTruncated());
    }

    private Step step(OrderSummary order, OrderBlockers.Blocker reason) {
        String orderPage = "/admin/orders/" + order.orderId();
        String batch = reason.batchSeq() == null ? messages.get(KEY + "wholeOrder", "the order")
                : messages.get(KEY + "batch", "delivery batch {0}", String.valueOf(reason.batchSeq()));
        return switch (reason.code()) {
            case "CONTRACT_NOT_SIGNED" -> order.status() == OrderStatus.DRAFT
                    ? step(reason, WaitingOn.SALES, "contractDraft", orderPage)
                    : step(reason, WaitingOn.CUSTOMER, "contractUnsigned", orderPage);
            case "SIGNING_INSTALLMENT_UNPAID" -> step(reason, WaitingOn.CUSTOMER, "signingInstallment", orderPage);
            case "ON_HOLD" -> hold(reason, orderPage);
            case "INSTALLMENT_DUE" -> reason.amountDue() == null
                    ? step(reason, WaitingOn.CUSTOMER, "installmentDueHidden", orderPage, batch)
                    : step(reason, WaitingOn.CUSTOMER, "installmentDue", orderPage, batch,
                    money(reason.amountDue(), reason.currency()));
            case "DELIVERY_WINDOW_NOT_OPEN" -> step(reason, WaitingOn.SALES, "windowNotOpen", orderPage, batch,
                    day(reason.windowStart()));
            case "NO_SHIPPING_WAREHOUSE" -> step(reason, WaitingOn.SALES, "noShippingWarehouse", orderPage);
            case "AWAITING_RELEASE" -> step(reason, WaitingOn.WAREHOUSE, "awaitingRelease", orderPage, batch);
            case "AWAITING_PICK" -> reason.pickTaskStatus() == null
                    ? step(reason, WaitingOn.WAREHOUSE, "awaitingPick", orderPage)
                    : step(reason, WaitingOn.WAREHOUSE, "awaitingPickTask", orderPage,
                    messages.get(KEY + "pickStatus." + reason.pickTaskStatus(), reason.pickTaskStatus()));
            case "STOCK_NOT_HELD" -> step(reason, WaitingOn.PRODUCTION, "stockNotHeld", orderPage, batch,
                    reason.sku(), count(reason.shortCartons()), count(reason.cartons()), count(reason.heldCartons()));
            case "PRODUCTION_NOT_RECEIVED" -> step(reason, WaitingOn.PRODUCTION, "productionNotReceived", orderPage,
                    reason.productionOrderCode(), reason.productionOrderStatus(), batch,
                    count(reason.receivedCartons()), count(reason.cartons()),
                    reason.expectedCompletionDate() == null ? messages.get(KEY + "noDate", "not given")
                            : day(reason.expectedCompletionDate()));
            default -> step(reason, WaitingOn.WAREHOUSE, "handoverRefused", orderPage, batch,
                    reason.refusalCode() == null ? reason.code() : reason.refusalCode());
        };
    }

    /**
     * A hold, by who lifts it: accounting decides CREDIT on its own page; the Sales Manager looks at a FRAUD hold; an
     * INVENTORY hold waits for the warehouse (the short carton's count); the customer is asked for a CUSTOMER hold;
     * Sales lift the rest with a note, and decide a DELIVERY_FAILED case: the redelivery is gone, so they cancel what
     * was not delivered, and goods the customer still wants go on a new order.
     */
    private Step hold(OrderBlockers.Blocker reason, String orderPage) {
        String holdReason = reason.holdReason() == null ? "OTHER" : reason.holdReason();
        WaitingOn who = switch (holdReason) {
            case "CREDIT" -> WaitingOn.ACCOUNTANT;
            case "FRAUD" -> WaitingOn.SALES_MANAGER;
            case "INVENTORY" -> WaitingOn.WAREHOUSE;
            case "CUSTOMER" -> WaitingOn.CUSTOMER;
            default -> WaitingOn.SALES;
        };
        String variant = switch (holdReason) {
            case "CREDIT", "FRAUD", "INVENTORY", "CUSTOMER", "PAYMENT", "ADDRESS", "DELIVERY_FAILED" -> holdReason;
            default -> "OTHER";
        };
        return step(reason, who, "hold." + variant, "CREDIT".equals(holdReason) ? CREDIT_HOLDS_PAGE : orderPage);
    }

    private Step step(OrderBlockers.Blocker reason, WaitingOn who, String variant, String href, Object... arguments) {
        String key = KEY + variant;
        return new Step(reason.code(), who.name(),
                messages.get(key + ".title", reason.code(), arguments),
                messages.get(key + ".detail", reason.code(), arguments),
                messages.get(key + ".nextStep", reason.code(), arguments),
                reason.batchSeq(), reason.amountDue(), href);
    }

    /** As the screens write VND: grouped by dots, no decimals ("437.580 VND"). */
    static String money(BigDecimal amount, String currency) {
        NumberFormat grouped = NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN"));
        return grouped.format(amount) + " " + (currency == null ? "VND" : currency);
    }

    private static String day(LocalDate day) {
        return day == null ? "" : DAY.format(day);
    }

    /** A count as text, so the message format does not group it by the request's locale. */
    private static String count(Integer value) {
        return value == null ? "0" : String.valueOf(value);
    }

    /**
     * @param truncated more reasons were found than {@value OrderBlockers#MAX_BLOCKERS}
     * @param movingNormally a live order, no reason found, nothing left unchecked; false for an order no longer live
     *                       (handed over whole, delivered, cancelled, closed), whose status says where it is
     */
    public record NextSteps(java.util.UUID orderId, String orderNumber, String status, String statusLabel,
                            List<Step> blockers, List<String> notChecked, boolean movingNormally, boolean truncated) {
    }

    /**
     * @param waitingOn who must act ({@link WaitingOn})
     * @param amountDue INSTALLMENT_DUE, for a caller who may read instalments; null otherwise
     */
    public record Step(String code, String waitingOn, String title, String detail, String nextStep, Integer batchSeq,
                BigDecimal amountDue, String href) {
    }
}
