package com.stockflow.payment.internal.service;

import com.stockflow.common.document.BusinessDocument;
import com.stockflow.common.document.BusinessDocument.Align;
import com.stockflow.common.document.BusinessDocument.Block;
import com.stockflow.common.document.BusinessDocument.Cell;
import com.stockflow.common.document.BusinessDocument.Column;
import com.stockflow.common.document.BusinessDocument.Field;
import com.stockflow.common.document.BusinessDocument.Fields;
import com.stockflow.common.document.BusinessDocument.Paragraph;
import com.stockflow.common.document.BusinessDocument.Signatures;
import com.stockflow.common.document.BusinessDocument.Table;
import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.document.DocumentParts;
import com.stockflow.common.document.Letterhead;
import com.stockflow.common.document.SellerLetterhead;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Draws the confirmation of a payment received ("phiếu xác nhận thanh toán") as a PDF: what the company gives a
 * customer who has paid an instalment by bank transfer and asks for a paper saying so - for its own books, or to
 * release the next delivery.
 *
 * <p>Deliberately not the cash receipt voucher (Mẫu 01-TT, Thông tư 99/2025/TT-BTC): that form records cash entering
 * the till, and printing it for a transfer would state a cash movement that never happened. This is a commercial paper
 * with the company's head; it names the bank's reference of the transfer, which is the source document, and says in
 * its own words that it replaces neither the VAT invoice nor a cash receipt.</p>
 *
 * <p>Under the amount it shows where the contract now stands - its value, what has been paid including this payment,
 * what is left - because the question a customer's accountant asks of a receipt for "instalment 2" is how much is
 * still to come. The words are the Vietnamese bundle's, the layout the kit's ({@link BusinessDocument}); what it shows
 * is given, nothing is looked up here.</p>
 */
@Component
class PaymentConfirmationDocument {

    /**
     * Everything the confirmation says.
     *
     * @param installment   what was paid, as the contract's payment plan names it ("Đợt 2/3 - Thanh toán trước khi
     *                      giao hàng")
     * @param receivedOn    the day the money reached the account, by the bank statement
     * @param recordedAt    when the accountant recorded it against the instalment
     * @param bankReference the transfer's reference on the bank statement
     * @param paidToDate    everything paid under the contract, this payment included
     * @param preparedBy    who recorded the payment; null to leave the name to be written in
     */
    record Facts(String number, LocalDate dated, DocumentParts.Party payer, String contractNumber, String orderNumber,
                 String installment, BigDecimal amount, LocalDate receivedOn, Instant recordedAt,
                 String bankReference,
                 BigDecimal contractValue, BigDecimal paidToDate, String preparedBy) {
    }

    private final DocumentFonts fonts;
    private final SellerLetterhead letterhead;

    PaymentConfirmationDocument(DocumentFonts fonts, SellerLetterhead letterhead) {
        this.fonts = fonts;
        this.letterhead = letterhead;
    }

    byte[] render(Facts f) {
        ResourceBundle t = DocumentParts.bundle();
        Letterhead head = letterhead.get();
        List<Block> blocks = new ArrayList<>();
        String title = t.getString("payment-confirmation.pdf.title");
        PaymentDocumentLines.open(blocks, t, head, f.number(), f.dated(), title, f.payer());
        blocks.add(DocumentParts.lead(t.getString("payment-confirmation.pdf.confirms").formatted(head.name()))
                .after(2));

        List<Field> fields = new ArrayList<>();
        fields.add(Field.of(t.getString("payment-confirmation.pdf.for"),
                t.getString("payment-confirmation.pdf.forValue").formatted(f.installment(), f.contractNumber())));
        fields.add(Field.of(t.getString("payment-confirmation.pdf.order"), f.orderNumber()));
        PaymentDocumentLines.amount(fields, t, t.getString("payment-confirmation.pdf.amount"), f.amount());
        fields.add(Field.of(t.getString("payment-confirmation.pdf.method"),
                DocumentParts.filled(head.bankAccountNumber()) && DocumentParts.filled(head.bankName())
                        ? t.getString("payment-confirmation.pdf.methodAccount").formatted(head.bankAccountNumber(),
                        DocumentParts.bank(head)) : t.getString("payment-confirmation.pdf.methodTransfer")));
        fields.add(Field.of(t.getString("payment-confirmation.pdf.reference"),
                DocumentParts.orBlank(t, f.bankReference())));
        fields.add(Field.of(t.getString("payment-confirmation.pdf.receivedOn"),
                DocumentFormats.date(f.receivedOn())));
        fields.add(Field.of(t.getString("payment-confirmation.pdf.receivedAt"),
                DocumentFormats.moment(f.recordedAt())));
        blocks.add(new Fields(fields, 0));

        blocks.add(Paragraph.bold(t.getString("payment-confirmation.pdf.standing").formatted(f.contractNumber()))
                .align(Align.LEFT).after(0));
        blocks.add(new Table(List.of(
                new Column(t.getString("payment-confirmation.pdf.col.contract"), 150, Align.RIGHT),
                new Column(t.getString("payment-confirmation.pdf.col.paid"), 150, Align.RIGHT),
                new Column(t.getString("payment-confirmation.pdf.col.remaining"), 150, Align.RIGHT)),
                List.of(List.of(Cell.of(DocumentFormats.money(f.contractValue())),
                        Cell.of(DocumentFormats.money(f.paidToDate())),
                        Cell.bold(DocumentFormats.money(f.contractValue().subtract(f.paidToDate())))))));
        blocks.add(Paragraph.italic(t.getString("payment-confirmation.pdf.note")).size(-1));

        blocks.add(new Signatures(null, List.of(PaymentDocumentLines.preparedBy(t, f.preparedBy()),
                PaymentDocumentLines.company(t, letterhead))));
        return BusinessDocument.draw(fonts, DocumentParts.commercial(t, f.number(), head, null),
                title + " " + f.number(), blocks);
    }
}
