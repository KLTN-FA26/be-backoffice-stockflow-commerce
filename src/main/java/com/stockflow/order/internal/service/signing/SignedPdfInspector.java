package com.stockflow.order.internal.service.signing;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccessStreamCache;
import org.apache.pdfbox.io.ScratchFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignerDigestMismatchException;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Looks at a PDF somebody uploaded as a signed contract and says what is true of it: a scan (no digital signature), or a
 * digitally signed file whose signatures hold, who signed, and whether it is the contract that was sent.
 *
 * <p>What "valid" means: for every signature in the file the ByteRange is well formed, the CMS signature verifies over
 * exactly the bytes it names (the digest and the signer's signature), the signer's certificate is in the CMS, and - when
 * the signing time is known (the CMS's, else the signature dictionary's {@code M}) - the certificate was within its
 * validity then. It does <b>not</b> say a certification authority we trust issued the certificate or that it was not
 * revoked: that needs the CAs' roots and revocation lists, which the application does not hold, so
 * {@link Inspection#chainChecked()} is always false (as for the e-invoice's XML signature). What the user sees is the
 * signer's name and issuer, and decides.</p>
 *
 * <p>{@code coversWholeDocument}: the last signature's range ends at the end of the file, so nothing was appended after
 * it that nobody signed. {@code matchesSentDocument}: the file begins with exactly the PDF that was sent (its first
 * {@code expectedLength} bytes hash to the expected SHA-256), every signature was made after it, in an incremental
 * update, <b>and what each signature covers shows the same thing as the sent PDF</b> ({@link PdfRevisions}). The byte
 * prefix alone is not enough: an incremental update made before the signature may replace a page's content or its
 * resources, or lay an annotation over it (the "shadow attack" on PDF signatures), and the signature then covers - and
 * holds over - a contract that reads differently from the one sent. Each signed revision is opened and compared with the
 * sent PDF page by page; anything changed is {@code CONTENT_CHANGED_AFTER_SENT}. The one addition always allowed is the
 * signature itself (its widget annotation), and, when the caller allows it ({@link Additions#CERTIFICATE_PAGE}), one
 * page after the last: the certificate page the company adds before it signs.</p>
 *
 * <p>The bytes come from a person, so: at most {@value #MAX_BYTES} bytes; an encrypted or malformed file is described,
 * never thrown at the caller; the PDF is only parsed (no external resource is fetched, no script runs); the CMS parsing
 * is guarded as one - any runtime failure there is "not valid", not a 500. And memory is bounded, because a 20 MB upload
 * can claim gigabytes once decompressed (a "Flate bomb" in an object stream, which PDFBox decodes into memory to parse
 * it): every Flate stream of the file is first inflated into nothing, counting, and a file that would decode past
 * {@value #MAX_DECODED_STREAM_BYTES} bytes in one stream or {@value #MAX_DECODED_BYTES} in all is {@code TOO_LARGE}
 * before PDFBox sees it ({@link FlateBudget}); what PDFBox buffers goes to a bounded temporary file, not the heap; the
 * content comparison hashes streams as they are stored, never decoded; and at most {@value #CONCURRENT} inspections run
 * at once - another waits up to {@value #WAIT_SECONDS} s, then is refused with 503 {@code CONTRACT_INSPECTION_BUSY}, so a
 * burst of uploads queues rather than piles up in the heap. {@link OutOfMemoryError} is deliberately not caught: once it
 * is thrown other threads may have failed with it too, and the bounds above are what keep it from being thrown.</p>
 */
@Component
public class SignedPdfInspector {

    /** The largest upload looked at. */
    public static final int MAX_BYTES = 20 * 1024 * 1024;
    /**
     * The most one stream of the file may decompress to. A contract page scanned at 300 dpi in colour and Flate-coded is
     * about 26 MB; an object stream of a real contract is kilobytes.
     */
    static final long MAX_DECODED_STREAM_BYTES = 48L * 1024 * 1024;
    /** The most all streams of the file together may decompress to. */
    static final long MAX_DECODED_BYTES = 512L * 1024 * 1024;
    /** What PDFBox may buffer on disk for one document it opens. */
    private static final long MAX_SCRATCH_BYTES = 256L * 1024 * 1024;
    /** Inspections at once; each may hold the file a few times over plus what PDFBox parses. */
    static final int CONCURRENT = 2;
    /** How long an inspection waits for another to finish before it is refused. */
    static final int WAIT_SECONDS = 10;

    /** What the file is. */
    public enum Kind {
        /** Carries at least one digital signature. */
        DIGITAL,
        /** A PDF with no digital signature: the scan of a paper signed by hand, or any other PDF. */
        SCAN,
        /** Not a PDF this can read: empty, too large, malformed or encrypted ({@link Inspection#reason()} says which). */
        UNREADABLE
    }

    /** What a signed revision may add to the sent document besides the signatures themselves. */
    public enum Additions {
        /** Nothing: every page the same, no page added. A customer's file, staff's, the company's signed outside. */
        NONE,
        /**
         * One page after the last, with no annotation but signature widgets: the certificate page the company adds to
         * the sent PDF before signing it. Never for a customer's file - an extra page there would be terms nobody sent.
         */
        CERTIFICATE_PAGE
    }

    /**
     * One signature's signer.
     *
     * @param serial                    the certificate's serial, hex
     * @param signedAt                  the signing time the signature claims; null when it claims none
     * @param certificateValidAtSigning whether {@code signedAt} is inside the certificate's validity; null without it
     */
    public record SignerFacts(String subject, String issuer, String serial, Instant notBefore, Instant notAfter,
                              Instant signedAt, Boolean certificateValidAtSigning) {
    }

    /**
     * @param valid               every signature holds (see the class comment); false for a scan and an unreadable file
     * @param coversWholeDocument see the class comment; false for a scan
     * @param matchesSentDocument see the class comment; false when no expected document was given
     * @param chainChecked        always false
     * @param reason              the first thing wrong, one of the {@code Reasons} codes; null when nothing is
     */
    public record Inspection(Kind kind, boolean valid, boolean coversWholeDocument, boolean matchesSentDocument,
                             boolean chainChecked, List<SignerFacts> signers, String reason) {

        public Inspection {
            signers = List.copyOf(signers);
        }

        /** Digitally signed, every signature holds, nothing unsigned was appended, and it is the sent contract. */
        public boolean acceptedAsSentContract() {
            return kind == Kind.DIGITAL && valid && coversWholeDocument && matchesSentDocument;
        }

        static Inspection unreadable(String reason) {
            return new Inspection(Kind.UNREADABLE, false, false, false, false, List.of(), reason);
        }
    }

    /** The codes of {@link Inspection#reason()}. */
    public static final class Reasons {
        public static final String EMPTY = "EMPTY";
        /** Larger than {@link #MAX_BYTES}, or it would decompress past the bounds (see the class comment). */
        public static final String TOO_LARGE = "TOO_LARGE";
        public static final String NOT_A_PDF = "NOT_A_PDF";
        public static final String ENCRYPTED = "ENCRYPTED";
        public static final String MALFORMED = "MALFORMED";
        public static final String NO_DIGITAL_SIGNATURE = "NO_DIGITAL_SIGNATURE";
        public static final String BYTE_RANGE_INVALID = "BYTE_RANGE_INVALID";
        public static final String SIGNATURE_UNREADABLE = "SIGNATURE_UNREADABLE";
        public static final String CERTIFICATE_MISSING = "CERTIFICATE_MISSING";
        public static final String CONTENT_CHANGED = "CONTENT_CHANGED";
        public static final String SIGNATURE_INVALID = "SIGNATURE_INVALID";
        public static final String CERTIFICATE_NOT_VALID_AT_SIGNING = "CERTIFICATE_NOT_VALID_AT_SIGNING";
        public static final String NOT_WHOLE_DOCUMENT = "NOT_WHOLE_DOCUMENT";
        public static final String NOT_SENT_DOCUMENT = "NOT_SENT_DOCUMENT";
        /**
         * The file begins with the sent PDF and its signatures hold, but what a signature covers does not show what was
         * sent: a page's content or resources replaced, an annotation added, or a page added or taken away, in an update
         * made before the signature.
         */
        public static final String CONTENT_CHANGED_AFTER_SENT = "CONTENT_CHANGED_AFTER_SENT";

        private Reasons() {
        }
    }

    private final Semaphore permits;
    private final long waitMillis;

    public SignedPdfInspector() {
        this(CONCURRENT, TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
    }

    /** For tests: another bound on the inspections at once, and on the wait. */
    SignedPdfInspector(int concurrent, long waitMillis) {
        this.permits = new Semaphore(concurrent, true);
        this.waitMillis = waitMillis;
    }

    /** Inspects {@code pdf} on its own; {@code matchesSentDocument} is false. */
    public Inspection inspect(byte[] pdf) {
        return inspect(pdf, null, 0, Additions.NONE);
    }

    /**
     * Inspects the company's signed contract against the PDF that was sent: the sent pages unchanged, and the
     * certificate page the company adds after them ({@link Additions#CERTIFICATE_PAGE}). A file anybody else signed is
     * inspected with {@link Additions#NONE}.
     *
     * @param expectedSha256 SHA-256 of the document that was sent, or null to skip the comparison
     * @param expectedLength its length in bytes
     */
    public Inspection inspect(byte[] pdf, byte[] expectedSha256, long expectedLength) {
        return inspect(pdf, expectedSha256, expectedLength, Additions.CERTIFICATE_PAGE);
    }

    /**
     * @param expectedSha256 SHA-256 of the document that was sent, or null to skip the comparison
     * @param expectedLength its length in bytes
     * @param allowed        what a signed revision may add to it besides the signatures
     * @throws BusinessException 503 {@code CONTRACT_INSPECTION_BUSY} when {@value #CONCURRENT} inspections are running
     *                           and none ended within {@value #WAIT_SECONDS} s
     */
    public Inspection inspect(byte[] pdf, byte[] expectedSha256, long expectedLength, Additions allowed) {
        if (pdf == null || pdf.length == 0) {
            return Inspection.unreadable(Reasons.EMPTY);
        }
        if (pdf.length > MAX_BYTES) {
            return Inspection.unreadable(Reasons.TOO_LARGE);
        }
        if (!startsLikePdf(pdf)) {
            return Inspection.unreadable(Reasons.NOT_A_PDF);
        }
        acquire();
        try {
            if (!FlateBudget.within(pdf, MAX_DECODED_STREAM_BYTES, MAX_DECODED_BYTES)) {
                return Inspection.unreadable(Reasons.TOO_LARGE);
            }
            try (PDDocument document = load(pdf)) {
                if (document.isEncrypted()) {
                    return Inspection.unreadable(Reasons.ENCRYPTED);
                }
                List<PDSignature> signatures = new ArrayList<>(document.getSignatureDictionaries());
                if (signatures.isEmpty()) {
                    return new Inspection(Kind.SCAN, false, false, false, false, List.of(),
                            Reasons.NO_DIGITAL_SIGNATURE);
                }
                return inspectSignatures(pdf, signatures, expectedSha256, expectedLength,
                        allowed == null ? Additions.NONE : allowed);
            } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException encrypted) {
                return Inspection.unreadable(Reasons.ENCRYPTED);
            } catch (IOException | RuntimeException | StackOverflowError malformed) {
                return Inspection.unreadable(Reasons.MALFORMED);
            }
        } finally {
            permits.release();
        }
    }

    /**
     * Opens a PDF of an inspection's: what PDFBox buffers goes to a bounded file on disk, never the heap (the bytes
     * themselves are in memory already, bounded by {@link #MAX_BYTES}).
     */
    static PDDocument load(byte[] pdf) throws IOException {
        MemoryUsageSetting disk = MemoryUsageSetting.setupTempFileOnly(MAX_SCRATCH_BYTES);
        RandomAccessStreamCache.StreamCacheCreateFunction cache = () -> new ScratchFile(disk);
        return Loader.loadPDF(pdf, null, null, null, cache);
    }

    private void acquire() {
        try {
            if (!permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)) {
                throw new BusinessException(ErrorCode.CONTRACT_INSPECTION_BUSY);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.CONTRACT_INSPECTION_BUSY);
        }
    }

    private Inspection inspectSignatures(byte[] pdf, List<PDSignature> signatures, byte[] expectedSha256,
                                         long expectedLength, Additions allowed) {
        String reason = null;
        List<SignerFacts> signers = new ArrayList<>();
        List<int[]> ranges = new ArrayList<>();
        for (PDSignature signature : signatures) {
            int[] range = signature.getByteRange();
            if (!wellFormed(range, pdf.length)) {
                reason = first(reason, Reasons.BYTE_RANGE_INVALID);
                continue;
            }
            ranges.add(range);
            reason = first(reason, check(pdf, signature, range, signers));
        }
        boolean valid = reason == null && ranges.size() == signatures.size();
        boolean covers = !ranges.isEmpty() && ranges.stream().mapToLong(r -> (long) r[2] + r[3]).max().orElse(-1)
                == pdf.length;
        reason = first(reason, covers ? null : Reasons.NOT_WHOLE_DOCUMENT);
        boolean matches = false;
        if (expectedSha256 != null) {
            matches = startsWith(pdf, expectedSha256, expectedLength) && !ranges.isEmpty()
                    && ranges.size() == signatures.size() && ranges.stream().allMatch(r -> r[1] >= expectedLength);
            reason = first(reason, matches ? null : Reasons.NOT_SENT_DOCUMENT);
            if (matches) {
                // Only now, the prefix being the sent PDF: every signed revision opened and read against it.
                String changed = PdfRevisions.sameAsSent(pdf, (int) expectedLength, ranges, allowed);
                matches = changed == null;
                reason = first(reason, changed);
            }
        }
        return new Inspection(Kind.DIGITAL, valid, covers, matches, false, signers, reason);
    }

    /**
     * Verifies one signature; adds the signer to {@code signers} when its certificate can be read.
     *
     * @return the reason it does not hold, or null
     */
    private String check(byte[] pdf, PDSignature signature, int[] range, List<SignerFacts> signers) {
        try {
            byte[] contents = signature.getContents(pdf);
            if (contents == null || contents.length == 0) {
                return Reasons.SIGNATURE_UNREADABLE;
            }
            ByteArrayOutputStream signed = new ByteArrayOutputStream(range[1] + range[3]);
            signed.write(pdf, range[0], range[1]);
            signed.write(pdf, range[2], range[3]);
            CMSSignedData cms = new CMSSignedData(new CMSProcessableByteArray(signed.toByteArray()), contents);
            List<SignerInformation> infos = new ArrayList<>(cms.getSignerInfos().getSigners());
            if (infos.isEmpty()) {
                return Reasons.SIGNATURE_UNREADABLE;
            }
            String reason = null;
            for (SignerInformation info : infos) {
                reason = first(reason, checkSigner(cms, info, signature, signers));
            }
            return reason;
        } catch (java.io.IOException | org.bouncycastle.cms.CMSException | RuntimeException | StackOverflowError ex) {
            return Reasons.SIGNATURE_UNREADABLE;
        }
    }

    private String checkSigner(CMSSignedData cms, SignerInformation info, PDSignature signature,
                               List<SignerFacts> signers) {
        @SuppressWarnings("unchecked")
        var matches = (java.util.Collection<X509CertificateHolder>) cms.getCertificates().getMatches(info.getSID());
        if (matches.isEmpty()) {
            return Reasons.CERTIFICATE_MISSING;
        }
        X509CertificateHolder holder = matches.iterator().next();
        Instant signedAt = CertificateDates.signingTime(info);
        if (signedAt == null) {
            Calendar claimed = signature.getSignDate();
            signedAt = claimed == null ? null : claimed.toInstant();
        }
        Instant from = CertificateDates.notBefore(holder);
        Instant to = CertificateDates.notAfter(holder);
        Boolean validThen = signedAt == null ? null : !signedAt.isBefore(from) && !signedAt.isAfter(to);
        signers.add(new SignerFacts(holder.getSubject().toString(), holder.getIssuer().toString(),
                serial(holder.getSerialNumber()), from, to, signedAt, validThen));
        try {
            if (!info.verify(new JcaSimpleSignerInfoVerifierBuilder().setProvider(new BouncyCastleProvider())
                    .build(holder))) {
                return Reasons.SIGNATURE_INVALID;
            }
        } catch (CMSSignerDigestMismatchException changed) {
            return Reasons.CONTENT_CHANGED;
        } catch (org.bouncycastle.cms.CMSException | org.bouncycastle.operator.OperatorCreationException
                 | java.security.cert.CertificateException | RuntimeException failed) {
            return Reasons.SIGNATURE_INVALID;
        }
        return Boolean.FALSE.equals(validThen) ? Reasons.CERTIFICATE_NOT_VALID_AT_SIGNING : null;
    }

    /** {@code [0, a, b, c]} with the two ranges inside the file, in order, and a gap that can hold the contents. */
    private static boolean wellFormed(int[] range, int length) {
        if (range == null || range.length != 4) {
            return false;
        }
        for (int value : range) {
            if (value < 0) {
                return false;
            }
        }
        return range[0] == 0 && range[1] > 0 && range[2] > range[1] && range[3] > 0
                && (long) range[2] + range[3] <= length;
    }

    private static boolean startsWith(byte[] pdf, byte[] expectedSha256, long expectedLength) {
        if (expectedLength <= 0 || expectedLength > pdf.length) {
            return false;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(pdf, 0, (int) expectedLength);
            return MessageDigest.isEqual(digest.digest(), expectedSha256);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** {@code %PDF} within the first kilobyte, where readers look for it. */
    private static boolean startsLikePdf(byte[] bytes) {
        int limit = Math.min(bytes.length - 4, 1024);
        for (int i = 0; i <= limit; i++) {
            if (bytes[i] == '%' && bytes[i + 1] == 'P' && bytes[i + 2] == 'D' && bytes[i + 3] == 'F') {
                return true;
            }
        }
        return false;
    }

    private static String serial(BigInteger serial) {
        return serial.toString(16);
    }

    private static String first(String reason, String next) {
        return reason != null ? reason : next;
    }
}
