package com.stockflow.assistant.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

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
            List<GoldenCase> cases = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                    .readValue(in, new TypeReference<List<GoldenCase>>() { });
            Set<String> ids = new HashSet<>();
            for (GoldenCase each : cases) {
                if (!ids.add(each.id())) {
                    throw new IllegalStateException("two golden cases are " + each.id());
                }
                if (each.persona() == null || each.question() == null || each.question().isBlank()) {
                    throw new IllegalStateException(each.id() + " needs a persona and a question");
                }
            }
            return List.copyOf(cases);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("classpath:" + RESOURCE + " is not a valid golden set", unreadable);
        }
    }
}
