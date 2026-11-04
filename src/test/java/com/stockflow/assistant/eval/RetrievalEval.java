package com.stockflow.assistant.eval;

import com.stockflow.assistant.internal.domain.EmbeddingModel;
import com.stockflow.assistant.internal.domain.KnowledgeRepository;
import com.stockflow.assistant.internal.domain.KnowledgeSearch;
import com.stockflow.assistant.internal.domain.Reranker;
import com.stockflow.assistant.internal.service.KnowledgeService;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.RedisContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The knowledge search measured alone, without a chat model (design §10.2): what it returns for every golden case
 * about the rules and the flows, under every setting worth choosing between. The only cost is embeddings — none with a
 * local embedding server — so it runs as often as the corpus or the ranking changes, and the defaults of
 * {@code stockflow.assistant.knowledge} are chosen on its numbers.
 *
 * <pre>
 * # Gemini embeddings, the deployment's own:
 * ASSISTANT_PROVIDER=gemini ASSISTANT_API_KEY=... mvn test -Dtest=RetrievalEval -Dassistant.eval=true
 * # bge-m3 on a local Ollama, and a local reranker (text-embeddings-inference):
 * ASSISTANT_PROVIDER=openai OPENAI_COMPATIBLE_BASE_URL=http://localhost:11434/v1 ASSISTANT_MODEL=qwen3:8b \
 *   ASSISTANT_EMBEDDINGS_PROVIDER=openai ASSISTANT_EMBEDDINGS_MODEL=bge-m3 ASSISTANT_EMBEDDINGS_DIMENSIONS=1024 \
 *   ASSISTANT_RERANKER_BASE_URL=http://localhost:8081 mvn test -Dtest=RetrievalEval -Dassistant.eval=true
 * </pre>
 *
 * <p><b>Two searches per case.</b> The user's question as the query, which is what a model that does not translate
 * sends; and the case's oracle query, the keywords a model that follows the tool's description sends, with the
 * question beside it for the multi-query settings.</p>
 *
 * <p><b>What is measured.</b> recall@5: the share of a case's expected anchors among the five passages; MRR: one over the place of the first expected
 * one, zero when none is there. For the cases no rule answers, the noise: the share of searches that returned a passage
 * found by meaning — the floor exists to return none — and the share that returned anything at all.</p>
 *
 * <p>The report goes to {@code target/assistant-eval/retrieval/}: every setting best first, then every case under the
 * best one. Nothing is asserted about the numbers: this is a measurement, not a gate. The test profile indexes the
 * flows ({@code ASSISTANT_KNOWLEDGE_FLOWS}, on by default there): run once with it {@code false} to compare the rules'
 * recall without them, which is what deciding to switch them on in production needs.</p>
 */
@Tag("assistant-eval")
@EnabledIfSystemProperty(named = "assistant.eval", matches = "true")
@IntegrationTest
@Import({PostgresContainer.class, RedisContainer.class})
@TestPropertySource(properties = {
        "stockflow.assistant.enabled=true",
        // The test profile turns embeddings off; the eval measures the search the deployment runs.
        "stockflow.assistant.embeddings.enabled=${ASSISTANT_EMBEDDINGS_ENABLED:true}"})
class RetrievalEval {

    static final int RESULTS = 5;
    static final double[] STANDOUTS = {2.0, 2.5, 3.0, 3.5};
    static final int[] DEPTHS = {10, 20};

    @Autowired KnowledgeService knowledge;
    @Autowired KnowledgeRepository repository;
    @Autowired EmbeddingModel embeddings;
    @Autowired Reranker reranker;
    @Autowired JdbcTemplate jdbc;

    /**
     * One search of one case under one setting.
     *
     * @param degraded the search ran below its setting: the question was not embedded or the reranker not asked, after
     *                 a provider failed ({@link KnowledgeService#degradedUntil})
     */
    record Search(GoldenCase golden, String variant, List<KnowledgeSearch.Hit> hits, boolean degraded) {

        List<String> anchors() {
            return hits.stream().map(hit -> hit.chunk().anchor()).toList();
        }

