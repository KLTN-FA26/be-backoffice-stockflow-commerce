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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The fabrication gates against a model that lies: whatever it is asked, it answers from nothing with codes and a
 * count, as a small local model did ("Có 2 mã dưới điểm đặt hàng: CUP-A và LID-B", copied from the old system
 * prompt). Under the oracle those gates are green by construction, since the oracle only repeats what the tools
 * returned; this shows they would be red for the model the P9 report measures, and that the loop asked it to look the
 * data up before showing the answer, flagged.
 */
@SecuredIntegrationTest
class AssistantFabricationHarnessTest {

    private static final String LIE = "Có 2 mã dưới điểm đặt hàng: CUP-A và LID-B. LID-B nên đặt trước.";

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
    @DisplayName("a model inventing data without a lookup fails the fabrication gates on every case it answers, after "
            + "the loop asked it once to look the data up")
    void aLyingModelIsCaught() throws Exception {
        GoldenFixtures fixtures = new GoldenFixtures(jdbc, tx, entityManager, receipts, inventory, consumption,
                catalog);
        Map<String, String> values = fixtures.ensure();
        when(model.available()).thenReturn(true);
        when(model.next(any())).thenReturn(new LanguageModel.Reply(new Turn.ModelTurn("{\"lie\":true}", LIE,
                List.of()), LanguageModel.StopReason.END_TURN, new LanguageModel.Usage(1, 1, 0, 0), "liar"));
        when(model.closingTurn(anyString())).thenAnswer(call -> new Turn.ModelTurn("{}", call.getArgument(0),
                List.of()));
        GoldenRunner runner = new GoldenRunner(new SecuredMvc(mockMvc, decoder), jdbc, fixtures);

        List<GoldenRunner.CaseResult> results = new ArrayList<>();
        for (GoldenCase golden : GoldenSet.load()) {
            if (golden.expectNoData() || "U1-01".equals(golden.id())) {
                results.add(runner.run(golden.resolve(values)));
            }
        }
        GoldenReport report = new GoldenReport(results, List.of(), "liar");

        assertThat(results).hasSizeGreaterThanOrEqualTo(5).allSatisfy(result -> {
            assertThat(result.answer()).as(result.golden().id()).isEqualTo(LIE);
            assertThat(result.fabricated()).as(result.golden().id()).isTrue();
            assertThat(result.ungroundedCodes()).as(result.golden().id()).containsExactly("CUP-A", "LID-B");
            assertThat(result.passed()).as(result.golden().id()).isFalse();
            assertThat(result.modelRequests()).as("%s: the answer, then the one request to look it up",
                    result.golden().id()).isEqualTo(2);
        });
        assertThat(results).filteredOn(result -> result.golden().expectNoData())
                .allSatisfy(result -> assertThat(result.noDataOk()).isFalse());
        assertThat(report.gates()).filteredOn(gate -> gate.name().startsWith("Answers stating data without a lookup")
                        || gate.name().startsWith("Answers where the truth is 'none'"))
                .hasSize(2).allSatisfy(gate -> {
                    assertThat(gate.hard()).isTrue();
                    assertThat(gate.holds()).isFalse();
                });
    }
}
