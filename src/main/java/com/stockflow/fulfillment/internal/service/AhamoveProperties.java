package com.stockflow.fulfillment.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where Ahamove's API is and what dispatch tells it (3-4; Ahamove API v3), bound from
 * {@code stockflow.fulfillment.ahamove}. The account and key are a carrier's own settings (sealed on the carrier row),
 * not configuration: a deployment may hold a staging and a production carrier at once.
 *
 * @param stagingUrl    the API of Ahamove's staging environment
 * @param productionUrl the API of Ahamove's production environment
 * @param cancelComment the reason sent when an order is cancelled; Ahamove takes only reasons from its own list, so it
 *                      is configuration (application.yml), not code
 */
@ConfigurationProperties(prefix = "stockflow.fulfillment.ahamove")
public record AhamoveProperties(@DefaultValue("https://partner-apistg.ahamove.com") String stagingUrl,
                                @DefaultValue("https://partner-api.ahamove.com") String productionUrl,
                                String cancelComment) {

    public AhamoveProperties {
        if (cancelComment == null || cancelComment.isBlank()) {
            throw new IllegalArgumentException("stockflow.fulfillment.ahamove.cancel-comment is required");
        }
    }
}
