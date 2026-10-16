package com.stockflow.warehouse.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.warehouse.internal.domain.EffectiveStatus;
import com.stockflow.warehouse.internal.domain.LabelSelection;
import com.stockflow.warehouse.internal.repository.LocationLabelRepository;
import com.stockflow.warehouse.internal.repository.LocationLabelRepository.LabelRow;
import com.stockflow.warehouse.internal.repository.LocationLookupRepository;
import com.stockflow.warehouse.internal.repository.LocationLookupRepository.WarehouseHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The only implementation of {@link LocationLabelService}.
 *
 * <p>A code the user named and that cannot be printed is an error, while a broad selection silently
 * skips what is off the layout: someone who asked for one particular label must learn why it is
 * missing, whereas nobody asked for each bin of a shelf one by one.</p>
 */
@Service
@Transactional(readOnly = true)
class LocationLabelServiceImpl implements LocationLabelService {

    /** How many offending codes an error message names; the rest are counted, not listed. */
    private static final int LISTED_CODES = 10;

    private final LocationLabelRepository rows;
    private final LocationLookupRepository lookup;

    LocationLabelServiceImpl(LocationLabelRepository rows, LocationLookupRepository lookup) {
        this.rows = rows;
        this.lookup = lookup;
    }

    @Override
    public LocationLabelSheet sheetFor(UUID warehouseId, LabelSelection selection) {
        WarehouseHeader warehouse = lookup.findWarehouseHeader(warehouseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND));
        checkSelection(warehouseId, selection);

        List<LabelRow> found = rows.findRows(warehouseId, selection);
        List<LabelRow> printed;
        if (selection instanceof LabelSelection.OfCodes named) {
            refuseMissingOrOffLayout(named.codes(), found);
            printed = found;
        } else {
            printed = found.stream().filter(LocationLabelServiceImpl::onLayout).toList();
        }

        if (printed.isEmpty()) {
            throw new BusinessException(ErrorCode.LOCATION_LABELS_EMPTY);
        }
        if (printed.size() > MAX_LABELS) {
            throw new BusinessException(ErrorCode.LOCATION_LABELS_TOO_MANY,
                    "%d labels requested; at most %d per request - print by zone or shelf"
                            .formatted(printed.size(), MAX_LABELS));
        }
        return new LocationLabelSheet(warehouse.prefix(), warehouse.name(),
                printed.stream().map(LocationLabelServiceImpl::toLabel).toList());
    }

    /** A shelf, level or zone of another warehouse is as unknown as one that does not exist. */
    private void checkSelection(UUID warehouseId, LabelSelection selection) {
        switch (selection) {
            case LabelSelection.OfShelf shelf -> {
                if (!rows.shelfExists(warehouseId, shelf.shelfId())) {
                    throw new BusinessException(ErrorCode.SHELF_NOT_FOUND);
                }
                if (!shelf.levelIndexes().isEmpty()
                        && !rows.levelIndexesOf(shelf.shelfId()).containsAll(shelf.levelIndexes())) {
                    throw new BusinessException(ErrorCode.SHELF_LEVEL_NOT_FOUND);
                }
            }
            case LabelSelection.OfZone zone -> {
                if (!rows.zoneExists(warehouseId, zone.zoneId())) {
                    throw new BusinessException(ErrorCode.ZONE_NOT_FOUND);
                }
            }
            // The whole warehouse needs no check, and named codes are checked against what the query
            // finds, in refuseMissingOrOffLayout. A default rather than two empty cases because Java 21
            // cannot match OfCodes without binding a variable nobody reads; findRows still switches
            // exhaustively, so a new selector cannot slip past both.
            default -> { }
        }
    }

    private static void refuseMissingOrOffLayout(Set<String> requested, List<LabelRow> found) {
        Set<String> missing = new HashSet<>(requested);
        found.forEach(row -> missing.remove(row.locationCode()));
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND,
                    "No storage location of this warehouse has the code " + listed(missing));
        }
        List<String> offLayout = found.stream().filter(Predicate.not(LocationLabelServiceImpl::onLayout))
                .map(LabelRow::locationCode).toList();
        if (!offLayout.isEmpty()) {
            throw new BusinessException(ErrorCode.LOCATION_INACTIVE,
                    "These locations are off the layout and get no label: " + listed(offLayout));
        }
    }

    /**
     * Safe to put in a message: a requested code passed the request's {@code [A-Za-z0-9-]} pattern
     * and was upper-cased, so it cannot carry markup or a forged log line.
     */
    private static String listed(Collection<String> codes) {
        List<String> sorted = codes.stream().sorted().toList();
        String shown = String.join(", ", sorted.subList(0, Math.min(LISTED_CODES, sorted.size())));
        return sorted.size() > LISTED_CODES ? shown + " and " + (sorted.size() - LISTED_CODES) + " more" : shown;
    }

    private static boolean onLayout(LabelRow row) {
        return EffectiveStatus.isOnLayout(row.shelfStatus(), row.status());
    }

    private static LocationLabel toLabel(LabelRow row) {
        return new LocationLabel(row.locationCode(), row.kind(), row.shelfCode(), row.levelIndex(), row.binCode(),
                row.areaName(), row.storageClass());
    }
}
