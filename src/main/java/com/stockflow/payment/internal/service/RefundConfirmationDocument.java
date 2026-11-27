package com.stockflow.payment.internal.service;

import com.stockflow.common.document.BusinessDocument;
import com.stockflow.common.document.BusinessDocument.Block;
import com.stockflow.common.document.BusinessDocument.Field;
import com.stockflow.common.document.BusinessDocument.Fields;
import com.stockflow.common.document.BusinessDocument.Paragraph;
import com.stockflow.common.document.BusinessDocument.Signatures;
import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.document.DocumentParts;
import com.stockflow.common.document.Letterhead;
import com.stockflow.common.document.SellerLetterhead;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Draws the confirmation of a refund ("phiếu hoàn tiền") as a PDF: what the company gives a customer it pays money
 * back to - for goods returned, or for a delivery that never arrived - so the customer's books can show why the money
 * came.
 *
 * <p>A refund here is a bank transfer, so this is not the cash payment voucher (Mẫu 02-TT, Thông tư 99/2025/TT-BTC),
 * which records cash leaving the till: it is a commercial paper under the company's head that names the bank's
 * reference. It names what the refund follows from - the return, the invoice that adjusts the sale down (Thông tư
 * 91/2026/TT-BTC Điều 10 khoản 5 has the seller issue one for goods returned), the contract and the order - because a
 * refund with no stated basis is the one an auditor asks about.</p>
 *
 * <p>A decision about money is two people's here even when it is nothing (owner decision Q-4), so three sign: who drew
 * it up, the chief accountant, and the company's representative under their title. Printed before the transfer is made
 * as well as after: the reference and the moment are then lines to fill in. The words are the Vietnamese bundle's, the
 * layout the kit's ({@link BusinessDocument}); what it shows is given, nothing is looked up here.</p>
 */
@Component
class RefundConfirmationDocument {

    /**
     * Everything the confirmation says.
     *
     * @param reason            why the money is paid back, in the company's words
     * @param returnNumber      the return it follows from; null for a refund without one (a lost delivery)
     * @param adjustmentInvoice the invoice adjusting the sale down, as "number (date)"; null while not issued
     * @param payeeAccount      the customer's account as a transfer names it; null when not recorded
     * @param transferredOn     the day of the transfer, by the bank statement; null until it is made
     * @param preparedBy        who drew the paper up; null to leave the name to be written in
     */
    record Facts(String number, LocalDate dated, DocumentParts.Party payee, String reason, String returnNumber,
                 String adjustmentInvoice, String contractNumber, String orderNumber, BigDecimal amount,
                 String payeeAccount, LocalDate transferredOn, String bankReference, String preparedBy) {
    }

    private final DocumentFonts fonts;
    private final SellerLetterhead letterhead;

    RefundConfirmationDocument(DocumentFonts fonts, SellerLetterhead letterhead) {
        this.fonts = fonts;
        this.letterhead = letterhead;
    }

    byte[] render(Facts f) {
        ResourceBundle t = DocumentParts.bundle();
        Letterhead head = letterhead.get();
        List<Block> blocks = new ArrayList<>();
        String title = t.getString("refund.pdf.title");
        PaymentDocumentLines.open(blocks, t, head, f.number(), f.dated(), title, f.payee());
        blocks.add(DocumentParts.lead(t.getString("refund.pdf.confirms").formatted(head.name())).after(2));

        List<Field> fields = new ArrayList<>();
        fields.add(Field.of(t.getString("refund.pdf.reason"), f.reason()));
        if (DocumentParts.filled(f.returnNumber())) {
            fields.add(Field.of(t.getString("refund.pdf.return"), f.returnNumber()));
        }
        if (DocumentParts.filled(f.adjustmentInvoice())) {
            fields.add(Field.of(t.getString("refund.pdf.invoice"), f.adjustmentInvoice()));
        }
        fields.add(Field.of(t.getString("refund.pdf.contract"),
                t.getString("refund.pdf.contractValue").formatted(f.contractNumber(), f.orderNumber())));
        PaymentDocumentLines.amount(fields, t, t.getString("refund.pdf.amount"), f.amount());
        fields.add(Field.of(t.getString("refund.pdf.method"), t.getString("refund.pdf.methodTransfer")));
        fields.add(Field.of(t.getString("refund.pdf.account"), DocumentParts.orBlank(t, f.payeeAccount())));
        fields.add(Field.of(t.getString("refund.pdf.reference"), DocumentParts.orBlank(t, f.bankReference())));
        fields.add(Field.of(t.getString("refund.pdf.transferredAt"), f.transferredOn() == null
                ? t.getString("document.blank") : DocumentFormats.date(f.transferredOn())));
        blocks.add(new Fields(fields, 0));
        blocks.add(Paragraph.italic(t.getString("refund.pdf.note")).size(-1));

        blocks.add(new Signatures(null, List.of(PaymentDocumentLines.preparedBy(t, f.preparedBy()),
                PaymentDocumentLines.chiefAccountant(t), PaymentDocumentLines.company(t, letterhead))));
        return BusinessDocument.draw(fonts, DocumentParts.commercial(t, f.number(), head, null),
                title + " " + f.number(), blocks);
    }
}
