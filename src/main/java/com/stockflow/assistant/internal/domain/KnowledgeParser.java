package com.stockflow.assistant.internal.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cuts the business-design documents into chunks (design §6.1). One parser per document shape, because each
 * document's natural unit is different: a rule is a table row, a term is a table row with a bold first cell, a state
 * machine is a second-level section, cut further at its third-level headings and to a passage's size, and a process
 * flow is a second-level section named by its code, cut the same way.
 *
 * <p>The cuts follow the documents' own structure rather than a fixed window of characters, so a chunk is always a
 * whole rule and its anchor is the id people already use.</p>
 */
public final class KnowledgeParser {

    /**
     * The longest state-machine passage. A model is shown whole passages; a section of 17,000 characters (the order)
     * cut to its first 1,500 hid the cancellation table and invited the model to fill the gap itself.
     */
    public static final int MAX_PASSAGE = 1400;

    private static final Pattern RULE_ROW = Pattern.compile("^\\|\\s*(BR-[A-Z]+-\\d+[a-z]?)\\s*\\|(.*)$");
    private static final Pattern TERM_ROW = Pattern.compile("^\\|\\s*\\*\\*(.+?)\\*\\*\\s*\\|(.*)$");
    private static final Pattern SECTION = Pattern.compile("^##\\s+(?!#)(.+)$");
    private static final Pattern SUBSECTION = Pattern.compile("^###\\s+(.+)$");
    private static final Pattern TOP = Pattern.compile("^#\\s+(?!#)(.+)$");

    /** A flow's heading: its code in backticks, then its name ({@code `F-PO` Đơn mua hàng gửi nhà cung cấp}). */
    private static final Pattern FLOW_CODE = Pattern.compile("^`([A-Z]+-[A-Z]+)`\\s+(.+)$");

    /**
     * The platform flows that describe how the services of the distributed design ADR-0005 replaced registered their
     * permissions and fed their read models: nothing the staff do. The two others, an order placed for a customer
     * from a chat and a notification sent again by hand, are staff work and stay.
     */
    private static final Set<String> SYSTEM_FLOWS = Set.of("P-PERM", "P-RPT");

    /**
     * A note on what was built ({@code > · Đã cài (SCRUM-146): …}): API paths, error codes, and the limits of the
     * build — one of them how a blind counter could still read the book quantity elsewhere. For the people building
     * it, not for every user of the assistant.
     */
    private static final Pattern BUILD_NOTE = Pattern.compile("^>\\s*·\\s*Đã cài\\b.*");

    /** A note's first line: the next note after a build note ends it. */
    private static final Pattern NOTE_START = Pattern.compile("^>\\s*[·⚠★]");

    /** What a step writes for the system's own bookkeeping, never what happens: dropped from a kept cell. */
    private static final Pattern SYSTEM_WRITES = Pattern.compile("\\s*[+~−-]\\s*(?:outbox_event|processed_event)\\s*,"
            + "|,?\\s*[+~−-]\\s*(?:outbox_event|processed_event)");

    /**
     * Columns of a flow's tables that name what the system writes rather than what happens: the tables and rows a
     * step writes, the events it sends, the work-breakdown reference. Noise to the staff who ask, as a rule's
     * enforcement point is; and table names are the schema's, not the business's.
     */
    private static final Set<String> SYSTEM_COLUMNS = Set.of("ghi vào", "data written", "event out", "wbs");
    private static final int MAX_ANCHOR = 160;

    private KnowledgeParser() {
    }

    /**
     * {@code 05-business-rules.md}: every {@code | BR-… |} row. The rule text is the second cell; the enforcement
     * point and the WBS reference are dropped — class names are noise to the staff who ask. The group is the
     * second-level heading above the row ("Product", "Procurement", ...).
     */
    public static List<KnowledgeChunk> rules(String source, String markdown) {
        Map<String, KnowledgeChunk> chunks = new LinkedHashMap<>();
        String group = null;
        for (String line : markdown.split("\\R")) {
            Matcher section = SECTION.matcher(line);
            if (section.matches()) {
                group = section.group(1).split("\\s+[—-]\\s+", 2)[0].strip();
                continue;
            }
            Matcher row = RULE_ROW.matcher(line);
            if (!row.matches()) {
                continue;
            }
            List<String> cells = cells(row.group(2));
            if (cells.isEmpty() || cells.getFirst().isBlank()) {
                continue;
            }
            String id = row.group(1);
            String title = group == null ? id : id + " — " + group;
            // A rule id appears once; a later row with the same id (a revision table) adds to it.
            KnowledgeChunk earlier = chunks.get(id);
            String body = earlier == null ? cells.getFirst() : earlier.body() + "\n" + cells.getFirst();
            chunks.put(id, new KnowledgeChunk(source, id, KnowledgeChunk.Kind.RULE, title, body));
        }
        return List.copyOf(chunks.values());
    }

