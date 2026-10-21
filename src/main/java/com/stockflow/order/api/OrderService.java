package com.stockflow.order.api;

import com.stockflow.common.api.PageResponse;

import java.util.Optional;
import java.util.UUID;

/**
 * THE public API of the order module.
 *
 * <p>Narrow on purpose. Other modules need to place an order and to look one up; they do not need
 * to add a line, change an address or transition a status, so those are not here. Every method
 * added to this interface is a coupling point that can never be removed without touching every
 * caller.</p>
 */
public interface OrderService {

    /**
     * Place an order: create it, reserve the stock, publish the fact.
     *
     * <p>All of it in one transaction. See {@code OrderServiceImpl} for why that sentence is the
     * entire justification for this architecture.</p>
     */
    OrderSummary placeOrder(PlaceOrderCommand command);

    /** Retained for caller compatibility; always refuses guest checkout under the B2B policy. */
    OrderSummary placeGuestOrder(PlaceGuestOrderCommand command);

    Optional<OrderSummary> findById(UUID orderId);

    /**
     * Cancel an order and release whatever stock it was holding; {@code OrderCancelled} tells payment,
     * fulfillment and production (SCRUM-460).
     *
     * @throws com.stockflow.common.error.BusinessException {@code ORDER_NOT_CANCELLABLE} from SHIPPED on
     */
    OrderSummary cancel(UUID orderId, CancelOrderCommand command);

    /** {@link #cancel(UUID, CancelOrderCommand)} with a free-text reason, read by {@link CancelOrderCommand#fromText}. */
    void cancel(UUID orderId, String reason);

    /**
     * A customer cancelling their own order: at once while nothing has been released, otherwise as a
     * request Sales decides (kltn-docs 17 §2, §4.4). {@code customerId} is the customer profile id
     * that orders are stored under, not the sign-in id, so the ownership check is made here
     * explicitly rather than through the ambient data scope (which compares against the sign-in id).
     *
     * @param requestedBy the signed-in user, recorded on a request
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} for a reason that is not the
     *         customer's to give ({@link CancellationReasonCode#customerMayGive}); {@code NOT_FOUND} if the order is not
     *         this customer's, so the existence of another customer's order is not revealed;
     *         {@code ORDER_CANCELLATION_REQUEST_PENDING} when a request is already waiting
     */
    CancellationOutcome cancelOwn(UUID orderId, UUID customerId, CancellationReasonCode reasonCode, String note,
                                  UUID requestedBy);

    /**
     * @deprecated since G3-2: {@link #startPicking(UUID, UUID)}. Kept one sprint for callers written before it.
     */
    @Deprecated(forRemoval = true)
    OrderSummary releaseToFulfillment(UUID orderId);

    // ---- the warehouse and the carrier (G3-2). Every method joins the caller's transaction, takes the order's row
    // lock and is idempotent when the order is already where the call would take it (architecture decision A1).

    /**
     * The warehouse starts picking: READY_TO_FULFILL → PICKING (kltn-docs 17 §4.2 step 6). An order already picking,
     * packed or partly shipped is left alone, and so is one IN_PRODUCTION: picking its stock lines in parallel does
     * not change it (17 §4.2 step 5).
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}; {@code ORDER_INVALID_TRANSITION} from
     *         any other status
     */
    OrderSummary startPicking(UUID orderId, UUID by);

    /**
     * Everything still to ship is packed: READY_TO_FULFILL (printed goods only), PICKING or PARTIALLY_FULFILLED →
     * PACKED (kltn-docs 08, 17 §5). Also records that a package was completed (the cancellation rules read it).
     *
     * @throws com.stockflow.common.error.BusinessException {@code ORDER_CANCELLED}; {@code ORDER_INVALID_TRANSITION},
     *         also while the order is ON_HOLD
     */
    OrderSummary markPacked(UUID orderId, UUID by);

    /**
     * Everything handed to the carrier at once, no shipment recorded on the order: PACKED → SHIPPED (09 BR-03).
     *
     * @throws com.stockflow.common.error.BusinessException {@code ORDER_BALANCE_NOT_PAID} when money due before the
     *         goods leave has not all arrived (G3-Q4); {@code ORDER_DELIVERY_VIA_SHIPMENT} for an order with shipments
     */
    OrderSummary markShipped(UUID orderId, java.time.Instant handedOverAt, UUID by);

    /**
     * A shipment of the order was handed to the carrier (09 §4.1 step 3; split shipments, G4-Q3): PICKING / PACKED /
     * PARTIALLY_FULFILLED → PARTIALLY_FULFILLED while units are left, → SHIPPED once every unit is shipped or closed.
     *
     * @throws com.stockflow.common.error.BusinessException {@code ORDER_BALANCE_NOT_PAID};
     *         {@code ORDER_SHIPMENT_QUANTITY_EXCEEDED}; {@code ORDER_SHIPMENT_CONFLICT} when the same shipment id comes
     *         with other units; {@code ORDER_INVALID_TRANSITION}, also while ON_HOLD; {@code ORDER_CANCELLED}
     */
    OrderSummary recordHandover(UUID orderId, ShipmentHandover handover);

    /**
     * What the carrier would collect for a shipment of these units if it were handed over now (G4-5: the package label
     * and the shipment, 08 step 6, 09 BR-04): the sum {@link #recordHandover} fixes, from the same formula and the
     * payment ledger. {@code earlierPending}: the units of the order's shipments made before this one and not handed
     * over yet - the money paid ahead goes to them first, as it will at their handovers. Read-only; 0 for an order whose
     * carrier collects nothing (not an ON_DELIVERY deposit order).
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}; {@code VALIDATION_FAILED} for a line not
     *         on the order
     */
    java.math.BigDecimal codFor(UUID orderId, java.util.List<ShipmentHandover.Line> lines,
                                java.util.List<ShipmentHandover.Line> earlierPending);

