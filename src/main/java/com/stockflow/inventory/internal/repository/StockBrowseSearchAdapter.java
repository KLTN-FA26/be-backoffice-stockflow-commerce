package com.stockflow.inventory.internal.repository;

import com.stockflow.common.persistence.Specs;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.internal.entity.ReservationJpaEntity;
import com.stockflow.inventory.internal.domain.ReservationStatus;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.entity.StockPackageJpaEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Holds are read with their carton in one query (Criteria API, a constructor result: nothing lands
 * in the persistence context, and no filter that was not asked for is bound). The carton is a left join: a SKU
 * reservation (SCRUM-152) has none, and is listed with a null location - outside every warehouse, so a warehouse
 * filter or a warehouse-bound caller does not see it. Stock rows are a Specification over {@code stock_package}: one
 * carton is one row, in units of its count (ADR-0011), and whether it is held is read from its live allocation, one
 * query for the page.
 */
@Repository
class StockBrowseSearchAdapter implements StockBrowseSearch {

    private final StockPackageJpaRepository stockPackages;

    @PersistenceContext
    private EntityManager entityManager;

    StockBrowseSearchAdapter(StockPackageJpaRepository stockPackages) {
        this.stockPackages = stockPackages;
    }

    @Override
    public Page<ReservationRow> reservations(ReservationCriteria criteria, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<ReservationRow> query = cb.createQuery(ReservationRow.class);
        Root<ReservationJpaEntity> root = query.from(ReservationJpaEntity.class);
        Join<ReservationJpaEntity, StockPackageJpaEntity> stock = root.join("stockPackage", jakarta.persistence.criteria.JoinType.LEFT);
        query.select(row(cb, root, stock))
                .where(reservationFilters(cb, root, stock, criteria).toArray(Predicate[]::new))
                .orderBy(cb.desc(root.get("reservedAt")), cb.asc(root.get("id")));
        List<ReservationRow> content = entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        CriteriaQuery<Long> count = cb.createQuery(Long.class);
        Root<ReservationJpaEntity> countRoot = count.from(ReservationJpaEntity.class);
        Join<ReservationJpaEntity, StockPackageJpaEntity> countStock = countRoot.join("stockPackage", jakarta.persistence.criteria.JoinType.LEFT);
        count.select(cb.count(countRoot))
                .where(reservationFilters(cb, countRoot, countStock, criteria).toArray(Predicate[]::new));
        return new PageImpl<>(content, pageable, entityManager.createQuery(count).getSingleResult());
    }

    @Override
    public Optional<ReservationRow> reservation(UUID reservationId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<ReservationRow> query = cb.createQuery(ReservationRow.class);
        Root<ReservationJpaEntity> root = query.from(ReservationJpaEntity.class);
        Join<ReservationJpaEntity, StockPackageJpaEntity> stock = root.join("stockPackage", jakarta.persistence.criteria.JoinType.LEFT);
        query.select(row(cb, root, stock)).where(cb.equal(root.get("id"), reservationId));
        return entityManager.createQuery(query).getResultList().stream().findFirst();
    }

    private static jakarta.persistence.criteria.CompoundSelection<ReservationRow> row(
            CriteriaBuilder cb, Root<ReservationJpaEntity> root, Join<ReservationJpaEntity, StockPackageJpaEntity> stock) {
        return cb.construct(ReservationRow.class, root.get("id"), root.get("orderId"), stock.get("id"),
                root.get("sku"), stock.get("locationCode"), stock.get("lotNumber"),
                // The row keeps cartons and their count; the screen reads units (ADR-0011).
                cb.prod(root.<Integer>get("cartons"), root.<Integer>get("unitsPerCarton")),
                root.get("status"), root.get("reservedAt"), root.get("releaseReason"),
                root.get("closedAt"), root.get("orderNumber"));
    }

