package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.Role;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.design.api.DesignArtifactRole;
import com.stockflow.design.api.DesignService;
import com.stockflow.fulfillment.api.FulfillmentArtifactAccess;
import com.stockflow.fulfillment.api.FulfillmentAssignee;
import com.stockflow.fulfillment.api.FulfillmentService;
import com.stockflow.fulfillment.api.FulfillmentTask;
import com.stockflow.fulfillment.api.FulfillmentTaskQuery;
import com.stockflow.fulfillment.internal.domain.DesignVerificationResult;
import com.stockflow.fulfillment.internal.domain.PackStatus;
import com.stockflow.fulfillment.internal.domain.PickStatus;
import com.stockflow.fulfillment.internal.entity.DesignVerificationJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.fulfillment.internal.repository.DesignVerificationJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.OrderHold;
import com.stockflow.inventory.api.StockReservation;
import com.stockflow.inventory.consumption.PickConsumption;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.persistence.Pages;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Fulfillment transaction boundary and task/data-scope enforcement. */
@Service
@Transactional
class FulfillmentServiceImpl implements FulfillmentService {

    /** Who a pick can be given to: the two warehouse roles that work orders. */
    private static final Set<String> ASSIGNEE_ROLES = Set.of(
            Role.WAREHOUSE_STAFF.authority(), Role.WAREHOUSE_MANAGER.authority());

    private final PickJpaRepository picks;
    private final PackJpaRepository packs;
    private final DesignVerificationJpaRepository verifications;
    private final OrderService orders;
    private final DesignService designs;
    private final Clock clock;
    private final IdentityService identities;
    private final InventoryService inventory;
    private final WarehouseService warehouses;
    private final PickListService pickLists;
    private final PickConsumption consumption;

    FulfillmentServiceImpl(PickJpaRepository picks, PackJpaRepository packs,
            DesignVerificationJpaRepository verifications, OrderService orders,
            DesignService designs, IdentityService identities, InventoryService inventory,
            WarehouseService warehouses, PickListService pickLists, PickConsumption consumption, Clock clock) {
        this.consumption = consumption;
        this.picks = picks;
        this.packs = packs;
        this.verifications = verifications;
        this.orders = orders;
        this.designs = designs;
        this.identities = identities;
        this.inventory = inventory;
        this.warehouses = warehouses;
        this.pickLists = pickLists;
        this.clock = clock;
    }

