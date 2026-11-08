package com.stockflow.fulfillment.internal.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The system's file template for importing waybill numbers (kltn-docs 09 §4.4 "Nhập hàng loạt từ file"; QD-17: CSV,
 * UTF-8 with a byte-order mark so Excel opens the Vietnamese headings as they are, no extra library).
 *
 * <p>Five columns in this order: the internal load code, the waybill number (both required), the vehicle plate, the
 * driver's name and phone (optional; left blank, the load keeps what it has). The first line is the heading and is not
 * read as data. Columns are found by position, not by heading, so the headings may be retyped in either language. The
 * delimiter is the heading's: a comma, or the semicolon or tab a spreadsheet set to another locale saves with.
 * Quoting is RFC 4180's.</p>
 *
 * <p>A file that is not such a table at all - not UTF-8, no data line, more than {@value #MAX_ROWS} lines, a quote
 * left open - is refused whole ({@link Malformed}). Everything about a line's content is the row check's, line by
 * line, so one bad line does not refuse the others (E18).</p>
 */
public final class WaybillImportFile {

    /** One import is one dispatcher's batch of bookings, not a data migration. */
    public static final int MAX_ROWS = 1_000;
    /** A thousand lines of five short columns fit in far less. */
    public static final int MAX_BYTES = 1024 * 1024;

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private WaybillImportFile() {
    }

    /**
     * One data line of the file, as written (cells trimmed; a blank optional cell is null).
     *
     * @param line the line's number in the file, the heading being line 1 - what the dispatcher finds in Excel
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

    /**
     * The empty template: the byte-order mark and the heading line.
     *
     * @param headings the five headings in the reader's language (never read back: columns go by position)
     */
    public static byte[] template(List<String> headings) {
        if (headings == null || headings.size() != 5) {
            throw new IllegalArgumentException("The template has five headings");
        }
        byte[] heading = (String.join(",", headings.stream().map(WaybillImportFile::quoted).toList()) + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[BOM.length + heading.length];
        System.arraycopy(BOM, 0, out, 0, BOM.length);
        System.arraycopy(heading, 0, out, BOM.length, heading.length);
        return out;
    }

    /**
     * The data lines of {@code bytes}; blank lines are skipped.
     *
     * @throws Malformed for a file over {@value #MAX_BYTES} bytes, not UTF-8, with no heading or no data line, over
     *         {@value #MAX_ROWS} data lines, or with a quote left open
     */
    public static List<Row> parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new Malformed("The file is empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new Malformed("The file is over " + MAX_BYTES + " bytes");
        }
        String text = decode(bytes);
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        List<List<String>> records = records(text, delimiter(text));
        if (records.isEmpty()) {
            throw new Malformed("The file has no heading line");
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> cells = records.get(i);
            if (cells.stream().allMatch(c -> c == null || c.isBlank())) {
                continue;
            }
            if (rows.size() == MAX_ROWS) {
                throw new Malformed("The file has more than " + MAX_ROWS + " lines");
            }
            rows.add(new Row(i + 1, cell(cells, 0), cell(cells, 1), cell(cells, 2), cell(cells, 3), cell(cells, 4)));
        }
        if (rows.isEmpty()) {
            throw new Malformed("The file has no line below its heading");
        }
        return rows;
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notUtf8) {
            throw new Malformed("The file is not UTF-8 text: save it from the template as CSV UTF-8");
        }
    }

    /** The heading line's delimiter: the most frequent of comma, semicolon and tab outside quotes. */
    private static char delimiter(String text) {
        int commas = 0;
        int semicolons = 0;
        int tabs = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (c == '\n' || c == '\r')) {
                break;
            } else if (!quoted) {
                commas += c == ',' ? 1 : 0;
                semicolons += c == ';' ? 1 : 0;
                tabs += c == '\t' ? 1 : 0;
            }
        }
        if (semicolons > commas && semicolons >= tabs) {
            return ';';
        }
        return tabs > commas ? '\t' : ',';
    }

    /** RFC 4180 records: quoted cells may hold the delimiter, line breaks and doubled quotes. */
    private static List<List<String>> records(String text, char delimiter) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
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
                records.add(record);
                record = new ArrayList<>();
                any = false;
            } else {
                cell.append(c);
            }
        }
        if (quoted) {
            throw new Malformed("A quote is left open in the file");
        }
        if (any) {
            record.add(cell.toString());
            records.add(record);
        }
        return records;
    }

    private static String quoted(String heading) {
        return heading.contains(",") || heading.contains("\"")
                ? "\"" + heading.replace("\"", "\"\"") + "\"" : heading;
    }

    private static String cell(List<String> cells, int index) {
        if (index >= cells.size()) {
            return null;
        }
        String value = cells.get(index).strip();
        return value.isEmpty() ? null : value;
    }
}
