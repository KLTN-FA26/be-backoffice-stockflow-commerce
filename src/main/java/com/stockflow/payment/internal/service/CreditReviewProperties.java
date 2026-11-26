package com.stockflow.payment.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What the review of a credit request warns about ({@code stockflow.payment.credit}; plan §4.4, owner decision
 * 2026-11-26 §5 question 4). Warnings only: nothing here refuses a request - the accountant decides.
 *
 * @param minSettledContracts below this many settled contracts the review warns that the customer has too little
 *                            history ({@code min-settled-contracts}, 2)
 * @param suggestedMonths     the suggested limit is this many months of what the customer paid a month on average over
 *                            the last twelve ({@code suggested-months}, 1)
 */
@ConfigurationProperties(prefix = "stockflow.payment.credit")
public record CreditReviewProperties(@DefaultValue("2") int minSettledContracts, @DefaultValue("1") int suggestedMonths) {

    @ConstructorBinding
    public CreditReviewProperties {
        if (minSettledContracts < 0 || suggestedMonths < 1) {
            throw new IllegalArgumentException("stockflow.payment.credit: min-settled-contracts is 0 or more and "
                    + "suggested-months 1 or more");
        }
    }
}
