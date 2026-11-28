package com.stockflow.order.internal.service.signing;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.GregorianCalendar;

/**
 * Signs a PDF as the company: a PAdES baseline B-B signature ({@code ETSI.CAdES.detached}, SHA-256, the ESS
 * signing-certificate-v2 attribute), added as an <b>incremental update</b>, so the bytes of the PDF that were sent stay
 * exactly as they were and a signature a customer or a reader checks covers a file that begins with the sent one
 * ({@link SignedPdfInspector} checks that).
 *
 * <p>The PDF it signs for the company is the sent PDF with the certificate page added, itself an incremental update
 * ({@link CertificatePage#append}): so the signed file begins with exactly the bytes the customer was sent - the sent
 * PDF, then the update that adds the page, then this one - and {@code SignedPdfInspector} finds the sent contract in it,
 * unchanged, with one page after it.</p>
 *
 * <p>The signature is invisible. What a reader of the printed contract sees is the certificate page added before
 * signing; a visible stamp on a page of the contract would be one more thing a template change could clip - and an
 * annotation over the sent pages, which the inspector refuses. The document itself goes nowhere: {@link ContractSigner}
 * gets the SHA-256 of the signed ranges only.</p>
 */
@Component
public class PadesSigner {

    /**
     * Room for the signature in the file, in bytes: a CMS with a 3072-bit key and a two-certificate chain is about 4 KB;
     * a deployment's own chain of three certificates fits too. A signature that does not fit is refused, not cut.
     */
    private static final int SIGNATURE_BYTES = 16 * 1024;

    /**
     * @param pdf      the PDF to sign, not signed already by anyone else (a second signature is added after theirs
     *                 and leaves theirs valid, which PDFBox's incremental save guarantees, but nothing here promises
     *                 more)
     * @param signerName shown by PDF readers as the signer ({@code /Name}); the company's authorised signer
     * @param reason   {@code /Reason}: why the signature was made, the text of the bundle for signing a contract
     * @param location {@code /Location}: where, the country name
     * @param time     {@code /M}, when the signature was made
     * @return the signed PDF: {@code pdf} followed by the update
     * @throws IllegalArgumentException when {@code pdf} cannot be read or is encrypted
     * @throws IllegalStateException    when the signature could not be made or does not fit its reserved room
     */
    public byte[] sign(byte[] pdf, ContractSigner signer, String signerName, String reason, String location,
                       Instant time) {
        try (PDDocument document = Loader.loadPDF(pdf); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (document.isEncrypted()) {
                throw new IllegalArgumentException("An encrypted PDF cannot be signed");
            }
            PDSignature signature = new PDSignature();
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED);
            signature.setName(signerName);
            signature.setReason(reason);
            signature.setLocation(location);
            signature.setSignDate(GregorianCalendar.from(time.atZone(ZoneOffset.UTC)));
            SignatureOptions options = new SignatureOptions();
            options.setPreferredSignatureSize(SIGNATURE_BYTES);
            document.addSignature(signature, content -> signer.signDigest(sha256(content.readAllBytes())), options);
            document.saveIncremental(out);
            return out.toByteArray();
        } catch (IOException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("Can't write signature")) {
                throw new IllegalStateException("The signature does not fit the room reserved for it", ex);
            }
            throw new IllegalArgumentException("The PDF could not be read or signed", ex);
        }
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
