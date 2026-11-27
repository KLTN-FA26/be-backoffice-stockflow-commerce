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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link GoodsReceiptQueries} over {@link GoodsReceipts#list}, the very list the receipt screen reads, so its warehouse
 * rule and its producer and warehouse labels are applied there, once, for the screen and for this alike. What a row of
 * that list also carries - the ids of the production order and the warehouse, the delivery note and who received and
 * confirmed it - stays here.
 */
@Service
@Transactional(readOnly = true)
class GoodsReceiptQueriesImpl implements GoodsReceiptQueries {

    /**
     * Domain statuses no receipt can be in any more and so not offered: PENDING_APPROVAL, which
     * {@code ck_goods_receipts_status} no longer allows (purchasing went with 4A-8, and with it the over-receipt
     * approval).
     */
    static final Set<GoodsReceiptStatus> LEGACY = EnumSet.of(GoodsReceiptStatus.PENDING_APPROVAL);

    private final GoodsReceipts receipts;
    private final com.stockflow.procurement.internal.repository.ReturnReceiptSearch returns;

    GoodsReceiptQueriesImpl(GoodsReceipts receipts,
                            com.stockflow.procurement.internal.repository.ReturnReceiptSearch returns) {
        // The api's vocabulary is a copy of the domain's, so another module can name a status without importing it. A
        // status added to one and not the other would be refused or shown wrongly; it stops the start instead. The
        // legacy constants are the domain's only, kept to name an old value read back, never to be asked for.
        if (!Set.copyOf(STATUSES).equals(Arrays.stream(GoodsReceiptStatus.values())
                .filter(status -> !LEGACY.contains(status)).map(Enum::name).collect(Collectors.toSet()))) {
            throw new IllegalStateException("GoodsReceiptQueries.STATUSES no longer matches GoodsReceiptStatus");
        }
        this.receipts = receipts;
        this.returns = returns;
    }

    @Override
    public PageResponse<GoodsReceiptSummary> receipts(List<String> statuses, String number,
                                                      java.util.UUID warehouseId, int limit) {
        if (limit < 1 || limit > MAX_RECEIPTS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "limit must be between 1 and " + MAX_RECEIPTS + ", not " + limit);
        }
        List<GoodsReceiptStatus> wanted = statuses == null ? List.of()
                : statuses.stream().map(GoodsReceiptQueriesImpl::status).toList();
        String text = number == null || number.isBlank() ? null : number.strip();
        // receiptType null: production receipts and the legacy purchase ones alike; return receipts are their own
        // aggregate and never on this list (GoodsReceiptJpaEntity excludes them).
        return receipts.list(wanted, warehouseId, text, null, null, null, null, 0, limit, null)
                .map(row -> new GoodsReceiptSummary(row.id(), row.number(),
                        row.receiptType() == null ? null : row.receiptType().name(), row.productionOrderCode(),
                        row.producerName(), row.warehouseCode(), row.status().name(), row.receivedAt(),
                        row.confirmedAt(), row.closedAt()));
    }

    @Override
    public List<ReturnReceiptRef> openReturnReceiptsOf(java.util.UUID stockPackageId) {
        if (stockPackageId == null) {
            return List.of();
        }
        return returns.draftsListing(stockPackageId).stream().map(row -> new ReturnReceiptRef(row.id(), row.number(),
                row.status().name(), row.warehouseId(), row.quarantineLocationId())).toList();
    }

    private static GoodsReceiptStatus status(String name) {
        if (name == null || !STATUSES.contains(name)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A goods receipt status is one of " + STATUSES + ", not " + name);
        }
        return GoodsReceiptStatus.valueOf(name);
    }
}
