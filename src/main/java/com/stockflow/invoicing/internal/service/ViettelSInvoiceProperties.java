package com.stockflow.invoicing.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Where Viettel SInvoice's API is and how it is called ({@code stockflow.invoicing.viettel.*}). The account - user name
 * and password - is not here: it is data, sealed in {@code invoicing.einvoice_provider_config}, set by the
 * administrator at runtime.
 *
 * <p>The addresses are a deployment's, never a person's input: an address typed into a settings screen would let the
 * application be pointed at any host with the account's password in the request.</p>
 *
 * @param productionUrl     the host of SInvoice's API v2. Viettel's public description names one host,
 *                          {@code https://api-vinvoice.viettel.vn}, for the log-in ({@code /auth/login}) and the
 *                          services ({@code /services/einvoiceapplication/api/...})
 * @param sandboxUrl        the host an account whose environment is SANDBOX is called at. The public description gives
 *                          no separate test host - a test <i>account</i> is issued on request and used against the
 *                          same one - so it defaults to the production host; a deployment given another address by
 *                          Viettel sets it here
 * @param readTimeout       how long an answer is waited for. Viettel asks an integrating system to wait for the answer
 *                          or at least 90 seconds before it sends a transaction again, so this is far above the
 *                          application's usual outbound timeout and must not be set below {@code resubmitQuietPeriod}
 * @param resubmitQuietPeriod how long after a submission the same transaction is not submitted again, even when a
 *                          look-up does not find it: Viettel does not stop two submissions of one transaction id that
 *                          overlap, and may issue two invoices (its description, "kiểm soát trùng giao dịch")
 * @param resubmitWindow    how long Viettel treats a transaction id as already used (three days in its description).
 *                          Past it nothing is submitted again automatically
 * @param filePause         how long is waited before an invoice's file is asked for again when SInvoice hands over an
 *                          empty one: it renders the file a few seconds after issuing ("gọi sau 2-5 giây")
 * @param paymentStatusPaid what {@code generalInvoiceInfo.paymentStatus} says. It is a required flag of SInvoice's own
 *                          (the invoice shows "paid"/"unpaid" on its portal) and no part of the invoice in law; the
 *                          system does not tie an invoice to payments (kltn-docs 21 BR-11), so it is one fixed value
 */
@ConfigurationProperties(prefix = "stockflow.invoicing.viettel")
public record ViettelSInvoiceProperties(@DefaultValue("https://api-vinvoice.viettel.vn") String productionUrl,
                                        @DefaultValue("https://api-vinvoice.viettel.vn") String sandboxUrl,
                                        @DefaultValue("PT95S") Duration readTimeout,
                                        @DefaultValue("PT90S") Duration resubmitQuietPeriod,
                                        @DefaultValue("P3D") Duration resubmitWindow,
                                        @DefaultValue("PT2S") Duration filePause,
                                        @DefaultValue("true") boolean paymentStatusPaid) {

    public ViettelSInvoiceProperties {
        if (readTimeout.isZero() || readTimeout.isNegative() || resubmitQuietPeriod.isNegative()
                || filePause.isNegative()
                || resubmitWindow.compareTo(resubmitQuietPeriod) <= 0) {
            throw new IllegalArgumentException("stockflow.invoicing.viettel: the read timeout must be positive and the "
                    + "resubmit window longer than the quiet period");
        }
    }
}
