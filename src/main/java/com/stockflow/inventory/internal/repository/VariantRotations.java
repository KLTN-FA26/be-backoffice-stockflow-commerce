package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.spi.CartonRotation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Whether the cartons of a variant may be turned, asked of the catalogue ({@link CartonRotation}, owner decision Q9).
 * Inventory has no copy of the flag and no cross-schema JOIN: the queries that need it read the variant id and ask
 * here, once per read. Without the answering module (a module test) every carton is rotatable, the documented default
 * (docs 01, E13).
 */
@Component
public class VariantRotations {

    private final ObjectProvider<CartonRotation> catalogue;

    public VariantRotations(ObjectProvider<CartonRotation> catalogue) {
        this.catalogue = catalogue;
    }

    /** Each of {@code variantIds} mapped to whether its cartons may be turned; nulls are skipped. */
    public Map<UUID, Boolean> of(Collection<UUID> variantIds) {
        CartonRotation answer = catalogue.getIfAvailable();
        if (answer == null || variantIds.isEmpty()) {
            return Map.of();
        }
        return answer.rotatableOf(variantIds.stream().filter(Objects::nonNull).distinct().toList());
    }

    /** Whether the cartons of one variant may be turned; true when the catalogue does not say otherwise. */
    public static boolean rotatable(Map<UUID, Boolean> answers, UUID variantId) {
        return !Boolean.FALSE.equals(answers.get(variantId));
    }
}
