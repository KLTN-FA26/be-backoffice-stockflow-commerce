package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.internal.repository.PickLineJpaRepository;
import com.stockflow.inventory.spi.PickedNotHandedOver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Set;

/**
 * Inventory's question of {@code inventory :: spi} (kltn-docs 03 §4.1 step 4, 08 §6 edge case "Nhãn LPN rách"): which
 * consumed cartons are picked for an order and not handed over yet, so their torn or lost LPN label is printed again
 * and they can be scanned at the check station. One query for them all ({@link PickLineJpaRepository#findPickedNotHandedOver}).
 */
@Component
class PickedCartons implements PickedNotHandedOver {

    private final PickLineJpaRepository pickLines;

    PickedCartons(PickLineJpaRepository pickLines) {
        this.pickLines = pickLines;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public Set<String> amongst(Collection<String> lpns) {
        return lpns.isEmpty() ? Set.of() : Set.copyOf(pickLines.findPickedNotHandedOver(lpns));
    }
}
