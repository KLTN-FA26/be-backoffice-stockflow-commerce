package com.stockflow.order.internal.service.signing;

import com.stockflow.common.config.SellerProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.function.Supplier;

/** What the signing tests share: a signer that keeps its key in memory, small PDFs, a PKCS#12. */
final class SigningTestSupport {

    static final String SECRET = "a-long-random-test-secret-0123456789";

    private SigningTestSupport() {
    }

    static SellerProperties seller() {
        return new SellerProperties("Cong ty Test An Phat", "0312345678", "1 Le Loi, TP HCM", "Nguyen Van A",
                "Giam doc", null, null, null, null, null, null, null, null);
    }

    static ContractSigningProperties properties(String keystore, String password, String secret) {
        return new ContractSigningProperties(keystore, password, secret, "https://portal.example.vn",
                java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(1), 5);
    }

    /** A {@link ContractSigningKeys} that stores in memory: the table is the integration test's. */
    static ContractSigningKeys memoryKeys() {
        return new ContractSigningKeys(null) {
            private Stored stored;

            @Override
            public synchronized Stored loadOrCreate(String purpose, Supplier<Stored> generator) {
                if (stored == null) {
                    stored = generator.get();
                }
                return stored;
            }
        };
    }

    static TestCertificateSigner signer() {
        return new TestCertificateSigner(properties("", "", SECRET), seller(),
                new SigningSecrets(properties("", "", SECRET), new MockEnvironment()), memoryKeys(), Clock.systemUTC());
    }

    /** A PDF with {@code pages} pages, each with a line of text; different counts make different documents. */
    static byte[] pdf(int pages) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.OVERWRITE,
                        false)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 700);
                    content.showText("Contract page " + i);
                    content.endText();
                }
            }
            // Uncompressed, so a test can change a visible byte without breaking the file structure.
            document.save(out, org.apache.pdfbox.pdfwriter.compress.CompressParameters.NO_COMPRESSION);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** A base64 PKCS#12 holding a fresh self-issued RSA 2048 certificate, under {@code password}. */
    static String keystore(String password, String commonName) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            X500Name name = new X500Name("CN=" + commonName + ", O=Test");
            Instant now = Instant.now();
            X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                    CertificateDates.builder(name, BigInteger.valueOf(42), now.minusSeconds(3600),
                            now.plusSeconds(86_400), name, pair.getPublic())
                            .build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));
            KeyStore keystore = KeyStore.getInstance("PKCS12");
            keystore.load(null, null);
            keystore.setKeyEntry("company", pair.getPrivate(), password.toCharArray(),
                    new java.security.cert.Certificate[] {certificate});
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            keystore.store(out, password.toCharArray());
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (java.security.GeneralSecurityException | IOException | org.bouncycastle.operator.OperatorCreationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** {@code bytes} with the first occurrence of {@code from} replaced by {@code to}, of the same length. */
    static byte[] replaceFirst(byte[] bytes, String from, String to) {
        if (from.length() != to.length()) {
            throw new IllegalArgumentException("Same length only: offsets must not move");
        }
        String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        int at = text.indexOf(from);
        if (at < 0) {
            throw new IllegalStateException(from + " not found");
        }
        byte[] copy = bytes.clone();
        byte[] replacement = to.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        System.arraycopy(replacement, 0, copy, at, replacement.length);
        return copy;
    }
}
