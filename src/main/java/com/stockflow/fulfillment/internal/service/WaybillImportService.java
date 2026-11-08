package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.i18n.Messages;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.AssignedWarehouses;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.common.storage.StagedUploads;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.fulfillment.internal.domain.CarrierConnectionType;
import com.stockflow.fulfillment.internal.domain.DispatchLoad;
import com.stockflow.fulfillment.internal.domain.WaybillImportCheck;
import com.stockflow.fulfillment.internal.domain.WaybillImportFile;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.entity.LoadWaybillFileJpaEntity;
import com.stockflow.fulfillment.internal.entity.WaybillImportJpaEntity;
import com.stockflow.fulfillment.internal.entity.WaybillImportRowJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadStore;
import com.stockflow.fulfillment.internal.repository.LoadWaybillFileJpaRepository;
import com.stockflow.fulfillment.internal.repository.WaybillImportJpaRepository;
import com.stockflow.fulfillment.internal.repository.WaybillImportRowJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * See {@link WaybillImports}.
 *
 * <p>An import keeps the file first, with no transaction held, then applies it in one short transaction: the loads the
 * file names in the importer's warehouses are locked in id order (the order load-plan operations lock them in, so the
 * two cannot deadlock) - a load of another warehouse is never locked by someone who cannot act on it - then the
 * advisory locks of the file's numbers (as every path giving a number takes them: a number taken meanwhile is a refused
 * line, not a refused file); the loads are read again, checked again, and the valid lines applied through the load
 * itself ({@code DispatchLoad.recordWaybill}) - the same rules as a number typed on the load's screen. One transaction, not one per line: the import record, its
 * lines, the loads' numbers, their history and their waybill files are all there or none is. A line refused here may
 * have passed the preview: the preview is a picture of a moment.</p>
 */
@Service
@Transactional
class WaybillImportService implements WaybillImports {

    private static final Logger log = LoggerFactory.getLogger(WaybillImportService.class);
    private static final String CSV = "text/csv";

    private final DispatchLoadJpaRepository loads;
    private final DispatchLoadStore store;
    private final CarrierJpaRepository carriers;
    private final WaybillImportJpaRepository imports;
    private final WaybillImportRowJpaRepository importRows;
    private final LoadWaybillFileJpaRepository waybillFiles;
    private final Waybills waybills;
    private final FileTransfers transfers;
    private final StagedUploads staged;
    private final Messages messages;
    private final TransactionOperations tx;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    WaybillImportService(DispatchLoadJpaRepository loads, DispatchLoadStore store, CarrierJpaRepository carriers,
                         WaybillImportJpaRepository imports, WaybillImportRowJpaRepository importRows,
                         LoadWaybillFileJpaRepository waybillFiles, Waybills waybills, FileTransfers transfers,
                         StagedUploads staged, Messages messages, TransactionOperations tx, Clock clock) {
        this.loads = loads;
        this.store = store;
        this.carriers = carriers;
        this.imports = imports;
        this.importRows = importRows;
        this.waybillFiles = waybillFiles;
        this.waybills = waybills;
        this.transfers = transfers;
        this.staged = staged;
        this.messages = messages;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] template() {
        return WaybillImportFile.template(List.of(
                messages.get("fulfillment.waybill-import.loadCode", "Load code"),
                messages.get("fulfillment.waybill-import.waybillNo", "Waybill number"),
                messages.get("fulfillment.waybill-import.vehiclePlate", "Vehicle plate"),
                messages.get("fulfillment.waybill-import.driverName", "Driver name"),
                messages.get("fulfillment.waybill-import.driverPhone", "Driver phone")));
    }

