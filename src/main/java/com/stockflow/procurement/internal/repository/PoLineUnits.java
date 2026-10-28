package com.stockflow.procurement.internal.repository;

import com.stockflow.common.domain.Sku;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.procurement.internal.entity.PoLineJpaEntity;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The factor of the packing unit a purchase order line was entered in (V20261020000400), asked of
 * inventory's api: the unit is an inventory row, and procurement does not join inventory's schema. A
 * unit a line counts in keeps its factor (V20261020000600), so the factor read here is the one the
 * line was converted with.
 */
@Component
class PoLineUnits {

    private final InventoryService inventory;
    private final UomConversionService units;

    PoLineUnits(InventoryService inventory, UomConversionService units) {
        this.inventory = inventory;
        this.units = units;
    }

    /**
     * The factor of each of {@code lines} entered in a packing unit, by line id. A line in the base
     * unit is absent, and lines all in the base unit - every line the application writes today - cost
     * no query; otherwise one query per SKU.
     */
    Map<UUID, Integer> factors(Collection<PoLineJpaEntity> lines) {
        Map<UUID, Integer> factors = new HashMap<>();
        units(lines).forEach((line, unit) -> factors.put(line, unit.factor()));
        return factors;
    }

    /**
     * The packing unit of each of {@code lines} entered in one, by line id; a line in the base unit is absent and,
     * when every line is, nothing is asked.
     */
    Map<UUID, UomConversion> units(Collection<PoLineJpaEntity> lines) {
        List<PoLineJpaEntity> inUnit = lines.stream().filter(line -> line.getUomConversionId() != null).toList();
        if (inUnit.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> skus = inventory.skusOf(inUnit.stream().map(PoLineJpaEntity::getInventoryItemId).toList());
        Map<UUID, List<PoLineJpaEntity>> byItem = inUnit.stream()
                .collect(Collectors.groupingBy(PoLineJpaEntity::getInventoryItemId));
        Map<UUID, UomConversion> units = new HashMap<>();
        byItem.forEach((itemId, itemLines) -> {
            String sku = skus.get(itemId);
            if (sku == null) {
                // fk_purchase_order_lines_item: the item exists, so the directory not knowing it is a fault.
                throw new IllegalStateException("Inventory has no SKU for item " + itemId + " of line "
                        + itemLines.get(0).getId());
            }
            Map<UUID, UomConversion> found = this.units.conversions(new Sku(sku),
                    itemLines.stream().map(PoLineJpaEntity::getUomConversionId).distinct().toList());
            for (PoLineJpaEntity line : itemLines) {
                UomConversion unit = found.get(line.getUomConversionId());
                if (unit == null) {
                    // fk_purchase_order_lines_unit and check_po_line_unit: the unit exists and is the item's.
                    throw new IllegalStateException("Line " + line.getId() + " is in unit " + line.getUomConversionId()
                            + ", which is not a unit of " + sku);
                }
                units.put(line.getId(), unit);
            }
        });
        return units;
    }
}
