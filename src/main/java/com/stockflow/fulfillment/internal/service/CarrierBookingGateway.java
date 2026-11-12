package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.internal.domain.CarrierApiEnvironment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A carrier's booking API, as dispatch needs it (3-4; kltn-docs 09 §4.2 step 6, BR-29): create the order for a
 * vehicle, cancel it, list the carrier's truck services in a city. One implementation per carrier API; Ahamove's is
 * {@link AhamoveGateway}.
 *
 * <p>Called with no transaction open: a carrier answering in five seconds must not hold a load's row lock, nor a
 * database connection, for five seconds. Every call is written to the carrier's call log whatever its outcome.</p>
 */
public interface CarrierBookingGateway {

    /**
     * Whom the call is made as. {@code apiKey} is in clear for the length of the call only.
     *
     * @param bookingId the order being booked or cancelled, for the call log; null when there is none
     */
    record Caller(UUID carrierId, UUID bookingId, CarrierApiEnvironment environment, String account, String apiKey) {

        /** Never prints the key. */
        @Override
        public String toString() {
            return "Caller[carrierId=" + carrierId + ", bookingId=" + bookingId + ", environment=" + environment
                    + ", account=" + account + "]";
        }
    }

    /**
     * One point of the route: the pickup first, then each drop-off.
     *
     * @param trackingNumber our reference for the point (a shipment's code), echoed back in the carrier's events
     */
    record Stop(String address, String name, String phone, String trackingNumber) { }

    /**
     * @param pickupAt null: as soon as possible
     * @param remarks  shown to the driver; carries the internal load code (09 §4.1 step 3)
     */
    record Order(String serviceCode, Instant pickupAt, List<Stop> route, String remarks) { }

    /** What the carrier created: its order id - the load's waybill number - and the link it tracks the order at. */
    record Placed(String orderId, String trackingLink) { }

    /** A truck service the carrier sells in a city. */
    record ServiceOption(String id, String name, String cityId) { }

    /**
     * @throws CarrierCallFailed when the carrier refused (with its reason) or did not answer
     */
    Placed place(Caller caller, Order order);

    /**
     * @throws CarrierCallFailed when the carrier refused - typically because the driver already picked up - or did not
     *         answer
     */
    void cancel(Caller caller, String orderId);

    /** @throws CarrierCallFailed when the carrier refused or did not answer */
    List<ServiceOption> services(Caller caller, String cityId);

    /**
     * An order as the carrier's API gives it now.
     *
     * @param status       the order's status, upper case ({@code ASSIGNING}, {@code IN PROCESS}, {@code COMPLETED},
     *                     {@code CANCELLED}, ...); null when the carrier gave none
     * @param subStatus    the order's sub-status, upper case; null when the carrier gave none
     * @param stopStatuses the status of each drop-off in the order they were booked in (the first drop-off first),
     *                     upper case, empty for one with none yet
     */
    record OrderState(String status, String subStatus, List<String> stopStatuses) {

        public OrderState {
            stopStatuses = stopStatuses == null ? List.of() : List.copyOf(stopStatuses);
        }

        /** The status of drop-off {@code stop} (1 = the first); null when the order has no such drop-off. */
        public String stopStatus(int stop) {
            return stop < 1 || stop > stopStatuses.size() ? null : stopStatuses.get(stop - 1);
        }
    }

    /**
     * The order as the carrier gives it now; empty only when the carrier says it knows no such order. Asked before a
     * final outcome an unsigned webhook reported is applied (WF-05 fix plan item 6).
     *
     * @throws CarrierCallFailed when the carrier refused, or did not answer - which includes being told to come back
     *         later (too many requests, a request timed out) and an answer with no order in it: none of those says
     *         anything about the order
     */
    java.util.Optional<OrderState> orderState(Caller caller, String orderId);

    /**
     * The status the carrier gives the order now ({@code ASSIGNING}, {@code CANCELLED}, ...); empty when it knows no
     * such order. Asked after a refused cancel, to tell an order already gone from one the driver picked up.
     *
     * @throws CarrierCallFailed when the carrier refused or did not answer
     */
    default java.util.Optional<String> orderStatus(Caller caller, String orderId) {
        return orderState(caller, orderId).map(OrderState::status);
    }

    /**
     * A call that did not do what was asked.
     *
     * @param answered false when the carrier's own answer did not come back - a timeout, a refused connection, a 5xx
     *                 (a proxy's error page, or the carrier failing after it may have acted): the carrier may or may
     *                 not have done what was asked
     * @param code     the carrier's error code when it gave one ({@code SERVICE_NOT_VALID_AT_PICKUP}, ...)
     */
    final class CarrierCallFailed extends RuntimeException {

        private final boolean answered;
        private final String code;

        public CarrierCallFailed(boolean answered, String code, String message) {
            super(message);
            this.answered = answered;
            this.code = code;
        }

        public boolean answered() {
            return answered;
        }

        public String code() {
            return code;
        }
    }
}
