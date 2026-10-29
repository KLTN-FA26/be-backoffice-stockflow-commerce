package com.stockflow.assistant.eval;

import com.stockflow.assistant.internal.service.AssistantProperties;
import com.stockflow.catalog.internal.service.CatalogProjectionService;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.consumption.PickConsumption;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The golden set against the real model (design §10.3). Not part of {@code mvn test}: it costs money or a free
 * quota, and is not deterministic. Run it on any change to the system prompt, a tool, or the model:
 *
 * <pre>
 * ASSISTANT_PROVIDER=gemini ASSISTANT_MODEL=gemini-3.5-flash-lite ASSISTANT_API_KEY=... \
 *     mvn test -Dtest=AssistantGoldenSetEval -Dassistant.eval=true -Dassistant.eval.smoke=true
 * </pre>
 *
 * <p>The provider, the key and the model come from the environment, as for the application
 * ({@code ASSISTANT_PROVIDER}, {@code ASSISTANT_API_KEY}, {@code ASSISTANT_MODEL}); {@code -Dassistant.eval.model}
 * overrides the model, to compare cost and quality (design §13 AQ-07). Security is on and the data is the demo seed
 * plus {@link GoldenFixtures}, so refusals are real refusals.</p>
 *
 * <p><b>A run asks a part of the set and resumes.</b> {@link GoldenSelection} picks the cases. Each finished case is
 * one line of {@code assistant-eval-runs/<model>/results.jsonl} ({@code -Dassistant.eval.out} for another folder;
 * git ignores it, and {@code mvn clean} does not delete it), and a case already there is not asked again
 * ({@link GoldenResults}). The report — the gates, every case, every answer, the model requests each case cost — is
 * built from every line and written beside them as {@code report.md} and {@code results.csv}; it is what goes into the
 * capstone report.</p>
 *
 * <p><b>Only settled outcomes count.</b> The first {@code ASSISTANT_QUOTA_EXHAUSTED} stops the run: that case and
 * every one after it are NOT_RUN, in no gate, and the report says how many of the set ran. A provider failure (5xx,
 * 429) leaves its case NOT_RUN too, and three in a row stop the run. {@code ASSISTANT_MISCONFIGURED} (a wrong key or
 * model name) stops it and fails it: nothing would run. The run fails when a gate does not hold over the cases that
 * ran, and is aborted — reported as skipped, with how to continue — when cases it was asked for did not finish.</p>
 */
@Tag("assistant-eval")
@EnabledIfSystemProperty(named = "assistant.eval", matches = "true")
@SecuredIntegrationTest
@TestPropertySource(properties = {
        "stockflow.assistant.enabled=true",
        // ASSISTANT_MODEL as the application reads it; a literal default here would send a Claude model to Gemini.
        "stockflow.assistant.model=${assistant.eval.model:${ASSISTANT_MODEL:claude-opus-5-5}}"})
class AssistantGoldenSetEval {

    /** Failures of the provider in a row after which the rest of the run would only spend quota on the same failure. */
    static final int FAILURES_IN_A_ROW = 3;

    /** No case can run under these; going on would only repeat them. */
    private static final Set<String> CONFIGURATION = Set.of(ErrorCode.ASSISTANT_MISCONFIGURED.name(),
            ErrorCode.ASSISTANT_DISABLED.name());

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired GoodsReceipts receipts;
    @Autowired InventoryService inventory;
    @Autowired PickConsumption consumption;
    @Autowired CatalogProjectionService catalog;
    @Autowired AssistantProperties properties;

