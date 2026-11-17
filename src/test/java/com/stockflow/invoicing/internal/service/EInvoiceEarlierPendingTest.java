package com.stockflow.invoicing.internal.service;

import com.stockflow.common.storage.FileTransfers;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceFixtures;
import com.stockflow.invoicing.internal.domain.EInvoiceQueries;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.ProviderCallLog;
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
 * E0 M6, the owner's decision: issuing a draft is refused while an earlier-dated invoice waits, and the accountant is
 * told so on the invoice's own screen before trying. This pins what the screen is told.
 */
class EInvoiceEarlierPendingTest {

    private final EInvoiceRepository invoices = mock(EInvoiceRepository.class);
    private final EInvoiceQueryService service = new EInvoiceQueryService(invoices, mock(EInvoiceQueries.class),
            mock(ProviderCallLog.class), mock(FileTransfers.class));

    @Test
    @DisplayName("a draft names the earlier-dated invoice that keeps it from being issued")
    void aDraftNamesTheInvoiceItWaitsFor() {
        EInvoice.Snapshot draft = EInvoiceFixtures.draft().snapshot();
        when(invoices.findEarlierUnissued(draft.id(), draft.issueDate())).thenReturn(Optional.of("EINV-EARLIER"));

        assertThat(service.earlierPending(draft)).contains("EINV-EARLIER");
    }

    @Test
    @DisplayName("an invoice that is not a draft waits for nothing: it is not asked about")
    void onlyADraftIsAskedAbout() {
        EInvoice.Snapshot issued = EInvoiceFixtures.in(EInvoiceStatus.ISSUED).snapshot();

        assertThat(service.earlierPending(issued)).isEmpty();
        verify(invoices, never()).findEarlierUnissued(any(), any());
    }
}
