package com.stockflow.invoicing.internal.service;

import com.stockflow.common.storage.FileTransfers;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceFixtures;
import com.stockflow.invoicing.internal.domain.EInvoiceQueries;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.InvoiceSettings;
import com.stockflow.invoicing.internal.domain.ProviderCallLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HĐ-2: a draft's screen names an earlier-dated invoice still to be issued under the series the draft would be sent
 * under - a warning; only one at the provider keeps the draft back. This pins what the screen is told.
 */
class EInvoiceEarlierPendingTest {

    private final EInvoiceRepository invoices = mock(EInvoiceRepository.class);
    private final InvoiceSettings settings = mock(InvoiceSettings.class);
    private final EInvoiceQueryService service = new EInvoiceQueryService(invoices, mock(EInvoiceQueries.class),
            mock(ProviderCallLog.class), mock(FileTransfers.class), settings);

    @BeforeEach
    void theSandboxIsInUse() {
        when(settings.active()).thenReturn(new InvoiceSettings.Provider("SANDBOX", "SANDBOX", null, null, false,
                false, 0));
    }

    @Test
    @DisplayName("a draft names the earlier-dated invoice of the series in use for its year at the provider in use")
    void aDraftNamesTheInvoiceOfItsSeries() {
        EInvoice.Snapshot draft = EInvoiceFixtures.draft().snapshot();
        when(settings.seriesInUse("SANDBOX", draft.issueDate().getYear()))
                .thenReturn(Optional.of(EInvoiceFixtures.SERIES));
        when(invoices.findEarlierUnissued(draft.id(), EInvoiceFixtures.SERIES, draft.issueDate()))
                .thenReturn(Optional.of("EINV-EARLIER"));

        assertThat(service.earlierPending(draft)).contains("EINV-EARLIER");
    }

    @Test
    @DisplayName("no series in use for the draft's year: nothing is named - issuing is refused for that instead")
    void withoutASeriesNothingIsNamed() {
        EInvoice.Snapshot draft = EInvoiceFixtures.draft().snapshot();
        when(settings.seriesInUse("SANDBOX", draft.issueDate().getYear())).thenReturn(Optional.empty());

        assertThat(service.earlierPending(draft)).isEmpty();
        verify(invoices, never()).findEarlierUnissued(any(), any(), any());
    }

    @Test
    @DisplayName("an invoice that is not a draft waits for nothing: it is not asked about")
    void onlyADraftIsAskedAbout() {
        EInvoice.Snapshot issued = EInvoiceFixtures.in(EInvoiceStatus.ISSUED).snapshot();

        assertThat(service.earlierPending(issued)).isEmpty();
        verify(invoices, never()).findEarlierUnissued(any(), any(), any());
    }
}
