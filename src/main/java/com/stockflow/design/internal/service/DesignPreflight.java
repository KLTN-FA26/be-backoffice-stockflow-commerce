package com.stockflow.design.internal.service;

import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.StorageException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters;
import org.apache.pdfbox.contentstream.operator.state.SetMatrix;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * BR-DSG-001's preflight, by the machine: what a print file must be before a person spends a technical review on it.
 *
 * <h2>Blocking - the review cannot pass</h2>
 * <ul>
 *   <li>{@code PDF_UNREADABLE}: the PDF does not open (damaged, or locked with a password);</li>
 *   <li>{@code FONT_NOT_EMBEDDED}: a font the print shop's RIP would substitute;</li>
 *   <li>{@code LOW_RESOLUTION}: a placed image below {@value #MIN_EFFECTIVE_DPI} dpi at the size it is printed.</li>
 * </ul>
 *
 * <h2>Warnings - the reviewer decides</h2>
 * <ul>
 *   <li>{@code RGB_COLOUR}: an RGB image, which the print shop converts to CMYK (colours shift);</li>
 *   <li>{@code NO_BLEED}: no bleed beyond the trim, so a colour running to the edge may show a white sliver;</li>
 *   <li>{@code RASTER_ARTWORK}: a JPEG/PNG/WebP file, whose print size this check cannot know - its pixel size is
 *       reported for the reviewer to judge against the product's print area.</li>
 * </ul>
 *
 * <p>The file is read from storage into a temporary file and parsed with temp-file caching, so a 50 MB PDF does not
 * land in the heap. The template-specific limits of {@code product.customization_templates} (minimum dpi, colour
 * mode) are not read yet: {@value #MIN_EFFECTIVE_DPI} dpi is that table's own default.</p>
 */
@Component
public class DesignPreflight {

    static final int MIN_EFFECTIVE_DPI = 150;
    private static final int MAX_FORM_DEPTH = 8;

    public record Finding(String code, String detail, boolean blocking) {
    }

    public record Report(boolean passed, List<Finding> findings) {
        public Report {
            findings = List.copyOf(findings);
        }

        static Report of(List<Finding> findings) {
            return new Report(findings.stream().noneMatch(Finding::blocking), findings);
        }

        String blockingSummary() {
            return String.join(", ", findings.stream().filter(Finding::blocking)
                    .map(finding -> finding.code() + " (" + finding.detail() + ")").toList());
        }
    }

    private final FileStorage storage;

    DesignPreflight(FileStorage storage) {
        this.storage = storage;
    }

    public Report inspect(String storageKey, String contentType) {
        Path file = null;
        try (InputStream content = storage.download(storageKey)) {
            file = Files.createTempFile("stockflow-preflight-", ".bin");
            Files.copy(content, file, StandardCopyOption.REPLACE_EXISTING);
            return "application/pdf".equalsIgnoreCase(contentType) ? pdf(file) : raster(file);
        } catch (IOException ex) {
            throw new StorageException("Could not read the design file for preflight", ex);
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // A temp file left behind is removed with the temp directory.
                }
            }
        }
    }

    private static Report pdf(Path file) {
        List<Finding> findings = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(file.toFile(), "", null, null, IOUtils.createTempFileOnlyStreamCache())) {
            Set<String> unembedded = new LinkedHashSet<>();
            var scale = new ImageScale();
            boolean bleed = true;
            int pageNumber = 0;
            for (PDPage page : document.getPages()) {
                pageNumber++;
                fonts(page.getResources(), unembedded, 0);
                scale.page = pageNumber;
                scale.processPage(page);
                if (!hasBleed(page)) {
                    bleed = false;
                }
            }
            for (String font : unembedded) {
                findings.add(new Finding("FONT_NOT_EMBEDDED", font, true));
            }
            if (scale.lowest != null) {
                findings.add(new Finding("LOW_RESOLUTION", scale.lowest, true));
            }
            if (scale.rgb) {
                findings.add(new Finding("RGB_COLOUR", "an RGB image is converted to CMYK for printing", false));
            }
            if (!bleed) {
                findings.add(new Finding("NO_BLEED", "a page has no bleed beyond its trim", false));
            }
        } catch (IOException | RuntimeException unreadable) {
            findings.add(new Finding("PDF_UNREADABLE", "the PDF cannot be opened", true));
        }
        return Report.of(findings);
    }

    private static Report raster(Path file) {
        try (var input = ImageIO.createImageInputStream(file.toFile())) {
            var readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) {
                return Report.of(List.of(new Finding("IMAGE_UNREADABLE", "the image cannot be decoded", true)));
            }
            var reader = readers.next();
            try {
                reader.setInput(input);
                return Report.of(List.of(new Finding("RASTER_ARTWORK", "%d x %d px: at %d dpi that prints at most %.0f x %.0f mm"
                        .formatted(reader.getWidth(0), reader.getHeight(0), MIN_EFFECTIVE_DPI,
                                reader.getWidth(0) * 25.4 / MIN_EFFECTIVE_DPI,
                                reader.getHeight(0) * 25.4 / MIN_EFFECTIVE_DPI), false)));
            } finally {
                reader.dispose();
            }
        } catch (IOException ex) {
            return Report.of(List.of(new Finding("IMAGE_UNREADABLE", "the image cannot be decoded", true)));
        }
    }

    private static void fonts(PDResources resources, Set<String> unembedded, int depth) throws IOException {
        if (resources == null || depth > MAX_FORM_DEPTH) {
            return;
        }
        for (COSName name : resources.getFontNames()) {
            var font = resources.getFont(name);
            if (font != null && !font.isEmbedded()) {
                unembedded.add(font.getName() == null ? name.getName() : font.getName());
            }
        }
        for (COSName name : resources.getXObjectNames()) {
            if (resources.getXObject(name) instanceof PDFormXObject form) {
                fonts(form.getResources(), unembedded, depth + 1);
            }
        }
    }

    private static boolean hasBleed(PDPage page) {
        PDRectangle bleed = page.getBleedBox();
        PDRectangle trim = page.getTrimBox();
        return bleed.getWidth() > trim.getWidth() + 0.5f && bleed.getHeight() > trim.getHeight() + 0.5f;
    }

    /** Walks a page's content tracking the transformation, to know each image's size where it is drawn. */
    private static final class ImageScale extends PDFStreamEngine {
        int page;
        String lowest;
        double lowestDpi = Double.MAX_VALUE;
        boolean rgb;

        ImageScale() {
            addOperator(new Concatenate(this));
            addOperator(new Save(this));
            addOperator(new Restore(this));
            addOperator(new SetMatrix(this));
            addOperator(new SetGraphicsStateParameters(this));
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if ("Do".equals(operator.getName()) && !operands.isEmpty() && operands.get(0) instanceof COSName name) {
                PDXObject object = getResources().getXObject(name);
                if (object instanceof PDImageXObject image) {
                    Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
                    double widthInches = Math.abs(ctm.getScalingFactorX()) / 72d;
                    double heightInches = Math.abs(ctm.getScalingFactorY()) / 72d;
                    if (widthInches > 0 && heightInches > 0) {
                        double dpi = Math.min(image.getWidth() / widthInches, image.getHeight() / heightInches);
                        if (dpi < MIN_EFFECTIVE_DPI && dpi < lowestDpi) {
                            lowestDpi = dpi;
                            lowest = "page %d: an image prints at %.0f dpi".formatted(page, dpi);
                        }
                    }
                    if (image.getColorSpace() instanceof PDDeviceRGB) {
                        rgb = true;
                    }
                    return;
                }
                if (object instanceof PDFormXObject form) {
                    showForm(form);
                    return;
                }
            }
            super.processOperator(operator, operands);
        }
    }
}
