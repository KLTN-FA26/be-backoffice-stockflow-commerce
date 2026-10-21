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
     * no query.
     */
    Map<UUID, Integer> factors(Collection<PoLineJpaEntity> lines) {
        List<PoLineJpaEntity> inUnit = lines.stream().filter(line -> line.getUomConversionId() != null).toList();
        if (inUnit.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> skus = inventory.skusOf(inUnit.stream().map(PoLineJpaEntity::getInventoryItemId).toList());
        Map<UUID, Integer> factors = new HashMap<>();
        for (PoLineJpaEntity line : inUnit) {
            String sku = skus.get(line.getInventoryItemId());
            UomConversion unit = sku == null ? null
                    : units.conversions(new Sku(sku), List.of(line.getUomConversionId())).get(line.getUomConversionId());
            if (unit == null) {
                // fk_purchase_order_lines_unit and check_po_line_unit: the unit exists and is the item's.
                throw new IllegalStateException("Line " + line.getId() + " is in unit " + line.getUomConversionId()
                        + ", which is not a unit of its item");
            }
            factors.put(line.getId(), unit.factor());
        }
        return factors;
    }
}
