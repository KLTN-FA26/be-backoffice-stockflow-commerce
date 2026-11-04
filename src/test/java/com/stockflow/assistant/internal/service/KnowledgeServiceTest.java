package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.EmbeddingModel.Purpose;
import com.stockflow.assistant.internal.domain.KnowledgeChunk;
import com.stockflow.assistant.internal.domain.KnowledgeSearch;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The knowledge search by keyword and by meaning (design §6.4), with a fake embedding model and an in-memory corpus:
 * what is embedded and when, how the two rankings are fused, and that every failure to embed leaves the keyword
 * search exactly as it was.
 */
class KnowledgeServiceTest {

    private static final KnowledgeChunk RETURNS = rule("BR-RMA-002",
            "Custom-printed items are not returnable unless defective.");
    private static final KnowledgeChunk WINDOW = rule("BR-RMA-001",
            "A return can only be opened within the configured return window after delivery.");
    private static final KnowledgeChunk FEFO = rule("BR-STK-006",
            "Allocation picks the lot with the earliest expiry date first (FEFO).");
    private static final KnowledgeChunk IN_TRANSIT = term("In transit", "Goods on their way between two warehouses.");

    /** U6-03 as a small model may search it, untranslated: by keyword it finds "In transit" on the word "in". */
    private static final String QUESTION = "hang in theo yeu cau co duoc tra lai khong";

    private final InMemoryKnowledgeRepository repository = new InMemoryKnowledgeRepository();
    private final FakeEmbeddingModel embeddings = new FakeEmbeddingModel();
    private final MovingClock clock = new MovingClock(Instant.parse("2026-10-29T08:00:00Z"));
    @SuppressWarnings("unchecked")
    private final ObjectProvider<BuildProperties> build = mock(ObjectProvider.class);
    private final KnowledgeService knowledge = service(new TransactionTemplate(mock(PlatformTransactionManager.class)));

    private static KnowledgeChunk rule(String id, String body) {
        return new KnowledgeChunk("05-business-rules", id, KnowledgeChunk.Kind.RULE, id + " — Rules", body);
    }

    private static KnowledgeChunk term(String term, String body) {
        return new KnowledgeChunk("01-ubiquitous-language", term, KnowledgeChunk.Kind.TERM, term, body);
    }

