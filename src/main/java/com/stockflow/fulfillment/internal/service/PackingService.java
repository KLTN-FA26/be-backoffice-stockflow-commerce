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
import com.stockflow.design.api.DesignService;
import com.stockflow.fulfillment.api.PackingCompletion;
import com.stockflow.fulfillment.internal.domain.DeliveryMode;
import com.stockflow.fulfillment.internal.domain.DesignVerificationResult;
import com.stockflow.fulfillment.internal.domain.KitPart;
import com.stockflow.fulfillment.internal.domain.PackStatus;
import com.stockflow.fulfillment.internal.domain.PackageStatus;
import com.stockflow.fulfillment.internal.domain.PackageType;
import com.stockflow.fulfillment.internal.domain.PackingSession;
import com.stockflow.fulfillment.internal.domain.PickLineStatus;
import com.stockflow.fulfillment.internal.domain.PickStatus;
import com.stockflow.fulfillment.internal.domain.Shipment;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.DesignVerificationJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickLineJpaEntity;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.DesignVerificationJpaRepository;
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
import com.stockflow.order.api.ShipmentHandover;
import com.stockflow.warehouse.api.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
 * service takes the order's row lock). Packing changes no stock: picked goods left it when they were picked, printed
 * goods when their production order completed (decision G4-Q2, 2026-10-21). Cancelling an order takes the order and
 * stock locks in its own transaction and reaches the pick only afterwards, in the listener.</p>
 */
@Service
@Transactional
class PackingService implements Packing {

    private static final Logger log = LoggerFactory.getLogger(PackingService.class);

    /**
     * The order statuses packing may change parcels in: released, picking, or the rest of a split order - and
     * IN_PRODUCTION, its stock packed ahead of the prints, when Sales allowed partial shipments (PACK-EARLY).
     */
    private static final Set<OrderStatus> PACKABLE = EnumSet.of(OrderStatus.READY_TO_FULFILL, OrderStatus.PICKING,
            OrderStatus.PARTIALLY_FULFILLED);
    private static final EnumSet<PackStatus> QUEUED = EnumSet.of(PackStatus.PENDING, PackStatus.PACKING,
            PackStatus.ON_HOLD);
    /** The most picked-but-unpacked tasks the queue reads at once; far above what one warehouse has waiting. */
    private static final int QUEUE_WORKING_SET = Pages.MAX_PAGE_SIZE * 10;

    private final PickJpaRepository picks;
    private final PickLineJpaRepository pickLines;
    private final PackJpaRepository packs;
    private final PackageJpaRepository packages;
    private final ShipmentJpaRepository shipments;
    private final DesignVerificationJpaRepository verifications;
    private final PackingSessionStore store;
    private final com.stockflow.fulfillment.internal.repository.PackageScanJpaRepository scans;
    private final OrderService orders;
    private final InventoryService inventory;
    private final InventoryControlService itemControls;
    private final DesignService designs;
    private final IdentityService identities;
    private final WarehouseService warehouses;
    private final ShipmentTimeline timeline;
    private final com.stockflow.fulfillment.internal.repository.DispatchLoadShipmentJpaRepository loadMembers;
    private final com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository loads;
    private final com.stockflow.fulfillment.internal.repository.DispatchLoadStore loadStore;
    private final Clock clock;
    private final int dimDivisor;
    private final com.stockflow.common.audit.AuditTrail audit;
    private final LoadPlanReleases loadPlans;
    private final PlanVehicles planVehicles;

