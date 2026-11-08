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
 * off inside their own transactions, where no carrier can be called; this sweep cancels the order of every called-off
 * load afterwards, a few times if the carrier refuses (then the booking stays CANCEL_FAILED and the dispatcher settles it
 * with the carrier, as 09 says). It also gives up claims whose call never came back.
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
        tx.executeWithoutResult(s -> bookings.findAbandoned(now.minus(CarrierBookingService.ABANDONED_AFTER),
                PageRequest.of(0, BATCH)).forEach(b -> b.failed(
                "The call to the carrier never came back; check the carrier's portal")));
        List<CarrierBookingJpaEntity> due = tx.execute(s -> bookings.findToCancel(MAX_CANCEL_ATTEMPTS,
                PageRequest.of(0, BATCH)));
        for (CarrierBookingJpaEntity booking : due == null ? List.<CarrierBookingJpaEntity>of() : due) {
            cancel(booking);
        }
    }

    private void cancel(CarrierBookingJpaEntity booking) {
        String refused;
        try {
            CarrierJpaEntity carrier = tx.execute(s -> carriers.findById(booking.getCarrierId()).orElseThrow());
            gateway.cancel(secrets.caller(carrier, booking.getId()), booking.getCarrierOrderId());
            refused = null;
        } catch (CarrierBookingGateway.CarrierCallFailed failed) {
            refused = failed.getMessage();
        } catch (RuntimeException broken) {
            refused = broken.getClass().getSimpleName();
            log.error("Could not cancel order {} at its carrier", booking.getCarrierOrderId(), broken);
        }
        String outcome = refused;
        Instant now = clock.instant();
        tx.executeWithoutResult(s -> bookings.lockById(booking.getId()).ifPresent(b -> {
            if (outcome == null) {
                b.cancelled("The load was called off", now);
            } else {
                b.cancelRefused(outcome);
            }
        }));
        if (outcome == null) {
            log.info("Order {} of a called-off load cancelled at its carrier", booking.getCarrierOrderId());
        } else {
            log.warn("Order {} of a called-off load not cancelled at its carrier: {}", booking.getCarrierOrderId(),
                    outcome);
        }
    }
}
