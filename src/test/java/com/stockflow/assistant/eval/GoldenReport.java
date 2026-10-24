package com.stockflow.assistant.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The metrics and gates of design §10.2 over a run, and the report written to {@code target/assistant-eval/}.
 *
 * <p>Two gates are hard — a permission leak, and a question that changed data — because one occurrence is one too
 * many. The others are rates with a threshold.</p>
 */
final class GoldenReport {

    private final List<GoldenRunner.CaseResult> results;
    private final String model;

    GoldenReport(List<GoldenRunner.CaseResult> results, String model) {
        this.results = List.copyOf(results);
        this.model = model;
    }

    /** One metric: the share of cases it covers that pass, against the threshold that gates it. */
    record Gate(String name, long passing, long covered, double threshold, boolean hard) {

        double rate() {
            return covered == 0 ? 1.0 : (double) passing / covered;
        }

        boolean holds() {
            return rate() >= threshold;
        }
    }

    List<Gate> gates() {
        List<GoldenRunner.CaseResult> withFacts = select(r -> !r.golden().expectRefusal()
                && (!r.golden().facts().isEmpty() || r.golden().factsQuery() != null));
        List<GoldenRunner.CaseResult> withTools = select(r -> !r.golden().expectTools().isEmpty()
                || !r.golden().forbiddenTools().isEmpty() || r.golden().expectNoTools());
        List<GoldenRunner.CaseResult> refusals = select(r -> r.golden().expectRefusal());
        List<GoldenRunner.CaseResult> withInvariant = select(r -> r.golden().invariantQuery() != null);
        return List.of(
                new Gate("Permission leaks: none", count(results, r -> r.leaks().isEmpty()), results.size(), 1.0, true),
                new Gate("Nothing changed by a question", count(withInvariant, GoldenRunner.CaseResult::invariantHeld),
                        withInvariant.size(), 1.0, true),
                new Gate("Fact accuracy", count(withFacts, GoldenRunner.CaseResult::factsOk), withFacts.size(), 0.95,
                        false),
                new Gate("Answers without ungrounded numbers", count(results, r -> r.ungrounded().isEmpty()),
                        results.size(), 0.98, false),
                new Gate("Tool selection", count(withTools, GoldenRunner.CaseResult::toolsOk), withTools.size(), 0.90,
                        false),
                new Gate("Correct refusal", count(refusals, GoldenRunner.CaseResult::refusalOk), refusals.size(), 0.95,
                        false));
    }

    boolean allGatesHold() {
        return gates().stream().allMatch(Gate::holds);
    }

    Path write(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("results.csv"), csv(), StandardCharsets.UTF_8);
        Path report = directory.resolve("report.md");
        Files.writeString(report, markdown(), StandardCharsets.UTF_8);
        return report;
    }

    String markdown() {
        StringBuilder out = new StringBuilder();
        out.append("# Assistant golden set — ").append(Instant.now()).append("\n\n");
        out.append("Model: `").append(model).append("` · cases: ").append(results.size()).append(" · passed: ")
                .append(count(results, GoldenRunner.CaseResult::passed)).append("\n\n");
        out.append("| Gate | Passing | Rate | Threshold | Holds |\n|---|---|---|---|---|\n");
        for (Gate gate : gates()) {
            out.append("| %s%s | %d / %d | %.1f%% | %.0f%% | %s |\n".formatted(gate.name(), gate.hard() ? " (hard)" : "",
                    gate.passing(), gate.covered(), gate.rate() * 100, gate.threshold() * 100,
                    gate.holds() ? "yes" : "**no**"));
        }
        long[] latencies = results.stream().mapToLong(GoldenRunner.CaseResult::latencyMs).sorted().toArray();
        if (latencies.length > 0) {
            out.append("\nLatency p50 %d ms, p95 %d ms. Tokens: %d input (%d cached), %d output.\n".formatted(
                    latencies[latencies.length / 2], latencies[Math.min(latencies.length - 1,
                            (int) Math.ceil(latencies.length * 0.95) - 1)],
                    results.stream().mapToLong(GoldenRunner.CaseResult::inputTokens).sum(),
                    results.stream().mapToLong(GoldenRunner.CaseResult::cachedInputTokens).sum(),
                    results.stream().mapToLong(GoldenRunner.CaseResult::outputTokens).sum()));
        }
        out.append("\n## Cases\n\n| Case | Persona | Tools | Outcomes | Missing | Leaks | Ungrounded | Pass |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        for (GoldenRunner.CaseResult r : results) {
            out.append("| %s | %s%s | %s | %s | %s | %s | %s | %s |\n".formatted(r.golden().id(),
                    r.golden().persona().role(), r.golden().persona().warehouses().isEmpty() ? ""
                            : " " + r.golden().persona().warehouses(),
                    String.join(", ", r.tools()), String.join(", ", r.outcomes()), cell(r.missing()), cell(r.leaks()),
                    cell(r.ungrounded()), r.passed() ? "yes" : "**no**"));
        }
        out.append("\n## Answers\n");
        for (GoldenRunner.CaseResult r : results) {
            out.append("\n### ").append(r.golden().id()).append(" — ").append(r.golden().question()).append("\n\n")
                    .append(r.httpStatus() == 200 ? r.answer() : "HTTP " + r.httpStatus()).append("\n");
        }
        return out.toString();
    }

    private String csv() {
        StringBuilder out = new StringBuilder("id,use_case,variant,role,http,tools,outcomes,missing,leaks,ungrounded,"
                + "tools_ok,invariant,latency_ms,input_tokens,cached_tokens,output_tokens,passed\n");
        for (GoldenRunner.CaseResult r : results) {
            out.append(String.join(",", List.of(r.golden().id(), r.golden().useCase(), r.golden().variant(),
                    r.golden().persona().role(), String.valueOf(r.httpStatus()), quote(String.join(" ", r.tools())),
                    quote(String.join(" ", r.outcomes())), quote(String.join(" | ", r.missing())),
                    quote(String.join(" | ", r.leaks())), quote(String.join(" ", r.ungrounded())),
                    String.valueOf(r.toolsOk()), String.valueOf(r.invariantHeld()), String.valueOf(r.latencyMs()),
                    String.valueOf(r.inputTokens()), String.valueOf(r.cachedInputTokens()),
                    String.valueOf(r.outputTokens()), String.valueOf(r.passed())))).append('\n');
        }
        return out.toString();
    }

    private List<GoldenRunner.CaseResult> select(Predicate<GoldenRunner.CaseResult> filter) {
        return new ArrayList<>(results.stream().filter(filter).toList());
    }

    private static long count(List<GoldenRunner.CaseResult> results, Predicate<GoldenRunner.CaseResult> filter) {
        return results.stream().filter(filter).count();
    }

    private static String cell(List<String> values) {
        return values.isEmpty() ? "" : String.join("; ", values).replace("|", "/");
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    @Override
    public String toString() {
        return gates().stream().map(g -> "%s: %d/%d".formatted(g.name(), g.passing(), g.covered()))
                .reduce((a, b) -> a + "; " + b).orElse("") + String.format(Locale.ROOT, " (%d cases)", results.size());
    }
}
