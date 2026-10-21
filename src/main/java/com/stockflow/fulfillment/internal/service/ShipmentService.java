package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.Specs;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.fulfillment.internal.domain.DeliveryFailureReason;
import com.stockflow.fulfillment.internal.domain.Shipment;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentEventJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentEventJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * See {@link Shipments}. An outcome locks the shipment's row, then (through {@code OrderService}) the order's: the same
 * order as the handover's shipments-then-order, so the two never deadlock.
 */
@Service
@Transactional
class ShipmentService implements Shipments {

    private static final Logger log = LoggerFactory.getLogger(ShipmentService.class);

    private final ShipmentJpaRepository shipments;
    private final com.stockflow.fulfillment.internal.repository.ShipmentLocks locks;
    private final ShipmentEventJpaRepository events;
    private final PackageJpaRepository packages;
    private final PickJpaRepository picks;
    private final CarrierJpaRepository carriers;
    private final DispatchLoadJpaRepository loads;
    private final DispatchLoadShipmentJpaRepository members;
    private final ShipmentTimeline timeline;
    private final ShipmentContext context;
    private final OrderService orders;
    private final IdentityService identities;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    ShipmentService(ShipmentJpaRepository shipments, com.stockflow.fulfillment.internal.repository.ShipmentLocks locks,
                    ShipmentEventJpaRepository events, PackageJpaRepository packages,
                    PickJpaRepository picks, CarrierJpaRepository carriers, DispatchLoadJpaRepository loads,
                    DispatchLoadShipmentJpaRepository members, ShipmentTimeline timeline, ShipmentContext context,
                    OrderService orders,
                    IdentityService identities, Clock clock) {
        this.shipments = shipments;
        this.locks = locks;
        this.events = events;
        this.packages = packages;
        this.picks = picks;
        this.carriers = carriers;
        this.loads = loads;
        this.members = members;
        this.timeline = timeline;
        this.context = context;
        this.orders = orders;
        this.identities = identities;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ShipmentRow> list(UUID warehouseId, String status, UUID orderId, UUID carrierId,
                                          String search, int page, int size) {
        if (warehouseId != null) {
            WarehouseScope.requireWarehouse(warehouseId);
        }
        List<Specification<ShipmentJpaEntity>> filters = new ArrayList<>();
        filters.add(WarehouseScope.warehousesIn("warehouseId"));
        if (warehouseId != null) {
            filters.add(Specs.eq("warehouseId", warehouseId));
        }
        if (status != null && !status.isBlank()) {
            filters.add(Specs.eq("status", status(status)));
        }
        if (orderId != null) {
            filters.add(Specs.eq("orderId", orderId));
        }
        if (carrierId != null) {
            filters.add(Specs.eq("carrierId", carrierId));
        }
        if (search != null && !search.isBlank()) {
            filters.add(Specs.contains("code", search.trim()));
        }
        var found = shipments.findAll(Specification.allOf(filters),
                Pages.of(page, size, Sort.by(Sort.Direction.DESC, "readyAt", "id")));
        Map<UUID, ShipmentRow> rows = rows(found.getContent()).stream()
                .collect(Collectors.toMap(ShipmentRow::shipmentId, Function.identity()));
        return Pages.toResponse(found, row -> rows.get(row.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public ShipmentDetail get(UUID shipmentId) {
        ShipmentJpaEntity row = shipments.findById(shipmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHIPMENT_NOT_FOUND));
        WarehouseScope.requireWarehouse(row.getWarehouseId());
        return detail(row);
    }

    @Override
    public ShipmentDetail assignCarrier(UUID shipmentId, UUID carrierId, CurrentUser actor) {
        ShipmentJpaEntity row = lock(shipmentId);
        if (carrierId != null) {
            CarrierJpaEntity carrier = carriers.findById(carrierId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CARRIER_NOT_FOUND));
            if (!carrier.isActive()) {
                throw new BusinessException(ErrorCode.CARRIER_INACTIVE,
                        "Carrier %s is no longer in use".formatted(carrier.getCode()));
            }
        }
        members.findByShipmentIdInAndActiveTrue(Set.of(shipmentId)).stream().findFirst().ifPresent(on -> {
            if (!Objects.equals(carrierId, row.getCarrierId())) {
                throw new BusinessException(ErrorCode.SHIPMENT_ON_ANOTHER_LOAD, "Shipment %s is on load %s; take it "
                        .formatted(row.getCode(), loads.findById(on.getLoadId()).map(DispatchLoadJpaEntity::getCode)
                                .orElse("?")) + "off before choosing another carrier");
            }
        });
        Shipment shipment = row.toDomain();
        if (shipment.assignCarrier(carrierId)) {
            row.apply(shipment);
            shipments.saveAndFlush(row);
        }
        return detail(row);
    }

    @Override
    public ShipmentDetail recordOutcome(UUID shipmentId, Outcome outcome, CurrentUser actor) {
        ShipmentJpaEntity row = lock(shipmentId);
        Shipment shipment = row.toDomain();
        ShipmentStatus before = shipment.status();
        ShipmentStatus target = outcome(outcome.outcome());
        Instant now = clock.instant();
        UUID by = actor.userId();
        String note = outcome.note() == null || outcome.note().isBlank() ? null : outcome.note().trim();
        DeliveryFailureReason reason = null;
        boolean changed = switch (target) {
            case DELIVERED -> shipment.recordDelivered(by, now);
            case DELIVERY_FAILED -> {
                reason = reason(outcome.failureReason());
                yield shipment.recordFailed(reason, note, by, now);
            }
            case RETURNED -> shipment.recordReturned(by, now);
            default -> throw new IllegalStateException(target.name());
        };
        if (!changed) {
            return detail(row);
        }
        OrderSummary order = tellOrder(row, target, reason, note, by);
        row.apply(shipment);
        shipments.saveAndFlush(row);
        String carrierName = row.getCarrierId() == null ? null
                : carriers.findById(row.getCarrierId()).map(CarrierJpaEntity::getName).orElse(null);
        String loadCode = row.getLoadId() == null ? null
                : loads.findById(row.getLoadId()).map(DispatchLoadJpaEntity::getCode).orElse(null);
        timeline.record(shipment, before, order, null, carrierName, loadCode);
        log.info("Shipment {}: {} -> {} (order now {})", row.getCode(), before, target,
                order == null ? "?" : order.status());
        return detail(row);
    }

    /**
     * The order follows in this transaction (A1). A shipment the order does not know while it records no shipment at
     * all left as the whole order (before loads existed, or packed before parcels recorded their contents): its outcome
     * is the order's own, as long as it is the order's only shipment not cancelled.
     */
    private OrderSummary tellOrder(ShipmentJpaEntity row, ShipmentStatus target, DeliveryFailureReason reason,
                                   String note, UUID by) {
        OrderSummary order = orders.findById(row.getOrderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order " + row.getOrderId()));
        boolean known = order.lifecycle() != null && order.lifecycle().shipments().stream()
                .anyMatch(s -> s.shipmentId().equals(row.getId()));
        boolean orderHasNone = order.lifecycle() == null || order.lifecycle().shipments().isEmpty();
        // The whole-order path, for one rule only: the order does not know this shipment and records none - a shipment
        // handed over as the whole order (wholeOrderHandover), however it got there.
        if (!known && (row.isWholeOrderHandover() || orderHasNone)) {
            long others = shipments.findByOrderIdOrderByReadyAtAscIdAsc(row.getOrderId()).stream()
                    .filter(s -> !s.getId().equals(row.getId()) && s.getStatus() != ShipmentStatus.CANCELLED)
                    .count();
            if (others > 0 || !orderHasNone) {
                throw new BusinessException(ErrorCode.SHIPMENT_STATE_CONFLICT, "Shipment %s left before shipments "
                        .formatted(row.getCode()) + "were recorded on the order, beside other shipments: record "
                        + "this order's outcome by hand");
            }
            return switch (target) {
                case DELIVERED -> orders.recordDelivery(row.getOrderId(), by);
                case DELIVERY_FAILED -> orders.recordDeliveryFailure(row.getOrderId(), orderReason(reason), note, by);
                case RETURNED -> orders.recordReturnedToOrigin(row.getOrderId(), by);
                default -> throw new IllegalStateException(target.name());
            };
        }
        return switch (target) {
            case DELIVERED -> orders.recordShipmentDelivered(row.getOrderId(), row.getId(), by);
            case DELIVERY_FAILED -> orders.recordShipmentDeliveryFailed(row.getOrderId(), row.getId(),
                    orderReason(reason), note, by);
            case RETURNED -> orders.recordShipmentReturned(row.getOrderId(), row.getId(), by);
            default -> throw new IllegalStateException(target.name());
        };
    }

    private static com.stockflow.order.api.DeliveryFailureReason orderReason(DeliveryFailureReason reason) {
        return com.stockflow.order.api.DeliveryFailureReason.valueOf(reason.name());
    }

    private ShipmentJpaEntity lock(UUID shipmentId) {
        ShipmentJpaEntity row = locks.lock(Set.of(shipmentId)).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SHIPMENT_NOT_FOUND));
        WarehouseScope.requireWarehouse(row.getWarehouseId());
        return row;
    }

    private ShipmentDetail detail(ShipmentJpaEntity row) {
        ShipmentRow view = rows(List.of(row)).getFirst();
        List<Parcel> parcels = packages.findByShipmentIdOrderByPackageNoAsc(row.getId()).stream()
                .map(p -> new Parcel(p.getId(), p.getLabelCode(), p.getStatus().name(), p.getWeightKg(),
                        p.getLengthCm(), p.getWidthCm(), p.getHeightCm())).toList();
        List<ShipmentEventJpaEntity> steps = events.findByShipmentIdOrderByOccurredAtAscIdAsc(row.getId());
        Map<UUID, String> names = new java.util.HashMap<>(identities.displayNames(steps.stream()
                .map(ShipmentEventJpaEntity::getRecordedBy).filter(Objects::nonNull).distinct().toList()));
        return new ShipmentDetail(view, row.getPartialNote(), row.getFailureNote(), row.getCancelledAt(), parcels,
                steps.stream().map(e -> new Step(e.getStatus(), e.getOccurredAt(), e.getRecordedBy(),
                        names.get(e.getRecordedBy()), e.getNote())).toList());
    }

    /** Rows with their order numbers, carriers and loads, read in one query each. */
    private List<ShipmentRow> rows(Collection<ShipmentJpaEntity> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = found.stream().map(ShipmentJpaEntity::getId).collect(Collectors.toSet());
        Map<UUID, String> numbers = picks.findByOrderIdIn(found.stream().map(ShipmentJpaEntity::getOrderId)
                .collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(PickJpaEntity::getOrderId, PickJpaEntity::getOrderNumber, (a, b) -> a,
                        java.util.HashMap::new));
        Set<UUID> carrierIds = found.stream().map(ShipmentJpaEntity::getCarrierId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // HashMaps throughout: a shipment without a carrier or a load looks up null, which Map.of() refuses.
        Map<UUID, String> carrierNames = new java.util.HashMap<>();
        carriers.findAllById(carrierIds).forEach(c -> carrierNames.put(c.getId(), c.getName()));
        // The load each one is on now (open or dispatched), or the one it left on.
        Map<UUID, UUID> loadOf = new java.util.HashMap<>();
        members.findByShipmentIdInAndActiveTrue(ids).forEach(m -> loadOf.put(m.getShipmentId(), m.getLoadId()));
        found.stream().filter(s -> s.getLoadId() != null).forEach(s -> loadOf.put(s.getId(), s.getLoadId()));
        Map<UUID, String> loadCodes = new java.util.HashMap<>();
        if (!loadOf.isEmpty()) {
            loads.findByIdIn(Set.copyOf(loadOf.values())).forEach(l -> loadCodes.put(l.getId(), l.getCode()));
        }
        Map<UUID, UUID> tasks = context.tasks(found);
        Map<UUID, String> currencies = context.currencies(found.stream().map(ShipmentJpaEntity::getOrderId).toList());
        return found.stream().map(s -> new ShipmentRow(s.getId(), s.getCode(), s.getOrderId(),
                numbers.get(s.getOrderId()), tasks.get(s.getId()), s.getWarehouseId(), s.getStatus().name(),
                s.getDeliveryMode().name(), s.getPackageCount(), s.getCodAmount(), currencies.get(s.getOrderId()),
                s.getCarrierId(), carrierNames.get(s.getCarrierId()),
                loadOf.get(s.getId()), loadCodes.get(loadOf.get(s.getId())), s.isPartial(), s.getReadyAt(),
                s.getHandedOverAt(), s.getOutcomeAt(), s.getFailureReason() == null ? null : s.getFailureReason().name(),
                s.isWholeOrderHandover(), s.getVersion())).toList();
    }

    private static ShipmentStatus status(String raw) {
        try {
            return ShipmentStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER, "Unknown shipment status",
                    List.of(FieldError.of("status", "must be one of " + java.util.Arrays.toString(
                            ShipmentStatus.values()))));
        }
    }

    private static ShipmentStatus outcome(String raw) {
        ShipmentStatus status = raw == null ? null : switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "DELIVERED" -> ShipmentStatus.DELIVERED;
            case "DELIVERY_FAILED" -> ShipmentStatus.DELIVERY_FAILED;
            case "RETURNED" -> ShipmentStatus.RETURNED;
            default -> null;
        };
        if (status == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Unknown outcome",
                    List.of(FieldError.of("outcome", "DELIVERED, DELIVERY_FAILED or RETURNED")));
        }
        return status;
    }

    private static DeliveryFailureReason reason(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A failed delivery says why",
                    List.of(FieldError.of("failureReason", "required with DELIVERY_FAILED")));
        }
        try {
            return DeliveryFailureReason.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Unknown failure reason",
                    List.of(FieldError.of("failureReason", "one of " + java.util.Arrays.toString(
                            DeliveryFailureReason.values()))));
        }
    }
}