    @Override
    public FulfillmentTask createTask(UUID orderId, UUID assignedUserId, CurrentUser actor) {
        requireRole(actor, Role.ORDER_COORDINATOR, Role.WAREHOUSE_MANAGER);
        var existing = picks.findByOrderId(orderId);
        if (existing.isPresent()) {
            requireWarehouseAssignee(assignedUserId, existing.get().getWarehouseId());
            TaskAccess.requireVisible(existing.get(), actor);
            return toTask(existing.get());
        }
        OrderSummary order = requireOrder(orderId);
        // READY_TO_FULFILL: released, and production (if any) delivered (SCRUM-423). CONFIRMED: a stock-only
        // order nobody released yet, as before release existed; the order refuses one with print lines.
        if (order.status() != OrderStatus.CONFIRMED && order.status() != OrderStatus.READY_TO_FULFILL) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "Only a confirmed or released order can enter fulfillment; it is " + order.status());
        }
        UUID warehouseId = pickingWarehouse(order);
        WarehouseScope.requireWarehouse(warehouseId);
        requireWarehouseAssignee(assignedUserId, warehouseId);
        orders.releaseToFulfillment(orderId);
        var now = clock.instant();
        var pick = new PickJpaEntity(Identifiers.newId(), null, orderId, order.orderNumber(), warehouseId,
                PickStatus.PENDING);
        pick.assign(assignedUserId, now);
        picks.saveAndFlush(pick);      // the pick row first: its lines' trigger and foreign key look for it
        packs.save(new PackJpaEntity(Identifiers.newId(), pick.getId(), PackStatus.PENDING, null));
        // SCRUM-267: one line per hold of the order's stock lines, in this warehouse (docs 07 step 2).
        pickLists.planLines(pick, order);
        return toTask(pick, nameOf(assignedUserId), null);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<FulfillmentTask> listTasks(CurrentUser actor, FulfillmentTaskQuery query) {
        if (query.warehouseId() != null) {
            WarehouseScope.requireWarehouse(query.warehouseId());
        }
        var filters = new ArrayList<Specification<PickJpaEntity>>();
        filters.add(WarehouseScope.warehousesIn("warehouseId"));
        PickStatus status = parseStatus(query.status());
        if (status != null) {
            filters.add((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (query.warehouseId() != null) {
            filters.add((root, q, cb) -> cb.equal(root.get("warehouseId"), query.warehouseId()));
        }
        if (query.assignedUserId() != null) {
            filters.add((root, q, cb) -> cb.equal(root.get("assignedUserId"), query.assignedUserId()));
        }
        // The role decides whose tasks: managers and coordinators all of them, QC those on a design hold,
        // a picker their own.
        if (actor.hasAnyRole(Role.WAREHOUSE_MANAGER, Role.ORDER_COORDINATOR)) {
            // no further filter
        } else if (actor.hasRole(Role.QC_STAFF)) {
            filters.add((root, q, cb) -> {
                var held = q.subquery(UUID.class);
                var pack = held.from(PackJpaEntity.class);
                held.select(pack.get("pickId")).where(cb.equal(pack.get("pickId"), root.get("id")),
                        cb.equal(pack.get("status"), PackStatus.ON_HOLD));
                return cb.exists(held);
            });
        } else if (actor.hasRole(Role.WAREHOUSE_STAFF)) {
            filters.add((root, q, cb) -> cb.equal(root.get("assignedUserId"), actor.userId()));
        } else {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        var newestFirst = Sort.by(Sort.Direction.DESC, "createdAt", "id");
        Page<PickJpaEntity> selected = picks.findAll(Specification.allOf(filters),
                Pages.of(query.page(), query.size(), newestFirst));
        Map<UUID, String> names = identities.displayNames(selected.getContent().stream()
                .map(PickJpaEntity::getAssignedUserId).toList());
        return Pages.toResponse(selected, pick -> toTask(pick, names.get(pick.getAssignedUserId()), null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FulfillmentAssignee> assignees(UUID warehouseId, CurrentUser actor) {
        requireRole(actor, Role.ORDER_COORDINATOR, Role.WAREHOUSE_MANAGER);
        WarehouseScope.requireWarehouse(warehouseId);
        if (warehouses.findWarehouse(warehouseId).isEmpty()) {
            throw new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND);
        }
        return identities.assignableUsers(ASSIGNEE_ROLES, warehouseId).stream()
                .map(user -> new FulfillmentAssignee(user.userId(), user.displayName(), user.roles()))
                .toList();
    }

    @Override
    public FulfillmentTask assign(UUID taskId, UUID assignedUserId, CurrentUser actor) {
        requireRole(actor, Role.ORDER_COORDINATOR, Role.WAREHOUSE_MANAGER);
        var pick = lockPick(taskId);
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        requireWarehouseAssignee(assignedUserId, pick.getWarehouseId());
        pick.assign(assignedUserId, clock.instant());
        return toTask(picks.save(pick));
    }

    @Override
    @Transactional(readOnly = true)
    public FulfillmentTask findTask(UUID taskId, CurrentUser actor) {
        var pick = requirePick(taskId);
        TaskAccess.requireVisible(pick, actor);
        return toTask(pick, nameOf(pick.getAssignedUserId()), linesOf(requireOrder(pick.getOrderId())));
    }

    /**
     * The assignee starts picking. A task opened before pick lines existed gets its lines now (lazily,
     * SCRUM-267); starting a task already PICKING plans its lines if it has none and changes nothing else.
     */
    @Override
    public FulfillmentTask startPicking(UUID taskId, CurrentUser actor) {
        var pick = lockPick(taskId);
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        pick.requireAssigned(actor.userId());
        var order = requireOrderInFulfilment(pick);
        pick.start(actor.userId(), clock.instant());
        picks.save(pick);
        pickLists.planLines(pick, order);
        return toTask(pick);
    }

    /** Docs 07 step 9: only when every line is picked ({@code PICK_LINES_INCOMPLETE} otherwise). */
    @Override
    public FulfillmentTask completePicking(UUID taskId, CurrentUser actor) {
        var pick = lockPick(taskId);
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        pick.requireAssigned(actor.userId());
        var order = requireOrderInFulfilment(pick);
        if (pick.getStatus() == PickStatus.PICKING) {
            pickLists.requireAllPicked(pick, order);
        }
        pick.complete(actor.userId(), clock.instant());
        return toTask(picks.save(pick));
    }

    @Override
    @Transactional(readOnly = true)
    public FulfillmentArtifactAccess artifactDownload(UUID taskId, UUID orderLineId, String role,
                                                       CurrentUser actor) {
        var pick = requirePick(taskId);
        requireArtifactAccess(pick, actor);
        var line = requireOrderInFulfilment(pick).lines().stream()
                .filter(value -> value.lineId().equals(orderLineId))
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (line.designSnapshotId() == null || line.designChecksum() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Order line has no confirmed design");
        }
        DesignArtifactRole parsedRole;
        try {
            parsedRole = DesignArtifactRole.valueOf(role.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER, "Unknown design artifact role");
        }
        var artifact = designs.fulfillmentDownload(
                line.designSnapshotId(), line.designChecksum(), parsedRole);
        return new FulfillmentArtifactAccess(artifact.artifactId(), artifact.role().name(),
                artifact.originalName(), artifact.contentType(), artifact.sizeBytes(), artifact.checksum(),
                artifact.url(), artifact.expiresAt());
    }

    @Override
    public FulfillmentTask completePacking(UUID taskId, CurrentUser actor) {
        var pick = lockPick(taskId);
        pick.requireAssigned(actor.userId());
        if (pick.getStatus() != PickStatus.PICKED) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT, "Picking must finish before packing");
        }
        var order = requireOrderInFulfilment(pick);
        var pack = requirePack(pick.getId());
        if (pack.getStatus() == PackStatus.ON_HOLD) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "A checksum hold requires QC or warehouse-manager reverification");
        }
        boolean matching = verifyAll(pack, order, actor.userId());
        if (!matching) {
            pack.hold();
            packs.save(pack);
            orders.putOnDesignHold(pick.getOrderId(), "DESIGN_MISMATCH");
            return toTask(pick);
        }
        // Under the order's row lock: a cancellation came first (refused, nothing deducted) or is refused after (03 §9).
        orders.recordPacked(pick.getOrderId());
        pack.complete(clock.instant());
        deductPrintedGoods(pack, order, actor.userId());
        packs.save(pack);
        return toTask(pick);
    }

    @Override
    public FulfillmentTask reverifyDesignIntegrity(UUID taskId, String resolutionNote, CurrentUser actor) {
        requireRole(actor, Role.QC_STAFF, Role.WAREHOUSE_MANAGER);
        var pick = lockPick(taskId);
        var pack = requirePack(pick.getId());
        if (pack.getStatus() != PackStatus.ON_HOLD) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT, "The package has no design-integrity hold");
        }
        OrderSummary order = requireOrderInFulfilment(pick);
        if (!verifyAll(pack, order, actor.userId())) {
            return toTask(pick);
        }
        orders.recordPacked(pick.getOrderId());
        pack.complete(clock.instant());
        deductPrintedGoods(pack, order, actor.userId());
        packs.save(pack);
        orders.resolveDesignHold(pick.getOrderId(), actor.userId(), resolutionNote);
        return toTask(pick);
    }

    /**
     * Decision 2026-10-16 (docs 04b §15-18, 04c F-FUL): the order's custom-printed goods leave stock with their
     * package, in the transaction that records it complete - after every design checksum matched, so a mismatch or
     * any failure leaves the holds and the goods where they are. Stock lines were deducted when picked; a print line
     * is never picked (production hands its goods to packing, still held for the order), so this is its one
     * deduction, and dispatch deducts nothing. A completed package is not completed again ({@code CONFLICT}), and
     * inventory answers a package it already deducted for as done.
     */
    private void deductPrintedGoods(PackJpaEntity pack, OrderSummary order, UUID actor) {
        List<UUID> printHolds = order.lines().stream().filter(line -> line.designSnapshotId() != null)
                .flatMap(line -> line.reservationIds().stream()).toList();
        if (printHolds.isEmpty()) {
            return;
        }
        List<UUID> live = inventory.liveHolds(printHolds).stream().map(StockReservation::reservationId).toList();
        consumption.consumeForPack(pack.getId(), order.orderId(), live, actor);
    }

    private boolean verifyAll(PackJpaEntity pack, OrderSummary order, UUID actor) {
        boolean allMatch = true;
        for (var line : order.lines()) {
            if (line.designSnapshotId() == null) {
                continue;
            }
            var check = designs.verifyForFulfillment(line.designSnapshotId(), line.designChecksum());
            var result = check.matching() ? DesignVerificationResult.MATCH : DesignVerificationResult.MISMATCH;
            verifications.save(new DesignVerificationJpaEntity(Identifiers.newId(), pack.getId(), order.orderId(),
                    line.lineId(), line.designSnapshotId(), check.expectedChecksum(), check.actualChecksum(),
                    result, actor, clock.instant()));
            allMatch &= check.matching();
        }
        return allMatch;
    }

    private void requireArtifactAccess(PickJpaEntity pick, CurrentUser actor) {
        if (!java.util.Objects.equals(pick.getAssignedUserId(), actor.userId())
                && !actor.hasAnyRole(Role.WAREHOUSE_MANAGER, Role.QC_STAFF)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private FulfillmentTask toTask(PickJpaEntity pick) {
        return toTask(pick, nameOf(pick.getAssignedUserId()), null);
    }

    private FulfillmentTask toTask(PickJpaEntity pick, String assignedUserName,
                                   List<FulfillmentTask.Line> lines) {
        var pack = requirePack(pick.getId());
        var evidence = verifications.findByPackIdOrderByVerifiedAtDesc(pack.getId()).stream()
                .map(value -> new FulfillmentTask.IntegrityEvidence(value.getOrderLineId(), value.getSnapshotId(),
                        value.getExpectedChecksum(), value.getActualChecksum(),
                        value.getResult() == DesignVerificationResult.MATCH, value.getVerifiedAt()))
                .toList();
        return new FulfillmentTask(pick.getId(), pick.getOrderId(), pick.getOrderNumber(), pick.getWarehouseId(),
                pick.getAssignedUserId(), assignedUserName, pick.getStatus().name(), pack.getStatus().name(),
                pick.getAssignedAt(), pick.getStartedAt(), pick.getPickedAt(), pack.getPackedAt(), evidence, lines);
    }

    private String nameOf(UUID userId) {
        return userId == null ? null : identities.displayNames(List.of(userId)).get(userId);
    }

    private static List<FulfillmentTask.Line> linesOf(OrderSummary order) {
        return order.lines().stream()
                .map(line -> new FulfillmentTask.Line(line.lineId(), line.sku(), line.quantity(),
                        line.designSnapshotId(), line.designSnapshotId() != null))
                .toList();
    }

    private static PickStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return PickStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER, "Unknown pick status",
                    List.of(FieldError.of("status", "must be one of "
                            + java.util.Arrays.stream(PickStatus.values()).map(Enum::name)
                            .collect(Collectors.joining(", ")))));
        }
    }

    private PickJpaEntity requirePick(UUID taskId) {
        return picks.findById(taskId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private PickJpaEntity lockPick(UUID taskId) {
        return picks.lockById(taskId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private PackJpaEntity requirePack(UUID pickId) {
        return packs.findByPickId(pickId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private OrderSummary requireOrderInFulfilment(PickJpaEntity pick) {
        return TaskAccess.orderInFulfilment(orders, pick);
    }

    private OrderSummary requireOrder(UUID orderId) {
        return orders.findById(orderId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static void requireRole(CurrentUser actor, Role... roles) {
        if (actor == null || !actor.hasAnyRole(roles)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    /**
     * BR-SEC-002: work goes to an active warehouse staff member or manager who is assigned to the
     * task's warehouse; anyone else is a 400 on the request's {@code assignedUserId}.
     */
    private void requireWarehouseAssignee(UUID userId, UUID warehouseId) {
        boolean eligible = userId != null && identities.assignableUsers(ASSIGNEE_ROLES, warehouseId).stream()
                .anyMatch(user -> user.userId().equals(userId));
        if (!eligible) {
            String message = "Assignee must be an active warehouse staff member or warehouse manager "
                    + "assigned to this warehouse";
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, message,
                    List.of(new FieldError("assignedUserId", message, "NotAssignable")));
        }
    }

    /**
     * The warehouse a new task is picked in: the order's own once it is released; for a stock-only
     * order picked straight from CONFIRMED, where its holds are. Release does not check where the holds
     * sit, so they are checked here: stock in two warehouses, or outside the order's, cannot be picked as
     * one task (the goods would have to be moved by a transfer order first, docs 11 BR-01).
     */
    private UUID pickingWarehouse(OrderSummary order) {
        List<OrderHold> holds = inventory.holdsOf(order.orderId());
        Map<UUID, Set<String>> skusByWarehouse = new java.util.LinkedHashMap<>();
        for (OrderHold hold : holds) {
            UUID where = warehouses.findLocation(hold.locationCode()).map(StorageLocationView::warehouseId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.LOCATION_NOT_FOUND,
                            "Stock held for the order sits at an unknown location"));
            skusByWarehouse.computeIfAbsent(where, key -> new TreeSet<>()).add(hold.sku());
        }
        UUID released = order.warehouseId();
        if (released != null) {
            Set<String> elsewhere = new TreeSet<>();
            skusByWarehouse.forEach((where, skus) -> {
                if (!where.equals(released)) {
                    elsewhere.addAll(skus);
                }
            });
            if (!elsewhere.isEmpty()) {
                throw new BusinessException(ErrorCode.FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE,
                        "Order %s ships from warehouse %s but stock for %s is held in another warehouse"
                                .formatted(order.orderNumber(), released, String.join(", ", elsewhere)));
            }
            return released;
        }
        if (skusByWarehouse.size() > 1) {
            Set<String> all = new TreeSet<>();
            skusByWarehouse.values().forEach(all::addAll);
            throw new BusinessException(ErrorCode.FULFILLMENT_HOLDS_OUTSIDE_WAREHOUSE,
                    "Stock for order %s (%s) is held in %d warehouses; it cannot be picked as one task"
                            .formatted(order.orderNumber(), String.join(", ", all), skusByWarehouse.size()));
        }
        if (skusByWarehouse.isEmpty()) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "Order %s has no stock held and no release warehouse, so its warehouse is unknown"
                            .formatted(order.orderNumber()));
        }
        return skusByWarehouse.keySet().iterator().next();
    }
}
