package com.stockflow.payment.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The bank feed (credit plan 2026-11-26 part C; {@code stockflow.payment.bank-feed}): SePay reads the company's bank
 * account and reports each incoming transfer, which becomes a receipt when it names one installment. Off by default;
 * every value comes from the environment ({@code BANK_FEED_*}), the same on a laptop and on the VPS.
 *
 * @param enabled       whether transfers are taken at all ({@code BANK_FEED_ENABLED}); off, the webhook answers 503
 *                      and the catch-up does nothing
 * @param accountNumber the account customers pay into ({@code BANK_FEED_ACCOUNT_NUMBER}, else
 *                      {@code COMPANY_BANK_ACCOUNT_NUMBER}); a transfer into another account waits for the Accountant.
 *                      Blank: every account SePay reports is taken
 * @param webhook       how SePay's webhook call is authenticated
 * @param api           SePay's transaction API, for the nightly catch-up
 */
@ConfigurationProperties(prefix = "stockflow.payment.bank-feed")
public record BankFeedProperties(@DefaultValue("false") boolean enabled, String accountNumber,
                                 @DefaultValue Webhook webhook, @DefaultValue Api api) {

    /**
     * SePay's webhook authentication (developer.sepay.vn, "Xác thực webhook"), one of two, set on my.sepay.vn when the
     * webhook is created:
     *
     * @param hmacSecret the HMAC-SHA256 secret key ({@code BANK_FEED_WEBHOOK_HMAC_SECRET}): SePay signs
     *                   {@code <timestamp>.<raw body>} into {@code X-SePay-Signature: sha256=<hex>}, with
     *                   {@code X-SePay-Timestamp}. SePay's recommended method; when set, it is the one checked
     * @param apiKey     the API key ({@code BANK_FEED_WEBHOOK_API_KEY}): {@code Authorization: Apikey <key>}; checked
     *                   only when no HMAC secret is set
     * @param maxSkew    how far a signed call's timestamp may be from now (SePay's sample: five minutes)
     */
    public record Webhook(String hmacSecret, String apiKey, @DefaultValue("PT5M") Duration maxSkew) {

        boolean hmac() {
            return hmacSecret != null && !hmacSecret.isBlank();
        }

        boolean key() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    /**
     * SePay's API v2 ({@code GET /transactions}), which the nightly catch-up reads for transfers the webhook missed.
     *
     * @param baseUrl     {@code https://userapi.sepay.vn/v2}, or {@code https://userapi-sandbox.sepay.vn/v2} with a
     *                    token made in SePay's Test mode ({@code BANK_FEED_API_BASE_URL})
     * @param token       the API token ({@code BANK_FEED_API_TOKEN}); blank, the catch-up only retries what is stranded
     * @param readTimeout how long one page may take
     * @param lookback    how far back each run reads: a few days, so a missed night is caught up
     */
    public record Api(@DefaultValue("https://userapi.sepay.vn/v2") String baseUrl, String token,
                      @DefaultValue("PT20S") Duration readTimeout, @DefaultValue("P3D") Duration lookback) {

        boolean configured() {
            return token != null && !token.isBlank() && baseUrl != null && !baseUrl.isBlank();
        }
    }
}
