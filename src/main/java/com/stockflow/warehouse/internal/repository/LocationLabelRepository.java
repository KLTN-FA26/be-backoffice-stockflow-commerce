package com.stockflow.warehouse.internal.repository;

import com.stockflow.warehouse.internal.domain.LabelSelection;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.StorageClass;
import com.stockflow.warehouse.internal.domain.StorageLocationKind;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What a location label needs, read in a constant number of queries whatever the number of labels
 * (SCRUM-97): one projection over every storage location, joined to its bin or its area.
 *
 * <p>Rows come back with their statuses unfiltered. Which of them get a label is the service's call,
 * because a code the user named and that is off the layout must be refused, not silently dropped.</p>
 */
public interface LocationLabelRepository {

    boolean shelfExists(UUID warehouseId, UUID shelfId);

    Set<Integer> levelIndexesOf(UUID shelfId);

    boolean zoneExists(UUID warehouseId, UUID zoneId);

    /**
     * Bins first, ordered by shelf code, level index (numerically), bin code - the order a person walks
     * the aisle - then storage areas by code. A shelf or zone selection holds no area.
     */
    List<LabelRow> findRows(UUID warehouseId, LabelSelection selection);

    /**
     * @param shelfStatus the status of a bin's shelf; null for an area, as are {@code shelfCode},
     *                    {@code levelIndex} and {@code binCode}
     * @param areaName    an area's name; null for a bin
     */
    record LabelRow(String locationCode, StorageLocationKind kind, StorageClass storageClass,
                    LocationStatus status, LocationStatus shelfStatus, String shelfCode, Integer levelIndex,
                    String binCode, String areaName) {
    }
}
