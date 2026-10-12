package com.stockflow.inventory.internal.repository;

import com.stockflow.common.persistence.Specs;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.internal.entity.ReservationJpaEntity;
import com.stockflow.inventory.internal.entity.StockItemJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Holds are read with their stock row in one query (Criteria API, a constructor result: nothing lands
 * in the persistence context, and no filter that was not asked for is bound). Stock rows are a
 * Specification over {@code stock_item}.
 */
@Repository
class StockBrowseSearchAdapter implements StockBrowseSearch {

    private final StockItemJpaRepository stockItems;

    @PersistenceContext
    private EntityManager entityManager;

    StockBrowseSearchAdapter(StockItemJpaRepository stockItems) {
        this.stockItems = stockItems;
    }

    @Override
    public Page<ReservationRow> reservations(ReservationCriteria criteria, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<ReservationRow> query = cb.createQuery(ReservationRow.class);
        Root<ReservationJpaEntity> root = query.from(ReservationJpaEntity.class);
        Join<ReservationJpaEntity, StockItemJpaEntity> stock = root.join("stockItem");
        query.select(row(cb, root, stock))
                .where(reservationFilters(cb, root, stock, criteria).toArray(Predicate[]::new))
                .orderBy(cb.desc(root.get("reservedAt")), cb.asc(root.get("id")));
        List<ReservationRow> content = entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        CriteriaQuery<Long> count = cb.createQuery(Long.class);
        Root<ReservationJpaEntity> countRoot = count.from(ReservationJpaEntity.class);
        Join<ReservationJpaEntity, StockItemJpaEntity> countStock = countRoot.join("stockItem");
        count.select(cb.count(countRoot))
                .where(reservationFilters(cb, countRoot, countStock, criteria).toArray(Predicate[]::new));
        return new PageImpl<>(content, pageable, entityManager.createQuery(count).getSingleResult());
    }

    @Override
    public Optional<ReservationRow> reservation(UUID reservationId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<ReservationRow> query = cb.createQuery(ReservationRow.class);
        Root<ReservationJpaEntity> root = query.from(ReservationJpaEntity.class);
        Join<ReservationJpaEntity, StockItemJpaEntity> stock = root.join("stockItem");
        query.select(row(cb, root, stock)).where(cb.equal(root.get("id"), reservationId));
        return entityManager.createQuery(query).getResultList().stream().findFirst();
    }

    private static jakarta.persistence.criteria.CompoundSelection<ReservationRow> row(
            CriteriaBuilder cb, Root<ReservationJpaEntity> root, Join<ReservationJpaEntity, StockItemJpaEntity> stock) {
        return cb.construct(ReservationRow.class, root.get("id"), root.get("orderId"), stock.get("id"),
                stock.get("sku"), stock.get("locationCode"), stock.get("lotNumber"), root.get("quantity"),
                root.get("status"), root.get("reservedAt"), root.get("expiresAt"), root.get("releaseReason"),
                root.get("closedAt"));
    }

    private static List<Predicate> reservationFilters(CriteriaBuilder cb, Root<ReservationJpaEntity> root,
                                                      Join<ReservationJpaEntity, StockItemJpaEntity> stock,
                                                      ReservationCriteria criteria) {
        List<Predicate> filters = new ArrayList<>();
        if (criteria.orderId() != null) {
            filters.add(cb.equal(root.get("orderId"), criteria.orderId()));
        }
        if (criteria.sku() != null && !criteria.sku().isBlank()) {
            filters.add(cb.equal(stock.get("sku"), criteria.sku().trim()));
        }
        if (criteria.statuses() != null) {
            filters.add(criteria.statuses().isEmpty() ? cb.disjunction() : root.get("status").in(criteria.statuses()));
        }
        if (criteria.warehousePrefix() != null) {
            filters.add(cb.like(stock.get("locationCode"), escape(criteria.warehousePrefix()) + "-%", '\\'));
        }
        WarehouseScope.restriction().ifPresent(allowed -> {
            List<Predicate> any = allowed.prefixes().stream()
                    .map(prefix -> cb.like(stock.get("locationCode"), escape(prefix) + "-%", '\\'))
                    .toList();
            filters.add(any.isEmpty() ? cb.disjunction() : cb.or(any.toArray(Predicate[]::new)));
        });
        return filters;
    }

    @Override
    public Page<StockRow> stock(StockCriteria criteria, Pageable pageable) {
        Specification<StockItemJpaEntity> spec = Specification
                .<StockItemJpaEntity>where(Specs.startsWith("locationCode", criteria.warehousePrefix() + "-"))
                .and(Specs.startsWith("locationCode", criteria.locationPrefix()))
                .and(Specs.eq("sku", blankToNull(criteria.sku())))
                .and(Specs.eq("lotNumber", blankToNull(criteria.lotNumber())))
                .and(Specs.in("status", criteria.statuses()))
                .and(criteria.includeEmpty() ? null : (root, query, cb) -> cb.greaterThan(root.get("onHand"), 0))
                .and(WarehouseScope.locationsIn("locationCode"));
        return stockItems.findAll(spec, pageable).map(s -> new StockRow(s.getId(), s.getSku(), s.getLocationCode(),
                s.getLotNumber(), s.getExpiryDate(), s.getOnHand(), s.getReserved(), s.getStatus(), s.getReceivedAt()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** A prefix is letters and digits, but it is a LIKE pattern all the same: escape it. */
    private static String escape(String value) {
        return value.toUpperCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
