package com.stockflow.invoicing.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * SePay eInvoice's API and the account it is called with ({@code stockflow.invoicing.sepay.*}, the
 * {@code EINVOICE_SEPAY_*} environment variables).
 *
 * <p>Unlike Viettel's, this account is the deployment's configuration and not a row an administrator stores: SePay
 * hands a developer a {@code client_id} and a {@code client_secret} per environment, which belong with the other API
 * keys of a deployment. Which provider issues is still the administrator's choice at runtime
 * ({@code POST /api/v1/einvoice-settings/providers/SEPAY_EINVOICE/activation}); without both values configured that
 * call is refused.</p>
 *
 * @param environment       {@code SANDBOX} (SePay's test system: simulated data, nothing reaches the tax authority) or
 *                          {@code PRODUCTION}. It picks the address and is what SePay's keys were issued for: a sandbox
 *                          key does not sign in to production
 * @param clientId          the account's {@code client_id}; blank means not configured
 * @param clientSecret      the account's {@code client_secret}; blank means not configured. Never logged, never returned
 * @param providerAccountId which of the account's invoicing registrations ({@code GET /v1/provider-accounts}) issues;
 *                          blank takes the first active one SePay lists
 * @param sandboxUrl        SePay's sandbox host
 * @param productionUrl     SePay's production host
 * @param readTimeout       how long an answer is waited for. SePay takes an invoice in at once and works on it
 *                          afterwards, so a call is short
 * @param resubmitQuietPeriod how long after its last submission a transaction a look-up does not find is not submitted
 *                          again. SePay refuses a second invoice under one {@code reference_code}
 *                          ({@code EINVOICE_DOCUMENT_EXISTED}), but its documentation does not say whether that holds
 *                          while the first is still being processed, so a resend waits well past one whole call
 */
@ConfigurationProperties(prefix = "stockflow.invoicing.sepay")
public record SepayEInvoiceProperties(@DefaultValue("SANDBOX") String environment,
                                      String clientId,
                                      String clientSecret,
                                      String providerAccountId,
                                      @DefaultValue("https://einvoice-api-sandbox.sepay.vn") String sandboxUrl,
                                      @DefaultValue("https://einvoice-api.sepay.vn") String productionUrl,
                                      @DefaultValue("PT30S") Duration readTimeout,
                                      @DefaultValue("PT10M") Duration resubmitQuietPeriod) {

    public SepayEInvoiceProperties {
        environment = environment == null || environment.isBlank() ? "SANDBOX" : environment.strip();
        if (!"SANDBOX".equals(environment) && !"PRODUCTION".equals(environment)) {
            throw new IllegalArgumentException("stockflow.invoicing.sepay.environment is SANDBOX or PRODUCTION");
        }
        clientId = blankIsNull(clientId);
        clientSecret = blankIsNull(clientSecret);
        providerAccountId = blankIsNull(providerAccountId);
        if (readTimeout.isZero() || readTimeout.isNegative() || resubmitQuietPeriod.isNegative()) {
            throw new IllegalArgumentException("stockflow.invoicing.sepay: the read timeout must be positive");
        }
    }

    /** Whether there is something to sign in with. */
    public boolean configured() {
        return clientId != null && clientSecret != null;
    }

    public boolean production() {
        return "PRODUCTION".equals(environment);
    }

    public String baseUrl() {
        return production() ? productionUrl : sandboxUrl;
    }

    @Override
    public String toString() {
        // A record prints its components: this one must never print the secret.
        return "SepayEInvoiceProperties[environment=" + environment + ", configured=" + configured() + "]";
    }

    private static String blankIsNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
