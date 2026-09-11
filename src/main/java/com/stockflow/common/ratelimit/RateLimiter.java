package com.stockflow.common.ratelimit;

import java.time.Duration;

/**
 * Decides whether one more request is allowed right now.
 *
 * <h2>Token bucket, not a fixed window</h2>
 *
 * <p>A fixed window ("100 per minute", counter reset on the minute) is simpler and has a well-known
 * hole: a client sends 100 at 10:00:59 and 100 more at 10:01:00, so 200 land in one second while
 * every window stays within its limit. A token bucket refills continuously, so the burst is bounded
 * by the bucket's capacity no matter where the clock happens to be.</p>
 *
 * <p>The bucket also gives something a fixed window cannot: a legitimate client that has been idle
 * accumulates tokens and may burst briefly, which is usually what you want — the limit exists to
 * stop sustained abuse, not to punish a user who opens five tabs at once.</p>
 */
public interface RateLimiter {

    /**
     * Take one token if the bucket has one.
     *
     * @param bucketKey identifies the caller and the endpoint budget
     * @param capacity  maximum tokens, which is also the largest burst allowed
     * @param window    how long a full bucket takes to refill from empty
     */
    Decision tryAcquire(String bucketKey, int capacity, Duration window);

    /**
     * @param allowed   whether the request may proceed
     * @param remaining tokens left, for the {@code X-RateLimit-Remaining} header
     * @param retryAfter how long to wait before the next token appears; zero when allowed
     */
    record Decision(boolean allowed, int remaining, Duration retryAfter) {

        public static Decision allowed(int remaining) {
            return new Decision(true, remaining, Duration.ZERO);
        }

        public static Decision denied(Duration retryAfter) {
            return new Decision(false, 0, retryAfter);
        }

        /**
         * The answer when the limiter itself is unavailable.
         *
         * <p><b>Fail open, deliberately.</b> If Redis is down, denying every request turns a
         * degraded dependency into a full outage — the rate limiter would be doing precisely the
         * damage it exists to prevent. Allowing means that during a Redis outage the limits are not
         * enforced, which is a real but much smaller risk, and one that is visible in the logs.
         * This is the opposite of the choice made for data scope, where the default is deny;
         * the difference is that a leaked row cannot be undone, while an unthrottled minute can.</p>
         */
        public static Decision failOpen() {
            return new Decision(true, -1, Duration.ZERO);
        }
    }
}
