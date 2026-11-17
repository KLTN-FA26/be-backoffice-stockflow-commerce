package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.internal.domain.CarrierEventKind;
import com.stockflow.fulfillment.internal.domain.CarrierReport;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Asks Ahamove back before a final outcome its callback reported is applied (WF-05 fix plan item 6). The callback is
 * not signed - a fixed header value is all that tells Ahamove from anyone who learnt it - so what cannot be undone
 * quietly (a drop-off delivered or failed, the order cancelled, completed or returned) is applied only when
 * {@code GET /v3/orders/{id}}, called with the carrier's own key, gives the same.
 *
 * <p><b>What is not asked back, and so rests on the header credential alone</b> (a risk accepted, WF-05 review F5):
 * journey milestones (picked up, on its way, returning) and the driver's name and phone - nothing an order, an invoice
 * or stock follows from, but the name and phone are what the warehouse reads when the vehicle arrives, so whoever holds
 * the credential can put a wrong driver on a booked load; and, of a final outcome that is borne out, its time and the
 * wording of a failure. Those are limited by the time window, the allow-list and the credential's rotation.</p>
 *
 * <p>Called with no transaction open, like every carrier call. An API that does not answer is not a verdict: the
 * callback is left to be sent again ({@link CarrierWebhookRetry}).</p>
 */
@Component
class AhamoveOutcomeVerifier {

    private static final Logger log = LoggerFactory.getLogger(AhamoveOutcomeVerifier.class);

    private final CarrierBookingGateway gateway;
    private final CarrierSecrets secrets;
    private final boolean enabled;

    AhamoveOutcomeVerifier(CarrierBookingGateway gateway, CarrierSecrets secrets, AhamoveProperties properties) {
        this.gateway = gateway;
        this.secrets = secrets;
        this.enabled = properties.webhook().verifyOutcomes();
    }

    /** Whether the report is one that is asked back: a final outcome, with the check switched on. */
    boolean concerns(CarrierEventApplier.Matched matched) {
        if (!enabled) {
            return false;
        }
        return switch (matched.report().kind()) {
            case DELIVERED, DELIVERY_FAILED, ORDER_CANCELLED, RETURNED -> true;
            // A completed order with no outcome of a drop-off in it changes nothing.
            case ORDER_COMPLETED -> !matched.stops().isEmpty();
            default -> false;
        };
    }

    /**
     * @return null when Ahamove's API gives what the callback reported; otherwise what did not agree, for the event's
     *         note - the callback is then UNVERIFIED and not applied
     * @throws CarrierWebhookRetry when the API did not answer: nothing was learnt, the carrier is asked to send the
     *         callback again
     */
    String verify(CarrierJpaEntity carrier, CarrierEventApplier.Matched matched) {
        String orderId = matched.carrierOrderId();
        Optional<CarrierBookingGateway.OrderState> state;
        try {
            state = gateway.orderState(secrets.caller(carrier, matched.bookingId()), orderId);
        } catch (CarrierBookingGateway.CarrierCallFailed failed) {
            if (!failed.answered()) {
                throw new CarrierWebhookRetry(("The carrier's API did not answer when asked about order %s: the "
                        + "carrier is asked to send it again").formatted(orderId));
            }
            return "The carrier's API refused to say what order %s is (%s)".formatted(orderId, failed.getMessage());
        } catch (RuntimeException unopened) {
            // No secrets key on this instance, or a carrier whose API settings are gone: nothing can be asked.
            log.error("Carrier {}: order {} could not be asked back ({})", carrier.getCode(), orderId,
                    unopened.getClass().getSimpleName());
            return "The carrier's API could not be asked about order %s on this server".formatted(orderId);
        }
        if (state.isEmpty()) {
            return "The carrier's API knows no order %s".formatted(orderId);
        }
        return mismatch(matched.report(), matched.stops(), state.get());
    }

    /**
     * What in {@code state} - the order as the carrier's API gives it now - does not bear out the callback; null when
     * it is borne out.
     *
     * @param stops the outcomes of drop-offs a completed order's callback carries
     */
    static String mismatch(CarrierReport report, List<CarrierReport> stops, CarrierBookingGateway.OrderState state) {
        return switch (report.kind()) {
            case DELIVERED, DELIVERY_FAILED -> stop(report, state);
            case ORDER_CANCELLED -> order(report.reference(), "CANCELLED", state);
            case RETURNED -> {
                String order = order(report.reference(), "COMPLETED", state);
                // A callback is read as "returned" only from COMPLETED / RETURNED, so the API must give both: a
                // completed order with no sub-status is an ordinary completion, not the goods back.
                yield order != null ? order : "RETURNED".equals(state.subStatus()) ? null
                        : "The callback says order %s is returned; the carrier's API says %s".formatted(
                                report.reference(), state.subStatus() == null ? "nothing of a return"
                                        : state.subStatus());
            }
            case ORDER_COMPLETED -> {
                String order = order(report.reference(), "COMPLETED", state);
                if (order != null) {
                    yield order;
                }
                for (CarrierReport stop : stops) {
                    String differs = stop(stop, state);
                    if (differs != null) {
                        yield differs;
                    }
                }
                yield null;
            }
            default -> null;
        };
    }

    private static String order(String orderId, String expected, CarrierBookingGateway.OrderState state) {
        return expected.equals(state.status()) ? null
                : "The callback says order %s is %s; the carrier's API says %s".formatted(orderId, expected,
                        state.status() == null ? "nothing" : state.status());
    }

    private static String stop(CarrierReport report, CarrierBookingGateway.OrderState state) {
        String expected = report.kind() == CarrierEventKind.DELIVERED ? "COMPLETED" : "FAILED";
        String given = report.stop() == null ? null : state.stopStatus(report.stop());
        return expected.equals(given) ? null
                : "The callback says drop-off %s of order %s is %s; the carrier's API says %s".formatted(report.stop(),
                        report.reference(), expected, given == null || given.isEmpty() ? "nothing" : given);
    }
}
