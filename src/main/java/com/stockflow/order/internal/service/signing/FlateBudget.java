package com.stockflow.order.internal.service.signing;

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * How much the Flate streams of an uploaded PDF decompress to, counted before PDFBox opens it: PDFBox decodes an object
 * stream (and a cross-reference stream) whole into memory to parse it, so a few hundred kilobytes of zeros compressed
 * into one would take the heap down. Every {@code stream} keyword of the raw bytes is looked at, whatever the dictionary
 * before it says - its {@code /Type} and {@code /Filter} may be written so as not to read as such - and what follows is
 * inflated into a fixed buffer that is thrown away, so the count costs CPU in proportion to the bound and no memory.
 * Bytes that are not a zlib stream (an image in DCT, an uncompressed stream) fail to inflate at once and count nothing.
 *
 * <p>Not covered: a stream whose Flate layer is followed by another expanding filter (LZW, RunLength) is counted only
 * for its Flate layer. PDFBox drops a filter named twice, so Flate cannot be stacked on itself; the remaining factor
 * is bounded by those legacy filters' own ratios, on data already within the bound.</p>
 */
final class FlateBudget {

    private static final byte[] STREAM = {'s', 't', 'r', 'e', 'a', 'm'};
    private static final int SINK = 64 * 1024;

    private FlateBudget() {
    }

    /**
     * @param perStream the most one stream may decode to
     * @param total     the most all of them together may
     * @return whether the file stays within both
     */
    static boolean within(byte[] pdf, long perStream, long total) {
        byte[] sink = new byte[SINK];
        long sum = 0;
        int at = indexOf(pdf, 0);
        while (at >= 0) {
            int after = at + STREAM.length;
            if (!endOfEndstream(pdf, at)) {
                long decoded = inflated(pdf, dataStart(pdf, after), sink, perStream);
                if (decoded > perStream) {
                    return false;
                }
                sum += decoded;
                if (sum > total) {
                    return false;
                }
            }
            at = indexOf(pdf, after);
        }
        return true;
    }

    /** Where a stream's data begins: after the keyword, spaces, then an optional CR and LF - as PDFBox reads it. */
    private static int dataStart(byte[] pdf, int from) {
        int at = from;
        while (at < pdf.length && pdf[at] == ' ') {
            at++;
        }
        if (at < pdf.length && pdf[at] == '\r') {
            at++;
        }
        if (at < pdf.length && pdf[at] == '\n') {
            at++;
        }
        return at;
    }

    /**
     * What the bytes from {@code from} inflate to, counted up to one byte past {@code cap}; what decodes before a
     * corrupt byte counts too, since a lenient reader keeps it.
     */
    private static long inflated(byte[] pdf, int from, byte[] sink, long cap) {
        if (from >= pdf.length) {
            return 0;
        }
        Inflater inflater = new Inflater();
        long count = 0;
        try {
            inflater.setInput(pdf, from, pdf.length - from);
            while (!inflater.finished() && count <= cap) {
                int n = inflater.inflate(sink);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                if (n == 0) {
                    // Neither data nor a reason to wait for more: nothing further will come out.
                    break;
                }
                count += n;
            }
            return count;
        } catch (DataFormatException notFlate) {
            return count;
        } finally {
            inflater.end();
        }
    }

    /** The {@code stream} at {@code at} is the tail of {@code endstream}. */
    private static boolean endOfEndstream(byte[] pdf, int at) {
        return at >= 3 && pdf[at - 3] == 'e' && pdf[at - 2] == 'n' && pdf[at - 1] == 'd';
    }

    private static int indexOf(byte[] pdf, int from) {
        outer:
        for (int i = from; i <= pdf.length - STREAM.length; i++) {
            for (int j = 0; j < STREAM.length; j++) {
                if (pdf[i + j] != STREAM[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
