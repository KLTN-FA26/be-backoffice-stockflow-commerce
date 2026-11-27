package com.stockflow.common.document;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

/**
 * A business document as the answer of a download endpoint: {@code application/pdf}, shown in the browser, saved
 * under its Vietnamese name ("Phiếu xác nhận thanh toán XNTT-....pdf").
 *
 * <p>The name is sent RFC 6266/5987 encoded ({@code filename*=UTF-8''Phi%E1%BA%BFu...}), with an RFC 2047
 * {@code filename="=?UTF-8?Q?...?="} beside it, both in ASCII, so a browser keeps the diacritics. Without the charset
 * the header would carry raw UTF-8, which a header may not, and browsers would read it as Latin-1.</p>
 *
 * <p>Not cached: a paper is drawn from the books as they stand, and a receipt voided since must not come back from a
 * cache with its confirmation.</p>
 */
public final class PdfDownload {

    private PdfDownload() {
    }

    public static ResponseEntity<byte[]> inline(String fileName, byte[] pdf) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(savable(fileName), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /**
     * The name with what no file system takes replaced by a hyphen: a number is the parties' own ("BB-01/2026" for an
     * agreement), and a browser saving "BB-01/2026.pdf" would make up a name of its own.
     */
    static String savable(String fileName) {
        StringBuilder name = new StringBuilder(fileName.length());
        fileName.codePoints().forEach(c -> name.appendCodePoint(
                Character.isISOControl(c) || "\\/:*?\"<>|".indexOf(c) >= 0 ? '-' : c));
        return name.toString().strip();
    }
}
