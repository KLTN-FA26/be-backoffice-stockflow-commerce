package com.stockflow.common.storage;

import org.springframework.http.ContentDisposition;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * How the browser should treat a signed link's response, sent as the {@code response-content-*}
 * overrides S3 honours on a presigned GET.
 *
 * <ul>
 *   <li>{@code attachment} with the file's own name: saving an invoice scan gives
 *       {@code HD-0012.pdf}, not the storage key's {@code 0192f4c1-....pdf}. The name is
 *       RFC 6266/5987 encoded, so a Vietnamese file name survives.</li>
 *   <li>{@code inline} with a private cache lifetime equal to the link's: a thumbnail rendered twice
 *       within the link's life is fetched once.</li>
 * </ul>
 *
 * @param contentDisposition the {@code Content-Disposition} the store returns
 * @param cacheControl       the {@code Cache-Control} the store returns, or null to keep the object's
 */
public record DownloadDisposition(String contentDisposition, String cacheControl) {

    /** Save under {@code fileName}; a blank name leaves the choice to the browser. */
    public static DownloadDisposition attachment(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return new DownloadDisposition("attachment", "private, no-store");
        }
        return new DownloadDisposition(ContentDisposition.attachment()
                .filename(printable(fileName), StandardCharsets.UTF_8).build().toString(), "private, no-store");
    }

    /** Show in the page, cacheable by this browser for {@code validFor}. */
    public static DownloadDisposition inline(Duration validFor) {
        return new DownloadDisposition("inline", "private, max-age=" + Math.max(0, validFor.toSeconds()));
    }

    /** A user-supplied name without the characters that have no business in a header. */
    private static String printable(String fileName) {
        StringBuilder clean = new StringBuilder(fileName.length());
        fileName.codePoints().forEach(c -> clean.appendCodePoint(Character.isISOControl(c) ? '_' : c));
        String result = clean.toString().strip();
        // By code point, so a cut never splits a surrogate pair.
        return result.codePointCount(0, result.length()) > 200
                ? result.substring(0, result.offsetByCodePoints(0, 200)) : result;
    }
}
