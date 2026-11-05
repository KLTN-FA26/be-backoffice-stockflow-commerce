package com.stockflow.assistant.internal.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How the knowledge search reads a query and orders what it finds (design §6.3–6.4): the words and the rule ids a
 * query names, the passages nearest to it in meaning, and the one list made of the keyword and the meaning rankings.
 * The SQL of the keyword ranking lives in the repository; everything here is plain Java, so the ordering is tested
 * without a database.
 *
 * <p><b>Reciprocal rank fusion, k = {@value #RRF_K}.</b> A passage scores {@code 1 / (k + rank)} in each ranking that
 * holds it, summed. Ranks, not scores: {@code ts_rank_cd} and cosine similarity live on scales that do not add up, and
 * weighing one against the other would need numbers the golden set does not have yet. With k = 60 the first places of
 * a ranking are worth almost the same, so a passage both rankings found comes before one that a single ranking put
 * first. On a tie the keyword hit comes first: it is the one a person can check by reading the rule.</p>
 *
 * <p><b>A rule the query names comes first</b>, whatever else matched, as it did when the search was keyword-only: a
 * question about "BR-RMA-002" wants that rule, and fusion alone could put a passage both rankings liked above it.</p>
 *
 * <p><b>Near means standing out from the corpus, not merely ranking first.</b> Every passage has a nearest neighbour,
 * and an embedding model's similarities sit high even between unrelated texts, so the ten most similar passages to a
 * question no rule answers ("nhân viên nghỉ phép năm bao nhiêu ngày") would still be handed to the model, which may
 * cite one, and the citation check would call it verified. A passage counts as near only when its similarity stands
 * {@value #STANDOUT} robust standard deviations above the median similarity of this question to the whole corpus. It
 * needs no absolute threshold, which would depend on the model: the passages that answer a question stand clear of the
 * rest, and for a question nothing answers, nothing does, so the search returns what the keywords alone found — often
 * nothing in the English rules, though a Vietnamese question finds Vietnamese passages by its words — exactly as
 * before. The median and the median absolute deviation, not the mean and the standard deviation,
 * so that a large group of related passages (the twelve passages of the order state machine) does not raise the bar
 * for its own members. Three deviations: among 170 passages that are all unrelated, about one question in five sees a
 * single one clear it by chance, while a rule that answers the question stands further out. It is a first value, to
 * tune with the golden set's recall@5.</p>
 */
public final class KnowledgeSearch {

    /** The constant of reciprocal rank fusion: the value its authors found robust, and the usual default. */
    public static final int RRF_K = 60;

    /** How far above the corpus's median similarity, in robust standard deviations, a near passage stands. */
    public static final double STANDOUT = 3.0;

    /** Turns a median absolute deviation into the standard deviation it estimates for normally spread values. */
    private static final double MAD_TO_DEVIATION = 1.4826;

    /** At most this many words of a query reach the keyword search. */
    public static final int MAX_WORDS = 16;

    /**
     * A word of a query: two or more letters or digits. Nothing else reaches {@code to_tsquery}, so nothing in a query
     * can parse as one of its operators.
     */
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{2,}");

    /** A rule id or a flow code named in a query ({@code BR-RMA-002}, {@code F-PO}, {@code X-SHORT}; any case). */
    private static final Pattern RULE = Pattern.compile("BR-[A-Z]+-\\d+[a-z]?|(?<![\\p{L}\\p{N}-])[FX]-[A-Z]{2,8}"
            + "(?![\\p{L}\\p{N}-])", Pattern.CASE_INSENSITIVE);

    /**
     * Vietnamese words that carry no topic, as written with their marks. The keyword search is OR over a query's
     * words, in the {@code simple} configuration, which knows no stop words, so "có", "được" or "không" in a question
     * matched nearly every Vietnamese passage and buried the one asked about. Compared as written: "có" goes, "cỡ"
     * (a size) and "cờ" (the QC flag) stay.
     */
    private static final Set<String> EMPTY_MARKED = Set.of("không", "có", "được", "của", "và", "là", "các", "những",
            "một", "thì", "này", "khi", "nào", "nhiều", "gì", "đã", "sẽ", "với", "vào", "lại", "nếu", "rồi", "nhé",
            "ơi", "vậy", "sao", "hay", "hoặc", "mình", "tôi", "em", "ra", "đi");

    /**
     * The same words typed without marks, only those no other reading makes into a word a warehouse uses: not
     * {@code co} (cỡ, cờ), {@code cua} (cửa), {@code roi} (hàng rời), {@code toi}, {@code da} (tối đa, tới),
     * {@code lai} (lãi), {@code di} (di chuyển), {@code ra} (rà soát), {@code nhe} (hàng nhẹ), {@code cho} (chờ),
     * {@code bao} (báo giá), {@code cung} (nhà cung cấp), {@code ban} (bán), {@code trong} (trống), {@code thoi}
     * (thời gian), {@code tu} (từ chối), {@code de} (đề xuất), {@code ma} (mã).
     */
    private static final Set<String> EMPTY_UNMARKED = Set.of("khong", "ko", "duoc", "dc", "va", "la", "cac", "nhung",
            "mot", "thi", "nay", "khi", "nao", "nhieu", "gi", "se", "voi", "vao", "neu", "oi", "vay", "sao", "hay",
            "hoac", "minh", "em");

    private KnowledgeSearch() {
    }

    /**
     * The distinct words of {@code query}, lower-case, in order, at most {@value #MAX_WORDS}, without the Vietnamese
     * words that name nothing ({@link #EMPTY_MARKED}, {@link #EMPTY_UNMARKED}). Empty when the query has nothing to
     * search for ({@code "!!! ??"}, "có không"): both rankings then find nothing.
     */
    public static Set<String> words(String query) {
        Set<String> words = new LinkedHashSet<>();
        Matcher word = WORD.matcher(Normalizer.normalize(query, Normalizer.Form.NFC));
        while (word.find() && words.size() < MAX_WORDS) {
            String lower = word.group().toLowerCase(Locale.ROOT);
            String unmarked = unmarked(lower);
            boolean empty = unmarked.equals(lower) ? EMPTY_UNMARKED.contains(lower) : EMPTY_MARKED.contains(lower);
            if (!empty) {
                words.add(lower);
            }
        }
        return words;
    }

    /** A word without its Vietnamese marks, đ read as d: how the database compares it ({@code immutable_unaccent}). */
    public static String unmarked(String word) {
        return Normalizer.normalize(word.replace('đ', 'd'), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /** The rule ids and flow codes {@code query} names, upper-case: those passages come first in either search. */
    public static List<String> namedRules(String query) {
        List<String> rules = new ArrayList<>();
        Matcher rule = RULE.matcher(query);
        while (rule.find()) {
            rules.add(rule.group().toUpperCase(Locale.ROOT));
        }
        return rules;
    }

    /**
     * The cosine of the angle between two vectors: 1 for the same direction, 0 for unrelated. Computed with the
     * lengths, so it does not matter whether the provider normalised its vectors (Gemini does only at full length).
     * A zero vector is related to nothing.
     *
     * @throws IllegalArgumentException for vectors of different lengths, which come from different models
     */
    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("vectors of " + a.length + " and " + b.length + " dimensions");
        }
        double dot = 0;
        double squaresA = 0;
        double squaresB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            squaresA += (double) a[i] * a[i];
            squaresB += (double) b[i] * b[i];
        }
        if (squaresA == 0 || squaresB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(squaresA) * Math.sqrt(squaresB));
    }

    /**
     * How a passage was found, shown with it ({@code foundBy}): named by the query, by both rankings, by its words
     * alone, or by its meaning alone. A passage only meaning found shares no word with the search: the likeliest to be
     * near the question without answering it, so the one to read most carefully.
     */
    public enum FoundBy {
        NAMED, BOTH, WORDS, MEANING;

        /** As the tool result writes it. */
        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A passage of a search's result, and how it was found. */
    public record Hit(KnowledgeChunk chunk, FoundBy foundBy) {
    }

    /** {@link #nearest(float[], List, int, double)} at {@value #STANDOUT} deviations. */
    public static List<KnowledgeChunk> nearest(float[] query, List<KnowledgeRepository.EmbeddedChunk> passages,
                                               int limit) {
        return nearest(query, passages, limit, STANDOUT);
    }

    /**
     * The meaning ranking: at most {@code limit} of the passages near {@code query} ({@link #floor}), most similar
     * first. Ties are broken by source and anchor, so the order never depends on the order the database returned the
     * rows in.
     *
     * @param passages the whole embedded corpus: the floor is measured against all of it
     * @param standout how far above the median, in robust deviations, a passage must stand ({@link #STANDOUT})
     */
    public static List<KnowledgeChunk> nearest(float[] query, List<KnowledgeRepository.EmbeddedChunk> passages,
                                               int limit, double standout) {
        record Scored(KnowledgeChunk chunk, double similarity) {
        }
        List<Scored> scored = passages.stream()
                .map(passage -> new Scored(passage.chunk(), cosine(query, passage.embedding())))
                .filter(passage -> !Double.isNaN(passage.similarity()))
                .toList();
        double floor = floor(scored.stream().mapToDouble(Scored::similarity).toArray(), standout);
        return scored.stream()
                .filter(passage -> passage.similarity() > floor)
                .sorted(Comparator.comparingDouble(Scored::similarity).reversed()
                        .thenComparing(passage -> passage.chunk().source())
                        .thenComparing(passage -> passage.chunk().anchor()))
                .limit(limit)
                .map(Scored::chunk)
                .toList();
    }

    /**
     * The similarity a near passage must exceed: {@value #STANDOUT} robust standard deviations above the median of
     * {@code similarities}, and never less than zero. When more than half the similarities are equal the deviation is
     * zero, and anything above the median stands out; a corpus of one or two passages has nothing that does.
     */
    static double floor(double[] similarities) {
        return floor(similarities, STANDOUT);
    }

    static double floor(double[] similarities, double standout) {
        if (similarities.length == 0) {
            return 0;
        }
        double median = median(similarities);
        double[] deviations = new double[similarities.length];
        for (int i = 0; i < similarities.length; i++) {
            deviations[i] = Math.abs(similarities[i] - median);
        }
        return Math.max(0, median + standout * MAD_TO_DEVIATION * median(deviations));
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }

    /**
     * The keyword and the meaning rankings as one list of at most {@code limit} passages: a rule the query names
     * first, then by fused score, a tie going to the better keyword place, then to the better meaning place. With an
     * empty meaning ranking this is the keyword ranking unchanged.
     *
     * @param byWords    best first, as the keyword search returned it
     * @param byMeaning  best first, as {@link #nearest} returned it
     * @param namedRules {@link #namedRules} of the query
     */
    public static List<KnowledgeChunk> fuse(List<KnowledgeChunk> byWords, List<KnowledgeChunk> byMeaning,
                                            Collection<String> namedRules, int limit) {
        return fuseAll(byWords, List.of(byMeaning), namedRules, limit).stream().map(Hit::chunk).toList();
    }

    /**
     * The keyword ranking and any number of meaning rankings — one per text embedded: the search terms, and the
     * user's question when it is searched too — as one list, each passage with how it was found. Every ranking adds
     * {@code 1 / (k + rank)}; a passage's meaning place is its best among them.
     */
    public static List<Hit> fuseAll(List<KnowledgeChunk> byWords, List<List<KnowledgeChunk>> byMeaning,
                                    Collection<String> namedRules, int limit) {
        Map<Key, Fused> fused = new LinkedHashMap<>();
        rank(byWords, fused, true);
        byMeaning.forEach(ranking -> rank(ranking, fused, false));
        return fused.values().stream()
                .sorted(Comparator.comparing((Fused entry) -> !named(entry.chunk, namedRules))
                        .thenComparing(Comparator.comparingDouble((Fused entry) -> entry.score).reversed())
                        .thenComparingInt(entry -> entry.wordRank)
                        .thenComparingInt(entry -> entry.meaningRank)
                        .thenComparing(entry -> entry.chunk.source())
                        .thenComparing(entry -> entry.chunk.anchor()))
                .limit(limit)
                .map(entry -> new Hit(entry.chunk, named(entry.chunk, namedRules) ? FoundBy.NAMED
                        : entry.wordRank != Integer.MAX_VALUE && entry.meaningRank != Integer.MAX_VALUE ? FoundBy.BOTH
                        : entry.wordRank != Integer.MAX_VALUE ? FoundBy.WORDS : FoundBy.MEANING))
                .toList();
    }

    private static boolean named(KnowledgeChunk chunk, Collection<String> namedRules) {
        return namedRules.contains(chunk.anchor().toUpperCase(Locale.ROOT));
    }

    private static void rank(List<KnowledgeChunk> ranking, Map<Key, Fused> fused, boolean byWords) {
        Set<Key> seen = new HashSet<>();
        int rank = 0;
        for (KnowledgeChunk chunk : ranking) {
            Key key = new Key(chunk.source(), chunk.anchor());
            if (!seen.add(key)) {
                continue;
            }
            rank++;
            Fused entry = fused.computeIfAbsent(key, any -> new Fused(chunk));
            entry.score += 1.0 / (RRF_K + rank);
            if (byWords) {
                entry.wordRank = rank;
            } else {
                entry.meaningRank = Math.min(entry.meaningRank, rank);
            }
        }
    }

    /** A passage's identity: its anchor is unique within its source, whichever ranking returned it. */
    private record Key(String source, String anchor) {
    }

    private static final class Fused {

        private final KnowledgeChunk chunk;
        private double score;
        private int wordRank = Integer.MAX_VALUE;
        private int meaningRank = Integer.MAX_VALUE;

        private Fused(KnowledgeChunk chunk) {
            this.chunk = chunk;
        }
    }
}
