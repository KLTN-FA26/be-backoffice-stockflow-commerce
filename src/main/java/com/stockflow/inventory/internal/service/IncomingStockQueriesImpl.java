package com.stockflow.inventory.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.IncomingStockQueries;
import com.stockflow.inventory.internal.repository.IncomingStockRows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@link IncomingStockQueries} over {@link IncomingStockRows}, every SKU answered once and in the order asked. */
@Service
@Transactional(readOnly = true)
class IncomingStockQueriesImpl implements IncomingStockQueries {

    private static final IncomingStockRows.Incoming NONE = new IncomingStockRows.Incoming(0, 0, 0);

    private final IncomingStockRows rows;

    IncomingStockQueriesImpl(IncomingStockRows rows) {
        this.rows = rows;
    }

    @Override
    public List<IncomingStock> incoming(Collection<String> skus) {
        Set<String> distinct = new LinkedHashSet<>(skus);
        if (distinct.size() > MAX_SKUS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "at most " + MAX_SKUS + " SKUs");
        }
        Map<String, IncomingStockRows.Incoming> found = rows.bySku(distinct);
        return distinct.stream().map(sku -> {
            IncomingStockRows.Incoming incoming = found.getOrDefault(sku, NONE);
            return new IncomingStock(sku, incoming.inbound(), incoming.quarantine(), incoming.inTransit());
        }).toList();
    }
}
