package com.stockflow.assistant.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The finished cases of a real-model evaluation, one JSON line each, kept between runs (design §10.3).
 *
 * <p><b>Why a file, and why one line per case.</b> A free model quota answers about ten cases a day, so one pass over
 * the set takes several days and runs in a fresh test database each time. A line is written the moment its case
 * finishes: a run stopped by the quota, or killed, keeps every case it finished, and the next run asks only the rest.
 * The report is built from every line, so it covers the whole set however many days it took.</p>
 *
 * <p><b>A line counts only for the case it was scored against.</b> Each line carries a fingerprint of its case as
 * written in the golden set, without what the oracle calls and the {@code smoke} mark (neither changes what the real
 * model is asked or how it is scored). A case edited since — another question, other facts — no longer matches its
 * old lines and is asked again; a line of another model is ignored, so two models never share one report.</p>
 */
final class GoldenResults {

    /** One finished case. {@code ranAt} is ISO-8601, kept as text so the file needs no date module to read. */
    record Line(String id, String fingerprint, String model, String ranAt, GoldenRunner.CaseResult result) {
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path file;
    private final String model;
    private final Map<String, Line> latest = new HashMap<>();
    private int ignored;

    private GoldenResults(Path file, String model) {
        this.file = file;
        this.model = model;
    }

    /** Reads the lines {@code file} already holds; a missing file is an empty one. */
    static GoldenResults open(Path file, String model) {
        GoldenResults results = new GoldenResults(file, model);
        if (!Files.exists(file)) {
            return results;
        }
        try {
            for (String text : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (text.isBlank()) {
                    continue;
                }
                try {
                    Line line = JSON.readValue(text, Line.class);
                    if (line.result() == null || line.result().golden() == null || !model.equals(line.model())) {
                        results.ignored++;
                    } else {
                        // A later line for the same case wins: the case was asked again after it changed.
                        results.latest.put(line.id(), line);
                    }
                } catch (JsonProcessingException unreadable) {
                    results.ignored++;
                }
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + file, unreadable);
        }
        return results;
    }

    /** The result kept for {@code golden} as the golden set has it now; empty when it is still to be asked. */
    Optional<GoldenRunner.CaseResult> finished(GoldenCase golden) {
        Line line = latest.get(golden.id());
        return line != null && fingerprint(golden).equals(line.fingerprint()) ? Optional.of(line.result())
                : Optional.empty();
    }

    /** Keeps {@code result} for {@code golden} (as the golden set has it, placeholders unfilled), at once. */
    void append(GoldenCase golden, GoldenRunner.CaseResult result) {
        Line line = new Line(golden.id(), fingerprint(golden), model, Instant.now().toString(), result);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, JSON.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException unwritable) {
            throw new UncheckedIOException("cannot write " + file, unwritable);
        }
        latest.put(line.id(), line);
    }

    /** Lines left out: unreadable, or of another model. */
    int ignored() {
        return ignored;
    }

    Path file() {
        return file;
    }

    /**
     * What a stored result was scored with. Raised when a result records something new, so results scored by the old
     * checks are asked again instead of passing the new gates with empty fields: 2 on 5/11/2026, codes, copied
     * examples and "none" answers.
     */
    static final int SCORING = 2;

    /**
     * What identifies a case's content: its JSON without the oracle's calls and the smoke mark, and the scoring
     * version ({@link #SCORING}). Placeholders stay
     * unfilled, so a case's fingerprint does not change with the date its expiry facts are computed from.
     */
    static String fingerprint(GoldenCase golden) {
        ObjectNode tree = JSON.valueToTree(golden);
        tree.put("scoring", SCORING);
        tree.remove(List.of("oracle", "smoke"));
        tree.path("earlier").forEach(turn -> ((ObjectNode) turn).remove("oracle"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(tree.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is always available", impossible);
        }
    }
}
