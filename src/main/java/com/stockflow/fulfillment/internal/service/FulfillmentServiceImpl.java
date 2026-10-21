package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Action;
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
import com.stockflow.fulfillment.api.PackingCompletion;
import com.stockflow.fulfillment.internal.domain.DesignVerificationResult;
import com.stockflow.fulfillment.internal.domain.PackStatus;
import com.stockflow.fulfillment.internal.domain.PickStatus;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FulfillmentServiceImpl.class);

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
    private final PackingService packing;

    FulfillmentServiceImpl(PickJpaRepository picks, PackJpaRepository packs,
            DesignVerificationJpaRepository verifications, OrderService orders,
            DesignService designs, IdentityService identities, InventoryService inventory,
            WarehouseService warehouses, PickListService pickLists, PackingService packing, Clock clock) {
        this.packing = packing;
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
        require(actor, FulfillmentPermissions.TASK_ASSIGNMENT, Action.CREATE);
        var existing = picks.findByOrderId(orderId);
        if (existing.isPresent()) {
            requireWarehouseAssignee(assignedUserId, existing.get().getWarehouseId());
            TaskAccess.requireVisible(existing.get(), actor);
            return toTask(existing.get());
        }
        OrderSummary order = requireOrder(orderId);
        // READY_TO_FULFILL only: released - its warehouse chosen - and production (if any) delivered (SCRUM-423,
        // kltn-docs 17 BR-02). A CONFIRMED order is released by the coordinator first (G3-2).
        if (order.status() != OrderStatus.READY_TO_FULFILL) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "Only a released order (READY_TO_FULFILL) can enter fulfillment; it is " + order.status());
        }
        UUID warehouseId = pickingWarehouse(order);
        WarehouseScope.requireWarehouse(warehouseId);
        requireWarehouseAssignee(assignedUserId, warehouseId);
        // In this transaction, under the order's row lock (A1): READY_TO_FULFILL → PICKING.
        orders.startPicking(orderId, actor.userId());
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
        // Whose tasks (data scope, not access - the endpoint's permission decided that): every task of the caller's
        // warehouses with oversight, those on a design hold for whoever re-verifies designs, otherwise the caller's own.
        if (actor.can(FulfillmentPermissions.TASK_OVERSIGHT, Action.READ)) {
            // no further filter
        } else if (actor.can(FulfillmentPermissions.DESIGN_REVERIFICATION, Action.READ)) {
            filters.add((root, q, cb) -> {
                var held = q.subquery(UUID.class);
                var pack = held.from(PackJpaEntity.class);
                held.select(pack.get("pickId")).where(cb.equal(pack.get("pickId"), root.get("id")),
                        cb.equal(pack.get("status"), PackStatus.ON_HOLD));
                return cb.exists(held);
            });
        } else {
            actor.require(FulfillmentPermissions.PICK_LISTS, Action.READ);
            filters.add((root, q, cb) -> cb.equal(root.get("assignedUserId"), actor.userId()));
        }
        var newestFirst = Sort.by(Sort.Direction.DESC, "createdAt", "id");
        Page<PickJpaEntity> selected = picks.findAll(Specification.allOf(filters),
                Pages.of(query.page(), query.size(), newestFirst));
        Map<UUID, String> names = identities.displayNames(selected.getContent().stream()
                .map(PickJpaEntity::getAssignedUserId).toList());
        List<UUID> pickIds = selected.getContent().stream().map(PickJpaEntity::getId).toList();
        Map<UUID, PackJpaEntity> packByPick = pickIds.isEmpty() ? Map.of() : packs.findByPickIdIn(pickIds).stream()
                .collect(Collectors.toMap(PackJpaEntity::getPickId, java.util.function.Function.identity()));
        return Pages.toResponse(selected, pick -> toTask(pick, packByPick.get(pick.getId()),
                names.get(pick.getAssignedUserId()), null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FulfillmentAssignee> assignees(UUID warehouseId, CurrentUser actor) {
        require(actor, FulfillmentPermissions.TASK_ASSIGNMENT, Action.UPDATE);
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
        require(actor, FulfillmentPermissions.TASK_ASSIGNMENT, Action.UPDATE);
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
        TaskAccess.requireArtifactAccess(pick, actor);
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
        return completePacking(taskId, PackingCompletion.whole(), actor);
    }

    /** G4-5: see {@link PackingService#complete}; the answer is the task, with the shipment and the payment warning. */
    @Override
    public FulfillmentTask completePacking(UUID taskId, PackingCompletion completion, CurrentUser actor) {
        require(actor, FulfillmentPermissions.PACKING, Action.UPDATE);
        PackingService.Completed outcome = packing.complete(taskId, completion == null ? PackingCompletion.whole()
                : completion, actor);
        FulfillmentTask task = toTask(requirePick(taskId));
        return outcome.shipmentId() == null ? task
                : withPaymentWarning(task, outcome.order()).withShipment(outcome.shipmentId());
    }

    @Override
    public FulfillmentTask reverifyDesignIntegrity(UUID taskId, String resolutionNote, CurrentUser actor) {
        require(actor, FulfillmentPermissions.DESIGN_REVERIFICATION, Action.UPDATE);
        packing.reverify(taskId, resolutionNote, actor);
        return toTask(requirePick(taskId));
    }

    private FulfillmentTask toTask(PickJpaEntity pick) {
        return toTask(pick, nameOf(pick.getAssignedUserId()), null);
    }

    private FulfillmentTask toTask(PickJpaEntity pick, String assignedUserName,
                                   List<FulfillmentTask.Line> lines) {
        return toTask(pick, packs.findByPickId(pick.getId()).orElse(null), assignedUserName, lines);
    }

    /**
     * A task as the API shows it. {@code pack} null - a pick without its pack row, which {@link #createTask} never
     * leaves but data loaded around it did (E2E QA 2026-10-21: three seeded picks made {@code GET /tasks} 404 for
     * everyone) - reads as packing not started ({@code PENDING}), logged, rather than failing the task or, worse, a
     * whole list of tasks for one row. Packing itself still requires the row ({@code PackingService}).
     */
    private FulfillmentTask toTask(PickJpaEntity pick, PackJpaEntity pack, String assignedUserName,
                                   List<FulfillmentTask.Line> lines) {
        if (pack == null) {
            log.warn("Fulfillment task {} (order {}) has no pack row; shown as packing not started", pick.getId(),
                    pick.getOrderNumber());
        }
        var evidence = pack == null ? List.<FulfillmentTask.IntegrityEvidence>of()
                : verifications.findByPackIdOrderByVerifiedAtDesc(pack.getId()).stream()
                .map(value -> new FulfillmentTask.IntegrityEvidence(value.getOrderLineId(), value.getSnapshotId(),
                        value.getExpectedChecksum(), value.getActualChecksum(),
                        value.getResult() == DesignVerificationResult.MATCH, value.getVerifiedAt()))
                .toList();
        return new FulfillmentTask(pick.getId(), pick.getOrderId(), pick.getOrderNumber(), pick.getWarehouseId(),
                pick.getAssignedUserId(), assignedUserName, pick.getStatus().name(),
                (pack == null ? PackStatus.PENDING : pack.getStatus()).name(),
                pick.getAssignedAt(), pick.getStartedAt(), pick.getPickedAt(), pack == null ? null : pack.getPackedAt(),
                evidence, lines);
    }

    /**
     * G3-Q4 / G2-3: the early warning at packing - the order still owes money its terms want before the goods leave,
     * so the handover will be refused until the accountant records it.
     */
    private static FulfillmentTask withPaymentWarning(FulfillmentTask task, OrderSummary packed) {
        var due = packed == null || packed.lifecycle() == null ? null : packed.lifecycle().amountDueBeforeHandover();
        if (due == null || due.signum() <= 0) {
            return task;
        }
        return task.withPaymentDue(new FulfillmentTask.PaymentDue(due, packed.total().currency().getCurrencyCode()));
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

    private OrderSummary requireOrderInFulfilment(PickJpaEntity pick) {
        return TaskAccess.orderInFulfilment(orders, pick);
    }

    private OrderSummary requireOrder(UUID orderId) {
        return orders.findById(orderId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static void require(CurrentUser actor, String resource, Action action) {
        if (actor == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        actor.require(resource, action);
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
