package com.stockflow.fulfillment.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Where Ahamove's API is and what dispatch tells it (3-4; Ahamove API v3), bound from
 * {@code stockflow.fulfillment.ahamove}. The account and key are a carrier's own settings (sealed on the carrier row),
 * not configuration: a deployment may hold a staging and a production carrier at once.
 *
 * @param stagingUrl    the API of Ahamove's staging environment
 * @param productionUrl the API of Ahamove's production environment
 * @param cancelComment the reason sent when an order is cancelled; Ahamove takes only reasons from its own list, so it
 *                      is configuration (application.yml), not code
 * @param webhook       how its callbacks are defended, since it does not sign them
 */
@ConfigurationProperties(prefix = "stockflow.fulfillment.ahamove")
public record AhamoveProperties(@DefaultValue("https://partner-apistg.ahamove.com") String stagingUrl,
                                @DefaultValue("https://partner-api.ahamove.com") String productionUrl,
                                String cancelComment,
                                Webhook webhook) {

    /**
     * Ahamove's callback carries a fixed header value and no signature of its body, so whoever learns the value can
     * write any callback (WF-05 fix plan item 6). Three defences, each configurable:
     *
     * @param verifyOutcomes a final outcome (a drop-off delivered or failed, the order cancelled, completed or
     *                       returned) is applied only once Ahamove's own API, asked back, gives the same; off, a
     *                       callback is taken at its word
     * @param maxAge         a report the carrier dates longer ago than this is not applied (STALE)
     * @param maxFuture      a report dated later than now by more than this is not applied (NEEDS_REVIEW): applied, its
     *                       date would make every true report after it look outdated
     * @param allowedIps     the addresses and CIDR blocks its callbacks may come from; empty: any. Behind a proxy the
     *                       address seen is the proxy's unless the server is told to trust its forwarded header
     */
    public record Webhook(@DefaultValue("true") boolean verifyOutcomes,
                          @DefaultValue("PT24H") Duration maxAge,
                          @DefaultValue("PT5M") Duration maxFuture,
                          List<String> allowedIps) {

        public Webhook {
            if (maxAge == null || maxAge.isNegative() || maxAge.isZero()) {
                throw new IllegalArgumentException("stockflow.fulfillment.ahamove.webhook.max-age must be positive");
            }
            if (maxFuture == null || maxFuture.isNegative()) {
                throw new IllegalArgumentException("stockflow.fulfillment.ahamove.webhook.max-future must not be "
                        + "negative");
            }
            allowedIps = allowedIps == null ? List.of() : List.copyOf(allowedIps);
            // Fails the start-up on a list that would otherwise refuse - or allow - every callback by a typing mistake.
            IpAllowList.of(allowedIps);
        }

        /** Every defence at its default: outcomes verified, a day back and five minutes ahead, any address. */
        public static Webhook defaults() {
            return new Webhook(true, Duration.ofHours(24), Duration.ofMinutes(5), List.of());
        }
    }

    public AhamoveProperties {
        if (cancelComment == null || cancelComment.isBlank()) {
            throw new IllegalArgumentException("stockflow.fulfillment.ahamove.cancel-comment is required");
        }
        if (webhook == null) {
            webhook = Webhook.defaults();
        }
    }
}