    private static List<KnowledgeChunk> rules(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> rule("BR-TST-%03d".formatted(i), "Rule " + i + "."))
                .toList();
    }

    private KnowledgeService service(TransactionTemplate tx) {
        return new KnowledgeService(repository, embeddings, tx, build, clock);
    }

    /**
     * The corpus, embedded: BR-RMA-002 means what the question means, BR-RMA-001 nearly, and the rest — thirty
     * glossary terms among them, so the spread of similarities is a corpus's — nothing like it.
     */
    private int embeddedCorpus() {
        repository.sync("05-business-rules", List.of(RETURNS, WINDOW, FEFO), null);
        repository.sync("01-ubiquitous-language", Stream.concat(Stream.of(IN_TRANSIT), IntStream.rangeClosed(1, 30)
                .mapToObj(i -> term("Term " + i, "Something else entirely, number " + i + "."))).toList(), null);
        embeddings.vector(RETURNS.title(), 1, 0, 0).vector(WINDOW.title(), 0.8f, 0.6f, 0).vector(QUESTION, 1, 0, 0);
        int corpus = 34;
        assertThat(knowledge.embed()).isEqualTo(corpus);
        return corpus;
    }

    @Test
    @DisplayName("each passage is embedded once; a changed passage alone again; a new model all of them again")
    void embedsOnlyWhatChanged() {
        repository.sync("05-business-rules", List.of(RETURNS, WINDOW, FEFO), null);

        assertThat(knowledge.embed()).isEqualTo(3);
        assertThat(embeddings.texts(Purpose.DOCUMENT)).containsExactly(KnowledgeService.embeddingText(WINDOW),
                KnowledgeService.embeddingText(RETURNS), KnowledgeService.embeddingText(FEFO));
        assertThat(knowledge.embed()).as("nothing changed, nothing is sent").isZero();
        assertThat(embeddings.calls).hasSize(1);

        KnowledgeChunk reworded = rule("BR-RMA-002", "Custom-printed items cannot be returned unless defective.");
        repository.sync("05-business-rules", List.of(reworded, WINDOW, FEFO), null);
        assertThat(knowledge.embed()).isEqualTo(1);
        assertThat(embeddings.calls.getLast()).containsExactly(KnowledgeService.embeddingText(reworded));

        embeddings.name = "fake-embedding-2";
        assertThat(knowledge.embed()).as("vectors of another model do not count").isEqualTo(3);
    }

    @Test
    @DisplayName("the rules are embedded first: they are what answers cite")
    void rulesFirst() {
        KnowledgeChunk orders = new KnowledgeChunk("03-state-machines", "9. Order — order-service",
                KnowledgeChunk.Kind.STATE_MACHINE, "9. Order — order-service (1/12)", "The central machine.");
        repository.sync("03-state-machines", List.of(orders), null);
        repository.sync("01-ubiquitous-language", List.of(IN_TRANSIT), null);
        repository.sync("05-business-rules", List.of(RETURNS), null);

        knowledge.embed();

        assertThat(embeddings.texts(Purpose.DOCUMENT)).containsExactly(KnowledgeService.embeddingText(RETURNS),
                KnowledgeService.embeddingText(IN_TRANSIT), KnowledgeService.embeddingText(orders));
    }

    @Test
    @DisplayName("a batch the provider failed for a moment is skipped, the next ones go on, and another pass is due")
    void aBadMomentSkipsOneBatch() {
        repository.sync("05-business-rules", rules(120), null);
        embeddings.fail(new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "provider down"), call -> call == 2);

        assertThat(knowledge.pass()).isEqualTo(new KnowledgeService.Pass(70, 50, true));
        assertThat(embeddings.calls).extracting(List::size).containsExactly(50, 50, 20);

        embeddings.fail(null, call -> false);
        assertThat(knowledge.pass()).as("the next pass takes what was skipped")
                .isEqualTo(new KnowledgeService.Pass(50, 0, false));
    }

    @Test
    @DisplayName("a spent quota or a refusal stops the pass: what was stored stays, and no pass before the next start")
    void aRefusalStopsThePass() {
        repository.sync("05-business-rules", rules(120), null);
        embeddings.fail(new BusinessException(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED, "spent"), call -> call >= 2);

        assertThat(knowledge.pass()).isEqualTo(new KnowledgeService.Pass(50, 70, false));
        assertThat(embeddings.calls).hasSize(2);

        embeddings.fail(new BusinessException(ErrorCode.ASSISTANT_MISCONFIGURED, "404"), call -> true);
        assertThat(knowledge.pass()).isEqualTo(new KnowledgeService.Pass(0, 70, false));
    }

    @Test
    @DisplayName("a Vietnamese question whose words find only noise still gets the rule that means the same")
    void fusesKeywordAndMeaning() {
        embeddedCorpus();
        repository.ranks(QUESTION, IN_TRANSIT, FEFO);

        List<KnowledgeChunk> found = knowledge.search(QUESTION, 5);

        // In transit and BR-RMA-002 are each first of one ranking (1/61), the keyword hit first; then 1/62 each.
        assertThat(found).containsExactly(IN_TRANSIT, RETURNS, FEFO, WINDOW);
        assertThat(repository.searchLimits).containsExactly(KnowledgeService.FUSION_DEPTH);
        assertThat(embeddings.texts(Purpose.QUERY)).containsExactly(QUESTION);
    }

    @Test
    @DisplayName("with the question searched too, a rule the question means and the terms do not is found by meaning; "
            + "each passage says how it was found")
    void theQuestionIsSearchedToo() {
        embeddedCorpus();
        String terms = "return policy";
        repository.ranks(terms, FEFO);
        KnowledgeService.Settings both = new KnowledgeService.Settings(true, KnowledgeSearch.STANDOUT, 10, true,
                false);
        KnowledgeService.Settings termsOnly = new KnowledgeService.Settings(true, KnowledgeSearch.STANDOUT, 10, false,
                false);

        List<KnowledgeSearch.Hit> found = knowledge.find(terms, QUESTION, 5, both);

        assertThat(found).extracting(KnowledgeSearch.Hit::chunk).containsExactly(FEFO, RETURNS, WINDOW);
        assertThat(found).extracting(KnowledgeSearch.Hit::foundBy).containsExactly(KnowledgeSearch.FoundBy.WORDS,
                KnowledgeSearch.FoundBy.MEANING, KnowledgeSearch.FoundBy.MEANING);
        assertThat(embeddings.texts(Purpose.QUERY)).containsExactly(terms, QUESTION);
        assertThat(knowledge.find(terms, QUESTION, 5, termsOnly)).extracting(KnowledgeSearch.Hit::chunk)
                .containsExactly(FEFO);
        assertThat(knowledge.find(QUESTION, " " + QUESTION, 5, both)).as("the same text is embedded once")
                .isNotEmpty();
        assertThat(embeddings.texts(Purpose.QUERY)).containsExactly(terms, QUESTION);
    }

    @Test
    @DisplayName("the reranker reorders the fused passages, the named rule kept first; asked with the question when "
            + "there is one")
    void theRerankerReorders() {
        embeddedCorpus();
        String named = "what does BR-STK-006 say";
        embeddings.vector(named, 1, 0, 0);
        repository.ranks(named, WINDOW, FEFO);
        FakeReranker reranker = new FakeReranker();
        KnowledgeService reranking = new KnowledgeService(repository, embeddings,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), build, clock, AssistantTelemetry.NONE,
                KnowledgeProperties.DEFAULTS, reranker);

        // Fused: BR-STK-006 (named), BR-RMA-001 (both), BR-RMA-002 (meaning); the fake reverses all but the named.
        assertThat(reranking.find(named, null, 5)).extracting(KnowledgeSearch.Hit::chunk)
                .containsExactly(FEFO, RETURNS, WINDOW);
        assertThat(reranker.queries).containsExactly(named);
        assertThat(reranker.texts.getFirst()).hasSize(2).first().asString().startsWith(WINDOW.title() + "\n");
        assertThat(repository.searchLimits.getLast()).as("enough fused passages for the reranker to choose from")
                .isEqualTo(KnowledgeProperties.DEFAULTS.reranker().depth());

        reranking.find(named, "Quy định BR-STK-006 nói gì?", 5);
        assertThat(reranker.queries.getLast()).isEqualTo("Quy định BR-STK-006 nói gì?");
    }

    @Test
    @DisplayName("a reranker that fails leaves the fused order, and is not asked again for a while")
    void aFailingRerankerLeavesTheFusedOrder() {
        embeddedCorpus();
        String named = "what does BR-STK-006 say";
        embeddings.vector(named, 1, 0, 0);
        repository.ranks(named, WINDOW, FEFO);
        FakeReranker reranker = new FakeReranker();
        reranker.failure = new IllegalStateException("connection refused");
        KnowledgeService reranking = new KnowledgeService(repository, embeddings,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), build, clock, AssistantTelemetry.NONE,
                KnowledgeProperties.DEFAULTS, reranker);

        assertThat(reranking.find(named, null, 5)).extracting(KnowledgeSearch.Hit::chunk)
                .containsExactly(FEFO, WINDOW, RETURNS);
        reranking.find(named, null, 5);
        assertThat(reranker.queries).as("paused after the failure").hasSize(1);

        clock.advance(KnowledgeService.PAUSE_AFTER_FAILURE.plusSeconds(1));
        reranker.failure = null;
        assertThat(reranking.find(named, null, 5)).extracting(KnowledgeSearch.Hit::chunk)
                .containsExactly(FEFO, RETURNS, WINDOW);
    }

    @Test
    @DisplayName("with the flows switched off, their document is synced empty: indexed before, their passages go")
    void flowsOff() {
        KnowledgeChunk flow = new KnowledgeChunk(KnowledgeService.FLOWS, "F-PO", KnowledgeChunk.Kind.FLOW,
                "F-PO — Đơn mua hàng", "Từ nháp tới lúc nhà cung cấp xác nhận.");
        repository.sync(KnowledgeService.FLOWS, List.of(flow), null);

        knowledge.index();

        assertThat(repository.chunks(KnowledgeService.FLOWS)).as("off by default").isEmpty();
        KnowledgeService flowsOn = new KnowledgeService(repository, embeddings,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), build, clock, AssistantTelemetry.NONE,
                new KnowledgeProperties(3.0, 10, false, KnowledgeProperties.DEFAULTS.reranker(), true),
                com.stockflow.assistant.internal.domain.Reranker.NONE);
        flowsOn.index();
        assertThat(repository.chunks(KnowledgeService.FLOWS)).extracting(KnowledgeChunk::anchor).contains("F-PO",
                "X-SHORT");
    }

    /** Reverses what it is given, or fails. */
    private static final class FakeReranker implements com.stockflow.assistant.internal.domain.Reranker {

        final List<String> queries = new java.util.ArrayList<>();
        final List<List<String>> texts = new java.util.ArrayList<>();
        RuntimeException failure;

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public List<Integer> rank(String query, List<String> passages) {
            queries.add(query);
            texts.add(passages);
            if (failure != null) {
                throw failure;
            }
            return IntStream.range(0, passages.size()).map(i -> passages.size() - 1 - i).boxed().toList();
        }
    }

    @Test
    @DisplayName("a question nothing answers gets nothing by meaning: an empty search stays empty")
    void nothingStandsOut() {
        repository.sync("01-ubiquitous-language", IntStream.range(0, 40)
                .mapToObj(i -> term("Term " + i, "Passage " + i + ".")).toList(), null);
        // Similarities from 0.40 to 0.50, as unrelated texts have; the question names nothing.
        for (int i = 0; i < 40; i++) {
            float similarity = 0.40f + 0.0025f * i;
            embeddings.vector("Term " + i + "\n", similarity, (float) Math.sqrt(1 - similarity * similarity), 0);
        }
        embeddings.vector("annual leave", 1, 0, 0);
        knowledge.embed();

        assertThat(knowledge.search("annual leave days per year", 5)).isEmpty();
    }

    @Test
    @DisplayName("a rule the question names comes first, whatever the fusion says")
    void aNamedRuleStaysFirst() {
        embeddedCorpus();
        String named = "what does BR-STK-006 say";
        embeddings.vector(named, 1, 0, 0);
        repository.ranks(named, WINDOW, FEFO);

        // Fused alone: BR-RMA-001 (both rankings), BR-RMA-002, then BR-STK-006.
        assertThat(knowledge.search(named, 5)).containsExactly(FEFO, WINDOW, RETURNS);
    }

    @Test
    @DisplayName("any failure to embed the question searches by keyword alone, exactly as without embeddings")
    void anEmbeddingFailureSearchesByKeyword() {
        embeddedCorpus();
        repository.ranks(QUESTION, IN_TRANSIT, FEFO);
        for (RuntimeException failure : List.of(new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "down"),
                new BusinessException(ErrorCode.ASSISTANT_QUOTA_EXHAUSTED, "spent"),
                new IllegalStateException("a bug"))) {
            int before = embeddings.calls.size();
            embeddings.fail(failure, call -> call > before);
            repository.searchLimits.clear();

            assertThat(knowledge.search(QUESTION, 5)).as(failure.getMessage()).containsExactly(IN_TRANSIT, FEFO);
            assertThat(repository.searchLimits).as("the search of old, with its own limit").containsExactly(5);
            assertThat(embeddings.calls).as("the provider was asked").hasSize(before + 1);
            clock.advance(KnowledgeService.PAUSE_AFTER_REFUSAL.plusSeconds(1));
        }
    }

    @Test
    @DisplayName("after a failure questions go unembedded for a while: one wait and one log line, not one per search")
    void aFailurePausesEmbedding() {
        embeddedCorpus();
        repository.ranks(QUESTION, IN_TRANSIT, FEFO);
        repository.ranks("another question", FEFO);
        int before = embeddings.calls.size();
        embeddings.fail(new BusinessException(ErrorCode.ASSISTANT_UNAVAILABLE, "timeout"), call -> true);

        assertThat(knowledge.search(QUESTION, 5)).containsExactly(IN_TRANSIT, FEFO);
        assertThat(knowledge.search("another question", 5)).containsExactly(FEFO);
        assertThat(embeddings.calls).as("the second search waited for nothing").hasSize(before + 1);

        clock.advance(KnowledgeService.PAUSE_AFTER_FAILURE.plusSeconds(1));
        embeddings.fail(null, call -> false);
        assertThat(knowledge.search(QUESTION, 5)).contains(RETURNS);
        assertThat(embeddings.calls).hasSize(before + 2);
    }

    @Test
    @DisplayName("a refusal pauses questions for an hour; a remembered question still searches by meaning meanwhile")
    void aRefusalPausesLonger() {
        embeddedCorpus();
        repository.ranks(QUESTION, IN_TRANSIT, FEFO);
        knowledge.search(QUESTION, 5);
        int before = embeddings.calls.size();
        embeddings.fail(new BusinessException(ErrorCode.ASSISTANT_MISCONFIGURED, "404"), call -> true);

        knowledge.search("another question", 5);
        clock.advance(Duration.ofMinutes(30));
        knowledge.search("another question", 5);
        assertThat(embeddings.calls).hasSize(before + 1);
        assertThat(knowledge.search(QUESTION, 5)).as("remembered, so no call").contains(RETURNS);

        clock.advance(Duration.ofMinutes(31));
        knowledge.search("another question", 5);
        assertThat(embeddings.calls).hasSize(before + 2);
    }

    @Test
    @DisplayName("a question is embedded once, however it is spaced; one with nothing to search for not at all")
    void aQuestionIsEmbeddedOnce() {
        embeddedCorpus();

        knowledge.search(QUESTION, 5);
        knowledge.search("  " + QUESTION.replace(" ", "   ") + " ", 5);
        assertThat(knowledge.search("!!! ??", 5)).isEmpty();

        assertThat(embeddings.texts(Purpose.QUERY)).containsExactly(QUESTION);
    }

    @Test
    @DisplayName("the question is embedded before a transaction opens: no connection waits on the provider")
    void noConnectionWaitsOnTheProvider() {
        embeddedCorpus();
        List<String> events = new ArrayList<>();
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> {
            events.add("transaction");
            return null;
        });
        embeddings.onCall = () -> events.add("embedding");

        service(new TransactionTemplate(manager)).search(QUESTION, 5);

        assertThat(events).containsExactly("embedding", "transaction");
    }

    @Test
    @DisplayName("without embeddings nothing is sent, and the search is the keyword search of old")
    void withoutEmbeddingsTheSearchIsKeywordOnly() {
        embeddings.available = false;
        repository.sync("05-business-rules", List.of(RETURNS, WINDOW, FEFO), null);
        repository.ranks(QUESTION, IN_TRANSIT, FEFO);

        assertThat(knowledge.embed()).isZero();
        assertThat(knowledge.search(QUESTION, 5)).containsExactly(IN_TRANSIT, FEFO);
        assertThat(repository.searchLimits).containsExactly(5);
        assertThat(embeddings.calls).isEmpty();
    }

    @Test
    @DisplayName("before any passage is embedded, the keyword order stands")
    void nothingEmbeddedYet() {
        repository.ranks(QUESTION, IN_TRANSIT, FEFO, WINDOW);

        assertThat(knowledge.search(QUESTION, 2)).containsExactly(IN_TRANSIT, FEFO);
    }

    @Test
    @DisplayName("a passage is embedded as its title and body, the text its content hash covers, cut at the limit")
    void embeddingText() {
        assertThat(KnowledgeService.embeddingText(RETURNS))
                .isEqualTo("BR-RMA-002 — Rules\nCustom-printed items are not returnable unless defective.");
        assertThat(KnowledgeService.embeddingText(rule("BR-TST-001", "x".repeat(10_000))))
                .hasSize(KnowledgeService.MAX_EMBEDDED_TEXT);
    }

    @Test
    @DisplayName("start-up does not wait for the provider: the passages are sent from a thread of their own")
    void startUpDoesNotWait() throws InterruptedException {
        // A source of its own, so something is pending whatever the documents on the classpath hold.
        repository.sync("start-up-test", List.of(new KnowledgeChunk("start-up-test", "T-1", KnowledgeChunk.Kind.TERM,
                "T-1", "A passage to embed.")), null);
        Thread startUp = Thread.currentThread();
        AtomicReference<Thread> caller = new AtomicReference<>();
        CountDownLatch called = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        embeddings.onCall = () -> {
            caller.compareAndSet(null, Thread.currentThread());
            called.countDown();
            if (Thread.currentThread() != startUp) {
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        try {
            knowledge.indexOnStartup();

            assertThat(called.await(10, TimeUnit.SECONDS)).as("the provider is called").isTrue();
            assertThat(caller.get()).as("not by the start-up thread").isNotSameAs(startUp);
            assertThat(caller.get().getName()).isEqualTo("assistant-embeddings");
        } finally {
            release.countDown();
            knowledge.stopEmbedding();
        }
    }

    /** A clock that stands still until the test moves it. */
    private static final class MovingClock extends Clock {

        private Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
