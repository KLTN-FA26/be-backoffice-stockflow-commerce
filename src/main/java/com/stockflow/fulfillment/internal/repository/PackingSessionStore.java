package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.fulfillment.internal.domain.PackingSession;
import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageLineJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageScanJpaEntity;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads a {@link PackingSession} from its pack, parcels and scans, and writes back what changed: the pack's status,
 * the parcels touched, the new scans (append-only) and the per-line totals of the parcels touched.
 *
 * <p>A parcel's contents are the sum of its scans per order line, lot and serial; {@code package_line} is kept equal to
 * the per-line sum, which is what the over-pack trigger checks and load planning reads. The caller holds the pick's
 * lock, so two writers of one session are serialised before they reach here.</p>
 */
@Component
public class PackingSessionStore {

    private final PackageJpaRepository packages;
    private final PackageLineJpaRepository packageLines;
    private final PackageScanJpaRepository scans;
    private final PackJpaRepository packs;
    private final ShipmentJpaRepository shipments;
    private final EntityManager entityManager;

    PackingSessionStore(PackageJpaRepository packages, PackageLineJpaRepository packageLines,
                        PackageScanJpaRepository scans, PackJpaRepository packs, ShipmentJpaRepository shipments,
                        EntityManager entityManager) {
        this.packages = packages;
        this.packageLines = packageLines;
        this.scans = scans;
        this.packs = packs;
        this.shipments = shipments;
        this.entityManager = entityManager;
    }

    public PackingSession load(PackJpaEntity pack, String orderNumber, List<PackingSession.Line> lines) {
        List<PackageJpaEntity> rows = packages.findByPackIdOrderByPackageNoAsc(pack.getId());
        List<PackageScanJpaEntity> scanned = rows.isEmpty() ? List.of()
                : scans.findByPackageIdInOrderByScannedAtAscIdAsc(rows.stream().map(PackageJpaEntity::getId).toList());
        Map<UUID, Map<PackingSession.ContentKey, Integer>> contents = new HashMap<>();
        for (PackageScanJpaEntity scan : scanned) {
            contents.computeIfAbsent(scan.getPackageId(), id -> new LinkedHashMap<>())
                    .merge(new PackingSession.ContentKey(scan.getOrderLineId(), scan.getLotNumber(),
                            scan.getSerialNumber()), scan.getQuantity(), Integer::sum);
        }
        // Parcels written before scans existed (seeded or packed by the old flow): their package_line rows are the
        // contents, without a lot.
        Map<UUID, List<PackageLineJpaEntity>> legacy = rows.stream().anyMatch(row -> !contents.containsKey(row.getId()))
                ? packageLines.findByPackageIdIn(rows.stream().map(PackageJpaEntity::getId).toList()).stream()
                .collect(Collectors.groupingBy(PackageLineJpaEntity::getPackageId))
                : Map.of();
        java.util.Set<UUID> returned = rows.stream().anyMatch(row -> row.getShipmentId() != null)
                ? shipments.findByPackIdOrderByReadyAtAscIdAsc(pack.getId()).stream()
                .filter(s -> s.getStatus() == com.stockflow.fulfillment.internal.domain.ShipmentStatus.RETURNED)
                .map(com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity::getId)
                .collect(Collectors.toSet())
                : java.util.Set.of();
        List<PackingSession.Parcel> parcels = new ArrayList<>();
        for (PackageJpaEntity row : rows) {
            Map<PackingSession.ContentKey, Integer> held = contents.get(row.getId());
            if (held == null) {
                held = new LinkedHashMap<>();
                for (PackageLineJpaEntity line : legacy.getOrDefault(row.getId(), List.of())) {
                    held.put(new PackingSession.ContentKey(line.getOrderLineId(), null, null), line.getQuantity());
                }
            }
            PackingSession.Parcel parcel = toParcel(row, held);
            boolean cameBack = row.getShipmentId() != null && returned.contains(row.getShipmentId());
            parcels.add(cameBack ? parcel.markReturned() : parcel);
        }
        return new PackingSession(pack.getId(), orderNumber, pack.getStatus(), pack.getStartedAt(), pack.getStartedBy(),
                pack.getPackedAt(), pack.getPackedBy(), lines, parcels,
                scanned.stream().map(PackageScanJpaEntity::getId).toList());
    }

    /**
     * Writes what changed. A database kit rule that refuses it (V20261022001000) is answered with the kit's own 409
     * ({@link KitConstraints}), not a 500 - the rule that a kit ships whole included: it waits for the commit, so it is
     * checked here, before the write returns, whenever a kit part changed.
     */
    public void save(PackingSession session, PackJpaEntity pack) {
        try {
            write(session, pack);
            if (session.changedParcels().stream().anyMatch(parcel -> parcel.kit() != null)) {
                checkKitsNow();
            }
        } catch (RuntimeException refused) {
            throw KitConstraints.translate(refused).map(RuntimeException.class::cast).orElse(refused);
        }
    }

    /**
     * The deferred "a kit ships whole" rule on what this transaction wrote so far, answered as the station answers it
     * ({@code KIT_INCOMPLETE}) instead of failing the commit.
     */
    public void checkKits() {
        try {
            checkKitsNow();
        } catch (RuntimeException refused) {
            throw KitConstraints.translate(refused).map(RuntimeException.class::cast).orElse(refused);
        }
    }

