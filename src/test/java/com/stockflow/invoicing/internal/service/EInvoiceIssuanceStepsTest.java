package com.stockflow.invoicing.internal.service;

import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.RecordRetention;
import com.stockflow.common.storage.StorageException;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceFixtures;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.InvoiceSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Review finding 5: an invoice is marked issued only once its XML and PDF are stored and locked for their ten years.
 * A lock that fails leaves it waiting for the provider's answer, to be reconciled again.
 */
class EInvoiceIssuanceStepsTest {

    private static final String XML_KEY = "einvoices/2026/11/12/00000000-0000-7000-8000-000000000001.xml";
    private static final String PDF_KEY = "einvoices/2026/11/12/00000000-0000-7000-8000-000000000002.pdf";
    private static final EInvoiceProvider.Result ISSUED = new EInvoiceProvider.Result(EInvoiceProvider.ResultState.ISSUED,
            42, "SBXCODE", "SBXLOOK", EInvoiceFixtures.NOW, null, null);
    private static final EInvoiceProvider.Documents DOCUMENTS = new EInvoiceProvider.Documents(
            "<HDon/>".getBytes(StandardCharsets.UTF_8), "%PDF-1.7".getBytes(StandardCharsets.UTF_8));

    private final EInvoiceRepository invoices = mock(EInvoiceRepository.class);
    private final FileTransfers files = mock(FileTransfers.class);
    private final EInvoiceIssuanceSteps steps = new EInvoiceIssuanceSteps(invoices,
            mock(com.stockflow.invoicing.internal.domain.EInvoiceSending.class), mock(InvoiceSettings.class),
            mock(EInvoiceProviders.class), files,
            mock(org.springframework.context.ApplicationEventPublisher.class), Clock.fixed(EInvoiceFixtures.NOW, ZoneOffset.UTC));
    private EInvoice invoice;

    @BeforeEach
    void anInvoiceAtTheProvider() {
        invoice = EInvoiceFixtures.in(EInvoiceStatus.SUBMITTING);
        when(invoices.findByIdForUpdate(invoice.id())).thenReturn(Optional.of(invoice));
        when(invoices.save(any())).thenAnswer(call -> call.getArgument(0));
        when(files.store(eq(FileCategory.EINVOICE), any(FileUpload.class))).thenAnswer(call -> {
            FileUpload upload = call.getArgument(1);
            return new StoredFile(upload.contentType().endsWith("xml") ? XML_KEY : PDF_KEY, upload.originalName(),
                    upload.contentType(), upload.sizeBytes(), EInvoiceFixtures.NOW);
        });
    }

    @Test
    @DisplayName("both files are stored and locked, then the invoice is issued - in that order")
    void storedLockedThenIssued() {
        steps.issued(invoice.id(), invoice.transactionId(), ISSUED, DOCUMENTS);

        InOrder order = inOrder(files, invoices);
        order.verify(files).retainNow(XML_KEY, RecordRetention.EINVOICE);
        order.verify(files).retainNow(PDF_KEY, RecordRetention.EINVOICE);
        order.verify(invoices).save(invoice);
        assertThat(invoice.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(invoice.snapshot().invoiceNumber()).isEqualTo(42);
        assertThat(invoice.xmlFileKey()).isEqualTo(XML_KEY);
        assertThat(invoice.pdfFileKey()).isEqualTo(PDF_KEY);
        verify(files, never()).retainAfterCommit(any(), any());
    }

    @Test
    @DisplayName("a lock that fails fails the step: the invoice is not issued and nothing is saved")
    void aFailedLockLeavesItWaiting() {
        doThrow(new StorageException("Object Lock was not applied")).when(files).retainNow(PDF_KEY,
                RecordRetention.EINVOICE);

        assertThatThrownBy(() -> steps.issued(invoice.id(), invoice.transactionId(), ISSUED, DOCUMENTS))
                .isInstanceOf(StorageException.class);

        assertThat(invoice.status()).as("still waiting: reconciled again").isEqualTo(EInvoiceStatus.SUBMITTING);
        assertThat(invoice.snapshot().invoiceNumber()).isNull();
        verify(invoices, never()).save(any());
    }

    @Test
    @DisplayName("an answer for another transaction, or for an invoice that moved on, stores nothing")
    void anAnswerNobodyWaitsFor() {
        steps.issued(invoice.id(), invoice.id() + "-9", ISSUED, DOCUMENTS);
        EInvoice issued = EInvoiceFixtures.in(EInvoiceStatus.ISSUED);
        when(invoices.findByIdForUpdate(issued.id())).thenReturn(Optional.of(issued));
        steps.issued(issued.id(), issued.transactionId(), ISSUED, DOCUMENTS);

        verify(files, never()).store(any(), any(FileUpload.class));
        verify(files, never()).retainNow(any(), any());
        verify(invoices, never()).save(any());
    }
}
