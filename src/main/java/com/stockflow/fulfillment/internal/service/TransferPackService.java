package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.fulfillment.internal.domain.PackStatus;
import com.stockflow.fulfillment.internal.domain.PackageStatus;
import com.stockflow.fulfillment.internal.domain.PackageType;
import com.stockflow.fulfillment.internal.domain.PackingSession;
import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.repository.PackJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageScanJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackingSessionStore;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.PackagingSpec;
import com.stockflow.inventory.api.PackagingSpecResolver;
import com.stockflow.inventory.api.StorageClass;
import com.stockflow.inventory.api.TransferFulfillment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * See {@link TransferPacks}. The session is a {@link PackingSession} over the transfer's lines - what is requested is
 * both what is wanted and what may be packed (the transfer reserves nothing at the source before dispatch: its stock is
 * taken, under lock, when its plan's last vehicle signs) - read and written by the same {@link PackingSessionStore}.
 *
 * <p>Lock order: the pack's row, then (through {@link TransferFulfillment}) the transfer's.</p>
 */
@Service
@Transactional
class TransferPackService implements TransferPacks {

    private static final Logger log = LoggerFactory.getLogger(TransferPackService.class);
    /** LP2-8: why a carton shipped whole is an ordinary parcel even when its item ships as several boxes. */
    static final String CARTON_AS_IS = "Unopened stock carton shipped as it is (LP2-8)";

    private final PackJpaRepository packs;
    private final PackageJpaRepository packages;
    private final PackageScanJpaRepository scans;
    private final PackingSessionStore store;
    private final TransferFulfillment transfers;
    private final InventoryControlService itemControls;
    private final PackagingSpecResolver packaging;
    private final Clock clock;
    private final int dimDivisor;

    @SuppressWarnings("java:S107")
    TransferPackService(PackJpaRepository packs, PackageJpaRepository packages, PackageScanJpaRepository scans,
                        PackingSessionStore store, TransferFulfillment transfers, InventoryControlService itemControls,
                        PackagingSpecResolver packaging, Clock clock,
                        @Value("${stockflow.fulfillment.packing.dim-divisor:6000}") int dimDivisor) {
        this.packs = packs;
        this.packages = packages;
        this.scans = scans;
        this.store = store;
        this.transfers = transfers;
        this.itemControls = itemControls;
        this.packaging = packaging;
        this.clock = clock;
        this.dimDivisor = dimDivisor;
    }

    private record Context(PackJpaEntity pack, TransferFulfillment.TransferView transfer) { }

    // ------------------------------------------------------------------ the session

    @Override
    public Session open(UUID transferOrderId, CurrentUser actor) {
        TransferFulfillment.TransferView transfer = transfers.find(transferOrderId).orElseThrow(() ->
                new BusinessException(ErrorCode.TRANSFER_ORDER_NOT_FOUND, "No transfer order " + transferOrderId));
        WarehouseScope.requireWarehouse(transfer.fromWarehouseId());
        PackJpaEntity live = packs.findByTransferOrderIdOrderByCreatedAtDesc(transferOrderId).stream()
                .filter(p -> p.getStatus() != PackStatus.CANCELLED).findFirst().orElse(null);
        if (live == null) {
            transfer = transfers.beginPacking(transferOrderId);
            live = packs.saveAndFlush(PackJpaEntity.forTransfer(Identifiers.newId(), transferOrderId));
            log.info("Transfer {}: packing session opened at the source", transfer.number());
        }
        Context ctx = new Context(packs.lockById(live.getId()).orElseThrow(), transfer);
        PackingSession session = load(ctx);
        if (session.status() == PackStatus.PENDING || session.status() == PackStatus.PACKING) {
            if (session.start(actor.userId(), isManager(actor), clock.instant())) {
                store.save(session, ctx.pack());
            }
        }
        return view(ctx, session);
    }

    @Override
    @Transactional(readOnly = true)
    public Session get(UUID packId) {
        Context ctx = context(packs.findById(packId).filter(p -> p.getTransferOrderId() != null)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No transfer packing session " + packId)));
        return view(ctx, load(ctx));
    }

    @Override
    public Parcel openPackage(UUID packId, String type, String kitOverrideReason, CurrentUser actor) {
        Context ctx = forWork(packId);
        PackingSession session = workable(ctx, actor);
        PackingSession.Parcel parcel = session.openPackage(Identifiers.newId(), packageType(type), kitOverrideReason,
                clock.instant());
        store.save(session, ctx.pack());
        return parcelView(parcel, skus(session), Map.of());
    }

    @Override
    public Kit openKit(UUID packId, UUID transferLineId, Integer partCount, String packageType, CurrentUser actor) {
        Context ctx = forWork(packId);
        PackingSession session = workable(ctx, actor);
        int parts = session.lines().stream().filter(l -> l.orderLineId().equals(transferLineId))
                .mapToInt(PackingSession.Line::packageCount).findFirst().orElse(0);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < parts; i++) {
            ids.add(Identifiers.newId());
        }
        UUID groupId = Identifiers.newId();
        List<PackingSession.Parcel> kit = session.openKit(groupId, ids, transferLineId, partCount,
                packageType(packageType), clock.instant());
        store.save(session, ctx.pack());
        Map<UUID, String> skus = skus(session);
        log.info("Transfer {}: kit {} of {} parts opened for line {}", ctx.transfer().number(), groupId, kit.size(),
                transferLineId);
        return new Kit(groupId, transferLineId, skus.get(transferLineId), kit.size(),
                kit.stream().map(parcel -> parcelView(parcel, skus, Map.of())).toList());
    }

    @Override
    public Session scan(UUID packId, UUID packageId, Scan scan, CurrentUser actor) {
        Context ctx = forWork(packId);
        if (isReplay(ctx, packageId, scan.scanId())) {
            return view(ctx, load(ctx));
        }
        PackingSession session = workable(ctx, actor);
        if (session.scan(scan.scanId(), packageId, scan.scannedCode(), scan.lotNumber(), null, scan.quantity(),
                scan.transferLineId(), scan.kitOverrideReason(), actor.userId(), clock.instant())) {
            store.save(session, ctx.pack());
        }
        return view(ctx, session);
    }

    @Override
    public Parcel seal(UUID packId, UUID packageId, Seal seal, CurrentUser actor) {
        Context ctx = forWork(packId);
        PackingSession session = workable(ctx, actor);
        PackingSession.Parcel parcel = session.seal(packageId, new PackingSession.Measures(seal.weightKg(),
                        seal.lengthCm(), seal.widthCm(), seal.heightCm()), seal.rotatable(), seal.fragile(),
                seal.packageType() == null ? null : packageType(seal.packageType()), seal.kitOverrideReason(),
                dimDivisor, actor.userId(), clock.instant());
        store.save(session, ctx.pack());
        return parcelView(parcel, skus(session), cartonsOf(List.of(parcel)));
    }

    @Override
    public Session voidPackage(UUID packId, UUID packageId, CurrentUser actor) {
        Context ctx = forWork(packId);
        PackingSession session = workable(ctx, actor);
        session.voidPackage(packageId, actor.userId(), clock.instant());
        store.save(session, ctx.pack());
        return view(ctx, session);
    }

    // ------------------------------------------------------------------ LP2-8: a carton as it is

    @Override
    public CartonParcel shipCarton(UUID packId, String lpn, String packageType, CurrentUser actor) {
        Context ctx = forWork(packId);
        PackingSession session = workable(ctx, actor);
        TransferFulfillment.Carton carton = transfers.carton(lpn, ctx.transfer().fromWarehouseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No stock carton labelled " + lpn));
        String code = carton.lpn();
        if (!carton.unopened() || !carton.inStock() || !carton.atWarehouse() || !carton.sellable()
                || carton.quantity() < 1) {
            throw new BusinessException(ErrorCode.STOCK_CARTON_NOT_SHIPPABLE, ("Carton %s is not an unopened carton "
                    + "in stock at the source warehouse with all of it sellable: open it and pack its units at the "
                    + "table").formatted(code));
        }
        if (packages.findFirstByStockPackageIdAndStatusNot(carton.stockPackageId(), PackageStatus.VOIDED)
                .isPresent()) {
            throw new BusinessException(ErrorCode.STOCK_CARTON_NOT_SHIPPABLE,
                    "Carton %s is packed already".formatted(code));
        }
        PackingSession.Line line = session.lines().stream()
                .filter(l -> l.sku().equalsIgnoreCase(String.valueOf(carton.sku())))
                .filter(l -> l.lots().isEmpty() || l.lots().containsKey(String.valueOf(carton.lotNumber())))
                .filter(l -> l.wanted() - session.packedOf(l.orderLineId()) >= carton.quantity())
                .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.STOCK_CARTON_NOT_SHIPPABLE, ("Carton "
                        + "%s holds %d of %s%s: no line of transfer %s still wants that many of it").formatted(code,
                        carton.quantity(), carton.sku(), carton.lotNumber() == null ? "" : " lot " + carton.lotNumber(),
                        ctx.transfer().number()), List.of(FieldError.of("lpn", code))));
        Instant now = clock.instant();
        PackingSession.Parcel parcel = session.openPackage(Identifiers.newId(), packageType(packageType),
                CARTON_AS_IS, now);
        session.scan(Identifiers.newId(), parcel.id(), line.sku(), line.lots().isEmpty() ? null : carton.lotNumber(),
                null, carton.quantity(), line.orderLineId(), CARTON_AS_IS, actor.userId(), now);
        store.save(session, ctx.pack());
        PackageJpaEntity row = packages.findById(parcel.id()).orElseThrow();
        row.shipsCarton(carton.stockPackageId());
        packages.saveAndFlush(row);
        Measures suggested = packaging.unitCarton(new Sku(line.sku()), carton.uomConversionId())
                .map(TransferPackService::measures).orElse(null);
        log.info("Transfer {}: carton {} ({} x {}) opened as package {} - weigh and measure it to seal",
                ctx.transfer().number(), code, carton.quantity(), line.sku(), parcel.labelCode());
        return new CartonParcel(parcelView(parcel, skus(session), Map.of(parcel.id(), carton.stockPackageId())),
                code, carton.unitOfMeasure(), suggested);
    }

    private static Measures measures(PackagingSpec.CartonSpec c) {
        return new Measures(c.weightKg(), cm(c.lengthMm()), cm(c.widthMm()), cm(c.heightMm()));
    }

    private static BigDecimal cm(int mm) {
        return BigDecimal.valueOf(mm, 1);
    }

    // ------------------------------------------------------------------ completing

    @Override
    public Session complete(UUID packId, boolean partial, String note, CurrentUser actor) {
        Context ctx = forWork(packId);
        PackingSession session = load(ctx);
        if (session.status() == PackStatus.PACKED) {
            return view(ctx, session);
        }
        session.requireWorkable(actor.userId(), isManager(actor));
        Instant now = clock.instant();
        // A transfer may ship less than requested (10 step 8, "chênh lệch"): the packer says why.
        PackingSession.Completion completion = session.complete(partial, note, true, actor.userId(), now);
        session.finishTransfer(actor.userId(), now);
        store.save(session, ctx.pack());
        TransferFulfillment.TransferView packed = transfers.markPacked(ctx.transfer().transferId(), now);
        log.info("Transfer {} packed: {} package(s){} - ready for a load plan", packed.number(),
                completion.parcels().size(), completion.partial() ? ", short of the request: " + note.trim() : "");
        return view(new Context(ctx.pack(), packed), session);
    }

    // ------------------------------------------------------------------ internals

    /** Locks the session, checks the source warehouse and that the transfer is still being picked. */
    private Context forWork(UUID packId) {
        PackJpaEntity pack = packs.lockById(packId).filter(p -> p.getTransferOrderId() != null)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No transfer packing session " + packId));
        Context ctx = context(pack);
        if (pack.getStatus() == PackStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.PACK_STATE_CONFLICT,
                    "Picking of transfer %s was cancelled with this packing".formatted(ctx.transfer().number()));
        }
        if (!ctx.transfer().picking()) {
            throw new BusinessException(ErrorCode.PACK_STATE_CONFLICT, "Transfer %s is %s; its parcels cannot change"
                    .formatted(ctx.transfer().number(), ctx.transfer().status()));
        }
        return ctx;
    }

    private Context context(PackJpaEntity pack) {
        TransferFulfillment.TransferView transfer = transfers.find(pack.getTransferOrderId()).orElseThrow();
        WarehouseScope.requireWarehouse(transfer.fromWarehouseId());
        return new Context(pack, transfer);
    }

    private PackingSession workable(Context ctx, CurrentUser actor) {
        PackingSession session = load(ctx);
        session.requireWorkable(actor.userId(), isManager(actor));
        return session;
    }

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

    private PackingSession load(Context ctx) {
        return store.load(ctx.pack(), labelBase(ctx), facts(ctx.transfer()));
    }

    /**
     * What the session's labels start with after {@code PKG-}: the transfer's number, and for a session after a cancelled
     * one (picking cancelled, then packed again) {@code -R<n>} - a label is never printed twice ({@code
     * uk_package_label_code}), and the voided parcels keep theirs.
     */
    private String labelBase(Context ctx) {
        // Ids are time-ordered (UUIDv7): the oldest session first.
        List<UUID> sessions = packs.findByTransferOrderIdOrderByCreatedAtDesc(ctx.transfer().transferId()).stream()
                .map(PackJpaEntity::getId).sorted().toList();
        int ordinal = 1 + sessions.indexOf(ctx.pack().getId());
        return ordinal <= 1 ? ctx.transfer().number() : ctx.transfer().number() + "-R" + ordinal;
    }

    /**
     * The transfer's lines as the packing table sees them: what is requested is what is wanted and what may be packed;
     * a line naming a lot takes that lot only; the SKU's barcode, rotatable flag, storage class and boxes per unit.
     */
    private List<PackingSession.Line> facts(TransferFulfillment.TransferView transfer) {
        Map<String, ItemLogistics> logistics = new HashMap<>();
        List<PackingSession.Line> lines = new ArrayList<>();
        for (TransferFulfillment.Line line : transfer.lines()) {
            ItemLogistics item = logistics.computeIfAbsent(line.sku(),
                    sku -> itemControls.logistics(new Sku(sku)).logistics());
            Map<String, Integer> lots = line.lotNumber() == null ? Map.of() : Map.of(line.lotNumber(),
                    line.requested());
            lines.add(new PackingSession.Line(line.lineId(), line.sku(), item.barcode(), line.requested(),
                    line.requested(), false, null, lots, java.util.Set.of(), item.rotatable(),
                    item.storageClass() == StorageClass.FRAGILE, item.packageCount()));
        }
        return lines;
    }

    private Session view(Context ctx, PackingSession session) {
        Map<UUID, String> skus = skus(session);
        Map<UUID, Integer> lineNos = ctx.transfer().lines().stream()
                .collect(Collectors.toMap(TransferFulfillment.Line::lineId, TransferFulfillment.Line::lineNo));
        Map<UUID, String> lotOf = new HashMap<>();
        ctx.transfer().lines().forEach(l -> lotOf.put(l.lineId(), l.lotNumber()));
        List<Line> lines = session.lines().stream().map(l -> {
            int packed = session.packedOf(l.orderLineId());
            return new Line(l.orderLineId(), lineNos.getOrDefault(l.orderLineId(), 0), l.sku(), l.barcode(),
                    lotOf.get(l.orderLineId()), l.wanted(), packed, Math.max(0, l.wanted() - packed), l.packageCount(),
                    l.packageCount() >= com.stockflow.fulfillment.internal.domain.KitPart.MIN_PARTS);
        }).toList();
        Map<UUID, UUID> cartons = cartonsOf(session.parcels());
        TransferFulfillment.TransferView t = ctx.transfer();
        return new Session(ctx.pack().getId(), t.transferId(), t.number(), t.fromWarehouseId(), t.toWarehouseId(),
                t.status(), t.deliveryMode().name(), session.status().name(), session.startedBy(),
                session.startedAt(), session.packedAt(), lines,
                session.parcels().stream().map(p -> parcelView(p, skus, cartons)).toList());
    }

    private Map<UUID, UUID> cartonsOf(List<PackingSession.Parcel> parcels) {
        Map<UUID, UUID> out = new HashMap<>();
        packages.findAllById(parcels.stream().map(PackingSession.Parcel::id).toList()).stream()
                .filter(p -> p.getStockPackageId() != null).forEach(p -> out.put(p.getId(), p.getStockPackageId()));
        return out;
    }

    private static Parcel parcelView(PackingSession.Parcel parcel, Map<UUID, String> skus, Map<UUID, UUID> cartons) {
        var m = parcel.measures();
        List<Content> contents = parcel.contents().entrySet().stream().map(e -> new Content(e.getKey().orderLineId(),
                skus.get(e.getKey().orderLineId()), e.getKey().lotNumber(), e.getValue())).toList();
        var kit = parcel.kit();
        return new Parcel(parcel.id(), parcel.packageNo(), parcel.labelCode(), parcel.type().name(),
                parcel.status().name(), m == null ? null : m.weightKg(), m == null ? null : m.lengthCm(),
                m == null ? null : m.widthCm(), m == null ? null : m.heightCm(), parcel.dimWeightKg(),
                parcel.rotatable(), parcel.fragile(), parcel.sealedAt(), contents,
                kit == null ? null : new KitView(kit.groupId(), kit.partNo(), kit.partCount(), kit.orderLineId()),
                parcel.kitOverrideReason(), cartons.get(parcel.id()));
    }

    private static Map<UUID, String> skus(PackingSession session) {
        return session.lines().stream().collect(Collectors.toMap(PackingSession.Line::orderLineId,
                PackingSession.Line::sku));
    }

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
}
