package com.stockflow.warehouse.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ConflictException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.warehouse.internal.domain.CodePart;
import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.domain.LocationStatus;
import com.stockflow.warehouse.internal.domain.Placement;
import com.stockflow.warehouse.internal.domain.Shelf;
import com.stockflow.warehouse.internal.domain.ShelfRepository;
import com.stockflow.warehouse.internal.domain.Warehouse;
import com.stockflow.warehouse.internal.domain.WarehouseRepository;
import com.stockflow.warehouse.internal.domain.ZoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The transaction boundary for shelves.
 *
 * <p><b>Why the geometry rules have no database twin.</b> CLAUDE.md asks for every invariant twice,
 * aggregate and constraint. BR-06 (inside the map) and BR-07 (no overlap) are the deliberate
 * exception: they span tables - a shelf against the warehouse frame, against other shelves and
 * against areas - and a PostgreSQL exclusion constraint covers one table only, and its {@code &&}
 * would count shelves standing back to back as overlapping. The second line of defence is the
 * warehouse row lock taken first in every write here: two people placing two shelves on the same
 * spot are serialised, and the second one reads the first one's shelf before deciding
 * (issue #18 D4).</p>
 */
@Service
@Transactional
class ShelfLayoutServiceImpl implements ShelfLayoutService {

    private final ShelfRepository shelves;
    private final WarehouseRepository warehouses;
    private final ZoneRepository zones;

    ShelfLayoutServiceImpl(ShelfRepository shelves, WarehouseRepository warehouses, ZoneRepository zones) {
        this.shelves = shelves;
        this.warehouses = warehouses;
        this.zones = zones;
    }

    @Override
    public ShelfSummary createShelf(ShelfCommands.CreateShelf command) {
        Warehouse warehouse = warehouses.findByIdForUpdate(command.warehouseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND,
                        "Warehouse " + command.warehouseId() + " not found"));
        requireZoneOf(command.zoneId(), warehouse.id());
        String code = CodePart.of(command.code(), CodePart.CODE_MAX_LENGTH).value();
        if (shelves.existsByWarehouseIdAndCode(warehouse.id(), code)) {
            throw new ConflictException(ErrorCode.SHELF_CODE_ALREADY_EXISTS,
                    "Warehouse %s already has shelf %s".formatted(warehouse.prefix(), code));
        }
        Shelf shelf = Shelf.create(Identifiers.newId(), warehouse.id(), command.zoneId(), code, command.name(),
                command.description(), command.footprint(), command.obstacle(), command.pickFaces(),
                command.defaultStorageClass());
        requirePlaceable(warehouse, shelf.id(), shelf.footprint());
        return ShelfSummary.of(shelves.save(shelf));
    }

    @Override
    public ShelfSummary updateShelf(ShelfCommands.UpdateShelf command) {
        Warehouse warehouse = lockWarehouseOf(command.shelfId());
        Shelf shelf = require(command.shelfId());
        requireZoneOf(command.zoneId(), warehouse.id());
        shelf.update(command.zoneId(), command.name(), command.description(), command.footprint(),
                command.obstacle(), command.pickFaces(), command.defaultStorageClass(), command.expectedVersion());
        if (shelf.status() != LocationStatus.INACTIVE) {
            requirePlaceable(warehouse, shelf.id(), shelf.footprint());
        }
        return ShelfSummary.of(shelves.save(shelf));
    }

    @Override
    public ShelfSummary changeShelfStatus(UUID shelfId, LocationStatus status) {
        Warehouse warehouse = lockWarehouseOf(shelfId);
        Shelf shelf = require(shelfId);
        if (shelf.status() == LocationStatus.INACTIVE && status != null && status != LocationStatus.INACTIVE) {
            requirePlaceable(warehouse, shelf.id(), shelf.footprint());
        }
        shelf.changeStatus(status);
        return ShelfSummary.of(shelves.save(shelf));
    }

    @Override
    @Transactional(readOnly = true)
    public ShelfSummary getShelf(UUID shelfId) {
        return ShelfSummary.of(require(shelfId));
    }

    @Override
    public ShelfSummary.Level addLevel(ShelfCommands.AddLevel command) {
        lockWarehouseOf(command.shelfId());
        Shelf shelf = require(command.shelfId());
        UUID levelId = shelf.addLevel(Identifiers.newId(), command.levelIndex(), command.measures()).id();
        return ShelfSummary.Level.of(shelves.save(shelf).level(levelId));
    }

    @Override
    public ShelfSummary.Level updateLevel(ShelfCommands.UpdateLevel command) {
        lockWarehouseOf(command.shelfId());
        Shelf shelf = require(command.shelfId());
        shelf.updateLevel(command.levelId(), command.measures());
        return ShelfSummary.Level.of(shelves.save(shelf).level(command.levelId()));
    }

    @Override
    public ShelfSummary.BinEntry addBin(ShelfCommands.AddBin command) {
        Warehouse warehouse = lockWarehouseOf(command.shelfId());
        Shelf shelf = require(command.shelfId());
        UUID binId = shelf.addBin(command.levelId(), Identifiers.newId(), Identifiers.newId(), command.code(),
                command.details(), command.settings(), warehouse.prefix()).id();
        return ShelfSummary.BinEntry.of(shelves.save(shelf).level(command.levelId()).bin(binId));
    }

    @Override
    public ShelfSummary.BinEntry updateBin(ShelfCommands.UpdateBin command) {
        lockWarehouseOf(command.shelfId());
        Shelf shelf = require(command.shelfId());
        shelf.updateBin(command.levelId(), command.binId(), command.details(), command.settings());
        return ShelfSummary.BinEntry.of(shelves.save(shelf).level(command.levelId()).bin(command.binId()));
    }

    @Override
    public ShelfSummary.BinEntry changeBinStatus(UUID shelfId, UUID levelId, UUID binId, LocationStatus status) {
        lockWarehouseOf(shelfId);
        Shelf shelf = require(shelfId);
        shelf.changeBinStatus(levelId, binId, status);
        return ShelfSummary.BinEntry.of(shelves.save(shelf).level(levelId).bin(binId));
    }

    /**
     * Up to {@value com.stockflow.warehouse.internal.domain.BinGrid#MAX_BINS_PER_LEVEL} bins and as many
     * locations a level, inserted in one flush: {@code hibernate.jdbc.batch_size} and
     * {@code order_inserts} send them in batches, locations ahead of bins.
     */
    @Override
    public List<ShelfSummary.Level> generateBins(ShelfCommands.GenerateBins command) {
        Warehouse warehouse = lockWarehouseOf(command.shelfId());
        Shelf shelf = require(command.shelfId());
        shelf.generateBins(command.levelIds(), command.rows(), command.columns(), command.scheme(),
                command.defaults(), warehouse.prefix(), Identifiers::newId);
        Set<UUID> filled = Set.copyOf(command.levelIds());
        return shelves.save(shelf).levels().stream()
                .filter(level -> filled.contains(level.id()))
                .map(ShelfSummary.Level::of)
                .toList();
    }

    /** BR-06 against the frame, BR-07 against every shelf and area on the layout but this one. */
    private void requirePlaceable(Warehouse warehouse, UUID self, Footprint footprint) {
        warehouse.requireOnMap(footprint);
        Placement.requireClear(footprint, self, warehouses.findPlacements(warehouse.id()));
    }

    /**
     * Looks the warehouse up without reading the shelf, locks it, and only then lets the caller
     * read the tree: a tree read before the lock could be stale by the time the lock is granted.
     */
    private Warehouse lockWarehouseOf(UUID shelfId) {
        UUID warehouseId = shelves.findWarehouseIdOf(shelfId).orElseThrow(() -> shelfNotFound(shelfId));
        return warehouses.findByIdForUpdate(warehouseId).orElseThrow(() -> shelfNotFound(shelfId));
    }

    /** A zone of another warehouse is as good as no zone at all: 404, the zone id means nothing here. */
    private void requireZoneOf(UUID zoneId, UUID warehouseId) {
        if (zoneId == null) {
            return;
        }
        zones.findById(zoneId)
                .filter(zone -> zone.warehouseId().equals(warehouseId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ZONE_NOT_FOUND,
                        "Zone " + zoneId + " is not in this warehouse"));
    }

    private Shelf require(UUID shelfId) {
        return shelves.findById(shelfId).orElseThrow(() -> shelfNotFound(shelfId));
    }

    private static BusinessException shelfNotFound(UUID shelfId) {
        return new BusinessException(ErrorCode.SHELF_NOT_FOUND, "Shelf " + shelfId + " not found");
    }
}
