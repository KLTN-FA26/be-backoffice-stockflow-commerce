package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.inventory.api.TransferDeliveryMode;
import com.stockflow.inventory.api.TransferOrderQueries;
import com.stockflow.inventory.internal.domain.TransferStatus;
import com.stockflow.inventory.internal.domain.WarehouseDirectory;
import com.stockflow.inventory.internal.repository.TransferOrderSearch;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link TransferOrderQueries} over {@link TransferOrders#list}, the very list the transfer screen reads, so its
 * warehouse rule is applied there, once, for the screen and for this alike; the earliest-expected order reads the
 * screen's own search ({@link TransferOrderSearch}) under that order, the warehouse rule with it. The warehouses are
 * named by their prefix, each looked up once a page: a page names a handful at most.
 */
@Service
@Transactional(readOnly = true)
public class TransferOrderQueriesImpl implements TransferOrderQueries {

    /**
     * The earliest expected date first, the newest first among equals. None last: PostgreSQL sorts NULL last in an
     * ascending order, and Spring Data cannot ask for it on a Specification query (Hibernate refuses null precedence
     * in criteria queries).
     */
    private static final Sort EXPECTED_FIRST = Sort.by(Sort.Order.asc("expectedDate"), Sort.Order.desc("createdAt"));

    private final TransferOrders transfers;
    private final TransferOrderSearch search;
    private final WarehouseDirectory warehouses;

    TransferOrderQueriesImpl(TransferOrders transfers, TransferOrderSearch search, WarehouseDirectory warehouses) {
        // The api's vocabulary is a copy of the domain's, so another module can name a status without importing it. A
        // status added to one and not the other would be refused or shown wrongly; it stops the start instead.
        if (!Set.copyOf(STATUSES).equals(Arrays.stream(TransferStatus.values()).map(Enum::name)
                .collect(Collectors.toSet()))) {
            throw new IllegalStateException("TransferOrderQueries.STATUSES no longer matches TransferStatus");
        }
        this.transfers = transfers;
        this.search = search;
        this.warehouses = warehouses;
    }

    @Override
    public PageResponse<TransferOrderSummary> transfers(List<String> statuses, String number, boolean expectedFirst,
                                                        int limit) {
        if (limit < 1 || limit > MAX_TRANSFERS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "limit must be between 1 and " + MAX_TRANSFERS + ", not " + limit);
        }
        List<TransferStatus> wanted = statuses == null ? List.of()
                : statuses.stream().map(TransferOrderQueriesImpl::status).toList();
        String text = number == null || number.isBlank() ? null : number.strip();
        Map<UUID, String> codes = new HashMap<>();
        if (expectedFirst) {
            // The screen's own search, so its warehouse rule, under another order than the screen offers.
            return Pages.toResponse(search.search(new TransferOrderSearch.Criteria(wanted, null, null, text),
                            Pages.of(0, limit, EXPECTED_FIRST))
                    .map(row -> summary(codes, row.number(), row.fromWarehouseId(), row.toWarehouseId(), row.status(),
                            row.expectedDate(), row.createdAt(), row.dispatchedAt(), row.deliveryMode(),
                            row.packedAt())));
        }
        return transfers.list(wanted, null, null, text, 0, limit)
                .map(row -> summary(codes, row.number(), row.fromWarehouseId(), row.toWarehouseId(), row.status(),
                        row.expectedDate(), row.createdAt(), row.dispatchedAt(), row.deliveryMode(), row.packedAt()));
    }

    private TransferOrderSummary summary(Map<UUID, String> codes, String number, UUID from, UUID to,
                                         TransferStatus status, LocalDate expectedDate, Instant createdAt,
                                         Instant dispatchedAt, TransferDeliveryMode deliveryMode, Instant packedAt) {
        return new TransferOrderSummary(number, code(codes, from), code(codes, to), status.name(), expectedDate,
                createdAt, dispatchedAt, deliveryMode == null ? null : deliveryMode.name(), packedAt);
    }

    private String code(Map<UUID, String> codes, UUID warehouseId) {
        if (warehouseId == null) {
            return null;
        }
        return codes.computeIfAbsent(warehouseId, id -> warehouses.prefixOf(id).orElse(null));
    }

    private static TransferStatus status(String name) {
        if (name == null || !STATUSES.contains(name)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A transfer order status is one of " + STATUSES + ", not " + name);
        }
        return TransferStatus.valueOf(name);
    }
}
