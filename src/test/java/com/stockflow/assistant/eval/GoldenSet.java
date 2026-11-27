package com.stockflow.assistant.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.History;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Loads {@code classpath:assistant/golden-set.json}: the cases, each with a unique id. */
public final class GoldenSet {

    static final String RESOURCE = "/assistant/golden-set.json";

    private GoldenSet() {
    }

    public static List<GoldenCase> load() {
        try (InputStream in = GoldenSet.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("classpath:" + RESOURCE + " is missing");
            }
            // One oracle call is written as an object and a chain as an array; both read as a list of steps.
            List<GoldenCase> cases = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                    .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
                    .readValue(in, new TypeReference<List<GoldenCase>>() { });
            Set<String> ids = new HashSet<>();
            for (GoldenCase each : cases) {
                if (!ids.add(each.id())) {
                    throw new IllegalStateException("two golden cases are " + each.id());
                }
                if (each.expectNoPassage() && (!each.expectRetrieved().isEmpty() || !each.facts().isEmpty())) {
                    throw new IllegalStateException(each.id() + " expects no passage, so it names none to retrieve "
                            + "or state");
                }
                if (each.persona() == null || each.question() == null || each.question().isBlank()) {
                    throw new IllegalStateException(each.id() + " needs a persona and a question");
                }
                // The model is sent only the last ten earlier questions; an older one could not be followed up.
                if (each.earlier().size() > History.EARLIER_QUESTIONS || each.earlier().stream()
                        .anyMatch(turn -> turn.question() == null || turn.question().isBlank())) {
                    throw new IllegalStateException(each.id() + " needs at most " + History.EARLIER_QUESTIONS
                            + " earlier questions, none of them blank");
                }
            }
            return List.copyOf(cases);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("classpath:" + RESOURCE + " is not a valid golden set", unreadable);
        }
    }
}
