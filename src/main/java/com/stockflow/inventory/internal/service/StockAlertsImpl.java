package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.common.security.AssignedWarehouses;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.inventory.internal.domain.StockAlert;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.StockAlertRepository;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class StockAlertsImpl implements StockAlerts {

    private static final SortWhitelist SORT = SortWhitelist.of("openedAt", "kind", "sku")
            .withDefault("openedAt", Sort.Direction.DESC);

    private final StockAlertRepository alerts;
    private final InventoryPolicyRepository policies;
    private final WarehouseService warehouses;
    private final IdentityService identities;
    private final Clock clock;

    StockAlertsImpl(StockAlertRepository alerts, InventoryPolicyRepository policies, WarehouseService warehouses,
                    IdentityService identities, Clock clock) {
        this.alerts = alerts;
        this.policies = policies;
        this.warehouses = warehouses;
        this.identities = identities;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AlertView> list(Set<StockAlert.Kind> kinds, Set<StockAlert.Status> statuses, String sku,
                                        UUID warehouseId, int page, int size, String sort) {
        Pageable pageable = Pages.of(page, size, SORT.parse(sort));
        if (warehouseId != null) {
            WarehouseScope.requireWarehouse(warehouseId);
        }
        Set<UUID> allowed = WarehouseScope.restriction().map(AssignedWarehouses::ids).orElse(null);
        var criteria = new StockAlertRepository.Criteria(kinds, statuses, sku, warehouseId, allowed);
        List<StockAlert> found = alerts.search(criteria, orderBy(pageable.getSort()), pageable.getPageSize(),
                pageable.getOffset());
        long total = alerts.count(criteria);
        return PageResponse.of(views(found), page, size, total);
    }

    @Override
    @Transactional(readOnly = true)
    public AlertView get(UUID id) {
        return views(List.of(inScope(alerts.findById(id).orElseThrow(() -> notFound(id))))).get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<StockAlert.Kind, Long> summary() {
        Set<UUID> allowed = WarehouseScope.restriction().map(AssignedWarehouses::ids).orElse(null);
        Map<StockAlert.Kind, Long> counts = alerts.openCountsByKind(allowed);
        Map<StockAlert.Kind, Long> all = new EnumMap<>(StockAlert.Kind.class);
        for (StockAlert.Kind kind : StockAlert.Kind.values()) {
            all.put(kind, counts.getOrDefault(kind, 0L));
        }
        return all;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UnconfiguredView> unconfigured(int page, int size) {
        Pageable pageable = Pages.of(page, size);
        if (WarehouseScope.restriction().isPresent()) {
            throw new BusinessException(ErrorCode.OUT_OF_DATA_SCOPE,
                    "Thresholds are per SKU for the whole system; the list is not available per warehouse");
        }
        LocalDate today = BusinessCalendar.date(clock.instant());
        List<UnconfiguredView> rows = policies.unconfigured(pageable.getPageSize(), pageable.getOffset(), today).stream()
                .map(r -> new UnconfiguredView(r.sku(), r.usableOnHand(), r.safetyStock())).toList();
        return PageResponse.of(rows, page, size, policies.countUnconfigured(today));
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "stock-alert", resourceId = "#id")
    public AlertView acknowledge(UUID id, long version, String note, UUID userId) {
        StockAlert alert = inScope(alerts.findByIdForUpdate(id).orElseThrow(() -> notFound(id)));
        if (alert.version() != version) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, "Stock alert " + id + " changed since it was read");
        }
        alert.acknowledge(userId, note, clock.instant());
        alerts.update(alert);
        return views(List.of(alerts.findById(id).orElseThrow())).get(0);
    }

    // ------------------------------------------------------------------ helpers

    private static BusinessException notFound(UUID id) {
        return new BusinessException(ErrorCode.ALERT_NOT_FOUND, "No stock alert " + id);
    }

    /** A warehouse-limited caller reaches expiry alerts of their warehouses only; REORDER/SAFETY have no warehouse. */
    private static StockAlert inScope(StockAlert alert) {
        if (WarehouseScope.restriction().isPresent()) {
            WarehouseScope.requireWarehouse(alert.warehouseId());
        }
        return alert;
    }

    /** Whitelisted property names to column names; never request text in SQL. */
    private static String orderBy(Sort sort) {
        List<String> parts = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = switch (order.getProperty()) {
                case "openedAt" -> "opened_at";
                case "kind" -> "kind";
                case "sku" -> "sku";
                default -> throw new IllegalArgumentException("Unexpected sort " + order.getProperty());
            };
            parts.add(column + (order.isDescending() ? " DESC" : " ASC"));
        }
        parts.add("id DESC");
        return String.join(", ", parts);
    }

    /** Resolves warehouse codes and names for a page in one lookup per distinct warehouse / one query. */
    private List<AlertView> views(List<StockAlert> found) {
        LocalDate today = BusinessCalendar.date(clock.instant());
        Map<UUID, String> codes = new HashMap<>();
        for (UUID warehouse : new HashSet<>(found.stream().map(StockAlert::warehouseId).filter(Objects::nonNull).toList())) {
            codes.put(warehouse, warehouses.findWarehouse(warehouse).map(WarehouseView::prefix).orElse(null));
        }
        Set<UUID> people = new HashSet<>(found.stream().map(StockAlert::acknowledgedBy).filter(Objects::nonNull).toList());
        Map<UUID, String> names = people.isEmpty() ? Map.of() : identities.displayNames(people);
        return found.stream().map(a -> new AlertView(a.id(), a.kind(), a.sku(), a.warehouseId(),
                a.warehouseId() == null ? null : codes.get(a.warehouseId()), a.lotNumber(), a.expiryDate(),
                a.expiryDate() == null ? null : ChronoUnit.DAYS.between(today, a.expiryDate()), a.observedQty(),
                a.thresholdQty(), a.reservationImpaired(), a.status(), a.openedAt(), a.lastObservedAt(),
                a.acknowledgedBy(), a.acknowledgedBy() == null ? null : names.get(a.acknowledgedBy()),
                a.acknowledgedAt(), a.ackNote(), a.resolvedAt(), a.resolution(), a.version())).toList();
    }
}
