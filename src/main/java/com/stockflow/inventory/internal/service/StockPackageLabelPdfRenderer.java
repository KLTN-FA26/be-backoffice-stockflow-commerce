package com.stockflow.inventory.internal.service;

import com.stockflow.common.label.CartonLabelSheet;
import com.stockflow.common.label.CartonLabelSheet.CartonLabel;
import com.stockflow.common.label.LabelFonts;
import com.stockflow.common.label.LabelFormat;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Draws carton labels into a PDF (SCRUM-473) with {@link CartonLabelSheet}: the LPN as large text, a QR code and a
 * Code 128 barcode all carrying exactly the LPN - the string every carton scan accepts - plus the count, the SKU, the
 * item name and the lot for the eye.
 *
 * <p>Same label stock as the location label ({@link LabelFormat}), so one printer setup serves both. Knows nothing
 * about persistence: it runs after the read-only transaction that chose the cartons has ended.</p>
 */
@Component
public class StockPackageLabelPdfRenderer {

    private final LabelFonts fonts;

    public StockPackageLabelPdfRenderer(LabelFonts fonts) {
        this.fonts = fonts;
    }

    /**
     * @return the PDF, one page per {@link LabelFormat#labelsPerPage()} labels, in the given order
     * @throws IllegalArgumentException when there is no label; the service refuses that case first
     */
    public byte[] render(List<StockPackageLabel> labels, LabelFormat format) {
        return CartonLabelSheet.render(fonts, labels.stream()
                .map(label -> new CartonLabel(label.lpn(), label.sku(), label.name(), label.lot(),
                        label.counted(), label.nominalQty(), label.unit()))
                .toList(), format);
    }
}
