package com.stockflow.inventory.internal.repository;

import com.stockflow.inventory.internal.domain.AdjustmentReason;
import com.stockflow.inventory.internal.domain.AdjustmentStatus;
import com.stockflow.inventory.internal.domain.CycleCountStatus;
import com.stockflow.inventory.internal.entity.CycleCountJpaEntity;
import com.stockflow.inventory.internal.entity.CycleCountLineJpaEntity;
import com.stockflow.inventory.internal.entity.StockAdjustmentJpaEntity;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * The blind-count rule for queries built with the Criteria API (the ledger and the adjustments, SCRUM-146): rows whose
 * slot a cycle count holds are left out of what a counter may read. The same rule as {@link CountBusy#BUSY} - the
 * count is open, or submitted with a {@code COUNT_VARIANCE} of the line still pending approval - and the same slot
 * matching as {@link CountBusy#movementInBusySlot}; the SQL fragment cannot be used here, so
 * {@code BlindCountIntegrationTest} runs both over every state a slot goes through.
 */
final class UnderCountSpecs {

    private UnderCountSpecs() {
    }

    /**
     * Rows that do not lie in a held slot.
     *
     * @param locations the row's location attributes, any of which may name the slot's bin (a ledger line has two)
     * @param packages  the row's carton attributes, any of which may name the slot's carton
     */
    static <T> Specification<T> notUnderCount(String sku, String lotNumber, List<String> locations,
                                              List<String> packages) {
        return (root, query, cb) -> {
            Subquery<Integer> held = query.subquery(Integer.class);
            Root<CycleCountLineJpaEntity> line = held.from(CycleCountLineJpaEntity.class);
            Join<CycleCountLineJpaEntity, CycleCountJpaEntity> count = line.join("cycleCount");

            Subquery<Integer> pending = held.subquery(Integer.class);
            Root<StockAdjustmentJpaEntity> adjustment = pending.from(StockAdjustmentJpaEntity.class);
            pending.select(cb.literal(1)).where(
                    cb.equal(adjustment.get("cycleCountLineId"), line.get("id")),
                    cb.equal(adjustment.get("reasonCode"), AdjustmentReason.COUNT_VARIANCE),
                    cb.equal(adjustment.get("status"), AdjustmentStatus.PENDING_APPROVAL));
            Predicate busy = cb.or(
                    count.get("status").in(CycleCountStatus.PLANNED, CycleCountStatus.IN_PROGRESS),
                    cb.and(cb.equal(count.get("status"), CycleCountStatus.COMPLETED), cb.exists(pending)));

            List<Predicate> cartonSlot = new ArrayList<>();
            for (String attribute : packages) {
                cartonSlot.add(cb.equal(line.get("stockPackageId"), root.get(attribute)));
            }
            List<Predicate> looseBin = new ArrayList<>();
            for (String attribute : locations) {
                looseBin.add(cb.equal(line.get("locationCode"), root.get(attribute)));
            }
            Predicate carton = cb.and(cb.isNotNull(line.get("stockPackageId")), cb.or(cartonSlot.toArray(Predicate[]::new)));
            Predicate loose = cb.and(cb.isNull(line.get("stockPackageId")), cb.or(looseBin.toArray(Predicate[]::new)),
                    cb.equal(cb.coalesce(cb.trim(line.<String>get("lotNumber")), ""),
                            cb.coalesce(cb.trim(root.<String>get(lotNumber)), "")));
            held.select(cb.literal(1)).where(cb.equal(line.get("sku"), root.get(sku)), cb.or(carton, loose), busy);
            return cb.not(cb.exists(held));
        };
    }

    static <T> Specification<T> unless(boolean hide, Specification<T> spec) {
        return hide ? spec : null;
    }

    /** Adjustments: one bin, one carton. */
    static Specification<StockAdjustmentJpaEntity> adjustmentsOutsideCountedSlots() {
        return notUnderCount("sku", "lotNumber", List.of("locationCode"), List.of("stockPackageId"));
    }

    /** Ledger lines: a bin and a carton on each side. */
    static <T> Specification<T> movementsOutsideCountedSlots() {
        return notUnderCount("sku", "lotNumber", List.of("fromLocationCode", "toLocationCode"),
                List.of("fromStockPackageId", "toStockPackageId"));
    }
}
