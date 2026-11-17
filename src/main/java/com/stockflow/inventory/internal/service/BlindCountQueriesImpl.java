package com.stockflow.inventory.internal.service;

import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.inventory.api.BlindCountQueries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * {@link BlindCountQueries} by the stock-items screen's own two steps: the caller classified by {@link BookVisibility#of}
 * (as {@code BlindCount} does for the screen), then {@link StockBrowsing#underCount} for the rows. A caller who may see
 * the books costs no query.
 */
@Service
@Transactional(readOnly = true)
class BlindCountQueriesImpl implements BlindCountQueries {

    private final StockBrowsing browsing;
    private final CurrentUserProvider currentUsers;

    BlindCountQueriesImpl(StockBrowsing browsing, CurrentUserProvider currentUsers) {
        this.browsing = browsing;
        this.currentUsers = currentUsers;
    }

    @Override
    public Set<UUID> hiddenFromCaller(Collection<UUID> stockItemIds) {
        if (stockItemIds.isEmpty() || !BookVisibility.of(currentUsers.current()).hidesCountedSlots()) {
            return Set.of();
        }
        return Set.copyOf(browsing.underCount(stockItemIds));
    }
}
