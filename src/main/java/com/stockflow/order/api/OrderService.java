package com.stockflow.order.api;

import com.stockflow.common.api.PageResponse;

import java.util.Optional;
import java.util.UUID;

/**
 * THE public API of the order module.
 *
 * <p>Narrow on purpose. Other modules need to look an order up and move it along; they do not need
 * to add a line, change an address or transition a status, so those are not here. Every method
 * added to this interface is a coupling point that can never be removed without touching every
 * caller.</p>
 */
public interface OrderService {

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

    // ---- the warehouse and the carrier (G3-2). Every method joins the caller's transaction, takes the order's row
    // lock and is idempotent when the order is already where the call would take it (architecture decision A1).

    /**
     * The warehouse starts picking: READY_TO_FULFILL → PICKING (kltn-docs 17 §4.2 step 6). An order already picking,
     * packed or partly shipped is left alone.
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
     * Goods of the order leave on a load plan's vehicle (SCRUM-392 LP-3: part of a shipment that is handed over only
     * with its last vehicle). The gate of {@link #recordHandover} runs - cancelled, on hold, not picking, money due
     * before the goods leave - and an order that does not allow partial shipments must have in the plan
     * ({@code inPlan}) every unit it must ship together ({@link #unitsDueTogether}). The first time, the order records it ({@code goodsLeftAt}): from then it is no
     * longer cancelled, only its remainder closed or returned. Takes the order's row lock.
     *
     * @param inPlan the units of the order's parcels in the plan not handed over yet; empty when they record no contents
     *               (not checked)
     * @throws com.stockflow.common.error.BusinessException {@code ORDER_BALANCE_NOT_PAID};
     *         {@code ORDER_INVALID_TRANSITION}, also while ON_HOLD; {@code ORDER_CANCELLED};
     *         {@code ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED}; {@code NOT_FOUND}
     */
    void recordGoodsLeft(UUID orderId, java.time.Instant at, java.util.List<ShipmentHandover.Line> inPlan);

    /**
     * The partial-shipment check of {@link #recordGoodsLeft} alone, when a vehicle of a load plan starts loading: a
     * plan that would leave part of an order that may not ship in parts is refused before anything is on board.
     *
     * @throws com.stockflow.common.error.BusinessException {@code ORDER_PARTIAL_SHIPMENT_NOT_ALLOWED},
     *         {@code ORDER_CANCELLED}, {@code NOT_FOUND}
     */
    void requirePlanCarriesTheRest(UUID orderId, java.util.List<ShipmentHandover.Line> inPlan);

    /**
     * The status and hold reason of each of {@code orderIds} that exists, in one read (G4-5: the packing queue leaves
     * out orders on hold, and orders packing can no longer work on).
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
     * As {@link #recordShipmentDelivered(UUID, UUID, UUID)}, at the time the carrier gives (09 §4.5, E25: the delivery's
     * own time, which an after-delivery instalment counts from). {@code by} is null when the carrier's webhook reported
     * it.
     */
    OrderSummary recordShipmentDelivered(UUID orderId, UUID shipmentId, java.time.Instant at, UUID by);

    /** As {@link #recordShipmentDeliveryFailed(UUID, UUID, DeliveryFailureReason, String, UUID)}, at {@code at}. */
    OrderSummary recordShipmentDeliveryFailed(UUID orderId, UUID shipmentId, DeliveryFailureReason reason, String note,
                                              java.time.Instant at, UUID by);

    /** As {@link #recordShipmentReturned(UUID, UUID, UUID)}, at {@code at}. */
    OrderSummary recordShipmentReturned(UUID orderId, UUID shipmentId, java.time.Instant at, UUID by);

    /**
     * A delivery the carrier reported wrongly, corrected to a failure by the warehouse manager (09 §4.3 step 5, E17):
     * the order follows, while it is not completed and the delivery billed nothing.
     *
     * @throws com.stockflow.common.error.BusinessException {@code ORDER_DELIVERY_NOT_CORRECTABLE};
     *         {@code ORDER_SHIPMENT_INVALID_TRANSITION}
     */
    OrderSummary correctShipmentToFailed(UUID orderId, UUID shipmentId, DeliveryFailureReason reason, String note,
                                         java.time.Instant at, UUID by);

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

    /** As {@link #recordDelivery(UUID, UUID)}, at the time the carrier gives ({@code by} null: its webhook, E25). */
    OrderSummary recordDelivery(UUID orderId, java.time.Instant at, UUID by);

    /** As {@link #recordDeliveryFailure(UUID, DeliveryFailureReason, String, UUID)}, at {@code at}. */
    OrderSummary recordDeliveryFailure(UUID orderId, DeliveryFailureReason reason, String note, java.time.Instant at,
                                       UUID by);

    /** As {@link #recordReturnedToOrigin(UUID, UUID)}, at {@code at}. */
    OrderSummary recordReturnedToOrigin(UUID orderId, java.time.Instant at, UUID by);

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
    default OrderSummary closeRemainder(UUID orderId, CancellationReasonCode reasonCode, String note, UUID by) {
        return closeRemainder(orderId, reasonCode, note, null, by);
    }

