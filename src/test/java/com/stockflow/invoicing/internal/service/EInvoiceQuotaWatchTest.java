package com.stockflow.invoicing.internal.service;

import com.stockflow.contracts.EInvoiceProviderAccountAlert;
import com.stockflow.invoicing.internal.domain.EInvoiceFixtures;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ProviderUnavailableException;
import com.stockflow.invoicing.internal.domain.InvoiceSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HĐ-1, kltn-docs 21 BR-09: the accountants are warned when the provider in use has fewer invoices left than the
 * configured level - and only a provider that tells its quota is asked.
 */
class EInvoiceQuotaWatchTest {

    private final InvoiceSettings settings = mock(InvoiceSettings.class);
    private final EInvoiceProviders providers = mock(EInvoiceProviders.class);
    private final EInvoiceProvider sepay = mock(EInvoiceProvider.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

    private EInvoiceQuotaWatch watch(long threshold) {
        return new EInvoiceQuotaWatch(settings, providers, new InvoicingProperties(Duration.ofDays(1), null, null,
                new InvoicingProperties.Corrections(false), null, threshold), events,
                Clock.fixed(EInvoiceFixtures.NOW, ZoneOffset.UTC), transactions);
    }

    @BeforeEach
    void sepayInUse() {
        when(settings.active()).thenReturn(new InvoiceSettings.Provider("SEPAY_EINVOICE", "SANDBOX", null, null, false,
                false, 1));
        when(providers.find("SEPAY_EINVOICE")).thenReturn(Optional.of(sepay));
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    @DisplayName("under the level: warned, with what is left and the level, in a transaction of its own")
    void underTheLevelIsWarned() {
        when(sepay.remainingQuota()).thenReturn(OptionalLong.of(12));

        assertThat(watch(50).check()).isTrue();

        verify(events).publishEvent(new EInvoiceProviderAccountAlert("SEPAY_EINVOICE",
                EInvoiceProviderAccountAlert.Problem.QUOTA_LOW, null, 12L, 50L, EInvoiceFixtures.NOW));
        verify(transactions).commit(any());
    }

    @Test
    @DisplayName("at or above the level, no figure, no answer, or the warning off: nothing said")
    void otherwiseNothingIsSaid() {
        when(sepay.remainingQuota()).thenReturn(OptionalLong.of(50));
        assertThat(watch(50).check()).isFalse();

        when(sepay.remainingQuota()).thenReturn(OptionalLong.empty());
        assertThat(watch(50).check()).as("the sandbox, Viettel: no quota to tell").isFalse();

        when(sepay.remainingQuota()).thenThrow(new ProviderUnavailableException("no answer"));
        assertThat(watch(50).check()).isFalse();

        assertThat(watch(0).check()).as("turned off").isFalse();
        verify(events, never()).publishEvent(any(Object.class));
    }
}
