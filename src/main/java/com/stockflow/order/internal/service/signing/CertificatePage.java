package com.stockflow.order.internal.service.signing;

import com.stockflow.common.document.BusinessDocument;
import com.stockflow.common.document.BusinessDocument.Block;
import com.stockflow.common.document.BusinessDocument.Field;
import com.stockflow.common.document.BusinessDocument.Figure;
import com.stockflow.common.document.BusinessDocument.Heading;
import com.stockflow.common.document.BusinessDocument.Paragraph;
import com.stockflow.common.document.BusinessDocument.Run;
import com.stockflow.common.document.BusinessDocument.Title;
import com.stockflow.common.document.DocumentFonts;
import com.stockflow.common.document.DocumentParts;
import com.stockflow.common.label.LabelDrawing;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * The certificate page of a signed contract: one A4 page in Vietnamese, appended to the contract's PDF before the
 * company signs, that says what was signed (the SHA-256 of the PDF the customer was sent), who signed for each side and
 * how, and where anyone can check the file ({@code <storefront>/xac-minh-hop-dong/<code>}, as a QR code and as text).
 * While the company's certificate is the system's own test certificate, the page says that no certification authority
 * issued it.
 *
 * <p>Set with the same fonts and page kit as every contract paper ({@link BusinessDocument}), so diacritics draw and the
 * page looks like part of the contract. All words are the {@code contract.certificate.*} keys of the bundles.</p>
 */
@Component
public class CertificatePage {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy").withZone(VIETNAM);
    private static final float QR_SIDE_MM = 32;

    /** How the customer signed. */
    public enum Method { OTP, UPLOAD, STAFF_RECORDED }

    /**
     * @param email  the address the one-time code went to; null when the customer did not sign by code
     * @param ip     null when not recorded
     */
    public record CustomerSignature(String signerName, String signerTitle, String email, Instant signedAt, String ip,
                                    Method method) {
    }

    /**
     * @param accountName the staff account that pressed the button (the signer may be the authorised person on a
     *                    power of attorney while an assistant's account acts)
     * @param certificateSubject / {@code certificateIssuer} the certificate the PDF signature will carry
     */
    public record CompanySignature(String signerName, String signerTitle, String accountName, Instant signedAt,
                                   String certificateSubject, String certificateIssuer) {
    }

    /**
     * @param sentDocumentSha256 lower-case hex SHA-256 of the PDF the customer was sent (the revision's document)
     * @param verificationCode   as {@link VerificationCodes#newCode()} made it
     * @param verificationUrl    where the QR code points, or null for a page with the code in text only
     * @param testCertificate    whether to print the notice that the certificate is the system's own
     */
    public record Content(String contractNumber, int revision, String sentDocumentSha256,
                          CustomerSignature customer, CompanySignature company, String verificationCode,
                          String verificationUrl, boolean testCertificate) {
    }

    private final DocumentFonts fonts;

    CertificatePage(DocumentFonts fonts) {
        this.fonts = fonts;
    }

    /** The one-page PDF. */
    public byte[] render(Content content) {
        ResourceBundle t = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Title(t.getString("contract.certificate.title"),
                t.getString("contract.certificate.subtitle").formatted(content.contractNumber(), content.revision()),
                t.getString("contract.certificate.note")));

        blocks.add(new Heading(t.getString("contract.certificate.document")));
        List<Field> document = new ArrayList<>();
        add(document, t, "contractNumber", content.contractNumber());
        add(document, t, "revision", String.valueOf(content.revision()));
        add(document, t, "sha256", content.sentDocumentSha256());
        blocks.add(new BusinessDocument.Fields(document, 0));

        CustomerSignature customer = content.customer();
        blocks.add(new Heading(t.getString("contract.certificate.customer")));
        List<Field> buyer = new ArrayList<>();
        add(buyer, t, "signerName", customer.signerName());
        add(buyer, t, "signerTitle", customer.signerTitle());
        add(buyer, t, "email", customer.email());
        add(buyer, t, "signedAt", when(customer.signedAt()));
        add(buyer, t, "ip", customer.ip());
        add(buyer, t, "method", t.getString(switch (customer.method()) {
            case OTP -> "contract.certificate.method.otp";
            case UPLOAD -> "contract.certificate.method.upload";
            case STAFF_RECORDED -> "contract.certificate.method.staff";
        }));
        blocks.add(new BusinessDocument.Fields(buyer, 0));

        CompanySignature company = content.company();
        blocks.add(new Heading(t.getString("contract.certificate.company")));
        List<Field> seller = new ArrayList<>();
        add(seller, t, "signerName", company.signerName());
        add(seller, t, "signerTitle", company.signerTitle());
        add(seller, t, "account", company.accountName());
        add(seller, t, "signedAt", when(company.signedAt()));
        add(seller, t, "certSubject", company.certificateSubject());
        add(seller, t, "certIssuer", company.certificateIssuer());
        blocks.add(new BusinessDocument.Fields(seller, 0));

        blocks.add(new Heading(t.getString("contract.certificate.verify")));
        List<Field> lookup = new ArrayList<>();
        add(lookup, t, "code", VerificationCodes.grouped(content.verificationCode()));
        add(lookup, t, "url", content.verificationUrl());
        blocks.add(new BusinessDocument.Fields(lookup, 0));
        blocks.add(Paragraph.of(t.getString("contract.certificate.verifyHint")));
        if (content.verificationUrl() != null) {
            blocks.add(qr(content.verificationUrl()));
        }
        if (content.testCertificate()) {
            blocks.add(Paragraph.of(new Run(t.getString("contract.certificate.testNotice"),
                    com.stockflow.common.document.Style.BOLD_ITALIC)));
        }
        return BusinessDocument.draw(fonts, DocumentParts.official(t, content.contractNumber(), null),
                t.getString("contract.certificate.title"), blocks);
    }

    /** {@code contract} followed by {@code certificatePage}: the contract's pages as they were, then the page. */
    public byte[] append(byte[] contract, byte[] certificatePage) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFMergerUtility merger = new PDFMergerUtility();
            merger.setDestinationStream(out);
            merger.addSource(new RandomAccessReadBuffer(contract));
            merger.addSource(new RandomAccessReadBuffer(certificatePage));
            merger.mergeDocuments(IOUtils.createMemoryOnlyStreamCache());
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException("The certificate page could not be appended", ex);
        }
    }

    private static void add(List<Field> fields, ResourceBundle t, String key, String value) {
        if (value != null && !value.isBlank()) {
            fields.add(Field.of(t.getString("contract.certificate." + key), value));
        }
    }

    private static String when(Instant instant) {
        return instant == null ? null : WHEN.format(instant);
    }

    private static Figure qr(String url) {
        float height = BusinessDocument.mm(QR_SIDE_MM) + 4;
        return new Figure(height, canvas -> {
            double pageMm = LabelDrawing.millimetres(PDRectangle.A4.getHeight());
            var page = new LabelDrawing.Cell(0, 0, LabelDrawing.millimetres(PDRectangle.A4.getWidth()), pageMm,
                    pageMm);
            canvas.stream().setNonStrokingColor(java.awt.Color.BLACK);
            LabelDrawing.qr(canvas.stream(), page, url, LabelDrawing.millimetres(canvas.left()),
                    pageMm - LabelDrawing.millimetres(canvas.top()), QR_SIDE_MM);
        });
    }
}
