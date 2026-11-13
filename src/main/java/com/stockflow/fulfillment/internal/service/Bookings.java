package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.security.CurrentUser;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Booking a load's vehicle, whichever way the carrier is booked (3-4; kltn-docs 09 §4.2 step 6, BR-29). A carrier booked
 * outside the system is recorded as it was ({@link Dispatching#book}); an API_BOOKING carrier is booked through its API:
 * the system creates the order - the warehouse the pickup, each shipment a drop-off in the order the dispatcher
 * confirmed - and the order id becomes the load's waybill number. Cancelling the booking cancels the order there.
 */
public interface Bookings {

    /**
     * @param pickupAt        API_BOOKING: when the driver should pick up; null: as soon as possible
     * @param stopShipmentIds API_BOOKING: the load's shipments in the order they are delivered (E26: the dispatcher
     *                        confirms it); null: the manifest's order
     */
    record Request(UUID carrierId, String vehiclePlate, String driverName, String driverPhone, String waybillNo,
                   Instant pickupAt, List<UUID> stopShipmentIds) { }

    /** A truck service of an API_BOOKING carrier in a city, to choose service codes from. */
    record Service(String id, String name, String cityId) { }

    /**
     * Books the load's vehicle. Through the API: no transaction is held while the carrier answers; a refusal leaves the
     * load PLANNED and names the carrier's reason.
     *
     * @throws com.stockflow.common.error.BusinessException besides {@link Dispatching#book}'s:
     *         422 {@code CARRIER_PICKUP_NOT_CONFIGURED}, {@code CARRIER_SERVICE_NOT_CONFIGURED},
     *         {@code CARRIER_STOP_OUT_OF_AREA} (E24), {@code CARRIER_BOOKING_FAILED} (the carrier refused);
     *         409 {@code CARRIER_BOOKING_IN_PROGRESS}; 502 {@code EXTERNAL_SERVICE_ERROR} (the carrier did not answer)
     */
    Dispatching.LoadDetail book(UUID loadId, Request request, CurrentUser actor);

    /**
     * Cancels the booking; for an order at the carrier, the carrier cancels it first.
     *
     * @throws com.stockflow.common.error.BusinessException besides {@link Dispatching#cancelBooking}'s: 409
     *         {@code CARRIER_CANCEL_REFUSED} (the driver already picked up), 502 {@code EXTERNAL_SERVICE_ERROR}
     */
    Dispatching.LoadDetail cancelBooking(UUID loadId, String reason, CurrentUser actor);

    /**
     * The carrier's truck services in a city, asked of its API.
     *
     * @throws com.stockflow.common.error.BusinessException 409 {@code CARRIER_NOT_API_BOOKING};
     *         {@code CARRIER_BOOKING_FAILED}; 502 {@code EXTERNAL_SERVICE_ERROR}
     */
    List<Service> services(UUID carrierId, String cityId);
}
