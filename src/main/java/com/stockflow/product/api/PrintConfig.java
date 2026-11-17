package com.stockflow.product.api;

import java.util.List;
import java.util.UUID;

/**
 * A customization template: what a customer may print on one variant, and where (kltn-docs 01 §4.10, BR-21; 12 BR-02).
 * Stored in {@code product.customization_templates} and {@code product.print_areas}.
 *
 * @param templateId         the template's id
 * @param productId          the product the variant belongs to
 * @param variantId          the variant (quy cách) the template hangs off
 * @param sku                that variant's SKU
 * @param code               unique per variant
 * @param name               what the customer is shown
 * @param minDpi             the lowest effective resolution an image may be placed at (kltn-docs 12 BR-05, B34)
 * @param colorMode          {@code CMYK} or {@code RGB}: what the producer prints in
 * @param allowedFileFormats MIME types a customer may upload into a design on this template
 * @param maxFileSizeMb      the largest upload, in megabytes
 * @param maxColors          the most ink colours the producer prints, or null for no limit
 * @param allowedFontCodes   the fonts a design may use, or null for every font the studio offers
 * @param active             whether customers may start designs on it; at most one per variant
 * @param model              the parametric product the areas are computed from
 * @param printAreas         the printable surfaces, in display order
 * @param referenceFileKey   storage key of the reference die-line the Sales Manager attached, or null
 * @param referenceFileName  that file's name as uploaded, or null
 * @param version            optimistic-lock version
 * @param model3d            the {@code .glb} the model was measured from and its print-area-to-mesh map; never null
 */
public record PrintConfig(UUID templateId, UUID productId, UUID variantId, String sku, String code, String name,
                          int minDpi, String colorMode, List<String> allowedFileFormats, int maxFileSizeMb,
                          Integer maxColors, List<String> allowedFontCodes, boolean active, ProductModel model,
                          List<PrintArea> printAreas, String referenceFileKey, String referenceFileName,
                          long version, TemplateModel3d model3d) {

    public PrintConfig {
        allowedFileFormats = List.copyOf(allowedFileFormats);
        allowedFontCodes = allowedFontCodes == null ? null : List.copyOf(allowedFontCodes);
        printAreas = List.copyOf(printAreas);
        model3d = model3d == null ? TemplateModel3d.NONE : model3d;
    }

    /** A template with no 3D model. */
    public PrintConfig(UUID templateId, UUID productId, UUID variantId, String sku, String code, String name,
                       int minDpi, String colorMode, List<String> allowedFileFormats, int maxFileSizeMb,
                       Integer maxColors, List<String> allowedFontCodes, boolean active, ProductModel model,
                       List<PrintArea> printAreas, String referenceFileKey, String referenceFileName, long version) {
        this(templateId, productId, variantId, sku, code, name, minDpi, colorMode, allowedFileFormats, maxFileSizeMb,
                maxColors, allowedFontCodes, active, model, printAreas, referenceFileKey, referenceFileName, version,
                TemplateModel3d.NONE);
    }

    /** The print area with {@code code}, or null when the template has none. */
    public PrintArea area(String code) {
        for (PrintArea area : printAreas) {
            if (area.code().equals(code)) {
                return area;
            }
        }
        return null;
    }
}
