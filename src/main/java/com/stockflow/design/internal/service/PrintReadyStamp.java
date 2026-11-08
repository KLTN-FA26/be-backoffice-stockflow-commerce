package com.stockflow.design.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.design.internal.domain.PrintSurface;
import com.stockflow.product.api.PrintConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.stereotype.Component;

/**
 * Stamps the print file a customer submitted with what a press reads (kltn-docs 12 §4.5; plan D3): each page's
 * TrimBox and BleedBox from the template's geometry, and the PDF/X-4 identification - {@code GTS_PDFXVersion} in the
 * document information and in the XMP metadata, and {@code Trapped} set to {@code False}.
 *
 * <p>What it does not do: embed a CMYK output intent. That needs the printer's ICC profile, which the business has not
 * chosen yet (open question B35), so the file is identified as PDF/X-4 but does not conform to it; the production
 * instruction says so. Nothing here validates conformance (there is no veraPDF in the build).</p>
 *
 * <p>The file is checked against the template first ({@link PrintFileGeometry}, as at submission): a file that does not
 * fit is refused rather than stamped with boxes that would cut it in the wrong place.</p>
 */
@Component
class PrintReadyStamp {

    private static final float POINTS_PER_MM = PrintFileGeometry.POINTS_PER_MM;

    /** {@code pdf} stamped for {@code template}; {@code title} names it in its metadata. */
    byte[] stamp(byte[] pdf, PrintConfig template, String title) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            String problem = PrintFileGeometry.problem(document, template);
            if (problem != null) {
                throw invalid(problem);
            }
            for (int i = 0; i < template.printAreas().size(); i++) {
                PDPage page = document.getPage(i);
                PrintSurface surface = PrintSurface.of(template.model(), template.printAreas().get(i));
                PDRectangle media = page.getMediaBox();
                PrintSurface.Box trim = surface.trimBounds();
                // PDF space grows upwards from the page's bottom-left; the surface's grows downwards from its top-left.
                page.setBleedBox(new PDRectangle(media.getLowerLeftX(), media.getLowerLeftY(), media.getWidth(),
                        media.getHeight()));
                page.setTrimBox(new PDRectangle(media.getLowerLeftX() + (float) trim.x() * POINTS_PER_MM,
                        media.getLowerLeftY() + media.getHeight() - (float) (trim.y() + trim.height()) * POINTS_PER_MM,
                        (float) trim.width() * POINTS_PER_MM, (float) trim.height() * POINTS_PER_MM));
            }
            var info = document.getDocumentInformation();
            info.setTitle(title);
            info.setTrapped("False");
            info.setCustomMetadataValue("GTS_PDFXVersion", "PDF/X-4");
            PDMetadata metadata = new PDMetadata(document);
            metadata.importXMPMetadata(xmp(title).getBytes(StandardCharsets.UTF_8));
            document.getDocumentCatalog().setMetadata(metadata);
            var out = new ByteArrayOutputStream(pdf.length + 4096);
            document.save(out);
            return out.toByteArray();
        } catch (IOException unreadable) {
            throw invalid("The print file is not a readable PDF");
        }
    }

    /** The XMP packet: the PDF/X identification, the trapping state and the title, nothing a reader must not see. */
    private static String xmp(String title) {
        String escaped = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" xmlns:pdfxid=\"http://www.npes.org/pdfx/ns/id/\""
                + " xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                + "<pdfxid:GTS_PDFXVersion>PDF/X-4</pdfxid:GTS_PDFXVersion><pdf:Trapped>False</pdf:Trapped>"
                + "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">" + escaped + "</rdf:li></rdf:Alt></dc:title>"
                + "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
    }

    private static BusinessException invalid(String reason) {
        return new BusinessException(ErrorCode.DESIGN_PRINT_FILE_INVALID, reason);
    }
}
