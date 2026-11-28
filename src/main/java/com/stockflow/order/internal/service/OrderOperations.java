package com.stockflow.order.internal.service;

import com.stockflow.order.api.CancelOrderCommand;
import com.stockflow.order.api.CancellationFee;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.HoldReason;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.domain.CancellationRequest;
import com.stockflow.order.internal.domain.CompletionHoldReason;
import com.stockflow.order.internal.domain.OnBehalfAction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The operational work staff do on an order from the back office after signing (kltn-docs 17 BR-32; 14 §4.4 step 18;
 * sales-owner plan BR-S13), as the controllers call it. None of it is blocked by the customer's owner - any holder of
 * the permission does it, so the order does not wait for an absent salesperson - and each step done by someone other
 * than the owner or the owner's cover is recorded as "làm hộ" and told to them ({@link OnBehalfLog}), in the step's
 * own transaction.
 *
 * <p>Why a class of its own rather than a line in each service: several of these services are also called by the
 * system or by another module with someone else's id - a short pick holds the order for the picker, the carton check
 * lifts the hold it raised, a failed delivery's cancellation closes the rest itself - and none of that is Sales' work
 * done for a colleague. Only the requests staff make from these screens come through here. The steps themselves do not
 * change: each method calls the service the controller called before and joins its transaction.</p>
 */
@Service
@Transactional
public class OrderOperations {

    private final ShippingWarehouseService shippingWarehouses;
    private final OrderService orders;
    private final OrderCompletionHolds completionHolds;
    private final CancellationRequestService cancellationRequests;
    private final PartialShipments partialShipments;
    private final DeliveryFailureService deliveryFailures;
    private final ShippingAddressChanges addressChanges;
    private final OnBehalfLog onBehalf;
    private final com.stockflow.order.internal.repository.OrderOnBehalfActions entries;
    /** The concrete service, for the one operation the {@link OrderService} interface does not carry (shortage cards). */
    private final OrderServiceImpl orderService;

    OrderOperations(ShippingWarehouseService shippingWarehouses, OrderService orders, OrderServiceImpl orderService,
                    OrderCompletionHolds completionHolds, CancellationRequestService cancellationRequests,
                    PartialShipments partialShipments, DeliveryFailureService deliveryFailures,
                    ShippingAddressChanges addressChanges, OnBehalfLog onBehalf,
                    com.stockflow.order.internal.repository.OrderOnBehalfActions entries) {
        this.entries = entries;
        this.orderService = orderService;
        this.shippingWarehouses = shippingWarehouses;
        this.orders = orders;
        this.completionHolds = completionHolds;
        this.cancellationRequests = cancellationRequests;
        this.partialShipments = partialShipments;
        this.deliveryFailures = deliveryFailures;
        this.addressChanges = addressChanges;
        this.onBehalf = onBehalf;
    }

    /**
     * One "làm hộ" entry of an order's history (17 BR-32): {@code action} (an {@code OnBehalfAction} name) done by
     * {@code actorId} for {@code actingForUserId}, who was told; null when the customer had no active owner.
     */
    public record OnBehalfEntry(String action, UUID actorId, UUID actingForUserId, java.time.Instant actedAt) {
    }

    /** The order's "làm hộ" entries, oldest first, for its history (staff only). */
    @Transactional(readOnly = true)
    public List<OnBehalfEntry> onBehalfHistory(UUID orderId) {
        return entries.of(orderId).stream().map(entry -> new OnBehalfEntry(entry.action(), entry.actorId(),
                entry.actingForUserId(), entry.actedAt())).toList();
    }

    /** 17 §4.2: {@link ShippingWarehouseService#choose}. */
    public ShippingWarehouseService.Coverage chooseShippingWarehouse(UUID orderId, UUID warehouseId, UUID by) {
        ShippingWarehouseService.Coverage coverage = shippingWarehouses.choose(orderId, warehouseId, by);
        onBehalf.record(orderId, by, OnBehalfAction.CHOOSE_SHIPPING_WAREHOUSE);
        return coverage;
    }

    /** 17 §5: {@link OrderService#putOnHold}, by hand. */
    public OrderSummary putOnHold(UUID orderId, HoldReason reason, String note, UUID by) {
        OrderSummary order = orders.putOnHold(orderId, reason, note, by);
        onBehalf.record(orderId, by, OnBehalfAction.HOLD);
        return order;
    }

