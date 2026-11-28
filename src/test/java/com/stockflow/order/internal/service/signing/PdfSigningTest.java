package com.stockflow.order.internal.service.signing;

import com.stockflow.common.document.DocumentFonts;
import com.stockflow.order.internal.service.signing.SignedPdfInspector.Inspection;
import com.stockflow.order.internal.service.signing.SignedPdfInspector.Kind;
import com.stockflow.order.internal.service.signing.SignedPdfInspector.Reasons;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.internal.service.signing.SignedPdfInspector.Additions;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

import static com.stockflow.order.internal.service.signing.SigningTestSupport.pdf;
import static com.stockflow.order.internal.service.signing.SigningTestSupport.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The signing machinery end to end, without a database: a PDF signed as the company and looked at again, in the cases a
 * person's upload makes (changed, appended to, another contract, a scan, rubbish).
 */
class PdfSigningTest {

    private static TestCertificateSigner signer;
    private static final PadesSigner PADES = new PadesSigner();
    private static final SignedPdfInspector INSPECTOR = new SignedPdfInspector();

    @BeforeAll
    static void generateOnce() {
        signer = SigningTestSupport.signer();
        signer.identity();
    }

    private static byte[] signed(byte[] pdf) {
        return PADES.sign(pdf, signer, "Nguyen Van A", "Sign contract", "Viet Nam", Instant.now());
    }

