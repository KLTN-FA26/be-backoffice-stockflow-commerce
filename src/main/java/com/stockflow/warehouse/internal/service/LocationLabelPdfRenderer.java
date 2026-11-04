package com.stockflow.warehouse.internal.service;

import com.stockflow.common.label.CodeLabelSheet;
import com.stockflow.common.label.CodeLabelSheet.CodeLabel;
import com.stockflow.common.label.LabelFonts;
import com.stockflow.common.label.LabelFormat;
import com.stockflow.warehouse.internal.domain.StorageLocationKind;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Draws location labels into a PDF (SCRUM-97): the code as text, a QR code, and a Code 128 barcode,
 * all three carrying exactly the location code - the string every scan endpoint accepts - and under
 * the code the warehouse name and where the location sits in it.
 *
 * <p>Every bar and every QR module is a filled vector rectangle rather than a raster image, so a
 * label is as sharp on a 203 dpi thermal printer as on a 600 dpi office printer. Code 128 is what a
 * handheld 1D scanner reads; the QR code is for phone and tablet cameras, and stays readable when a
 * very long code forces the barcode's bars below what a scanner resolves. The layout is the shared
 * {@link CodeLabelSheet}, the carton label's too.</p>
 *
 * <p>Knows nothing about persistence: it is called after the read-only transaction that chose the
 * labels has ended, so no connection is held while a large PDF is drawn.</p>
 */
@Component
public class LocationLabelPdfRenderer {

    private final LabelFonts fonts;

    public LocationLabelPdfRenderer(LabelFonts fonts) {
        this.fonts = fonts;
    }

    /**
     * @return the PDF, one page per {@link LabelFormat#labelsPerPage()} labels, in the sheet's order
     * @throws IllegalArgumentException when the sheet has no label; the service refuses that case first
     */
    public byte[] render(LocationLabelSheet sheet, LabelFormat format, LabelWords words) {
        List<CodeLabel> labels = sheet.labels().stream()
                .map(label -> new CodeLabel(label.code(), sheet.warehouseName(), context(label, words)))
                .toList();
        return CodeLabelSheet.render(fonts, labels, format);
    }

    private static String context(LocationLabel label, LabelWords words) {
        if (label.kind() == StorageLocationKind.AREA) {
            return "%s · %s".formatted(label.areaName(), label.storageClass());
        }
        return "%s %s · %s %d · %s %s · %s".formatted(words.shelf(), label.shelfCode(), words.level(),
                label.levelIndex(), words.bin(), label.binCode(), label.storageClass());
    }
}
