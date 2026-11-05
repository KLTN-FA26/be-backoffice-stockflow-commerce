package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.KnowledgeSearch;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * How the knowledge search ranks ({@code stockflow.assistant.knowledge}, design §6.4). Every value has the default the
 * code had before it could be set; each is changed only on what the retrieval eval measures
 * ({@code RetrievalEval}, design §10.2), never by feel.
 *
 * @param standout    how far above the corpus's median similarity, in robust deviations, a passage must stand to be
 *                    near a question ({@link KnowledgeSearch#STANDOUT}). Lower finds more, and hands the model more
 *                    passages for questions no rule answers
 * @param fusionDepth how many of each ranking take part in the fusion ({@link KnowledgeService#FUSION_DEPTH})
 * @param multiQuery  also embed the user's question as they wrote it, beside the model's search terms, and fuse the
 *                    three rankings; the tool then asks the model for the question. Off until measured
 * @param reranker    a cross-encoder reading the fused passages with the query; off without a base URL
 * @param flows       index the process flows ({@code 04c-all-flows.md}). Off, and their passages are removed: the
 *                    document names statuses the system does not have (a goods receipt QC_PASSED or POSTED, a
 *                    purchase order SENT), which the assistant would state as fact, and its Vietnamese words crowd the
 *                    English rules out of a keyword search (design §6.1). On once the document matches
 *                    {@code 03-state-machines.md} and {@code RetrievalEval} shows the rules still found
 * @param minMatchedWords how many of a search's words a passage must hold to be found by its words, 1 to 4
 *                    ({@link com.stockflow.assistant.internal.domain.KnowledgeRepository#search}). One let a single
 *                    shared word bring back passages about something else: "calendar" found five on returns for a
 *                    question about the Tet holiday. Two, on RetrievalEval's keyword-only numbers of 5/11/2026: with
 *                    the flows off, recall@5 0.211 → 0.224, MRR 0.158 → 0.175, no-rule searches returning anything
 *                    0.33 → 0.17; with them on, recall 0.789 → 0.803. Three found less (0.197 off, 0.776 on)
 */
@ConfigurationProperties(prefix = "stockflow.assistant.knowledge")
public record KnowledgeProperties(
        @DefaultValue("3.0") double standout,
        @DefaultValue("10") int fusionDepth,
        @DefaultValue("false") boolean multiQuery,
        @DefaultValue Reranker reranker,
        @DefaultValue("false") boolean flows,
        @DefaultValue("2") int minMatchedWords) {

    /** What the search did before these could be set. */
    static final KnowledgeProperties DEFAULTS = new KnowledgeProperties(KnowledgeSearch.STANDOUT,
            KnowledgeService.FUSION_DEPTH, false, new Reranker("", Duration.ofSeconds(5), 20), false, 1);

    public KnowledgeProperties {
        if (!(standout >= 0.5 && standout <= 10)) {
            throw new IllegalArgumentException("stockflow.assistant.knowledge.standout must be between 0.5 and 10");
        }
        if (fusionDepth < 5 || fusionDepth > 50) {
            throw new IllegalArgumentException("stockflow.assistant.knowledge.fusion-depth must be between 5 and 50");
        }
        if (minMatchedWords < 1 || minMatchedWords > 4) {
            throw new IllegalArgumentException("stockflow.assistant.knowledge.min-matched-words must be between 1 "
                    + "and 4");
        }
        reranker = reranker == null ? DEFAULTS.reranker() : reranker;
    }

    /**
     * A server speaking text-embeddings-inference's {@code /rerank} (TEI, serving {@code bge-reranker-v2-m3}), inside
     * the company's network: one outside it is never asked, since it would be a vendor no agreement covers and the
     * search terms carry what users ask (design §5.5).
     *
     * @param baseUrl blank for none
     * @param timeout how long a search waits for it; past that, the fused order stands
     * @param depth   how many fused passages it reorders, of which the best five are shown: at most 32, the batch a
     *                text-embeddings-inference server takes by default ({@code --max-client-batch-size})
     */
    public record Reranker(
            @DefaultValue("") String baseUrl,
            @DefaultValue("PT5S") Duration timeout,
            @DefaultValue("20") int depth) {

        public Reranker {
            baseUrl = baseUrl == null ? "" : baseUrl.strip();
            if (timeout == null || timeout.isNegative() || timeout.isZero()
                    || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException("stockflow.assistant.knowledge.reranker.timeout must be over zero "
                        + "and at most 30 seconds");
            }
            if (depth < 5 || depth > 32) {
                throw new IllegalArgumentException("stockflow.assistant.knowledge.reranker.depth must be between 5 "
                        + "and 32");
            }
        }

        boolean configured() {
            return !baseUrl.isEmpty();
        }
    }
}
