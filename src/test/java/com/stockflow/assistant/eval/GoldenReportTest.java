package com.stockflow.assistant.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A case that did not run is reported as such and counts in no gate: a spent quota is not poor quality. */
class GoldenReportTest {

    @Test
    @DisplayName("cases not run are left out of every gate, listed with their reason, and counted as 'x of n run'")
    void notRun() {
        GoldenCase asked = golden("S-01", false);
        GoldenReport report = new GoldenReport(List.of(result(asked, List.of(), 2)), List.of(
                new GoldenReport.NotRun(golden("S-02", false), "ASSISTANT_QUOTA_EXHAUSTED", 1),
                new GoldenReport.NotRun(golden("S-05", true), "stopped after the model's quota ran out at S-02", 0)),
                "gemini-3.5-flash-lite (gemini)");

        assertThat(report.allGatesHold()).isTrue();
        assertThat(report.gates()).allSatisfy(gate -> assertThat(gate.covered()).isLessThanOrEqualTo(1));
        assertThat(report.run()).isEqualTo(1);
        assertThat(report.total()).isEqualTo(3);
        assertThat(report.toString()).endsWith("(1 of 3 run)");
        assertThat(report.markdown())
                .contains("run: 1 of 3")
                .contains("- ASSISTANT_QUOTA_EXHAUSTED (1): S-02")
                .contains("- stopped after the model's quota ran out at S-02 (1): S-05")
                .contains("Model requests: 2 for the 1 cases run (2.0 a case), and 1 spent on cases that did not "
                        + "finish");
    }

    @Test
    @DisplayName("a case that ran and leaked fails the hard gate, whatever did not run")
    void leak() {
        GoldenCase refusal = golden("S-05", true);
        GoldenReport report = new GoldenReport(List.of(result(refusal, List.of("75"), 1)),
                List.of(new GoldenReport.NotRun(golden("S-01", false), "not selected", 0)), "oracle");

        assertThat(report.allGatesHold()).isFalse();
        assertThat(report.gates()).filteredOn(GoldenReport.Gate::hard).filteredOn(gate -> !gate.holds())
                .extracting(GoldenReport.Gate::name).containsExactly("Permission leaks: none");
    }

    private static GoldenCase golden(String id, boolean refusal) {
        return new GoldenCase(id, "S", refusal ? "refusal-permission" : "formal",
                new GoldenCase.Persona(refusal ? "SALES_STAFF" : "INVENTORY_PLANNER", List.of()),
                "Còn bao nhiêu EVAL-BOX-A4?", refusal ? List.of() : List.of("getStockLevels"), List.of(), false,
                refusal ? List.of() : List.of("75"), null, refusal ? List.of("75") : List.of(), null, refusal, null,
                List.of(), List.of(), List.of(new GoldenCase.Oracle("getStockLevels", Map.of("sku", "EVAL-BOX-A4"))),
                false);
    }

    private static GoldenRunner.CaseResult result(GoldenCase golden, List<String> leaks, int requests) {
        return new GoldenRunner.CaseResult(golden, 200, null, "Kho HCM còn 75 cái.", List.of(),
                List.of("getStockLevels"), List.of("OK"), List.of(), leaks, List.of(), true, true, List.of(),
                List.of(), true, 900, requests, 800, 0, 30);
    }
}
