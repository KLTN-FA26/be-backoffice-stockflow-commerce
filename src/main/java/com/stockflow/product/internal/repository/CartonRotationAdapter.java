package com.stockflow.product.internal.repository;

import com.stockflow.inventory.spi.CartonRotation;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inventory's question of {@code inventory :: spi}: whether the cartons of each variant may be turned (owner decision
 * Q9). One query for a whole read; a projection, so no entity is loaded.
 */
@Repository
class CartonRotationAdapter implements CartonRotation {

    private final VariantJpaRepository variants;

    CartonRotationAdapter(VariantJpaRepository variants) {
        this.variants = variants;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Map<UUID, Boolean> rotatableOf(Collection<UUID> variantIds) {
        List<UUID> wanted = variantIds.stream().distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Boolean> rotatable = new HashMap<>();
        for (Object[] row : variants.rotatableById(wanted)) {
            rotatable.put((UUID) row[0], (Boolean) row[1]);
        }
        return rotatable;
    }
}
