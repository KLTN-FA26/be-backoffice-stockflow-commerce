package com.stockflow.fulfillment.internal.repository;

import com.stockflow.fulfillment.internal.domain.CartonCheck;
import com.stockflow.fulfillment.internal.domain.PickLineStatus;
import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickLineJpaEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reads a task's {@link CartonCheck} from its pack row, its shipping-carton rows of {@code fulfillment.package} and their
 * pick lines, and writes back what changed (team plan 4C-6). What a carton holds - order line, SKU, lot, units - is the
 * pick line's, never copied onto the carton row: the line is what took the carton, and the two cannot disagree.
 *
 * <p>The repack station's parcels of the same pack ({@code pick_line_id} null) are not part of the check and are left
 * out. The caller holds the pick's lock, so two writers of one session are serialised before they reach here.</p>
 */
@Component
public class CartonCheckStore {

    private final PackageJpaRepository packages;
    private final PackJpaRepository packs;
    private final PickLineJpaRepository pickLines;

    CartonCheckStore(PackageJpaRepository packages, PackJpaRepository packs, PickLineJpaRepository pickLines) {
        this.packages = packages;
        this.packs = packs;
        this.pickLines = pickLines;
    }

    /** The session of {@code pack}, for order {@code orderId} numbered {@code orderNumber}. */
    public CartonCheck load(PackJpaEntity pack, UUID orderId, String orderNumber) {
        List<PackageJpaEntity> rows = packages.findByPackIdOrderByPackageNoAsc(pack.getId()).stream()
                .filter(PackageJpaEntity::isCarton).toList();
        Map<UUID, PickLineJpaEntity> lines = rows.isEmpty() ? Map.of()
                : pickLines.findAllById(rows.stream().map(PackageJpaEntity::getPickLineId).toList()).stream()
                .collect(Collectors.toMap(PickLineJpaEntity::getId, Function.identity()));
        List<CartonCheck.Carton> cartons = new ArrayList<>();
        for (PackageJpaEntity row : rows) {
            PickLineJpaEntity line = lines.get(row.getPickLineId());
            cartons.add(new CartonCheck.Carton(row.getId(), row.getPackageNo(), row.getLabelCode(), picked(line),
                    row.getStatus(), row.getCheckedAt(), row.getCheckedBy(), row.getLabeledAt(), row.getLabeledBy(),
                    row.getLabelVersion(), row.getLengthCm(), row.getWidthCm(), row.getHeightCm(), row.getWeightKg(),
                    row.getDimWeightKg(), row.isRotatable(), row.getShipmentId(), row.getRemovedAt(),
                    row.getRemovedBy(), row.getRemovalReason(), row.getRemovalNote()));
        }
        return new CartonCheck(pack.getId(), orderNumber, pack.getStatus(), pack.getStartedAt(), pack.getStartedBy(),
                pack.getPackedAt(), pack.getPackedBy(), cartons, packages.lastPackageNoOfOrder(orderId));
    }

    /**
     * The cartons the task picked, as the check receives them (08 §4.1 step 1, §5.2): every PICKED line of the task with
     * its carton whole - nothing of it found damaged at the bench before 4C-6 - in walking order.
     */
    public List<CartonCheck.PickedCarton> pickedCartons(UUID pickId) {
        return pickLines.findByPickIdOrderBySequenceNoAscIdAsc(pickId).stream()
                .filter(line -> line.getStatus() == PickLineStatus.PICKED && line.getLpn() != null
                        && line.getGoodQty() == line.getAllocatedQty())
                .map(CartonCheckStore::picked).toList();
    }

    /** Writes the session's own state when it changed and every carton changed or added. */
    public void save(CartonCheck check, PackJpaEntity pack) {
        if (check.sessionChanged()) {
            pack.apply(check.status(), check.startedAt(), check.startedBy(), check.completedAt(), check.completedBy());
            packs.save(pack);
        }
        List<CartonCheck.Carton> changed = check.changedCartons();
        if (changed.isEmpty()) {
            check.saved();
            return;
        }
        Map<UUID, PackageJpaEntity> rows = new java.util.HashMap<>(packages.findAllById(changed.stream()
                        .filter(CartonCheck.Carton::stored).map(CartonCheck.Carton::id).toList()).stream()
                .collect(Collectors.toMap(PackageJpaEntity::getId, Function.identity())));
        // A carton new to the session is written TO_CHECK first, as tg_package_carton wants every carton to start, even
        // when it was received and scanned in one request (a carton a top-up picked, scanned at once).
        List<PackageJpaEntity> fresh = changed.stream().filter(carton -> !carton.stored())
                .map(carton -> PackageJpaEntity.carton(carton.id(), check.sessionId(), carton.packageNo(),
                        carton.labelCode(), carton.pickLineId(), carton.lpn())).toList();
        if (!fresh.isEmpty()) {
            packages.saveAllAndFlush(fresh).forEach(row -> rows.put(row.getId(), row));
        }
        List<PackageJpaEntity> written = new ArrayList<>();
        for (CartonCheck.Carton carton : changed) {
            PackageJpaEntity row = rows.get(carton.id());
            if (row == null) {
                throw new IllegalStateException("Shipping carton " + carton.id() + " vanished");
            }
            row.applyCarton(carton.status(), carton.checkedAt(), carton.checkedBy(), carton.labeledAt(),
                    carton.labeledBy(), carton.labelVersion(), carton.weightKg(), carton.lengthCm(), carton.widthCm(),
                    carton.heightCm(), carton.dimWeightKg(), carton.rotatable(), carton.shipmentId(),
                    carton.removedAt(), carton.removedBy(), carton.removalReason(), carton.removalNote());
            written.add(row);
        }
        // Flushed here so a database rule (tg_package_carton, the unique LPN being checked) refuses inside the call.
        packages.saveAllAndFlush(written);
        check.saved();
    }

    private static CartonCheck.PickedCarton picked(PickLineJpaEntity line) {
        return new CartonCheck.PickedCarton(line.getId(), line.getLpn(), line.getOrderLineId(), line.getSku(),
                line.getLotNumber(), line.getAllocatedQty());
    }
}
