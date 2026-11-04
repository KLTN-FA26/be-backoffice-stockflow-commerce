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
 * After handover nothing is tracked beyond what staff recorded (kltn-docs 09 BR-02): the delivery state comes
 * from the order's status and the outcomes staff recorded per shipment (BR-05).</p>
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

    /**
     * Where the parcels are, as far as staff have recorded it. With several shipments (G4-Q3) it sums them up
     * (kltn-docs 09 BR-05): PARTIALLY_DELIVERED once one arrived while another has not.
     */
    enum DeliveryState { NOT_SHIPPED, IN_TRANSIT, PARTIALLY_DELIVERED, DELIVERY_FAILED, DELIVERED, RETURNED }

    /**
     * What the customer may do with the order now, so the portal does not keep its own copy of the rules (G3-2):
     * cancel at once, ask for a cancellation, or order the same again.
     */
    record AllowedActions(boolean cancel, boolean requestCancellation, boolean reorder) {
    }

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
    /**
     * @param cancellationRequestStatus the status of the customer's latest cancellation request (PENDING, APPROVED
     *                                  or REJECTED); null when they never asked
     */
    record Card(UUID orderId, String orderNumber, OrderStatus status, StatusGroup statusGroup, BigDecimal total,
                String currency, Instant placedAt, int lineCount, DeliveryState deliveryState,
                String cancellationRequestStatus) {
    }

    /**
     * The customer's latest cancellation request of the order and how it was decided, so the portal remembers it
     * across reloads and shows a rejection. {@code note} is what the customer wrote; {@code decisionNote} what Sales
     * answered. Who decided is the shop's and is not shown.
     */
    record CancellationRequestView(String status, CancellationReasonCode reasonCode, String note, Instant requestedAt,
                                   Instant decidedAt, String decisionNote) {
    }

    /** @param goodQuantity good units printed so far, as the production module reported them */
    record Production(ProductionState state, int goodQuantity) {
    }

    /**
     * @param productSlug  where the line's SKU is on sale in the storefront today ({@code /catalog/{slug}}); with
     *                     {@code productTitle} and {@code productImageUrl} what a portal needs to put the line
     *                     back in a basket (reorder, SCRUM-190). All three are null when the SKU is not on sale now
     *                     (suspended, discontinued, unpublished): the portal skips such a line. They describe
     *                     today's catalogue, not what the order froze
     */
    record Line(UUID lineId, String sku, int quantity, BigDecimal unitPrice, BigDecimal lineTotal,
                UUID designSnapshotId, Production production, String productSlug, String productTitle,
                String productImageUrl) {
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
     * One shipment of the order as the customer reads it (G4-2a, kltn-docs 09 BR-05): from its handover to the carrier
     * on. Who handed it over or recorded its outcome, the note on a failure, the money it carries: none of it is here.
     *
     * @param shipmentCode  null for a shipment recorded before G4-2a (as are packageCount 0, carrier and mode)
     * @param failureReason set once a delivery failed (kept after a retry delivered it, as the history does)
     */
    record Shipment(String shipmentCode, com.stockflow.order.api.OrderShipmentStatus status, int packageCount,
                    String carrierName, String deliveryMode, Instant handedOverAt, Instant outcomeAt,
                    com.stockflow.order.api.DeliveryFailureReason failureReason) {
    }

    /**
     * @param holdReasonPublic null unless the order is ON_HOLD: {@code AWAITING_CREDIT_APPROVAL} or
     *                         {@code UNDER_REVIEW}, never the internal reason
     * @param timeline         oldest first
     * @param shipments        handed over to a carrier, oldest first; empty before the first handover
     * @param deliveryMode     SCRUM-209: PARCEL or FULL_TRUCKLOAD as the order carries it; null without a lifecycle
     */
    record Detail(UUID orderId, String orderNumber, OrderStatus status, StatusGroup statusGroup,
                  String holdReasonPublic, PaymentTerm paymentTerm, BigDecimal total, String currency,
                  Instant placedAt, OrderSummary.AddressSummary shippingAddress, Payment payment, List<Line> lines,
                  List<TimelineEntry> timeline, DeliveryState deliveryState, AllowedActions allowedActions,
                  CancellationRequestView cancellationRequest, List<Shipment> shipments, String deliveryMode) {

        public Detail {
            shipments = shipments == null ? List.of() : List.copyOf(shipments);
        }
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
