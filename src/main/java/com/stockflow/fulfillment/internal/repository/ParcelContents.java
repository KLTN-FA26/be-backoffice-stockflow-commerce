package com.stockflow.fulfillment.internal.repository;

import com.stockflow.fulfillment.internal.entity.PackageJpaEntity;
import com.stockflow.fulfillment.internal.entity.PackageLineJpaEntity;
import com.stockflow.fulfillment.internal.entity.PickLineJpaEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What shipping parcels hold, per order line: what the handover reports to the order, what a load plan names a parcel
 * by and checks a partial order against (09). Two kinds of row hold goods differently (team plan 4C-6):
 * <ul>
 *   <li>a shipping carton of the check station holds what its pick line took - the carton whole, the line's order line;
 *       nothing is copied onto the carton row;</li>
 *   <li>a repack parcel holds its {@code package_line} totals (until team plan 4C-7 removes that station).</li>
 * </ul>
 * Read in two or three queries for any number of parcels.
 */
@Component
public class ParcelContents {

    /** {@code quantity} units of {@code orderLineId} in parcel {@code packageId}. */
    public record Line(UUID packageId, UUID orderLineId, int quantity) { }

    private final PackageJpaRepository packages;
    private final PackageLineJpaRepository packageLines;
    private final PickLineJpaRepository pickLines;

    ParcelContents(PackageJpaRepository packages, PackageLineJpaRepository packageLines,
                   PickLineJpaRepository pickLines) {
        this.packages = packages;
        this.packageLines = packageLines;
        this.pickLines = pickLines;
    }

    /** The contents of these parcels, one entry per parcel and order line it holds; unknown ids hold nothing. */
    public List<Line> of(Collection<UUID> packageIds) {
        if (packageIds.isEmpty()) {
            return List.of();
        }
        List<PackageJpaEntity> cartons = packages.findAllById(packageIds).stream().filter(PackageJpaEntity::isCarton)
                .toList();
        List<Line> out = new ArrayList<>();
        if (!cartons.isEmpty()) {
            Map<UUID, PickLineJpaEntity> lines = pickLines.findAllById(cartons.stream()
                            .map(PackageJpaEntity::getPickLineId).toList()).stream()
                    .collect(Collectors.toMap(PickLineJpaEntity::getId, Function.identity()));
            for (PackageJpaEntity carton : cartons) {
                PickLineJpaEntity line = lines.get(carton.getPickLineId());
                out.add(new Line(carton.getId(), line.getOrderLineId(), line.getAllocatedQty()));
            }
        }
        if (cartons.size() < packageIds.size()) {
            for (PackageLineJpaEntity line : packageLines.findByPackageIdIn(packageIds)) {
                out.add(new Line(line.getPackageId(), line.getOrderLineId(), line.getQuantity()));
            }
        }
        return out;
    }
}
