package com.stockflow.assistant.eval;

import com.stockflow.assistant.internal.service.AssistantProperties;
import com.stockflow.catalog.internal.service.CatalogProjectionService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.consumption.PickConsumption;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The golden set against the real model (design §10.3). Not part of {@code mvn test}: it costs money and is not
 * deterministic. Run it on any change to the system prompt, a tool, or the model:
 *
 * <pre>
 * ANTHROPIC_API_KEY=... mvn test -Dtest=AssistantGoldenSetEval -Dassistant.eval=true
 * </pre>
 *
 * <p>Security is on and the data is the demo seed plus {@link GoldenFixtures}, so refusals are real refusals. The
 * report — the gates, every case, every answer — is written to {@code target/assistant-eval/report.md} with a CSV
 * beside it; it is what goes into the capstone report. The run fails when a gate does not hold.</p>
 *
 * <p>{@code -Dassistant.eval.model=claude-sonnet-5-5} runs the same set on another model, to compare cost and
 * quality (design §13 AQ-07).</p>
 */
@Tag("assistant-eval")
@EnabledIfSystemProperty(named = "assistant.eval", matches = "true")
@SecuredIntegrationTest
@TestPropertySource(properties = {
        "stockflow.assistant.enabled=true",
        "stockflow.assistant.model=${assistant.eval.model:claude-opus-5-5}"})
class AssistantGoldenSetEval {

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
        assertThat(properties.usable()).as("set ANTHROPIC_API_KEY to run the evaluation").isTrue();
        GoldenFixtures fixtures = new GoldenFixtures(jdbc, tx, entityManager, receipts, inventory, consumption,
                catalog);
        Map<String, String> values = fixtures.ensure();
        GoldenRunner runner = new GoldenRunner(new SecuredMvc(mockMvc, decoder), jdbc, fixtures);

        List<GoldenRunner.CaseResult> results = new ArrayList<>();
        for (GoldenCase golden : GoldenSet.load()) {
            results.add(runner.run(golden.resolve(values)));
        }
        GoldenReport report = new GoldenReport(results, properties.model());
        Path written = report.write(Path.of("target", "assistant-eval"));
        System.out.println("Assistant golden set: " + report + " - report " + written.toAbsolutePath());

        assertThat(report.allGatesHold()).as("%s (report: %s)", report, written.toAbsolutePath()).isTrue();
    }
}
