package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.label.ShippingLabelSheet;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.fulfillment.api.PackingCompletion;
import com.stockflow.fulfillment.internal.domain.CartonCheck;
import com.stockflow.fulfillment.internal.domain.PackStatus;
import com.stockflow.fulfillment.internal.domain.PackageStatus;
import com.stockflow.fulfillment.internal.domain.PickStatus;
import com.stockflow.fulfillment.internal.domain.Shipment;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.CartonCheckStore;
import com.stockflow.fulfillment.internal.repository.PackJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.inventory.api.CartonSpec;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.order.api.OrderHoldState;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.payment.api.InstallmentStanding;
import com.stockflow.payment.api.PaymentService;
import com.stockflow.warehouse.api.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The carton check and labelling station (team plan 4C-6): see {@link CartonChecks} for the contract and
 * {@link CartonCheck} for the rules. Also completes a session into a shipment ({@link #complete}, for
 * {@code FulfillmentService.completePacking}) and stops it when its order is cancelled ({@link #cancelForOrder}).
 *
 * <p><b>Lock order</b>, as everywhere in fulfillment: the pick first ({@code SELECT … FOR UPDATE}), then the order (its
 * service takes the order's row lock: the first label, the completion). The station changes no stock (08 BR-10): a
 * carton left stock when it was picked to the dispatch area, or when its task moved there from the staging area.
 * Cancelling an order takes the order's lock in its own transaction and reaches the pick only afterwards, in the
 * listener.</p>
 *
 * <p>The repack station of before 4C-6 ({@link PackingService}) shares the session row and the shipments; until team
 * plan 4C-7 removes it, its parcels are not part of a check and a check's cartons are not its parcels.</p>
 */
@Service
@Transactional
class CartonCheckService implements CartonChecks {

    private static final Logger log = LoggerFactory.getLogger(CartonCheckService.class);

    /** The order statuses the station may change cartons in: released, picking, or the rest of a split order. */
    private static final Set<OrderStatus> WORKABLE = EnumSet.of(OrderStatus.READY_TO_FULFILL, OrderStatus.PICKING,
            OrderStatus.PARTIALLY_FULFILLED);
    private static final EnumSet<PackStatus> QUEUED = EnumSet.of(PackStatus.PENDING, PackStatus.IN_PROGRESS);
    /** The most picked-but-unchecked tasks the queue reads at once; far above what one warehouse has waiting. */
    private static final int QUEUE_WORKING_SET = Pages.MAX_PAGE_SIZE * 10;

    private final PickJpaRepository picks;
    private final PackJpaRepository packs;
    private final PackageJpaRepository packages;
    private final ShipmentJpaRepository shipments;
    private final CartonCheckStore store;
    private final OrderService orders;
    private final InventoryControlService itemControls;
    private final PaymentService payments;
    private final IdentityService identities;
    private final WarehouseService warehouses;
    private final ShipmentTimeline timeline;
    private final Clock clock;
    private final int dimDivisor;

    @SuppressWarnings("java:S107")
    CartonCheckService(PickJpaRepository picks, PackJpaRepository packs, PackageJpaRepository packages,
                       ShipmentJpaRepository shipments, CartonCheckStore store, OrderService orders,
                       InventoryControlService itemControls, PaymentService payments, IdentityService identities,
                       WarehouseService warehouses, ShipmentTimeline timeline, Clock clock,
                       @Value("${stockflow.fulfillment.packing.dim-divisor:6000}") int dimDivisor) {
        if (dimDivisor <= 0) {
            throw new IllegalArgumentException("stockflow.fulfillment.packing.dim-divisor must be positive");
        }
        this.picks = picks;
        this.packs = packs;
        this.packages = packages;
        this.shipments = shipments;
        this.store = store;
        this.orders = orders;
        this.itemControls = itemControls;
        this.payments = payments;
        this.identities = identities;
        this.warehouses = warehouses;
        this.timeline = timeline;
        this.clock = clock;
        this.dimDivisor = dimDivisor;
    }

    // ------------------------------------------------------------------ the queue

    @Override
    @Transactional(readOnly = true)
    public PageResponse<QueueRow> queue(UUID warehouseId, String status, int page, int size, CurrentUser actor) {
        if (warehouseId != null) {
            WarehouseScope.requireWarehouse(warehouseId);
        }
        Set<PackStatus> statuses = status == null || status.isBlank() ? QUEUED : EnumSet.of(parse(status));
        var filters = new ArrayList<Specification<PickJpaEntity>>();
        filters.add(WarehouseScope.warehousesIn("warehouseId"));
        filters.add((root, q, cb) -> cb.equal(root.get("status"), PickStatus.PICKED));
        if (warehouseId != null) {
            filters.add((root, q, cb) -> cb.equal(root.get("warehouseId"), warehouseId));
        }
        filters.add((root, q, cb) -> {
            var waiting = q.subquery(UUID.class);
            var pack = waiting.from(PackJpaEntity.class);
            waiting.select(pack.get("pickId")).where(cb.equal(pack.get("pickId"), root.get("id")),
                    pack.get("status").in(statuses));
            return cb.exists(waiting);
        });
        // Oldest pick first: its cartons have waited longest at the dispatch area. Orders live in another module, so
        // the candidates - picked, not completed, in the caller's warehouses: a bounded working set - are read whole,
        // asked about in one call, and paged here; an order on hold or out of reach is left out.
        List<PickJpaEntity> candidates = picks.findAll(Specification.allOf(filters),
                PageRequest.of(0, QUEUE_WORKING_SET, Sort.by(Sort.Direction.ASC, "pickedAt", "id"))).getContent();
        Map<UUID, OrderHoldState> states = orders.holdStates(candidates.stream().map(PickJpaEntity::getOrderId)
                .toList()).stream().collect(Collectors.toMap(OrderHoldState::orderId, Function.identity()));
        List<PickJpaEntity> shown = candidates.stream()
                .filter(pick -> states.get(pick.getOrderId()) != null
                        && WORKABLE.contains(states.get(pick.getOrderId()).status()))
                .toList();
        var pageable = Pages.of(page, size, Sort.by(Sort.Direction.ASC, "pickedAt", "id"));
        int from = (int) Math.min(pageable.getOffset(), shown.size());
        Page<PickJpaEntity> found = new PageImpl<>(shown.subList(from, Math.min(from + pageable.getPageSize(),
                shown.size())), pageable, shown.size());
        List<UUID> pickIds = found.getContent().stream().map(PickJpaEntity::getId).toList();
        Map<UUID, PackJpaEntity> packByPick = pickIds.isEmpty() ? Map.of() : packs.findByPickIdIn(pickIds).stream()
                .collect(Collectors.toMap(PackJpaEntity::getPickId, Function.identity()));
        List<UUID> packIds = packByPick.values().stream().map(PackJpaEntity::getId).toList();
        Map<UUID, List<PackageJpaEntity>> cartonsByPack = packIds.isEmpty() ? Map.of()
                : packages.findByPackIdIn(packIds).stream().filter(PackageJpaEntity::isCarton)
                .filter(row -> row.getStatus() != PackageStatus.REMOVED)
                .collect(Collectors.groupingBy(PackageJpaEntity::getPackId));
        Map<UUID, Long> shipmentsByPack = packIds.isEmpty() ? Map.of() : shipments.findByPackIdIn(packIds).stream()
                .filter(s -> s.getStatus() != ShipmentStatus.CANCELLED)
                .collect(Collectors.groupingBy(ShipmentJpaEntity::getPackId, Collectors.counting()));
        List<UUID> people = new ArrayList<>();
        found.getContent().forEach(pick -> people.add(pick.getAssignedUserId()));
        packByPick.values().forEach(pack -> people.add(pack.getStartedBy()));
        Map<UUID, String> names = identities.displayNames(people.stream().filter(Objects::nonNull).distinct().toList());
        return Pages.toResponse(found, pick -> {
            PackJpaEntity pack = packByPick.get(pick.getId());
            List<PackageJpaEntity> cartons = cartonsByPack.getOrDefault(pack.getId(), List.of());
            return new QueueRow(pick.getId(), pick.getOrderId(), pick.getOrderNumber(), pick.getWarehouseId(),
                    pack.getStatus().name(), pick.getPickedAt(), names.get(pick.getAssignedUserId()),
                    pack.getStartedBy(), names.get(pack.getStartedBy()), pack.getStartedAt(), cartons.size(),
                    (int) cartons.stream().filter(c -> c.getStatus() != PackageStatus.TO_CHECK).count(),
                    (int) cartons.stream().filter(c -> c.getStatus() == PackageStatus.LABELED).count(),
                    shipmentsByPack.getOrDefault(pack.getId(), 0L).intValue());
        });
    }

    // ------------------------------------------------------------------ the session

    @Override
    public Session start(UUID taskId, CurrentUser actor) {
        Context ctx = forWork(taskId);
        requireWorkable(ctx.order());
        CartonCheck check = load(ctx);
        if (check.start(actor.userId(), isManager(actor), clock.instant())) {
            log.info("Check of order {} started by {}", ctx.pick().getOrderNumber(), actor.userId());
        }
        store.save(check, ctx.pack());
        return view(ctx, check);
    }

    @Override
    @Transactional(readOnly = true)
    public Session session(UUID taskId, CurrentUser actor) {
        PickJpaEntity pick = picks.findById(taskId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        Context ctx = new Context(pick, requirePack(pick), requireOrder(pick.getOrderId()));
        // Read only: the cartons the task picked that the session has not received are shown TO_CHECK, not written.
        return view(ctx, load(ctx));
    }

    @Override
    public Session check(UUID taskId, String lpn, CurrentUser actor) {
        Context ctx = forWork(taskId);
        requireWorkable(ctx.order());
        CartonCheck check = load(ctx);
        check.requireWorkable(actor.userId(), isManager(actor));
        CartonCheck.Scan scan = check.check(lpn, actor.userId(), clock.instant());
        store.save(check, ctx.pack());
        if (!scan.repeated()) {
            log.info("Order {}: carton {} checked", ctx.pick().getOrderNumber(), scan.carton().lpn());
        }
        return view(ctx, check);
    }

    @Override
    public Session label(UUID taskId, List<String> lpns, CurrentUser actor) {
        Context ctx = forWork(taskId);
        requireWorkable(ctx.order());
        CartonCheck check = load(ctx);
        check.requireWorkable(actor.userId(), isManager(actor));
        List<CartonCheck.Carton> labeled = check.label(lpns, specs(check.cartons()), dimDivisor, actor.userId(),
                clock.instant());
        if (!labeled.isEmpty()) {
            // 08 BR-04, 17 BR-12, BR-16: the first label locks the order, under its row lock, after the pick's. A
            // cancellation that got the order's lock first is refused here (ORDER_CANCELLED) and nothing is labelled.
            orders.recordFirstLabel(ctx.order().orderId());
        }
        store.save(check, ctx.pack());
        log.info("Order {}: {} carton(s) labelled", ctx.pick().getOrderNumber(), labeled.size());
        return view(ctx, check);
    }

    @Override
    public Session reprint(UUID taskId, String lpn, CurrentUser actor) {
        Context ctx = forWork(taskId);
        CartonCheck check = load(ctx);
        if (check.status() == PackStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.PACK_STATE_CONFLICT, "The check was cancelled with its order");
        }
        if (check.status() == PackStatus.IN_PROGRESS) {
            check.requireWorkable(actor.userId(), isManager(actor));
        } else if (check.status() == PackStatus.PENDING) {
            throw new BusinessException(ErrorCode.PACK_STATE_CONFLICT, "Start the check first");
        }
        // A completed check's label is printed again by any worker of the station: nobody works the session any more.
        CartonCheck.Carton carton = check.carton(lpn).orElse(null);
        CartonCheck.Spec spec = carton == null ? null : specs(List.of(carton)).get(carton.sku());
        CartonCheck.Carton reprinted = check.reprint(lpn, spec, dimDivisor);
        store.save(check, ctx.pack());
        log.info("Order {}: label of carton {} printed again, version {}", ctx.pick().getOrderNumber(),
                reprinted.lpn(), reprinted.labelVersion());
        return view(ctx, check);
    }

    @Override
    public Session remove(UUID taskId, String lpn, String note, CurrentUser actor) {
        Context ctx = forWork(taskId);
        CartonCheck check = load(ctx);
        check.requireWorkable(actor.userId(), isManager(actor));
        CartonCheck.Carton removed = check.remove(lpn, note, actor.userId(), clock.instant());
        store.save(check, ctx.pack());
        log.info("Order {}: carton {} removed as damaged; it waits in the put-back queue for quarantine",
                ctx.pick().getOrderNumber(), removed.lpn());
        return view(ctx, check);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShippingLabelSheet.ShippingLabel> labels(UUID taskId, List<String> lpns, CurrentUser actor) {
        PickJpaEntity pick = picks.findById(taskId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        OrderSummary order = requireOrder(pick.getOrderId());
        CartonCheck check = store.load(requirePack(pick), order.orderId(), order.orderNumber());
        List<CartonCheck.Carton> chosen;
        if (lpns == null || lpns.isEmpty()) {
            chosen = check.cartons().stream()
                    .filter(c -> c.status() == PackageStatus.LABELED && c.shipmentId() == null).toList();
        } else {
            Map<UUID, CartonCheck.Carton> named = new LinkedHashMap<>();
            for (String lpn : lpns) {
                CartonCheck.Carton carton = check.carton(lpn).orElseThrow(() -> new BusinessException(
                        ErrorCode.CARTON_NOT_IN_TASK, "Carton %s is not a carton of order %s"
                        .formatted(lpn, order.orderNumber())));
                if (carton.status() != PackageStatus.LABELED) {
                    throw new BusinessException(ErrorCode.CARTON_NOT_CHECKED, ("Carton %s is %s: only a labelled "
                            + "carton has a shipping label to print").formatted(carton.lpn(), carton.status()));
                }
                named.put(carton.id(), carton);
            }
            chosen = List.copyOf(named.values());
        }
        if (chosen.isEmpty()) {
            throw new BusinessException(ErrorCode.CARTON_NOT_CHECKED,
                    "No labelled carton of order %s waits to ship".formatted(order.orderNumber()));
        }
        var address = order.shippingAddress();
        String recipient = address == null || address.recipientName() == null ? order.contactName()
                : address.recipientName();
        String phone = address == null || address.phone() == null ? order.contactPhone() : address.phone();
        String delivery = address == null ? null : joined(address.line1(), address.line2(), address.wardName(),
                address.provinceName(), address.postalCode(), address.countryCode());
        var warehouse = warehouses.findWarehouse(pick.getWarehouseId());
        return chosen.stream().sorted(java.util.Comparator.comparingInt(CartonCheck.Carton::packageNo))
                .map(c -> new ShippingLabelSheet.ShippingLabel(order.orderNumber(), c.lpn(), c.labelCode(),
                        c.labelVersion(), c.sku(), c.quantity(), c.weightKg(), c.dimWeightKg(), c.lengthCm(),
                        c.widthCm(), c.heightCm(), c.rotatable(), recipient, phone, delivery,
                        warehouse.map(w -> w.name()).orElse(null), warehouse.map(w -> w.address()).orElse(null)))
                .toList();
    }

    // ------------------------------------------------------------------ completing

    /**
     * What a completion did: the shipment made (null when a completed check answered a retry without one), the order
     * after it, and the payment warning (08 §4.4 step 9).
     */
    record Completed(UUID shipmentId, OrderSummary order, PaymentDue paymentDue) { }

    /**
     * See {@code FulfillmentService.completePacking}: checked in the order of 08 §4.4 step 8 ({@link CartonCheck#complete}),
     * then the labelled cartons not shipped yet become one shipment READY_TO_DISPATCH, {@code <order>-S<n>}. In full,
     * the order is PACKED and the session COMPLETED; in part, the order records it (it was locked by its first label
     * anyway) and the session goes on for the cartons picked later. The same request id again answers with the shipment
     * it made. Nothing leaves stock here (08 BR-10).
     */
    public Completed complete(UUID taskId, PackingCompletion request, CurrentUser actor) {
        Context ctx = forWork(taskId);
        if (request.requestId() != null) {
            var replay = shipments.findByRequestId(request.requestId());
            if (replay.isPresent()) {
                if (!ctx.pack().getId().equals(replay.get().getPackId())) {
                    throw new BusinessException(ErrorCode.CONFLICT, "That request id completed another task");
                }
                return new Completed(replay.get().getId(), ctx.order(), paymentDue(ctx.order()));
            }
        }
        CartonCheck check = load(ctx);
        if (check.status() == PackStatus.COMPLETED) {
            // Completed before: answered as it is, with the last shipment it made.
            List<ShipmentJpaEntity> made = shipments.findByPackIdOrderByReadyAtAscIdAsc(ctx.pack().getId());
            return new Completed(made.isEmpty() ? null : made.get(made.size() - 1).getId(), ctx.order(),
                    paymentDue(ctx.order()));
        }
        check.requireWorkable(actor.userId(), isManager(actor));
        requireWorkable(ctx.order());
        Map<UUID, Integer> wanted = new LinkedHashMap<>();
        Map<UUID, String> skus = new HashMap<>();
        Map<UUID, Integer> closed = closedUnits(ctx.order());
        ctx.order().lines().forEach(line -> {
            wanted.put(line.lineId(), Math.max(0, line.quantity() - closed.getOrDefault(line.lineId(), 0)));
            skus.put(line.lineId(), line.sku());
        });
        CartonCheck.Completion completion = check.complete(request.partial(), request.note(),
                ctx.order().lifecycle() != null && ctx.order().lifecycle().allowPartialShipment(), wanted, skus);
        Instant now = clock.instant();
        OrderSummary after;
        if (completion.partial()) {
            // Part of the order: its status follows the handover of what ships (17 §4.5).
            orders.recordPacked(ctx.order().orderId());
            after = requireOrder(ctx.order().orderId());
        } else {
            after = orders.markPacked(ctx.order().orderId(), actor.userId());
        }
        long before = shipments.countByOrderId(ctx.order().orderId());
        Shipment shipment = Shipment.ready(Identifiers.newId(), ctx.order().orderNumber() + "-S" + (before + 1),
                ctx.order().orderId(), ctx.pack().getId(), ctx.pick().getWarehouseId(), request.requestId(),
                completion.cartons().size(), completion.partial() ? request.note().trim() : null, actor.userId(), now);
        shipments.saveAndFlush(ShipmentJpaEntity.of(shipment));
        timeline.record(shipment, null, ctx.order(), null, null, null);
        check.ship(completion, shipment.id(), actor.userId(), now);
        store.save(check, ctx.pack());
        log.info("Order {}: shipment {} ready to dispatch, {} carton(s){}", ctx.pick().getOrderNumber(),
                shipment.code(), shipment.packageCount(), completion.partial() ? ", part of the order" : "");
        return new Completed(shipment.id(), after, paymentDue(after));
    }

    /**
     * The order was cancelled (08 BR-04, 17 §4.6 step 32): every carton not in a shipment is REMOVED - it goes back onto
     * a bin through the put-back queue - and the session is CANCELLED. Called by the cancellation listener under the
     * pick's lock.
     */
    public void cancelForOrder(PickJpaEntity pick, PackJpaEntity pack) {
        CartonCheck check = store.load(pack, pick.getOrderId(), pick.getOrderNumber());
        List<CartonCheck.Carton> removed = check.cancel(clock.instant());
        store.save(check, pack);
        if (removed.stream().anyMatch(c -> c.labelVersion() > 0)) {
            // The order refuses a cancellation once a carton is labelled (08 BR-04): a labelled carton here means it
            // was cancelled around that rule, and its labels are void now. Logged for a person to look at.
            log.error("Order {} cancelled with labelled cartons; their labels are void", pick.getOrderNumber());
        }
        log.info("Check of cancelled order {}: {} carton(s) removed to the put-back queue", pick.getOrderNumber(),
                removed.size());
    }

    // ------------------------------------------------------------------ internals

    private record Context(PickJpaEntity pick, PackJpaEntity pack, OrderSummary order) { }

    /** Locks the pick, checks the warehouse, needs picking done (08 BR-01) and the order still in fulfilment. */
    private Context forWork(UUID taskId) {
        PickJpaEntity pick = picks.lockById(taskId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        if (pick.getStatus() == PickStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT, "The task was cancelled with its order");
        }
        if (pick.getStatus() != PickStatus.PICKED) {
            // A STAGED task's cartons are still in the staging area: they come to the station when the warehouse
            // manager moves the whole task to dispatch (07 BR-11, 08 BR-01).
            throw new BusinessException(ErrorCode.PACKING_NOT_READY, ("Picking is %s; the check starts once every "
                    + "carton is at the dispatch area (08 BR-01)").formatted(pick.getStatus()));
        }
        OrderSummary order = TaskAccess.orderInFulfilment(orders, pick);
        return new Context(pick, requirePack(pick), order);
    }

    /** The session with the cartons the task picked since it was last written received as TO_CHECK. */
    private CartonCheck load(Context ctx) {
        CartonCheck check = store.load(ctx.pack(), ctx.order().orderId(), ctx.order().orderNumber());
        check.receive(store.pickedCartons(ctx.pick().getId()), Identifiers::newId);
        return check;
    }

    private static void requireWorkable(OrderSummary order) {
        if (!WORKABLE.contains(order.status())) {
            String why = order.status() == OrderStatus.ON_HOLD && order.lifecycle() != null
                    && order.lifecycle().holdReason() != null ? " (" + order.lifecycle().holdReason() + ")" : "";
            throw new BusinessException(ErrorCode.PACK_STATE_CONFLICT,
                    "The order is %s%s; its cartons cannot change now".formatted(order.status(), why));
        }
    }

    /**
     * The carton spec of each SKU of these cartons, as the label wants it (08 §4.3 step 5, 01 BR-03). A SKU with no
     * carton unit is missing; one whose unit declares no size or weight is there, not measured.
     */
    private Map<String, CartonCheck.Spec> specs(Collection<CartonCheck.Carton> cartons) {
        Map<String, CartonCheck.Spec> specs = new HashMap<>();
        cartons.stream().map(CartonCheck.Carton::sku).distinct().forEach(sku -> itemControls.cartonSpec(new Sku(sku))
                .ifPresent(spec -> specs.put(sku, toSpec(spec))));
        return specs;
    }

    private static CartonCheck.Spec toSpec(CartonSpec spec) {
        return new CartonCheck.Spec(spec.lengthCm(), spec.widthCm(), spec.heightCm(), spec.weightKg(),
                spec.rotatable());
    }

    private static Map<UUID, Integer> closedUnits(OrderSummary order) {
        Map<UUID, Integer> closed = new HashMap<>();
        if (order.lifecycle() != null) {
            order.lifecycle().lines().forEach(p -> closed.put(p.lineId(), p.cancelledQuantity()));
        }
        return closed;
    }

    private Session view(Context ctx, CartonCheck check) {
        OrderSummary order = ctx.order();
        Map<UUID, Integer> closed = closedUnits(order);
        List<CartonCheck.Carton> cartons = check.cartons();
        List<Line> lines = order.lines().stream().map(line -> {
            List<CartonCheck.Carton> mine = cartons.stream().filter(c -> c.orderLineId().equals(line.lineId()))
                    .toList();
            int wanted = Math.max(0, line.quantity() - closed.getOrDefault(line.lineId(), 0));
            int labeledUnits = check.labeledUnitsOf(line.lineId());
            return new Line(line.lineId(), line.sku(), wanted,
                    (int) mine.stream().filter(c -> c.status() != PackageStatus.REMOVED).count(),
                    (int) mine.stream().filter(c -> c.status() == PackageStatus.CHECKED
                            || c.status() == PackageStatus.LABELED).count(),
                    (int) mine.stream().filter(c -> c.status() == PackageStatus.LABELED).count(),
                    (int) mine.stream().filter(c -> c.status() == PackageStatus.REMOVED).count(),
                    (int) mine.stream().filter(c -> c.shipmentId() != null).count(),
                    labeledUnits, Math.max(0, wanted - labeledUnits));
        }).toList();
        Set<String> unmeasured = new TreeSet<>();
        List<CartonCheck.Carton> toLabel = cartons.stream()
                .filter(c -> c.status() == PackageStatus.TO_CHECK || c.status() == PackageStatus.CHECKED).toList();
        if (!toLabel.isEmpty()) {
            Map<String, CartonCheck.Spec> specs = specs(toLabel);
            toLabel.forEach(c -> {
                CartonCheck.Spec spec = specs.get(c.sku());
                if (spec == null || !spec.measured()) {
                    unmeasured.add(c.sku());
                }
            });
        }
        List<ShipmentSummary> made = shipments.findByPackIdOrderByReadyAtAscIdAsc(ctx.pack().getId()).stream()
                .map(row -> new ShipmentSummary(row.getId(), row.getCode(), row.getStatus().name(),
                        row.getPackageCount(), row.isPartial(), row.getPartialNote(), row.getReadyAt())).toList();
        String startedByName = check.startedBy() == null ? null
                : identities.displayNames(List.of(check.startedBy())).get(check.startedBy());
        boolean locked = cartons.stream().anyMatch(c -> c.labelVersion() > 0)
                || order.lifecycle() != null && order.lifecycle().packedAt() != null;
        return new Session(ctx.pick().getId(), order.orderId(), order.orderNumber(), ctx.pick().getWarehouseId(),
                check.status().name(), check.startedBy(), startedByName, check.startedAt(), check.completedAt(), locked,
                lines, cartons.stream().map(CartonCheckService::cartonView).toList(), made, paymentDue(order),
                List.copyOf(unmeasured));
    }

    private static Carton cartonView(CartonCheck.Carton c) {
        return new Carton(c.stored() ? c.id() : null, c.packageNo(), c.labelCode(), c.lpn(), c.pickLineId(),
                c.orderLineId(), c.sku(), c.lotNumber(), c.quantity(), c.status().name(), c.checkedAt(),
                c.labeledAt(), c.labelVersion(), c.lengthCm(), c.widthCm(), c.heightCm(), c.weightKg(),
                c.dimWeightKg(), c.rotatable(), c.shipmentId(),
                c.removalReason() == null ? null : c.removalReason().name(), c.removalNote());
    }

    /**
     * 08 §4.4 step 9, BR-11 (15 BR-08): what the contract's installments due before delivery still owe; for an order
     * without a contract, what its own terms want before the goods leave. A warning only: the station never blocks on
     * it, the handover does (09).
     */
    private PaymentDue paymentDue(OrderSummary order) {
        InstallmentStanding standing = payments.installmentStanding(order.orderId());
        BigDecimal due = standing.opened() ? standing.beforeDeliveryUnpaid()
                : order.lifecycle() == null ? null : order.lifecycle().amountDueBeforeHandover();
        return due == null || due.signum() <= 0 ? null
                : new PaymentDue(due, order.total().currency().getCurrencyCode());
    }

    /** May work another worker's session: {@code fulfillment-packing:APPROVE} (warehouse manager, 08 BR-06). */
    private static boolean isManager(CurrentUser actor) {
        return actor.can(FulfillmentPermissions.PACKING, Action.APPROVE);
    }

    private static PackStatus parse(String status) {
        try {
            return PackStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER, "Unknown check status",
                    List.of(FieldError.of("status", "must be one of " + java.util.Arrays.toString(
                            PackStatus.values()))));
        }
    }

    private static String joined(String... parts) {
        return java.util.Arrays.stream(parts).filter(p -> p != null && !p.isBlank()).map(String::trim)
                .collect(Collectors.joining(", "));
    }

    private PackJpaEntity requirePack(PickJpaEntity pick) {
        return packs.findByPickId(pick.getId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private OrderSummary requireOrder(UUID orderId) {
        return orders.findById(orderId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
