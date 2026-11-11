package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.id.Identifiers;
import com.stockflow.fulfillment.internal.entity.CarrierApiCallJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierApiCallJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * The carrier call log (kltn-docs 09 BR-29): one row per call, in a transaction of its own, so a call is on record
 * whatever happens to the work that made it. Never throws: a log that cannot be written is logged, not allowed to fail
 * the booking it describes.
 */
@Component
public class CarrierApiCalls {

    private static final Logger log = LoggerFactory.getLogger(CarrierApiCalls.class);

    /** What came of a call. */
    public enum Outcome { OK, REFUSED, NO_ANSWER }

    private final CarrierApiCallJpaRepository calls;
    private final Clock clock;

    public CarrierApiCalls(CarrierApiCallJpaRepository calls, Clock clock) {
        this.calls = calls;
        this.clock = clock;
    }

    /**
     * @param operation   TOKEN, SERVICES, BOOK or CANCEL
     * @param httpStatus  null when no answer came
     * @param requestBody never a body carrying a credential (a TOKEN call keeps none)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID carrierId, UUID bookingId, String operation, Outcome outcome, Integer httpStatus,
                       String requestBody, String responseBody, String error, long durationMs) {
        try {
            calls.saveAndFlush(new CarrierApiCallJpaEntity(Identifiers.newId(), carrierId, bookingId, operation,
                    outcome.name(), outcome == Outcome.NO_ANSWER ? null : httpStatus,
                    "TOKEN".equals(operation) ? null : requestBody, "TOKEN".equals(operation) ? null : responseBody,
                    error, (int) Math.min(durationMs, Integer.MAX_VALUE), clock.instant()));
        } catch (RuntimeException ex) {
            log.error("Could not log a {} call to carrier {} ({})", operation, carrierId, outcome, ex);
        }
    }
}
