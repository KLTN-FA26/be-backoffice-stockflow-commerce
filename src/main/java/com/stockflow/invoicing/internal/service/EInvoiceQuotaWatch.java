package com.stockflow.invoicing.internal.service;

import com.stockflow.contracts.EInvoiceProviderAccountAlert;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ProviderUnavailableException;
import com.stockflow.invoicing.internal.domain.InvoiceSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Warns the accountants when the provider in use is running out of invoices (kltn-docs 21 BR-09: "dưới ngưỡng cấu hình →
 * cảnh báo Kế toán"; HĐ-1). A plan used up is found out otherwise only when an invoice is refused for it, which is the
 * day invoices stop going out.
 *
 * <p>Only a provider that tells its quota is asked ({@link EInvoiceProvider#remainingQuota()}: SePay's
 * {@code GET /v1/usage}); the sandbox and Viettel's adapter tell none, and nothing is said about them. Run by the
 * deadline sweep ({@link EInvoiceJobs}), every quarter of an hour by default; the warning
 * ({@link EInvoiceProviderAccountAlert}, {@code QUOTA_LOW}) is posted at most once a day by notification, so asking
 * often costs one small call and no noise. Nothing is blocked here: a plan that is used up refuses the invoices itself,
 * under {@code SEPAY_QUOTA_EXHAUSTED}, and those refusals are what stop issuing.</p>
 */
@Component
class EInvoiceQuotaWatch {

    private static final Logger log = LoggerFactory.getLogger(EInvoiceQuotaWatch.class);

    private final InvoiceSettings settings;
    private final EInvoiceProviders providers;
    private final InvoicingProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    /** The warning is published in a transaction of its own, after the provider answered: a module listener hears only
     * what a transaction committed, and no transaction is held open across the call to the provider. */
    private final TransactionTemplate transaction;

    EInvoiceQuotaWatch(InvoiceSettings settings, EInvoiceProviders providers, InvoicingProperties properties,
                       ApplicationEventPublisher events, Clock clock, PlatformTransactionManager transactions) {
        this.settings = settings;
        this.providers = providers;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** @return whether the accountants were warned now */
    boolean check() {
        long threshold = properties.quotaWarningBelow();
        if (threshold <= 0) {
            return false;
        }
        String code = settings.active().provider();
        Optional<EInvoiceProvider> provider = providers.find(code);
        if (provider.isEmpty()) {
            return false;
        }
        OptionalLong remaining;
        try {
            remaining = provider.get().remainingQuota();
        } catch (ProviderUnavailableException unavailable) {
            log.warn("E-invoice provider {} did not tell its remaining quota; asked again at the next sweep", code);
            return false;
        }
        if (remaining.isEmpty() || remaining.getAsLong() >= threshold) {
            return false;
        }
        log.warn("E-invoice provider {} has {} invoice(s) left in its plan, under {}", code, remaining.getAsLong(),
                threshold);
        EInvoiceProviderAccountAlert alert = new EInvoiceProviderAccountAlert(code,
                EInvoiceProviderAccountAlert.Problem.QUOTA_LOW, null, remaining.getAsLong(), threshold, clock.instant());
        transaction.executeWithoutResult(status -> events.publishEvent(alert));
        return true;
    }
}