    @SuppressWarnings("java:S107")
    PackingService(PickJpaRepository picks, PickLineJpaRepository pickLines, PackJpaRepository packs,
                   PackageJpaRepository packages, ShipmentJpaRepository shipments,
                   DesignVerificationJpaRepository verifications,
                   PackingSessionStore store,
                   com.stockflow.fulfillment.internal.repository.PackageScanJpaRepository scans,
                   OrderService orders, InventoryService inventory,
                   InventoryControlService itemControls, DesignService designs,
                   IdentityService identities, WarehouseService warehouses, ShipmentTimeline timeline,
                   com.stockflow.fulfillment.internal.repository.DispatchLoadShipmentJpaRepository loadMembers,
                   com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository loads,
                   com.stockflow.fulfillment.internal.repository.DispatchLoadStore loadStore, Clock clock,
                   @Value("${stockflow.fulfillment.packing.dim-divisor:6000}") int dimDivisor,
                   com.stockflow.common.audit.AuditTrail audit, LoadPlanReleases loadPlans,
                   PlanVehicles planVehicles) {
        this.picks = picks;
        this.pickLines = pickLines;
        this.packs = packs;
        this.packages = packages;
        this.shipments = shipments;
        this.verifications = verifications;
        this.store = store;
        this.scans = scans;
        this.orders = orders;
        this.inventory = inventory;
        this.itemControls = itemControls;
        this.designs = designs;
        this.identities = identities;
        this.warehouses = warehouses;
        this.timeline = timeline;
        this.loadMembers = loadMembers;
        this.loads = loads;
        this.loadStore = loadStore;
        this.clock = clock;
        this.dimDivisor = dimDivisor;
        this.audit = audit;
        this.loadPlans = loadPlans;
        this.planVehicles = planVehicles;
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
        // rest (spec §5.5): an order on hold for anything but its design check, or one packing can no longer work on,
        // is left out. Orders live in another module, so the candidates - picked, not yet packed, in the caller's
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
        // LP-1: packing started - the order's delivery mode is locked from now on (under the order's row lock, after
        // the pick's, as completion takes them).
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
        // LP-1: a kit opens packages as openPackage does - packing started, the delivery mode is locked from now on.
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
     * The label as it prints now. For a shipment waiting for dispatch the COD is what the carrier would collect if it
     * left now, and printing records it on the shipment (09 BR-04): the handover is refused while the order fixes
     * another figure than the last label printed ({@code LABEL_OUTDATED}), so a reprint is what lets it go.
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
        List<ShipmentHandover.Line> contents = handoverLines(together);
        BigDecimal cod;
        if (shipment != null && shipment.getStatus() != ShipmentStatus.READY_TO_DISPATCH) {
            cod = shipment.getCodAmount();
        } else if (shipment != null && contents.isEmpty()) {
            // Parcels packed before they recorded their contents leave as the whole order: its balance (G4-2a).
            cod = order.lifecycle() == null || order.lifecycle().amountDueOnDelivery() == null ? shipment.getCodAmount()
                    : order.lifecycle().amountDueOnDelivery();
        } else {
            cod = orders.codFor(order.orderId(), contents, pendingLines(session,
                    shipments.findByPackIdOrderByReadyAtAscIdAsc(ctx.pack().getId()), shipment));
        }
        if (shipment != null && shipment.getStatus() == ShipmentStatus.READY_TO_DISPATCH) {
            Shipment printed = shipment.toDomain();
            if (printed.labelPrinted(cod, clock.instant())) {
                log.info("Shipment {}: label reprinted, the carrier now collects {}", shipment.getCode(), cod);
            }
            // Before the shipment's row changes: the load's lock first, the order the handover takes them in. Also when
            // the figure did not change here: a load that copied an older one is brought in line by the reprint.
            relabelOnLoad(shipment.getId(), cod);
            shipment.apply(printed);
            shipments.saveAndFlush(shipment);
        }
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
                shipment == null, cod, order.total().currency().getCurrencyCode(), recipient,
                warehouse.map(w -> w.name()).orElse(null), warehouse.map(w -> w.address()).orElse(null),
                parcel.type().name(), m.weightKg(), m.lengthCm(), m.widthCm(), m.heightCm(), parcel.dimWeightKg(),
                parcel.fragile(), parcel.rotatable(), contents(parcel, skus), parcel.kit());
    }

    // ------------------------------------------------------------------ completing

    /** What a completion did: the shipment made, or null when a design check put packing on hold. */
    record Completed(UUID shipmentId, OrderSummary order) { }

    /**
     * See {@code FulfillmentService.completePacking}. The pick is locked first; the order's row lock is taken by its
     * service. Nothing leaves stock here, a print line included: its goods left when production completed (G4-Q2).
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
        if (request.requestId() != null && ctx.pack().getStatus() == PackStatus.ON_HOLD
                && request.requestId().equals(ctx.pack().getHeldRequestId())) {
            // The same completion again after its design check put packing on hold: the same answer.
            return new Completed(null, ctx.order());
        }
        if (ctx.pack().getStatus() == PackStatus.PACKED) {
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
        // BR-DSG-003, 08 BR-02: the printed goods leaving now are checked against their design first.
        List<PackingSession.Line> printed = session.printLinesIn(completion.lines().keySet());
        if (!verifyDesigns(ctx.pack(), ctx.order(), printed.stream().map(PackingSession.Line::orderLineId).toList(),
                actor.userId())) {
            session.hold();
            ctx.pack().holdFor(request.requestId());
            store.save(session, ctx.pack());
            orders.putOnDesignHold(ctx.order().orderId(), "DESIGN_MISMATCH");
            log.warn("Packing of order {} held: a printed design does not match", ctx.pick().getOrderNumber());
            return new Completed(null, ctx.order());
        }
        OrderSummary after;
        if (completion.partial()) {
            // Part of the order: it is no longer cancelled (decision 2026-10-17); its status follows the handover.
            orders.recordPacked(ctx.order().orderId());
            after = requireOrder(ctx.order().orderId());
        } else {
            after = orders.markPacked(ctx.order().orderId(), actor.userId());
        }
        List<ShipmentHandover.Line> lines = completion.lines().entrySet().stream()
                .map(e -> new ShipmentHandover.Line(e.getKey(), e.getValue())).toList();
        // The money paid ahead goes to the shipments waiting for dispatch first, in the order they were made.
        BigDecimal cod = orders.codFor(ctx.order().orderId(), lines, pendingLines(session,
                shipments.findByPackIdOrderByReadyAtAscIdAsc(ctx.pack().getId()), null));
        long before = shipments.countByOrderId(ctx.order().orderId());
        // LP-1: the shipment travels as the order does - locked since its first package was opened.
        DeliveryMode mode = deliveryModeOf(after);
        Shipment shipment = Shipment.ready(Identifiers.newId(), ctx.order().orderNumber() + "-S" + (before + 1),
                ctx.order().orderId(), ctx.pack().getId(), ctx.pick().getWarehouseId(), request.requestId(),
                mode, completion.parcels().size(), cod,
                completion.partial() ? request.note().trim() : null, actor.userId(), now);
        shipments.saveAndFlush(ShipmentJpaEntity.of(shipment));
        timeline.record(shipment, null, ctx.order(), null, null, null);
        session.ship(completion, shipment.id(), actor.userId(), now);
        store.save(session, ctx.pack());
        log.info("Order {}: shipment {} ready to dispatch, {} package(s){}", ctx.pick().getOrderNumber(),
                shipment.code(), shipment.packageCount(), completion.partial() ? ", part of the order" : "");
        return new Completed(shipment.id(), after);
    }

    /**
     * QC or a warehouse manager re-verifies held packing (08 §5 Verification Failed → In Progress): every print line
     * of the order is checked again; all matching, the order's design hold is resolved and the packer completes again.
     *
     * @return whether packing is back in progress
     */
    public boolean reverify(UUID taskId, String resolutionNote, CurrentUser actor) {
        PickJpaEntity pick = lockPick(taskId);
        WarehouseScope.requireWarehouse(pick.getWarehouseId());
        PackJpaEntity pack = requirePack(pick);
        if (pack.getStatus() != PackStatus.ON_HOLD) {
            throw new BusinessException(ErrorCode.PICK_TASK_STATE_CONFLICT, "The package has no design-integrity hold");
        }
        OrderSummary order = TaskAccess.orderInFulfilment(orders, pick);
        List<UUID> printLines = order.lines().stream().filter(line -> line.designSnapshotId() != null)
                .map(OrderSummary.LineSummary::lineId).toList();
        if (!verifyDesigns(pack, order, printLines, actor.userId())) {
            return false;
        }
        orders.resolveDesignHold(order.orderId(), actor.userId(), resolutionNote);
        PackingSession session = load(new Context(pick, pack, order));
        session.resume();
        store.save(session, pack);
        return true;
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
                    // with the carrier and cannot be voided here. Left as it is for a person to resolve; its other
                    // vehicles are refused at their signature (the order is cancelled).
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
                packages.findByShipmentIdOrderByPackageNoAsc(row.getId()).forEach(parcel -> {
                    parcel.apply(parcel.getShipmentId(), parcel.getPackageType(), PackageStatus.VOIDED, parcel.getWeightKg(),
                            parcel.getLengthCm(), parcel.getWidthCm(), parcel.getHeightCm(), parcel.getDimWeightKg(),
                            parcel.isRotatable(), parcel.isRotatableOverridden(), parcel.isFragile(),
                            parcel.isFragileOverridden(), parcel.getSealedAt(), parcel.getSealedBy(), now, null);
                    packages.save(parcel);
                });
            }
        }
        for (PackageJpaEntity parcel : packages.findByPackIdOrderByPackageNoAsc(pack.getId())) {
            if (parcel.getStatus() != PackageStatus.VOIDED && parcel.getShipmentId() == null) {
                parcel.apply(null, parcel.getPackageType(), PackageStatus.VOIDED, parcel.getWeightKg(),
                        parcel.getLengthCm(), parcel.getWidthCm(), parcel.getHeightCm(), parcel.getDimWeightKg(),
                        parcel.isRotatable(), parcel.isRotatableOverridden(), parcel.isFragile(),
                        parcel.isFragileOverridden(), parcel.getSealedAt(), parcel.getSealedBy(), now, null);
                packages.save(parcel);
            }
        }
        log.info("Packing of cancelled order {}: open parcels voided", pick.getOrderNumber());
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

    /** Shown in the queue: an order packing may work on, or one held only for its design check (QC's work). */
    private static boolean queueable(com.stockflow.order.api.OrderHoldState state) {
        if (state == null) {
            return false;
        }
        return PACKABLE.contains(state.status()) || (state.status() == OrderStatus.ON_HOLD
                && state.holdReason() == com.stockflow.order.api.HoldReason.DESIGN_MISMATCH)
                || state.status() == OrderStatus.IN_PRODUCTION && state.allowPartialShipment();
    }

    /** LP-1: the order's delivery mode in fulfillment's words (the same names); PARCEL on a row without it. */
    static DeliveryMode deliveryModeOf(OrderSummary order) {
        return order.lifecycle() == null ? DeliveryMode.PARCEL
                : DeliveryMode.valueOf(order.lifecycle().deliveryMode().name());
    }

    private static boolean allowsPartialShipment(OrderSummary order) {
        return order.lifecycle() != null && order.lifecycle().allowPartialShipment();
    }

    private static void requirePackable(OrderSummary order) {
        if (order.status() == OrderStatus.IN_PRODUCTION) {
            // PACK-EARLY: packing the stock while the prints are made ships part of the order - Sales' decision.
            if (!allowsPartialShipment(order)) {
                throw new BusinessException(ErrorCode.PACK_PARTIAL_NOT_ALLOWED, ("Order %s is still printed: its stock "
                        + "is packed ahead of the prints only when Sales allows partial shipments on the order; "
                        + "otherwise it waits at packing until production completes").formatted(order.orderNumber()));
            }
            return;
        }
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
     * what reached packing (a stock line: what was picked and not put back; a print line: everything, production
     * delivered it before the order was released to the warehouse), the lots and serials of those goods, and the SKU's
     * barcode, rotatable flag and storage class.
     */
    private List<PackingSession.Line> facts(PickJpaEntity pick, OrderSummary order) {
        Map<UUID, Integer> closed = new HashMap<>();
        if (order.lifecycle() != null) {
            order.lifecycle().lines().forEach(p -> closed.put(p.lineId(), p.cancelledQuantity()));
        }
        Map<UUID, List<PickLineJpaEntity>> picked = pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pick.getId())
                .stream().filter(line -> line.getStatus() == PickLineStatus.PICKED)
                .collect(Collectors.groupingBy(PickLineJpaEntity::getOrderLineId));
        List<UUID> holdIds = new ArrayList<>();
        picked.values().forEach(lines -> lines.forEach(line -> {
            if (line.getReservationId() != null) {
                holdIds.add(line.getReservationId());
            }
        }));
        order.lines().stream().filter(line -> line.designSnapshotId() != null)
                .forEach(line -> holdIds.addAll(line.reservationIds()));
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
            int available;
            if (print) {
                // PACK-EARLY: while the order is printed (or shipped its stock before production completed) its printed
                // goods are not at packing yet; once production completed, everything it wants is.
                available = order.lifecycle() == null || order.lifecycle().printLinesReady() ? wanted : 0;
                for (UUID id : line.reservationIds()) {
                    HoldView hold = holds.get(id);
                    if (hold != null && (hold.status() == HoldView.Status.HELD
                            || hold.status() == HoldView.Status.CONSUMED)) {
                        collect(lots, serials, hold.lotNumber(), hold.serialNumber(), hold.quantity());
                    }
                }
            } else {
                List<PickLineJpaEntity> rows = picked.getOrDefault(line.lineId(), List.of());
                available = rows.stream().mapToInt(PickLineJpaEntity::getPickedQty).sum();
                for (PickLineJpaEntity row : rows) {
                    HoldView hold = row.getReservationId() == null ? null : holds.get(row.getReservationId());
                    collect(lots, serials, row.getLotNumber(), hold == null ? null : hold.serialNumber(),
                            row.getPickedQty());
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

    /**
     * BR-DSG-003: the design file of each print line among {@code lineIds} against the checksum the order confirmed.
     * Every attempt is kept ({@code design_verification}, append-only), matching or not.
     */
    private boolean verifyDesigns(PackJpaEntity pack, OrderSummary order, List<UUID> lineIds, UUID actor) {
        boolean allMatch = true;
        for (var line : order.lines()) {
            if (line.designSnapshotId() == null || !lineIds.contains(line.lineId())) {
                continue;
            }
            var check = designs.verifyForFulfillment(line.designSnapshotId(), line.designChecksum());
            var result = check.matching() ? DesignVerificationResult.MATCH : DesignVerificationResult.MISMATCH;
            verifications.save(new DesignVerificationJpaEntity(Identifiers.newId(), pack.getId(), order.orderId(),
                    line.lineId(), line.designSnapshotId(), check.expectedChecksum(), check.actualChecksum(), result,
                    actor, clock.instant()));
            allMatch &= check.matching();
        }
        return allMatch;
    }


    /**
     * E2E QA 2026-10-21 (F2): a label reprinted with another COD while its shipment is on an open load - the
     * {@code LABEL_OUTDATED} way out - updates the load's copy of the label's figure, so the load, the handover answer
     * and the signed manifest show what the parcels say. The load's lock is taken before the shipment's row changes,
     * the order the handover takes them in.
     */
    private void relabelOnLoad(UUID shipmentId, BigDecimal cod) {
        loadMembers.findByShipmentIdInAndActiveTrue(List.of(shipmentId)).stream().findFirst().ifPresent(member ->
                loads.lockById(member.getLoadId()).ifPresent(header -> {
                    var load = loadStore.load(header);
                    if (load.relabel(shipmentId, cod)) {
                        loadStore.save(load, header);
                        log.info("Load {}: label COD of shipment {} is now {}", load.code(), shipmentId, cod);
                    }
                }));
    }

    /**
     * The units of the shipments waiting for dispatch that were made before {@code before} (all of them when null): the
     * paid-ahead money is theirs first (09 BR-04), at the label as at the handover.
     */
    private static List<ShipmentHandover.Line> pendingLines(PackingSession session, List<ShipmentJpaEntity> made,
                                                            ShipmentJpaEntity before) {
        java.util.Set<UUID> earlier = new java.util.HashSet<>();
        for (ShipmentJpaEntity row : made) {
            if (before != null && row.getId().equals(before.getId())) {
                break;
            }
            if (row.getStatus() == ShipmentStatus.READY_TO_DISPATCH) {
                earlier.add(row.getId());
            }
        }
        return handoverLines(session.parcels().stream().filter(p -> p.status() == PackageStatus.SEALED
                && p.shipmentId() != null && earlier.contains(p.shipmentId())).toList());
    }

    private static List<ShipmentHandover.Line> handoverLines(List<PackingSession.Parcel> parcels) {
        Map<UUID, Integer> totals = new LinkedHashMap<>();
        parcels.forEach(parcel -> parcel.lineTotals().forEach((line, quantity) -> totals.merge(line, quantity,
                Integer::sum)));
        return totals.entrySet().stream().map(e -> new ShipmentHandover.Line(e.getKey(), e.getValue())).toList();
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
                        row.getDeliveryMode().name(), row.getPackageCount(), row.getCodAmount(),
                        ctx.order().total().currency().getCurrencyCode(), row.isPartial(),
                        row.getPartialNote(), row.getReadyAt())).toList();
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
                parcel.kitOverrideReason());
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