    @Test
    @DisplayName("the golden set against the real model meets every gate")
    void goldenSet() throws Exception {
        assertThat(properties.usable()).as("set ASSISTANT_API_KEY to run the evaluation (for a Google AI Studio key, "
                + "also ASSISTANT_PROVIDER=gemini and ASSISTANT_MODEL)").isTrue();
        List<GoldenCase> set = GoldenSet.load();
        GoldenSelection selection = GoldenSelection.fromSystemProperties(set);
        Path directory = Path.of(System.getProperty("assistant.eval.out",
                Path.of("assistant-eval-runs", properties.model().replaceAll("[^A-Za-z0-9._-]", "_")).toString()));
        GoldenResults stored = GoldenResults.open(directory.resolve("results.jsonl"), properties.model());
        GoldenFixtures fixtures = new GoldenFixtures(jdbc, tx, entityManager, receipts, inventory, consumption,
                catalog);
        Map<String, String> values = fixtures.ensure();
        GoldenRunner runner = new GoldenRunner(new SecuredMvc(mockMvc, decoder), jdbc, fixtures);

        List<GoldenRunner.CaseResult> results = new ArrayList<>();
        List<GoldenReport.NotRun> notRun = new ArrayList<>();
        List<String> unfinished = new ArrayList<>();
        String stopped = null;
        String misconfigured = null;
        int failures = 0;
        for (GoldenCase golden : set) {
            Optional<GoldenRunner.CaseResult> finished = stored.finished(golden);
            if (finished.isPresent()) {
                results.add(finished.get());
            } else if (!selection.includes(golden)) {
                notRun.add(new GoldenReport.NotRun(golden, "not selected", 0));
            } else if (stopped != null) {
                notRun.add(new GoldenReport.NotRun(golden, stopped, 0));
                unfinished.add(golden.id());
            } else {
                GoldenRunner.CaseResult result = runner.run(golden.resolve(values));
                if (result.settled() && !configuration(result.errorCode())) {
                    stored.append(golden, result);
                    results.add(result);
                    failures = 0;
                    System.out.println("Assistant golden set: " + golden.id()
                            + (result.passed() ? " passed, " : " failed, ") + result.modelRequests() + " model requests");
                    continue;
                }
                String reason = result.errorCode() != null ? result.errorCode() : "HTTP " + result.httpStatus();
                System.out.println("Assistant golden set: " + golden.id() + " not run, " + reason);
                notRun.add(new GoldenReport.NotRun(golden, reason, result.modelRequests()));
                unfinished.add(golden.id());
                failures++;
                if (ErrorCode.ASSISTANT_QUOTA_EXHAUSTED.name().equals(result.errorCode())) {
                    stopped = "stopped after the model's quota ran out at " + golden.id();
                } else if (configuration(result.errorCode())) {
                    stopped = "stopped after " + result.errorCode() + " at " + golden.id();
                    misconfigured = result.errorCode() + " at " + golden.id();
                } else if (failures >= FAILURES_IN_A_ROW) {
                    stopped = "stopped after " + FAILURES_IN_A_ROW + " provider failures in a row, the last at "
                            + golden.id();
                }
            }
        }
        GoldenReport report = new GoldenReport(results, notRun, properties.model() + " (" + properties.provider()
                + ")");
        Path written = report.write(directory);
        System.out.println("Assistant golden set: " + report + " - report " + written.toAbsolutePath()
                + (stored.ignored() == 0 ? "" : " (" + stored.ignored() + " stored lines of another model or "
                + "unreadable were left out)"));

        assertThat(misconfigured).as("the provider refused the key or the model name (%s): fix ASSISTANT_PROVIDER, "
                + "ASSISTANT_API_KEY or ASSISTANT_MODEL", misconfigured).isNull();
        assertThat(report.allGatesHold()).as("%s (report: %s)", report, written.toAbsolutePath()).isTrue();
        String why = stopped == null ? "the provider failed on them" : stopped;
        Assumptions.assumeTrue(unfinished.isEmpty(), () -> "%d of %d run; %d asked for did not finish (%s), %s. "
                .formatted(report.run(), report.total(), unfinished.size(), String.join(", ", unfinished), why)
                + "Run the same command again (tomorrow, for a daily quota): the cases in "
                + stored.file().toAbsolutePath() + " are kept and not asked again.");
    }

    private static boolean configuration(String errorCode) {
        return errorCode != null && CONFIGURATION.contains(errorCode);
    }
}
