package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.internal.domain.CarrierEventResult;

import java.time.Duration;
import java.time.Instant;

/**
 * The span of carrier times an unsigned webhook's report is applied within (WF-05 fix plan item 6): not longer ago than
 * {@code maxAge} - a callback recorded once and sent again later is not applied - and not later than now by more than
 * {@code maxFuture}. A report from the future is worse than an old one: once applied, its date makes every true report
 * after it look outdated (BR-27 compares carrier times), so it goes to the error list rather than to the log only.
 *
 * <p>{@code maxFuture} is a clock's allowance, and what it allows is a risk accepted (WF-05 review F5): a forged
 * milestone dated that far ahead makes a true report arriving within those minutes look outdated, once per load (a
 * milestone is recorded once). Keep it as small as the carrier's clock permits.</p>
 *
 * <p>A report the carrier gives no time for is not judged here; the final outcomes among those are asked back of the
 * carrier's API instead.</p>
 */
record CarrierReportWindow(Duration maxAge, Duration maxFuture) {

    /** Why a report is not applied, and as what. */
    record Outside(CarrierEventResult result, String note) { }

    /** @return null when the report is inside the window or carries no time */
    Outside judge(Instant reportedAt, Instant now) {
        if (reportedAt == null) {
            return null;
        }
        if (reportedAt.isBefore(now.minus(maxAge))) {
            return new Outside(CarrierEventResult.STALE, "Reported for %s, more than %s ago: outside the time window"
                    .formatted(reportedAt, maxAge));
        }
        if (reportedAt.isAfter(now.plus(maxFuture))) {
            return new Outside(CarrierEventResult.NEEDS_REVIEW, ("Reported for %s, which has not come yet: outside the "
                    + "time window").formatted(reportedAt));
        }
        return null;
    }
}
