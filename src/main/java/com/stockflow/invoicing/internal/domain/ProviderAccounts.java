package com.stockflow.invoicing.internal.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port: the account the system calls each e-invoice provider with, and which provider is in use
 * ({@code invoicing.einvoice_provider_config}; kltn-docs 21 BR-10). Apart from {@link InvoiceSettings}, which every
 * part of the module reads, because this one carries the sealed secret: only what stores an account and what calls the
 * provider may see it.
 */
public interface ProviderAccounts {

    /**
     * @param environment  {@code SANDBOX} (the provider's test system) or {@code PRODUCTION}
     * @param secretSealed the account's password as {@code SecretCipher} sealed it, bound to {@code id}; never clear
     */
    record Account(UUID id, String provider, String environment, String username, String secretSealed, boolean active,
                   boolean autoIssue, long version) {

        /** Whether there is something to sign in with. */
        public boolean complete() {
            return username != null && secretSealed != null;
        }
    }

    /** Every provider that can be configured, the one in use first. */
    List<Account> all();

    Optional<Account> find(String provider);

    /**
     * Stores a provider's account against the version read.
     *
     * @param secretSealed the new sealed password; null keeps the one stored
     */
    void store(String provider, String environment, String username, String secretSealed, long expectedVersion);

    /** Puts {@code provider} in use instead of the one that is, against the version read of {@code provider}. */
    void activate(String provider, long expectedVersion);

    /**
     * Makes a series the one invoices of {@code series.year()} are numbered under at its provider, with the provider's
     * own name for the template (null where it has none).
     */
    void useSeries(InvoiceSeries series, String providerTemplateCode);

    /**
     * The provider's own code of the template of a series; empty when the series is unknown or has none.
     *
     * @param templateCode null when only the symbol is known (an invoice number read back from the provider); the
     *                     series in use is preferred then
     */
    Optional<String> providerTemplateCode(String provider, String templateCode, String symbol);
}
