package com.stockflow.assistant.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * How a search reads its query and orders what it finds (design §6.3–6.4): no database, no provider, only the
 * rankings and their fusion.
 */
class KnowledgeSearchTest {

    private static final KnowledgeChunk RETURNS = rule("BR-RMA-002", "Custom-printed items are not returnable unless "
            + "defective.");
    private static final KnowledgeChunk WINDOW = rule("BR-RMA-001", "A return can only be opened within the "
            + "configured return window after delivery.");
    private static final KnowledgeChunk FEFO = rule("BR-STK-006", "Allocation picks the lot with the earliest expiry "
            + "date first (FEFO).");
    private static final KnowledgeChunk IN_TRANSIT = new KnowledgeChunk("01-ubiquitous-language", "In transit",
            KnowledgeChunk.Kind.TERM, "In transit", "Goods on their way between two warehouses.");
    private static final KnowledgeChunk PUTAWAY = new KnowledgeChunk("03-state-machines",
            "7. Putaway task — warehouse-service", KnowledgeChunk.Kind.STATE_MACHINE,
            "7. Putaway task — warehouse-service", "A putaway task moves received goods in to a bin.");

    private static KnowledgeChunk rule(String id, String body) {
        return new KnowledgeChunk("05-business-rules", id, KnowledgeChunk.Kind.RULE, id + " — Rules", body);
    }

    private static KnowledgeRepository.EmbeddedChunk embedded(KnowledgeChunk chunk, float... vector) {
        return new KnowledgeRepository.EmbeddedChunk(chunk, vector);
    }

    @Test
    @DisplayName("a query's words are letters and digits, lower-case, distinct, at most sixteen; rule ids upper-case")
    void readsAQuery() {
        assertThat(KnowledgeSearch.words("Hàng IN theo yêu cầu: in? (x) BR-rma-002")).containsExactly("hàng", "in",
                "theo", "yêu", "cầu", "br", "rma", "002");
        assertThat(KnowledgeSearch.words("!!! ?? - a")).isEmpty();
        assertThat(KnowledgeSearch.words("w1 w2 w3 w4 w5 w6 w7 w8 w9 w10 w11 w12 w13 w14 w15 w16 w17 w18"))
                .hasSize(KnowledgeSearch.MAX_WORDS).doesNotContain("w17");
        assertThat(KnowledgeSearch.namedRules("what do br-rma-002 and BR-STK-006a say")).containsExactly("BR-RMA-002",
                "BR-STK-006A");
        assertThat(KnowledgeSearch.namedRules("custom printed returns")).isEmpty();
    }