    /**
     * The same, keeping {@code fee} - an amount agreed with the customer for print lines closed after printing
     * started, at most their value (review of #150): the money received pays it first, the rest is refunded, what it
     * does not cover is owed.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CANCELLATION_FEE_BEFORE_PRINTING},
     *         {@code VALIDATION_FAILED}, {@code ORDER_INVALID_TRANSITION}
     */
    OrderSummary closeRemainder(UUID orderId, CancellationReasonCode reasonCode, String note, CancellationFee fee,
                                UUID by);

    /**
     * Fulfillment completed a package of the order. Joins the caller's transaction and takes the order's row lock,
     * so a cancellation in flight either came first ({@code ORDER_CANCELLED}: the package is not completed, nothing
     * is deducted) or is refused afterwards: a packed order goes through a return (03 §9). Idempotent.
     */
    void recordPacked(UUID orderId);

    /**
     * The check station labelled a shipping carton of the order (4C-6, kltn-docs 08 §4.3 step 7): the first time, the
     * order is locked - no cancellation, no change of delivery address (08 BR-04, 17 BR-12, BR-16) - and a customer's
     * cancellation request still waiting expires. Joins the caller's transaction, which must exist, and takes the
     * order's row lock, so a cancellation in flight either came first ({@code ORDER_CANCELLED}: nothing is labelled) or
     * is refused afterwards. A hold put on after the station read the order is refused the same way, under the lock.
     * Idempotent.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}; {@code ORDER_CANCELLED};
     *         {@code PACK_STATE_CONFLICT} while the order is on hold or in a status the station does not label in
     */
    void recordFirstLabel(UUID orderId);

    /**
     * The order's status read under its row lock, in the caller's transaction: what fulfillment checks before it
     * carries on work for the order, so a cancellation cannot slip in between the check and the work (audit of #109).
     * Empty for an unknown order.
     */
    Optional<OrderStatus> lockedStatus(UUID orderId);

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

    /**
     * 4B-8 (kltn-docs 09 BR-18, 15 BR-08, 17 BR-29): the orders among {@code orderIds} whose goods may not leave at
     * {@code at} - an installment still owed before delivery, a delivery window not open yet, a hold - each with why
     * and what it owes. Empty: the vehicle may go. Fulfillment asks before handing a vehicle over, and refuses it whole.
     */
    java.util.List<HandoverBlock> handoverBlocks(java.util.Collection<UUID> orderIds, java.time.Instant at);

    /**
     * 4B-8 (kltn-docs 07 BR-07): the delivery batch of the order released and not picked yet, with the holds it stands
     * for - what fulfillment opens a pick task for. Empty for an order that does not release by batch.
     *
     * @param pickedBatches the batches that have a pick task already
     */
    java.util.Optional<ReleasedBatch> batchToPick(UUID orderId, java.util.Collection<UUID> pickedBatches);

    /** 4B-8: the holds a released delivery batch stands for - its allocations, picked or not. */
    java.util.Set<UUID> holdsOfBatch(UUID orderId, UUID deliveryBatchId);

    /**
     * WF-05 fix plan, section 2 (09 §4.3): the goods of a returned shipment that Sales decided to deliver again and the
     * warehouse manager released, with no pick task yet - what {@code createTask} opens a second pick for. Empty when
     * the order has none, or every one of them is in {@code pickedCases}. Read without locks: its caller takes the
     * order's row lock ({@link #lockedStatus}) and reads it again before opening the task (WF-05 review N4).
     */
    Optional<RedeliveryToPick> redeliveryToPick(UUID orderId, java.util.Collection<UUID> pickedCases);

    /** The redelivery of delivery failure case {@code caseId}: its allocations and units, whatever became of it. */
    Optional<RedeliveryToPick> redelivery(UUID caseId);

    /**
     * 4B-8 (kltn-docs 08 §4.4 step 8.3): the units of each order line a delivery batch carries - its cartons in pieces,
     * as the signed schedule fixes them: what the carton check of that batch's task expects. Empty for a batch not of
     * the order's contract.
     */
    java.util.Map<UUID, Integer> batchUnits(UUID orderId, UUID deliveryBatchId);

    /**
     * PACK-PARTIAL with 4B-8 (kltn-docs 17 BR-15): per order, the units that must leave together for its next shipment
     * not to be a partial shipment - every unit it still wants, less the units of its delivery batches not released
     * yet. A delivery schedule is a plan of whole shipments: shipping exactly the batch released ships nothing "in
     * parts", so it needs no partial-shipment decision from Sales. For an order without a schedule it is everything
     * it still wants. The rule fulfillment applies when it confirms a load plan, as the order applies it at loading
     * start and at handover. Orders not found are left out.
     */
    java.util.Map<UUID, Integer> unitsDueTogether(java.util.Collection<UUID> orderIds);
}
