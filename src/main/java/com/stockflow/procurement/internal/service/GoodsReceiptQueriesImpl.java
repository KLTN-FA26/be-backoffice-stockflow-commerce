package com.stockflow.procurement.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.procurement.api.GoodsReceiptQueries;
import com.stockflow.procurement.api.GoodsReceiptSummary;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link GoodsReceiptQueries} over {@link GoodsReceipts#list}, the very list the receipt screen reads, so its warehouse
 * rule and its supplier and warehouse labels are applied there, once, for the screen and for this alike. What a row of
 * that list also carries - the ids of the order, the supplier and the warehouse, the delivery note and who received and
 * confirmed it - stays here.
 */
@Service
@Transactional(readOnly = true)
class GoodsReceiptQueriesImpl implements GoodsReceiptQueries {

    private final GoodsReceipts receipts;

    GoodsReceiptQueriesImpl(GoodsReceipts receipts) {
        // The api's vocabulary is a copy of the domain's, so another module can name a status without importing it. A
        // status added to one and not the other would be refused or shown wrongly; it stops the start instead.
        if (!Set.copyOf(STATUSES).equals(Arrays.stream(GoodsReceiptStatus.values()).map(Enum::name)
                .collect(Collectors.toSet()))) {
            throw new IllegalStateException("GoodsReceiptQueries.STATUSES no longer matches GoodsReceiptStatus");
        }
        this.receipts = receipts;
    }

    @Override
    public PageResponse<GoodsReceiptSummary> receipts(List<String> statuses, String number, int limit) {
        if (limit < 1 || limit > MAX_RECEIPTS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "limit must be between 1 and " + MAX_RECEIPTS + ", not " + limit);
        }
        List<GoodsReceiptStatus> wanted = statuses == null ? List.of()
                : statuses.stream().map(GoodsReceiptQueriesImpl::status).toList();
        String text = number == null || number.isBlank() ? null : number.strip();
        return receipts.list(wanted, null, null, text, null, null, 0, limit, null)
                .map(row -> new GoodsReceiptSummary(row.id(), row.number(), row.purchaseOrderNumber(),
                        row.supplierName(), row.warehouseCode(), row.status().name(), row.receivedAt(),
                        row.confirmedAt(), row.closedAt()));
    }

    private static GoodsReceiptStatus status(String name) {
        if (name == null || !STATUSES.contains(name)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A goods receipt status is one of " + STATUSES + ", not " + name);
        }
        return GoodsReceiptStatus.valueOf(name);
    }
}