        double recall() {
            long found = golden.expectRetrieved().stream().filter(anchors()::contains).count();
            return (double) found / golden.expectRetrieved().size();
        }

        double reciprocalRank() {
            for (int i = 0; i < hits.size(); i++) {
                if (golden.expectRetrieved().contains(hits.get(i).chunk().anchor())) {
                    return 1.0 / (i + 1);
                }
            }
            return 0;
        }

        boolean byMeaning() {
            return hits.stream().anyMatch(hit -> hit.foundBy() == KnowledgeSearch.FoundBy.MEANING
                    || hit.foundBy() == KnowledgeSearch.FoundBy.BOTH);
        }
    }

    /** A setting's numbers over every search. */
    record Score(String name, KnowledgeService.Settings settings, double recall, double mrr, double meaningNoise,
                 double anyNoise, List<Search> searches) {

        long degraded() {
            return searches.stream().filter(Search::degraded).count();
        }
    }

    @Test
    @DisplayName("every setting worth choosing between, measured on the golden set's rule and flow cases")
    void measure() throws IOException {
        knowledge.index();
        int embedded = embedAll();
        int passages = jdbc.queryForObject("select count(*) from assistant.knowledge_chunk", Integer.class);
        List<GoldenCase> cases = GoldenSet.load().stream()
                .filter(golden -> !golden.expectRetrieved().isEmpty() || golden.expectNoPassage()).toList();
        assertThat(cases).as("the golden set's rule and flow cases").isNotEmpty();

        List<Score> scores = new ArrayList<>();
        scores.add(score("words alone", new KnowledgeService.Settings(false, 3.0, 10, false, false), cases));
        if (embeddings.available() && embedded > 0) {
            for (double standout : STANDOUTS) {
                for (int depth : DEPTHS) {
                    for (boolean multi : new boolean[] {false, true}) {
                        scores.add(score(name(standout, depth, multi, false),
                                new KnowledgeService.Settings(true, standout, depth, multi, false), cases));
                        if (reranker.available() && (standout == 2.5 || standout == 3.0)) {
                            scores.add(score(name(standout, depth, multi, true),
                                    new KnowledgeService.Settings(true, standout, depth, multi, true), cases));
                        }
                    }
                }
            }
        }
        Path written = write(scores, cases.size(), passages, embedded);
        System.out.println("Retrieval eval: " + scores.size() + " settings, report " + written.toAbsolutePath());
    }

    private static String name(double standout, int depth, boolean multi, boolean rerank) {
        return String.format(Locale.ROOT, "meaning, standout %.1f, depth %d%s%s", standout, depth,
                multi ? ", + question" : "", rerank ? ", + rerank" : "");
    }

    /** Embeds every passage, pass after pass while a pass stores something. @return passages with a vector */
    private int embedAll() {
        if (!embeddings.available()) {
            return 0;
        }
        // Another pass while one stores something: a batch the provider failed for a moment is taken again.
        int passes = 0;
        int stored;
        do {
            stored = knowledge.embed();
        } while (stored > 0 && ++passes < 10);
        return repository.embedded(embeddings.name(), embeddings.dimensions()).size();
    }

    private Score score(String name, KnowledgeService.Settings settings, List<GoldenCase> cases) {
        List<Search> searches = new ArrayList<>();
        for (GoldenCase golden : cases) {
            searches.add(search(golden, "question", golden.question(), null, settings));
            String query = oracleQuery(golden);
            if (query != null) {
                // The question goes with the terms only where the tool asks for it: multi-query on.
                searches.add(search(golden, "oracle", query, settings.multiQuery() ? golden.question() : null,
                        settings));
            }
        }
        List<Search> answerable = searches.stream().filter(search -> !search.golden().expectRetrieved().isEmpty())
                .toList();
        List<Search> unanswerable = searches.stream().filter(search -> search.golden().expectNoPassage()).toList();
        return new Score(name, settings, mean(answerable.stream().mapToDouble(Search::recall).toArray()),
                mean(answerable.stream().mapToDouble(Search::reciprocalRank).toArray()),
                share(unanswerable.stream().filter(Search::byMeaning).count(), unanswerable.size()),
                share(unanswerable.stream().filter(search -> !search.hits().isEmpty()).count(), unanswerable.size()),
                searches);
    }

