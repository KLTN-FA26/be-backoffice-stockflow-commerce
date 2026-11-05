package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.procurement.api.GoodsReceiptQueries;
import com.stockflow.procurement.api.GoodsReceiptSummary;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link GoodsReceiptQueries} reads the receipt screen's own list and hands on a row without the people on it.
 */
class GoodsReceiptQueriesImplTest {

    private final GoodsReceipts receipts = mock(GoodsReceipts.class);
    private final GoodsReceiptQueriesImpl queries = new GoodsReceiptQueriesImpl(receipts);

    private static GoodsReceipts.Row row(GoodsReceiptStatus status) {
        Instant received = Instant.parse("2026-10-28T02:30:00Z");
        return new GoodsReceipts.Row(UUID.randomUUID(), "GR-20261028-0001", UUID.randomUUID(), "PO-HCM-0007",
                UUID.randomUUID(), "Xưởng gỗ Việt", UUID.randomUUID(), "HCM", "Kho Hồ Chí Minh", status, "PGH-778",
                received, UUID.randomUUID(), "Trần Thị Nhận", received.plusSeconds(600), UUID.randomUUID(),
                "Lê Văn Xác Nhận", null, false);
    }

    @Test
    @DisplayName("the statuses and the number go to the screen's list as they are, newest first, one page")
    void asksTheScreensList() {
        when(receipts.list(anyList(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        queries.receipts(List.of("IN_QC", "IN_PUTAWAY"), " GR-2026 ", 20);

        verify(receipts).list(eq(List.of(GoodsReceiptStatus.IN_QC, GoodsReceiptStatus.IN_PUTAWAY)), isNull(),
                isNull(), eq("GR-2026"), isNull(), isNull(), isNull(), eq(0), eq(20), isNull());
    }

    @Test
    @DisplayName("a row keeps where the receipt stands and drops who received and confirmed it")
    void row() {
        GoodsReceipts.Row row = row(GoodsReceiptStatus.IN_QC);
        when(receipts.list(anyList(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(PageResponse.of(List.of(row), 0, 5, 9));

        PageResponse<GoodsReceiptSummary> page = queries.receipts(List.of(), null, 5);

        assertThat(page.totalElements()).isEqualTo(9);
        GoodsReceiptSummary summary = page.items().getFirst();
        assertThat(summary.receiptId()).isEqualTo(row.id());
        assertThat(summary.number()).isEqualTo("GR-20261028-0001");
        assertThat(summary.purchaseOrderNumber()).isEqualTo("PO-HCM-0007");
        assertThat(summary.supplierName()).isEqualTo("Xưởng gỗ Việt");
        assertThat(summary.withoutPurchaseOrder()).as("a receipt against a PO").isFalse();
        assertThat(summary.warehouseCode()).isEqualTo("HCM");
        assertThat(summary.status()).isEqualTo("IN_QC");
        assertThat(summary.receivedAt()).isEqualTo(row.receivedAt());
        assertThat(summary.confirmedAt()).isEqualTo(row.confirmedAt());
        assertThat(summary.closedAt()).isNull();
        assertThat(summary.toString()).doesNotContain("Trần Thị Nhận", "Lê Văn Xác Nhận", "PGH-778",
                row.receivedBy().toString(), row.confirmedBy().toString());
    }

    @Test
    @DisplayName("an unknown status or a limit out of range is refused before anything is read")
    void refusals() {
        assertThatThrownBy(() -> queries.receipts(List.of("RECEIVED"), null, 5))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> queries.receipts(List.of(), null, 0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> queries.receipts(List.of(), null, GoodsReceiptQueries.MAX_RECEIPTS + 1))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(receipts);
    }

    @Test
    @DisplayName("the api's statuses are the domain's, so another module can name every one and only those")
    void vocabularyMatchesTheDomain() {
        assertThat(GoodsReceiptQueries.STATUSES)
                .containsExactlyElementsOf(Arrays.stream(GoodsReceiptStatus.values()).map(Enum::name).toList());
        assertThat(GoodsReceiptQueries.STATUSES).containsAll(GoodsReceiptQueries.OPEN_STATUSES)
                .doesNotContain("RECEIVED");
        assertThat(GoodsReceiptQueries.OPEN_STATUSES).doesNotContain("CLOSED", "CANCELLED");
    }
}
