package com.stockflow.fulfillment.internal.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The system's file template for importing waybill numbers (kltn-docs 09 §4.4 "Nhập hàng loạt từ file"; QD-17: CSV,
 * UTF-8 with a byte-order mark so Excel opens the Vietnamese headings as they are, no extra library).
 *
 * <p>Five columns in this order: the internal load code, the waybill number (both required), the vehicle plate, the
 * driver's name and phone (optional; left blank, the load keeps what it has). The first line is the heading and is not
 * read as data. Columns are found by position, not by heading, so the headings may be retyped in either language.</p>
 *
 * <p>The delimiter is found from the lines themselves, not the heading alone: Excel set to the Vietnamese region saves
 * with semicolons, and a template opened there keeps its comma heading in one cell, so the heading of such a file holds
 * as many commas as semicolons while every data line holds semicolons only. The delimiter is the one most lines hold;
 * on a tie the semicolon, then the tab, then the comma. Quoting is RFC 4180's.</p>
 *
 * <p>A file that is not such a table at all - not UTF-8, a NUL character, no data line, more than {@value #MAX_ROWS}
 * lines, a quote left open - is refused whole ({@link Malformed}). Everything about a line's content is the row check's,
 * line by line, so one bad line does not refuse the others (E18).</p>
 */
public final class WaybillImportFile {

    /** One import is one dispatcher's batch of bookings, not a data migration. */
    public static final int MAX_ROWS = 1_000;
    /** A thousand lines of five short columns fit in far less. */
    public static final int MAX_BYTES = 1024 * 1024;
    /** Physical lines read at all, blank ones included: a file of nothing but line breaks is not read to the end. */
    static final int MAX_LINES = MAX_ROWS * 10;

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final char BYTE_ORDER_MARK = (char) 0xFEFF;
    private static final char[] DELIMITERS = {';', '\t', ','};

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

    /** The file is not a table this template reads; the message says why, naming no cell's content. */
    public static final class Malformed extends RuntimeException {
        public Malformed(String message) {
            super(message);
        }
    }

    /** A non-blank record: its line number and its cells as written. */
    private record Record(int line, List<String> cells) { }

    /**
     * The empty template: the byte-order mark and the heading line.
     *
     * @param headings the five headings in the reader's language (never read back: columns go by position)
     */
    public static byte[] template(List<String> headings) {
        if (headings == null || headings.size() != 5) {
            throw new IllegalArgumentException("The template has five headings");
        }
        return csv(List.of(headings));
    }

    /**
     * The data lines of {@code bytes}; blank lines are skipped.
     *
     * @throws Malformed for a file over {@value #MAX_BYTES} bytes, not UTF-8, holding a NUL, with no heading or no data
     *         line, over {@value #MAX_ROWS} data lines, or with a quote left open
     */
    public static List<Row> parse(byte[] bytes) {
        List<Record> records = read(bytes);
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            Record record = records.get(i);
            List<String> cells = record.cells();
            rows.add(new Row(record.line(), cell(cells, 0), cell(cells, 1), cell(cells, 2), cell(cells, 3),
                    cell(cells, 4)));
        }
        if (rows.isEmpty()) {
            throw new Malformed("The file has no line below its heading");
        }
        if (rows.size() > MAX_ROWS) {
            throw new Malformed("The file has more than " + MAX_ROWS + " lines");
        }
        return rows;
    }

    /**
     * The heading and only the data lines numbered {@code lines}, as a template file of their own (3-3: the extract of
     * one warehouse's lines, kept with that warehouse's loads so no load shows another warehouse's lines).
     *
     * @throws Malformed as {@link #parse}
     */
    public static byte[] extract(byte[] bytes, Set<Integer> lines) {
        List<Record> records = read(bytes);
        List<List<String>> kept = new ArrayList<>();
        kept.add(records.get(0).cells());
        for (int i = 1; i < records.size(); i++) {
            if (lines.contains(records.get(i).line())) {
                kept.add(records.get(i).cells());
            }
        }
        return csv(kept);
    }

    // ------------------------------------------------------------------ reading

    private static List<Record> read(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new Malformed("The file is empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new Malformed("The file is over " + MAX_BYTES + " bytes");
        }
        String text = decode(bytes);
        if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
            text = text.substring(1);
        }
        if (text.indexOf('\0') >= 0) {
            throw new Malformed("The file holds a NUL character: save it from the template as CSV UTF-8");
        }
        List<Record> records = records(text, delimiter(text));
        if (records.isEmpty()) {
            throw new Malformed("The file has no heading line");
        }
        return records;
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notUtf8) {
            throw new Malformed("The file is not UTF-8 text: save it from the template as CSV UTF-8");
        }
    }

    /**
     * The delimiter most of the first lines hold outside quotes; on a tie the semicolon, then the tab, then the comma.
     */
    private static char delimiter(String text) {
        int[] lines = new int[DELIMITERS.length];
        boolean[] seen = new boolean[DELIMITERS.length];
        boolean quoted = false;
        int read = 0;
        for (int i = 0; i < text.length() && read < 20; i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (c == '\n' || c == '\r')) {
                for (int d = 0; d < DELIMITERS.length; d++) {
                    lines[d] += seen[d] ? 1 : 0;
                    seen[d] = false;
                }
                read++;
            } else if (!quoted) {
                for (int d = 0; d < DELIMITERS.length; d++) {
                    seen[d] |= c == DELIMITERS[d];
                }
            }
        }
        for (int d = 0; d < DELIMITERS.length; d++) {
            lines[d] += seen[d] ? 1 : 0;
        }
        int best = DELIMITERS.length - 1;
        for (int d = DELIMITERS.length - 1; d >= 0; d--) {
            if (lines[d] >= lines[best]) {
                best = d;
            }
        }
        return DELIMITERS[best];
    }

    /** RFC 4180 records, the blank ones dropped as they are read; quoted cells may hold delimiters and line breaks. */
    private static List<Record> records(String text, char delimiter) {
        List<Record> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        int line = 1;
        int start = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            any = true;
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n' || (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n'))) {
                        line++;
                    }
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == delimiter) {
                record.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                record.add(cell.toString());
                cell.setLength(0);
                keep(records, start, record);
                record = new ArrayList<>();
                any = false;
                line++;
                start = line;
                if (line > MAX_LINES) {
                    throw new Malformed("The file has more than " + MAX_LINES + " lines");
                }
            } else {
                cell.append(c);
            }
        }
        if (quoted) {
            throw new Malformed("A quote is left open in the file");
        }
        if (any) {
            record.add(cell.toString());
            keep(records, start, record);
        }
        return records;
    }

    private static void keep(List<Record> records, int line, List<String> cells) {
        if (cells.stream().anyMatch(c -> !c.isBlank())) {
            records.add(new Record(line, cells));
        }
    }

    private static String cell(List<String> cells, int index) {
        if (index >= cells.size()) {
            return null;
        }
        String value = cells.get(index).strip();
        return value.isEmpty() ? null : value;
    }

    // ------------------------------------------------------------------ writing

    /** BOM, then each record, comma-separated and quoted where it must be, CRLF after each. */
    private static byte[] csv(Collection<List<String>> records) {
        StringBuilder out = new StringBuilder();
        for (List<String> record : records) {
            out.append(String.join(",", record.stream().map(WaybillImportFile::quoted).toList())).append("\r\n");
        }
        byte[] text = out.toString().getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[BOM.length + text.length];
        System.arraycopy(BOM, 0, bytes, 0, BOM.length);
        System.arraycopy(text, 0, bytes, BOM.length, text.length);
        return bytes;
    }

    private static String quoted(String cell) {
        String value = cell == null ? "" : cell;
        return value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")
                ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
