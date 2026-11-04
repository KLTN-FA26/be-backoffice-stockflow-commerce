package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.common.api.PageResponse;
import com.stockflow.procurement.api.GoodsReceiptQueries;
import com.stockflow.procurement.api.GoodsReceiptSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * findGoodsReceipts (ROLE-7): receiving's and QC's queue asked for by status, and a receipt's rows as the list shows
 * them.
 */
class FindGoodsReceiptsToolTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Instant RECEIVED = Instant.parse("2026-10-28T02:30:00Z");

    private final GoodsReceiptQueries queries = mock(GoodsReceiptQueries.class);
    private final FindGoodsReceiptsTool tool = new FindGoodsReceiptsTool(queries);

    private static ToolArguments arguments(String status, String number, int limit) {
        return ToolArguments.parse(JSON, "{\"status\":\"" + status + "\",\"receiptNumber\":\"" + number
                + "\",\"limit\":" + limit + "}");
    }

    private static GoodsReceiptSummary receipt(String number, String status) {
        return new GoodsReceiptSummary(UUID.randomUUID(), number, "PO-HCM-0007", "Xưởng gỗ Việt", "HCM", status,
                RECEIVED, "DRAFT".equals(status) ? null : RECEIVED.plusSeconds(600),
                "CLOSED".equals(status) ? RECEIVED.plusSeconds(7_200) : null);
    }

    private void listed(long total, GoodsReceiptSummary... receipts) {
        when(queries.receipts(anyList(), any(), anyInt())).thenReturn(PageResponse.of(List.of(receipts), 0, 50,
                total));
    }

    @Test
    @DisplayName("OPEN is the work still to do, ANY every status, one status itself")
    void statuses() {
        listed(0);

        tool.run(arguments("OPEN", "", 50));
        tool.run(arguments("ANY", "", 50));
        tool.run(arguments("IN_PUTAWAY", "", 20));

        verify(queries).receipts(GoodsReceiptQueries.OPEN_STATUSES, null, 50);
        verify(queries).receipts(List.of(), null, 50);
        verify(queries).receipts(List.of("IN_PUTAWAY"), null, 20);
    }

    @Test
    @DisplayName("the receipt number text is passed on trimmed; empty is no filter")
    void byNumber() {
        listed(0);

        tool.run(arguments("ANY", " GR-20261028 ", 5));

        verify(queries).receipts(List.of(), "GR-20261028", 5);
    }

    @Test
    @DisplayName("a row as the list shows it: number, PO, supplier, warehouse, status and its three moments")
    void rows() {
        listed(2, receipt("GR-20261028-0001", "IN_QC"), receipt("GR-20261028-0002", "CLOSED"));

        FindGoodsReceiptsTool.Answer answer = tool.run(arguments("ANY", "", 50));

        assertThat(answer.receipts()).extracting(FindGoodsReceiptsTool.Row::receiptNumber,
                        FindGoodsReceiptsTool.Row::purchaseOrderNumber, FindGoodsReceiptsTool.Row::supplierName,
                        FindGoodsReceiptsTool.Row::warehouseCode, FindGoodsReceiptsTool.Row::status)
                .containsExactly(tuple("GR-20261028-0001", "PO-HCM-0007", "Xưởng gỗ Việt", "HCM", "IN_QC"),
                        tuple("GR-20261028-0002", "PO-HCM-0007", "Xưởng gỗ Việt", "HCM", "CLOSED"));
        assertThat(answer.receipts().get(1).closedAt()).isEqualTo(RECEIVED.plusSeconds(7_200));
        assertThat(answer.receipts().getFirst().closedAt()).isNull();
        assertThat(answer.total()).isEqualTo(2);
        assertThat(answer.truncated()).isFalse();
    }

    @Test
    @DisplayName("more receipts than returned is said: total counts them all")
    void truncated() {
        listed(73, receipt("GR-20261028-0001", "IN_QC"));

        FindGoodsReceiptsTool.Answer answer = tool.run(arguments("OPEN", "", 1));

        assertThat(answer.total()).isEqualTo(73);
        assertThat(answer.truncated()).isTrue();
    }

    @Test
    @DisplayName("CONFIRMED is not offered: no receipt stays in it; every other status of the api is")
    void offered() {
        assertThat(FindGoodsReceiptsTool.STATUSES).containsExactly("OPEN", "ANY", "DRAFT", "IN_QC", "IN_PUTAWAY",
                "CLOSED", "CANCELLED");
        assertThatThrownBy(() -> tool.run(arguments("CONFIRMED", "", 5))).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("status must be one of");
        verifyNoInteractions(queries);
    }

    @Test
    @DisplayName("fifty receipts of long supplier names, every moment set, stay well under the result limit")
    void fiftyRowsFit() {
        String longName = "Công ty TNHH Sản xuất Thương mại và Dịch vụ Bao bì Giấy Kraft Miền Nam ".repeat(3);
        GoodsReceiptSummary[] rows = java.util.stream.IntStream.range(0, FindGoodsReceiptsTool.MAX_ROWS)
                .mapToObj(i -> new GoodsReceiptSummary(UUID.randomUUID(), "GR-20261028-%04d".formatted(i),
                        "PO-HCM-20261028-%04d".formatted(i), longName, "HCM", "IN_PUTAWAY", RECEIVED,
                        RECEIVED.plusSeconds(600), RECEIVED.plusSeconds(7_200)))
                .toArray(GoodsReceiptSummary[]::new);
        listed(200, rows);
        AssistantTools tools = new AssistantTools(List.of(tool), JsonMapper.builder()
                .addModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializationInclusion(JsonInclude.Include.NON_NULL).build());

        ToolExecution execution = tools.execute(new ToolCall("toolu_1", "findGoodsReceipts",
                "{\"status\":\"ANY\",\"receiptNumber\":\"\",\"limit\":50}"));

        assertThat(execution.outcome()).isEqualTo(ToolOutcome.OK);
        assertThat(execution.result()).contains("…").contains("+07:00");
        assertThat(execution.result().length())
                .as("well under the limit above which the model gets RESULT_TOO_LARGE and no receipt at all")
                .isLessThan(ToolExecution.MAX_RESULT_LENGTH * 9 / 10);
    }

    @Test
    @DisplayName("a receipt's id stays out of the answer")
    void noIds() throws Exception {
        GoodsReceiptSummary row = receipt("GR-20261028-0001", "IN_QC");
        listed(1, row);

        assertThat(JSON.writeValueAsString(tool.run(arguments("ANY", "", 50))))
                .doesNotContain(row.receiptId().toString());
    }
}
