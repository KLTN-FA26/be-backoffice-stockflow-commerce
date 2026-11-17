package com.stockflow.invoicing.internal.domain;

import java.util.List;
import java.util.Optional;

/**
 * Port: which provider issues, and under which series (plan §1.5: {@code einvoice_provider_config},
 * {@code einvoice_series}).
 */
public interface InvoiceSettings {

    /**
     * The provider in use.
     *
     * @param environment {@code SANDBOX} or {@code PRODUCTION}
     * @param secretSet   whether an account secret is stored - sealed, and never read back out (kltn-docs 21 BR-10)
     * @param autoIssue   whether a draft is issued as soon as it is drawn up, when its buyer is complete and nothing
     *                    on it needs a look (plan §1.4); off, the accountant issues each one
     */
    record Provider(String provider, String environment, String endpoint, String username, boolean secretSet,
                    boolean autoIssue, long version) {
    }

    /** The provider in use; there is always one (the sandbox is seeded). */
    Provider active();

    /** Turns automatic issuing on or off for the provider in use, against the version read. */
    Provider changeAutoIssue(boolean autoIssue, long expectedVersion);

    /** The series invoices dated in {@code year} are numbered under at {@code provider}; empty when none is in use. */
    Optional<InvoiceSeries> seriesInUse(String provider, int year);

    /** Every series of the provider in use, newest year first. */
    List<InvoiceSeries> seriesOfActiveProvider();
}
