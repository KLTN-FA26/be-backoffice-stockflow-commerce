package com.stockflow.order.internal.service.signing;

import com.stockflow.order.internal.service.signing.SignedPdfInspector.Additions;
import com.stockflow.order.internal.service.signing.SignedPdfInspector.Reasons;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether each revision a signature covers shows what the sent PDF showed (see {@link SignedPdfInspector}, "shadow
 * attack"). The sent PDF and each signed revision - the file up to the end of the signature's ByteRange - are opened, and
 * compared page by page on everything that draws a page: its content streams, its resources (fonts, images, forms, the
 * graphics states), its boxes, rotation, user unit and transparency group, and its annotations other than signature
 * widgets; and, for the whole document, the optional-content settings that show or hide marked content.
 *
 * <p>Compared by fingerprint, not by object: an incremental update may rewrite an object with the very same value, and
 * that is no change. A fingerprint is a SHA-256 over the objects - dictionary keys in order, arrays in order, references
 * followed - with every stream's dictionary and its bytes <b>as stored</b>, never decoded: equal stored bytes under
 * equal filters decode equally, and nothing an upload holds is ever decompressed here (see {@link FlateBudget}). Back references that only point up the tree ({@code /P},
 * {@code /Parent}) and the stream lengths, which the bytes themselves carry, are left out. A walk is bounded in nodes and
 * depth; a document past the bound is not judged the same.</p>
 */
final class PdfRevisions {

    /** What a page draws with besides its content streams and annotations. */
    private static final List<COSName> DRAWN = List.of(COSName.CONTENTS, COSName.RESOURCES, COSName.MEDIA_BOX,
            COSName.CROP_BOX, COSName.ROTATE, COSName.USER_UNIT, COSName.GROUP);
    private static final Set<COSName> SKIPPED = Set.of(COSName.P, COSName.PARENT, COSName.LENGTH);
    private static final int MAX_NODES = 500_000;
    private static final int MAX_DEPTH = 200;

    private PdfRevisions() {
    }

    /**
     * @param pdf            the whole upload, beginning with the sent PDF (already checked)
     * @param expectedLength the sent PDF's length
     * @param ranges         every signature's ByteRange, well formed
     * @return null when every signed revision shows the sent PDF (with what {@code allowed} lets it add), else the
     *         reason: {@code CONTENT_CHANGED_AFTER_SENT}, or {@code MALFORMED} when a revision cannot be read
     */
    static String sameAsSent(byte[] pdf, int expectedLength, List<int[]> ranges, Additions allowed) {
        try (PDDocument sent = SignedPdfInspector.load(Arrays.copyOf(pdf, expectedLength))) {
            Snapshot want = Snapshot.of(sent);
            for (int[] range : ranges) {
                int end = range[2] + range[3];
                try (PDDocument revision = SignedPdfInspector.load(Arrays.copyOf(pdf, end))) {
                    if (!want.shownBy(Snapshot.of(revision), allowed)) {
                        return Reasons.CONTENT_CHANGED_AFTER_SENT;
                    }
                }
            }
            return null;
        } catch (IOException | RuntimeException | StackOverflowError unreadable) {
            return Reasons.MALFORMED;
        }
    }

    /**
     * What a document shows: per page, the fingerprint of what draws it and of each annotation that is not a signature
     * widget; the document's optional-content settings.
     */
    private record Snapshot(List<String> pages, List<List<String>> annotations, String optionalContent) {

        static Snapshot of(PDDocument document) throws IOException {
            Walk walk = new Walk();
            List<String> pages = new ArrayList<>();
            List<List<String>> annotations = new ArrayList<>();
            for (PDPage page : document.getPages()) {
                pages.add(walk.page(page));
                List<String> drawn = new ArrayList<>();
                COSArray annots = page.getCOSObject().getCOSArray(COSName.ANNOTS);
                if (annots != null) {
                    for (int i = 0; i < annots.size(); i++) {
                        COSBase annotation = dereferenced(annots.get(i));
                        if (!(annotation instanceof COSDictionary dictionary) || !signatureWidget(dictionary)) {
                            drawn.add(walk.of(annotation));
                        }
                    }
                }
                annotations.add(drawn);
            }
            COSBase properties = document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.OCPROPERTIES);
            return new Snapshot(pages, annotations, walk.of(properties));
        }

        /** Whether {@code revision} shows this document: the same pages, plus at most what {@code allowed} lets it add. */
        boolean shownBy(Snapshot revision, Additions allowed) {
            int n = pages.size();
            int extra = revision.pages.size() - n;
            if (extra < 0 || extra > (allowed == Additions.CERTIFICATE_PAGE ? 1 : 0)) {
                return false;
            }
            if (!optionalContent.equals(revision.optionalContent)) {
                return false;
            }
            for (int i = 0; i < n; i++) {
                if (!pages.get(i).equals(revision.pages.get(i))
                        || !annotations.get(i).equals(revision.annotations.get(i))) {
                    return false;
                }
            }
            // The certificate page carries nothing over it but signatures.
            return extra == 0 || revision.annotations.get(n).isEmpty();
        }
    }

    /** A widget of a signature field: {@code /Subtype /Widget} whose field ({@code /FT}, inherited) is {@code /Sig}. */
    private static boolean signatureWidget(COSDictionary annotation) {
        if (!COSName.WIDGET.equals(annotation.getCOSName(COSName.SUBTYPE))) {
            return false;
        }
        COSDictionary field = annotation;
        for (int depth = 0; field != null && depth < 32; depth++) {
            COSName type = field.getCOSName(COSName.FT);
            if (type != null) {
                return COSName.SIG.equals(type);
            }
            field = dereferenced(field.getItem(COSName.PARENT)) instanceof COSDictionary parent ? parent : null;
        }
        return false;
    }

    private static COSBase dereferenced(COSBase base) {
        return base instanceof COSObject reference ? reference.getObject() : base;
    }

    /**
     * One document's fingerprints, Merkle-style: a dictionary's or an array's fingerprint is the SHA-256 of its own
     * entries' fingerprints, computed once per object however many pages share it (the fonts of a long contract are
     * reached from every page). A cycle - rare once the back references are left out - is written as a marker.
     */
    private static final class Walk {

        private static final byte[] CYCLE = {'c'};

        private final Map<COSBase, byte[]> known = new IdentityHashMap<>();
        private final Set<COSBase> open = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private int nodes;

        /** What draws the page, inherited attributes included (as PDPage reads them). */
        String page(PDPage page) throws IOException {
            MessageDigest digest = sha256();
            COSDictionary dictionary = page.getCOSObject();
            for (COSName key : DRAWN) {
                digest.update(fingerprint(key, 0));
                digest.update(fingerprint(dictionary.getItem(key), 0));
            }
            digest.update(fingerprint(page.getResources() == null ? null : page.getResources().getCOSObject(), 0));
            digest.update(fingerprint(page.getMediaBox().getCOSArray(), 0));
            digest.update(fingerprint(page.getCropBox().getCOSArray(), 0));
            digest.update(ByteBuffer.allocate(8).putInt(page.getRotation()).putFloat(page.getUserUnit()).array());
            return java.util.HexFormat.of().formatHex(digest.digest());
        }

        String of(COSBase base) throws IOException {
            return java.util.HexFormat.of().formatHex(sha256().digest(fingerprint(base, 0)));
        }

        private byte[] fingerprint(COSBase given, int depth) throws IOException {
            COSBase base = dereferenced(given);
            if (base == null || base instanceof COSNull) {
                return new byte[] {'n'};
            }
            if (base instanceof COSName name) {
                return encoded('N', name.getName().getBytes(StandardCharsets.UTF_8));
            }
            if (base instanceof COSString string) {
                return encoded('S', string.getBytes());
            }
            if (base instanceof COSInteger integer) {
                return ByteBuffer.allocate(9).put((byte) 'I').putLong(integer.longValue()).array();
            }
            if (base instanceof COSFloat real) {
                return ByteBuffer.allocate(5).put((byte) 'F').putFloat(real.floatValue()).array();
            }
            if (base instanceof COSBoolean bool) {
                return new byte[] {(byte) (bool.getValue() ? 'T' : 'f')};
            }
            if (!(base instanceof COSDictionary) && !(base instanceof COSArray)) {
                return encoded('?', base.getClass().getName().getBytes(StandardCharsets.UTF_8));
            }
            byte[] done = known.get(base);
            if (done != null) {
                return done;
            }
            if (!open.add(base)) {
                return CYCLE;
            }
            if (++nodes > MAX_NODES || depth > MAX_DEPTH) {
                throw new IOException("The document is too complex to compare");
            }
            MessageDigest digest = sha256();
            if (base instanceof COSDictionary dictionary) {
                digest.update((byte) (dictionary instanceof COSStream ? 's' : 'd'));
                List<COSName> keys = dictionary.keySet().stream().filter(key -> !SKIPPED.contains(key))
                        .sorted(Comparator.comparing(COSName::getName)).toList();
                digest.update(ByteBuffer.allocate(4).putInt(keys.size()).array());
                for (COSName key : keys) {
                    digest.update(fingerprint(key, depth + 1));
                    digest.update(fingerprint(dictionary.getItem(key), depth + 1));
                }
                if (dictionary instanceof COSStream stream) {
                    digest.update(stored(stream));
                }
            } else {
                COSArray array = (COSArray) base;
                digest.update((byte) 'a');
                digest.update(ByteBuffer.allocate(4).putInt(array.size()).array());
                for (int i = 0; i < array.size(); i++) {
                    digest.update(fingerprint(array.get(i), depth + 1));
                }
            }
            byte[] result = digest.digest();
            open.remove(base);
            known.put(base, result);
            return result;
        }

        /** The SHA-256 of a stream's bytes as stored: read, never decoded. */
        private static byte[] stored(COSStream stream) throws IOException {
            MessageDigest digest = sha256();
            byte[] buffer = new byte[16 * 1024];
            try (InputStream raw = stream.createRawInputStream()) {
                for (int n = raw.read(buffer); n >= 0; n = raw.read(buffer)) {
                    digest.update(buffer, 0, n);
                }
            }
            return digest.digest();
        }

        /** A tag, the length and the bytes: no two values encode alike. */
        private static byte[] encoded(char tag, byte[] bytes) {
            return ByteBuffer.allocate(5 + bytes.length).put((byte) tag).putInt(bytes.length).put(bytes).array();
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
