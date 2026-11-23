package com.stockflow.common.document;

import com.stockflow.common.document.BusinessDocument.Align;
import com.stockflow.common.document.BusinessDocument.Block;
import com.stockflow.common.document.BusinessDocument.BrandHeader;
import com.stockflow.common.document.BusinessDocument.Field;
import com.stockflow.common.document.BusinessDocument.Fields;
import com.stockflow.common.document.BusinessDocument.Figure;
import com.stockflow.common.document.BusinessDocument.Layout;
import com.stockflow.common.document.BusinessDocument.NationalHeader;
import com.stockflow.common.document.BusinessDocument.Paragraph;
import com.stockflow.common.document.BusinessDocument.Run;
import com.stockflow.common.document.BusinessDocument.Signer;
import com.stockflow.common.document.BusinessDocument.VoucherHeader;
import com.stockflow.common.text.VietnameseAmountInWords;

import java.awt.Color;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.StringJoiner;

/**
 * The parts every business document draws the same way, whichever module issues it: our company's head, a party's
 * block, the line a record opens with, a total in words, the foot of the page. In {@code common} because the papers of
 * one sale come from five modules - the contract from order, the delivery record from fulfillment, the invoice
 * agreement from invoicing, the payment papers from payment, the return voucher from procurement - and a customer who
 * holds them side by side must see one company, written one way. The shared words are the {@code document.*} keys of
 * the Vietnamese bundle.
 */
public final class DocumentParts {

    /**
     * The brand colour of the company's papers: the dark green of its logo. A constant, not configuration - a second
     * company would bring its own palette with its own logo, which is a change of more than one value.
     */
    public static final Color ACCENT = new Color(0x1F, 0x6F, 0x54);

    /**
     * A company as a paper names it. Any part but the name may be null: a block leaves out what is not known rather
     * than print a blank.
     *
     * @param account "0071001234567 tại Ngân hàng ... - Chi nhánh ...", as a contract's party block writes it
     */
    public record Party(String name, String taxCode, String address, String phone, String email, String account,
                        String representative, String representativeTitle) {

        /** A party known by its legal identity alone: a customer, whose phone and account we do not keep. */
        public Party(String name, String taxCode, String address, String representative, String representativeTitle) {
            this(name, taxCode, address, null, null, null, representative, representativeTitle);
        }

        /** Our company, from its letterhead and whoever signs for it. */
        public static Party of(ResourceBundle t, Letterhead head, String representative, String representativeTitle) {
            String account = filled(head.bankAccountNumber()) && filled(head.bankName())
                    ? t.getString("document.party.accountAt").formatted(head.bankAccountNumber(), bank(head)) : null;
            return new Party(head.name(), head.taxCode(), head.address(), head.phone(), head.email(), account,
                    representative, representativeTitle);
        }
    }

    private DocumentParts() {
    }

    /** Every document is Vietnamese whatever the request's language: it is a paper between Vietnamese companies. */
    public static ResourceBundle bundle() {
        return ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
    }

    /** Our company's head on a commercial paper: logo, name, then address, "MST | ĐT" and "Email | Website". */
    public static BrandHeader brandHeader(ResourceBundle t, Letterhead head) {
        List<String> lines = new ArrayList<>();
        if (filled(head.address())) {
            lines.add(t.getString("document.head.address").formatted(head.address()));
        }
        StringJoiner contacts = new StringJoiner("   |   ");
        add(contacts, t, "document.head.taxCode", head.taxCode());
        add(contacts, t, "document.head.phone", head.phone());
        lines.add(contacts.toString());
        StringJoiner online = new StringJoiner("   |   ");
        add(online, t, "document.head.email", head.email());
        add(online, t, "document.head.website", head.website());
        lines.add(online.toString());
        return new BrandHeader(head.logo(), head.name(), lines);
    }

    private static void add(StringJoiner joined, ResourceBundle t, String key, String value) {
        if (filled(value)) {
            joined.add(t.getString(key).formatted(value));
        }
    }

