package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockflow.assistant.eval.GoldenCase;
import com.stockflow.assistant.internal.domain.AnswerFeedbackRepository;
import com.stockflow.assistant.internal.domain.FeedbackVerdict;
import com.stockflow.assistant.internal.domain.MarkedAnswer;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedbackReviewTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-27T03:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper json = new ObjectMapper();
    private final AnswerFeedbackRepository verdicts = mock(AnswerFeedbackRepository.class);
    private final FeedbackReview review = new FeedbackReview(verdicts, json, CLOCK);

    private static MarkedAnswer marked(MarkedAnswer.Rater rater, List<MarkedAnswer.MarkedTool> tools) {
        return new MarkedAnswer(UUID.fromString("0193c000-0000-7000-8000-0000000000ab"), UUID.randomUUID(),
                UUID.randomUUID(), FeedbackVerdict.DOWN, "Sai số tồn", Instant.parse("2026-11-26T08:00:00Z"), rater,
                "Tồn EVAL-BOX-A4 ở HCM?", "Còn 75 thùng.", Instant.parse("2026-11-26T07:59:00Z"), "gemini", tools);
    }

    @Test
    @DisplayName("a marked answer becomes a golden case to complete: persona from the rater's first role and "
            + "warehouses, the tools that answered, the calls of one turn as one step, useCase TODO, facts empty")
    void goldenCase() throws Exception {
        MarkedAnswer answer = marked(new MarkedAnswer.Rater("WAREHOUSE_MANAGER+WAREHOUSE_STAFF", List.of("HCM")),
                List.of(new MarkedAnswer.MarkedTool(1, "getStockLevels", "{\"sku\":\"EVAL-BOX-A4\"}", ToolOutcome.OK),
                        new MarkedAnswer.MarkedTool(1, "getStockLots", "{\"sku\":\"EVAL-BOX-A4\"}",
                                ToolOutcome.DENIED),
                        new MarkedAnswer.MarkedTool(3, "traceLot", "{\"lot\":\"PO-1\"}", ToolOutcome.OK)));

        ObjectNode golden = review.goldenCase(answer);

        assertThat(golden.get("id").asText()).isEqualTo("FB-000000ab");
        assertThat(golden.get("useCase").asText()).isEqualTo("TODO");
        assertThat(golden.at("/persona/role").asText()).isEqualTo("WAREHOUSE_MANAGER");
        assertThat(golden.at("/persona/warehouses/0").asText()).isEqualTo("HCM");
        assertThat(golden.get("question").asText()).isEqualTo("Tồn EVAL-BOX-A4 ở HCM?");
        assertThat(golden.get("expectTools").toString()).isEqualTo("[\"getStockLevels\",\"traceLot\"]");
        assertThat(golden.get("facts").size()).isZero();
        assertThat(golden.get("oracle").isArray()).as("two turns: a chain").isTrue();
        assertThat(golden.at("/oracle/0/tool").asText()).isEqualTo("getStockLevels");
        assertThat(golden.at("/oracle/0/arguments/sku").asText()).isEqualTo("EVAL-BOX-A4");
        assertThat(golden.at("/oracle/0/together/0/tool").asText()).isEqualTo("getStockLots");
        assertThat(golden.at("/oracle/1/tool").asText()).isEqualTo("traceLot");

        // Read as GoldenSet reads golden-set.json: a pasted case is one of its own, no field unknown to it.
        GoldenCase parsed = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
                .treeToValue(golden, GoldenCase.class);
        assertThat(parsed.oracle()).hasSize(2);
        assertThat(parsed.persona().warehouses()).containsExactly("HCM");
    }

    @Test
    @DisplayName("one call is one oracle object; no call expects no tools; an unrecorded or run-time role is TODO")
    void edges() {
        ObjectNode single = review.goldenCase(marked(new MarkedAnswer.Rater("OTHER", List.of()),
                List.of(new MarkedAnswer.MarkedTool(1, "findOrder", "{\"orderNumber\":\"SO-1\"}", ToolOutcome.OK))));
        assertThat(single.get("oracle").isObject()).isTrue();
        assertThat(single.at("/persona/role").asText()).isEqualTo("TODO");

        ObjectNode none = review.goldenCase(marked(null, List.of()));
        assertThat(none.get("expectNoTools").asBoolean()).isTrue();
        assertThat(none.has("expectTools")).isFalse();
        assertThat(none.get("oracle").isArray() && none.get("oracle").isEmpty()).isTrue();
        assertThat(none.at("/persona/role").asText()).isEqualTo("TODO");
    }

    @Test
    @DisplayName("the period is business days, both included; the last 30 by default; refused backwards, and a page "
            + "larger than 50")
    void periods() {
        when(verdicts.marked(any(), any(), any(), anyInt(), anyInt())).thenReturn(PageResponse.empty(0, 20));

        review.marked(FeedbackVerdict.DOWN, null, null, 0, 20);
        verify(verdicts).marked(eq(FeedbackVerdict.DOWN), eq(Instant.parse("2026-10-28T17:00:00Z")),
                eq(Instant.parse("2026-11-27T17:00:00Z")), eq(0), eq(20));

        assertThatThrownBy(() -> review.marked(FeedbackVerdict.DOWN, LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 1), 0, 20))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> review.marked(FeedbackVerdict.DOWN, null, null, 0, 51))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNSUPPORTED_PARAMETER));
    }
}
