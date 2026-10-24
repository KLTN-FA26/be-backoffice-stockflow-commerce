package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.KnowledgeChunk;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.RequiresPermission;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * U6: the business rules, glossary and state machines, searched (design §6).
 *
 * <p>No screen shows these, so the tool mirrors no endpoint and carries the assistant's own permission: whoever may
 * ask the assistant may read the rules. Each passage keeps its anchor, so the answer can cite {@code BR-RMA-002} and
 * the golden set can check that what it cites was actually retrieved.</p>
 */
@Component
class SearchKnowledgeTool implements AssistantTool {

    static final int RESULTS = 5;
    static final int MAX_TEXT = 1500;

    private static final ToolSpec SPEC = new ToolSpec("searchKnowledge",
            "Search StockFlow's business rules (ids like BR-RMA-002), glossary and state machines: policies, what "
                    + "is allowed, definitions, and which status changes are possible. The rules and the glossary are "
                    + "written in English, so search with English keywords; add Vietnamese ones if useful. Returns up "
                    + "to 5 passages, each with its anchor; quote a rule by its anchor. A long state machine is split "
                    + "into passages ('9. Order — order-service', '... (2)', '... › Who may cancel ...'): when a rule "
                    + "points elsewhere or a passage is cut, search again for the part you need. Answer a policy "
                    + "question only from what the passages say; if they do not say it, say no rule was found. Not "
                    + "for live data such as stock or orders.",
            ToolSchema.object()
                    .string("query", "Keywords, for example 'custom printed return defective'")
                    .build());

    private final KnowledgeService knowledge;

    SearchKnowledgeTool(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @Override
    public ToolSpec spec() {
        return SPEC;
    }

    @Override
    public String mirrors() {
        return null;
    }

    @Override
    @RequiresPermission(resource = ToolPermissions.ASSISTANT, action = Action.READ)
    public Answer run(ToolArguments arguments) {
        String query = arguments.text("query", 300);
        return new Answer(knowledge.search(query, RESULTS).stream().map(Passage::of).toList());
    }

    /** {@code passages} is empty when nothing matched; the model must then say it found no rule. */
    record Answer(List<Passage> passages) {
    }

    /** @param cut the text was shortened; the rest of the passage was not shown */
    record Passage(String anchor, String source, String title, String text, boolean cut) {

        static Passage of(KnowledgeChunk chunk) {
            boolean cut = chunk.body().length() > MAX_TEXT;
            return new Passage(chunk.anchor(), chunk.source(), chunk.title(),
                    cut ? chunk.body().substring(0, MAX_TEXT) + "…" : chunk.body(), cut);
        }
    }
}