    @Test
    @DisplayName("cosine: 1 for one direction whatever the length, 0 for unrelated or a zero vector, -1 for opposite")
    void cosine() {
        assertThat(KnowledgeSearch.cosine(new float[] {1, 2, 3}, new float[] {2, 4, 6})).isCloseTo(1, within(1e-9));
        assertThat(KnowledgeSearch.cosine(new float[] {1, 0}, new float[] {0, 5})).isZero();
        assertThat(KnowledgeSearch.cosine(new float[] {1, 0}, new float[] {-3, 0})).isCloseTo(-1, within(1e-9));
        assertThat(KnowledgeSearch.cosine(new float[] {0, 0}, new float[] {1, 1})).isZero();
        assertThatThrownBy(() -> KnowledgeSearch.cosine(new float[] {1, 0}, new float[] {1, 0, 0}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final float[] QUESTION = {1, 0, 0};

    /** A passage whose cosine similarity to {@link #QUESTION} is exactly {@code similarity}. */
    private static KnowledgeRepository.EmbeddedChunk at(KnowledgeChunk chunk, double similarity) {
        return embedded(chunk, (float) similarity, (float) Math.sqrt(1 - similarity * similarity), 0);
    }

    /** {@code count} glossary passages with similarities spread evenly from {@code from}, {@code step} apart. */
    private static List<KnowledgeRepository.EmbeddedChunk> bulk(int count, double from, double step) {
        return IntStream.range(0, count).mapToObj(i -> at(new KnowledgeChunk("01-ubiquitous-language", "Term " + i,
                KnowledgeChunk.Kind.TERM, "Term " + i, "Passage " + i + "."), from + step * i)).toList();
    }

    private static List<KnowledgeRepository.EmbeddedChunk> with(List<KnowledgeRepository.EmbeddedChunk> bulk,
                                                                 KnowledgeRepository.EmbeddedChunk... passages) {
        return Stream.concat(bulk.stream(), Stream.of(passages)).toList();
    }

    @Test
    @DisplayName("the meaning ranking: most similar first, ties by source and anchor, nothing unrelated, at most the "
            + "limit")
    void nearest() {
        List<KnowledgeRepository.EmbeddedChunk> corpus = with(bulk(30, 0, 0),
                embedded(FEFO, 0.6f, 0.8f, 0),
                embedded(WINDOW, 0.9f, 0.1f, 0),
                embedded(RETURNS, 2, 0, 0),
                embedded(PUTAWAY, -1, 0, 0));

        assertThat(KnowledgeSearch.nearest(QUESTION, corpus, 10)).containsExactly(RETURNS, WINDOW, FEFO);
        assertThat(KnowledgeSearch.nearest(QUESTION, corpus, 2)).containsExactly(RETURNS, WINDOW);
        assertThat(KnowledgeSearch.nearest(QUESTION, with(bulk(30, 0, 0), embedded(WINDOW, 1, 0, 0),
                embedded(RETURNS, 1, 0, 0)), 10)).as("a tie goes by anchor, not by the order the rows came in")
                .containsExactly(WINDOW, RETURNS);
    }

    @Test
    @DisplayName("near is standing out from the corpus: the rule that answers clears the bar, a slightly closer "
            + "passage does not, and a question nothing answers gets nothing")
    void nearMeansStandingOut() {
        // Unrelated passages as an embedding model sees them: similarities from 0.40 to 0.50, none of them zero.
        List<KnowledgeRepository.EmbeddedChunk> unrelated = bulk(40, 0.40, 0.0025);

        assertThat(KnowledgeSearch.nearest(QUESTION, with(unrelated, at(RETURNS, 0.75), at(WINDOW, 0.52)), 10))
                .containsExactly(RETURNS);
        assertThat(KnowledgeSearch.nearest(QUESTION, unrelated, 10)).isEmpty();
    }

    @Test
    @DisplayName("a large group of related passages does not raise the bar for its own members")
    void aGroupStandsOutTogether() {
        List<KnowledgeRepository.EmbeddedChunk> orders = IntStream.range(0, 12).mapToObj(i -> at(new KnowledgeChunk(
                "03-state-machines", "9. Order (" + i + ")", KnowledgeChunk.Kind.STATE_MACHINE, "9. Order (" + i + ")",
                "Order passage " + i + "."), 0.70 + 0.002 * i)).toList();
        List<KnowledgeRepository.EmbeddedChunk> corpus = Stream.concat(bulk(40, 0.40, 0.0025).stream(),
                orders.stream()).toList();

        // The mean plus two standard deviations would put the bar at about 0.74, above all twelve.
        assertThat(KnowledgeSearch.nearest(QUESTION, corpus, 20)).hasSize(12)
                .allSatisfy(chunk -> assertThat(chunk.anchor()).startsWith("9. Order"));
    }

    @Test
    @DisplayName("the bar is never below zero, and nothing stands out of a corpus where everything is alike")
    void floor() {
        assertThat(KnowledgeSearch.floor(new double[0])).isZero();
        assertThat(KnowledgeSearch.floor(new double[] {-0.5, -0.4, -0.45})).isZero();
        assertThat(KnowledgeSearch.nearest(QUESTION, bulk(10, 0.6, 0), 10)).isEmpty();
        assertThat(KnowledgeSearch.nearest(QUESTION, List.of(at(RETURNS, 0.9)), 10))
                .as("one passage stands out from nothing").isEmpty();
    }

    @Test
    @DisplayName("a passage both rankings found comes first; one that only one ranking found follows, keyword first "
            + "on a tie")
    void fusesTheRankings() {
        List<KnowledgeChunk> fused = KnowledgeSearch.fuse(
                List.of(IN_TRANSIT, WINDOW, PUTAWAY),
                List.of(RETURNS, WINDOW, FEFO),
                List.of(), 5);

        // WINDOW: 1/62 + 1/62; IN_TRANSIT and RETURNS: 1/61 each, the keyword hit first; PUTAWAY and FEFO: 1/63.
        assertThat(fused).containsExactly(WINDOW, IN_TRANSIT, RETURNS, PUTAWAY, FEFO);
        assertThat(KnowledgeSearch.fuse(List.of(IN_TRANSIT, WINDOW, PUTAWAY), List.of(RETURNS, WINDOW, FEFO),
                List.of(), 2)).containsExactly(WINDOW, IN_TRANSIT);
    }

    @Test
    @DisplayName("the motivating case: a Vietnamese question whose keywords hit only noise still gets the rule the "
            + "meaning ranking found")
    void meaningRescuesAKeywordMiss() {
        // "hang in theo yeu cau co duoc tra lai khong", keyword-only: "In transit" on the word "in", then noise.
        List<KnowledgeChunk> fused = KnowledgeSearch.fuse(List.of(IN_TRANSIT, PUTAWAY, FEFO), List.of(RETURNS, WINDOW),
                List.of(), 5);

        assertThat(fused).startsWith(IN_TRANSIT, RETURNS).contains(WINDOW);
    }

    @Test
    @DisplayName("a rule the query names stays first, even when fusion alone would rank another passage above it")
    void aNamedRuleStaysFirst() {
        List<KnowledgeChunk> fused = KnowledgeSearch.fuse(
                List.of(RETURNS, WINDOW),
                List.of(FEFO, WINDOW),
                List.of("BR-RMA-002"), 5);

        assertThat(fused).containsExactly(RETURNS, WINDOW, FEFO);
    }

    @Test
    @DisplayName("without a meaning ranking the keyword ranking is unchanged; a passage listed twice counts once")
    void keywordRankingUnchanged() {
        assertThat(KnowledgeSearch.fuse(List.of(PUTAWAY, RETURNS, IN_TRANSIT), List.of(), List.of(), 5))
                .containsExactly(PUTAWAY, RETURNS, IN_TRANSIT);
        assertThat(KnowledgeSearch.fuse(List.of(), List.of(FEFO, RETURNS), List.of(), 5)).containsExactly(FEFO,
                RETURNS);
        // Counted twice, RETURNS would score 1/61 + 1/62 and beat WINDOW, which would then be third by keyword
        // (1/63 + 1/61). Counted once, RETURNS has 1/61 and WINDOW 1/62 + 1/61.
        assertThat(KnowledgeSearch.fuse(List.of(RETURNS, RETURNS, WINDOW), List.of(WINDOW), List.of(), 5))
                .as("RETURNS counts once, and WINDOW is second by keyword").containsExactly(WINDOW, RETURNS);
        assertThat(KnowledgeSearch.fuse(List.of(), List.of(), List.of("BR-RMA-002"), 5)).isEmpty();
    }
}
