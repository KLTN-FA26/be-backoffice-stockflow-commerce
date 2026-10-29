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
 * The packing unit a purchase order line was ordered in (V20261020000400, SCRUM-473), asked of
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

    /** The factor of each of {@code lines} entered in a packing unit, by line id, as {@link #units(Collection)}. */
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
        List<PoLineJpaEntity> inUnit = inUnit(lines);
        return inUnit.isEmpty() ? Map.of()
                : units(inUnit, inventory.skusOf(inUnit.stream().map(PoLineJpaEntity::getInventoryItemId).toList()));
    }

    /**
     * As {@link #units(Collection)}, for a caller that already holds the SKU of every line's item: every line of an
     * order is read through here, so the SKUs are not asked twice, and the units of all of them are one query.
     */
    Map<UUID, UomConversion> units(Collection<PoLineJpaEntity> lines, Map<UUID, String> skus) {
        List<PoLineJpaEntity> inUnit = inUnit(lines);
        if (inUnit.isEmpty()) {
            return Map.of();
        }
        for (PoLineJpaEntity line : inUnit) {
            if (skus.get(line.getInventoryItemId()) == null) {
                // fk_purchase_order_lines_item: the item exists, so the directory not knowing it is a fault.
                throw new IllegalStateException("Inventory has no SKU for item " + line.getInventoryItemId()
                        + " of line " + line.getId());
            }
        }
        Map<String, List<UomConversion>> bySku = this.units.conversionsOf(inUnit.stream()
                .map(line -> new Sku(skus.get(line.getInventoryItemId()))).distinct().toList());
        Map<UUID, UomConversion> units = new HashMap<>();
        for (PoLineJpaEntity line : inUnit) {
            String sku = skus.get(line.getInventoryItemId());
            UomConversion unit = bySku.getOrDefault(sku, List.of()).stream()
                    .filter(candidate -> candidate.id().equals(line.getUomConversionId())).findFirst()
                    // fk_purchase_order_lines_unit and check_po_line_unit: the unit exists and is the item's.
                    .orElseThrow(() -> new IllegalStateException("Line " + line.getId() + " is in unit "
                            + line.getUomConversionId() + ", which is not a unit of " + sku));
            units.put(line.getId(), unit);
        }
        return units;
    }

    private static List<PoLineJpaEntity> inUnit(Collection<PoLineJpaEntity> lines) {
        return lines.stream().filter(line -> line.getUomConversionId() != null).toList();
    }
}
