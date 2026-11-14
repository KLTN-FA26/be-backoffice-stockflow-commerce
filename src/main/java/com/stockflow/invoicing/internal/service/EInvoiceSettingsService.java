package com.stockflow.invoicing.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.invoicing.internal.domain.InvoiceSeries;
import com.stockflow.invoicing.internal.domain.InvoiceSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The e-invoice settings an administrator reads and changes at runtime: which provider issues, under which series, and
 * whether a complete draft is issued without waiting for the accountant (plan §1.4 "cấu hình tự phát hành").
 *
 * <p>Only automatic issuing is changed here. A provider's account, its series and which provider is in use are
 * {@link EInvoiceProviderAccountService}'s.</p>
 */
@Service
public class EInvoiceSettingsService {

    /** The provider in use and its series. */
    public record Settings(InvoiceSettings.Provider provider, List<InvoiceSeries> series) {
    }

    private final InvoiceSettings settings;

    EInvoiceSettingsService(InvoiceSettings settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public Settings get() {
        return new Settings(settings.active(), settings.seriesOfActiveProvider());
    }

    /**
     * Turns automatic issuing on or off, against the version read.
     *
     * <p>On, a draft is issued by the system soon after it is drawn up - provided its buyer is complete and nothing on
     * it needs a look - which is what keeps an invoice inside its deadline of the next working day when nobody is at
     * the screen. Off, the accountant issues each one (kltn-docs 21 BR-02).</p>
     *
     * @throws com.stockflow.common.error.BusinessException {@code OPTIMISTIC_LOCK}
     */
    @Transactional
    @Auditable(action = AuditAction.UPDATE, resourceType = "einvoice-settings")
    public Settings changeAutoIssue(boolean autoIssue, long expectedVersion) {
        settings.changeAutoIssue(autoIssue, expectedVersion);
        return get();
    }
}