    private static List<Predicate> reservationFilters(CriteriaBuilder cb, Root<ReservationJpaEntity> root,
                                                      Join<ReservationJpaEntity, StockPackageJpaEntity> stock,
                                                      ReservationCriteria criteria) {
        List<Predicate> filters = new ArrayList<>();
        if (criteria.orderId() != null) {
            filters.add(cb.equal(root.get("orderId"), criteria.orderId()));
        }
        if (criteria.sku() != null && !criteria.sku().isBlank()) {
            filters.add(cb.equal(root.get("sku"), criteria.sku().trim().toUpperCase(Locale.ROOT)));
        }
        if (criteria.statuses() != null) {
            filters.add(criteria.statuses().isEmpty() ? cb.disjunction() : root.get("status").in(criteria.statuses()));
        }
        if (criteria.warehousePrefix() != null) {
            filters.add(cb.like(stock.get("locationCode"), WarehouseScope.locationPattern(criteria.warehousePrefix()),
                    '\\'));
        }
        WarehouseScope.restriction().ifPresent(allowed -> {
            List<Predicate> any = allowed.prefixes().stream()
                    .map(prefix -> cb.like(stock.get("locationCode"), WarehouseScope.locationPattern(prefix), '\\'))
                    .toList();
            filters.add(any.isEmpty() ? cb.disjunction() : cb.or(any.toArray(Predicate[]::new)));
        });
        return filters;
    }

    @Override
    public Page<StockRow> stock(StockCriteria criteria, Pageable pageable) {
        Specification<StockPackageJpaEntity> spec = Specification
                .<StockPackageJpaEntity>where((root, query, cb) -> cb.like(root.get("locationCode"),
                        WarehouseScope.locationPattern(criteria.warehousePrefix()), '\\'))
                .and(Specs.startsWith("locationCode", criteria.locationPrefix()))
                .and(Specs.eq("sku", blankToNull(criteria.sku())))
                .and(Specs.eq("lotNumber", blankToNull(criteria.lotNumber())))
                .and(Specs.in("stockStatus", criteria.statuses()))
                // A carton that has never been in stock (RECEIVING, VOID) is not stock at all; one that left it is
                // the "empty row" of the screen.
                .and((root, query, cb) -> criteria.includeEmpty()
                        ? root.get("status").in(StockPackageStatus.IN_STOCK, StockPackageStatus.CONSUMED,
                                StockPackageStatus.LOST)
                        : cb.equal(root.get("status"), StockPackageStatus.IN_STOCK))
                .and(WarehouseScope.locationsIn("locationCode"));
        Page<StockPackageJpaEntity> page = stockPackages.findAll(spec, onCartons(pageable));
        Set<UUID> held = heldAmong(page.getContent().stream().map(StockPackageJpaEntity::getId).toList());
        return page.map(p -> {
            int onHand = p.getStatus() == StockPackageStatus.IN_STOCK ? p.getNominalQty() : 0;
            return new StockRow(p.getId(), p.getSku(), p.getLocationCode(), p.getLotNumber(), onHand,
                    held.contains(p.getId()) ? onHand : 0, p.getStockStatus(), p.getReceivedAt());
        });
    }

    /** The screen sorts by {@code onHand}; of cartons that is their count. */
    private static Pageable onCartons(Pageable pageable) {
        Sort sort = Sort.by(pageable.getSort().stream()
                .map(order -> "onHand".equals(order.getProperty()) ? order.withProperty("nominalQty") : order)
                .toList());
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    /** Which of these cartons an order holds: the ones a live allocation names. One query for the page. */
    private Set<UUID> heldAmong(List<UUID> cartonIds) {
        if (cartonIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(entityManager.createQuery("""
                        select r.stockPackage.id from ReservationJpaEntity r
                        where r.stockPackage.id in :ids and r.status = :held""", UUID.class)
                .setParameter("ids", cartonIds).setParameter("held", ReservationStatus.HELD).getResultList());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
