package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.EmbeddingModel;
import com.stockflow.assistant.internal.domain.KnowledgeChunk;
import com.stockflow.assistant.internal.domain.KnowledgeParser;
import com.stockflow.assistant.internal.domain.KnowledgeRepository;
import com.stockflow.assistant.internal.domain.KnowledgeSearch;
import com.stockflow.assistant.internal.domain.Reranker;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The retrieval corpus: indexed on every start from the kltn-docs business analysis, searched by
 * {@code SearchKnowledgeTool} (design §6).
 *
 * <p><b>Indexed on start, not by a job.</b> {@code tools/sync_kltn_docs.py} copies the chapters the assistant searches
 * into {@code docs/kltn-snapshot/}, and the build copies them onto the classpath ({@code classpath:knowledge/}), so the
 * index follows them on every deploy and nobody has to remember to re-index. Every document its {@code index.txt} lists
 * is indexed — the glossary by {@link KnowledgeParser#glossary}, a chapter ({@code 15-payment}) by
 * {@link KnowledgeParser#chapter} — and nothing else under {@code knowledge/}, where an earlier build may have left the
 * documents this corpus replaced.
 * Unchanged chunks are not rewritten (content hash), so a normal start writes nothing.</p>
 *
 * <p><b>Never a reason not to start, never a stale rule.</b> A failed sync is logged per document, and the others
 * are still synced. A source holding chunks that this build does not index (a chapter dropped from the snapshot, the
 * {@code docs/business-design} documents the snapshot replaced) is synced empty rather than left to be cited forever —
 * but not when the build finds no document at all, which is a broken build, not an empty corpus. A document a newer
 * build indexed last is left alone: an older instance starting after it must not put the older rules back
 * ({@code assistant.knowledge_source}).</p>
 *
 * <p><b>Searched by words and by meaning</b> (design §6.4). The analysis is Vietnamese, and the staff ask in
 * Vietnamese, with accents or without, in words a rule does not use: "khách nhận hàng hai tháng rồi mới đòi trả" shares
 * few words with the return window. With an {@link EmbeddingModel} available, a search ranks the passages by keyword as
 * before and by meaning — the passages that stand out in cosine similarity to the question's vector
 * ({@link KnowledgeSearch#nearest}) — the best {@value #FUSION_DEPTH} of each, and fuses the two rankings
 * ({@link KnowledgeSearch#fuse}). Ten, not fifty: the fifty nearest are a large share of the corpus, and a keyword hit
 * on a common word would often collect a second share from them and push the passage that means the question out of
 * the five shown. The question is embedded outside any transaction, so no database connection waits on the provider,
 * and once: the last {@value #REMEMBERED_QUESTIONS} vectors are kept.</p>
 *
 * <p><b>What the search may add, each off until the retrieval eval shows it helps</b> ({@link KnowledgeProperties},
 * design §10.2): the user's question embedded beside the search terms, a third ranking in the fusion; and a reranker
 * reading the fused passages with the question ({@link Reranker}), the named rules kept first. A reranker that fails or
 * is slow leaves the fused order, and is not asked again for {@link #PAUSE_AFTER_FAILURE}. Each passage says how it was
 * found ({@link KnowledgeSearch.FoundBy}).</p>
 *
 * <p><b>Any failure to embed the question searches by keyword alone</b>, exactly as without embeddings, and pauses
 * embedding questions — {@link #PAUSE_AFTER_FAILURE} after a failure that may pass (a 5xx, a timeout, the per-minute
 * quota), {@link #PAUSE_AFTER_REFUSAL} after one that will not (a spent daily quota, a refused key or model) — so a
 * provider that hangs or refuses costs one wait and one log line, not one per search.</p>
 *
 * <p><b>Vectors in a plain {@code real[]}, compared here, not in pgvector.</b> A search reads every current vector,
 * about 900 × 768 numbers, in a few milliseconds, and compares them in about one; the question's own embedding call
 * costs more than both. pgvector (an HNSW index, the database returning only the nearest passages) is worth its price —
 * another postgres image in both compose files, the Testcontainers support and the migration-upgrade tests — once the
 * corpus passes a few thousand passages (the read grows with it: some 15 MB a search at 5,000), or reading the vectors
 * reaches tens of milliseconds of a search. The column then converts in place ({@code V20270120000600}).</p>
 *
 * <p><b>Embedded after the keyword index, off the start-up thread.</b> Only passages whose text, model or length
 * changed are sent, the rules first (they are what answers cite), in batches of {@value #EMBEDDING_BATCH}, each stored
 * as it comes back. A batch the provider failed for a moment is skipped and the pass goes on; while one leaves passages
 * behind that way, another pass follows {@link #RETRY_PASS_AFTER} later, {@value #EMBEDDING_PASSES} passes in all. A
 * spent quota or a refused key stops the pass: the passages left wait for the next start, found by keyword until then.
 * The start-up thread is not held, because Spring reports the application ready for traffic only once every ready
 * listener has returned, and the free tier's per-minute quota can make the provider ask for a minute's wait. Two
 * instances starting together may both embed the same passages: no lock is held across the provider's calls, and the
 * second write stores the same vector, at the cost of some quota.</p>
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    /**
     * The documents of the corpus, one name a line: written by {@code tools/sync_kltn_docs.py} beside the snapshot
     * ({@code docs/kltn-snapshot}), copied onto the classpath by the build ({@code pom.xml}).
     */
    static final String INDEX = "knowledge/index.txt";

    /** A chapter of the analysis, by its snapshot name: {@code 15-payment}. */
    private static final Pattern CHAPTER = Pattern.compile("\\d{2}-[a-z0-9-]+");

    /** How many of each ranking take part in the fusion, unless {@link KnowledgeProperties#fusionDepth} says. */
    static final int FUSION_DEPTH = 10;

    /** The longest text of a passage the reranker reads, title first: a few hundred tokens, as it was trained on. */
    static final int RERANKED_TEXT = 2_000;

    /** Passages per embedding call: a few thousand tokens, well inside the free tier's per-minute token quota. */
    static final int EMBEDDING_BATCH = 50;

    /** Embedding passes at most after a start: the first, and the ones that follow a provider's bad moment. */
    static final int EMBEDDING_PASSES = 4;

    /** Between those passes: past a provider's bad minutes, and soon enough to matter on the same day. */
    static final Duration RETRY_PASS_AFTER = Duration.ofMinutes(10);

    /** Questions are not embedded for this long after the provider failed in a way that may pass. */
    static final Duration PAUSE_AFTER_FAILURE = Duration.ofMinutes(2);

    /** Nor for this long after it refused (a spent daily quota, the key, the model): one call an hour sees the fix. */
    static final Duration PAUSE_AFTER_REFUSAL = Duration.ofHours(1);

    /** Question vectors kept: the same search terms recur, within an answer and across people asking alike. */
    static final int REMEMBERED_QUESTIONS = 500;

    /**
     * The longest text embedded, title included. Passages are at most {@value KnowledgeParser#MAX_PASSAGE} characters
     * today; this only keeps one long rule from failing its whole batch at every start against the model's input
     * limit (2,048 tokens for {@code gemini-embedding-001}). The keyword index and the text shown are never cut.
     */
    static final int MAX_EMBEDDED_TEXT = 6_000;

    /** The rules first, which answers cite; then the glossary, the state machines, and the flows last. */
    private static final Comparator<KnowledgeChunk> EMBEDDING_ORDER = Comparator.comparing(KnowledgeChunk::kind)
            .thenComparing(KnowledgeChunk::source)
            .thenComparing(KnowledgeChunk::anchor);

    /** A document of the corpus: its snapshot name ({@code 15-payment}, {@code glossary}) and its markdown. */
    record Document(String name, String markdown) {
    }

    /**
     * What one embedding pass did.
     *
     * @param stored vectors stored
     * @param left   passages still without a vector of the current model and text
     * @param again  whether a later pass may embed what this one could not: it skipped a batch the provider failed
     *               for a moment, or something else went wrong (the database). Never after a spent quota or a refusal
     */
    record Pass(int stored, int left, boolean again) {
    }

    /**
     * How one search ranks: {@link KnowledgeProperties} for the assistant, a grid of them for the retrieval eval.
     *
     * @param meaning          search by meaning too, when the texts can be embedded; false for words alone
     * @param rerank           ask the reranker, when there is one
     * @param minMatchedWords  how many of the query's words a passage found by its words must hold
     *                         ({@link KnowledgeRepository#search})
     */
    public record Settings(boolean meaning, double standout, int fusionDepth, boolean multiQuery, boolean rerank,
                           int minMatchedWords) {

        /** A passage found by any one of the query's words: the search before the threshold. */
        public Settings(boolean meaning, double standout, int fusionDepth, boolean multiQuery, boolean rerank) {
            this(meaning, standout, fusionDepth, multiQuery, rerank, 1);
        }
    }

    private final AssistantTelemetry telemetry;
    private final KnowledgeProperties properties;
    private final Reranker reranker;
    private final KnowledgeRepository knowledge;
    private final EmbeddingModel embeddings;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnly;
    private final ObjectProvider<BuildProperties> build;
    private final Clock clock;
    private final ScheduledExecutorService background =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("assistant-embeddings").factory());
    private final Map<String, float[]> questions = Collections.synchronizedMap(
            new LinkedHashMap<String, float[]>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                    return size() > REMEMBERED_QUESTIONS;
                }
            });
    /** Questions are searched by keyword alone until then, after the provider failed. */
    private volatile Instant questionsPausedUntil = Instant.MIN;
    /** The fused order stands until then, after the reranker failed. */
    private volatile Instant rerankPausedUntil = Instant.MIN;

    KnowledgeService(KnowledgeRepository knowledge, EmbeddingModel embeddings, TransactionTemplate tx,
                     ObjectProvider<BuildProperties> build, Clock clock) {
        this(knowledge, embeddings, tx, build, clock, AssistantTelemetry.NONE, KnowledgeProperties.DEFAULTS,
                Reranker.NONE);
    }

    /**
     * @param telemetry  where each search's span, with what each ranking found, is recorded
     * @param properties how the search ranks
     * @param reranker   reads the fused passages with the question; {@link Reranker#NONE} for none
     */
    @Autowired
    KnowledgeService(KnowledgeRepository knowledge, EmbeddingModel embeddings, TransactionTemplate tx,
                     ObjectProvider<BuildProperties> build, Clock clock, AssistantTelemetry telemetry,
                     KnowledgeProperties properties, Reranker reranker) {
        this.telemetry = telemetry;
        this.properties = properties;
        this.reranker = reranker;
        this.knowledge = knowledge;
        this.embeddings = embeddings;
        this.tx = tx;
        this.readOnly = new TransactionTemplate(tx.getTransactionManager());
        this.readOnly.setReadOnly(true);
        this.build = build;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void indexOnStartup() {
        try {
            int changes = index();
            log.info("Assistant knowledge index: {} chunk change(s)", changes);
        } catch (RuntimeException failed) {
            log.error("The assistant's knowledge index could not be refreshed; it answers from the previous one",
                    failed);
        }
        if (embeddings.available()) {
            background.execute(() -> embedInBackground(1));
        }
    }

    @PreDestroy
    void stopEmbedding() {
        background.shutdownNow();
    }

    /**
     * Syncs every document of this build, each in its own transaction, then empties every other source still holding
     * chunks. @return rows written or deleted
     */
    public int index() {
        return index(documents());
    }

    int index(List<Document> documents) {
        if (documents.isEmpty()) {
            log.error("No knowledge document in this build ({}): the assistant's knowledge index is left as it is",
                    INDEX);
            return 0;
        }
        BuildProperties info = build.getIfAvailable();
        Instant builtAt = info == null ? null : info.getTime();
        int changes = 0;
        Set<String> indexed = new HashSet<>();
        for (Document document : documents) {
            indexed.add(document.name());
            try {
                changes += sync(document.name(), parse(document), builtAt);
            } catch (RuntimeException failed) {
                log.error("The assistant could not index {}; it answers from that document's previous index",
                        document.name(), failed);
            }
        }
        List<String> stored;
        try {
            stored = readOnly.execute(status -> knowledge.sources());
        } catch (RuntimeException unreadable) {
            log.error("The assistant could not read which documents are indexed; none is retired", unreadable);
            return changes;
        }
        for (String source : stored == null ? List.<String>of() : stored) {
            if (!indexed.contains(source)) {
                try {
                    int removed = sync(source, List.of(), builtAt);
                    if (removed > 0) {
                        log.info("{} is not in this build's corpus: {} passage(s) removed", source, removed);
                    }
                } catch (RuntimeException failed) {
                    log.error("The assistant could not remove {} from its index", source, failed);
                }
            }
        }
        return changes;
    }

    /**
     * The passages of {@code document}: the glossary's or a chapter's, without the flows (§4) unless
     * {@link KnowledgeProperties#flows} is on. Switched off, a chapter's flow passages are deleted on the next start.
     */
    List<KnowledgeChunk> parse(Document document) {
        List<KnowledgeChunk> chunks;
        if (document.name().equals(KnowledgeParser.GLOSSARY)) {
            chunks = KnowledgeParser.glossary(document.name(), document.markdown());
        } else if (CHAPTER.matcher(document.name()).matches()) {
            chunks = KnowledgeParser.chapter(document.name(), document.markdown());
        } else {
            log.warn("{} is neither the glossary nor a chapter (NN-name): not indexed", document.name());
            return List.of();
        }
        return properties.flows() ? chunks
                : chunks.stream().filter(chunk -> chunk.kind() != KnowledgeChunk.Kind.FLOW).toList();
    }

    private int sync(String source, List<KnowledgeChunk> chunks, Instant builtAt) {
        KnowledgeRepository.Sync sync = tx.execute(status -> knowledge.sync(source, chunks, builtAt));
        if (sync == null) {
            return 0;
        }
        if (sync.newerIndexed()) {
            log.warn("{} was indexed by a newer build than this one ({}); left as it is", source, builtAt);
        }
        return sync.changes();
    }

    /** Pass {@code number} of this start, and the next one later while it may embed what this one could not. */
    private void embedInBackground(int number) {
        Pass pass = pass();
        if (pass.again() && number < EMBEDDING_PASSES) {
            log.info("Assistant knowledge embeddings: {} passage(s) left, another pass in {}", pass.left(),
                    RETRY_PASS_AFTER);
            try {
                background.schedule(() -> embedInBackground(number + 1), RETRY_PASS_AFTER.toSeconds(),
                        TimeUnit.SECONDS);
            } catch (RejectedExecutionException stopping) {
                // The application is shutting down; the next start embeds them.
            }
        }
    }

    /**
     * Embeds every passage without a vector of the current model made from its current text. Never throws: a failure
     * is logged, and what was stored stays. Each call to the provider is made outside any transaction.
     *
     * @return vectors stored; zero when embeddings are not available or nothing changed
     */
    public int embed() {
        return pass().stored();
    }

    /** One pass of {@link #embed}: the rules first, in batches, a batch the provider failed for a moment skipped. */
    Pass pass() {
        if (!embeddings.available()) {
            return new Pass(0, 0, false);
        }
        List<KnowledgeChunk> chunks;
        try {
            List<KnowledgeChunk> pending = readOnly.execute(status ->
                    knowledge.withoutEmbedding(embeddings.name(), embeddings.dimensions()));
            chunks = pending == null ? List.of() : pending.stream().sorted(EMBEDDING_ORDER).toList();
        } catch (RuntimeException unreadable) {
            log.error("The assistant could not read which passages to embed; they are found by keyword meanwhile",
                    unreadable);
            return new Pass(0, 0, true);
        }
        int stored = 0;
        boolean skipped = false;
        for (int from = 0; from < chunks.size(); from += EMBEDDING_BATCH) {
            List<KnowledgeChunk> batch = chunks.subList(from, Math.min(from + EMBEDDING_BATCH, chunks.size()));
            try {
                List<float[]> vectors = embeddings.embed(batch.stream().map(KnowledgeService::embeddingText).toList(),
                        EmbeddingModel.Purpose.DOCUMENT);
                if (vectors.size() != batch.size()) {
                    throw new IllegalStateException(vectors.size() + " vectors for " + batch.size() + " passages");
                }
                Integer saved = tx.execute(status -> save(batch, vectors));
                stored += saved == null ? 0 : saved;
            } catch (BusinessException provider) {
                if (provider.errorCode() != ErrorCode.ASSISTANT_UNAVAILABLE || Thread.currentThread().isInterrupted()) {
                    return stopped(stored, chunks.size(), provider);
                }
                // A bad moment of the provider's, after the adapter's own retries (logged there): the next batch may
                // go through, and a later pass takes this one again.
                skipped = true;
            } catch (RuntimeException failed) {
                return stopped(stored, chunks.size(), failed);
            }
        }
        if (!chunks.isEmpty()) {
            log.info("Assistant knowledge embeddings: {} of {} passage(s) embedded with {}", stored, chunks.size(),
                    embeddings.name());
        }
        return new Pass(stored, chunks.size() - stored, skipped);
    }

    private Pass stopped(int stored, int pending, RuntimeException failed) {
        int left = pending - stored;
        if (Thread.currentThread().isInterrupted()) {
            log.info("Assistant knowledge embeddings stopped by shutdown after {} of {} passage(s)", stored, pending);
            return new Pass(stored, left, false);
        }
        if (failed instanceof BusinessException provider) {
            // A spent quota, a refused key or model: the adapter has logged what the provider said, and no pass
            // before the next start would fare better.
            log.warn("The assistant embedded {} of {} passage(s) with {} ({}); the rest are embedded on the next start "
                    + "and found by keyword until then", stored, pending, embeddings.name(), provider.errorCode());
            return new Pass(stored, left, false);
        }
        log.error("The assistant embedded {} of {} passage(s) with {}; the rest are found by keyword until a later "
                + "pass", stored, pending, embeddings.name(), failed);
        return new Pass(stored, left, true);
    }

    private int save(List<KnowledgeChunk> batch, List<float[]> vectors) {
        int saved = 0;
        for (int i = 0; i < batch.size(); i++) {
            if (knowledge.saveEmbedding(batch.get(i), embeddings.name(), vectors.get(i))) {
                saved++;
            }
        }
        return saved;
    }

    /** {@link #find} without the user's question, for the passages alone. */
    public List<KnowledgeChunk> search(String query, int limit) {
        return find(query, null, limit).stream().map(KnowledgeSearch.Hit::chunk).toList();
    }

    /** {@link #find(String, String, int, Settings)} as the assistant is configured. */
    public List<KnowledgeSearch.Hit> find(String query, String question, int limit) {
        return find(query, question, limit, new Settings(true, properties.standout(), properties.fusionDepth(),
                properties.multiQuery(), reranker.available(), properties.minMatchedWords()));
    }

    /**
     * The passages for {@code query}, best first, at most {@code limit}, each with how it was found: by keyword, fused
     * with the passages that stand out in meaning when the text can be embedded — the search terms, and with
     * {@link Settings#multiQuery} the user's {@code question} too — then, with {@link Settings#rerank}, reordered by
     * the reranker. Not one transaction: every text is embedded first and the reranker asked after, without a
     * database connection held for a provider's answer.
     *
     * @param question the user's question as they wrote it; null or blank for none
     */
    public List<KnowledgeSearch.Hit> find(String query, String question, int limit, Settings settings) {
        return telemetry.retrieval(() -> {
            float[] byTerms = settings.meaning() ? questionVector(query) : null;
            float[] byQuestion = settings.meaning() && settings.multiQuery() && question != null && !question.isBlank()
                    && !plain(question).equalsIgnoreCase(plain(query)) ? questionVector(question) : null;
            boolean rerank = settings.rerank() && reranker.available()
                    && !clock.instant().isBefore(rerankPausedUntil);
            int kept = rerank ? Math.max(limit, properties.reranker().depth()) : limit;
            List<KnowledgeSearch.Hit> fused = readOnly.execute(status ->
                    fused(query, byTerms, byQuestion, kept, settings));
            List<KnowledgeSearch.Hit> passages = fused == null ? List.of()
                    : rerank ? reranked(question == null || question.isBlank() ? query : question, fused, limit)
                    : fused;
            telemetry.retrievalFound("returned", passages.size());
            return passages;
        });
    }

    private List<KnowledgeSearch.Hit> fused(String query, float[] byTerms, float[] byQuestion, int kept,
                                            Settings settings) {
        // By keyword alone, the search of old with its own limit: there is nothing to fuse it with.
        boolean meaning = byTerms != null || byQuestion != null;
        // Each ranking as deep as the passages kept: a reranker choosing among twenty gets twenty of each.
        int depth = meaning ? Math.max(kept, settings.fusionDepth()) : kept;
        List<KnowledgeChunk> byWords = knowledge.search(query, depth, settings.minMatchedWords());
        telemetry.retrievalFound("words", byWords.size());
        List<List<KnowledgeChunk>> byMeaning = new ArrayList<>();
        if (meaning) {
            List<KnowledgeRepository.EmbeddedChunk> corpus = knowledge.embedded(embeddings.name(),
                    embeddings.dimensions());
            for (float[] vector : new float[][] {byTerms, byQuestion}) {
                if (vector != null) {
                    byMeaning.add(KnowledgeSearch.nearest(vector, corpus, depth, settings.standout()));
                }
            }
            telemetry.retrievalFound("meaning", byMeaning.stream().mapToInt(List::size).sum());
        }
        return KnowledgeSearch.fuseAll(byWords, byMeaning, KnowledgeSearch.namedRules(query), kept);
    }

    /**
     * The fused passages reordered by the reranker, the ones the query names kept first, cut to {@code limit}. A
     * reranker that fails or is slow leaves the fused order, and is left alone for {@link #PAUSE_AFTER_FAILURE}.
     */
    private List<KnowledgeSearch.Hit> reranked(String text, List<KnowledgeSearch.Hit> fused, int limit) {
        List<KnowledgeSearch.Hit> named = fused.stream()
                .filter(hit -> hit.foundBy() == KnowledgeSearch.FoundBy.NAMED).toList();
        List<KnowledgeSearch.Hit> rest = fused.stream()
                .filter(hit -> hit.foundBy() != KnowledgeSearch.FoundBy.NAMED).toList();
        if (rest.size() < 2 || named.size() >= limit) {
            return fused.stream().limit(limit).toList();
        }
        try {
            List<Integer> order = reranker.rank(text, rest.stream().map(hit -> rerankedText(hit.chunk())).toList());
            List<KnowledgeSearch.Hit> result = new ArrayList<>(named);
            order.forEach(index -> result.add(rest.get(index)));
            telemetry.retrievalFound("reranked", rest.size());
            return result.stream().limit(limit).toList();
        } catch (RuntimeException failed) {
            rerankPausedUntil = clock.instant().plus(PAUSE_AFTER_FAILURE);
            log.warn("The knowledge search keeps its fused order for {}: the reranker failed ({})",
                    PAUSE_AFTER_FAILURE, reason(failed));
            return fused.stream().limit(limit).toList();
        }
    }

    /**
     * Until when a search runs below its settings: the question not embedded after the provider failed, or the
     * reranker not asked after it failed. The retrieval eval reports the searches it measured so.
     */
    public Instant degradedUntil() {
        Instant questionsPaused = questionsPausedUntil;
        Instant rerankPaused = rerankPausedUntil;
        return questionsPaused.isAfter(rerankPaused) ? questionsPaused : rerankPaused;
    }

    private static String rerankedText(KnowledgeChunk chunk) {
        String text = chunk.title() + "\n" + chunk.body();
        return text.length() > RERANKED_TEXT ? text.substring(0, RERANKED_TEXT) : text;
    }

    private static String plain(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
    }

    /**
     * The question's vector, remembered; null when the search is keyword-only: no embeddings, nothing to search for,
     * questions paused after a failure, or the provider failed now (which starts a pause).
     */
    private float[] questionVector(String query) {
        if (!embeddings.available() || KnowledgeSearch.words(query).isEmpty()) {
            return null;
        }
        String text = Normalizer.normalize(query, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
        float[] remembered = questions.get(text);
        if (remembered != null) {
            return remembered;
        }
        Instant now = clock.instant();
        if (now.isBefore(questionsPausedUntil)) {
            return null;
        }
        try {
            float[] vector = embeddings.embed(List.of(text), EmbeddingModel.Purpose.QUERY).getFirst();
            if (vector.length != embeddings.dimensions()) {
                throw new IllegalStateException("a vector of " + vector.length + " dimensions, not "
                        + embeddings.dimensions());
            }
            questions.put(text, vector);
            return vector;
        } catch (RuntimeException failed) {
            boolean refused = failed instanceof BusinessException provider
                    && provider.errorCode() != ErrorCode.ASSISTANT_UNAVAILABLE;
            Duration pause = refused ? PAUSE_AFTER_REFUSAL : PAUSE_AFTER_FAILURE;
            questionsPausedUntil = now.plus(pause);
            log.warn("The knowledge search goes by keyword alone for {}: the question could not be embedded ({})",
                    pause, reason(failed));
            return null;
        }
    }

    /** What a passage is embedded as: the text its content hash covers, title first, cut at the model's limit. */
    static String embeddingText(KnowledgeChunk chunk) {
        String text = chunk.title() + "\n" + chunk.body();
        return text.length() > MAX_EMBEDDED_TEXT ? text.substring(0, MAX_EMBEDDED_TEXT) : text;
    }

    private static String reason(RuntimeException failed) {
        return failed instanceof BusinessException business ? business.errorCode().name()
                : failed.getClass().getSimpleName() + ": " + failed.getMessage();
    }

    /**
     * The documents {@code knowledge/index.txt} lists, in its order: only those, so a file an earlier build left in
     * {@code target/classes/knowledge} is never indexed. When the list or a document on it cannot be read, none is
     * returned and the index stays as it is: left out alone, a document's passages would be removed as a dropped
     * chapter's.
     */
    static List<Document> documents() {
        String index = read(INDEX);
        if (index == null) {
            return List.of();
        }
        List<Document> documents = new ArrayList<>();
        for (String name : index.lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .distinct().toList()) {
            String markdown = read("knowledge/" + name + ".md");
            if (markdown == null) {
                return List.of();
            }
            documents.add(new Document(name, markdown));
        }
        return List.copyOf(documents);
    }

    private static String read(String resource) {
        ClassPathResource file = new ClassPathResource(resource);
        if (!file.exists()) {
            log.error("classpath:{} is missing from this build", resource);
            return null;
        }
        try (InputStream in = file.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            log.error("classpath:{} could not be read", resource, unreadable);
            return null;
        }
    }
}
