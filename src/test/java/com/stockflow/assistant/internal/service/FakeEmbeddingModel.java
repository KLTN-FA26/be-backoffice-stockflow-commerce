package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.EmbeddingModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * An {@link EmbeddingModel} with no network: a text's vector is the one given for the first prefix it starts with,
 * else {@link #UNRELATED}, which no question vector of these tests points towards. Every call is recorded.
 */
final class FakeEmbeddingModel implements EmbeddingModel {

    static final float[] UNRELATED = {0, 0, 1};

    boolean available = true;
    String name = "fake-embedding";
    /** Runs first in every call, before anything is recorded: a test's own probe. */
    Runnable onCall = () -> { };

    final List<List<String>> calls = new ArrayList<>();
    final List<Purpose> purposes = new ArrayList<>();
    private final Map<String, float[]> vectors = new LinkedHashMap<>();
    private RuntimeException failure;
    private IntPredicate failsOn = call -> false;

    FakeEmbeddingModel vector(String prefix, float... vector) {
        vectors.put(prefix, vector);
        return this;
    }

    /** Throws {@code failure} from every call {@code on} accepts, calls counted from 1; null for no failure. */
    void fail(RuntimeException failure, IntPredicate on) {
        this.failure = failure;
        this.failsOn = on;
    }

    /** The texts embedded for {@code purpose}, call after call. */
    List<String> texts(Purpose purpose) {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            if (purposes.get(i) == purpose) {
                texts.addAll(calls.get(i));
            }
        }
        return texts;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public int dimensions() {
        return UNRELATED.length;
    }

    @Override
    public List<float[]> embed(List<String> texts, Purpose purpose) {
        onCall.run();
        calls.add(List.copyOf(texts));
        purposes.add(purpose);
        if (failure != null && failsOn.test(calls.size())) {
            throw failure;
        }
        return texts.stream().map(this::vectorOf).toList();
    }

    private float[] vectorOf(String text) {
        return vectors.entrySet().stream()
                .filter(entry -> text.startsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(UNRELATED);
    }
}
