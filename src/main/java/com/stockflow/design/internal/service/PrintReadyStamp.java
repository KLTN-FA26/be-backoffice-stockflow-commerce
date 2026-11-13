package com.stockflow.design.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.design.internal.domain.PrintSurface;
import com.stockflow.product.api.PrintConfig;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Stamps the print file a customer submitted with what a press reads (kltn-docs 12 §4.5; plan D3): each page's
 * TrimBox and BleedBox from the template's geometry, its CropBox the whole page (a tighter one would hide the bleed
 * from the press's software) and no ArtBox (PDF/X allows a page a TrimBox or an ArtBox, not both), and the PDF/X-4
 * identification - {@code GTS_PDFXVersion} in the document information and in the XMP metadata, and {@code Trapped}
 * set to {@code False} - and the PDF/X output intent ({@code GTS_PDFX}) of {@link PrintOutputIntentProperties}, which
 * replaces any PDF/X output intent the file brought with it (PDF/X allows one).
 *
 * <p>The output intent is interim until the printer's ICC profile is configured (open question B35): the JDK's sRGB
 * profile, which describes the RGB content the studio produces but is a display profile, so a strict PDF/X-4 checker
 * can still reject the file; the production instruction says which intent is embedded. The content is never converted
 * to the intent's colour space. Nothing here validates conformance (there is no veraPDF in the build).</p>
 *
 * <p>The file is checked against the template first ({@link PrintFileGeometry}, as at submission): a file that does not
 * fit is refused rather than stamped with boxes that would cut it in the wrong place.</p>
 *
 * <p>Both files are on disk ({@link PrintFileCopy}): a print file runs to 50 MB, and neither it nor its stamped copy is
 * held in the heap.</p>
 */
@Component
class PrintReadyStamp {

    private static final float POINTS_PER_MM = PrintFileGeometry.POINTS_PER_MM;
    private static final COSName GTS_PDFX = COSName.getPDFName("GTS_PDFX");

    private final PrintOutputIntentProperties intent;
    /** The ICC profile's bytes, read and checked once: a profile that cannot be read stops startup, not an approval. */
    private final byte[] profile;

    PrintReadyStamp(PrintOutputIntentProperties intent) {
        this.intent = intent;
        this.profile = profileOf(intent);
    }

    private static byte[] profileOf(PrintOutputIntentProperties intent) {
        if (intent.interim()) {
            return ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
        }
        Resource resource = new DefaultResourceLoader().getResource(intent.profile());
        try (InputStream in = resource.getInputStream()) {
            // getInstance refuses what is not an ICC profile, so a wrong file is found here rather than by the press.
            return ICC_Profile.getInstance(in).getData();
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("stockflow.design.print.output-intent.profile " + intent.profile()
                    + " is not a readable ICC profile", ex);
        }
    }

    /**
     * {@code pdf} stamped for {@code template}, written to {@code out}; {@code title} names it in its metadata.
     *
     * @throws BusinessException {@code DESIGN_PRINT_FILE_INVALID} when the file does not fit the template or cannot be
     *         read - PDFBox fails on a damaged file with runtime exceptions too, and with a stack overflow on a cyclic one
     */
    void stamp(PrintFileCopy pdf, PrintConfig template, String title, PrintFileCopy out) {
        try (PDDocument document = pdf.open()) {
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
                page.setCropBox(new PDRectangle(media.getLowerLeftX(), media.getLowerLeftY(), media.getWidth(),
                        media.getHeight()));
                page.getCOSObject().removeItem(COSName.ART_BOX);
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
            embedOutputIntent(document);
            document.save(out.path().toFile());
        } catch (BusinessException refused) {
            throw refused;
        } catch (IOException | RuntimeException | StackOverflowError unreadable) {
            throw invalid("The print file is not a readable PDF");
        }
    }

    /** The configured PDF/X output intent, in place of any the file had; other standards' intents (PDF/A) stay. */
    private void embedOutputIntent(PDDocument document) throws IOException {
        PDOutputIntent outputIntent = new PDOutputIntent(document, new ByteArrayInputStream(profile));
        outputIntent.getCOSObject().setItem(COSName.S, GTS_PDFX);
        outputIntent.setOutputConditionIdentifier(intent.outputConditionIdentifier());
        outputIntent.setInfo(intent.info());
        outputIntent.setRegistryName(intent.registryName());
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        List<PDOutputIntent> intents = new ArrayList<>();
        for (PDOutputIntent existing : catalog.getOutputIntents()) {
            if (!GTS_PDFX.equals(existing.getCOSObject().getCOSName(COSName.S))) {
                intents.add(existing);
            }
        }
        intents.add(outputIntent);
        catalog.setOutputIntents(intents);
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
