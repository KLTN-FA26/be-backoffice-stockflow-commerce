package com.stockflow.assistant.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real-model run keeps each finished case as one line, resumes after it, and never counts a line for a case that
 * changed or for another model.
 */
class GoldenResultsTest {

    private static final String MODEL = "gemini-3.5-flash-lite";

    @TempDir Path folder;

    @Test
    @DisplayName("a finished case is kept at once and read back whole by the next run")
    void resumes() {
        Path file = folder.resolve("runs").resolve("results.jsonl");
        GoldenCase golden = golden(List.of("75"));
        GoldenResults first = GoldenResults.open(file, MODEL);
        assertThat(first.finished(golden)).isEmpty();

        first.append(golden, result(golden.resolve(Map.of()), "Kho HCM còn 75 cái."));

        GoldenRunner.CaseResult read = GoldenResults.open(file, MODEL).finished(golden).orElseThrow();
        assertThat(read.golden().id()).isEqualTo("S-01");
        assertThat(read.answer()).isEqualTo("Kho HCM còn 75 cái.");
        assertThat(read.tools()).containsExactly("getStockLevels");
        assertThat(read.modelRequests()).isEqualTo(2);
        assertThat(read.passed()).isTrue();
    }

    @Test
    @DisplayName("a case edited since its run is asked again; its oracle and smoke mark do not count as an edit")
    void staleLines() {
        Path file = folder.resolve("results.jsonl");
        GoldenCase golden = golden(List.of("75"));
        GoldenResults.open(file, MODEL).append(golden, result(golden, "75"));

        GoldenResults reopened = GoldenResults.open(file, MODEL);
        assertThat(reopened.finished(golden(List.of("75", "HCM")))).isEmpty();
        assertThat(reopened.finished(new GoldenCase(golden.id(), golden.useCase(), golden.variant(), golden.persona(),
                golden.question(), golden.expectTools(), golden.forbiddenTools(), golden.expectNoTools(),
                golden.facts(), golden.factsQuery(), golden.forbidden(), golden.forbiddenQuery(),
                golden.expectRefusal(), golden.invariantQuery(), golden.expectRetrieved(), golden.earlier(),
                List.of(new GoldenCase.Oracle("getStockLots", Map.of("sku", "EVAL-BOX-A4"))), true))).isPresent();
    }

    @Test
    @DisplayName("the last line of a case wins; lines of another model and unreadable lines are left out")
    void lines() throws Exception {
        Path file = folder.resolve("results.jsonl");
        GoldenCase golden = golden(List.of("75"));
        GoldenResults.open(file, "claude-opus-5-5").append(golden, result(golden, "from another model"));
        GoldenResults.open(file, MODEL).append(golden, result(golden, "first"));
        GoldenResults.open(file, MODEL).append(golden, result(golden, "second"));
        Files.writeString(file, "{not json\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        GoldenResults reopened = GoldenResults.open(file, MODEL);

        assertThat(reopened.finished(golden).orElseThrow().answer()).isEqualTo("second");
        assertThat(reopened.ignored()).isEqualTo(2);
        assertThat(Files.readAllLines(file)).hasSize(4);
    }

    private static GoldenCase golden(List<String> facts) {
        return new GoldenCase("S-01", "S", "formal", new GoldenCase.Persona("INVENTORY_PLANNER", List.of()),
                "Tồn kho của EVAL-BOX-A4 ở từng kho là bao nhiêu?", List.of("getStockLevels"), List.of(), false, facts,
                null, List.of(), null, false, null, List.of(), List.of(),
                List.of(new GoldenCase.Oracle("getStockLevels", Map.of("sku", "EVAL-BOX-A4"))), false);
    }

    private static GoldenRunner.CaseResult result(GoldenCase golden, String answer) {
        return new GoldenRunner.CaseResult(golden, 200, null, answer, List.of(), List.of("getStockLevels"),
                List.of("OK"), List.of(), List.of(), List.of(), true, true, List.of(), List.of(), true, 1200, 2, 900,
                0, 40);
    }
}
