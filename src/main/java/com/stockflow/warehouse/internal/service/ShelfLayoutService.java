package com.stockflow.warehouse.internal.service;

import com.stockflow.warehouse.internal.domain.LocationStatus;

import java.util.List;
import java.util.UUID;

/**
 * Shelves of a warehouse map: the shelf, its levels, its bins and their storage locations.
 *
 * <p>A separate interface from {@link WarehouseLayoutService}: the two have different callers
 * (one controller each) and nothing in common but the warehouse lock, which lives in the
 * repository, not here.</p>
 *
 * <p>Every write takes the warehouse row lock first (issue #18 D4). For the shelf itself that is
 * what makes BR-06/BR-07 safe; for levels and bins it serialises writers on one tree, whose
 * version would not move when only a child row changes.</p>
 */
public interface ShelfLayoutService {

    /** Inside the map (BR-06), clear of every shelf and area still on the layout (BR-07). */
    ShelfSummary createShelf(ShelfCommands.CreateShelf command);

    /** Moving, turning or resizing re-checks BR-06/07, and every bin must still fit. */
    ShelfSummary updateShelf(ShelfCommands.UpdateShelf command);

    /** Back from {@code INACTIVE} needs its place on the map free again (issue #18 D3). */
    ShelfSummary changeShelfStatus(UUID shelfId, LocationStatus status);

    ShelfSummary getShelf(UUID shelfId);

    ShelfSummary.Level addLevel(ShelfCommands.AddLevel command);

    ShelfSummary.Level updateLevel(ShelfCommands.UpdateLevel command);

    ShelfSummary.BinEntry addBin(ShelfCommands.AddBin command);

    ShelfSummary.BinEntry updateBin(ShelfCommands.UpdateBin command);

    ShelfSummary.BinEntry changeBinStatus(UUID shelfId, UUID levelId, UUID binId, LocationStatus status);

    /**
     * Fills empty levels with a grid of equal bins (BR-14) - all the chosen levels or none.
     *
     * @return the levels filled, in level order, each holding exactly the bins just generated
     */
    List<ShelfSummary.Level> generateBins(ShelfCommands.GenerateBins command);
}
