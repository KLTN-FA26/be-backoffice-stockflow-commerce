package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.Role;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.fulfillment.api.FulfillmentAssignee;
import com.stockflow.fulfillment.api.FulfillmentService;
import com.stockflow.fulfillment.api.FulfillmentTask;
import com.stockflow.fulfillment.api.FulfillmentTaskQuery;
import com.stockflow.fulfillment.api.PackingCompletion;
import com.stockflow.fulfillment.api.PickDestination;
import com.stockflow.fulfillment.internal.domain.PackStatus;
import com.stockflow.fulfillment.internal.domain.PickStatus;
import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
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
    private final OrderService orders;
    private final Clock clock;
    private final IdentityService identities;
    private final InventoryService inventory;
    private final WarehouseService warehouses;
    private final PickListService pickLists;
    private final CartonCheckService cartonChecks;

    FulfillmentServiceImpl(PickJpaRepository picks, PackJpaRepository packs, OrderService orders,
            IdentityService identities, InventoryService inventory,
            WarehouseService warehouses, PickListService pickLists, CartonCheckService cartonChecks, Clock clock) {
        this.cartonChecks = cartonChecks;
        this.picks = picks;
        this.packs = packs;
        this.orders = orders;
        this.identities = identities;
        this.inventory = inventory;
        this.warehouses = warehouses;
        this.pickLists = pickLists;
        this.clock = clock;
    }

    @Override
    public FulfillmentTask createTask(UUID orderId, UUID assignedUserId, CurrentUser actor) {
        return createTask(orderId, assignedUserId, PickDestination.DISPATCH, null, actor);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The staging area is resolved after the warehouse is known and before anything is written: a STAGING task that
     * could stage nowhere is refused whole.</p>
     *
     * <p>A redelivery is opened under the order's row lock, taken before anything else is locked (the global order:
     * customer_order first), and is read again under it (WF-05 review N4). Read without it, Sales cancelling the case
     * at the same moment could commit first, and its {@code RedeliveryWithdrawn} listener could look for the order's
     * picks before this one committed: the pick would stay PENDING for a CANCELLED case. Cancelling takes the order's
     * row lock first too, so under it either the cancellation came first - the case is no longer released and no task
     * is opened for it - or it waits for this pick, and its listener finds and cancels it.</p>
     */
    @Override
    public FulfillmentTask createTask(UUID orderId, UUID assignedUserId, PickDestination destination,
                                      String stagingLocationCode, CurrentUser actor) {
        require(actor, FulfillmentPermissions.TASK_ASSIGNMENT, Action.CREATE);
        List<PickJpaEntity> existing = picks.findAllByOrderId(orderId);
        // WF-05 fix plan, section 2: goods of a shipment that came back, released to be delivered again, get a task of
        // their own - a second pick of a batch (or an order) picked once already. It comes before a new batch: a
        // batch is released only once the one before it is handed over, and that one is being handed over again.
        var redelivery = orders.redeliveryToPick(orderId, redeliveryCasesOf(existing));
        if (redelivery.isPresent()) {
            // WF-05 review N4: the order's row lock first, then the picks and the case read again under it - the case
            // may have been cancelled, or picked by a concurrent request, since the unlocked read.
            orders.lockedStatus(orderId);
            existing = picks.findAllByOrderId(orderId);
            redelivery = orders.redeliveryToPick(orderId, redeliveryCasesOf(existing));
        }
        Set<UUID> pickedBatches = existing.stream().map(PickJpaEntity::getDeliveryBatchId)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        // 4B-8 (07 BR-07): an order released by delivery batch gets a task per batch - the one released and not
        // picked yet. Nothing new to pick: the latest task answers, as a repeated request always did.
        var batch = redelivery.isPresent() ? java.util.Optional.<com.stockflow.order.api.ReleasedBatch>empty()
                : orders.batchToPick(orderId, pickedBatches);
        if (redelivery.isEmpty() && batch.isEmpty() && !existing.isEmpty()) {
            PickJpaEntity latest = existing.getLast();
            requireWarehouseAssignee(assignedUserId, latest.getWarehouseId());
            TaskAccess.requireVisible(latest, actor);
            return toTask(latest);
        }
        OrderSummary order = requireOrder(orderId);
        // Released - its warehouse chosen: READY_TO_FULFILL (SCRUM-423, kltn-docs 17 BR-02). A later delivery batch is
        // released while the order is PARTIALLY_FULFILLED and picked in that status (17 §4.5: no new order status).
        // A redelivery is picked in that status too: the order owes the customer the goods that came back.
        boolean laterBatch = (batch.isPresent() || redelivery.isPresent())
                && order.status() == OrderStatus.PARTIALLY_FULFILLED;
        if (order.status() != OrderStatus.READY_TO_FULFILL && !laterBatch) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "Only a released order (READY_TO_FULFILL) or its released delivery batch can enter fulfillment; "
                            + "it is " + order.status());
        }
        UUID warehouseId = pickingWarehouse(order);
        WarehouseScope.requireWarehouse(warehouseId);
        requireWarehouseAssignee(assignedUserId, warehouseId);
        PickDestination to = destination == null ? PickDestination.DISPATCH : destination;
        String stagingArea = stagingAreaFor(warehouseId, to, stagingLocationCode);
        // In this transaction, under the order's row lock (A1): READY_TO_FULFILL → PICKING; a later batch leaves the
        // order PARTIALLY_FULFILLED.
        if (!laterBatch) {
            orders.startPicking(orderId, actor.userId());
        }
        var now = clock.instant();
        var pick = new PickJpaEntity(Identifiers.newId(), null, orderId, order.orderNumber(), warehouseId,
                PickStatus.PENDING, to, stagingArea)
                .forDeliveryBatch(redelivery.isPresent() ? redelivery.get().deliveryBatchId()
                        : batch.map(com.stockflow.order.api.ReleasedBatch::batchId).orElse(null))
                .forRedelivery(redelivery.map(com.stockflow.order.api.RedeliveryToPick::caseId).orElse(null));
        pick.assign(assignedUserId, now);
        picks.saveAndFlush(pick);      // the pick row first: its lines' trigger and foreign key look for it
        packs.save(new PackJpaEntity(Identifiers.newId(), pick.getId(), PackStatus.PENDING, null));
        // SCRUM-267: one line per hold of the order's stock lines, in this warehouse (docs 07 step 2).
        pickLists.planLines(pick, order);
        return toTask(pick, nameOf(assignedUserId), null);
    }

    /** The delivery failure cases {@code picks} were opened for: their redeliveries have a task already. */
    private static Set<UUID> redeliveryCasesOf(List<PickJpaEntity> picks) {
        return picks.stream().map(PickJpaEntity::getRedeliveryCaseId).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
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
        // warehouses with oversight, otherwise the caller's own.
        if (actor.can(FulfillmentPermissions.TASK_OVERSIGHT, Action.READ)) {
            // no further filter
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
    public FulfillmentTask changeDestination(UUID taskId, PickDestination destination, String stagingLocationCode,
                                             CurrentUser actor) {
        require(actor, FulfillmentPermissions.TASK_ASSIGNMENT, Action.UPDATE);
        var pick = lockPick(taskId);
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        if (pick.getStatus() != PickStatus.PENDING) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "A task is given another destination only while PENDING; it is " + pick.getStatus());
        }
        PickDestination to = destination == null ? PickDestination.DISPATCH : destination;
        pick.changeDestination(to, stagingAreaFor(pick.getWarehouseId(), to, stagingLocationCode));
        return toTask(picks.save(pick));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Locks: the pick, then the order (read under its lock: a cancellation locks the order before the stock rows it
     * releases), then inventory's - the SKUs, the cartons, the rows - as every write of a task. The same request again,
     * the task already moved, answers the task as it is before the order is looked at.</p>
     */
    @Override
    public FulfillmentTask moveToDispatch(UUID taskId, CurrentUser actor) {
        require(actor, FulfillmentPermissions.TASK_ASSIGNMENT, Action.UPDATE);
        var pick = lockPick(taskId);
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        if (pick.getStatus() == PickStatus.PICKED && pick.getMovedToDispatchAt() != null) {
            return toTask(pick);
        }
        if (pick.getStatus() != PickStatus.STAGED) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT,
                    "A task moves to dispatch from STAGED; it is " + pick.getStatus());
        }
        TaskAccess.requireStillInFulfilment(orders, pick);
        if (orders.lockedStatus(pick.getOrderId()).orElseThrow() == OrderStatus.ON_HOLD) {
            // 07 edge case, an order on hold while its task is STAGED: its cartons stay in the staging area until the
            // hold is resolved.
            throw new BusinessException(ErrorCode.PICK_ORDER_ON_HOLD, "Order %s is on hold; task %s stays staged"
                    .formatted(pick.getOrderNumber(), taskId));
        }
        pickLists.moveStagedLinesToDispatch(pick, actor.userId());
        pick.moveToDispatch(actor.userId(), clock.instant());
        return toTask(picks.save(pick));
    }

    /**
     * The staging area a task of {@code warehouseId} takes its cartons to (07 §3): none for a DISPATCH task, whatever
     * was sent (a form may still carry an earlier choice); for a STAGING task the one named - an active STAGING area of
     * that warehouse - or, none named, the warehouse's only active one.
     *
     * @throws BusinessException {@code STAGING_AREA_INVALID} otherwise
     */
    private String stagingAreaFor(UUID warehouseId, PickDestination destination, String requested) {
        if (destination != PickDestination.STAGING) {
            return null;
        }
        if (requested != null && !requested.isBlank()) {
            return warehouses.findLocation(requested.trim())
                    .filter(view -> view.areaType() == StorageLocationView.AreaType.STAGING)
                    .filter(view -> warehouseId.equals(view.warehouseId()) && view.usable())
                    .map(StorageLocationView::locationCode)
                    .orElseThrow(() -> new BusinessException(ErrorCode.STAGING_AREA_INVALID,
                            "%s is not an active staging area of the task's warehouse".formatted(requested.trim()),
                            List.of(FieldError.of("stagingLocationCode", "must be an active staging area of the "
                                    + "task's warehouse"))));
        }
        List<String> areas = warehouses.areasOf(warehouseId, StorageLocationView.AreaType.STAGING).stream()
                .filter(StorageLocationView::usable).map(StorageLocationView::locationCode).toList();
        if (areas.size() != 1) {
            throw new BusinessException(ErrorCode.STAGING_AREA_INVALID, areas.isEmpty()
                    ? "The warehouse has no active staging area" : "The warehouse has %d active staging areas; name one"
                            .formatted(areas.size()),
                    List.of(FieldError.of("stagingLocationCode", areas.isEmpty()
                            ? "the warehouse has no active staging area" : "name one of " + String.join(", ", areas))));
        }
        return areas.get(0);
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

    /**
     * Docs 07 step 6: only when every line is picked - or staged ({@code PICK_LINES_INCOMPLETE} otherwise). A STAGING
     * task completes STAGED, its cartons waiting in the staging area for the warehouse manager (team plan 4C-5).
     * Completing a task that is already PICKED (or STAGED) by its assignee is answered with the task as it is
     * (SCRUM-482): a scanner retrying a lost response must get the first answer back, not a 409. It is
     * answered before the order is looked at, because the order has moved on since; nothing is applied
     * again. Another user, or any other status, is refused as before.
     */
    @Override
    public FulfillmentTask completePicking(UUID taskId, CurrentUser actor) {
        var pick = lockPick(taskId);
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        pick.requireAssigned(actor.userId());
        if (pick.getStatus() == PickStatus.PICKED || pick.getStatus() == PickStatus.STAGED) {
            return toTask(pick);
        }
        var order = requireOrderInFulfilment(pick);
        if (pick.getStatus() == PickStatus.PICKING) {
            pickLists.requireAllPicked(pick, order);
        }
        pick.complete(actor.userId(), clock.instant());
        return toTask(picks.save(pick));
    }

    @Override
    public FulfillmentTask completePacking(UUID taskId, CurrentUser actor) {
        return completePacking(taskId, PackingCompletion.whole(), actor);
    }

    /**
     * The check station's completion (team plan 4C-6, kltn-docs 08 §4.4): see {@link CartonCheckService#complete}; the
     * answer is the task, with the shipment and the warning of an installment due before delivery still unpaid.
     */
    @Override
    public FulfillmentTask completePacking(UUID taskId, PackingCompletion completion, CurrentUser actor) {
        require(actor, FulfillmentPermissions.PACKING, Action.UPDATE);
        CartonCheckService.Completed outcome = cartonChecks.complete(taskId, completion == null
                ? PackingCompletion.whole() : completion, actor);
        FulfillmentTask task = toTask(requirePick(taskId));
        if (outcome.shipmentId() == null) {
            return task;
        }
        FulfillmentTask shipped = task.withShipment(outcome.shipmentId());
        return outcome.paymentDue() == null ? shipped : shipped.withPaymentDue(new FulfillmentTask.PaymentDue(
                outcome.paymentDue().amount(), outcome.paymentDue().currency()));
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
        return new FulfillmentTask(pick.getId(), pick.getOrderId(), pick.getOrderNumber(), pick.getWarehouseId(),
                pick.getAssignedUserId(), assignedUserName, pick.getStatus().name(),
                (pack == null ? PackStatus.PENDING : pack.getStatus()).name(),
                pick.getAssignedAt(), pick.getStartedAt(), pick.getPickedAt(), pack == null ? null : pack.getPackedAt(),
                lines, null, null, pick.getDestination(), pick.getStagingLocationCode(), pick.getStagedAt(),
                pick.getMovedToDispatchAt());
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
     * one task (stock never changes warehouse, docs 11 BR-01).
     */
    private UUID pickingWarehouse(OrderSummary order) {
        List<OrderHold> holds = inventory.holdsOf(order.orderId());
        Map<UUID, Set<String>> skusByWarehouse = new java.util.LinkedHashMap<>();
        for (OrderHold hold : holds) {
            if (hold.locationCode() == null) {
                // A SKU reservation (SCRUM-152): the release turned it into allocations, which name the bins.
                continue;
            }
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
