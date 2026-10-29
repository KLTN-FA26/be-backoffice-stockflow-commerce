package com.stockflow.assistant.eval;

import com.stockflow.assistant.internal.domain.LanguageModel;
import com.stockflow.assistant.internal.domain.Turn;
import com.stockflow.catalog.internal.service.CatalogProjectionService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.consumption.PickConsumption;
import com.stockflow.procurement.internal.service.GoodsReceipts;
import com.stockflow.support.SecuredIntegrationTest;
import com.stockflow.support.SecuredMvc;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The golden set against the scripted {@link OracleModel}, in every {@code mvn test}: no API key, no cost, no
 * non-determinism.
 *
 * <p>It keeps the golden set honest as the code under it changes. Every case must pass: the fixtures exist, each
 * fact query matches what the tool returns, and — the part that matters most — a persona's tool results never contain
 * what their screens would hide. A failure here is a bug in the tools, the fixtures or the set, never "the model was
 * unlucky". {@code AssistantGoldenSetEval} runs the same cases against the real model.</p>
 */
@SecuredIntegrationTest
class AssistantGoldenSetHarnessTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean LanguageModel model;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager entityManager;
    @Autowired GoodsReceipts receipts;
    @Autowired InventoryService inventory;
    @Autowired PickConsumption consumption;
    @Autowired CatalogProjectionService catalog;

    @Test
    @DisplayName("every golden case passes against the oracle: fixtures, fact queries and permissions agree")
    void goldenSetHoldsUnderTheOracle() throws Exception {
        GoldenFixtures fixtures = new GoldenFixtures(jdbc, tx, entityManager, receipts, inventory, consumption,
                catalog);
        Map<String, String> values = fixtures.ensure();
        OracleModel oracle = new OracleModel();
        when(model.available()).thenReturn(true);
        when(model.next(any())).thenAnswer(call -> oracle.next(call.getArgument(0)));
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{}", call.getArgument(0),
                List.of()));
        GoldenRunner runner = new GoldenRunner(new SecuredMvc(mockMvc, decoder), jdbc, fixtures);

        List<GoldenRunner.CaseResult> results = new ArrayList<>();
        for (GoldenCase golden : GoldenSet.load()) {
            GoldenCase resolved = golden.resolve(values);
            oracle.use(resolved);
            results.add(runner.run(resolved));
        }
        GoldenReport report = new GoldenReport(results, List.of(), "oracle");
        Path written = report.write(Path.of("target", "assistant-eval", "oracle"));

        assertThat(results).as("the golden set has its size after the review of 28/10").hasSizeGreaterThanOrEqualTo(50);
        assertThat(results).filteredOn(r -> !r.passed())
                .as("cases failing under the oracle (report: %s)", written.toAbsolutePath())
                .extracting(r -> r.golden().id() + " missing=" + r.missing() + " leaks=" + r.leaks() + " tools="
                        + r.tools() + " outcomes=" + r.outcomes() + " http=" + r.httpStatus() + " "
                        + r.errorCode())
                .isEmpty();
        assertThat(results).as("the oracle makes one request per step and one for each answer")
                .allSatisfy(r -> assertThat(r.modelRequests()).isEqualTo(requests(r.golden())));
        assertThat(report.allGatesHold()).as(report.toString()).isTrue();
    }

    /** What a case costs the oracle: a request per call it scripts, and one answer per question. */
    private static int requests(GoldenCase golden) {
        return golden.oracle().size() + 1 + golden.earlier().stream().mapToInt(turn -> turn.oracle().size() + 1).sum();
    }
}
