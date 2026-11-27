package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.KnowledgeChunk;
import com.stockflow.assistant.internal.domain.KnowledgeRepository;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.i18n.Messages;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Why a business refusal happened and what to do next (contract §4.1): the link "Vì sao?" next to an error message.
 * Deterministic: no model is asked.
 *
 * <p><b>A curated map, not a guess.</b> {@code assistant/error-rules.yml} names, for the refusals people meet in daily
 * work, the kltn-docs rules behind each one and one to three next steps. A code it does not list is explained with its
 * message alone and no rule: matching a code to a rule by its words would sometimes cite the wrong rule, and a wrong
 * rule shown as the reason is worse than none. {@code ErrorRulesTest} holds the map to the corpus and to
 * {@link ErrorCode}; a code that is not in {@link ErrorCode} fails the start here too, so a renamed code cannot leave a
 * dead entry behind.</p>
 *
 * <p><b>The rule's text is the corpus's.</b> It is read from the indexed passages ({@link KnowledgeRepository#rules}),
 * the same ones the assistant cites, so it follows {@code docs/kltn-snapshot} on every sync and never drifts into a
 * second copy. A rule the index does not hold (indexing failed on this start) is left out rather than shown empty.</p>
 *
 * <p>The message is the caller's language ({@link Messages}); the rules and the next steps are Vietnamese, the language
 * of the analysis and of the people refused.</p>
 */
@Service
public class ErrorExplanations {

    static final String RESOURCE = "assistant/error-rules.yml";

    /** A rule as the corpus anchors it: chapter, then the rule ({@code 15 BR-08}, {@code 20 BR-14}). */
    private static final Pattern RULE_ID = Pattern.compile("\\d{2} BR-\\d+[a-z]?");

    /** The part counter the parser adds to a long rule's later passages: {@code 15 BR-08 (2)}. */
    private static final Pattern PART = Pattern.compile("^(.+) \\((\\d+)\\)$");

    /** The part counter the parser adds to a long rule's titles: {@code ... (2/3)}. */
    private static final Pattern TITLE_PART = Pattern.compile("\\s*\\(\\d+/\\d+\\)$");

    /** At most this many next steps an entry gives (contract §4.1). */
    static final int MAX_NEXT_STEPS = 3;

    private final Map<ErrorCode, Mapping> mappings;
    private final KnowledgeRepository knowledge;
    private final Messages messages;
    private final TransactionTemplate readOnly;

    @org.springframework.beans.factory.annotation.Autowired
    ErrorExplanations(KnowledgeRepository knowledge, Messages messages, PlatformTransactionManager transactions) {
        this(load(), knowledge, messages, transactions);
    }

    ErrorExplanations(Map<ErrorCode, Mapping> mappings, KnowledgeRepository knowledge, Messages messages,
                      PlatformTransactionManager transactions) {
        this.mappings = mappings;
        this.knowledge = knowledge;
        this.messages = messages;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    /** What the map says about one code: the rules behind it and what to do next. */
    record Mapping(List<String> rules, List<String> nextSteps) {

        Mapping {
            rules = List.copyOf(rules);
            nextSteps = List.copyOf(nextSteps);
        }
    }

    /**
     * @param message   the code's message in the caller's language, as the refusal showed it
     * @param rules     the rules behind it, in the map's order; empty when none is mapped
     * @param nextSteps what to do now; empty when none is mapped
     */
    public record Explanation(ErrorCode errorCode, String message, List<Rule> rules, List<String> nextSteps) {
    }

    /**
     * One rule, whole: every passage it was cut into, in order.
     *
     * @param chapter the kltn-docs chapter, by its snapshot name ({@code 15-payment})
     * @param title   the rule's title as the corpus gives it: anchor, name, chapter
     */
    public record Rule(String ruleId, String chapter, String title, String text) {
    }

    /**
     * @throws NotFoundException for a value that is no {@link ErrorCode}
     */
    public Explanation explain(String errorCode) {
        ErrorCode code;
        try {
            code = ErrorCode.valueOf(errorCode);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new NotFoundException("Error code", errorCode);
        }
        String message = messages.forCode(code);
        Mapping mapping = mappings.get(code);
        if (mapping == null) {
            return new Explanation(code, message, List.of(), List.of());
        }
        return new Explanation(code, message, rules(mapping.rules()), mapping.nextSteps());
    }

    /** The codes the map explains, for the tests and for anyone asking which links are worth showing. */
    public java.util.Set<ErrorCode> mapped() {
        return Collections.unmodifiableSet(mappings.keySet());
    }

    private List<Rule> rules(List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<KnowledgeChunk> chunks = readOnly.execute(status -> knowledge.rules(ids));
        Map<String, List<KnowledgeChunk>> byRule = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : chunks == null ? List.<KnowledgeChunk>of() : chunks) {
            Matcher part = PART.matcher(chunk.anchor());
            String base = part.matches() ? part.group(1) : chunk.anchor();
            byRule.computeIfAbsent(base, id -> new ArrayList<>()).add(chunk);
        }
        List<Rule> rules = new ArrayList<>();
        for (String id : ids) {
            List<KnowledgeChunk> parts = byRule.get(id);
            if (parts == null || parts.isEmpty()) {
                continue;
            }
            parts.sort(Comparator.comparingInt(ErrorExplanations::partNumber));
            KnowledgeChunk first = parts.getFirst();
            rules.add(new Rule(id, first.source(), TITLE_PART.matcher(first.title()).replaceFirst(""),
                    String.join("\n", parts.stream().map(KnowledgeChunk::body).toList())));
        }
        return rules;
    }

    private static int partNumber(KnowledgeChunk chunk) {
        Matcher part = PART.matcher(chunk.anchor());
        return part.matches() ? Integer.parseInt(part.group(2)) : 1;
    }

    /** {@link #RESOURCE}, read and checked. */
    static Map<ErrorCode, Mapping> load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return parse(in);
        } catch (IOException missing) {
            throw new UncheckedIOException("The error explanations " + RESOURCE + " cannot be read", missing);
        }
    }

    /**
     * The map in {@code in}, checked: every key an {@link ErrorCode}, every rule an anchor of the corpus's shape, one to
     * {@value #MAX_NEXT_STEPS} next steps, nothing blank.
     *
     * @throws IllegalStateException naming the first entry that is wrong
     */
    @SuppressWarnings("unchecked")
    static Map<ErrorCode, Mapping> parse(InputStream in) {
        Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
        if (!(document instanceof Map<?, ?> root) || !(root.get("errors") instanceof Map<?, ?> errors)) {
            throw new IllegalStateException(RESOURCE + " holds no 'errors' map");
        }
        Map<ErrorCode, Mapping> mappings = new EnumMap<>(ErrorCode.class);
        for (Map.Entry<?, ?> entry : errors.entrySet()) {
            String name = String.valueOf(entry.getKey());
            ErrorCode code;
            try {
                code = ErrorCode.valueOf(name);
            } catch (IllegalArgumentException unknown) {
                throw new IllegalStateException(RESOURCE + ": " + name + " is not an ErrorCode");
            }
            if (!(entry.getValue() instanceof Map<?, ?> body)) {
                throw new IllegalStateException(RESOURCE + ": " + name + " has no rules and next steps");
            }
            List<String> rules = strings(name, "rules", body.get("rules"));
            List<String> nextSteps = strings(name, "nextSteps", body.get("nextSteps"));
            for (String rule : rules) {
                if (!RULE_ID.matcher(rule).matches()) {
                    throw new IllegalStateException(RESOURCE + ": " + name + " names '" + rule
                            + "', not a rule as the corpus anchors it (\"15 BR-08\")");
                }
            }
            if (nextSteps.isEmpty() || nextSteps.size() > MAX_NEXT_STEPS) {
                throw new IllegalStateException(RESOURCE + ": " + name + " gives " + nextSteps.size()
                        + " next steps; one to " + MAX_NEXT_STEPS);
            }
            mappings.put(code, new Mapping(rules, nextSteps));
        }
        return Collections.unmodifiableMap(mappings);
    }

    private static List<String> strings(String code, String field, Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException(RESOURCE + ": " + code + "." + field + " is not a list");
        }
        List<String> strings = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalStateException(RESOURCE + ": " + code + "." + field + " holds a blank or a non-text");
            }
            strings.add(text.strip());
        }
        return strings;
    }
}
