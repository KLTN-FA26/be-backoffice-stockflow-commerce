package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.KnowledgeChunk;
import com.stockflow.assistant.internal.domain.KnowledgeSearch;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * U6: the business analysis (kltn-docs) searched: each chapter's rules, statuses, edge cases and flow, and the
 * glossary (design §6).
 *
 * <p>No screen shows these, so the tool mirrors no endpoint and carries the assistant's own permission: whoever may
 * ask the assistant may read the rules. Each passage keeps its anchor, so the answer can cite {@code 15 BR-08} and
 * the golden set can check that what it cites was actually retrieved, and says how the search found it
 * ({@code foundBy}): a passage only its meaning found shares no word with the search, and is the one to read most
 * carefully before relying on it.</p>
 *
 * <p>With {@code stockflow.assistant.knowledge.multi-query} on, the tool also asks for the user's question as they
 * wrote it, and the search embeds it beside the terms. Off, the tool does not ask for it: a model is never made to copy
 * the question for nothing.</p>
 */
@Component
class SearchKnowledgeTool implements AssistantTool {

    static final int RESULTS = 5;
    static final int MAX_TEXT = 1500;

    /** The longest question the search embeds: a pasted e-mail is cut to its start. */
    static final int QUESTION_LENGTH = 1000;

    private static final String QUERY_HINT = "Vietnamese keywords, for example 'phí huỷ đơn sau khi in hàng loạt'";

    private final KnowledgeService knowledge;
    private final ToolSpec spec;
    private final boolean withQuestion;

    SearchKnowledgeTool(KnowledgeService knowledge, KnowledgeProperties properties) {
        this.knowledge = knowledge;
        this.withQuestion = properties.multiQuery();
        ToolSchema schema = ToolSchema.object().string("query", QUERY_HINT);
        if (withQuestion) {
            schema = schema.string("question", "The user's question this search is for, word for word as they wrote "
                    + "it, in their language; at most the first " + QUESTION_LENGTH + " characters are used");
        }
        this.spec = new ToolSpec("searchKnowledge", description(properties.flows()), schema.build());
    }

    /**
     * What the model is told the tool searches: the process flows only when they are indexed, so it never searches for
     * a flow that is not there; and what {@code foundBy} means, so it reads a passage found by meaning alone with care.
     */
    static String description(boolean flows) {
        return "Search StockFlow's business analysis, written in Vietnamese: the business rules of each chapter, the "
                + "statuses and which changes are allowed, the edge cases"
                + (flows ? ", the steps of each process in order" : "")
                + ", and the glossary (terms, statuses, roles, and terms no longer used). Chapters: 01 products, SKUs "
                + "and carton specs; 03 receiving finished goods and returned goods; 05 putaway; 06 warehouse map, bins and "
                + "capacity; 07 picking; 08 carton check and shipping labels; 09 shipping, load plans and carriers; 11 "
                + "moves inside a warehouse; 20 inventory control, counts and adjustments; 14 quotes, orders and sales "
                + "contracts; 15 contract payments and receivables; 17 order management, production orders, holds, "
                + "cancellation and returns; 18 wholesale customers; 21 e-invoices. Search with Vietnamese keywords. "
                + "Returns up to 5 passages, each with its anchor and how the search found it (foundBy: named, both, "
                + "words or meaning; one found by meaning alone shares no word with your search, so check that it "
                + "really answers before relying on it). An anchor carries its chapter: '15 BR-08' is rule 8 of "
                + "chapter 15, and rule numbers start again in every chapter, so a rule number is never written "
                + "without its chapter; '17 §5.1' is a section (§5 statuses, §6 edge cases"
                + (flows ? ", §4 the steps" : "") + "); a glossary passage is anchored by its term or 'glossary §8'. "
                + "Name what you rely on by its anchor exactly as written, and give what it lays down in the language "
                + "of the user's question. A long section or rule is split into passages ('17 §5.1', '17 §5.1 (2)'). "
                + "A rule or section a passage names without a chapter ('BR-17', '§4.5') is one of the passage's own "
                + "chapter: inside '20 BR-01', 'BR-17' is '20 BR-17'. When a passage points to another rule or section "
                + "or is cut, search for that anchor. Answer a policy question only from what the passages say; if "
                + "they do not say it, "
                + "say no rule was found. Not for live data such as stock or orders.";
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public String mirrors() {
        return null;
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.ASSISTANT, action = Action.READ)
    public Answer run(ToolArguments arguments) {
        String query = arguments.text("query", 300);
        // A question longer than the search reads is cut, not refused: the search goes ahead on its start.
        String question = withQuestion ? arguments.optionalText("question", Integer.MAX_VALUE) : null;
        question = question != null && question.length() > QUESTION_LENGTH ? question.substring(0, QUESTION_LENGTH)
                : question;
        return new Answer(knowledge.find(query, question, RESULTS).stream().map(Passage::of).toList());
    }

    /** {@code passages} is empty when nothing matched; the model must then say it found no rule. */
    record Answer(List<Passage> passages) {
    }

    /**
     * @param cut     the text was shortened; the rest of the passage was not shown
     * @param foundBy {@code named}, {@code both}, {@code words} or {@code meaning} ({@link KnowledgeSearch.FoundBy})
     */
    record Passage(String anchor, String source, String title, String text, boolean cut, String foundBy) {

        static Passage of(KnowledgeSearch.Hit hit) {
            KnowledgeChunk chunk = hit.chunk();
            boolean cut = chunk.body().length() > MAX_TEXT;
            return new Passage(chunk.anchor(), chunk.source(), chunk.title(),
                    cut ? chunk.body().substring(0, MAX_TEXT) + "…" : chunk.body(), cut, hit.foundBy().label());
        }
    }
}
