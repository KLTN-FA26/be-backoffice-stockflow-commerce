package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.PackagingRequest;
import com.stockflow.inventory.api.PackagingSpec;
import com.stockflow.inventory.api.PackagingSpecResolver;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.internal.domain.PackagingRules;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository.ItemRow;
import com.stockflow.inventory.internal.repository.UomConversionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * See {@link PackagingSpecResolver}. This class only reads the item and its units; {@link PackagingRules} decides.
 * Read-only: it joins the caller's transaction and takes no lock, because an estimate is advisory and a stale
 * read of a unit being edited at the same moment is as good as the moment before.
 */
@Service
@Transactional(readOnly = true)
class PackagingSpecResolverImpl implements PackagingSpecResolver {

    private final InventoryPolicyRepository items;
    private final UomConversionRepository conversions;

    PackagingSpecResolverImpl(InventoryPolicyRepository items, UomConversionRepository conversions) {
        this.items = items;
        this.conversions = conversions;
    }

    @Override
    public PackagingSpec resolve(Sku sku, int quantity) {
        return resolveAll(List.of(new PackagingRequest(sku, quantity))).getFirst();
    }

    @Override
    public PackagingSpec resolve(UUID inventoryItemId, int quantity) {
        String sku = items.findSku(inventoryItemId).orElseThrow(() -> new BusinessException(
                ErrorCode.INVENTORY_ITEM_NOT_FOUND, "No inventory item " + inventoryItemId));
        return resolve(new Sku(sku), quantity);
    }

    @Override
    public java.util.Optional<PackagingSpec.CartonSpec> unitCarton(Sku sku, UUID uomConversionId) {
        UomConversion unit = conversions.find(sku.code(), uomConversionId).orElse(null);
        if (unit == null || !positive(unit.lengthCm()) || !positive(unit.widthCm()) || !positive(unit.heightCm())
                || !positive(unit.weightKg())) {
            return java.util.Optional.empty();
        }
        boolean rotatable = items.findBySkus(java.util.Set.of(sku.code())).stream().findFirst()
                .map(row -> row.logistics().rotatable()).orElse(true);
        return java.util.Optional.of(new PackagingSpec.CartonSpec(PackagingRules.mm(unit.lengthCm()),
                PackagingRules.mm(unit.widthCm()), PackagingRules.mm(unit.heightCm()), unit.weightKg(), rotatable));
    }

    private static boolean positive(java.math.BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    @Override
    public List<PackagingSpec> resolveAll(List<PackagingRequest> lines) {
        for (PackagingRequest line : lines) {
            if (line.quantity() < 1) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "quantity of " + line.sku() + " must be at least 1 base unit");
            }
        }
        LinkedHashSet<String> skus = new LinkedHashSet<>();
        lines.forEach(l -> skus.add(l.sku().code()));
        Map<String, ItemRow> rows = new HashMap<>();
        items.findBySkus(skus).forEach(r -> rows.put(r.sku(), r));
        List<String> unknown = skus.stream().filter(s -> !rows.containsKey(s)).toList();
        if (!unknown.isEmpty()) {
            throw new BusinessException(ErrorCode.INVENTORY_ITEM_NOT_FOUND,
                    "No inventory item " + String.join(", ", unknown));
        }
        Map<String, List<UomConversion>> units = conversions.bySkus(skus);
        return lines.stream().map(line -> {
            ItemRow row = rows.get(line.sku().code());
            return PackagingRules.resolve(row.id(), row.sku(), row.logistics(),
                    units.getOrDefault(row.sku(), List.of()), line.quantity());
        }).toList();
    }
}
