package com.stockflow.fulfillment.internal.domain;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The same table as {@link ImportCsv}, read from and written to an Excel workbook (.xlsx): the first sheet, the first
 * row the heading (not read as data), columns by position, a row's number being the number Excel shows it on - so a
 * workbook and the CSV of the same data give the same lines and the same problems.
 *
 * <p>An untrusted file, so before Apache POI sees it the ZIP container is counted: at most {@value #MAX_ENTRIES}
 * entries and {@value #MAX_UNCOMPRESSED_BYTES} bytes once inflated (a zip bomb is refused by what it holds, not by what
 * it weighs). POI's own XML readers do not resolve external entities, no formula is evaluated and no external link is
 * followed: a formula cell is read as the value Excel last stored with it.</p>
 *
 * <p>A cell is read as the text a person sees in it ({@link DataFormatter}), with one exception that protects the
 * numbers: a numeric cell whose formatted text is not plain digits - a waybill number {@code 123456789012} that a
 * "Scientific" format shows as {@code 1.23457E+11}, one with thousands separators - is read as the stored number,
 * written out in full with no exponent and no {@code .0}. A phone number typed as a number has lost its leading zero in
 * the file itself; it is read as the cell shows it (a cell formatted {@code 0000000000} keeps its zero), the file being
 * the dispatcher's, and nothing here adds a digit that is not in it.</p>
 *
 * <p>A file that is not such a workbook - encrypted, an old .xls, corrupt, no sheet, no heading, over the byte or line
 * limits - is refused whole ({@link ImportCsv.Malformed}); the message never names a cell's content.</p>
 */
public final class ImportXlsx {

    /** A workbook is a handful of parts; an archive of thousands of entries is not one. */
    static final int MAX_ENTRIES = 200;
    /** What the parts of a workbook of a thousand short lines come to is far less; this bounds what POI may load. */
    static final long MAX_UNCOMPRESSED_BYTES = 8L * 1024 * 1024;
    /** A table of five columns; more than this are not read. */
    static final int MAX_COLUMNS = 64;
    /** Excel's cell text limit. */
    private static final int MAX_CELL_TEXT = 32_767;

    private static final byte[] ZIP_LOCAL = {'P', 'K', 3, 4};
    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A,
            (byte) 0xE1};

    private ImportXlsx() {
    }

    /** The bytes open as a ZIP container, which is what an .xlsx is (an encrypted or old Excel file is not one). */
    public static boolean looksLike(byte[] bytes) {
        return startsWith(bytes, ZIP_LOCAL);
    }

    /** An Excel file of any kind (also an encrypted workbook or an old .xls, which {@link #read} refuses by name). */
    public static boolean claims(byte[] bytes) {
        return looksLike(bytes) || startsWith(bytes, OLE2);
    }

    /**
     * The non-blank rows of the first sheet, the heading first.
     *
     * @param maxLines rows read at all: a row beyond it with anything in it refuses the file (trailing rows holding
     *                 only formatting are not read)
     * @throws ImportCsv.Malformed for a file that is empty, over {@code maxBytes}, encrypted, an old .xls, not an
     *         .xlsx, unreadable, over the inflate limits, without a sheet or a heading row, or over {@code maxLines}
     */
    public static List<ImportCsv.CsvLine> read(byte[] bytes, int maxBytes, int maxLines) {
        if (bytes == null || bytes.length == 0) {
            throw new ImportCsv.Malformed("The file is empty");
        }
        if (bytes.length > maxBytes) {
            throw new ImportCsv.Malformed("The file is over " + maxBytes + " bytes");
        }
        if (startsWith(bytes, OLE2)) {
            throw new ImportCsv.Malformed("The file is encrypted or an old .xls: save it from the template as .xlsx "
                    + "or CSV UTF-8");
        }
        if (!looksLike(bytes)) {
            throw new ImportCsv.Malformed("The file is not an .xlsx workbook");
        }
        checkContainer(bytes);
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new ImportCsv.Malformed("The workbook has no sheet");
            }
            return rows(workbook.getSheetAt(0), maxLines);
        } catch (ImportCsv.Malformed malformed) {
            throw malformed;
        } catch (IOException | RuntimeException unreadable) {
            // POI's messages can quote what is in the file; this one does not.
            throw new ImportCsv.Malformed("The file cannot be read as an .xlsx workbook");
        }
    }

    /**
     * One sheet holding each record as text cells, the first record the heading. Not {@code common.export.XlsxTable}:
     * that writes report layouts (title lines, a blank row, autofilter, typed columns), whereas this file is re-read by
     * {@link #read} as the import template - heading in row 1, every cell text, so a row keeps its number and a waybill
     * number stays text.
     */
    public static byte[] write(Collection<List<String>> records) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            int rowNumber = 0;
            for (List<String> record : records) {
                Row row = sheet.createRow(rowNumber++);
                for (int c = 0; c < record.size(); c++) {
                    String value = record.get(c) == null ? "" : record.get(c);
                    row.createCell(c, CellType.STRING).setCellValue(value.length() > MAX_CELL_TEXT
                            ? value.substring(0, MAX_CELL_TEXT) : value);
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    // ------------------------------------------------------------------ reading

    /** The archive counted entry by entry, inflating each only as far as the limit allows. */
    private static void checkContainer(byte[] bytes) {
        long inflated = 0;
        int entries = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new ImportCsv.Malformed("The file holds too many parts to be an .xlsx workbook");
                }
                int n;
                while ((n = zip.read(buffer)) > 0) {
                    inflated += n;
                    if (inflated > MAX_UNCOMPRESSED_BYTES) {
                        throw new ImportCsv.Malformed("The file is over " + MAX_UNCOMPRESSED_BYTES
                                + " bytes once unpacked");
                    }
                }
            }
        } catch (ImportCsv.Malformed refused) {
            throw refused;
        } catch (IOException | RuntimeException broken) {
            throw new ImportCsv.Malformed("The file cannot be read as an .xlsx workbook");
        }
        if (entries == 0) {
            throw new ImportCsv.Malformed("The file cannot be read as an .xlsx workbook");
        }
    }

    private static List<ImportCsv.CsvLine> rows(Sheet sheet, int maxLines) {
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        List<ImportCsv.CsvLine> records = new ArrayList<>();
        for (Row row : sheet) {
            int line = row.getRowNum() + 1;
            List<String> cells = cells(row, formatter);
            boolean blank = cells.stream().allMatch(String::isBlank);
            if (blank) {
                continue;
            }
            if (line > maxLines) {
                throw new ImportCsv.Malformed("The file has more than " + maxLines + " lines");
            }
            records.add(new ImportCsv.CsvLine(line, cells));
        }
        if (records.isEmpty()) {
            throw new ImportCsv.Malformed("The file has no heading line");
        }
        return records;
    }

    private static List<String> cells(Row row, DataFormatter formatter) {
        int last = Math.min(Math.max(row.getLastCellNum(), 0), MAX_COLUMNS);
        List<String> cells = new ArrayList<>(last);
        for (int c = 0; c < last; c++) {
            cells.add(text(row.getCell(c), formatter));
        }
        return cells;
    }

    /**
     * What the cell holds as text (see the class comment); a blank cell is the empty string. A formula cell is read from
     * the value stored with it - {@link DataFormatter} would give the formula's own text - and a formula never
     * calculated (no stored value) is blank.
     */
    static String text(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        boolean formula = cell.getCellType() == CellType.FORMULA;
        if (formula && cell instanceof XSSFCell xssf && !xssf.getCTCell().isSetV()) {
            return "";
        }
        CellType type = formula ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> cell.getBooleanCellValue() ? "TRUE" : "FALSE";
            case NUMERIC -> number(cell, formatter);
            default -> "";
        };
    }

    private static String number(Cell cell, DataFormatter formatter) {
        double value = cell.getNumericCellValue();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "";
        }
        CellStyle style = cell.getCellStyle();
        String shown = formatter.formatRawCellContents(value, style.getDataFormat(), style.getDataFormatString());
        if (DateUtil.isCellDateFormatted(cell) || isDigits(shown)) {
            return shown;
        }
        BigDecimal number = BigDecimal.valueOf(value).stripTrailingZeros();
        return (number.scale() < 0 ? number.setScale(0) : number).toPlainString();
    }

    private static boolean isDigits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] bytes, byte[] signature) {
        if (bytes == null || bytes.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (bytes[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
