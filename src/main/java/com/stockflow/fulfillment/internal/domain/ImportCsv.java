package com.stockflow.fulfillment.internal.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The table a file template of the system is (QD-17: CSV, UTF-8 with a byte-order mark so Excel opens the Vietnamese
 * headings as they are, no extra library), read and written the same way for every template: waybill numbers
 * ({@link WaybillImportFile}) and delivery outcomes ({@link DeliveryOutcomeFile}).
 *
 * <p>The first line is the heading and is not read as data. Columns are found by position, not by heading, so the
 * headings may be retyped in either language.</p>
 *
 * <p>The delimiter is found from the lines themselves, not the heading alone: Excel set to the Vietnamese region saves
 * with semicolons, and a template opened there keeps its comma heading in one cell, so the heading of such a file holds
 * as many commas as semicolons while every data line holds semicolons only. The delimiter is the one most lines hold;
 * on a tie the semicolon, then the tab, then the comma. Quoting is RFC 4180's.</p>
 *
 * <p>A file that is not such a table at all - not UTF-8, a NUL character, no line, too many lines, a quote left open -
 * is refused whole ({@link Malformed}). Everything about a line's content is its template's row check.</p>
 */
public final class ImportCsv {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final char BYTE_ORDER_MARK = (char) 0xFEFF;
    private static final char[] DELIMITERS = {';', '\t', ','};

    private ImportCsv() {
    }

    /** The file is not a table a template reads; the message says why, naming no cell's content. */
    public static final class Malformed extends RuntimeException {
        public Malformed(String message) {
            super(message);
        }
    }

    /**
     * A non-blank record: its cells as written.
     *
     * @param line the line's number in the file, the heading being line 1 - the row Excel shows it on
     */
    public record CsvLine(int line, List<String> cells) {

        /** The cell at {@code index}, trimmed; null when the line is shorter or the cell blank. */
        public String cell(int index) {
            if (index >= cells.size()) {
                return null;
            }
            String value = cells.get(index).strip();
            return value.isEmpty() ? null : value;
        }
    }

    /**
     * The non-blank records of {@code bytes}, the heading first.
     *
     * @param maxLines physical lines read at all, blank ones included: a file of nothing but line breaks is not read
     *                 to the end
     * @throws Malformed for a file that is empty, over {@code maxBytes}, not UTF-8, holding a NUL, with no heading line,
     *         over {@code maxLines} lines, or with a quote left open
     */
    public static List<CsvLine> read(byte[] bytes, int maxBytes, int maxLines) {
        if (bytes == null || bytes.length == 0) {
            throw new Malformed("The file is empty");
        }
        if (bytes.length > maxBytes) {
            throw new Malformed("The file is over " + maxBytes + " bytes");
        }
        String text = decode(bytes);
        if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
            text = text.substring(1);
        }
        if (text.indexOf('\0') >= 0) {
            throw new Malformed("The file holds a NUL character: save it from the template as CSV UTF-8");
        }
        List<CsvLine> records = records(text, delimiter(text), maxLines);
        if (records.isEmpty()) {
            throw new Malformed("The file has no heading line");
        }
        return records;
    }

    /** The byte-order mark, then each record, comma-separated and quoted where it must be, CRLF after each. */
    public static byte[] write(Collection<List<String>> records) {
        StringBuilder out = new StringBuilder();
        for (List<String> record : records) {
            out.append(String.join(",", record.stream().map(ImportCsv::quoted).toList())).append("\r\n");
        }
        byte[] text = out.toString().getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[BOM.length + text.length];
        System.arraycopy(BOM, 0, bytes, 0, BOM.length);
        System.arraycopy(text, 0, bytes, BOM.length, text.length);
        return bytes;
    }

    // ------------------------------------------------------------------ reading

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
    private static List<CsvLine> records(String text, char delimiter, int maxLines) {
        List<CsvLine> records = new ArrayList<>();
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
                if (line > maxLines) {
                    throw new Malformed("The file has more than " + maxLines + " lines");
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

    private static void keep(List<CsvLine> records, int line, List<String> cells) {
        if (cells.stream().anyMatch(c -> !c.isBlank())) {
            records.add(new CsvLine(line, cells));
        }
    }

    // ------------------------------------------------------------------ writing

    private static String quoted(String cell) {
        String value = cell == null ? "" : cell;
        return value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")
                ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
