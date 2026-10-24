package com.stockflow.procurement.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.procurement.api.OnOrderQueries;
import com.stockflow.procurement.api.SkuOnOrder;
import com.stockflow.procurement.internal.repository.OpenPurchaseLines;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link OnOrderQueries} over {@link OpenPurchaseLines}. Lines name an inventory item, not a SKU, and procurement does
 * not join inventory's schema, so the SKUs are turned into item ids through inventory's api first.
 *
 * <p>No warehouse narrowing: the figure sits beside company-wide available-to-promise in the same suggestion, and a
 * total of units on order discloses no purchase order. Callers that show documents use the purchase order list.</p>
 */
@Service
@Transactional(readOnly = true)
class OnOrderQueriesImpl implements OnOrderQueries {

    private static final OpenPurchaseLines.OpenPurchasing NOTHING = new OpenPurchaseLines.OpenPurchasing(0, 0, null);

    private final InventoryService inventory;
    private final OpenPurchaseLines lines;

    OnOrderQueriesImpl(InventoryService inventory, OpenPurchaseLines lines) {
        this.inventory = inventory;
        this.lines = lines;
    }

    @Override
    public List<SkuOnOrder> onOrder(Collection<String> skus) {
        Set<String> distinct = new LinkedHashSet<>(skus);
        if (distinct.size() > MAX_SKUS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "at most " + MAX_SKUS + " SKUs");
        }
        Map<String, UUID> items = inventory.itemIdsOf(distinct);
        Map<UUID, OpenPurchaseLines.OpenPurchasing> open = lines.byItem(items.values());
        return distinct.stream().map(sku -> {
            UUID item = items.get(sku);
            OpenPurchaseLines.OpenPurchasing purchasing = item == null ? NOTHING : open.getOrDefault(item, NOTHING);
            return new SkuOnOrder(sku, purchasing.onOrder(), purchasing.awaitingApproval(),
                    purchasing.nextExpectedDate());
        }).toList();
    }
}
