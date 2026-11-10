package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.label.ShippingLabelSheet;
import com.stockflow.common.security.CurrentUser;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The carton check and labelling station (team plan 4C-6; kltn-docs 08): the queue of picked orders waiting at the
 * dispatch area, the check session of one task - scan each carton's LPN, label it with its carton spec's size and
 * weight, or remove it when damaged - and the shipping labels themselves. Completing is
 * {@code FulfillmentService.completePacking}: the labelled cartons become one shipment.
 *
 * <p>Inside {@code internal} on purpose, like {@link PickLists}: only this module's web layer calls it.</p>
 *
 * <p>Every write locks the task's pick first, so two workers on one order are serialised and the second sees what the
 * first did; only the worker who started the session, or a warehouse manager ({@code fulfillment-packing:APPROVE}),
 * works it ({@code PACKING_TAKEN}, 08 BR-06). Every call is limited to the caller's warehouses
 * ({@code OUT_OF_DATA_SCOPE}). Every carton is named by its LPN, in any case.</p>
 */
public interface CartonChecks {

    /**
     * Picked tasks waiting at the check station, oldest pick first (08 §4.1 step 1): their session PENDING or
     * IN_PROGRESS, or the one status asked for, and their order one the station may work on (not on hold). Limited to
     * the caller's warehouses and to {@code warehouseId}.
     *
     * @throws com.stockflow.common.error.BusinessException {@code OUT_OF_DATA_SCOPE}; {@code UNSUPPORTED_PARAMETER}
     */
    PageResponse<QueueRow> queue(UUID warehouseId, String status, int page, int size, CurrentUser actor);

    /**
     * A worker starts the order's check (08 §4.1 step 2, §5.1 Pending → In Progress): the cartons the task picked are
     * TO_CHECK. Again, answered as it is for that worker or a manager.
     *
     * @throws com.stockflow.common.error.BusinessException {@code PACKING_NOT_READY} until picking is complete (a task
     *         still STAGED included, 08 BR-01); {@code PACKING_TAKEN}; {@code PACK_STATE_CONFLICT} on hold or cancelled
     */
    Session start(UUID taskId, CurrentUser actor);

    /** The session as it stands, with the cartons the task picked that it has not received yet as TO_CHECK. */
    Session session(UUID taskId, CurrentUser actor);

    /**
     * The LPN of a carton is scanned (08 §4.2 step 3): a carton picked for this task becomes CHECKED; scanned again,
     * nothing changes (BR-05).
     *
     * @throws com.stockflow.common.error.BusinessException {@code CARTON_NOT_IN_TASK} for any other LPN (08 edge:
     *         refused, the warehouse manager checks); {@code PACK_STATE_CONFLICT} for a removed carton or a session not
     *         in progress; {@code PACKING_TAKEN}
     */
    Session check(UUID taskId, String lpn, CurrentUser actor);

    /**
     * Shipping labels for the cartons named, or for every CHECKED carton when none is (08 §4.3 steps 5-6): each takes
     * its SKU's carton-spec size, full weight and may-rotate flag, and the volumetric weight; it is LABELED. The first
     * label of the order locks it: no cancellation, no change of delivery address (08 BR-04, 17 BR-12, BR-16). Print the
     * labels with {@link #labels}. A retry labels nothing more and answers the session as it is
     * ({@link com.stockflow.fulfillment.internal.domain.CartonCheck#label}).
     *
     * @throws com.stockflow.common.error.BusinessException {@code CARTON_NOT_CHECKED}; {@code CARTON_SPEC_UNMEASURED}
     *         naming each SKU whose carton spec has no size or weight (BR-07); {@code CARTON_NOT_IN_TASK};
     *         {@code PACK_STATE_CONFLICT}; {@code PACKING_TAKEN}
     */
    Session label(UUID taskId, List<String> lpns, CurrentUser actor);

    /**
     * A label torn or lost is printed again (08 §4.3 step 7, BR-08): the carton's label version goes up, voiding the
     * old one, and its measures follow the carton spec unless a load plan or a vehicle holds it. Also once the session
     * is completed, until the carton leaves; only by the worker who started the session or a warehouse manager (BR-06).
     *
     * @param fromVersion the version on the label being replaced: a retry, the carton past it already, changes nothing;
     *                    null always reprints
     * @throws com.stockflow.common.error.BusinessException {@code CARTON_NOT_CHECKED} unless the carton is LABELED;
     *         {@code CARTON_NOT_IN_TASK}; {@code PACK_STATE_CONFLICT} while PENDING or CANCELLED; {@code PACKING_TAKEN}
     */
    Session reprint(UUID taskId, String lpn, Integer fromVersion, CurrentUser actor);