    @Override
    @Transactional(readOnly = true)
    public Preview preview(byte[] file, CurrentUser actor) {
        List<WaybillImportFile.Row> rows = parse(file);
        Set<String> codes = codes(rows);
        List<WaybillImportCheck.Verdict> verdicts = check(rows, codes.isEmpty() ? List.of()
                : loads.findByCodeIn(codes), null);
        int applicable = (int) verdicts.stream().filter(WaybillImportCheck.Verdict::applies).count();
        return new Preview(rows.size(), applicable, rows.size() - applicable,
                verdicts.stream().map(WaybillImportService::line).toList());
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "waybill-import", resourceId = "#result?.id()")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ImportView importFile(byte[] file, String fileName, Set<Integer> overwriteLines, CurrentUser actor) {
        if (actor == null || actor.userId() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        List<WaybillImportFile.Row> rows = parse(file);
        String name = fileName == null || fileName.isBlank() ? "waybills.csv" : fileName.strip();
        Set<String> codes = codes(rows);
        Optional<AssignedWarehouses> restriction = WarehouseScope.restriction();
        // A load never changes warehouse, so which warehouse each line is of can be read before the transaction.
        List<DispatchLoadJpaRepository.LoadPlace> places = tx.execute(s -> codes.isEmpty() ? List.of()
                : loads.placesOfCodes(codes));
        Map<String, UUID> warehouseOf = places.stream().filter(pl -> visible(restriction, pl.getWarehouseId()))
                .collect(Collectors.toMap(DispatchLoadJpaRepository.LoadPlace::getCode,
                        DispatchLoadJpaRepository.LoadPlace::getWarehouseId));
        // The file itself goes with the import; a load gets it whole only if every line is of the load's warehouse,
        // otherwise the extract of its warehouse's lines - no load shows another warehouse's lines.
        Set<UUID> warehouses = new java.util.LinkedHashSet<>(warehouseOf.values());
        boolean oneWarehouse = warehouses.size() == 1
                && rows.stream().allMatch(r -> r.loadCode() != null && warehouseOf.containsKey(r.loadCode()));
        List<FileUpload> uploads = new ArrayList<>();
        // The file was read as CSV text above, whatever type the browser declared (Windows says vnd.ms-excel).
        uploads.add(new FileUpload(name, CSV, file.length, new ByteArrayInputStream(file)));
        List<UUID> extracted = new ArrayList<>();
        if (!oneWarehouse) {
            for (UUID warehouse : warehouses) {
                Set<Integer> lines = rows.stream().filter(r -> r.loadCode() != null
                        && warehouse.equals(warehouseOf.get(r.loadCode()))).map(WaybillImportFile.Row::line)
                        .collect(Collectors.toSet());
                byte[] extract = WaybillImportFile.extract(file, lines);
                uploads.add(new FileUpload(name, CSV, extract.length, new ByteArrayInputStream(extract)));
                extracted.add(warehouse);
            }
        }
        try (PreparedFiles prepared = IncomingFiles.sent(uploads).prepare(FileCategory.WAYBILL_IMPORT, transfers,
                staged)) {
            StoredFile original = prepared.files().get(0).file();
            Map<UUID, StoredFile> fileOf = new HashMap<>();
            for (int i = 0; i < extracted.size(); i++) {
                fileOf.put(extracted.get(i), prepared.files().get(i + 1).file());
            }
            for (UUID warehouse : warehouses) {
                fileOf.putIfAbsent(warehouse, original);
            }
            Set<Integer> confirmed = overwriteLines == null ? Set.of() : Set.copyOf(overwriteLines);
            ImportView view = tx.execute(status -> {
                ImportView applied = apply(rows, places, restriction, original, fileOf, name, confirmed, actor);
                prepared.adopt();
                return applied;
            });
            log.info("Waybill import {}: {} of {} line(s) applied", view.id(), view.rowsApplied(), view.rowsTotal());
            return view;
        }
    }

    /**
     * Under the loads' locks - the importer's loads only, in id order - and the advisory locks of the file's numbers:
     * check again, apply the valid lines, record the import.
     */
    private ImportView apply(List<WaybillImportFile.Row> rows, List<DispatchLoadJpaRepository.LoadPlace> places,
                             Optional<AssignedWarehouses> restriction, StoredFile original,
                             Map<UUID, StoredFile> fileOf, String name, Set<Integer> confirmed, CurrentUser actor) {
        List<DispatchLoadJpaEntity> locked = new ArrayList<>();
        places.stream().filter(pl -> visible(restriction, pl.getWarehouseId()))
                .map(DispatchLoadJpaRepository.LoadPlace::getId).sorted()
                .forEach(id -> loads.lockById(id).ifPresent(locked::add));
        Map<UUID, DispatchLoadJpaEntity> byId = locked.stream()
                .collect(Collectors.toMap(DispatchLoadJpaEntity::getId, Function.identity()));
        Map<String, DispatchLoadJpaEntity> byCode = locked.stream()
                .collect(Collectors.toMap(DispatchLoadJpaEntity::getCode, Function.identity()));
        List<Waybills.Number> numbers = new ArrayList<>();
        for (WaybillImportFile.Row row : rows) {
            DispatchLoadJpaEntity load = row.loadCode() == null ? null : byCode.get(row.loadCode());
            if (load != null && load.getCarrierId() != null && row.waybillNo() != null) {
                numbers.add(new Waybills.Number(load.getCarrierId(), row.waybillNo()));
            }
        }
        waybills.lock(numbers);
        List<WaybillImportCheck.Verdict> verdicts = check(rows, locked, confirmed);
        Instant now = clock.instant();
        UUID importId = Identifiers.newId();
        int applied = 0;
        List<LoadWaybillFileJpaEntity> files = new ArrayList<>();
        for (WaybillImportCheck.Verdict verdict : verdicts) {
            if (!verdict.applies()) {
                continue;
            }
            applied++;
            DispatchLoadJpaEntity header = byId.get(verdict.load().loadId());
            DispatchLoad load = store.load(header);
            String before = load.waybillNo();
            WaybillImportFile.Row row = verdict.row();
            if (load.recordWaybill(row.waybillNo(), row.vehiclePlate(), row.driverName(), row.driverPhone(), null)) {
                waybills.saving(() -> store.save(load, header));
                waybills.record(load.id(), before, load.waybillNo(), WaybillSource.FILE, actor.userId(), now,
                        "File " + name + ", line " + row.line());
                // Kept with a load the file changed: a line that changed nothing adds no copy.
                StoredFile kept = fileOf.getOrDefault(header.getWarehouseId(), original);
                files.add(new LoadWaybillFileJpaEntity(Identifiers.newId(), load.id(), kept.key(), name, CSV,
                        kept.sizeBytes(), actor.userId(), now, importId));
            }
        }
        WaybillImportJpaEntity record = imports.saveAndFlush(new WaybillImportJpaEntity(importId, name,
                original.key(), actor.userId(), now, rows.size(), applied, rows.size() - applied));
        importRows.saveAll(verdicts.stream().map(v -> new WaybillImportRowJpaEntity(Identifiers.newId(), importId,
                v.row().line(), v.row().loadCode(), v.row().waybillNo(), v.row().vehiclePlate(), v.row().driverName(),
                v.row().driverPhone(), v.load() == null ? null : v.load().loadId(), v.outcome().name(),
                v.problem() == null ? null : v.problem().name())).toList());
        importRows.flush();
        waybillFiles.saveAllAndFlush(files);
        return view(record, verdicts.stream().map(WaybillImportService::line).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ImportRow> list(int page, int size, CurrentUser actor) {
        Optional<AssignedWarehouses> restriction = WarehouseScope.restriction();
        var found = restriction.isEmpty()
                ? imports.findAll(Pages.of(page, size, Sort.by(Sort.Direction.DESC, "uploadedAt", "id")))
                : imports.visibleTo(actor.userId(), nonEmpty(restriction.get().ids()), Pages.of(page, size));
        return Pages.toResponse(found, i -> new ImportRow(i.getId(), i.getFileName(), i.getUploadedBy(),
                i.getUploadedAt(), i.getRowsTotal(), i.getRowsApplied(), i.getRowsRejected()));
    }

    @Override
    @Transactional(readOnly = true)
    public ImportView get(UUID importId, CurrentUser actor) {
        WaybillImportJpaEntity record = imports.findById(importId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WAYBILL_IMPORT_NOT_FOUND));
        List<WaybillImportRowJpaEntity> rows = importRows.findByImportIdOrderByLineNoAsc(importId);
        Optional<AssignedWarehouses> restriction = WarehouseScope.restriction();
        boolean own = record.getUploadedBy().equals(actor.userId());
        if (restriction.isPresent() && !own) {
            // Another's import: only its lines on loads of the caller's warehouses, and only if it has one.
            Set<UUID> mine = loads.findAllById(rows.stream().map(WaybillImportRowJpaEntity::getLoadId)
                            .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                    .filter(l -> restriction.get().ids().contains(l.getWarehouseId()))
                    .map(DispatchLoadJpaEntity::getId).collect(Collectors.toSet());
            rows = rows.stream().filter(r -> r.getLoadId() != null && mine.contains(r.getLoadId())).toList();
            if (rows.isEmpty()) {
                throw new BusinessException(ErrorCode.WAYBILL_IMPORT_NOT_FOUND);
            }
        }
        return view(record, rows.stream().map(r -> new Line(r.getLineNo(), r.getLoadCode(), r.getWaybillNo(),
                r.getVehiclePlate(), r.getDriverName(), r.getDriverPhone(), r.getLoadId(), r.getOutcome(),
                r.getProblem(), null)).toList());
    }

    // ------------------------------------------------------------------ internals

    private static List<WaybillImportFile.Row> parse(byte[] file) {
        try {
            return WaybillImportFile.parse(file);
        } catch (WaybillImportFile.Malformed malformed) {
            throw new BusinessException(ErrorCode.WAYBILL_IMPORT_INVALID_FILE, malformed.getMessage());
        }
    }

    private static Set<String> codes(List<WaybillImportFile.Row> rows) {
        return rows.stream().map(WaybillImportFile.Row::loadCode).filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * The lines checked against {@code found} - the loads the file names - and the numbers live loads hold; a holder of
     * another warehouse is not named.
     */
    private List<WaybillImportCheck.Verdict> check(List<WaybillImportFile.Row> rows,
                                                   Collection<DispatchLoadJpaEntity> found, Set<Integer> confirmed) {
        Optional<AssignedWarehouses> restriction = WarehouseScope.restriction();
        Map<UUID, CarrierJpaEntity> carrierById = found.stream().map(DispatchLoadJpaEntity::getCarrierId)
                .filter(Objects::nonNull).distinct().collect(Collectors.collectingAndThen(Collectors.toSet(),
                        ids -> ids.isEmpty() ? Map.<UUID, CarrierJpaEntity>of() : carriers.findAllById(ids).stream()
                                .collect(Collectors.toMap(CarrierJpaEntity::getId, Function.identity()))));
        Map<String, WaybillImportCheck.LoadFacts> facts = new HashMap<>();
        for (DispatchLoadJpaEntity l : found) {
            CarrierJpaEntity carrier = l.getCarrierId() == null ? null : carrierById.get(l.getCarrierId());
            facts.put(l.getCode(), new WaybillImportCheck.LoadFacts(l.getId(), l.getCode(), l.getStatus(),
                    l.getCarrierId(), carrier != null
                    && CarrierConnectionType.API_BOOKING.name().equals(carrier.getConnectionType()),
                    l.getWaybillNo(), visible(restriction, l.getWarehouseId())));
        }
        Set<String> numbers = rows.stream().map(WaybillImportFile.Row::waybillNo).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, Map<String, WaybillImportCheck.Holder>> taken = new HashMap<>();
        if (!numbers.isEmpty() && !carrierById.isEmpty()) {
            for (DispatchLoadJpaEntity holder : loads.findLiveByCarriersAndWaybills(carrierById.keySet(), numbers)) {
                taken.computeIfAbsent(holder.getCarrierId(), c -> new HashMap<>()).put(holder.getWaybillNo(),
                        new WaybillImportCheck.Holder(holder.getCode(), visible(restriction, holder.getWarehouseId())));
            }
        }
        return WaybillImportCheck.check(rows, facts, taken, confirmed);
    }

    private static Line line(WaybillImportCheck.Verdict v) {
        WaybillImportFile.Row r = v.row();
        return new Line(r.line(), r.loadCode(), r.waybillNo(), r.vehiclePlate(), r.driverName(), r.driverPhone(),
                v.load() == null ? null : v.load().loadId(), v.outcome().name(),
                v.problem() == null ? null : v.problem().name(), v.holder());
    }

    private static ImportView view(WaybillImportJpaEntity i, List<Line> lines) {
        return new ImportView(i.getId(), i.getFileName(), i.getUploadedBy(), i.getUploadedAt(), i.getRowsTotal(),
                i.getRowsApplied(), i.getRowsRejected(), lines);
    }

    private static boolean visible(Optional<AssignedWarehouses> restriction, UUID warehouseId) {
        return restriction.isEmpty() || restriction.get().ids().contains(warehouseId);
    }

    /** An IN list with nothing in it is not valid SQL everywhere: an id no row has stands in. */
    private static Collection<UUID> nonEmpty(Set<UUID> ids) {
        return ids.isEmpty() ? Set.of(new UUID(0, 0)) : new HashSet<>(ids);
    }
}
