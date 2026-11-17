package com.stockflow.support;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Real PDFs for tests that upload one. An upload's PDF is opened and inspected for active content
 * ({@code PdfContentPolicy}) and, for a design, preflighted, so a few bytes starting with {@code %PDF} no longer pass
 * for one.
 */
public final class TestPdfs {

    private TestPdfs() {
    }

    /** One blank A4 page: no fonts, no images, nothing active. */
    public static byte[] blank() {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(PDRectangle.A4));
            document.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
