package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a shelf's face into {@code rows x columns} equal bins (BR-14) - the arithmetic only; which
 * levels get them, and whether they may, is the Shelf aggregate's decision (#23).
 *
 * <p>Columns run along the shelf's width (x), rows along its length (y), both in the shelf's own
 * frame, row by row from its origin. Sizes are rounded <b>down</b> to the millimetre: rounding up
 * would push the last bin past the shelf's edge, which the shelf then refuses; rounding down leaves
 * at most a few millimetres unused.</p>
 *
 * <p>The number in every code is padded to the same width, never fewer than two digits, so codes
 * sort the way the bins stand ({@code 001 ... 150}, not {@code 1, 10, 100, 101 ...}).</p>
 */
public final class BinGrid {

    /** Issue #18 D7: the Shelf aggregate is loaded and locked whole, so a level stays bounded. */
    public static final int MAX_BINS_PER_LEVEL = 200;
    private static final int MAX_LETTERED_ROWS = 26;
    private static final int MIN_DIGITS = 2;
    private static final int SCALE = 3;

    /** A bin to be created: its code within the level and its footprint in the shelf's frame. */
    public record GeneratedBin(String code, Footprint footprint) {
    }

    private BinGrid() {
    }

    public static List<GeneratedBin> generate(BigDecimal shelfWidth, BigDecimal shelfLength,
                                              int rows, int columns, BinNamingScheme scheme) {
        if (rows < 1 || columns < 1) {
            throw invalid("A bin grid needs at least one row and one column");
        }
        if (scheme == BinNamingScheme.ROW_LETTER_COLUMN && rows > MAX_LETTERED_ROWS) {
            throw invalid("Rows are lettered A-Z, so at most %d rows, got %d".formatted(MAX_LETTERED_ROWS, rows));
        }
        if ((long) rows * columns > MAX_BINS_PER_LEVEL) {
            throw new BusinessException(ErrorCode.SHELF_CAPACITY_EXCEEDED,
                    "A level holds at most %d bins, %d x %d asked for %d"
                            .formatted(MAX_BINS_PER_LEVEL, rows, columns, rows * columns));
        }

        BigDecimal width = shelfWidth.divide(BigDecimal.valueOf(columns), SCALE, RoundingMode.DOWN);
        BigDecimal length = shelfLength.divide(BigDecimal.valueOf(rows), SCALE, RoundingMode.DOWN);
        int digits = Math.max(MIN_DIGITS, String.valueOf(scheme == BinNamingScheme.SEQUENTIAL
                ? rows * columns : columns).length());

        List<GeneratedBin> bins = new ArrayList<>(rows * columns);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                String code = switch (scheme) {
                    case SEQUENTIAL -> pad(row * columns + column + 1, digits);
                    case ROW_LETTER_COLUMN -> (char) ('A' + row) + pad(column + 1, digits);
                };
                Footprint footprint = new Footprint(width.multiply(BigDecimal.valueOf(column)),
                        length.multiply(BigDecimal.valueOf(row)), width, length, 0);
                bins.add(new GeneratedBin(code, footprint));
            }
        }
        return List.copyOf(bins);
    }

    private static String pad(int number, int digits) {
        return String.format("%0" + digits + "d", number);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
}