    /** {@code 01-ubiquitous-language.md}: every table row whose first cell is a bold term. */
    public static List<KnowledgeChunk> terms(String source, String markdown) {
        Map<String, KnowledgeChunk> chunks = new LinkedHashMap<>();
        for (String line : markdown.split("\\R")) {
            Matcher row = TERM_ROW.matcher(line);
            if (!row.matches()) {
                continue;
            }
            List<String> cells = cells(row.group(2));
            if (cells.isEmpty() || cells.getFirst().isBlank()) {
                continue;
            }
            String term = row.group(1).strip();
            String body = cells.size() > 1 && !cells.get(1).isBlank()
                    ? cells.getFirst() + " Not to be confused with: " + cells.get(1) + "."
                    : cells.getFirst();
            chunks.putIfAbsent(term, new KnowledgeChunk(source, term, KnowledgeChunk.Kind.TERM, term, body));
        }
        return List.copyOf(chunks.values());
    }

    /**
     * {@code 03-state-machines.md}: every second-level section, cut at its third-level headings, each piece cut again
     * into passages of at most {@value #MAX_PASSAGE} characters at paragraph, table-row or line boundaries. A table cut
     * in two repeats its header row, so every passage reads on its own.
     *
     * <p>Anchors: the section heading for its first passage ({@code 9. Order — order-service}), then
     * {@code heading (2)}, {@code heading (3)}; a subsection adds {@code › its heading}. Unique within the source,
     * at most 160 characters.</p>
     */
    public static List<KnowledgeChunk> sections(String source, String markdown) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        Set<String> anchors = new HashSet<>();
        String heading = null;
        String subheading = null;
        List<String> body = new ArrayList<>();
        for (String line : markdown.split("\\R")) {
            Matcher section = SECTION.matcher(line);
            Matcher subsection = SUBSECTION.matcher(line);
            if (section.matches()) {
                addPassages(chunks, anchors, source, heading, subheading, body);
                heading = section.group(1).replace("`", "").strip();
                subheading = null;
                body.clear();
            } else if (heading != null && subsection.matches()) {
                addPassages(chunks, anchors, source, heading, subheading, body);
                subheading = subsection.group(1).replace("`", "").strip();
                body.clear();
            } else if (heading != null) {
                body.add(line);
            }
        }
        addPassages(chunks, anchors, source, heading, subheading, body);
        return List.copyOf(chunks);
    }

    private static void addPassages(List<KnowledgeChunk> chunks, Set<String> anchors, String source, String heading,
                                    String subheading, List<String> lines) {
        if (heading == null) {
            return;
        }
        String base = subheading == null ? heading : heading + " › " + subheading;
        addPassages(chunks, anchors, source, KnowledgeChunk.Kind.STATE_MACHINE, base, base, lines);
    }

    /** {@code lines} as passages of {@code kind}: {@code anchor}, {@code anchor (2)}, ..., titled {@code title (i/n)}. */
    private static void addPassages(List<KnowledgeChunk> chunks, Set<String> anchors, String source,
                                    KnowledgeChunk.Kind kind, String anchorBase, String titleBase, List<String> lines) {
        List<String> passages = passages(lines);
        for (int i = 0; i < passages.size(); i++) {
            String suffix = i == 0 ? "" : " (" + (i + 1) + ")";
            String anchor = unique(anchors, anchorBase, suffix);
            String title = passages.size() == 1 ? titleBase
                    : titleBase + " (" + (i + 1) + "/" + passages.size() + ")";
            chunks.add(new KnowledgeChunk(source, anchor, kind, title, passages.get(i)));
        }
    }

    /**
     * {@code 04c-all-flows.md}: every second-level section named by a flow's code, cut at its third-level headings and
     * into passages as a state machine is. The anchor is the code ({@code F-PO}, {@code X-SHORT}), the title the code,
     * the name and the group above it ({@code F-PO — Đơn mua hàng gửi nhà cung cấp (Nhập hàng)}).
     *
     * <p>Left out: the diagrams (Mermaid source, unreadable as text), the work-breakdown lines, the columns that name
     * tables and events ({@link #SYSTEM_COLUMNS}), the notes on what was built ({@link #BUILD_NOTE}), sections with no
     * flow code (the table of contents, the storage conventions), and {@link #SYSTEM_FLOWS}.</p>
     */
    public static List<KnowledgeChunk> flows(String source, String markdown) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        Set<String> anchors = new HashSet<>();
        String group = null;
        boolean titled = false;
        String code = null;
        String title = null;
        String subheading = null;
        List<String> body = new ArrayList<>();
        boolean diagram = false;
        boolean buildNote = false;
        for (String line : markdown.split("\\R")) {
            String stripped = line.strip();
            if (diagram) {
                diagram = !stripped.startsWith("```");
                continue;
            }
            if (buildNote && stripped.startsWith(">") && !NOTE_START.matcher(stripped).find()) {
                continue;
            }
            buildNote = BUILD_NOTE.matcher(stripped).matches();
            if (buildNote) {
                continue;
            }
            if (stripped.startsWith("```mermaid")) {
                diagram = true;
                continue;
            }
            Matcher top = TOP.matcher(line);
            Matcher section = SECTION.matcher(line);
            Matcher subsection = SUBSECTION.matcher(line);
            if (top.matches()) {
                addFlow(chunks, anchors, source, code, title, subheading, body);
                code = null;
                // The first top-level heading is the document's title; the later ones group its flows.
                group = titled ? top.group(1).strip() : null;
                titled = true;
            } else if (section.matches()) {
                addFlow(chunks, anchors, source, code, title, subheading, body);
                String heading = section.group(1).strip();
                Matcher coded = FLOW_CODE.matcher(heading);
                code = coded.matches() && !SYSTEM_FLOWS.contains(coded.group(1)) ? coded.group(1) : null;
                String name = code == null ? null : coded.group(2);
                title = code == null ? null
                        : code + " — " + name.replace("`", "").strip() + (group == null ? "" : " (" + group + ")");
                subheading = null;
            } else if (code != null && subsection.matches()) {
                addFlow(chunks, anchors, source, code, title, subheading, body);
                subheading = subsection.group(1).replace("`", "").strip();
            } else if (code != null && !stripped.equals("---") && !stripped.matches("\\*WBS[^*]*\\*")) {
                body.add(line);
            }
        }
        addFlow(chunks, anchors, source, code, title, subheading, body);
        return List.copyOf(chunks);
    }

    private static void addFlow(List<KnowledgeChunk> chunks, Set<String> anchors, String source, String code,
                                String title, String subheading, List<String> body) {
        if (code != null && !String.join("", body).isBlank()) {
            String anchor = subheading == null ? code : code + " › " + subheading;
            String titled = subheading == null ? title : title + " › " + subheading;
            addPassages(chunks, anchors, source, KnowledgeChunk.Kind.FLOW, anchor, titled, withoutSystemColumns(body));
        }
        body.clear();
    }

    /**
     * The lines with every table's {@link #SYSTEM_COLUMNS} removed, and the system's own bookkeeping
     * ({@link #SYSTEM_WRITES}) out of the cells kept. A row with more cells than its header — a result written
     * {@code QC_PASSED | QC_REJECTED} inside a cell — has the extra ones joined back into its last cell. A step whose
     * kept cells then say nothing beyond its number and module keeps what it writes, cleaned the same way: the step is
     * that write.
     */
    static List<String> withoutSystemColumns(List<String> lines) {
        List<String> kept = new ArrayList<>(lines.size());
        List<Boolean> keep = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean row = line.strip().startsWith("|");
            if (!row) {
                keep = null;
                kept.add(line);
                continue;
            }
            List<String> cells = rowCells(line);
            if (keep == null) {
                boolean header = i + 1 < lines.size() && lines.get(i + 1).strip().matches("\\|[\\s:|-]+\\|?");
                if (!header) {
                    kept.add(line);
                    continue;
                }
                keep = new ArrayList<>();
                for (String cell : cells) {
                    keep.add(!SYSTEM_COLUMNS.contains(cell.replace("*", "").replace("`", "").strip()
                            .toLowerCase(java.util.Locale.ROOT)));
                }
            }
            if (keep.size() < cells.size() && !keep.isEmpty()) {
                List<String> joined = new ArrayList<>(cells.subList(0, keep.size() - 1));
                joined.add(String.join(" | ", cells.subList(keep.size() - 1, cells.size())));
                cells = joined;
            }
            List<String> out = new ArrayList<>();
            List<String> dropped = new ArrayList<>();
            for (int c = 0; c < cells.size(); c++) {
                String cell = SYSTEM_WRITES.matcher(cells.get(c)).replaceAll("").strip();
                if (c >= keep.size() || keep.get(c)) {
                    out.add(cell);
                } else {
                    dropped.add(cell);
                }
            }
            // Only a step table (number, module, result) can have a step that says nothing once cleaned.
            boolean said = out.size() <= 2 || out.subList(2, out.size()).stream().anyMatch(cell -> !cell.isBlank()
                    && !cell.matches("[`~+−-]*"));
            if (!said && !out.isEmpty() && dropped.stream().anyMatch(cell -> !cell.isBlank())) {
                out.set(out.size() - 1, String.join(" ", dropped).strip());
            }
            kept.add("| " + String.join(" | ", out) + " |");
        }
        return kept;
    }

    /** Every cell of a table row, trimmed: the text between its first and last pipe, split at the others. */
    private static List<String> rowCells(String line) {
        String inner = line.strip();
        inner = inner.substring(1, inner.endsWith("|") && inner.length() > 1 ? inner.length() - 1 : inner.length());
        List<String> cells = new ArrayList<>();
        for (String cell : inner.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    private static String unique(Set<String> anchors, String base, String suffix) {
        for (int n = 1; ; n++) {
            String tail = n == 1 ? suffix : suffix + " #" + n;
            String cut = base.length() + tail.length() > MAX_ANCHOR
                    ? base.substring(0, MAX_ANCHOR - tail.length()).strip() : base;
            if (anchors.add(cut + tail)) {
                return cut + tail;
            }
        }
    }

    /** The lines packed into passages of at most {@value #MAX_PASSAGE} characters, whole blocks where they fit. */
    private static List<String> passages(List<String> lines) {
        List<String> passages = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (List<String> block : blocks(lines)) {
            for (String piece : split(block)) {
                if (current.length() > 0 && current.length() + 2 + piece.length() > MAX_PASSAGE) {
                    passages.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append("\n\n");
                }
                current.append(piece);
            }
        }
        if (!current.toString().isBlank()) {
            passages.add(current.toString());
        }
        return passages;
    }

    /** Paragraphs, tables and fenced code blocks: runs of lines separated by blank lines, a fence kept whole. */
    private static List<List<String>> blocks(List<String> lines) {
        List<List<String>> blocks = new ArrayList<>();
        List<String> block = new ArrayList<>();
        boolean fenced = false;
        for (String line : lines) {
            if (line.strip().startsWith("```")) {
                fenced = !fenced;
            }
            if (!fenced && line.isBlank()) {
                if (!block.isEmpty()) {
                    blocks.add(block);
                    block = new ArrayList<>();
                }
            } else {
                block.add(line);
            }
        }
        if (!block.isEmpty()) {
            blocks.add(block);
        }
        return blocks;
    }

    /**
     * One block as pieces that each fit a passage: the block itself when it fits; a table cut between rows with its
     * header and separator rows repeated; anything else cut between lines.
     */
    private static List<String> split(List<String> block) {
        String whole = String.join("\n", block).strip();
        if (whole.length() <= MAX_PASSAGE) {
            return List.of(whole);
        }
        boolean table = block.size() > 2 && block.get(0).strip().startsWith("|")
                && block.get(1).strip().matches("\\|[\\s:|-]+\\|?");
        List<String> header = table ? block.subList(0, 2) : List.of();
        List<String> pieces = new ArrayList<>();
        List<String> piece = new ArrayList<>(header);
        int length = String.join("\n", header).length();
        for (String line : table ? block.subList(2, block.size()) : block) {
            if (piece.size() > header.size() && length + 1 + line.length() > MAX_PASSAGE) {
                pieces.add(String.join("\n", piece).strip());
                piece = new ArrayList<>(header);
                length = String.join("\n", header).length();
            }
            piece.add(line);
            length += 1 + line.length();
        }
        if (piece.size() > header.size()) {
            pieces.add(String.join("\n", piece).strip());
        }
        return pieces;
    }

    /** The cells after the first, trimmed, the trailing empty one dropped. */
    private static List<String> cells(String rest) {
        List<String> cells = new ArrayList<>();
        for (String cell : rest.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        while (!cells.isEmpty() && cells.getLast().isEmpty()) {
            cells.removeLast();
        }
        return cells;
    }
}
