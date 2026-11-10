package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.ItemSpecifications;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionDraft;
import com.stockflow.inventory.api.UomConversionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * A customer's own SKU inherits its variant's carton spec, logistics and stock policy (kltn-docs 01 §4.8, B20) through
 * the same writes a person makes on the item screens - logistics, policy, one packing unit at a time - so every rule
 * those writes check (the SKU lock, a positive factor, one default shipping unit) holds for the copy too.
 */
@Service
@Transactional
class ItemSpecificationsImpl implements ItemSpecifications {

    private final InventoryItems items;
    private final UomConversionService units;

    ItemSpecificationsImpl(InventoryItems items, UomConversionService units) {
        this.items = items;
        this.units = units;
    }

    @Override
    public void copy(Sku source, Sku target) {
        InventoryItems.Summary sourceSummary = summaryOf(source);
        InventoryItems.Detail from = items.get(sourceSummary.id());
        UUID targetId = summaryOf(target).id();
        ItemLogistics l = from.logistics();
        ItemLogistics withoutBarcode = new ItemLogistics(l.unitOfMeasure(), null, l.weightKg(), l.lengthCm(),
                l.widthCm(), l.heightCm(), l.packageWeightKg(), l.packageLengthCm(), l.packageWidthCm(),
                l.packageHeightCm(), l.packageCount(), l.packSize(), l.storageClass(), l.requiresAdultSignature(),
                l.shippingRestrictionNote(), l.rotatable());
        // No stock policy to copy: every SKU has the same one (kltn-docs 01 BR-17, team plan 5-5).
        items.describe(targetId, items.get(targetId).version(), withoutBarcode);
        List<UomConversion> packing = units.conversionsOf(List.of(source)).getOrDefault(source.code(), List.of());
        for (UomConversion unit : packing) {
            if (unit.base() || !unit.active()) {
                continue;
            }
            units.add(target, new UomConversionDraft(unit.unitOfMeasure(), unit.factor(), unit.weightKg(),
                    unit.lengthCm(), unit.widthCm(), unit.heightCm(), unit.defaultPurchase(), true,
                    unit.defaultShipping()));
        }
        // A SKU shipped in its base unit carries the shipping mark there; only the marks change on a base unit.
        packing.stream().filter(u -> u.base() && u.defaultShipping()).findFirst().ifPresent(base -> {
            UomConversion targetBase = units.baseUnit(target);
            units.change(target, targetBase.id(), targetBase.version(), new UomConversionDraft(
                    targetBase.unitOfMeasure(), 1, targetBase.weightKg(), targetBase.lengthCm(), targetBase.widthCm(),
                    targetBase.heightCm(), targetBase.defaultPurchase(), true, true));
        });
    }

    private InventoryItems.Summary summaryOf(Sku sku) {
        return items.search(new InventoryItems.ItemQuery(sku.code(), null, null, 0, 1)).items().stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVENTORY_ITEM_NOT_FOUND,
                        "No inventory item for " + sku.code()));
    }
}