    /** The head a contract opens with: Quốc hiệu and Tiêu ngữ centred over the page. */
    public static NationalHeader nationalHead(ResourceBundle t) {
        return new NationalHeader(t.getString("document.nation"), t.getString("document.motto"), null, null, null);
    }

    /**
     * The head a record ("biên bản") opens with: our company and the record's number at the left, Quốc hiệu, Tiêu ngữ
     * and the place and date at the right.
     */
    public static NationalHeader recordHead(ResourceBundle t, Letterhead head, String number, LocalDate dated) {
        return new NationalHeader(t.getString("document.nation"), t.getString("document.motto"), head.name(),
                filled(number) ? t.getString("document.number") + " " + number : null, placeDate(t, head, dated));
    }

    /** The head of an accounting voucher after Thông tư 99/2025/TT-BTC: our unit at the left, the form at the right. */
    public static VoucherHeader voucherHead(ResourceBundle t, Letterhead head, String form) {
        return new VoucherHeader(t.getString("document.voucher.unit").formatted(head.name()),
                filled(head.address()) ? t.getString("document.voucher.address").formatted(head.address()) : null,
                form, t.getString("document.voucher.basis"));
    }

    /** "Thành phố Hồ Chí Minh, ngày 23 tháng 11 năm 2026"; the date alone, capitalised, without a place. */
    public static String placeDate(ResourceBundle t, Letterhead head, LocalDate dated) {
        String date = DocumentFormats.longDate(dated);
        return filled(head.city()) ? t.getString("document.placeDate").formatted(head.city(), date)
                : date.substring(0, 1).toUpperCase(Locale.ROOT) + date.substring(1);
    }

    /** "Hôm nay, ngày ..., tại ..., chúng tôi gồm:" - the sentence a record names its parties after. */
    public static Paragraph today(ResourceBundle t, Letterhead head, LocalDate dated) {
        String date = DocumentFormats.longDate(dated);
        return Paragraph.of(filled(head.city()) ? t.getString("document.todayAt").formatted(date, head.city())
                : t.getString("document.today").formatted(date)).firstIndent(28).after(6);
    }

    /**
     * A party's block: its role and name in bold, then each of address, tax code, phone, e-mail, account and
     * representative that is known, their colons in one column.
     *
     * @param role "BÊN BÁN (BÊN A)", already in the paper's language
     */
    public static void party(List<Block> blocks, ResourceBundle t, String role, Party party) {
        party(blocks, t, role, party, List.of());
    }

    /**
     * A party's block with fields of the paper's own after the usual ones - the warehouse the goods leave from, the
     * person who receives them - in the same block, so every colon of the party stays in one column.
     */
    public static void party(List<Block> blocks, ResourceBundle t, String role, Party party, List<Field> more) {
        blocks.add(Paragraph.of(Run.bold(role + ": "), Run.bold(party.name())).align(Align.LEFT).after(0));
        List<Field> fields = new ArrayList<>();
        field(fields, t, "document.party.address", party.address());
        field(fields, t, "document.party.taxCode", party.taxCode());
        field(fields, t, "document.party.phone", party.phone());
        field(fields, t, "document.party.email", party.email());
        field(fields, t, "document.party.account", party.account());
        if (filled(party.representative())) {
            field(fields, t, "document.party.representative", filled(party.representativeTitle())
                    ? t.getString("document.party.representativeTitled").formatted(party.representative(),
                    party.representativeTitle())
                    : party.representative());
        }
        fields.addAll(more);
        if (!fields.isEmpty()) {
            blocks.add(new Fields(fields, 0));
        }
    }

    private static void field(List<Field> fields, ResourceBundle t, String key, String value) {
        if (filled(value)) {
            fields.add(Field.of(t.getString(key), value));
        }
    }

    /** A legal basis ("Căn cứ ...;"): italic, one to a line, as Nghị định 30/2020/NĐ-CP sets them. */
    public static Paragraph basis(String text) {
        return Paragraph.italic(text).firstIndent(28).after(1);
    }

