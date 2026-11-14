package com.stockflow.common.export;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * How a controller hands an export over (2026-11-14 plan, BE2), the way the e-invoice register's CSV does
 * ({@code EInvoiceController.exportRegister}): as an attachment, so the browser saves it rather than opening it in a
 * tab, and never cached - the file carries the customers' names and money, and a shared proxy or the browser's cache
 * keeping a copy would outlive the permission that let it be read.
 *
 * <p>The handler declares no {@code produces}: a refusal ({@code EXPORT_TOO_LARGE}, a 403) is the usual JSON error,
 * which a handler producing only the spreadsheet type could not answer with.</p>
 */
public final class ExportResponses {

    /** The media type of an Office Open XML workbook (.xlsx). */
    public static final MediaType XLSX =
            new MediaType("application", "vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private ExportResponses() {
    }

    /**
     * @param filename the name the browser saves it as, ending in {@code .xlsx}; ASCII, since report names are built
     *                 from codes and dates
     */
    public static ResponseEntity<byte[]> xlsx(byte[] body, String filename) {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build()
                        .toString())
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
