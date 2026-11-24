package com.stockflow.payment.internal.service;

import com.stockflow.common.document.BusinessDocument.Align;
import com.stockflow.common.document.BusinessDocument.Block;
import com.stockflow.common.document.BusinessDocument.Field;
import com.stockflow.common.document.BusinessDocument.Fields;
import com.stockflow.common.document.BusinessDocument.Paragraph;
import com.stockflow.common.document.BusinessDocument.Run;
import com.stockflow.common.document.BusinessDocument.Signer;
import com.stockflow.common.document.BusinessDocument.Split;
import com.stockflow.common.document.BusinessDocument.Title;
import com.stockflow.common.document.DocumentFormats;
import com.stockflow.common.document.DocumentParts;
import com.stockflow.common.document.Letterhead;
import com.stockflow.common.document.SellerLetterhead;
import com.stockflow.common.document.Style;
import com.stockflow.common.text.VietnameseAmountInWords;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

/**
 * The parts the two papers for money moved by bank transfer draw the same way - the confirmation of a payment received
 * and the confirmation of a refund paid ({@link PaymentConfirmationDocument}, {@link RefundConfirmationDocument}): the
 * company's head, the number beside the place and date, who it is addressed to, the amount in figures and in words,
 * and who signs. One place, so a customer holding the receipt of an instalment and the refund of a return reads the
 * same paper twice.
 *
 * <p>Neither is the ministry's cash voucher (Mẫu 01-TT, 02-TT of Thông tư 99/2025/TT-BTC): those record cash entering
 * and leaving the till, and a transfer's source document is the bank's own advice. These confirm a transfer to the
 * other company and name the bank's reference, so they carry the company's head, not "Mẫu số".</p>
 */
final class PaymentDocumentLines {

    private PaymentDocumentLines() {
    }

    /** The company's head, "Số: ..." beside the place and date, the paper's name, and who it is addressed to. */
    static void open(List<Block> blocks, ResourceBundle t, Letterhead head, String number, LocalDate dated,
                     String title, DocumentParts.Party addressee) {
        blocks.add(DocumentParts.brandHeader(t, head));
        blocks.add(new Split(List.of(Run.bold(t.getString("document.number") + " " + number)),
                List.of(Run.italic(DocumentParts.placeDate(t, head, dated)))));
        blocks.add(new Title(title, null, null));
        blocks.add(Paragraph.of(Run.of(t.getString("document.to") + " "), Run.bold(addressee.name()))
                .align(Align.LEFT).after(0));
        List<Field> fields = new ArrayList<>();
        if (DocumentParts.filled(addressee.address())) {
            fields.add(Field.of(t.getString("document.party.address"), addressee.address()));
        }
        if (DocumentParts.filled(addressee.taxCode())) {
            fields.add(Field.of(t.getString("document.party.taxCode"), addressee.taxCode()));
        }
        if (!fields.isEmpty()) {
            blocks.add(new Fields(fields, 0));
        }
    }

    /** "Số tiền: 25.000.000 đồng" in bold, and the same in words: a paper for money states it both ways. */
    static void amount(List<Field> fields, ResourceBundle t, String label, BigDecimal amount) {
        fields.add(new Field(label, List.of(Run.bold(t.getString("document.dong")
                .formatted(DocumentFormats.money(amount))))));
        VietnameseAmountInWords.ofWhole(amount).ifPresent(words -> fields.add(new Field(
                t.getString("document.inWordsLabel"), List.of(new Run(words + ".", Style.BOLD_ITALIC)))));
    }

    /** Who drew the paper up, signing as a voucher is signed. */
    static Signer preparedBy(ResourceBundle t, String name) {
        return new Signer(t.getString("document.sign.preparedBy"), t.getString("document.sign.voucher"), name);
    }

    /** The chief accountant: named by title alone - the system does not keep who holds the post. */
    static Signer chiefAccountant(ResourceBundle t) {
        return new Signer(t.getString("document.sign.chiefAccountant"), t.getString("document.sign.voucher"), null);
    }

    /** Whoever signs for the company, under their title ("GIÁM ĐỐC"), with room for the seal. */
    static Signer company(ResourceBundle t, SellerLetterhead letterhead) {
        return new Signer(t.getString("document.sign.company"),
                DocumentParts.position(letterhead.representativeTitle()), t.getString("document.sign.sealed"),
                letterhead.representative());
    }
}
