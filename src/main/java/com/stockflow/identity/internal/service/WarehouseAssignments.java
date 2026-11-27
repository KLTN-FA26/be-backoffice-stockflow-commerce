package com.stockflow.identity.internal.service;

import com.stockflow.common.security.AssignedWarehouses;
import com.stockflow.common.security.UserWarehouseLookup;
import com.stockflow.identity.internal.entity.UserWarehouseJpaEntity;
import com.stockflow.identity.internal.repository.UserWarehouseJpaRepository;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The warehouses of a warehouse-bound user, read per request (SCRUM-457, ADR-0008).
 *
 * <p>Only callers whose roles' scope is WAREHOUSE pay for this — clerks and
 * warehouse managers — and they are assigned one or two warehouses: one indexed read of
 * {@code user_warehouse}, and the prefix of each warehouse from the warehouse module. Not cached, so
 * an administrator's change applies on the user's very next request.</p>
 *
 * <p>Runs before any transaction of the request exists (inside the JWT converter), hence its own
 * read-only one.</p>
 */
@Component
public class WarehouseAssignments implements UserWarehouseLookup {

    private final UserWarehouseJpaRepository assignments;
    private final WarehouseService warehouses;
    private final TransactionTemplate readOnly;

    WarehouseAssignments(UserWarehouseJpaRepository assignments, WarehouseService warehouses,
                         PlatformTransactionManager transactionManager) {
        this.assignments = assignments;
        this.warehouses = warehouses;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    @Override
    public AssignedWarehouses warehousesOf(UUID userId) {
        AssignedWarehouses answer = readOnly.execute(status -> {
            Set<UUID> ids = new LinkedHashSet<>();
            Set<String> prefixes = new LinkedHashSet<>();
            for (UserWarehouseJpaEntity row : assignments.findByUserId(userId)) {
                ids.add(row.getWarehouseId());
                warehouses.findWarehouse(row.getWarehouseId())
                        .map(WarehouseView::prefix)
                        .filter(prefix -> prefix != null && !prefix.isBlank())
                        .ifPresent(prefixes::add);
            }
            return new AssignedWarehouses(ids, prefixes);
        });
        return answer == null ? AssignedWarehouses.none() : answer;
    }

    /**
     * The warehouses behind ids already resolved for a caller ({@code CurrentUser.warehouseIds()}), sorted by prefix,
     * for the caller to be told which ones they may work in. Read from the ids rather than from
     * {@code user_warehouse} again, so the list cannot disagree with what the guarded endpoints allow. The prefixes
     * {@link #warehousesOf} resolved are an unordered set apart from the ids, so each is looked up again to pair it;
     * an id with no warehouse is left out.
     */
    public List<WarehouseView> named(Collection<UUID> warehouseIds) {
        return warehouseIds.stream()
                .distinct()
                .map(warehouses::findWarehouse)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(WarehouseView::prefix, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}
