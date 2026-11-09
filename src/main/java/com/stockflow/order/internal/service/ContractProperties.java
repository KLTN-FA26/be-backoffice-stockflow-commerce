package com.stockflow.order.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Defaults of a new sales contract (4B-4), bound from {@code stockflow.order.contract.*}.
 *
 * @param freeSampleRounds the print-sample rounds offered without charge (kltn-docs 14 BR-18, decided 2026-11-05: 2);
 *                         Sales changes it on the contract
 */
@ConfigurationProperties(prefix = "stockflow.order.contract")
public record ContractProperties(@DefaultValue("2") int freeSampleRounds) {

    public ContractProperties {
        if (freeSampleRounds < 0 || freeSampleRounds > 100) {
            throw new IllegalArgumentException("stockflow.order.contract.free-sample-rounds must be 0 to 100");
        }
    }
}
