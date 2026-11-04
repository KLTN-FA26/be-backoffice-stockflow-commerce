package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.KnowledgeChunk;
import com.stockflow.assistant.internal.domain.KnowledgeSearch;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * U6: the business rules, glossary, state machines and process flows, searched (design §6).
 *
 * <p>No screen shows these, so the tool mirrors no endpoint and carries the assistant's own permission: whoever may
 * ask the assistant may read the rules. Each passage keeps its anchor, so the answer can cite {@code BR-RMA-002} and
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

    private static final String QUERY_HINT = "Keywords, for example 'custom printed return defective'";

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
        return "Search StockFlow's business rules (ids like BR-RMA-002), glossary and state machines"
                + (flows ? " and process flows (codes like F-PO purchase orders, F-RCP receiving, F-FUL picking and "
                        + "packing, F-SHP shipping, F-RMA returns, F-CNT stock counts, X-SHORT short picks)" : "")
                + ": policies, what is allowed, definitions, which status changes are possible"
                + (flows ? ", and the steps of a process in order" : "")
                + ". The rules, the glossary and the state machines are written in English"
                + (flows ? ", the flows in Vietnamese: search with English keywords, and add Vietnamese ones for how a "
                        + "process goes. A flow describes the steps as designed: which statuses exist is for the state "
                        + "machines to say, and live data for the other tools."
                        : ", so search with English keywords; add Vietnamese ones if useful.")
                + " Returns up to 5 passages, each with its anchor and how the search found it (foundBy: named, both, "
                + "words or meaning; one found by meaning alone shares no word with your search, so check that it "
                + "really answers before relying on it): name the rule" + (flows ? " or flow" : "") + " you rely on by "
                + "its anchor, and give what it lays down in the language of the user's question. A long state "
                + "machine" + (flows ? " or flow" : "") + " is split into passages ('9. Order — order-service', '... "
                + "(2)', '... › Who may cancel ...'): when a rule points elsewhere or a passage is cut, search again for "
                + "the part you need. Answer a policy question only from what the passages say; if they do not say it, "
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
