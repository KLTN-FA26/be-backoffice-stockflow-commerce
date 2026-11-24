package com.stockflow.order.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Defaults of a new sales contract (4B-4) and the standing terms its paper and the quotation before it print, bound
 * from {@code stockflow.order.contract.*}.
 *
 * <p>The three terms are configuration, not constants, because none of them is law: they are what this company offers
 * every customer until a contract says otherwise, and a company that prints to order changes them with its presses and
 * its suppliers.</p>
 *
 * @param freeSampleRounds         the print-sample rounds offered without charge (kltn-docs 14 BR-18, decided
 *                                 2026-11-05: 2); Sales changes it on the contract
 * @param quantityTolerancePercent how far the delivered quantity of a printed run may differ from the ordered one,
 *                                 either way, in percent; 0 prints no tolerance clause. A press run cannot be stopped
 *                                 on an exact count; Vietnamese print contracts write 5 to 10, so more than 10 is
 *                                 refused as a typing mistake
 * @param warrantyMonths           how long the print is warranted after delivery, in months; 0 prints no warranty
 * @param penaltyPercent           the penalty for a breach, in percent of the part of the obligation breached. Luật
 *                                 Thương mại 2005 Điều 301 caps it at 8: a contract printing more would carry a clause
 *                                 a court strikes down, so more is refused at startup
 */
@ConfigurationProperties(prefix = "stockflow.order.contract")
public record ContractProperties(@DefaultValue("2") int freeSampleRounds,
                                 @DefaultValue("5") int quantityTolerancePercent,
                                 @DefaultValue("6") int warrantyMonths,
                                 @DefaultValue("8") int penaltyPercent) {

    /** The cap of Luật Thương mại 2005 Điều 301. */
    static final int PENALTY_CAP_PERCENT = 8;

    public ContractProperties {
        if (freeSampleRounds < 0 || freeSampleRounds > 100) {
            throw new IllegalArgumentException("stockflow.order.contract.free-sample-rounds must be 0 to 100");
        }
        if (quantityTolerancePercent < 0 || quantityTolerancePercent > 10) {
            throw new IllegalArgumentException("stockflow.order.contract.quantity-tolerance-percent must be 0 to 10");
        }
        if (warrantyMonths < 0 || warrantyMonths > 60) {
            throw new IllegalArgumentException("stockflow.order.contract.warranty-months must be 0 to 60");
        }
        if (penaltyPercent < 0 || penaltyPercent > PENALTY_CAP_PERCENT) {
            throw new IllegalArgumentException("stockflow.order.contract.penalty-percent must be 0 to "
                    + PENALTY_CAP_PERCENT + " (Luật Thương mại 2005, Điều 301)");
        }
    }
}