    /** What a change does when it is saved as an incremental update of {@code pdf}: {@code pdf} followed by the update. */
    private static byte[] updated(byte[] pdf, PdfChange change) {
        try (PDDocument document = Loader.loadPDF(pdf); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            change.apply(document);
            document.saveIncremental(out);
            byte[] result = out.toByteArray();
            assertThat(Arrays.copyOf(result, pdf.length)).as("an incremental update").isEqualTo(pdf);
            return result;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @FunctionalInterface
    private interface PdfChange {
        void apply(PDDocument document) throws IOException;
    }

    private static CertificatePage.Content certificateContent(byte[] contract, String code,
                                                              CertificatePage.CompanySignature company) {
        return new CertificatePage.Content("045/2027/HDMB", 2, java.util.HexFormat.of().formatHex(sha256(contract)),
                new CertificatePage.CustomerSignature("Le Thi B", "Giam doc", "b***@example.com", Instant.now(),
                        "203.0.113.7", CertificatePage.Method.OTP),
                company, code, "https://portal.example.vn/xac-minh-hop-dong/" + code, company != null);
    }

    @Test
    @DisplayName("a signed PDF is valid, covers the whole file and begins with the sent document")
    void signedPdfIsValid() {
        byte[] sent = pdf(2);
        byte[] signed = signed(sent);

        Inspection inspection = INSPECTOR.inspect(signed, sha256(sent), sent.length);

        assertThat(inspection.kind()).isEqualTo(Kind.DIGITAL);
        assertThat(inspection.valid()).isTrue();
        assertThat(inspection.coversWholeDocument()).isTrue();
        assertThat(inspection.matchesSentDocument()).isTrue();
        assertThat(inspection.chainChecked()).isFalse();
        assertThat(inspection.reason()).isNull();
        assertThat(inspection.acceptedAsSentContract()).isTrue();
        assertThat(inspection.signers()).singleElement().satisfies(facts -> {
            assertThat(facts.subject()).contains("Cong ty Test An Phat");
            assertThat(facts.issuer()).contains("STOCKFLOW TEST CA");
            assertThat(facts.certificateValidAtSigning()).isTrue();
            assertThat(facts.signedAt()).isNotNull();
        });
        // The sent bytes are untouched: an incremental update, not a rewrite.
        assertThat(Arrays.copyOf(signed, sent.length)).isEqualTo(sent);
    }

    @Test
    @DisplayName("the signature is PAdES: ETSI.CAdES.detached over SHA-256 with the signing certificate bound")
    void signatureIsPades() throws Exception {
        byte[] signed = signed(pdf(1));
        String text = new String(signed, StandardCharsets.ISO_8859_1);
        assertThat(text).contains("/SubFilter /ETSI.CAdES.detached").contains("/ByteRange");
        try (PDDocument document = Loader.loadPDF(signed)) {
            var signature = document.getSignatureDictionaries().get(0);
            var cms = new org.bouncycastle.cms.CMSSignedData(signature.getContents(signed));
            var info = cms.getSignerInfos().getSigners().iterator().next();
            assertThat(info.getDigestAlgOID()).isEqualTo("2.16.840.1.101.3.4.2.1");
            assertThat(info.getSignedAttributes().get(
                    org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.id_aa_signingCertificateV2)).isNotNull();
            assertThat(info.getSignedAttributes().get(org.bouncycastle.asn1.cms.CMSAttributes.signingTime)).isNull();
        }
    }

    @Test
    @DisplayName("one changed byte inside the signed content breaks the signature")
    void tamperedContentIsInvalid() {
        byte[] sent = pdf(1);
        byte[] tampered = SigningTestSupport.replaceFirst(signed(sent), "Contract page 1", "Contract page 9");

        Inspection inspection = INSPECTOR.inspect(tampered, sha256(sent), sent.length);

        assertThat(inspection.kind()).isEqualTo(Kind.DIGITAL);
        assertThat(inspection.valid()).isFalse();
        assertThat(inspection.reason()).isIn(Reasons.CONTENT_CHANGED, Reasons.SIGNATURE_INVALID);
        assertThat(inspection.acceptedAsSentContract()).isFalse();
    }

    @Test
    @DisplayName("bytes appended after the last signature are not covered by it")
    void appendedBytesAreNotCovered() {
        byte[] sent = pdf(1);
        byte[] signed = signed(sent);
        byte[] longer = Arrays.copyOf(signed, signed.length + 16);
        System.arraycopy("\n% not signed  \n".getBytes(StandardCharsets.US_ASCII), 0, longer, signed.length, 16);

        Inspection inspection = INSPECTOR.inspect(longer, sha256(sent), sent.length);

        assertThat(inspection.valid()).isTrue();
        assertThat(inspection.coversWholeDocument()).isFalse();
        assertThat(inspection.reason()).isEqualTo(Reasons.NOT_WHOLE_DOCUMENT);
        assertThat(inspection.acceptedAsSentContract()).isFalse();
    }

    @Test
    @DisplayName("a valid signature over another document does not match the one that was sent")
    void anotherDocumentDoesNotMatch() {
        byte[] sent = pdf(1);
        byte[] other = pdf(3);

        Inspection inspection = INSPECTOR.inspect(signed(other), sha256(sent), sent.length);

        assertThat(inspection.valid()).isTrue();
        assertThat(inspection.coversWholeDocument()).isTrue();
        assertThat(inspection.matchesSentDocument()).isFalse();
        assertThat(inspection.reason()).isEqualTo(Reasons.NOT_SENT_DOCUMENT);
    }

    @Test
    @DisplayName("a signature made before the sent document's end is not a signature over the sent revision")
    void sentLengthLongerThanTheSignatureStart() {
        byte[] sent = pdf(1);
        byte[] signed = signed(sent);
        // The caller says the sent document was longer than where the signature starts: the prefix hash cannot match.
        Inspection inspection = INSPECTOR.inspect(signed, sha256(sent), sent.length + 40);
        assertThat(inspection.matchesSentDocument()).isFalse();
    }

    @Test
    @DisplayName("a PDF without a signature is a scan: accepted as a kind, never valid")
    void plainPdfIsAScan() {
        Inspection inspection = INSPECTOR.inspect(pdf(1));

        assertThat(inspection.kind()).isEqualTo(Kind.SCAN);
        assertThat(inspection.valid()).isFalse();
        assertThat(inspection.signers()).isEmpty();
        assertThat(inspection.reason()).isEqualTo(Reasons.NO_DIGITAL_SIGNATURE);
    }

    @Test
    @DisplayName("rubbish, an empty file, an oversize one and a truncated PDF are described, not thrown")
    void unreadableFilesAreDescribed() {
        assertThat(INSPECTOR.inspect(null).reason()).isEqualTo(Reasons.EMPTY);
        assertThat(INSPECTOR.inspect(new byte[0]).kind()).isEqualTo(Kind.UNREADABLE);
        assertThat(INSPECTOR.inspect("hello, not a pdf".getBytes(StandardCharsets.UTF_8)).reason())
                .isEqualTo(Reasons.NOT_A_PDF);
        assertThat(INSPECTOR.inspect(new byte[SignedPdfInspector.MAX_BYTES + 1]).reason())
                .isEqualTo(Reasons.TOO_LARGE);
        byte[] truncated = Arrays.copyOf(signed(pdf(1)), 300);
        // PDFBox repairs a cut file as far as it can: what is left may read as an unsigned PDF (a scan) or not read
        // at all, but never as a valid signed contract, and never as an exception.
        Inspection broken = INSPECTOR.inspect(truncated);
        assertThat(broken.acceptedAsSentContract()).isFalse();
        assertThat(broken.valid()).isFalse();
        byte[] header = "%PDF-1.7\n1 0 obj << /Type /Catalog /Pages 99 0 R >> endobj\n%%EOF".getBytes(
                StandardCharsets.US_ASCII);
        assertThat(INSPECTOR.inspect(header).kind()).isEqualTo(Kind.UNREADABLE);
    }

    @Test
    @DisplayName("a forged ByteRange that leaves the file is refused")
    void forgedByteRangeIsRefused() {
        byte[] signed = signed(pdf(1));
        String text = new String(signed, StandardCharsets.ISO_8859_1);
        int at = text.indexOf("/ByteRange");
        int open = text.indexOf('[', at);
        int close = text.indexOf(']', open);
        // [0 a b c] -> [0 a b 99999999]: same length is not needed, parsing is by tokens, but keep offsets by padding.
        String range = text.substring(open + 1, close);
        String[] parts = range.trim().split("\\s+");
        String forged = (parts[0] + " " + parts[1] + " " + parts[2] + " 99999999").substring(0,
                Math.min(range.length(), (parts[0] + " " + parts[1] + " " + parts[2] + " 99999999").length()));
        byte[] copy = signed.clone();
        byte[] replacement = String.format("%-" + range.length() + "s", forged).getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(replacement, 0, copy, open + 1, range.length());

        Inspection inspection = INSPECTOR.inspect(copy);

        assertThat(inspection.valid()).isFalse();
        assertThat(inspection.reason()).isIn(Reasons.BYTE_RANGE_INVALID, Reasons.MALFORMED);
    }

    @Test
    @DisplayName("the signer asks for a SHA-256 digest and nothing else")
    void signerRefusesAWrongDigest() {
        assertThatThrownBy(() -> signer.signDigest(new byte[20])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the identity says the certificate is a test one, self-issued, with the seller as subject")
    void identityOfTheTestCertificate() {
        SignerIdentity identity = signer.identity();
        assertThat(identity.testCertificate()).isTrue();
        assertThat(identity.subject()).contains("CN=Cong ty Test An Phat").contains("O=Cong ty Test An Phat");
        assertThat(identity.issuer()).isEqualTo(identity.subject());
        assertThat(identity.notAfter()).isAfter(Instant.now().plusSeconds(86_400L * 365));
        assertThat(identity.chain()).hasSize(1);
    }

    @Test
    @DisplayName("the certificate page is one Vietnamese page with the code, the hash and the test notice; added as an "
            + "incremental update, the company's signed file begins with the very bytes that were sent and inspects as "
            + "the sent contract plus its page")
    void certificatePageAppendedAndSigned() throws Exception {
        CertificatePage page = new CertificatePage(new DocumentFonts());
        byte[] contract = pdf(2);
        String code = VerificationCodes.newCode();
        byte[] certificate = page.render(certificateContent(contract, code, new CertificatePage.CompanySignature(
                "Nguyen Van A", "Giam doc", "an.nguyen", Instant.now(), signer.identity().subject(),
                signer.identity().issuer())));

        try (PDDocument document = Loader.loadPDF(certificate)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("TRANG CHỨNG NHẬN KÝ ĐIỆN TỬ").contains("045/2027/HDMB")
                    .contains(VerificationCodes.grouped(code)).contains("Chứng thư số dùng thử của hệ thống")
                    .contains("Le Thi B");
        }

        byte[] merged = page.append(contract, certificate);
        assertThat(Arrays.copyOf(merged, contract.length)).as("the sent bytes, untouched").isEqualTo(contract);
        try (PDDocument document = Loader.loadPDF(merged)) {
            assertThat(document.getNumberOfPages()).isEqualTo(3);
            PDFTextStripper last = new PDFTextStripper();
            last.setStartPage(3);
            assertThat(last.getText(document)).contains("TRANG CHỨNG NHẬN KÝ ĐIỆN TỬ");
        }
        byte[] companySigned = signed(merged);
        assertThat(Arrays.copyOf(companySigned, contract.length)).isEqualTo(contract);
        assertThat(INSPECTOR.inspect(companySigned, sha256(merged), merged.length).acceptedAsSentContract()).isTrue();
        // Against the PDF the customer was sent: the sent pages unchanged, and the certificate page after them.
        Inspection againstSent = INSPECTOR.inspect(companySigned, sha256(contract), contract.length);
        assertThat(againstSent.matchesSentDocument()).isTrue();
        assertThat(againstSent.acceptedAsSentContract()).isTrue();
        // Nobody else's file may add a page: in strict mode the very same file is content changed after it was sent.
        Inspection strict = INSPECTOR.inspect(companySigned, sha256(contract), contract.length, Additions.NONE);
        assertThat(strict.matchesSentDocument()).isFalse();
        assertThat(strict.reason()).isEqualTo(Reasons.CONTENT_CHANGED_AFTER_SENT);
    }

    @Test
    @DisplayName("the PDF to sign outside prints that it was prepared to be signed outside, no signer, account or time; "
            + "signed outside, it begins with the sent bytes and inspects as the sent contract plus its page")
    void outsideDocumentSignedOutside() throws Exception {
        CertificatePage page = new CertificatePage(new DocumentFonts());
        byte[] contract = pdf(2);
        String code = VerificationCodes.newCode();
        byte[] outsidePage = page.render(certificateContent(contract, code, null));
        try (PDDocument document = Loader.loadPDF(outsidePage)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Chuẩn bị để ký ngoài hệ thống").contains(VerificationCodes.grouped(code))
                    .doesNotContain("Tài khoản thực hiện").doesNotContain("dùng thử");
            // The customer signed: their time is printed; the company's section has none.
            assertThat(text.substring(text.indexOf("3. Bên bán ký"))).doesNotContain("Thời điểm ký")
                    .doesNotContain("Người ký");
        }
        byte[] toSign = page.append(contract, outsidePage);
        byte[] signedOutside = PADES.sign(toSign, SigningTestSupport.signer(), "Someone outside", "r", "l",
                Instant.now());

        assertThat(Arrays.copyOf(signedOutside, contract.length)).isEqualTo(contract);
        assertThat(INSPECTOR.inspect(signedOutside, sha256(toSign), toSign.length, Additions.NONE)
                .acceptedAsSentContract()).as("against exactly the PDF handed out").isTrue();
        assertThat(INSPECTOR.inspect(signedOutside, sha256(contract), contract.length).matchesSentDocument())
                .as("against the PDF the customer was sent").isTrue();
    }

    // ---- shadow attacks: an incremental update before the signature ------------------------------------------------

    @Test
    @DisplayName("a page's content replaced in an update before the signature: the signature holds, the file begins "
            + "with the sent bytes, and it is still not the sent contract")
    void contentReplacedBeforeSigningIsRejected() {
        byte[] sent = pdf(2);
        byte[] changed = updated(sent, document -> {
            PDPage first = document.getPage(0);
            try (PDPageContentStream content = new PDPageContentStream(document, first,
                    PDPageContentStream.AppendMode.OVERWRITE, false)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("Price: 1 VND");
                content.endText();
            }
        });

        Inspection inspection = INSPECTOR.inspect(signed(changed), sha256(sent), sent.length, Additions.NONE);

        assertThat(inspection.valid()).isTrue();
        assertThat(inspection.coversWholeDocument()).isTrue();
        assertThat(inspection.matchesSentDocument()).isFalse();
        assertThat(inspection.reason()).isEqualTo(Reasons.CONTENT_CHANGED_AFTER_SENT);
        assertThat(inspection.acceptedAsSentContract()).isFalse();
        assertThat(INSPECTOR.inspect(signed(changed), sha256(sent), sent.length).acceptedAsSentContract())
                .as("the company's allowance is one page after the last, not a changed page").isFalse();
    }

    @Test
    @DisplayName("an annotation laid over a page in an update before the signature is rejected")
    void annotationAddedBeforeSigningIsRejected() {
        byte[] sent = pdf(2);
        byte[] covered = updated(sent, document -> {
            COSDictionary square = new COSDictionary();
            square.setItem(COSName.TYPE, COSName.ANNOT);
            square.setItem(COSName.SUBTYPE, COSName.getPDFName("Square"));
            square.setItem(COSName.RECT, new PDRectangle(60, 690, 300, 30).getCOSArray());
            square.setItem(COSName.IC, COSArray.ofCOSIntegers(List.of(1, 1, 1)));
            document.getPage(0).setAnnotations(List.of(PDAnnotation.createAnnotation(square)));
        });

        Inspection inspection = INSPECTOR.inspect(signed(covered), sha256(sent), sent.length, Additions.NONE);

        assertThat(inspection.valid()).isTrue();
        assertThat(inspection.matchesSentDocument()).isFalse();
        assertThat(inspection.reason()).isEqualTo(Reasons.CONTENT_CHANGED_AFTER_SENT);
    }

    @Test
    @DisplayName("a page added by anyone but the company, before the signature, is terms nobody sent")
    void pageAddedBeforeSigningIsRejectedInStrictMode() {
        byte[] sent = pdf(1);
        byte[] longer = updated(sent, document -> document.addPage(new PDPage(PDRectangle.A4)));

        Inspection inspection = INSPECTOR.inspect(signed(longer), sha256(sent), sent.length, Additions.NONE);

        assertThat(inspection.matchesSentDocument()).isFalse();
        assertThat(inspection.reason()).isEqualTo(Reasons.CONTENT_CHANGED_AFTER_SENT);
    }

    @Test
    @DisplayName("a clean signature over the sent PDF, and an update that changes nothing drawn, are the sent contract")
    void cleanSignatureIsAccepted() {
        byte[] sent = pdf(2);
        assertThat(INSPECTOR.inspect(signed(sent), sha256(sent), sent.length, Additions.NONE).acceptedAsSentContract())
                .isTrue();
        // Metadata changed, the pages not: no visible difference, no refusal.
        byte[] retitled = updated(sent, document -> document.getDocumentInformation().setTitle("Hop dong"));
        assertThat(INSPECTOR.inspect(signed(retitled), sha256(sent), sent.length, Additions.NONE)
                .acceptedAsSentContract()).isTrue();
    }

    // ---- bounded inspection -------------------------------------------------------------------------------------

    @Test
    @DisplayName("a Flate bomb (64 MiB of zeros in a few kilobytes) is described as too large before it is decoded")
    void flateBombIsTooLarge() throws Exception {
        byte[] bomb;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            COSStream stream = document.getDocument().createCOSStream();
            try (OutputStream raw = stream.createRawOutputStream();
                 DeflaterOutputStream deflated = new DeflaterOutputStream(raw)) {
                byte[] zeros = new byte[1024 * 1024];
                for (int i = 0; i < 64; i++) {
                    deflated.write(zeros);
                }
            }
            stream.setItem(COSName.FILTER, COSName.FLATE_DECODE);
            page.setContents(new PDStream(stream));
            document.save(out, org.apache.pdfbox.pdfwriter.compress.CompressParameters.NO_COMPRESSION);
            bomb = out.toByteArray();
        }
        assertThat(bomb.length).as("small on the wire").isLessThan(1024 * 1024);

        Inspection inspection = INSPECTOR.inspect(bomb);

        assertThat(inspection.kind()).isEqualTo(Kind.UNREADABLE);
        assertThat(inspection.reason()).isEqualTo(Reasons.TOO_LARGE);
    }

    @Test
    @DisplayName("past the inspections allowed at once, a caller is refused with CONTRACT_INSPECTION_BUSY, not queued "
            + "forever")
    void tooManyInspectionsAtOnceAreRefused() {
        SignedPdfInspector none = new SignedPdfInspector(0, 10);
        assertThatThrownBy(() -> none.inspect(pdf(1))).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONTRACT_INSPECTION_BUSY));
        assertThat(ErrorCode.CONTRACT_INSPECTION_BUSY.httpStatus()).isEqualTo(503);
        // Not a property of the file: the cheap refusals still answer without a permit.
        assertThat(none.inspect(new byte[0]).reason()).isEqualTo(Reasons.EMPTY);
    }

    @Test
    @DisplayName("the page without a storefront address carries the code in text and no QR code; no test notice for a "
            + "real certificate")
    void certificatePageWithoutQr() throws Exception {
        CertificatePage page = new CertificatePage(new DocumentFonts());
        String code = VerificationCodes.newCode();
        byte[] bytes = page.render(new CertificatePage.Content("1/2027", 1, "ab".repeat(32),
                new CertificatePage.CustomerSignature("A", null, null, Instant.now(), null,
                        CertificatePage.Method.UPLOAD),
                new CertificatePage.CompanySignature("B", null, null, Instant.now(), null, null), code, null, false));
        try (PDDocument document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains(VerificationCodes.grouped(code)).doesNotContain("dùng thử");
        }
    }

    @Test
    @DisplayName("verification codes are 12 characters of an unambiguous alphabet, and typed forms normalise")
    void verificationCodes() {
        String code = VerificationCodes.newCode();
        assertThat(code).hasSize(12).matches("[ABCDEFGHJKMNPQRSTUVWXYZ2-9]{12}");
        assertThat(VerificationCodes.normalize(VerificationCodes.grouped(code).toLowerCase())).isEqualTo(code);
        assertThat(VerificationCodes.normalize("ABC")).isNull();
        assertThat(VerificationCodes.normalize("0OIL0OIL0OIL")).isNull();
        assertThat(VerificationCodes.normalize(null)).isNull();
        assertThat(java.util.stream.Stream.generate(VerificationCodes::newCode).limit(2000).distinct().count())
                .isEqualTo(2000);
    }

    @Test
    @DisplayName("a PKCS#12 from the environment is the signer; self-issued, it still counts as a test certificate")
    void keystoreSigner() {
        String keystore = SigningTestSupport.keystore("s3cret", "Real Company");
        var properties = SigningTestSupport.properties(keystore, "s3cret", "x");
        var fromKeystore = new TestCertificateSigner(properties, SigningTestSupport.seller(),
                new SigningSecrets(properties, SigningTestSupport.testProfile()), null, Clock.systemUTC());

        assertThat(fromKeystore.identity().subject()).contains("CN=Real Company");
        assertThat(fromKeystore.identity().testCertificate()).isTrue();
        byte[] sent = pdf(1);
        Inspection inspection = INSPECTOR.inspect(
                PADES.sign(sent, fromKeystore, "X", "r", "l", Instant.now()), sha256(sent), sent.length);
        assertThat(inspection.acceptedAsSentContract()).isTrue();
        assertThat(inspection.signers().get(0).subject()).contains("Real Company");

        var wrongPassword = SigningTestSupport.properties(keystore, "nope", "x");
        assertThatThrownBy(() -> new TestCertificateSigner(wrongPassword, SigningTestSupport.seller(),
                new SigningSecrets(wrongPassword, SigningTestSupport.testProfile()), null, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CONTRACT_SIGNING_KEYSTORE")
                .message().doesNotContain("nope").doesNotContain(keystore);
    }

    @Test
    @DisplayName("every profile but local and test - prod, and dev, which the VPS runs - refuses to start without a "
            + "CONTRACT_SIGNING_SECRET of 32 characters or more; local and test warn and go on")
    void serversNeedASecret() {
        String strong = "x".repeat(SigningSecrets.MIN_LENGTH);
        for (String profile : new String[] {"prod", "dev"}) {
            MockEnvironment server = new MockEnvironment();
            server.setActiveProfiles(profile);
            assertThatThrownBy(() -> new SigningSecrets(SigningTestSupport.properties("", "", ""), server))
                    .as(profile).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CONTRACT_SIGNING_SECRET").hasMessageContaining("openssl rand -base64 48");
            assertThatThrownBy(() -> new SigningSecrets(SigningTestSupport.properties("", "", "short"), server))
                    .as(profile + ", too short").isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("32").message().doesNotContain("short");
            new SigningSecrets(SigningTestSupport.properties("", "", strong), server);
        }
        assertThatThrownBy(() -> new SigningSecrets(SigningTestSupport.properties("", "", ""), new MockEnvironment()))
                .as("no profile is not a laptop").isInstanceOf(IllegalStateException.class);
        for (String profile : new String[] {"local", "test"}) {
            MockEnvironment laptop = new MockEnvironment();
            laptop.setActiveProfiles(profile);
            new SigningSecrets(SigningTestSupport.properties("", "", ""), laptop);
            new SigningSecrets(SigningTestSupport.properties("", "", "short"), laptop);
        }
        // The secret and a purpose make the key: another purpose, another key.
        var secrets = new SigningSecrets(SigningTestSupport.properties("", "", strong), new MockEnvironment());
        assertThat(secrets.derive("a")).hasSize(32).isNotEqualTo(secrets.derive("b"));
    }
}
