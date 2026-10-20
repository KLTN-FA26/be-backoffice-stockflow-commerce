package com.stockflow.order.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PaymentStatus;
import com.stockflow.order.api.PaymentTerm;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The customer's own view of their orders (SCRUM-245/264, kltn-docs 17 §2-§3): a list of cards with
 * one unified status, and one order's detail with a timeline, the production state of each line and
 * where the delivery stands.
 *
 * <p>Not on {@code order :: api}: only this module's controller drives it. Nothing here exposes what
 * is internal to the shop — no actors, no hold notes, no stock reservations, no free-text reasons.
 * After handover nothing is tracked beyond what staff recorded (kltn-docs 09 BR-02), so the delivery
 * state comes from the order's status alone.</p>
 *
 * <p>A customer sees only their own orders; someone else's order answers NOT_FOUND exactly as an
 * unknown one does (kltn-docs 18).</p>
 */
public interface CustomerOrders {

    /**
     * The status a customer reads, folded from the order status: several internal statuses are one
     * step to the customer.
     */
    enum StatusGroup {
        AWAITING_PAYMENT, UNDER_REVIEW, CONFIRMED, IN_PRODUCTION, PREPARING, SHIPPING, DELIVERED, CANCELLED,
        RETURNED
    }

    /** Where the parcel is, as far as staff have recorded it. */
    enum DeliveryState { NOT_SHIPPED, IN_TRANSIT, DELIVERED, RETURNED }

    /** Where printing a line stands. {@code NOT_REQUIRED} for a line of stock goods. */
    enum ProductionState { NOT_REQUIRED, NOT_STARTED, IN_PROGRESS, DONE, CANCELLED }

    /**
     * @param statuses  empty or null for every status
     * @param search    part of the order number, or of the recipient's name or phone
     * @param placedFrom inclusive, a Saigon day; null for no lower bound
     * @param placedTo  inclusive, a Saigon day; null for no upper bound
     * @param sort      {@code field,direction}; {@code placedAt}, {@code orderNumber} or {@code totalAmount}
     */
    record Query(int page, int size, List<OrderStatus> statuses, String search, LocalDate placedFrom,
                 LocalDate placedTo, String sort) {
    }

    /** One row of the list. */
    record Card(UUID orderId, String orderNumber, OrderStatus status, StatusGroup statusGroup, BigDecimal total,
                String currency, Instant placedAt, int lineCount, DeliveryState deliveryState) {
    }

    /** @param goodQuantity good units printed so far, as the production module reported them */
    record Production(ProductionState state, int goodQuantity) {
    }

    record Line(UUID lineId, String sku, int quantity, BigDecimal unitPrice, BigDecimal lineTotal,
                UUID designSnapshotId, Production production) {
    }

    /**
     * One step the order went through. {@code reasonCode} is set only on the cancellation step; the
     * free-text reason staff or the customer wrote is never exposed.
     */
    record TimelineEntry(OrderStatus status, StatusGroup statusGroup, Instant occurredAt,
                         CancellationReasonCode reasonCode) {
    }

    /** @param depositRequired null unless the order is on DEPOSIT terms; {@code creditTermDays} only on CREDIT */
    record Payment(PaymentStatus status, BigDecimal paidAmount, BigDecimal depositRequired,
                   Integer creditTermDays) {
    }

    /**
     * @param holdReasonPublic null unless the order is ON_HOLD: {@code AWAITING_CREDIT_APPROVAL} or
     *                         {@code UNDER_REVIEW}, never the internal reason
     * @param timeline         oldest first
     */
    record Detail(UUID orderId, String orderNumber, OrderStatus status, StatusGroup statusGroup,
                  String holdReasonPublic, PaymentTerm paymentTerm, BigDecimal total, String currency,
                  Instant placedAt, OrderSummary.AddressSummary shippingAddress, Payment payment, List<Line> lines,
                  List<TimelineEntry> timeline, DeliveryState deliveryState) {
    }

    /**
     * The customer's orders, newest first unless {@code query.sort} says otherwise.
     *
     * @param customerId the customer profile id, resolved by the controller from the sign-in; never
     *                   from a request parameter
     * @throws com.stockflow.common.error.BusinessException 400 for an unknown sort field or a
     *         {@code placedTo} before {@code placedFrom}
     */
    PageResponse<Card> myOrders(UUID customerId, Query query);

    /**
     * One of the customer's own orders.
     *
     * @throws com.stockflow.common.error.BusinessException NOT_FOUND when the order does not exist or is
     *         not this customer's (do not reveal which)
     */
    Detail myOrder(UUID customerId, UUID orderId);
}