    /**
     * A carton damaged outside is removed from the order (08 §4.2 step 4): REMOVED, its label void if it had one. It
     * waits in the put-back queue ({@code GET /fulfillment/put-backs}, {@code quarantine = true}) to go whole into a
     * quarantine area. What it leaves missing is completed in part, if the order allows, or handled as a shortage.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CARTON_NOT_IN_TASK}; {@code PACKAGE_SHIPPED} for one
     *         in a shipment; {@code PACK_STATE_CONFLICT}; {@code PACKING_TAKEN}
     */
    Session remove(UUID taskId, String lpn, String note, CurrentUser actor);

    /**
     * What the shipping labels of the cartons named - or of every labelled carton not shipped yet when none is - print,
     * at their current version, in carton order. Changes nothing: a reprint is {@link #reprint}.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CARTON_NOT_CHECKED} for a carton not labelled, or
     *         nothing to print; {@code CARTON_NOT_IN_TASK}
     */
    List<ShippingLabelSheet.ShippingLabel> labels(UUID taskId, List<String> lpns, CurrentUser actor);

    // ------------------------------------------------------------------ views

    /**
     * @param cartonCount  the cartons the session holds, not removed
     * @param checkedCount of them, scanned (CHECKED or LABELED)
     * @param labeledCount of them, labelled
     */
    record QueueRow(UUID taskId, UUID orderId, String orderNumber, UUID warehouseId, String status, Instant pickedAt,
                    String pickerName, UUID startedBy, String startedByName, Instant startedAt, int cartonCount,
                    int checkedCount, int labeledCount, int shipmentCount) { }

    /**
     * One order line at the station, in units and cartons.
     *
     * @param wantedUnits  what the order still wants of it: its quantity less what was closed
     * @param labeledUnits units in its labelled cartons, shipped or not
     * @param missingUnits {@code wantedUnits - labeledUnits}, not below zero: what a partial completion leaves behind
     */
    record Line(UUID orderLineId, String sku, int wantedUnits, int cartons, int checkedCartons, int labeledCartons,
                int removedCartons, int shippedCartons, int labeledUnits, int missingUnits) { }

    /**
     * One shipping carton. {@code packageId} is null for a carton the task picked that the session has not received
     * yet (read before the session started).
     */
    record Carton(UUID packageId, int packageNo, String labelCode, String lpn, UUID pickLineId, UUID orderLineId,
                  String sku, String lotNumber, int quantity, String status, Instant checkedAt, Instant labeledAt,
                  int labelVersion, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm, BigDecimal weightKg,
                  BigDecimal dimWeightKg, boolean rotatable, UUID shipmentId, String removalReason,
                  String removalNote) { }

    record ShipmentSummary(UUID shipmentId, String code, String status, int packageCount, boolean partial,
                           String partialNote, Instant readyAt) { }

    /**
     * What the contract's installments due before delivery still owe (kltn-docs 15 BR-08) - or, for an order without a
     * contract, what its own terms want before the goods leave: the warning at the station (08 §4.4 step 9, BR-11). It
     * never blocks the check; the handover is refused until it is paid (09).
     */
    record PaymentDue(BigDecimal amount, String currency) { }

    /**
     * @param orderLocked            a carton of the order is labelled: it is no longer cancelled and its delivery
     *                               address does not change (08 BR-04)
     * @param skusWithoutCartonSpec  SKUs of cartons still to label whose carton spec has no size or weight: no label
     *                               prints for them until a manager completes it (08 BR-07)
     */
    record Session(UUID taskId, UUID orderId, String orderNumber, UUID warehouseId, String status, UUID startedBy,
                   String startedByName, Instant startedAt, Instant completedAt, boolean orderLocked,
                   List<Line> lines, List<Carton> cartons, List<ShipmentSummary> shipments, PaymentDue paymentDue,
                   List<String> skusWithoutCartonSpec) { }
}