    /**
     * The status and hold reason of each of {@code orderIds} that exists, in one read (G4-5: the packing queue leaves
     * out orders held for anything but their design check, and orders packing can no longer work on).
     */
    java.util.List<OrderHoldState> holdStates(java.util.Collection<UUID> orderIds);

    /** The carrier delivered one shipment (09 §4.3 step 1); a credit order bills it ({@code OrderDelivered}). */
    OrderSummary recordShipmentDelivered(UUID orderId, UUID shipmentId, UUID by);

    /** The carrier could not deliver one shipment (09 §4.3 step 2); OTHER needs a note. */
    OrderSummary recordShipmentDeliveryFailed(UUID orderId, UUID shipmentId, DeliveryFailureReason reason, String note,
                                              UUID by);

    /** A failed shipment came back to the warehouse through the returns receipt (09 §4.3 step 3, BR-06). */
    OrderSummary recordShipmentReturned(UUID orderId, UUID shipmentId, UUID by);

    /**
     * The carrier reported the order delivered (kltn-docs 09 §4.3): SHIPPED or DELIVERY_FAILED → DELIVERED, announced
     * as {@code OrderDelivered}. Idempotent for an order already delivered. Only for an order without shipments
     * (G4-Q5): one with shipments is delivered shipment by shipment.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}; {@code ORDER_INVALID_TRANSITION} when it
     *         is not SHIPPED or DELIVERY_FAILED; {@code ORDER_DELIVERY_VIA_SHIPMENT} when it has shipments
     */
    OrderSummary recordDelivery(UUID orderId);

    OrderSummary recordDelivery(UUID orderId, UUID by);

    /** The whole order could not be delivered: SHIPPED → DELIVERY_FAILED; only without shipments (G4-Q5). */
    OrderSummary recordDeliveryFailure(UUID orderId, DeliveryFailureReason reason, String note, UUID by);

    /** The whole order came back (RTO through the returns receipt): DELIVERY_FAILED → RETURNED; without shipments. */
    OrderSummary recordReturnedToOrigin(UUID orderId, UUID by);

    /**
     * Put the order on hold (17 §5; WBS 3.17.6): from CONFIRMED up to PACKED, and a partly shipped order.
     * {@code CREDIT} is placed by the credit check only; OTHER needs a note.
     *
     * @throws com.stockflow.common.error.BusinessException {@code ORDER_INVALID_TRANSITION} from any other status,
     *         including ON_HOLD
     */
    OrderSummary putOnHold(UUID orderId, HoldReason reason, String note, UUID by);

    /**
     * Resolve a hold: back to the status it was entered from, then on with what its shipments reported meanwhile.
     *
     * @throws com.stockflow.common.error.BusinessException {@code ORDER_NOT_ON_HOLD};
     *         {@code ORDER_CREDIT_HOLD_USE_APPROVAL} for a credit hold
     */
    OrderSummary resolveHold(UUID orderId, String note, UUID by);

    /**
     * Close what is left of a partly fulfilled order (17 edge "short pick"): its units not shipped will not be
     * delivered; their stock holds are released, payment refunds money received above what the order is now worth
     * ({@code OrderRemainderClosed}). Only from PARTIALLY_FULFILLED.
     */
    OrderSummary closeRemainder(UUID orderId, CancellationReasonCode reasonCode, String note, UUID by);

    /** A printed design did not match at packing (BR-DSG-003): ON_HOLD for QC, reason DESIGN_MISMATCH. */
    void putOnDesignHold(UUID orderId, String reason);

    /**
     * Production started printing for the order - an in-house run, or work sent to a subcontractor. Joins the caller's
     * transaction and takes the order's row lock, so a cancellation in flight either came first (this is refused,
     * {@code ORDER_CANCELLED}, and nothing is printed) or comes after and may keep an agreed fee (03 §9). Idempotent.
     */
    void recordPrintingStarted(UUID orderId);

    /**
     * Fulfillment completed a package of the order. Joins the caller's transaction and takes the order's row lock,
     * so a cancellation in flight either came first ({@code ORDER_CANCELLED}: the package is not completed, nothing
     * is deducted) or is refused afterwards: a packed order goes through a return (03 §9). Idempotent.
     */
    void recordPacked(UUID orderId);

    /**
     * The order's status read under its row lock, in the caller's transaction: what production checks before it
     * completes, releases or carries on work for the order, so a cancellation cannot slip in between the check and
     * the work (audit of #109). Empty for an unknown order.
     */
    Optional<OrderStatus> lockedStatus(UUID orderId);

    /** QC reverified the design: the DESIGN_MISMATCH hold is resolved back to where it was held from. */
    void resolveDesignHold(UUID orderId, UUID resolvedBy, String note);

    /**
     * SCRUM-245/WBS 3.17.7. One customer's own order history, newest first, paginated. {@code
     * lines} is empty on every row — see {@code OrderSearchRepository} for why; a single order's
     * lines are available from {@link #findById}.
     */
    PageResponse<OrderSummary> myOrders(UUID customerId, int page, int size);

    /** The back-office list: every order the caller may see, filtered and paged (SCRUM-443). */
    PageResponse<OrderSummary> list(ListOrdersQuery query);

    /**
     * Status history, oldest first. Orders placed before history was recorded start at the first
     * transition after that; an order with no recorded change returns an empty list.
     */
    java.util.List<OrderStatusChange> history(UUID orderId);
}
