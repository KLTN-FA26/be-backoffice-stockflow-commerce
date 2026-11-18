package com.stockflow.design.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.product.api.PrintConfig;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.ResourceUtils;

/**
 * Stamps the print file a customer submitted with what a press reads (kltn-docs 12 §4.5; plan D3): each page's
 * TrimBox from the template's geometry - a cup's print area or a bag's flat sheet, {@link PrintFileGeometry#frames} -
 * placed from the page's BleedBox, which is kept as the studio declared it; its CropBox the whole page, blank margin
 * and slug included (a tighter one would hide the bleed and the slug from the press's software) and no ArtBox (PDF/X
 * allows a page a TrimBox or an ArtBox, not both), and the PDF/X-4 identification - {@code GTS_PDFXVersion} in the
 * document information and in the XMP metadata, and {@code Trapped} set to {@code False} - and the PDF/X output intent ({@code GTS_PDFX}) of {@link PrintOutputIntentProperties}, which
 * replaces any PDF/X output intent the file brought with it (PDF/X allows one).
 *
 * <p>The output intent is ISO Coated v2 (FOGRA39) by default, the profile bundled with the application and the one the
 * storefront embeds (FE request 2026-11-18 BE-3), until a printer names its own; the production instruction says which
 * intent is embedded. The content is never converted to the intent's colour space. Nothing here validates conformance
 * (there is no veraPDF in the build).</p>
 *
 * <p>Everything else the storefront wrote is kept: the XMP is its own with only the stamp's fields set again
 * ({@link PrintFileXmp}, BE-4), and the catalogue - the optional content groups of every element, the guide layer
 * off, a plastic cup's white underlay and its {@code White} separation - is saved as it was read (BE-5).</p>
 *
 * <p>The file is checked against the template first ({@link PrintFileGeometry}, as at submission): a file that does not
 * fit is refused rather than stamped with boxes that would cut it in the wrong place.</p>
 *
 * <p>Both files are on disk ({@link PrintFileCopy}): a print file runs to 50 MB, and neither it nor its stamped copy is
 * held in the heap.</p>
 */
@Component
class PrintReadyStamp {

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
        try (InputStream in = resource(intent.profile()).getInputStream()) {
            byte[] bytes = in.readAllBytes();
            // getInstance refuses what is not an ICC profile, so a wrong file is found here rather than by the press.
            ICC_Profile.getInstance(bytes);
            return bytes;
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("stockflow.design.print.output-intent.profile " + intent.profile()
                    + " is not a readable ICC profile", ex);
        }
    }

    /**
     * {@code location} as a Spring resource ({@code classpath:}, {@code file:}, a URL), or else as a path on disk. The
     * resource loader alone would read {@code /etc/stockflow/press.icc} as a classpath entry, and a plain path is what
     * an operator writes into {@code DESIGN_PRINT_ICC_PROFILE}.
     */
    static Resource resource(String location) {
        return ResourceUtils.isUrl(location) ? new DefaultResourceLoader().getResource(location)
                : new FileSystemResource(location);
    }

    /**
     * {@code pdf} stamped for {@code template}, written to {@code out}; {@code title} names it in its metadata.
     *
     * @throws BusinessException {@code DESIGN_PRINT_FILE_INVALID} when the file does not fit the template or cannot be
     *         read - PDFBox fails on a damaged file with runtime exceptions too, and with a stack overflow on a cyclic one
     * @return the pages the stamped file has: one for a bag's flat sheet, four for a bag submitted side by side before
     *         the sheet (FE request 2026-11-18 BE-10), one per print area for a cup
     */
    int stamp(PrintFileCopy pdf, PrintConfig template, String title, PrintFileCopy out) {
        try (PDDocument document = pdf.open()) {
            String problem = PrintFileGeometry.problem(document, template);
            if (problem != null) {
                throw invalid(problem);
            }
            List<PrintFileGeometry.PageFrame> frames =
                    PrintFileGeometry.frames(template, document.getNumberOfPages());
            for (int i = 0; i < frames.size(); i++) {
                PDPage page = document.getPage(i);
                PDRectangle media = page.getMediaBox();
                // The trim is placed from the BleedBox's origin, the artwork's frame, not the MediaBox's: the studio's
                // page has blank paper and a slug strip around the artwork (FE request 2026-11-18 BE-2). A declared
                // BleedBox is kept; a page without one is given the MediaBox it was measured by.
                PDRectangle bleed = PrintFileGeometry.bleedBox(page);
                page.setCropBox(new PDRectangle(media.getLowerLeftX(), media.getLowerLeftY(), media.getWidth(),
                        media.getHeight()));
                page.getCOSObject().removeItem(COSName.ART_BOX);
                if (PrintFileGeometry.declared(page, COSName.BLEED_BOX) == null) {
                    page.setBleedBox(new PDRectangle(bleed.getLowerLeftX(), bleed.getLowerLeftY(), bleed.getWidth(),
                            bleed.getHeight()));
                }
                page.setTrimBox(PrintFileGeometry.trimBox(bleed, frames.get(i).trimMm()));
            }
            Instant stampedAt = Instant.now();
            var info = document.getDocumentInformation();
            info.setTitle(title);
            info.setTrapped("False");
            info.setCustomMetadataValue("GTS_PDFXVersion", "PDF/X-4");
            // The same moment as xmp:ModifyDate: PDF/X wants the information dictionary to agree with the XMP.
            info.setModificationDate(GregorianCalendar.from(stampedAt.atZone(ZoneOffset.UTC)));
            PDMetadata metadata = new PDMetadata(document);
            metadata.importXMPMetadata(PrintFileXmp.stamped(document, title, stampedAt));
            document.getDocumentCatalog().setMetadata(metadata);
            embedOutputIntent(document);
            document.save(out.path().toFile());
            return frames.size();
        } catch (BusinessException refused) {
            throw refused;
        } catch (IOException | RuntimeException | StackOverflowError unreadable) {
            throw invalid("The print file is not a readable PDF");
        }
    }

    /** The configured PDF/X output intent, in place of any the file had; other standards' intents (PDF/A) stay. */
    private void embedOutputIntent(PDDocument document) throws IOException {
        PDOutputIntent outputIntent = new PDOutputIntent(document, new ByteArrayInputStream(profile));
        // PDFBox embeds the JDK's copy of the profile, which pads it to four bytes and rewrites its size - and so no
        // longer matches the profile ID (an MD5 over the header, size included) in ECI's file. The file as shipped
        // goes in instead, byte for byte the one the storefront embeds.
        try (OutputStream raw = outputIntent.getDestOutputIntent().createOutputStream(COSName.FLATE_DECODE)) {
            raw.write(profile);
        }
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

    private static BusinessException invalid(String reason) {
        return new BusinessException(ErrorCode.DESIGN_PRINT_FILE_INVALID, reason);
    }
}