    /** A sentence that opens a part of a record, its first line set in as body text is. */
    public static Paragraph lead(String text) {
        return Paragraph.of(text).firstIndent(28);
    }

    /**
     * Who signs for a company on a paper two companies sign: "ĐẠI DIỆN BÊN A", the signatory's title in capitals
     * under it where it is known, room for the seal, and their name.
     */
    public static Signer signatory(ResourceBundle t, String title, Party party) {
        return new Signer(title, position(party.representativeTitle()), t.getString("document.sign.sealed"),
                party.representative());
    }

    /**
     * A box to tick by hand with its text beside it: the state of the goods on receipt is one of a few answers, and a
     * ticked box is read faster - and disputed less - than a sentence written at a tailgate. Drawn, not typed: the
     * fonts have no ballot box.
     */
    public static Figure checkbox(String text, float size) {
        return new Figure(size * 1.45f, canvas -> {
            float box = size * 0.78f;
            float baseline = canvas.top() - size * 1.05f;
            canvas.stream().setLineWidth(0.7f);
            canvas.stream().addRect(canvas.left() + 28, baseline - 1, box, box);
            canvas.stream().stroke();
            canvas.text(Style.REGULAR, size, canvas.left() + 28 + box + 6, baseline,
                    canvas.cut(Style.REGULAR, size, text, canvas.width() - 28 - box - 6), Color.BLACK);
        });
    }

    /** "Ngân hàng ... - Chi nhánh ...", or the bank alone. */
    public static String bank(Letterhead head) {
        return filled(head.bankBranch()) ? head.bankName() + " - " + head.bankBranch() : head.bankName();
    }

    /**
     * An amount in words under the amount: the words settle a misread figure, and a voucher for money must carry them.
     * Italic, as accounting papers set it. Left out for an amount that is not whole dong or not known - a document is
     * drawn, not refused, for want of it.
     *
     * @param label "Số tiền bằng chữ:", already in the paper's language
     */
    public static void inWords(List<Block> blocks, String label, BigDecimal amount) {
        VietnameseAmountInWords.ofWhole(amount).ifPresent(words -> blocks.add(
                Paragraph.of(Run.of(label + " "), new Run(words + ".", Style.BOLD_ITALIC)).align(Align.LEFT)));
    }

    /**
     * A signatory's title as a signature block heads it: in capitals ("GIÁM ĐỐC"); null when not recorded, so the
     * block is headed by its party alone.
     */
    public static String position(String title) {
        return filled(title) ? title.strip().toUpperCase(Locale.forLanguageTag("vi")) : null;
    }

    /** The page of a paper two parties sign: black alone, its number at the foot of every page. */
    public static Layout official(ResourceBundle t, String number, String watermark) {
        return Layout.official(filled(number) ? t.getString("document.number") + " " + number : null, null,
                t.getString("document.page"), watermark);
    }

    /** The page of a commercial paper: the brand colour, its number and our name at the foot of every page. */
    public static Layout commercial(ResourceBundle t, String number, Letterhead head, String watermark) {
        return Layout.commercial(ACCENT, filled(number) ? t.getString("document.number") + " " + number : null,
                head.name(), t.getString("document.page"), watermark);
    }

    /** The page of an accounting voucher: a commercial paper's margins, in black - a ministry's form has no colour. */
    public static Layout voucher(ResourceBundle t, String number, Letterhead head) {
        return Layout.commercial(null, filled(number) ? t.getString("document.number") + " " + number : null,
                head.name(), t.getString("document.page"), null);
    }

    public static boolean filled(String value) {
        return value != null && !value.isBlank();
    }

    /** {@code value}, or the dotted line a person fills in by hand where it is not known yet. */
    public static String orBlank(ResourceBundle t, String value) {
        return filled(value) ? value : t.getString("document.blank");
    }
}
