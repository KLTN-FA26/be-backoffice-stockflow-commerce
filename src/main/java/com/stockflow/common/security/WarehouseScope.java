package com.stockflow.common.security;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * BR-SEC-002: a warehouse-bound staff member acts only on the warehouses they are assigned to.
 *
 * <p>Reads the scope {@link RequiresPermissionAspect} put in force for the current request. It limits
 * a caller whose own scope is {@link DataScope#WAREHOUSE} — what their roles resolve to — on any
 * endpoint that does not narrow further to OWN. The endpoint's declared scope does not decide it: an
 * endpoint declaring WAREHOUSE says it is warehouse-sensitive, not that a planner with ALL should be
 * cut down to the warehouses they were never assigned. With no scope at all — an event listener, a
 * scheduled job, security switched off — the code is acting as the system and is not limited. A user limited to warehouses but assigned none sees and touches nothing, which is the
 * narrow failure: they ask an administrator, nobody gets data they should not.</p>
 *
 * <p>Inventory and receiving call this for every document they act on. It is a static helper on
 * purpose, like {@link DataScopeContext}: the scope is ambient, so a service cannot forget to pass
 * it, only forget to call this — which a review of the service can see.</p>
 */
public final class WarehouseScope {

    private WarehouseScope() {
    }

    /** The warehouses the caller is limited to, or empty when they are not limited. */
    public static Optional<AssignedWarehouses> restriction() {
        return DataScopeContext.current()
                .filter(scope -> scope.user().scope() == DataScope.WAREHOUSE)
                .filter(scope -> !DataScope.WAREHOUSE.isBroaderThan(scope.required()))
                .map(scope -> new AssignedWarehouses(scope.user().warehouseIds(), scope.user().warehouseCodes()));
    }

    /** Refuses a warehouse, named by its prefix, that the caller is not assigned to. */
    public static void requirePrefix(String warehousePrefix) {
        restriction().ifPresent(allowed -> {
            if (warehousePrefix == null || !allowed.prefixes().contains(warehousePrefix)) {
                throw outOfScope(warehousePrefix);
            }
        });
    }

    /** Refuses a location in a warehouse the caller is not assigned to. */
    public static void requireLocation(String locationCode) {
        if (restriction().isPresent()) {
            requirePrefix(prefixOf(locationCode));
        }
    }

    /** Refuses a warehouse, named by its id, that the caller is not assigned to. */
    public static void requireWarehouse(UUID warehouseId) {
        restriction().ifPresent(allowed -> {
            if (warehouseId == null || !allowed.ids().contains(warehouseId)) {
                throw outOfScope(String.valueOf(warehouseId));
            }
        });
    }

    /** Refuses a document between two warehouses unless the caller is assigned to at least one. */
    public static void requireEither(UUID first, UUID second) {
        restriction().ifPresent(allowed -> {
            if (!allowed.ids().contains(first) && !allowed.ids().contains(second)) {
                throw outOfScope(first + "/" + second);
            }
        });
    }

    /**
     * Rows whose location code (any of {@code attributes}) lies in an assigned warehouse; null —
     * no filter — for a caller who is not limited. A limited caller with no warehouse matches nothing.
     */
    public static <E> Specification<E> locationsIn(String... attributes) {
        return restriction().<Specification<E>>map(allowed -> (root, query, cb) -> {
            List<Predicate> any = new ArrayList<>();
            for (String attribute : attributes) {
                for (String prefix : allowed.prefixes()) {
                    any.add(cb.like(root.get(attribute), prefix + "-%"));
                }
            }
            return any.isEmpty() ? cb.disjunction() : cb.or(any.toArray(Predicate[]::new));
        }).orElse(null);
    }

    /** Rows naming an assigned warehouse in any of {@code attributes}; null when not limited. */
    public static <E> Specification<E> warehousesIn(String... attributes) {
        return restriction().<Specification<E>>map(allowed -> (root, query, cb) -> {
            if (allowed.ids().isEmpty()) {
                return cb.disjunction();
            }
            List<Predicate> any = new ArrayList<>();
            for (String attribute : attributes) {
                any.add(root.get(attribute).in(allowed.ids()));
            }
            return cb.or(any.toArray(Predicate[]::new));
        }).orElse(null);
    }

    /**
     * The warehouse prefix a location code starts with: everything before the first {@code '-'}
     * (docs 06 BR-13: {@code HN-A01-2-03}, {@code HN-RCV01}), upper-cased like every warehouse
     * prefix ({@code ck_warehouse_prefix}) since callers may send a code in any case. Location codes
     * are immutable, so the prefix of a code never changes.
     */
    public static String prefixOf(String locationCode) {
        if (locationCode == null) {
            return null;
        }
        String code = locationCode.trim().toUpperCase(java.util.Locale.ROOT);
        int dash = code.indexOf('-');
        return dash > 0 ? code.substring(0, dash) : code;
    }

    private static BusinessException outOfScope(String warehouse) {
        return new BusinessException(ErrorCode.OUT_OF_DATA_SCOPE,
                "Warehouse " + warehouse + " is not one the caller is assigned to");
    }
}
