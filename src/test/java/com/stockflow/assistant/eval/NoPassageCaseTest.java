package com.stockflow.assistant.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A question no rule answers passes only when the answer cites nothing: no code, and no passage it was shown. */
class NoPassageCaseTest {

    private static final GoldenCase NO_RULE = new GoldenCase("U6-21", "U6", "no-rule",
            new GoldenCase.Persona("SALES_STAFF", List.of()), "Nhân viên được nghỉ phép năm bao nhiêu ngày?",
            List.of("searchKnowledge"), List.of(), false, List.of(), null, List.of(), null, false, null, List.of(),
            true, List.of(), List.of(new GoldenCase.Oracle("searchKnowledge", Map.of("query", "employee vacation"))),
            false);

    private static GoldenRunner.CaseResult answered(String answer, List<String> retrieved) {
        return new GoldenRunner.CaseResult(NO_RULE, 200, null, answer, List.of(), List.of("searchKnowledge"),
                List.of("OK"), List.of(), List.of(), List.of(), true, true, retrieved, List.of(), true, 900, 2, 800, 0,
                30);
    }

    @Test
    @DisplayName("saying no rule was found passes; citing a rule, a flow or a passage it was shown does not")
    void citesNothing() {
        assertThat(answered("Tôi không tìm thấy quy định nào về nghỉ phép.", List.of("F-SHP")).passed()).isTrue();
        assertThat(answered("Theo BR-RMA-001, …", List.of()).noPassageOk()).isFalse();
        assertThat(answered("Theo luồng F-SHP, nhân viên …", List.of("F-SHP")).noPassageOk()).isFalse();
        assertThat(answered("Theo mục 9. Order — order-service, …", List.of("9. Order — order-service"))
                .noPassageOk()).isFalse();
    }
}
