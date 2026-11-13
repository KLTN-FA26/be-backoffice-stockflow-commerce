package com.stockflow.chat.internal.service;

import com.stockflow.contracts.DesignProjectQuoteRequested;
import com.stockflow.contracts.DesignQuoteRequestClosed;
import com.stockflow.contracts.DesignQuoteRequested;
import com.stockflow.contracts.DesignReviewed;
import com.stockflow.contracts.DesignSubmittedForReview;
import com.stockflow.contracts.EInvoiceIssued;
import com.stockflow.contracts.InstallmentDue;
import com.stockflow.contracts.PaymentReceiptRecorded;
import com.stockflow.contracts.PaymentReceiptVoided;
import com.stockflow.contracts.ProductionOrderStopped;
import com.stockflow.contracts.QuoteStatusChanged;
import com.stockflow.contracts.SalesContractStatusChanged;
import com.stockflow.contracts.SampleRoundChanged;
import com.stockflow.contracts.ShipmentStatusChanged;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * The events the chat's cards follow (kltn-docs 16 §3 Output, BR-03). Each runs after the publishing transaction
 * committed, in its own transaction, and is redelivered by the event publication registry until it completes - so a
 * card is never lost to a crash, and {@link DocumentCards} makes every redelivery harmless. A card failing never fails
 * the step that published the event: a quote is sent whether or not its card could be drawn.
 */
@Component
class ChatCardListener {

    private final DocumentCards cards;

    ChatCardListener(DocumentCards cards) {
        this.cards = cards;
    }

    @ApplicationModuleListener
    public void on(DesignQuoteRequested event) {
        cards.designQuoteRequested(event.requestId());
    }

    @ApplicationModuleListener
    public void on(DesignProjectQuoteRequested event) {
        cards.projectQuoteRequested(event.requestId());
    }

    @ApplicationModuleListener
    public void on(DesignReviewed event) {
        cards.designChanged(event.designId());
    }

    @ApplicationModuleListener
    public void on(DesignSubmittedForReview event) {
        cards.designChanged(event.designId());
    }

    @ApplicationModuleListener
    public void on(DesignQuoteRequestClosed event) {
        cards.designQuoteRequestClosed(event);
    }

    @ApplicationModuleListener
    public void on(com.stockflow.contracts.DesignProjectQuoteRequestClosed event) {
        cards.projectQuoteRequestClosed(event.requestId());
    }

    @ApplicationModuleListener
    public void on(QuoteStatusChanged event) {
        cards.quoteChanged(event);
    }

    @ApplicationModuleListener
    public void on(SalesContractStatusChanged event) {
        cards.contractChanged(event);
    }

    @ApplicationModuleListener
    public void on(InstallmentDue event) {
        cards.installmentDue(event);
    }

    @ApplicationModuleListener
    public void on(PaymentReceiptRecorded event) {
        cards.paymentsChanged(event.customerId());
    }

    @ApplicationModuleListener
    public void on(PaymentReceiptVoided event) {
        cards.paymentsChanged(event.customerId());
    }

    @ApplicationModuleListener
    public void on(SampleRoundChanged event) {
        cards.sampleRoundChanged(event);
    }

    @ApplicationModuleListener
    public void on(ProductionOrderStopped event) {
        cards.productionOrderStopped(event);
    }

    @ApplicationModuleListener
    public void on(EInvoiceIssued event) {
        cards.einvoiceIssued(event);
    }

    @ApplicationModuleListener
    public void on(ShipmentStatusChanged event) {
        cards.shipmentChanged(event);
    }
}
