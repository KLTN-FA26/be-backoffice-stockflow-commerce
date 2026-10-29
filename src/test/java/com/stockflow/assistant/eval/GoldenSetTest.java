package com.stockflow.assistant.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The golden set itself: what every case of a kind must state, so a new case cannot pass with any answer. */
class GoldenSetTest {

    private final List<GoldenCase> set = GoldenSet.load();

    @Test
    @DisplayName("a refusal case expects no tool and names what must not leak; its oracle still calls the tool")
    void refusals() {
        assertThat(set).filteredOn(GoldenCase::expectRefusal).isNotEmpty().allSatisfy(refusal -> {
            assertThat(refusal.expectTools()).as(refusal.id()).isEmpty();
            assertThat(!refusal.forbidden().isEmpty() || refusal.forbiddenQuery() != null).as(refusal.id()).isTrue();
            assertThat(refusal.oracle()).as(refusal.id()).isNotEmpty();
        });
    }

    @Test
    @DisplayName("about ten smoke cases, one free-tier day of one model, across the use cases")
    void smoke() {
        List<GoldenCase> smoke = set.stream().filter(GoldenCase::smoke).toList();

        assertThat(smoke).hasSizeBetween(8, 12);
        assertThat(smoke.stream().map(GoldenCase::useCase).distinct().count()).isGreaterThanOrEqualTo(8);
        assertThat(smoke).anyMatch(GoldenCase::expectRefusal).anyMatch(golden -> golden.invariantQuery() != null);
    }

    @Test
    @DisplayName("a follow-up and a chain are written as earlier questions and as oracle steps")
    void followUpsAndChains() {
        GoldenCase followUp = set.stream().filter(golden -> golden.id().equals("S-07")).findFirst().orElseThrow();
        assertThat(followUp.earlier()).singleElement().satisfies(earlier ->
                assertThat(earlier.oracle()).extracting(GoldenCase.Oracle::tool).containsExactly("getStockLevels"));
        assertThat(followUp.oracle()).extracting(GoldenCase.Oracle::tool).containsExactly("getStockLots");

        GoldenCase chain = set.stream().filter(golden -> golden.id().equals("U7-03")).findFirst().orElseThrow();
        assertThat(chain.oracle()).extracting(GoldenCase.Oracle::tool)
                .containsExactly("searchCatalog", "getStockLevels");
    }

    @Test
    @DisplayName("QC_STAFF of HCM is asked about the lots it may see and refused what it may not")
    void qualityControl() {
        assertThat(set).filteredOn(golden -> golden.persona().role().equals("QC_STAFF"))
                .allSatisfy(golden -> assertThat(golden.persona().warehouses()).containsExactly("HCM"))
                .extracting(golden -> golden.oracle().getFirst().tool() + (golden.expectRefusal() ? " refused" : ""))
                .contains("getStockLots", "findExpiringLots refused", "traceLot refused", "findOrder refused");
    }
}
