package com.stockflow.invoicing.internal.service;

import com.stockflow.invoicing.internal.service.InvoiceXmlSignature.Check;
import com.stockflow.invoicing.internal.service.InvoiceXmlSignature.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seller's signature on an e-invoice's XML, against a real one: invoice C26TSE 357207, issued on SePay's sandbox
 * through {@code SepayEInvoiceProvider} on 2026-11-16 and signed by Mắt Bão's test certificate. Every case that is
 * not VALID is that same file changed in one place.
 */
class InvoiceXmlSignatureTest {

    private static String signed() throws IOException {
        try (InputStream in = InvoiceXmlSignatureTest.class.getResourceAsStream("/einvoice/sepay-sandbox-357207.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Check check(String xml) {
        return InvoiceXmlSignature.check(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theInvoiceAsIssuedIsValidAndSaysWhoSignedItAndWhen() throws IOException {
        Check check = check(signed());

        assertThat(check.status()).isEqualTo(Status.VALID);
        assertThat(check.valid()).isTrue();
        assertThat(check.signer()).isEqualTo("MẮT BÃO - KIỂM THỬ HĐĐT CÓ MÃ");
        assertThat(check.signerTaxCode()).isEqualTo("0302712571-999");
        assertThat(check.issuer()).isEqualTo("MATBAO-CA");
        assertThat(check.signingTime()).as("15:13:51 in Viet Nam").isEqualTo(Instant.parse("2026-11-16T08:13:51Z"));
        assertThat(check.certificateValidFrom()).isBefore(check.certificateValidTo());
        assertThat(check.certificateValidAtSigning()).isNotNull();
        assertThat(check.buyerSigned()).as("a business buyer does not sign").isFalse();
        assertThat(check.chainChecked()).isFalse();
    }

    @Test
    void anAmountChangedAfterSigningIsCaught() throws IOException {
        String changed = signed().replace("<TgTTTBSo>2862000</TgTTTBSo>", "<TgTTTBSo>2962000</TgTTTBSo>");
        assertThat(changed).isNotEqualTo(signed());

        Check check = check(changed);

        assertThat(check.status()).isEqualTo(Status.CONTENT_CHANGED);
        assertThat(check.valid()).isFalse();
        assertThat(check.signer()).as("still says whose signature it was").isEqualTo("MẮT BÃO - KIỂM THỬ HĐĐT CÓ MÃ");
    }

    @Test
    void aBuyerChangedAfterSigningIsCaught() throws IOException {
        Check check = check(signed().replace("<MST>0101243150</MST>", "<MST>0101243151</MST>"));

        assertThat(check.status()).isEqualTo(Status.CONTENT_CHANGED);
    }

    @Test
    void aSignatureValueChangedIsNotTheCertificates() throws IOException {
        String xml = signed();
        int start = xml.indexOf("<SignatureValue>") + "<SignatureValue>".length();
        char first = xml.charAt(start);
        String changed = xml.substring(0, start) + (first == 'A' ? 'B' : 'A') + xml.substring(start + 1);

        assertThat(check(changed).status()).isEqualTo(Status.SIGNATURE_INVALID);
    }

    @Test
    void anInvoiceWithoutTheSellersSignatureIsNotSigned() {
        assertThat(check("<HDon><DLHDon Id=\"data\"><TTChung><SHDon>1</SHDon></TTChung></DLHDon><MCCQT>SBX-1</MCCQT>"
                + "<DSCKS><NBan/><NMua/></DSCKS></HDon>").status()).isEqualTo(Status.NOT_SIGNED);
    }

    @Test
    void whatIsNotAnXmlIsUnreadable() {
        assertThat(check("%PDF-1.4 not an invoice").status()).isEqualTo(Status.UNREADABLE);
        assertThat(InvoiceXmlSignature.check(new byte[0]).status()).isEqualTo(Status.UNREADABLE);
        assertThat(InvoiceXmlSignature.check(new byte[InvoiceXmlSignature.MAX_BYTES + 1]).status())
                .isEqualTo(Status.UNREADABLE);
    }

    @Test
    void anXmlNamingAnExternalEntityIsRefusedWithoutReadingIt(@TempDir Path dir) throws IOException {
        Path secret = Files.writeString(dir.resolve("secret.txt"), "TOP-SECRET");
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE HDon [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]>"
                + "<HDon><DLHDon Id=\"d\"><TTChung><SHDon>&x;</SHDon></TTChung></DLHDon></HDon>";

        Check check = check(xxe);

        assertThat(check.status()).isEqualTo(Status.UNREADABLE);
        assertThat(check.toString()).doesNotContain("TOP-SECRET");
    }
}