    /**
     * Runs the deferred check on what this transaction wrote so far, then defers it again: later writes of the same
     * transaction (one part at a time) are judged as they leave it, at the next check or the commit.
     */
    private void checkKitsNow() {
        entityManager.flush();
        entityManager.createNativeQuery("SET CONSTRAINTS fulfillment.tg_package_kit_ships_whole IMMEDIATE")
                .executeUpdate();
        entityManager.createNativeQuery("SET CONSTRAINTS fulfillment.tg_package_kit_ships_whole DEFERRED")
                .executeUpdate();
    }

    private void write(PackingSession session, PackJpaEntity pack) {
        pack.apply(session.status(), session.startedAt(), session.startedBy(), session.packedAt(), session.packedBy());
        packs.saveAndFlush(pack);
        List<PackingSession.Parcel> changed = session.changedParcels();
        if (!changed.isEmpty()) {
            Map<UUID, PackageJpaEntity> existing = packages.findAllById(changed.stream()
                    .map(PackingSession.Parcel::id).toList()).stream()
                    .collect(Collectors.toMap(PackageJpaEntity::getId, Function.identity()));
            for (PackingSession.Parcel parcel : changed) {
                PackageJpaEntity row = existing.computeIfAbsent(parcel.id(), id -> new PackageJpaEntity(id,
                        session.packId(), parcel.packageNo(), parcel.labelCode(), parcel.kit()));
                var m = parcel.measures();
                row.apply(parcel.shipmentId(), parcel.type(), parcel.status(), m == null ? null : m.weightKg(),
                        m == null ? null : m.lengthCm(), m == null ? null : m.widthCm(), m == null ? null : m.heightCm(),
                        parcel.dimWeightKg(), parcel.rotatable(), parcel.rotatableOverridden(), parcel.fragile(),
                        parcel.fragileOverridden(), parcel.sealedAt(), parcel.sealedBy(), parcel.voidedAt(),
                        parcel.voidedBy());
            }
            packages.saveAllAndFlush(existing.values());
        }
        List<PackingSession.Scan> recorded = session.pullScans();
        if (!recorded.isEmpty()) {
            scans.saveAllAndFlush(recorded.stream().map(scan -> new PackageScanJpaEntity(scan.id(), scan.packageId(),
                    scan.orderLineId(), scan.scannedCode(), scan.lotNumber(), scan.serialNumber(), scan.quantity(),
                    scan.by(), scan.at())).toList());
        }
        writeLineTotals(changed);
    }

    /** {@code package_line} equals the scans' per-line sum of every parcel touched; rows at zero are removed. */
    private void writeLineTotals(List<PackingSession.Parcel> changed) {
        if (changed.isEmpty()) {
            return;
        }
        Map<UUID, List<PackageLineJpaEntity>> stored = packageLines.findByPackageIdIn(changed.stream()
                .map(PackingSession.Parcel::id).toList()).stream()
                .collect(Collectors.groupingBy(PackageLineJpaEntity::getPackageId));
        List<PackageLineJpaEntity> lower = new ArrayList<>();
        List<PackageLineJpaEntity> higher = new ArrayList<>();
        List<PackageLineJpaEntity> gone = new ArrayList<>();
        for (PackingSession.Parcel parcel : changed) {
            Map<UUID, Integer> totals = parcel.lineTotals();
            Map<UUID, PackageLineJpaEntity> rows = stored.getOrDefault(parcel.id(), List.of()).stream()
                    .collect(Collectors.toMap(PackageLineJpaEntity::getOrderLineId, Function.identity()));
            rows.forEach((line, row) -> {
                int now = totals.getOrDefault(line, 0);
                if (now == 0) {
                    gone.add(row);
                } else if (now < row.getQuantity()) {
                    row.setQuantity(now);
                    lower.add(row);
                } else if (now > row.getQuantity()) {
                    row.setQuantity(now);
                    higher.add(row);
                }
            });
            totals.forEach((line, quantity) -> {
                if (!rows.containsKey(line)) {
                    higher.add(new PackageLineJpaEntity(Identifiers.newId(), parcel.id(), line, quantity));
                }
            });
        }
        // Decreases before increases: the over-pack trigger sees every row as it is at that moment.
        packageLines.deleteAll(gone);
        packageLines.saveAllAndFlush(lower);
        packageLines.saveAllAndFlush(higher);
    }

    private static PackingSession.Parcel toParcel(PackageJpaEntity row, Map<PackingSession.ContentKey, Integer> held) {
        PackingSession.Measures measures = row.getWeightKg() == null && row.getLengthCm() == null ? null
                : new PackingSession.Measures(row.getWeightKg(), row.getLengthCm(), row.getWidthCm(),
                row.getHeightCm());
        return new PackingSession.Parcel(row.getId(), row.getPackageNo(), row.getLabelCode(), row.getPackageType(),
                row.getStatus(), row.getShipmentId(), measures, row.getDimWeightKg(), row.isRotatable(),
                row.isRotatableOverridden(), row.isFragile(), row.isFragileOverridden(), row.getSealedAt(),
                row.getSealedBy(), row.getVoidedAt(), row.getVoidedBy(), held, row.getCreatedAt(), row.getKit());
    }
}
