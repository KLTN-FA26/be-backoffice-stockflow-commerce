package com.stockflow.common.export;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * One way to write a report as an XLSX file (2026-11-14 plan, BE2; kltn-docs 15 BR-06: the accountant takes what the
 * system knows into the accounting software, and managers read reports in Excel). Every export of the system goes
 * through here, so they all look the same and none of them repeats the traps below.
 *
 * <ul>
 *   <li><b>Streamed</b> ({@link SXSSFWorkbook}): only {@value #WINDOW} rows are in memory at a time, the rest goes to a
 *       compressed temporary file that is deleted when the file is written - a 50,000-row export does not hold 50,000
 *       row objects on the heap.</li>
 *   <li><b>At most {@value #MAX_ROWS} data rows a sheet</b>, else {@code EXPORT_TOO_LARGE} (422). Refused rather than
 *       cut: a file that silently stops at a limit reads as complete.</li>
 *   <li><b>A text cell is a string, never a formula.</b> A customer name or a note typed as {@code =HYPERLINK(...)}
 *       stays the text it was typed as: nothing here ever calls {@code setCellFormula}, and the text style is Excel's
 *       {@code @} format, so editing the cell does not turn it into one either.</li>
 *   <li><b>Typed columns</b> ({@link Kind}): numbers stay numbers (sums and filters work in Excel), money is
 *       {@code #,##0}, volumes {@code 0.000}, dates {@code dd/mm/yyyy}, instants {@code hh:mm dd/mm/yyyy} in the business
 *       calendar's zone (Asia/Ho_Chi_Minh, {@link BusinessCalendar}) - never UTC, which would date an evening receipt
 *       the next day.</li>
 *   <li><b>No auto-sizing</b>: POI measures text with AWT fonts, which a headless server image may not have
 *       ("Fontconfig head is null"); a column is as wide as its longest value in characters, within bounds.</li>
 * </ul>
 *
 * <p>Layout of a sheet: the title lines (the first in bold, the next ones - what was filtered, when it was exported - in
 * italics), a blank row, the header row in bold with an autofilter, the panes frozen below it, then the rows.</p>
 */
public final class XlsxTable {

    /** The most data rows one sheet takes (the plan's bound; Excel itself takes 1,048,576). */
    public static final int MAX_ROWS = 50_000;

    /** Rows SXSSF keeps in memory before flushing to its temporary file. */
    static final int WINDOW = 500;

    /** Excel's limit on the characters of one cell: a longer value is cut, never refused. */
    static final int MAX_CELL_TEXT = 32_767;

    private static final int MIN_WIDTH = 8;
    private static final int MAX_WIDTH = 60;

    /** How a column's values are written and formatted. */
    public enum Kind {
        /** Anything, written as its {@code toString()} (an enum as its name), as a string cell. */
        TEXT,
        /** A whole number ({@link Number}), {@code #,##0}. */
        INTEGER,
        /** An amount of money ({@link Number}), {@code #,##0}: VND has no minor unit. */
        MONEY,
        /** A decimal such as a volume in m³ ({@link Number}), {@code 0.000}. */
        DECIMAL,
        /** A {@link LocalDate}, {@code dd/mm/yyyy}. */
        DATE,
        /** An {@link Instant} (or {@link OffsetDateTime}, {@link LocalDateTime}), {@code hh:mm dd/mm/yyyy}, Vietnam time. */
        DATETIME
    }

    /**
     * One column: its header, its kind and how to read its value from a row; a null value leaves the cell empty.
     */
    public record Column<T>(String header, Kind kind, Function<? super T, ?> value) {

        public Column {
            Objects.requireNonNull(header, "header");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * One sheet: its name (made safe for Excel: at most 31 characters, none of {@code []*?/\:}), the lines above the
     * table, the columns and the rows. The rows are read once, in order.
     */
    public record Sheet<T>(String name, List<String> titleLines, List<Column<T>> columns, Iterable<? extends T> rows) {

        public Sheet {
            Objects.requireNonNull(name, "name");
            titleLines = titleLines == null ? List.of() : titleLines.stream().map(line -> line == null ? "" : line)
                    .toList();
            if (columns == null || columns.isEmpty()) {
                throw new IllegalArgumentException("A sheet has at least one column");
            }
            columns = List.copyOf(columns);
            Objects.requireNonNull(rows, "rows");
        }
    }

    private XlsxTable() {
    }

    /** A workbook of one sheet. */
    public static <T> byte[] write(String sheetName, List<String> titleLines, List<Column<T>> columns,
                                   Iterable<? extends T> rows) {
        return write(List.of(new Sheet<>(sheetName, titleLines, columns, rows)));
    }

    /**
     * A workbook of these sheets, in order.
     *
     * @throws BusinessException {@code EXPORT_TOO_LARGE} when a sheet has more than {@value #MAX_ROWS} rows - checked
     *                           before anything is written when the rows are a {@link Collection}
     */
    public static byte[] write(List<? extends Sheet<?>> sheets) {
        if (sheets == null || sheets.isEmpty()) {
            throw new IllegalArgumentException("A workbook has at least one sheet");
        }
        for (Sheet<?> sheet : sheets) {
            if (sheet.rows() instanceof Collection<?> rows && rows.size() > MAX_ROWS) {
                throw tooLarge(sheet.name());
            }
        }
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(WINDOW)) {
            try {
                workbook.setCompressTempFiles(true);
                Styles styles = new Styles(workbook);
                for (Sheet<?> sheet : sheets) {
                    writeSheet(workbook, styles, sheet);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                workbook.write(out);
                return out.toByteArray();
            } finally {
                // The temporary files of the flushed rows; close() alone leaves them behind on some POI versions.
                workbook.dispose();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("The spreadsheet could not be written", e);
        }
    }

    private static <T> void writeSheet(SXSSFWorkbook workbook, Styles styles, Sheet<T> spec) {
        SXSSFSheet sheet = workbook.createSheet(WorkbookUtil.createSafeSheetName(spec.name()));
        List<Column<T>> columns = spec.columns();
        int next = 0;
        for (int i = 0; i < spec.titleLines().size(); i++) {
            Cell cell = sheet.createRow(next++).createCell(0);
            cell.setCellValue(clip(spec.titleLines().get(i)));
            cell.setCellStyle(i == 0 ? styles.title : styles.subtitle);
        }
        if (next > 0) {
            next++;
        }
        int headerRow = next;
        Row header = sheet.createRow(next++);
        int[] widths = new int[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            Column<T> column = columns.get(c);
            Cell cell = header.createCell(c);
            cell.setCellValue(clip(column.header()));
            cell.setCellStyle(styles.header);
            widths[c] = Math.max(column.header().length(), MIN_WIDTH);
        }
        int count = 0;
        for (T item : spec.rows()) {
            if (++count > MAX_ROWS) {
                throw tooLarge(spec.name());
            }
            Row row = sheet.createRow(next++);
            for (int c = 0; c < columns.size(); c++) {
                Column<T> column = columns.get(c);
                Object value = column.value().apply(item);
                if (value != null) {
                    widths[c] = Math.max(widths[c], write(row.createCell(c), column.kind(), value, styles));
                }
            }
        }
        sheet.createFreezePane(0, headerRow + 1);
        sheet.setAutoFilter(new CellRangeAddress(headerRow, next - 1, 0, columns.size() - 1));
        for (int c = 0; c < columns.size(); c++) {
            sheet.setColumnWidth(c, Math.min(widths[c] + 2, MAX_WIDTH) * 256);
        }
    }

    /** Writes the value and answers how many characters it shows, for the column's width. */
    private static int write(Cell cell, Kind kind, Object value, Styles styles) {
        switch (kind) {
            case TEXT -> {
                String text = clip(value instanceof Enum<?> constant ? constant.name() : value.toString());
                cell.setCellValue(text);
                cell.setCellStyle(styles.text);
                return text.length();
            }
            case INTEGER, MONEY, DECIMAL -> {
                BigDecimal number = number(value, kind);
                cell.setCellValue(number.doubleValue());
                cell.setCellStyle(kind == Kind.DECIMAL ? styles.decimal : kind == Kind.MONEY ? styles.money
                        : styles.integer);
                int digits = number.setScale(0, java.math.RoundingMode.DOWN).abs().toPlainString().length();
                return digits + digits / 3 + (number.signum() < 0 ? 1 : 0) + (kind == Kind.DECIMAL ? 4 : 0);
            }
            case DATE -> {
                if (!(value instanceof LocalDate day)) {
                    throw new IllegalArgumentException("A DATE column takes a LocalDate, not " + value.getClass());
                }
                cell.setCellValue(day);
                cell.setCellStyle(styles.date);
                return 10;
            }
            case DATETIME -> {
                cell.setCellValue(localTime(value));
                cell.setCellStyle(styles.dateTime);
                return 16;
            }
            default -> throw new IllegalStateException("Unknown column kind " + kind);
        }
    }

    private static BigDecimal number(Object value, Kind kind) {
        return switch (value) {
            case BigDecimal decimal -> decimal;
            case Number number -> new BigDecimal(number.toString());
            default -> throw new IllegalArgumentException("A " + kind + " column takes a number, not "
                    + value.getClass());
        };
    }

    private static LocalDateTime localTime(Object value) {
        return switch (value) {
            case Instant instant -> LocalDateTime.ofInstant(instant, BusinessCalendar.ZONE);
            case OffsetDateTime offset -> offset.atZoneSameInstant(BusinessCalendar.ZONE).toLocalDateTime();
            case LocalDateTime local -> local;
            default -> throw new IllegalArgumentException("A DATETIME column takes an Instant, not "
                    + value.getClass());
        };
    }

    private static String clip(String text) {
        return text.length() > MAX_CELL_TEXT ? text.substring(0, MAX_CELL_TEXT) : text;
    }

    private static BusinessException tooLarge(String sheet) {
        return new BusinessException(ErrorCode.EXPORT_TOO_LARGE, "Sheet '%s' would have more than %d rows"
                .formatted(sheet, MAX_ROWS));
    }

    /** The cell styles of one workbook: a style belongs to its workbook, and a workbook holds at most 64,000. */
    private static final class Styles {

        final CellStyle title;
        final CellStyle subtitle;
        final CellStyle header;
        final CellStyle text;
        final CellStyle integer;
        final CellStyle money;
        final CellStyle decimal;
        final CellStyle date;
        final CellStyle dateTime;

        Styles(Workbook workbook) {
            DataFormat formats = workbook.createDataFormat();
            Font large = workbook.createFont();
            large.setBold(true);
            large.setFontHeightInPoints((short) 14);
            Font italic = workbook.createFont();
            italic.setItalic(true);
            Font bold = workbook.createFont();
            bold.setBold(true);

            title = workbook.createCellStyle();
            title.setFont(large);
            subtitle = workbook.createCellStyle();
            subtitle.setFont(italic);
            header = workbook.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setWrapText(true);
            text = formatted(workbook, formats, "@");
            integer = formatted(workbook, formats, "#,##0");
            money = formatted(workbook, formats, "#,##0");
            decimal = formatted(workbook, formats, "0.000");
            date = formatted(workbook, formats, "dd/mm/yyyy");
            dateTime = formatted(workbook, formats, "hh:mm dd/mm/yyyy");
        }

        private static CellStyle formatted(Workbook workbook, DataFormat formats, String format) {
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(formats.getFormat(format));
            return style;
        }
    }
}
