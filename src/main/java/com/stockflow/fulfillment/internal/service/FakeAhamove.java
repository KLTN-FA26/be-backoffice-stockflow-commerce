package com.stockflow.fulfillment.internal.service;

import java.time.Instant;
import java.util.List;

/**
 * Drives the fake Ahamove ({@code stockflow.fulfillment.ahamove.mode=fake}) through what Ahamove does to an order once
 * it is placed: finds a driver, the driver picks up, drops off or fails, the order completes or is cancelled. Each step
 * is reported to the system's own webhook pipeline as Ahamove's callback would be, and what the pipeline made of it is
 * handed back - so a demo shows the log's result (APPLIED, NEEDS_REVIEW, ...) of every step.
 */
public interface FakeAhamove {

    /**
     * What happens next to an order. The order's own state decides which steps are possible, as Ahamove's does: a step
     * out of turn is refused.
     */
    enum Step {
        /** The order looks for a driver (the first callback); after ACCEPT, the driver gives it back. */
        ASSIGNING,
        /** A driver takes it: the callback carries the name and an opaque supplier identifier. */
        ACCEPT,
        /** The driver picks up at the warehouse (IN PROCESS). Only once the load has left the warehouse. */
        PICKUP,
        /** The vehicle is on its way (IN PROCESS, COMPLETING). */
        EN_ROUTE,
        /** A drop-off is completed: the delivery. */
        DELIVER,
        /** A drop-off fails, with a reason. */
        FAIL,
        /** The order completes, once every drop-off is settled. */
        COMPLETE,
        /** Failed goods are on their way back (COMPLETED, IN_RETURN). */
        RETURNING,
        /** The goods are back (COMPLETED, RETURNED). */
        RETURNED,
        /** Ahamove cancels the order itself (no driver, or the driver gave it up for good): before pickup only. */
        CARRIER_CANCEL
    }

    /**
     * Optional details of a step.
     *
     * @param stop         DELIVER, FAIL: the drop-off (1 = the first); null: the next one not settled
     * @param reason       FAIL: Ahamove's wording for the failure (default CUSTOMER_NOT_AVAILABLE); CARRIER_CANCEL: why
     * @param driverName   ACCEPT: default "Nguyen Van Tai (fake driver)"
     * @param driverPhone  ACCEPT: default 0912345678
     * @param vehiclePlate ACCEPT: the plate, kept on the fake order only - Ahamove's callback has none
     */
    record Details(Integer stop, String reason, String driverName, String driverPhone, String vehiclePlate) {

        public static Details none() {
            return new Details(null, null, null, null, null);
        }
    }

    /** One drop-off (or the pickup, as stop 0) of an order. */
    record StopView(int stop, String shipmentCode, String address, String status, String failComment) { }

    /** An order as the fake holds it. */
    record OrderView(String orderId, String carrierCode, String status, String subStatus, String serviceCode,
                     String remarks, String trackingLink, String driverName, String driverPhone, String vehiclePlate,
                     Instant createdAt, List<StopView> stops) { }

    /** What the system made of one callback: the webhook event it kept and the result it decided. */
    record Callback(String step, String carrierOrderRef, String eventId, String result, String resultNote) { }

    /** The order after the steps, and the callbacks they sent. */
    record Played(OrderView order, List<Callback> callbacks) { }

    List<OrderView> orders();

    /** @throws com.stockflow.common.error.BusinessException 404 {@code NOT_FOUND}: an order the fake does not hold */
    OrderView order(String orderId);

    /** One step. @throws com.stockflow.common.error.BusinessException 400 for a step the order cannot take now */
    Played send(String orderId, Step step, Details details);

    /**
     * Every step still to come, in Ahamove's order: a driver (if none), pickup, on its way, each unsettled drop-off
     * delivered - or failed, when {@code deliver} is false - and the order completed (a returned order when a drop-off
     * failed). Stops at the first step the system's pipeline did not APPLY, so a load that has not left the warehouse
     * shows where it stopped instead of piling up errors.
     */
    Played play(String orderId, boolean deliver, Details details);
}