    /** 17 §5: {@link OrderService#resolveHold}, by hand. */
    public OrderSummary resolveHold(UUID orderId, String note, UUID by) {
        // Sales decide without the card (17 §4.14 step 59, D47): a shortage card still open ends for that reason.
        OrderSummary order = orderService.resolveHold(orderId, note, by,
                com.stockflow.order.api.ShortageCardEnd.DECIDED_WITHOUT_CARD);
        onBehalf.record(orderId, by, OnBehalfAction.RESOLVE_HOLD);
        return order;
    }

    /** WF-05: {@link OrderCompletionHolds#hold}. */
    public OrderCompletionHolds.CompletionHold holdCompletion(UUID orderId, CompletionHoldReason reason, String note,
                                                              UUID by) {
        OrderCompletionHolds.CompletionHold hold = completionHolds.hold(orderId, reason, note, by);
        onBehalf.record(orderId, by, OnBehalfAction.HOLD_COMPLETION);
        return hold;
    }

    /** WF-05: {@link OrderCompletionHolds#release}. */
    public OrderCompletionHolds.CompletionHold releaseCompletionHold(UUID orderId, String note, UUID by) {
        OrderCompletionHolds.CompletionHold hold = completionHolds.release(orderId, note, by);
        onBehalf.record(orderId, by, OnBehalfAction.RELEASE_COMPLETION_HOLD);
        return hold;
    }

    /** 17 §4.8: {@link CancellationRequestService#approve}. */
    public OrderSummary approveCancellationRequest(UUID orderId, UUID requestId, BigDecimal retainedPercent,
                                                   CancellationFee agreedFee, String note, UUID by) {
        OrderSummary order = cancellationRequests.approve(orderId, requestId, retainedPercent, agreedFee, note, by);
        onBehalf.record(orderId, by, OnBehalfAction.APPROVE_CANCELLATION_REQUEST);
        return order;
    }

    /** 17 §4.8: {@link CancellationRequestService#reject}. */
    public CancellationRequest rejectCancellationRequest(UUID orderId, UUID requestId, String note, UUID by) {
        CancellationRequest request = cancellationRequests.reject(orderId, requestId, note, by);
        onBehalf.record(orderId, by, OnBehalfAction.REJECT_CANCELLATION_REQUEST);
        return request;
    }

    /** 17 §4.8: {@link OrderService#cancel} from the back office, by the staff the command names. */
    public OrderSummary cancel(UUID orderId, CancelOrderCommand command) {
        OrderSummary order = orders.cancel(orderId, command);
        onBehalf.record(orderId, command.cancelledBy(), OnBehalfAction.CANCEL_ORDER);
        return order;
    }

    /** G3-2: {@link OrderService#closeRemainder}. */
    public OrderSummary closeRemainder(UUID orderId, CancellationReasonCode reasonCode, String note,
                                       CancellationFee fee, UUID by) {
        OrderSummary order = orders.closeRemainder(orderId, reasonCode, note, fee, by);
        onBehalf.record(orderId, by, OnBehalfAction.CLOSE_REMAINDER);
        return order;
    }

    /** PACK-PARTIAL: {@link PartialShipments#decide}. */
    public OrderSummary decidePartialShipment(UUID orderId, boolean allowed, String note, UUID by) {
        OrderSummary order = partialShipments.decide(orderId, allowed, note, by);
        onBehalf.record(orderId, by, OnBehalfAction.DECIDE_PARTIAL_SHIPMENT);
        return order;
    }

    /** WF-05 §3: {@link DeliveryFailureService#cancelUndelivered}. */
    public DeliveryFailureService.CaseView cancelUndelivered(UUID orderId, UUID caseId,
                                                             DeliveryFailureService.Cancel command, UUID by) {
        DeliveryFailureService.CaseView view = deliveryFailures.cancelUndelivered(orderId, caseId, command, by);
        onBehalf.record(orderId, by, OnBehalfAction.CANCEL_UNDELIVERED);
        return view;
    }

    /** WF-05 §3: {@link DeliveryFailureService#cancelWithoutCase}. */
    public List<DeliveryFailureService.CaseView> cancelUndeliveredWithoutCase(UUID orderId,
                                                                             DeliveryFailureService.Cancel command,
                                                                             UUID by) {
        List<DeliveryFailureService.CaseView> views = deliveryFailures.cancelWithoutCase(orderId, command, by);
        onBehalf.record(orderId, by, OnBehalfAction.CANCEL_UNDELIVERED);
        return views;
    }

    /** 17 BR-16: {@link ShippingAddressChanges#change}. */
    public OrderSummary changeShippingAddress(UUID orderId, UUID shippingAddressId, UUID by) {
        OrderSummary order = addressChanges.change(orderId, shippingAddressId, by);
        onBehalf.record(orderId, by, OnBehalfAction.CHANGE_SHIPPING_ADDRESS);
        return order;
    }
}
