package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.internal.entity.CarrierBookingJpaEntity;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierBookingJpaRepository;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Settles orders at carriers that no load wants any more (3-4; kltn-docs 09 BR-29: "mở lại kế hoạch, huỷ có kiểm soát
 * ... → hệ thống huỷ đơn ở hãng"). Reopening or cancelling a plan, a controlled cancel and dropping a vehicle call loads
 * off inside their own transactions, where no carrier can be called, and a shipment taken off a booked vehicle puts it
 * back to PLANNED; this sweep cancels the order of every such load afterwards, a few times if the carrier refuses (then
 * the booking stays CANCEL_FAILED and the dispatcher settles it with the carrier, as 09 says). A claim whose call never
 * came back is made UNCONFIRMED: it may have created an order, so it keeps its load until someone checked.
 *
 * <p>Each order is cancelled with no transaction open, then its outcome written in a short one.</p>
 */
@Component
public class CarrierBookingSweeper {

    static final int MAX_CANCEL_ATTEMPTS = 5;
    private static final int BATCH = 20;
    private static final Logger log = LoggerFactory.getLogger(CarrierBookingSweeper.class);

    private final CarrierBookingJpaRepository bookings;
    private final CarrierJpaRepository carriers;
    private final CarrierSecrets secrets;
    private final CarrierBookingGateway gateway;
    private final TransactionOperations tx;
    private final Clock clock;

    public CarrierBookingSweeper(CarrierBookingJpaRepository bookings, CarrierJpaRepository carriers,
                                 CarrierSecrets secrets, CarrierBookingGateway gateway, TransactionOperations tx,
                                 Clock clock) {
        this.bookings = bookings;
        this.carriers = carriers;
        this.secrets = secrets;
        this.gateway = gateway;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${stockflow.fulfillment.carrier-booking-sweep-interval:PT1M}")
    @SchedulerLock(name = "fulfillment.settleCarrierBookings", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    public void settle() {
        Instant now = clock.instant();
        // A claim whose call never came back may have made an order: unconfirmed, it keeps the load until checked.
        tx.executeWithoutResult(s -> bookings.findAbandoned(now.minus(CarrierBookingService.ABANDONED_AFTER),
                PageRequest.of(0, BATCH)).forEach(b -> b.unconfirmed(CarrierBookingService.unconfirmed(
                "the call never came back", "the load's code"))));
        List<CarrierBookingJpaEntity> due = tx.execute(s -> bookings.findToCancel(MAX_CANCEL_ATTEMPTS,
                PageRequest.of(0, BATCH)));
        for (CarrierBookingJpaEntity booking : due == null ? List.<CarrierBookingJpaEntity>of() : due) {
            cancel(booking);
        }
    }

    /**
     * One order: cancelled at the carrier with nothing open, then the outcome written under the booking's lock - unless
     * something settled it meanwhile. Only the carrier's own refusal counts towards {@link #MAX_CANCEL_ATTEMPTS}, and
     * only after the carrier is asked whether it still has the order: an order it already dropped is cancelled. No
     * answer is tried again on the next sweep, however long the carrier is away.
     */
    private void cancel(CarrierBookingJpaEntity booking) {
        String refused = null;
        boolean answered = true;
        try {
            CarrierJpaEntity carrier = tx.execute(s -> carriers.findById(booking.getCarrierId()).orElseThrow());
            CarrierBookingGateway.Caller caller = secrets.caller(carrier, booking.getId());
            try {
                gateway.cancel(caller, booking.getCarrierOrderId());
            } catch (CarrierBookingGateway.CarrierCallFailed failed) {
                answered = failed.answered();
                if (!failed.answered() || !gone(caller, booking.getCarrierOrderId())) {
                    refused = failed.getMessage();
                }
            }
        } catch (RuntimeException broken) {
            refused = broken.getClass().getSimpleName();
            log.error("Could not cancel order {} at its carrier", booking.getCarrierOrderId(), broken);
        }
        String outcome = refused;
        boolean heard = answered;
        Instant now = clock.instant();
        tx.executeWithoutResult(s -> bookings.lockById(booking.getId())
                .filter(b -> CarrierBookingJpaEntity.BOOKED.equals(b.getStatus())
                        || CarrierBookingJpaEntity.CANCEL_FAILED.equals(b.getStatus()))
                .ifPresent(b -> {
                    if (outcome == null) {
                        b.cancelled("The load no longer needs it", now);
                    } else if (heard) {
                        b.cancelRefused(outcome);
                    } else {
                        b.cancelUnanswered(outcome);
                    }
                }));
        if (outcome == null) {
            log.info("Order {} of a load that no longer needs it cancelled at its carrier",
                    booking.getCarrierOrderId());
        } else {
            log.warn("Order {} of a load that no longer needs it not cancelled at its carrier ({}): {}",
                    booking.getCarrierOrderId(), heard ? "refused" : "no answer", outcome);
        }
    }

    /** Whether the carrier has no such order any more, or holds it cancelled; unknown counts as no. */
    private boolean gone(CarrierBookingGateway.Caller caller, String orderId) {
        try {
            return gateway.orderStatus(caller, orderId).map("CANCELLED"::equals).orElse(true);
        } catch (CarrierBookingGateway.CarrierCallFailed unknown) {
            return false;
        }
    }
}
