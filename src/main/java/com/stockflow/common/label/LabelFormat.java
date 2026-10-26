package com.stockflow.common.label;

/**
 * The two kinds of label stock a location label is printed on (SCRUM-97). Both draw the same label;
 * they differ only in how labels are laid onto pages.
 *
 * <p>All lengths are millimetres: label stock is sold by its millimetre size, and a printer driver
 * that scales "to fit" ruins a barcode, so the PDF page is the physical page.</p>
 */
public enum LabelFormat {

    /**
     * Office printer on an A4 sheet of 24 die-cut labels, 70 x 37 mm in 3 columns and 8 rows - the
     * common "A4 24 labels" decal sheet, whose rows start 0.5 mm from the top edge.
     */
    A4_SHEET(210, 297, 70, 37, 3, 3, 8, 0.5, 16),

    /** Thermal label printer on a 60 x 40 mm roll: every page is one label. */
    THERMAL_ROLL(60, 40, 60, 40, 2.5, 1, 1, 0, 15);

    private final double pageWidthMm;
    private final double pageHeightMm;
    private final double labelWidthMm;
    private final double labelHeightMm;
    private final double paddingMm;
    private final int columns;
    private final int rows;
    private final double topMarginMm;
    private final double qrSideMm;

    LabelFormat(double pageWidthMm, double pageHeightMm, double labelWidthMm, double labelHeightMm,
                double paddingMm, int columns, int rows, double topMarginMm, double qrSideMm) {
        this.pageWidthMm = pageWidthMm;
        this.pageHeightMm = pageHeightMm;
        this.labelWidthMm = labelWidthMm;
        this.labelHeightMm = labelHeightMm;
        this.paddingMm = paddingMm;
        this.columns = columns;
        this.rows = rows;
        this.topMarginMm = topMarginMm;
        this.qrSideMm = qrSideMm;
    }

    public double pageWidthMm() { return pageWidthMm; }
    public double pageHeightMm() { return pageHeightMm; }
    public double labelWidthMm() { return labelWidthMm; }
    public double labelHeightMm() { return labelHeightMm; }
    /** Blank margin inside each label; die-cut stock is never cut exactly on the line. */
    public double paddingMm() { return paddingMm; }
    public int columns() { return columns; }
    public int rows() { return rows; }
    public double topMarginMm() { return topMarginMm; }
    public double qrSideMm() { return qrSideMm; }

    public int labelsPerPage() {
        return columns * rows;
    }
}
