package com.stockflow.inventory.internal.service;

import com.stockflow.common.label.CodeLabelSheet;
import com.stockflow.common.label.CodeLabelSheet.CodeLabel;
import com.stockflow.common.label.LabelFonts;
import com.stockflow.common.label.LabelFormat;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Draws carton labels into a PDF (SCRUM-473): the LPN as large text, a QR code and a Code 128 barcode,
 * all three carrying exactly the LPN - the string every carton scan accepts - and beneath the text the SKU and
 * {@code received / nominal UNIT}.
 *
 * <p>No item name: it lives in product's schema, which inventory may not read.</p>
 *
 * <p>The layout is the location label's, the shared {@link CodeLabelSheet}, so one printer setup serves both.
 * Knows nothing about persistence: it runs after the read-only transaction that chose the cartons has ended.</p>
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
        return CodeLabelSheet.render(fonts, labels.stream()
                .map(label -> new CodeLabel(label.lpn(), label.sku(), quantityLine(label)))
                .toList(), format);
    }

    /** {@code 980 / 1000 CARTON}; a carton not yet counted shows a dash for what it holds. */
    private static String quantityLine(StockPackageLabel label) {
        return "%s / %d %s".formatted(label.receivedQty() == null ? "-" : label.receivedQty().toString(),
                label.nominalQty(), label.unit());
    }
}
