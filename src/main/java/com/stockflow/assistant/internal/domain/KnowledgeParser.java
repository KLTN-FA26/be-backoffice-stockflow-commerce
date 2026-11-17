package com.stockflow.assistant.internal.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cuts the kltn-docs business analysis into chunks (design §6.1). Two shapes: a chapter, which follows one template
 * (purpose, actors, input/output, the step-by-step flow in §4, the statuses in §5, the business rules and edge cases
 * in §6, links), and the glossary, a table per group of terms.
 *
 * <p><b>A chapter</b> gives one chunk per business rule of §6, anchored by the chapter and the rule
 * ({@code 15 BR-08}): rule numbers start again in every chapter, so {@code BR-08} alone names a rule of each.
 * Its §5 (the statuses) and §4 (the flow) are cut at their third-level headings, and the edge cases of §6 make a
 * section of their own; each is anchored by the chapter and its section number, as the team cites them
 * ({@code 17 §5.1}, {@code 07 §4.4}, {@code 17 §6}). §1–§3 and §7 (purpose, actors, inputs, links) are left out, and so
 * are the parts written for the people building the system: the data to store, a comparison with other products, and
 * the Mermaid diagrams, unreadable as text.</p>
 *
 * <p><b>The glossary</b> gives one chunk per term (a table row whose first cell is bold), anchored by the term as
 * written and titled by it alone; the rest of a group — the common statuses, the abbreviations, the terms no longer used
 * (§8) — is cut into passages anchored {@code glossary §5}, {@code glossary §8}. The log of decisions opening it is
 * left out ({@link #glossary}).</p>
 *
 * <p><b>Every chunk fits a passage.</b> A section, a rule or a definition longer than {@value #MAX_PASSAGE} characters
 * is cut at paragraph, table-row or line boundaries, a table cut in two repeating its header; the parts are anchored
 * {@code 17 §5.1}, {@code 17 §5.1 (2)}, ... and titled {@code (1/3)}, {@code (2/3)}. A link keeps its text, with the
 * chapter it points to when the text does not say ({@code [Payment](../15-payment/README.md)} reads
 * {@code Payment (15)}): a path is noise to the staff who ask, and the prompt forbids links in answers.</p>
 */
public final class KnowledgeParser {

    /**
     * The longest passage. A model is shown whole passages; a section of 17,000 characters cut to its first 1,500 hid
     * the table that answered the question and invited the model to fill the gap itself.
     */
    public static final int MAX_PASSAGE = 1400;

    /** The anchor of the glossary's own passages, and its source name. */
    public static final String GLOSSARY = "glossary";

    private static final Pattern TOP = Pattern.compile("^#\\s+(?!#)(.+)$");
    private static final Pattern SECTION = Pattern.compile("^##\\s+(?!#)(.+)$");
    private static final Pattern SUBSECTION = Pattern.compile("^###\\s+(?!#)(.+)$");

    /** A heading's number and name: {@code 4.1. Tạo & giao task}, {@code 3a. Thuật ngữ mẫu}. */
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+[a-z]?(?:\\.\\d+)*)\\.?\\s+(.+)$");

    /** A chapter's title: {@code 15 — Thanh toán theo hợp đồng & Công nợ (Contract Payment & Receivables)}. */
    private static final Pattern CHAPTER_TITLE = Pattern.compile("^\\d+\\s*[—–-]\\s*(.+)$");

    /** The English name in a chapter title's last brackets, letters of the Latin alphabet only. */
    private static final Pattern ENGLISH_NAME = Pattern.compile("\\(([A-Za-z0-9 &,/'-]+)\\)\\s*$");

    /** A source named by its chapter: {@code 15-payment}. */
    private static final Pattern CHAPTER_SOURCE = Pattern.compile("^(\\d{2})-.+$");

    /**
     * A business rule, a top-level bullet: {@code - **BR-08:** text}, or with a name of its own,
     * {@code - **BR-05 — Bốn mắt.** text}.
     */
    private static final Pattern RULE = Pattern.compile(
            "^[-*]\\s+\\*\\*BR-(\\d+[a-z]?)\\s*(?:[—–-]\\s*([^*]+?))?\\s*[:.]?\\s*\\*\\*\\s*[:.]?\\s*(.*)$");

    /**
     * A rule whose text opens with its name in bold, a sentence of its own: {@code **Nguồn kết quả giao.**},
     * {@code **Dời cửa sổ giao** *(✅ …)*.}. Not a bold phrase that opens a longer sentence
     * ({@code **Mọi đơn giao bằng giao nguyên chuyến** (17 BR-16); …}).
     */
    private static final Pattern RULE_NAME = Pattern.compile(
            "^\\*\\*([^*]{2,80}?)(?:\\.\\*\\*|\\*\\*(?:\\s*\\*\\([^)]*\\)\\*)?\\s*[.:])");

    /** The longest anchor, the column's ({@code assistant.knowledge_chunk.anchor}), with room for {@code " (99)"}. */
    private static final int MAX_ANCHOR = 150;

    /** A line naming the group of the rules below it: {@code **Giao nguyên chuyến & Load Planning:**}. */
    private static final Pattern RULE_GROUP = Pattern.compile("^\\*\\*([^*]+?)\\s*:?\\*\\*.*$");

    /** A link, its text and its target. */
    private static final Pattern LINK = Pattern.compile("!?\\[([^\\]]*)]\\(([^)\\s]*)\\)");

    /** The chapter a link's target is in: {@code ../15-payment/README.md}. */
    private static final Pattern LINKED_CHAPTER = Pattern.compile("(?:^|/)(\\d{2})-[\\w-]+/");

    /** A decision's date in a heading or a term: {@code *(✅ 2026-11-06)*}. */
    private static final Pattern ANNOTATION = Pattern.compile("\\s*\\*\\([^)]*\\)\\*");

    /** A table's separator row. */
    private static final Pattern SEPARATOR = Pattern.compile("\\|[\\s:|-]+\\|?");

    /** Subsections written for the people building the system: the fields to store, a comparison with other products. */
    private static final Set<String> FOR_BUILDERS = Set.of("dữ liệu tối thiểu cần lưu", "đối chiếu chuẩn tham chiếu");

    private KnowledgeParser() {
    }

    /**
     * A chapter of the analysis ({@code 15-payment}): its rules, statuses, edge cases and flow (class javadoc). The
     * chapter number in every anchor is the source's ({@code 15} of {@code 15-payment}).
     */
    public static List<KnowledgeChunk> chapter(String source, String markdown) {
        Matcher numbered = CHAPTER_SOURCE.matcher(source);
        String chapter = numbered.matches() ? numbered.group(1) : source;
        Chunks chunks = new Chunks(source);
        String name = null;
        String section = null;
        String sectionName = null;
        Part part = null;
        boolean rules = false;
        String group = null;
        Rule rule = null;
        boolean diagram = false;
        for (String line : markdown.split("\\R")) {
            String stripped = line.strip();
            if (diagram) {
                diagram = !stripped.startsWith("```");
                continue;
            }
            if (stripped.startsWith("```mermaid")) {
                diagram = true;
                continue;
            }
            Matcher top = TOP.matcher(line);
            Matcher heading = SECTION.matcher(line);
            Matcher subheading = SUBSECTION.matcher(line);
            if (top.matches()) {
                if (name == null) {
                    String title = plain(top.group(1)).strip();
                    Matcher titled = CHAPTER_TITLE.matcher(title);
                    name = englishName(titled.matches() ? titled.group(1).strip() : title);
                }
            } else if (heading.matches()) {
                chunks.add(part, name);
                chunks.add(rule, chapter, name);
                part = null;
                rule = null;
                rules = false;
                group = null;
                Matcher number = NUMBERED.matcher(plain(heading.group(1)).strip());
                section = number.matches() ? number.group(1) : null;
                sectionName = number.matches() ? number.group(2) : null;
                KnowledgeChunk.Kind kind = kindOf(section);
                // §6 opens nothing by itself: its rules and its edge cases each have a subsection.
                part = kind == null || kind == KnowledgeChunk.Kind.RULE ? null
                        : new Part(kind, chapter + " §" + section, chapter + " §" + section + " " + sectionName);
            } else if (subheading.matches() && kindOf(section) != null) {
                chunks.add(part, name);
                chunks.add(rule, chapter, name);
                part = null;
                rule = null;
                rules = false;
                group = null;
                // A decision's date stays in a title, without its emphasis: "5.4. (Đã bỏ 2026-11-05)".
                String title = plain(subheading.group(1)).replace("*", "").strip();
                KnowledgeChunk.Kind kind = kindOf(section);
                if (FOR_BUILDERS.contains(title.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (kind == KnowledgeChunk.Kind.RULE) {
                    String lower = title.toLowerCase(Locale.ROOT);
                    rules = lower.startsWith("business rules");
                    part = lower.startsWith("edge case")
                            ? new Part(kind, chapter + " §" + section, chapter + " §" + section + " " + title) : null;
                } else {
                    Matcher number = NUMBERED.matcher(title);
                    String anchor = chapter + " §" + (number.matches() ? number.group(1) : section);
                    part = new Part(kind, anchor, number.matches() ? anchor + " " + number.group(2)
                            : chapter + " §" + section + " " + title);
                }
            } else if (rules) {
                Matcher opens = RULE.matcher(line);
                Matcher groups = RULE_GROUP.matcher(stripped);
                boolean indented = !line.isEmpty() && Character.isWhitespace(line.charAt(0));
                if (opens.matches()) {
                    chunks.add(rule, chapter, name);
                    rule = new Rule(opens.group(1), opens.group(2), group, opens.group(3));
                } else if (!indented && groups.matches()) {
                    chunks.add(rule, chapter, name);
                    rule = null;
                    group = ANNOTATION.matcher(plain(groups.group(1))).replaceAll("").strip();
                } else if (rule != null && !stripped.equals("---")) {
                    rule.lines.add(line);
                }
            } else if (part != null && !stripped.equals("---")) {
                part.lines.add(line);
            }
        }
        chunks.add(part, name);
        chunks.add(rule, chapter, name);
        return chunks.list();
    }

    /**
     * The English name a chapter's title gives in brackets ({@code Contract Payment & Receivables} of
     * {@code Thanh toán theo hợp đồng & Công nợ (Contract Payment & Receivables)}), what every title of the chapter
     * carries; null when it gives none. Not the Vietnamese name: a title weighs most in the keyword search, and
     * "Quản lý đơn hàng" in every title of chapter 17 matched "đơn hàng" in any question, as "Giao hàng" in every title
     * of chapter 09 matched "hàng".
     */
    static String englishName(String title) {
        Matcher bracketed = ENGLISH_NAME.matcher(title);
        return bracketed.find() ? bracketed.group(1).strip() : null;
    }

    /** §4 the flow, §5 the statuses, §6 the rules and edge cases; null for a section left out. */
    private static KnowledgeChunk.Kind kindOf(String section) {
        if (section == null) {
            return null;
        }
        return switch (section) {
            case "4" -> KnowledgeChunk.Kind.FLOW;
            case "5" -> KnowledgeChunk.Kind.STATE_MACHINE;
            case "6" -> KnowledgeChunk.Kind.RULE;
            default -> null;
        };
    }

    /**
     * The glossary: a chunk per term and passages for the rest of each group (class javadoc), every chunk a
     * {@link KnowledgeChunk.Kind#TERM}. The decisions opening the document are left out: a log in date order, in which
     * a later decision replaces an earlier one ("Sale lập" Đơn sản xuất, then Điều phối sản xuất), and a passage cut
     * from it would state the earlier one as the rule.
     */
    public static List<KnowledgeChunk> glossary(String source, String markdown) {
        Chunks chunks = new Chunks(source);
        // Null until the first group: what opens the document is left out.
        String group = null;
        String groupName = null;
        List<String> lines = new ArrayList<>();
        boolean diagram = false;
        for (String line : markdown.split("\\R")) {
            String stripped = line.strip();
            if (diagram) {
                diagram = !stripped.startsWith("```");
                continue;
            }
            if (stripped.startsWith("```mermaid")) {
                diagram = true;
                continue;
            }
            Matcher heading = SECTION.matcher(line);
            if (heading.matches()) {
                if (group != null) {
                    addGlossaryGroup(chunks, group, groupName, lines);
                }
                lines.clear();
                Matcher number = NUMBERED.matcher(plain(heading.group(1)).strip());
                group = GLOSSARY + " §" + (number.matches() ? number.group(1) : heading.group(1).strip());
                groupName = number.matches() ? number.group(2) : heading.group(1).strip();
            } else if (!stripped.equals("---")) {
                lines.add(line);
            }
        }
        if (group != null) {
            addGlossaryGroup(chunks, group, groupName, lines);
        }
        return chunks.list();
    }

    /** One group of the glossary: each table of bold terms as terms, everything else as the group's passages. */
    private static void addGlossaryGroup(Chunks chunks, String anchor, String name, List<String> lines) {
        String title = name == null ? anchor : anchor + " " + name;
        List<String> rest = new ArrayList<>();
        for (List<String> block : blocks(lines)) {
            if (!isTermTable(block)) {
                rest.addAll(block);
                rest.add("");
                continue;
            }
            for (String row : block.subList(2, block.size())) {
                List<String> cells = rowCells(row);
                String term = ANNOTATION.matcher(plain(cells.getFirst())).replaceAll("").replace("**", "")
                        .replace("`", "").strip();
                String definition = String.join(" | ", cells.subList(1, cells.size())).strip();
                // Titled by the term alone: the group's name in every title of the group matched its words in any
                // question ("hàng hoá & tồn kho").
                if (!term.isEmpty() && !definition.isEmpty()) {
                    chunks.add(KnowledgeChunk.Kind.TERM, term, term, List.of(plain(definition)));
                }
            }
        }
        chunks.add(KnowledgeChunk.Kind.TERM, anchor, title, rest.stream().map(KnowledgeParser::plain).toList());
    }

    /** A table whose every row names its term in bold in its first cell. */
    private static boolean isTermTable(List<String> block) {
        if (block.size() < 3 || !block.getFirst().strip().startsWith("|")
                || !SEPARATOR.matcher(block.get(1).strip()).matches()) {
            return false;
        }
        return block.subList(2, block.size()).stream()
                .allMatch(row -> row.strip().startsWith("|") && rowCells(row).getFirst().startsWith("**"));
    }

    /**
     * {@code line} with every link replaced by its text, and by the chapter it points to when the text does not name
     * it: {@code [17](../17-order-management/README.md) BR-29} reads {@code 17 BR-29}, as rules are cited.
     */
    static String plain(String line) {
        Matcher link = LINK.matcher(line);
        StringBuilder out = new StringBuilder();
        while (link.find()) {
            String text = link.group(1).strip();
            Matcher chapter = LINKED_CHAPTER.matcher(link.group(2));
            if (chapter.find() && !text.contains(chapter.group(1))) {
                text = text.isEmpty() ? chapter.group(1) : text + " (" + chapter.group(1) + ")";
            }
            link.appendReplacement(out, Matcher.quoteReplacement(text));
        }
        link.appendTail(out);
        return out.toString();
    }

    /** A section's lines on their way to passages. */
    private static final class Part {

        private final KnowledgeChunk.Kind kind;
        private final String anchor;
        private final String title;
        private final List<String> lines = new ArrayList<>();

        private Part(KnowledgeChunk.Kind kind, String anchor, String title) {
            this.kind = kind;
            this.anchor = anchor;
            this.title = title;
        }
    }

    /** A business rule's first line and what continues it: indented bullets, notes and paragraphs. */
    private static final class Rule {

        private final String id;
        private final String name;
        private final String group;
        private final String first;
        private final List<String> lines = new ArrayList<>();

        private Rule(String id, String name, String group, String first) {
            this.id = id;
            this.name = name;
            this.group = group;
            this.first = first;
        }
    }

    /** The chunks of one source, every anchor unique: the second passage of an anchor is {@code anchor (2)}. */
    private static final class Chunks {

        private final String source;
        private final List<KnowledgeChunk> list = new ArrayList<>();
        private final Map<String, Integer> passages = new HashMap<>();

        private Chunks(String source) {
            this.source = source;
        }

        void add(Part part, String chapterName) {
            if (part != null) {
                add(part.kind, part.anchor, part.title + (chapterName == null ? "" : " — " + chapterName),
                        part.lines.stream().map(KnowledgeParser::plain).toList());
            }
        }

        void add(Rule rule, String chapter, String chapterName) {
            if (rule == null) {
                return;
            }
            List<String> body = new ArrayList<>();
            body.add(rule.first);
            body.addAll(dedent(rule.lines));
            String text = String.join("\n", body).strip();
            String name = rule.name;
            Matcher opening = RULE_NAME.matcher(text);
            if (name == null && opening.find()) {
                name = opening.group(1).strip();
            }
            String anchor = chapter + " BR-" + rule.id;
            String title = anchor + (name == null ? "" : " " + plain(name).strip())
                    + (chapterName == null ? "" : " — " + chapterName) + (rule.group == null ? "" : " › " + rule.group);
            add(KnowledgeChunk.Kind.RULE, anchor, title, text.lines().map(KnowledgeParser::plain).toList());
        }

        /** {@code lines} as passages of {@code kind}: {@code anchor}, {@code anchor (2)}, ..., titled (i/n). */
        void add(KnowledgeChunk.Kind kind, String base, String title, List<String> lines) {
            // A long term must not fail its document's sync against the column: cut, the counter keeps it unique.
            String anchor = base.length() > MAX_ANCHOR ? base.substring(0, MAX_ANCHOR).strip() : base;
            List<String> cut = passages(lines);
            for (int i = 0; i < cut.size(); i++) {
                int number = passages.merge(anchor, 1, Integer::sum);
                list.add(new KnowledgeChunk(source, number == 1 ? anchor : anchor + " (" + number + ")", kind,
                        cut.size() == 1 ? title : title + " (" + (i + 1) + "/" + cut.size() + ")", cut.get(i)));
            }
        }

        List<KnowledgeChunk> list() {
            return List.copyOf(list);
        }
    }

    /** The lines without the indentation they all share, blank lines aside. */
    private static List<String> dedent(List<String> lines) {
        int common = lines.stream().filter(line -> !line.isBlank())
                .mapToInt(line -> line.length() - line.stripLeading().length()).min().orElse(0);
        return lines.stream().map(line -> line.isBlank() ? "" : line.substring(common)).toList();
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
     * header and separator rows repeated; anything else cut between lines. A single line longer than a passage leaves
     * for its header is cut after a space ({@link #cut}), so no passage is ever longer than {@value #MAX_PASSAGE}.
     */
    private static List<String> split(List<String> block) {
        String whole = String.join("\n", block).strip();
        if (whole.length() <= MAX_PASSAGE) {
            return whole.isEmpty() ? List.of() : List.of(whole);
        }
        boolean table = block.size() > 2 && block.get(0).strip().startsWith("|")
                && SEPARATOR.matcher(block.get(1).strip()).matches();
        List<String> header = table ? block.subList(0, 2) : List.of();
        int headerLength = String.join("\n", header).length();
        if (headerLength > MAX_PASSAGE / 4) {
            // A header that long would leave its rows almost no room: the table is cut as plain lines instead.
            header = List.of();
            headerLength = 0;
        }
        int room = MAX_PASSAGE - headerLength - (header.isEmpty() ? 0 : 1);
        List<String> pieces = new ArrayList<>();
        List<String> piece = new ArrayList<>(header);
        int length = headerLength;
        for (String row : header.isEmpty() ? block : block.subList(2, block.size())) {
            for (String line : cut(row, room)) {
                if (piece.size() > header.size() && length + 1 + line.length() > MAX_PASSAGE) {
                    pieces.add(String.join("\n", piece).strip());
                    piece = new ArrayList<>(header);
                    length = headerLength;
                }
                piece.add(line);
                length += 1 + line.length();
            }
        }
        if (piece.size() > header.size()) {
            pieces.add(String.join("\n", piece).strip());
        }
        return pieces;
    }

    /**
     * A line in parts of at most {@code room} characters: whole when it fits, otherwise cut after the last space of
     * each part (hard at {@code room} when there is none in its second half, never inside a surrogate pair). Every part
     * after the first starts with {@value #CONTINUED}, so a reader sees it carries on a line cut above. A room below
     * {@value #MIN_ROOM} is taken as {@value #MIN_ROOM}, so every part moves the line on ({@link #split} never asks for
     * less than three quarters of a passage).
     */
    static List<String> cut(String line, int asked) {
        int room = Math.max(asked, MIN_ROOM);
        if (line.length() <= room) {
            return List.of(line);
        }
        List<String> parts = new ArrayList<>();
        String rest = line;
        String prefix = "";
        while (!rest.isBlank()) {
            if (prefix.length() + rest.length() <= room) {
                parts.add(prefix + rest);
                break;
            }
            int limit = room - prefix.length();
            int at = rest.lastIndexOf(' ', limit);
            if (at < limit / 2) {
                at = Character.isHighSurrogate(rest.charAt(limit - 1)) ? limit - 1 : limit;
            }
            parts.add(prefix + rest.substring(0, at).stripTrailing());
            rest = rest.substring(at).stripLeading();
            prefix = CONTINUED;
        }
        return parts;
    }

    /** What a part of a cut line starts with ({@link #cut}). */
    static final String CONTINUED = "… ";

    /** The smallest part {@link #cut} makes: room for the continuation mark and a few characters of text. */
    static final int MIN_ROOM = 16;

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
}
