package com.stockflow.order.internal.service.signing;

import com.stockflow.common.document.DocumentFonts;
import com.stockflow.order.internal.service.signing.SignedPdfInspector.Inspection;
import com.stockflow.order.internal.service.signing.SignedPdfInspector.Kind;
import com.stockflow.order.internal.service.signing.SignedPdfInspector.Reasons;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;

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
    @DisplayName("the certificate page is one Vietnamese page with the code, the hash and the test notice; appended, a PDF "
            + "of one more page that signs and inspects as the contract plus its page")
    void certificatePageAppendedAndSigned() throws Exception {
        CertificatePage page = new CertificatePage(new DocumentFonts());
        byte[] contract = pdf(2);
        String code = VerificationCodes.newCode();
        byte[] certificate = page.render(new CertificatePage.Content("045/2027/HDMB", 2,
                java.util.HexFormat.of().formatHex(sha256(contract)),
                new CertificatePage.CustomerSignature("Le Thi B", "Giam doc", "b***@example.com", Instant.now(),
                        "203.0.113.7", CertificatePage.Method.OTP),
                new CertificatePage.CompanySignature("Nguyen Van A", "Giam doc", "an.nguyen", Instant.now(),
                        signer.identity().subject(), signer.identity().issuer()),
                code, "https://portal.example.vn/xac-minh-hop-dong/" + code, true));

        try (PDDocument document = Loader.loadPDF(certificate)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("TRANG CHỨNG NHẬN KÝ ĐIỆN TỬ").contains("045/2027/HDMB")
                    .contains(VerificationCodes.grouped(code)).contains("Chứng thư số dùng thử của hệ thống")
                    .contains("Le Thi B");
        }

        byte[] merged = page.append(contract, certificate);
        try (PDDocument document = Loader.loadPDF(merged)) {
            assertThat(document.getNumberOfPages()).isEqualTo(3);
        }
        Inspection inspection = INSPECTOR.inspect(signed(merged), sha256(merged), merged.length);
        assertThat(inspection.acceptedAsSentContract()).isTrue();
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
                new SigningSecrets(properties, new MockEnvironment()), null, Clock.systemUTC());

        assertThat(fromKeystore.identity().subject()).contains("CN=Real Company");
        assertThat(fromKeystore.identity().testCertificate()).isTrue();
        byte[] sent = pdf(1);
        Inspection inspection = INSPECTOR.inspect(
                PADES.sign(sent, fromKeystore, "X", "r", "l", Instant.now()), sha256(sent), sent.length);
        assertThat(inspection.acceptedAsSentContract()).isTrue();
        assertThat(inspection.signers().get(0).subject()).contains("Real Company");

        var wrongPassword = SigningTestSupport.properties(keystore, "nope", "x");
        assertThatThrownBy(() -> new TestCertificateSigner(wrongPassword, SigningTestSupport.seller(),
                new SigningSecrets(wrongPassword, new MockEnvironment()), null, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CONTRACT_SIGNING_KEYSTORE")
                .message().doesNotContain("nope").doesNotContain(keystore);
    }

    @Test
    @DisplayName("the prod profile refuses to start without CONTRACT_SIGNING_SECRET; other profiles warn and go on")
    void productionNeedsASecret() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        assertThatThrownBy(() -> new SigningSecrets(SigningTestSupport.properties("", "", ""), production))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CONTRACT_SIGNING_SECRET");
        new SigningSecrets(SigningTestSupport.properties("", "", "set"), production);
        new SigningSecrets(SigningTestSupport.properties("", "", ""), new MockEnvironment());
        // The secret and a purpose make the key: another purpose, another key.
        var secrets = new SigningSecrets(SigningTestSupport.properties("", "", "set"), new MockEnvironment());
        assertThat(secrets.derive("a")).hasSize(32).isNotEqualTo(secrets.derive("b"));
    }
}
