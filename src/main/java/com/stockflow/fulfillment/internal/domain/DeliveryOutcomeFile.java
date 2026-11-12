package com.stockflow.fulfillment.internal.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * The system's file template for recording delivery outcomes a carrier sent as a list (WF-05 fix plan item 5; kltn-docs
 * 09 §4.3; the same CSV as the waybill import's, read and written by {@link ImportCsv}).
 *
 * <p>Seven columns in this order: the waybill number, the outcome ({@code DELIVERED} or {@code DELIVERY_FAILED}), the
 * reason of a failed delivery, when it happened (the first, second and fourth are required; the third with a failed
 * delivery), who received the goods, a note, and the shipment's code. A line without a shipment code is about every
 * shipment that left on the vehicle (BR-26); one with it is about that shipment only. The first line is the heading and
 * is not read as data.</p>
 *
 * <p>A file that is not such a table at all is refused whole ({@link ImportCsv.Malformed}); everything about a line's
 * content is {@link DeliveryOutcomeCheck}'s, line by line, so one bad line does not refuse the others.</p>
 */
public final class DeliveryOutcomeFile {

    /** One import is one carrier's list for a day or two, not a data migration. */
    public static final int MAX_ROWS = 1_000;
    /** A thousand lines of seven short columns fit in far less. */
    public static final int MAX_BYTES = 1024 * 1024;
    /** Physical lines read at all, blank ones included: a file of nothing but line breaks is not read to the end. */
    static final int MAX_LINES = MAX_ROWS * 10;
    /** How many headings the template has. */
    public static final int COLUMNS = 7;

    private DeliveryOutcomeFile() {
    }

    /**
     * One data line of the file, as written (cells trimmed; a blank cell is null).
     *
     * @param line the line's number in the file, the heading being line 1 - the row Excel shows it on
     */
    public record Row(int line, String waybillNo, String outcome, String failureReason, String reportedAt,
                      String recipient, String note, String shipmentCode) {
    }

    /**
     * The empty template: the byte-order mark and the heading line.
     *
     * @param headings the seven headings in the reader's language (never read back: columns go by position)
     */
    public static byte[] template(List<String> headings) {
        if (headings == null || headings.size() != COLUMNS) {
            throw new IllegalArgumentException("The template has " + COLUMNS + " headings");
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
        List<ImportCsv.Record> records = ImportCsv.read(bytes, MAX_BYTES, MAX_LINES);
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            ImportCsv.Record r = records.get(i);
            rows.add(new Row(r.line(), r.cell(0), r.cell(1), r.cell(2), r.cell(3), r.cell(4), r.cell(5), r.cell(6)));
        }
        if (rows.isEmpty()) {
            throw new ImportCsv.Malformed("The file has no line below its heading");
        }
        if (rows.size() > MAX_ROWS) {
            throw new ImportCsv.Malformed("The file has more than " + MAX_ROWS + " lines");
        }
        return rows;
    }
}
