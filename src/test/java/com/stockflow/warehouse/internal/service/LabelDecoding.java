package com.stockflow.warehouse.internal.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;

/**
 * Reads a rendered label back the way a scanner would: crop the label's cell from a rasterised page,
 * then decode one symbology with ZXing. What a test asserts is what the scanner gets.
 */
final class LabelDecoding {

    private LabelDecoding() {
    }

    /** @throws AssertionError when no symbol of {@code format} is found */
    static String decode(BufferedImage image, BarcodeFormat format) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        var bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, height, pixels)));
        try {
            return new MultiFormatReader().decode(bitmap, Map.of(
                    DecodeHintType.POSSIBLE_FORMATS, List.of(format),
                    DecodeHintType.TRY_HARDER, Boolean.TRUE)).getText();
        } catch (NotFoundException e) {
            throw new AssertionError("No " + format + " found in the label", e);
        }
    }

    /** Label {@code index} (row-major, within its page) of a page rendered at {@code dpi}. */
    static BufferedImage cell(BufferedImage page, LabelFormat format, int index, float dpi) {
        int slot = index % format.labelsPerPage();
        int column = slot % format.columns();
        int row = slot / format.columns();
        int x = px(column * format.labelWidthMm(), dpi);
        int y = px(format.topMarginMm() + row * format.labelHeightMm(), dpi);
        int w = Math.min(px(format.labelWidthMm(), dpi), page.getWidth() - x);
        int h = Math.min(px(format.labelHeightMm(), dpi), page.getHeight() - y);
        return page.getSubimage(x, y, w, h);
    }

    private static int px(double mm, float dpi) {
        return (int) Math.round(mm * dpi / 25.4);
    }
}
