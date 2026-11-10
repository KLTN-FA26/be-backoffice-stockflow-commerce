package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.fulfillment.api.PackingCompletion;
import com.stockflow.fulfillment.internal.domain.KitPart;
import com.stockflow.fulfillment.internal.domain.PackStatus;
import com.stockflow.fulfillment.internal.domain.PackageStatus;
import com.stockflow.fulfillment.internal.domain.PackageType;
import com.stockflow.fulfillment.internal.domain.PackingSession;
import com.stockflow.fulfillment.internal.domain.PickLineStatus;
import com.stockflow.fulfillment.internal.domain.PickStatus;
import com.stockflow.fulfillment.internal.domain.Shipment;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickLineJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.PackJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackingSessionStore;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickLineJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.inventory.api.HoldView;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.StorageClass;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.warehouse.api.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import com.stockflow.common.storage.PreparedFiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The packing station (G4-5): the queue, the packing session and its parcels, the label, and completing packing into
 * a shipment. See {@link Packing} for the contract and {@link PackingSession} for the rules.
 *
 * <p><b>Lock order</b>, as everywhere in fulfillment: the pick first ({@code SELECT … FOR UPDATE}), then the order (its
 * service takes the order's row lock). Packing changes no stock: picked goods left it when they were picked - a print
 * line's too, since its goods are printed outside the system and received into stock. Cancelling an order takes the
 * order and stock locks in its own transaction and reaches the pick only afterwards, in the listener.</p>
 */
@Service
@Transactional
class PackingService implements Packing {

    private static final Logger log = LoggerFactory.getLogger(PackingService.class);

    /** The order statuses packing may change parcels in: released, picking, or the rest of a split order. */
    private static final Set<OrderStatus> PACKABLE = EnumSet.of(OrderStatus.READY_TO_FULFILL, OrderStatus.PICKING,
            OrderStatus.PARTIALLY_FULFILLED);
    private static final EnumSet<PackStatus> QUEUED = EnumSet.of(PackStatus.PENDING, PackStatus.IN_PROGRESS);
    /** The most picked-but-unpacked tasks the queue reads at once; far above what one warehouse has waiting. */
    private static final int QUEUE_WORKING_SET = Pages.MAX_PAGE_SIZE * 10;

    private final PickJpaRepository picks;
    private final PickLineJpaRepository pickLines;
    private final PackJpaRepository packs;
    private final PackageJpaRepository packages;
    private final ShipmentJpaRepository shipments;
    private final PackingSessionStore store;
    private final com.stockflow.fulfillment.internal.repository.PackageScanJpaRepository scans;
    private final OrderService orders;
    private final InventoryService inventory;
    private final InventoryControlService itemControls;
    private final IdentityService identities;
    private final WarehouseService warehouses;
    private final ShipmentTimeline timeline;
    private final Clock clock;
    private final int dimDivisor;
    private final com.stockflow.common.audit.AuditTrail audit;
    private final LoadPlanReleases loadPlans;
    private final PlanVehicles planVehicles;
    private final PickListService pickLists;
    private final TransactionOperations tx;

    @SuppressWarnings("java:S107")
    PackingService(PickJpaRepository picks, PickLineJpaRepository pickLines, PackJpaRepository packs,
                   PackageJpaRepository packages, ShipmentJpaRepository shipments,
                   PackingSessionStore store,
                   com.stockflow.fulfillment.internal.repository.PackageScanJpaRepository scans,
                   OrderService orders, InventoryService inventory,
                   InventoryControlService itemControls,
                   IdentityService identities, WarehouseService warehouses, ShipmentTimeline timeline, Clock clock,
                   @Value("${stockflow.fulfillment.packing.dim-divisor:6000}") int dimDivisor,
                   com.stockflow.common.audit.AuditTrail audit, LoadPlanReleases loadPlans,
                   PlanVehicles planVehicles, PickListService pickLists, TransactionOperations tx) {
        this.picks = picks;
        this.pickLines = pickLines;
        this.packs = packs;
        this.packages = packages;
        this.shipments = shipments;
        this.store = store;
        this.scans = scans;
        this.orders = orders;
        this.inventory = inventory;
        this.itemControls = itemControls;
        this.identities = identities;
        this.warehouses = warehouses;
        this.timeline = timeline;
        this.clock = clock;
        this.dimDivisor = dimDivisor;
        this.audit = audit;
        this.loadPlans = loadPlans;
        this.planVehicles = planVehicles;
        this.pickLists = pickLists;
        this.tx = tx;
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
        // Oldest pick first: the goods have waited longest at the packing area. The orders' own status decides the
        // rest (spec §5.5): an order on hold, or one packing can no longer work on, is left out. Orders live in another module, so the candidates - picked, not yet packed, in the caller's
        // warehouses: a bounded working set - are read whole, asked about in one call, and paged here.
        List<PickJpaEntity> candidates = picks.findAll(Specification.allOf(filters),
                org.springframework.data.domain.PageRequest.of(0, QUEUE_WORKING_SET,
                        Sort.by(Sort.Direction.ASC, "pickedAt", "id"))).getContent();
        Map<UUID, com.stockflow.order.api.OrderHoldState> states = orders.holdStates(candidates.stream()
                .map(PickJpaEntity::getOrderId).toList()).stream()
                .collect(Collectors.toMap(com.stockflow.order.api.OrderHoldState::orderId, Function.identity()));
        List<PickJpaEntity> shown = candidates.stream().filter(pick -> queueable(states.get(pick.getOrderId())))
                .toList();
        var pageable = Pages.of(page, size, Sort.by(Sort.Direction.ASC, "pickedAt", "id"));
        int from = (int) Math.min(pageable.getOffset(), shown.size());
        Page<PickJpaEntity> found = new org.springframework.data.domain.PageImpl<>(
                shown.subList(from, Math.min(from + pageable.getPageSize(), shown.size())), pageable, shown.size());
        List<UUID> pickIds = found.getContent().stream().map(PickJpaEntity::getId).toList();
        Map<UUID, PackJpaEntity> packByPick = pickIds.isEmpty() ? Map.of() : packs.findByPickIdIn(pickIds).stream()
                .collect(Collectors.toMap(PackJpaEntity::getPickId, Function.identity()));
        List<UUID> packIds = packByPick.values().stream().map(PackJpaEntity::getId).toList();
        Map<UUID, List<PackageJpaEntity>> parcelsByPack = packIds.isEmpty() ? Map.of()
                : packages.findByPackIdIn(packIds).stream().collect(Collectors.groupingBy(PackageJpaEntity::getPackId));
        Map<UUID, Long> shipmentsByPack = packIds.isEmpty() ? Map.of() : shipments.findByPackIdIn(packIds).stream()
                .filter(s -> s.getStatus() != ShipmentStatus.CANCELLED)
                .collect(Collectors.groupingBy(ShipmentJpaEntity::getPackId, Collectors.counting()));
        List<UUID> people = new ArrayList<>();
        found.getContent().forEach(pick -> people.add(pick.getAssignedUserId()));
        packByPick.values().forEach(pack -> people.add(pack.getStartedBy()));
        Map<UUID, String> names = identities.displayNames(people.stream().filter(Objects::nonNull).distinct().toList());
        return Pages.toResponse(found, pick -> {
            PackJpaEntity pack = packByPick.get(pick.getId());
            List<PackageJpaEntity> parcels = parcelsByPack.getOrDefault(pack.getId(), List.of()).stream()
                    .filter(p -> p.getStatus() != PackageStatus.VOIDED).toList();
            return new QueueRow(pick.getId(), pick.getOrderId(), pick.getOrderNumber(), pick.getWarehouseId(),
                    pack.getStatus().name(), pick.getPickedAt(), names.get(pick.getAssignedUserId()),
                    pack.getStartedBy(), names.get(pack.getStartedBy()), pack.getStartedAt(), parcels.size(),
                    (int) parcels.stream().filter(p -> p.getStatus() == PackageStatus.SEALED).count(),
                    shipmentsByPack.getOrDefault(pack.getId(), 0L).intValue());
        });
    }

    // ------------------------------------------------------------------ the session

    @Override
    public Session start(UUID taskId, CurrentUser actor) {
        Context ctx = forWork(taskId);
        requirePackable(ctx.order());
        PackingSession session = load(ctx);
        if (session.start(actor.userId(), isManager(actor), clock.instant())) {
            store.save(session, ctx.pack());
            log.info("Packing of order {} started by {}", ctx.pick().getOrderNumber(), actor.userId());
        }
        return view(ctx, session);
    }

    @Override
    @Transactional(readOnly = true)
    public Session session(UUID taskId, CurrentUser actor) {
        PickJpaEntity pick = picks.findById(taskId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        OrderSummary order = requireOrder(pick.getOrderId());
        Context ctx = new Context(pick, requirePack(pick), order);
        return view(ctx, load(ctx));
    }

    @Override
    public Parcel openPackage(UUID taskId, String type, CurrentUser actor) {
        return openPackage(taskId, type, null, actor);
    }

    @Override
    public Parcel openPackage(UUID taskId, String type, String kitOverrideReason, CurrentUser actor) {
        Context ctx = forWork(taskId);
        requirePackable(ctx.order());
        PackingSession session = load(ctx);
        session.requireWorkable(actor.userId(), isManager(actor));
        PackingSession.Parcel parcel = session.openPackage(Identifiers.newId(), packageType(type), kitOverrideReason,
                clock.instant());
        auditKitOverride(parcel, null, actor);
        // Packing started: the order records it (under the order's row lock, after the pick's, as completion takes
        // them).
        orders.recordPackingStarted(ctx.order().orderId());
        store.save(session, ctx.pack());
        return parcelView(parcel, skus(session));
    }

    @Override
    public Kit openKit(UUID taskId, OpenKit request, CurrentUser actor) {
        Context ctx = forWork(taskId);
        requirePackable(ctx.order());
        PackingSession session = load(ctx);
        session.requireWorkable(actor.userId(), isManager(actor));
        // One id per part; the item's package_count (read with the session's facts) is the kit's size.
        int parts = session.lines().stream().filter(l -> l.orderLineId().equals(request.orderLineId()))
                .mapToInt(PackingSession.Line::packageCount).findFirst().orElse(0);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < parts; i++) {
            ids.add(Identifiers.newId());
        }
        UUID groupId = Identifiers.newId();
        List<PackingSession.Parcel> kit = session.openKit(groupId, ids, request.orderLineId(), request.partCount(),
                packageType(request.packageType()), clock.instant());
        // A kit opens packages as openPackage does: packing started.
        orders.recordPackingStarted(ctx.order().orderId());
        store.save(session, ctx.pack());
        Map<UUID, String> skus = skus(session);
        log.info("Order {}: kit {} of {} parts opened for line {}", ctx.pick().getOrderNumber(), groupId, kit.size(),
                request.orderLineId());
        return new Kit(groupId, request.orderLineId(), skus.get(request.orderLineId()), kit.size(),
                kit.stream().map(parcel -> parcelView(parcel, skus)).toList());
    }

    @Override
    public Session scan(UUID taskId, UUID packageId, Scan scan, CurrentUser actor) {
        Context ctx = forWork(taskId);
        if (isReplay(ctx, packageId, scan.scanId())) {
            return view(ctx, load(ctx));
        }
        requirePackable(ctx.order());
        PackingSession session = load(ctx);
        session.requireWorkable(actor.userId(), isManager(actor));
        String before = session.parcel(packageId).map(PackingSession.Parcel::kitOverrideReason).orElse(null);
        if (session.scan(scan.scanId(), packageId, scan.scannedCode(), scan.lotNumber(), scan.serialNumber(),
                scan.quantity(), scan.orderLineId(), scan.kitOverrideReason(), actor.userId(), clock.instant())) {
            store.save(session, ctx.pack());
            session.parcel(packageId).ifPresent(parcel -> auditKitOverride(parcel, before, actor));
        }
        return view(ctx, session);
    }

    @Override
    public Session removeUnits(UUID taskId, UUID packageId, UUID orderLineId, String lotNumber, String serialNumber,
                               int quantity, UUID scanId, CurrentUser actor) {
        Context ctx = forWork(taskId);
        UUID id = scanId == null ? Identifiers.newId() : scanId;
        if (isReplay(ctx, packageId, id)) {
            return view(ctx, load(ctx));
        }
        requirePackable(ctx.order());
        PackingSession session = load(ctx);
        session.requireWorkable(actor.userId(), isManager(actor));
        session.remove(id, packageId, orderLineId, lotNumber, serialNumber, quantity, actor.userId(), clock.instant());
        store.save(session, ctx.pack());
        return view(ctx, session);
    }

    /**
     * A scan id is answered before anything else is checked (a retry after the package was sealed, or packing
     * completed, gets the answer it would have got): the same id on the same package is a replay; on another package or
     * another task it is a client error, refused before the database's primary key would be.
     */
    private boolean isReplay(Context ctx, UUID packageId, UUID scanId) {
        if (scanId == null) {
            return false;
        }
        return scans.findById(scanId).map(recorded -> {
            boolean ours = recorded.getPackageId().equals(packageId) && packages.findById(packageId)
                    .map(parcel -> parcel.getPackId().equals(ctx.pack().getId())).orElse(false);
            if (!ours) {
                throw new BusinessException(ErrorCode.PACK_SCAN_ID_CONFLICT,
                        "Scan %s was already recorded for another package; send a new scanId".formatted(scanId));
            }
            return true;
        }).orElse(false);
    }

    @Override
    public Parcel seal(UUID taskId, UUID packageId, Seal seal, CurrentUser actor) {
        Context ctx = forWork(taskId);
        requirePackable(ctx.order());
        PackingSession session = load(ctx);
        session.requireWorkable(actor.userId(), isManager(actor));
        String before = session.parcel(packageId).map(PackingSession.Parcel::kitOverrideReason).orElse(null);
        PackingSession.Parcel parcel = session.seal(packageId, new PackingSession.Measures(seal.weightKg(),
                        seal.lengthCm(), seal.widthCm(), seal.heightCm()), seal.rotatable(), seal.fragile(),
                seal.packageType() == null ? null : packageType(seal.packageType()), seal.kitOverrideReason(),
                dimDivisor, actor.userId(), clock.instant());
        store.save(session, ctx.pack());
        auditKitOverride(parcel, before, actor);
        return parcelView(parcel, skus(session));
    }

    @Override
    public Parcel reopen(UUID taskId, UUID packageId, CurrentUser actor) {
        Context ctx = forWork(taskId);
        requireNotPlanned(ctx, packageId);
        requirePackable(ctx.order());
        PackingSession session = load(ctx);
        session.requireWorkable(actor.userId(), isManager(actor));
        PackingSession.Parcel parcel = session.reopen(packageId);
        store.save(session, ctx.pack());
        return parcelView(parcel, skus(session));
    }

    @Override
    public Session voidPackage(UUID taskId, UUID packageId, CurrentUser actor) {
        Context ctx = forWork(taskId);
        requireNotPlanned(ctx, packageId);
        requirePackable(ctx.order());
        PackingSession session = load(ctx);
        session.requireWorkable(actor.userId(), isManager(actor));
        session.voidPackage(packageId, actor.userId(), clock.instant());
        store.save(session, ctx.pack());
        return view(ctx, session);
    }

    /**
     * The label as it prints now. Nothing is collected on delivery (09 BR-08): the label carries no amount. Printed
     * again, a label outdated by a shipment split is current again.
     */
    @Override
    public Label label(UUID taskId, UUID packageId, CurrentUser actor) {
        PickJpaEntity pick = picks.findById(taskId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        OrderSummary order = requireOrder(pick.getOrderId());
        Context ctx = new Context(pick, requirePack(pick), order);
        PackingSession session = load(ctx);
        PackingSession.Parcel parcel = session.parcel(packageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PACKAGE_NOT_FOUND));
        if (parcel.status() != PackageStatus.SEALED) {
            throw new BusinessException(ErrorCode.PACK_STATE_CONFLICT, parcel.status() == PackageStatus.OPEN
                    ? "Seal package %d first: the label carries its weight and size".formatted(parcel.packageNo())
                    : "Package %d was voided; its label is void (08 BR-05)".formatted(parcel.packageNo()));
        }
        List<PackingSession.Parcel> together;
        ShipmentJpaEntity shipment = null;
        if (parcel.shipmentId() != null) {
            shipment = shipments.findById(parcel.shipmentId()).orElseThrow();
            UUID shipmentId = parcel.shipmentId();
            together = session.parcels().stream().filter(p -> shipmentId.equals(p.shipmentId())).toList();
        } else {
            together = session.parcels().stream()
                    .filter(p -> p.status() == PackageStatus.SEALED && p.shipmentId() == null).toList();
        }
        // SCRUM-392: printed again, a label outdated by a shipment split is current again.
        packages.findById(packageId).filter(p -> p.getLabelOutdatedAt() != null).ifPresent(p -> {
            p.markLabelOutdated(null);
            packages.save(p);
        });
        var address = order.shippingAddress();
        Address recipient = address == null ? new Address(order.contactName(), order.contactPhone(), null, null, null,
                null, null, null) : new Address(address.recipientName() == null ? order.contactName()
                : address.recipientName(), address.phone() == null ? order.contactPhone() : address.phone(),
                address.line1(), address.line2(), address.wardName(), address.provinceName(), address.countryCode(),
                address.postalCode());
        var warehouse = warehouses.findWarehouse(pick.getWarehouseId());
        var m = parcel.measures();
        Map<UUID, String> skus = skus(session);
        return new Label(parcel.labelCode(), parcel.id(), order.orderNumber(), shipment == null ? null : shipment.getId(),
                shipment == null ? null : shipment.getCode(), together.indexOf(parcel) + 1, together.size(),
                shipment == null, recipient,
                warehouse.map(w -> w.name()).orElse(null), warehouse.map(w -> w.address()).orElse(null),
                parcel.type().name(), m.weightKg(), m.lengthCm(), m.widthCm(), m.heightCm(), parcel.dimWeightKg(),
                parcel.fragile(), parcel.rotatable(), contents(parcel, skus), parcel.kit());
    }

    // ------------------------------------------------------------------ completing

    /** What a completion did: the shipment made, or null when nothing was made (a retried completion). */
    record Completed(UUID shipmentId, OrderSummary order) { }

    /**
     * See {@code FulfillmentService.completePacking}. The pick is locked first; the order's row lock is taken by its
     * service. Nothing leaves stock here: every line's goods left when they were picked.
     */
    public Completed complete(UUID taskId, PackingCompletion request, CurrentUser actor) {
        Context ctx = forWork(taskId);
        if (request.requestId() != null) {
            var replay = shipments.findByRequestId(request.requestId());
            if (replay.isPresent()) {
                if (!ctx.pack().getId().equals(replay.get().getPackId())) {
                    throw new BusinessException(ErrorCode.CONFLICT, "That request id completed another task");
                }
                return new Completed(replay.get().getId(), ctx.order());
            }
        }
        if (ctx.pack().getStatus() == PackStatus.COMPLETED) {
            // Completed before: answered as it is (a retried completion), with the last shipment it made.
            List<ShipmentJpaEntity> made = shipments.findByPackIdOrderByReadyAtAscIdAsc(ctx.pack().getId());
            return new Completed(made.isEmpty() ? null : made.get(made.size() - 1).getId(), ctx.order());
        }
        PackingSession session = load(ctx);
        session.requireWorkable(actor.userId(), isManager(actor));
        requirePackable(ctx.order());
        Instant now = clock.instant();
        // PACK-PARTIAL (lead's decision 2026-10-21): Sales decides on the order whether part of it may ship.
        PackingSession.Completion completion = session.complete(request.partial(), request.note(),
                allowsPartialShipment(ctx.order()), actor.userId(), now);
        OrderSummary after;
        if (completion.partial()) {
            // Part of the order: it is no longer cancelled (decision 2026-10-17); its status follows the handover.
            orders.recordPacked(ctx.order().orderId());
            after = requireOrder(ctx.order().orderId());
        } else {
            after = orders.markPacked(ctx.order().orderId(), actor.userId());
        }
        long before = shipments.countByOrderId(ctx.order().orderId());
        Shipment shipment = Shipment.ready(Identifiers.newId(), ctx.order().orderNumber() + "-S" + (before + 1),
                ctx.order().orderId(), ctx.pack().getId(), ctx.pick().getWarehouseId(), request.requestId(),
                completion.parcels().size(), completion.partial() ? request.note().trim() : null, actor.userId(), now);
        shipments.saveAndFlush(ShipmentJpaEntity.of(shipment));
        timeline.record(shipment, null, ctx.order(), null, null, null);
        session.ship(completion, shipment.id(), actor.userId(), now);
        store.save(session, ctx.pack());
        log.info("Order {}: shipment {} ready to dispatch, {} package(s){}", ctx.pick().getOrderNumber(),
                shipment.code(), shipment.packageCount(), completion.partial() ? ", part of the order" : "");
        return new Completed(shipment.id(), after);
    }

    /**
     * SCRUM-287 PR D (09 BR-09): a parcel a load plan holds - or any part of its kit, which is voided whole - is not
     * voided or re-opened: 409 {@code PACKAGE_IN_LOAD_PLAN}, before the station's own "already shipped" refusal.
     */
    private void requireNotPlanned(Context ctx, UUID packageId) {
        PackageJpaEntity parcel = packages.findById(packageId).orElse(null);
        if (parcel == null) {
            return;
        }
        java.util.List<UUID> ids = new java.util.ArrayList<>(List.of(packageId));
        if (parcel.getKit() != null) {
            packages.findByPackIdOrderByPackageNoAsc(ctx.pack().getId()).stream()
                    .filter(p -> p.getKit() != null && p.getKit().groupId().equals(parcel.getKit().groupId()))
                    .forEach(p -> ids.add(p.getId()));
        }
        loadPlans.requireNotPlanned(ids);
    }

    /**
     * The order was cancelled (08 BR-05, 17 §4.5 step 15): parcels that did not ship are voided with their labels, a
     * shipment still waiting for dispatch is cancelled. The goods in them were picked: the pick's put-back covers them.
     */
    public void cancelForOrder(PickJpaEntity pick, PackJpaEntity pack) {
        Instant now = clock.instant();
        for (ShipmentJpaEntity row : shipments.findByPackIdOrderByReadyAtAscIdAsc(pack.getId())) {
            Shipment shipment = row.toDomain();
            if (shipment.status() == ShipmentStatus.READY_TO_DISPATCH) {
                if (planVehicles.partlyGone(row.getId())) {
                    // SCRUM-392 (LP-3): a vehicle of its load plan already left with some of its parcels - they are
                    // with the carrier and cannot be voided. The order refuses its cancellation once goods left
                    // (goodsLeftAt, review of #162), so this is only a net: left as it is for a person to resolve.
                    log.error("Order {} cancelled, but shipment {} already left in part on a load plan's vehicle: "
                            + "not voided - resolve it by hand", pick.getOrderNumber(), row.getCode());
                    continue;
                }
                // SCRUM-287 PR D: out of its load plan first (plan lock before any load's), then off its loads -
                // a vehicle being loaded keeps the parcels on board to unload (SCRUM-392).
                loadPlans.releaseShipment(row.getId(), now);
                planVehicles.withdraw(row.getId(), now);
                shipment.cancel("The order was cancelled", null, now);
                row.apply(shipment);
                shipments.save(row);
                timeline.record(shipment, ShipmentStatus.READY_TO_DISPATCH, null, pick.getOrderNumber(), null, null);
                // A shipping carton of the check station (4C-6) is the check's to remove, never voided here.
                packages.findByShipmentIdOrderByPackageNoAsc(row.getId()).stream().filter(p -> !p.isCarton())
                        .forEach(parcel -> {
                    parcel.apply(parcel.getShipmentId(), parcel.getPackageType(), PackageStatus.VOIDED, parcel.getWeightKg(),
                            parcel.getLengthCm(), parcel.getWidthCm(), parcel.getHeightCm(), parcel.getDimWeightKg(),
                            parcel.isRotatable(), parcel.isRotatableOverridden(), parcel.isFragile(),
                            parcel.isFragileOverridden(), parcel.getSealedAt(), parcel.getSealedBy(), now, null);
                    packages.save(parcel);
                });
            }
        }
        for (PackageJpaEntity parcel : packages.findByPackIdOrderByPackageNoAsc(pack.getId())) {
            if (!parcel.isCarton() && parcel.getStatus() != PackageStatus.VOIDED && parcel.getShipmentId() == null) {
                parcel.apply(null, parcel.getPackageType(), PackageStatus.VOIDED, parcel.getWeightKg(),
                        parcel.getLengthCm(), parcel.getWidthCm(), parcel.getHeightCm(), parcel.getDimWeightKg(),
                        parcel.isRotatable(), parcel.isRotatableOverridden(), parcel.isFragile(),
                        parcel.isFragileOverridden(), parcel.getSealedAt(), parcel.getSealedBy(), now, null);
                packages.save(parcel);
            }
        }
        log.info("Packing of cancelled order {}: open parcels voided", pick.getOrderNumber());
    }

    // ------------------------------------------------------------------ damage found at packing (R8)

    /**
     * {@inheritDoc}
     *
     * <p>The same report sent again is answered from what it made before anything else is checked - the task is back
     * at picking by then. Damage is a fact about goods, so it is taken whenever picked units are off the shelf: in a
     * tote while the task is still PICKING (or picking again after an earlier report), or at the bench once it is
     * PICKED - whatever hold the order is on, as long as it is not cancelled (a cancelled order's goods are put back,
     * then reported on the shelf). The packing session has its say only when there is one: while it is being packed,
     * only its packer or a manager reports; units in a parcel come out first.</p>
     *
     * <p>Not transactional itself. Every check runs first in a short read, so a refused or repeated request reads no
     * photo; the photos are then checked, scanned and stored with no transaction held, so the pick is never locked
     * while one is read. Then one transaction locks the pick, checks everything again, and the pick list does the rest
     * under that lock, adopting the photos.</p>
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PickLists.PickDamageView reportDamageAfterPick(UUID taskId, UUID lineId, PackDamage damage,
                                                          com.stockflow.common.storage.IncomingFiles photos,
                                                          CurrentUser actor) {
        java.util.function.Supplier<Optional<PickLists.PickDamageView>> unlocked = () -> tx.execute(status ->
                damageAfterPickChecked(picks.findById(taskId).orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "No fulfilment task " + taskId)), lineId, damage, actor));
        Optional<PickLists.PickDamageView> answered = unlocked.get();
        if (answered.isPresent()) {
            return answered.get();
        }
        PreparedFiles prepared;
        try {
            prepared = pickLists.prepareDamagePhotos(photos);
        } catch (BusinessException refused) {
            // The same report sent again while the first was attaching these browser uploads: they are its, so the
            // answer is what it made - or, still in progress, the 409 to try again.
            if (refused.errorCode() != ErrorCode.CONFLICT) {
                throw refused;
            }
            return unlocked.get().orElseThrow(() -> refused);
        }
        try (prepared) {
            return tx.execute(status -> {
                PickJpaEntity pick = lockPick(taskId);
                // Made meanwhile by the same request: answered with it, and these photos go when they are closed.
                return damageAfterPickChecked(pick, lineId, damage, actor)
                        .orElseGet(() -> pickLists.damageAfterPick(pick, lineId, damage, prepared, actor));
            });
        }
    }

    /**
     * What the same report sent again made, or empty when this one may be made: the warehouse, the task's state, the
     * order, the line and the packing session all allow it. Run unlocked first and again under the pick's lock.
     */
    private Optional<PickLists.PickDamageView> damageAfterPickChecked(PickJpaEntity pick, UUID lineId,
                                                                      PackDamage damage, CurrentUser actor) {
        UUID taskId = pick.getId();
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        Optional<PickLists.PickDamageView> replay = pickLists.damageAfterPickReplay(pick, lineId,
                damage.requestId());
        if (replay.isPresent()) {
            return replay;
        }
        if (pick.getStatus() != PickStatus.PICKING && pick.getStatus() != PickStatus.PICKED) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT, pick.getStatus() == PickStatus.CANCELLED
                    ? "The task was cancelled with its order: put the goods back, then report them on the shelf"
                    : "Nothing was picked yet; damage on the shelf is reported at the line's bin");
        }
        OrderSummary order = TaskAccess.orderInFulfilment(orders, pick);
        PickLineJpaEntity line = pickLines.findById(lineId).filter(row -> row.getPickId().equals(taskId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "Task %s has no pick line %s".formatted(taskId, lineId)));
        pickLists.requireDamageableAfterPick(pick, lineId, damage.damagedQuantity());
        Optional<PackJpaEntity> pack = packs.findByPickId(pick.getId());
        if (pack.isPresent()) {
            PackingSession session = load(new Context(pick, pack.get(), order));
            if (session.status() == PackStatus.IN_PROGRESS) {
                session.requireWorkable(actor.userId(), isManager(actor));
            }
            String serial = line.getReservationId() == null ? null
                    : inventory.holds(List.of(line.getReservationId())).stream().findFirst()
                            .map(HoldView::serialNumber).orElse(null);
            session.requireUnpacked(line.getOrderLineId(), line.getLotNumber(), serial, damage.damagedQuantity());
        }
        return Optional.empty();
    }

    @Override
    @Transactional(readOnly = true)
    public com.stockflow.common.storage.UploadSlot beginDamagePhotoUpload(UUID taskId,
            com.stockflow.common.storage.UploadSlotRequest request, CurrentUser actor) {
        PickJpaEntity pick = picks.findById(taskId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "No fulfilment task " + taskId));
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        return pickLists.damagePhotoSlot(actor.userId(), request);
    }

    // ------------------------------------------------------------------ internals

    private record Context(PickJpaEntity pick, PackJpaEntity pack, OrderSummary order) { }

    /** Locks the pick, checks the warehouse, needs picking done and the order still in fulfilment. */
    private Context forWork(UUID taskId) {
        PickJpaEntity pick = lockPick(taskId);
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        if (pick.getStatus() == PickStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT, "The task was cancelled with its order");
        }
        if (pick.getStatus() != PickStatus.PICKED) {
            throw new BusinessException(ErrorCode.PACKING_NOT_READY,
                    "Picking is %s; packing starts once it is complete (08 step 1)".formatted(pick.getStatus()));
        }
        OrderSummary order = TaskAccess.orderInFulfilment(orders, pick);
        return new Context(pick, requirePack(pick), order);
    }

    /** Shown in the queue: an order packing may work on. */
    private static boolean queueable(com.stockflow.order.api.OrderHoldState state) {
        return state != null && PACKABLE.contains(state.status());
    }

    private static boolean allowsPartialShipment(OrderSummary order) {
        return order.lifecycle() != null && order.lifecycle().allowPartialShipment();
    }

    private static void requirePackable(OrderSummary order) {
        if (!PACKABLE.contains(order.status())) {
            String why = order.status() == OrderStatus.ON_HOLD && order.lifecycle() != null
                    && order.lifecycle().holdReason() != null ? " (" + order.lifecycle().holdReason() + ")" : "";
            throw new BusinessException(ErrorCode.PACK_STATE_CONFLICT,
                    "The order is %s%s; its parcels cannot change now".formatted(order.status(), why));
        }
    }

    private PackingSession load(Context ctx) {
        return store.load(ctx.pack(), ctx.pick().getOrderNumber(), facts(ctx.pick(), ctx.order()));
    }

    /**
     * The order's lines as the packing table sees them: what the order still wants (its quantity less units closed),
     * what reached packing (what was picked and not put back), the lots and serials of those goods, and the SKU's
     * barcode, rotatable flag and storage class.
     *
     * <p>Legacy: a print line of an order released while in-house production existed was never picked - production
     * consumed its hold when it delivered the printed goods to packing (G4-Q2). Those units, a CONSUMED hold of the line
     * that no pick of this task took, count as having reached packing too, so such an order still ships. Nothing makes
     * a hold like that any more.</p>
     */
    private List<PackingSession.Line> facts(PickJpaEntity pick, OrderSummary order) {
        Map<UUID, Integer> closed = new HashMap<>();
        if (order.lifecycle() != null) {
            order.lifecycle().lines().forEach(p -> closed.put(p.lineId(), p.cancelledQuantity()));
        }
        List<PickLineJpaEntity> taskLines = pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId());
        Map<UUID, List<PickLineJpaEntity>> picked = taskLines.stream()
                .filter(line -> line.getStatus() == PickLineStatus.PICKED)
                .collect(Collectors.groupingBy(PickLineJpaEntity::getOrderLineId));
        Set<UUID> takenByPicks = taskLines.stream().map(PickLineJpaEntity::getReservationId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<UUID> holdIds = new ArrayList<>();
        picked.values().forEach(lines -> lines.forEach(line -> {
            if (line.getReservationId() != null) {
                holdIds.add(line.getReservationId());
            }
        }));
        order.lines().stream().filter(line -> line.designSnapshotId() != null)
                .flatMap(line -> line.reservationIds().stream())
                .filter(id -> !takenByPicks.contains(id))
                .forEach(holdIds::add);
        Map<UUID, HoldView> holds = holdIds.isEmpty() ? Map.of() : inventory.holds(holdIds).stream()
                .collect(Collectors.toMap(HoldView::reservationId, Function.identity()));
        Map<String, ItemLogistics> logistics = new HashMap<>();
        List<PackingSession.Line> lines = new ArrayList<>();
        for (OrderSummary.LineSummary line : order.lines()) {
            ItemLogistics item = logistics.computeIfAbsent(line.sku(),
                    sku -> itemControls.logistics(new Sku(sku)).logistics());
            int wanted = line.quantity() - closed.getOrDefault(line.lineId(), 0);
            boolean print = line.designSnapshotId() != null;
            Map<String, Integer> lots = new TreeMap<>();
            java.util.Set<String> serials = new java.util.TreeSet<>();
            // What was picked, less what was found damaged here since (R8): that went back to quarantine. A print line
            // is picked like any other: its goods were printed outside the system and received into stock.
            List<PickLineJpaEntity> rows = picked.getOrDefault(line.lineId(), List.of()).stream()
                    .filter(row -> row.getGoodQty() > 0).toList();
            int available = rows.stream().mapToInt(PickLineJpaEntity::getGoodQty).sum();
            for (PickLineJpaEntity row : rows) {
                HoldView hold = row.getReservationId() == null ? null : holds.get(row.getReservationId());
                collect(lots, serials, row.getLotNumber(), hold == null ? null : hold.serialNumber(),
                        row.getGoodQty());
            }
            if (print) {
                // Legacy (see the method's javadoc): printed goods in-house production delivered to packing.
                for (UUID id : line.reservationIds()) {
                    HoldView hold = takenByPicks.contains(id) ? null : holds.get(id);
                    if (hold != null && hold.status() == HoldView.Status.CONSUMED) {
                        available += hold.quantity();
                        collect(lots, serials, hold.lotNumber(), hold.serialNumber(), hold.quantity());
                    }
                }
            }
            if (lots.keySet().equals(Set.of(PackingSession.NO_LOT))) {
                lots.clear();     // none of them has a lot: the line is not tracked by lot
            }
            lines.add(new PackingSession.Line(line.lineId(), line.sku(), item.barcode(), wanted, available, print,
                    line.designSnapshotId(), lots, serials, item.rotatable(),
                    item.storageClass() == StorageClass.FRAGILE, item.packageCount()));
        }
        return lines;
    }

    private static void collect(Map<String, Integer> lots, Set<String> serials, String lot, String serial,
                                int quantity) {
        lots.merge(lot == null || lot.isBlank() ? PackingSession.NO_LOT : lot, quantity, Integer::sum);
        if (serial != null && !serial.isBlank()) {
            serials.add(serial);
        }
    }

    private Session view(Context ctx, PackingSession session) {
        Map<UUID, String> skus = skus(session);
        List<Line> lines = session.lines().stream().map(line -> {
            int packed = session.packedOf(line.orderLineId());
            List<Lot> lots = line.lots().entrySet().stream().map(e -> {
                String lot = PackingSession.NO_LOT.equals(e.getKey()) ? null : e.getKey();
                return new Lot(lot, e.getValue(), session.parcels().stream()
                        .filter(p -> p.status() != PackageStatus.VOIDED)
                        .flatMap(p -> p.contents().entrySet().stream())
                        .filter(c -> c.getKey().orderLineId().equals(line.orderLineId())
                                && Objects.equals(lot, c.getKey().lotNumber()))
                        .mapToInt(Map.Entry::getValue).sum());
            }).toList();
            return new Line(line.orderLineId(), line.sku(), line.barcode(), line.printLine(), line.designSnapshotId(),
                    line.wanted(), line.available(), packed, session.shippedOf(line.orderLineId()),
                    Math.max(0, line.wanted() - packed), line.lots().keySet().stream()
                    .anyMatch(lot -> !PackingSession.NO_LOT.equals(lot)), !line.serials().isEmpty(), lots,
                    line.packageCount(), line.packageCount() >= KitPart.MIN_PARTS);
        }).toList();
        List<Parcel> parcels = session.parcels().stream().map(parcel -> parcelView(parcel, skus)).toList();
        List<ShipmentSummary> made = shipments.findByPackIdOrderByReadyAtAscIdAsc(ctx.pack().getId()).stream()
                .map(row -> new ShipmentSummary(row.getId(), row.getCode(), row.getStatus().name(),
                        row.getPackageCount(), row.isPartial(), row.getPartialNote(), row.getReadyAt())).toList();
        String packerName = session.startedBy() == null ? null
                : identities.displayNames(List.of(session.startedBy())).get(session.startedBy());
        return new Session(ctx.pick().getId(), ctx.order().orderId(), ctx.order().orderNumber(),
                ctx.pick().getWarehouseId(), session.status().name(), session.startedBy(), packerName,
                session.startedAt(), session.packedAt(), lines, parcels, made, paymentDue(ctx.order()));
    }

    /** G3-Q4, G2-3: money the order's terms want before its goods leave - the handover will be refused until paid. */
    private static PaymentDue paymentDue(OrderSummary order) {
        BigDecimal due = order.lifecycle() == null ? null : order.lifecycle().amountDueBeforeHandover();
        return due == null || due.signum() <= 0 ? null
                : new PaymentDue(due, order.total().currency().getCurrencyCode());
    }

    private static Parcel parcelView(PackingSession.Parcel parcel, Map<UUID, String> skus) {
        var m = parcel.measures();
        return new Parcel(parcel.id(), parcel.packageNo(), parcel.labelCode(), parcel.type().name(),
                parcel.status().name(), parcel.shipmentId(), m == null ? null : m.weightKg(),
                m == null ? null : m.lengthCm(), m == null ? null : m.widthCm(), m == null ? null : m.heightCm(),
                parcel.dimWeightKg(), parcel.rotatable(), parcel.rotatableOverridden(), parcel.fragile(),
                parcel.fragileOverridden(), parcel.sealedAt(), contents(parcel, skus), parcel.kit(),
                parcel.kitOverrideReason(), parcel.labelOutdatedAt());
    }

    private static List<Content> contents(PackingSession.Parcel parcel, Map<UUID, String> skus) {
        return parcel.contents().entrySet().stream().map(e -> new Content(e.getKey().orderLineId(),
                skus.get(e.getKey().orderLineId()), e.getKey().lotNumber(), e.getKey().serialNumber(),
                e.getValue())).toList();
    }

    private static Map<UUID, String> skus(PackingSession session) {
        return session.lines().stream().collect(Collectors.toMap(PackingSession.Line::orderLineId,
                PackingSession.Line::sku));
    }

    /**
     * KIT-FORCE: a reason given for packing an item that ships as several boxes in an ordinary parcel goes in the audit
     * trail, with the parcel and its label, when it is new or changed. The reason is the packer's own text: kept as
     * the details, never in a log line.
     */
    private void auditKitOverride(PackingSession.Parcel parcel, String before, CurrentUser actor) {
        String reason = parcel.kitOverrideReason();
        if (reason == null || reason.equals(before)) {
            return;
        }
        var entry = new com.stockflow.common.audit.AuditEntry(actor.userId(), actor.username(),
                com.stockflow.common.audit.AuditAction.UPDATE, "fulfillment-package", parcel.id().toString(),
                com.stockflow.common.audit.AuditEntry.Outcome.SUCCESS,
                org.slf4j.MDC.get(com.stockflow.common.web.CorrelationIdFilter.MDC_KEY), null,
                "{\"kitOverrideReason\":%s,\"labelCode\":%s}".formatted(json(reason), json(parcel.labelCode())),
                clock.instant());
        // Once the reason is stored: a write that rolls back leaves no entry for a reason that never was.
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        audit.record(entry);
                    }
                });
    }

    private static String json(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8).append('"');
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    /** May work, or take over, a session another packer started: {@code fulfillment-packing:APPROVE}. */
    private static boolean isManager(CurrentUser actor) {
        return actor.can(FulfillmentPermissions.PACKING, Action.APPROVE);
    }

    private static PackageType packageType(String type) {
        if (type == null || type.isBlank()) {
            return PackageType.CARTON;
        }
        try {
            return PackageType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Unknown package type",
                    List.of(FieldError.of("packageType", "must be one of " + java.util.Arrays.toString(
                            PackageType.values()))));
        }
    }

    private static PackStatus parse(String status) {
        try {
            return PackStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER, "Unknown packing status",
                    List.of(FieldError.of("status", "must be one of " + java.util.Arrays.toString(
                            PackStatus.values()))));
        }
    }

    private PickJpaEntity lockPick(UUID taskId) {
        return picks.lockById(taskId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private PackJpaEntity requirePack(PickJpaEntity pick) {
        return packs.findByPickId(pick.getId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private OrderSummary requireOrder(UUID orderId) {
        return orders.findById(orderId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
