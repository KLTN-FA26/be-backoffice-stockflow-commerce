package com.stockflow.fulfillment.internal.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The system's file template for importing waybill numbers (kltn-docs 09 §4.4 "Nhập hàng loạt từ file"; QD-17: CSV,
 * UTF-8 with a byte-order mark, read and written by {@link ImportCsv}).
 *
 * <p>Five columns in this order: the internal load code, the waybill number (both required), the vehicle plate, the
 * driver's name and phone (optional; left blank, the load keeps what it has). The first line is the heading and is not
 * read as data.</p>
 *
 * <p>A file that is not such a table at all - not UTF-8, a NUL character, no data line, more than {@value #MAX_ROWS}
 * lines, a quote left open - is refused whole ({@link ImportCsv.Malformed}). Everything about a line's content is the
 * row check's, line by line, so one bad line does not refuse the others (E18).</p>
 */
public final class WaybillImportFile {

    /** One import is one dispatcher's batch of bookings, not a data migration. */
    public static final int MAX_ROWS = 1_000;
    /** A thousand lines of five short columns fit in far less. */
    public static final int MAX_BYTES = 1024 * 1024;
    /** Physical lines read at all, blank ones included: a file of nothing but line breaks is not read to the end. */
    static final int MAX_LINES = MAX_ROWS * 10;

    private WaybillImportFile() {
    }

    /**
     * One data line of the file, as written (cells trimmed; a blank optional cell is null).
     *
     * @param line the line's number in the file, the heading being line 1 - the row Excel shows it on
     */
    public record Row(int line, String loadCode, String waybillNo, String vehiclePlate, String driverName,
                      String driverPhone) {
    }

    /**
     * The empty template: the byte-order mark and the heading line.
     *
     * @param headings the five headings in the reader's language (never read back: columns go by position)
     */
    public static byte[] template(List<String> headings) {
        if (headings == null || headings.size() != 5) {
            throw new IllegalArgumentException("The template has five headings");
        }
        return ImportCsv.write(List.of(headings));
    }

    /**
     * The data lines of {@code bytes}; blank lines are skipped.
     *
     * @throws ImportCsv.Malformed for a file over {@value #MAX_BYTES} bytes, not UTF-8, holding a NUL, with no heading
     *         or no data line, over {@value #MAX_ROWS} data lines, or with a quote left open
     */
    public static List<Row> parse(byte[] bytes) {
        List<ImportCsv.CsvLine> records = ImportCsv.read(bytes, MAX_BYTES, MAX_LINES);
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            ImportCsv.CsvLine record = records.get(i);
            rows.add(new Row(record.line(), record.cell(0), record.cell(1), record.cell(2), record.cell(3),
                    record.cell(4)));
        }
        if (rows.isEmpty()) {
            throw new ImportCsv.Malformed("The file has no line below its heading");
        }
        if (rows.size() > MAX_ROWS) {
            throw new ImportCsv.Malformed("The file has more than " + MAX_ROWS + " lines");
        }
        return rows;
    }

    /**
     * The heading and only the data lines numbered {@code lines}, as a template file of their own (3-3: the extract of
     * one warehouse's lines, kept with that warehouse's loads so no load shows another warehouse's lines).
     *
     * @throws ImportCsv.Malformed as {@link #parse}
     */
    public static byte[] extract(byte[] bytes, Set<Integer> lines) {
        List<ImportCsv.CsvLine> records = ImportCsv.read(bytes, MAX_BYTES, MAX_LINES);
        List<List<String>> kept = new ArrayList<>();
        kept.add(records.get(0).cells());
        for (int i = 1; i < records.size(); i++) {
            if (lines.contains(records.get(i).line())) {
                kept.add(records.get(i).cells());
            }
        }
        return ImportCsv.write(kept);
    }
}