    private Search search(GoldenCase golden, String variant, String query, String question,
                          KnowledgeService.Settings settings) {
        java.time.Instant before = java.time.Instant.now();
        List<KnowledgeSearch.Hit> hits = knowledge.find(query, question, RESULTS, settings);
        boolean degraded = settings.meaning() && knowledge.degradedUntil().isAfter(before);
        return new Search(golden, variant, hits, degraded);
    }

    /** The query the case's oracle gives {@code searchKnowledge}, if it calls it. */
    private static String oracleQuery(GoldenCase golden) {
        return golden.oracle().stream().filter(step -> step.tool().equals("searchKnowledge"))
                .map(step -> step.arguments().get("query")).filter(Objects::nonNull).map(Object::toString)
                .findFirst().orElse(null);
    }

    private static double mean(double[] values) {
        return values.length == 0 ? 0 : java.util.Arrays.stream(values).average().orElse(0);
    }

    private static double share(long count, int of) {
        return of == 0 ? 0 : (double) count / of;
    }

    private Path write(List<Score> scores, int cases, int passages, int embedded) throws IOException {
        Path folder = Path.of("target", "assistant-eval", "retrieval");
        Files.createDirectories(folder);
        List<Score> best = scores.stream().sorted(Comparator.comparingDouble(Score::recall).reversed()
                .thenComparing(Comparator.comparingDouble(Score::mrr).reversed())
                .thenComparingDouble(Score::meaningNoise)).toList();
        StringBuilder report = new StringBuilder("# Retrieval eval\n\n");
        report.append(String.format(Locale.ROOT, "%d cases, %d passages, %d with a vector of %s; reranker: %s.%n%n",
                cases, passages, embedded, embeddings.available() ? embeddings.name() : "none",
                reranker.available() ? "yes" : "none"));
        report.append("| Setting | recall@5 | MRR | no-rule: by meaning | no-rule: anything | degraded |\n")
                .append("|---|---|---|---|---|---|\n");
        for (Score score : best) {
            report.append(String.format(Locale.ROOT, "| %s | %.3f | %.3f | %.2f | %.2f | %d of %d |%n",
                    score.name(), score.recall(), score.mrr(), score.meaningNoise(), score.anyNoise(),
                    score.degraded(), score.searches().size()));
        }
        report.append("\nA degraded search ran below its setting after a provider failed (paused 2 minutes, "
                + "an hour after a refusal): a row with any is not that setting's measure; run again later.\n");
        StringBuilder csv = new StringBuilder("setting,case,variant,recall,reciprocal_rank,anchors\n");
        for (Score score : scores) {
            for (Search search : score.searches()) {
                csv.append(String.format(Locale.ROOT, "\"%s\",%s,%s,%.3f,%.3f,\"%s\"%n", score.name(),
                        search.golden().id(), search.variant(),
                        search.golden().expectRetrieved().isEmpty() ? 0 : search.recall(),
                        search.golden().expectRetrieved().isEmpty() ? 0 : search.reciprocalRank(),
                        String.join(" | ", search.anchors()).replace("\"", "'")));
            }
        }
        Score first = best.getFirst();
        report.append("\n## Every case under: ").append(first.name()).append("\n\n");
        for (Search search : first.searches()) {
            report.append("- **").append(search.golden().id()).append("** (").append(search.variant()).append(") ")
                    .append(search.golden().expectNoPassage() ? "expects nothing"
                            : "expects " + search.golden().expectRetrieved())
                    .append(": ").append(search.hits().stream()
                            .map(hit -> hit.chunk().anchor() + " [" + hit.foundBy().label() + "]").toList())
                    .append('\n');
        }
        Files.writeString(folder.resolve("report.md"), report, StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("results.csv"), csv, StandardCharsets.UTF_8);
        return folder.resolve("report.md");
    }
}
