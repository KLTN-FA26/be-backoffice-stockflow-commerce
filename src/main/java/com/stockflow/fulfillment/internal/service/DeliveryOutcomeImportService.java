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
import com.stockflow.common.storage.Checksums;
import com.stockflow.fulfillment.internal.domain.CarrierEventResult;
import com.stockflow.fulfillment.internal.domain.DeliveryOutcomeCheck;
import com.stockflow.fulfillment.internal.domain.DeliveryOutcomeFile;
import com.stockflow.fulfillment.internal.domain.ImportCsv;
import com.stockflow.fulfillment.internal.entity.DeliveryOutcomeImportJpaEntity;
import com.stockflow.fulfillment.internal.entity.DeliveryOutcomeImportRowJpaEntity;
import com.stockflow.fulfillment.internal.entity.CarrierJpaEntity;
import com.stockflow.fulfillment.internal.entity.DispatchLoadJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import com.stockflow.fulfillment.internal.repository.DeliveryOutcomeImportJpaRepository;
import com.stockflow.fulfillment.internal.repository.DeliveryOutcomeImportRowJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * See {@link DeliveryOutcomeImports}.
 *
 * <p>Applying a file holds no transaction of its own. The file and its lines are recorded first, in one short
 * transaction; then each accepted line goes through {@link CarrierEvents#recordFromFile} - the webhooks' pipeline, a
 * few short transactions per line under the load's carrier-event lock. One transaction for the whole file would hold
 * every load's lock for as long as a thousand lines take, and would undo the lines already applied when a late one
 * failed; a line is a report of its own, as a webhook call is. A process that dies half-way leaves the later lines
 * accepted with no event: applying the file again makes them, and repeats the earlier ones, which changes nothing.</p>
 */
@Service
class DeliveryOutcomeImportService implements DeliveryOutcomeImports {

    private static final Logger log = LoggerFactory.getLogger(DeliveryOutcomeImportService.class);
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "uploadedAt", "id");

    private final DispatchLoadJpaRepository loads;
    private final ShipmentJpaRepository shipments;
    private final CarrierJpaRepository carriers;
    private final DeliveryOutcomeImportJpaRepository imports;
    private final DeliveryOutcomeImportRowJpaRepository importRows;
    private final CarrierEvents events;
    private final Messages messages;
    private final TransactionOperations tx;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    DeliveryOutcomeImportService(DispatchLoadJpaRepository loads, ShipmentJpaRepository shipments,
                                 CarrierJpaRepository carriers, DeliveryOutcomeImportJpaRepository imports,
                                 DeliveryOutcomeImportRowJpaRepository importRows, CarrierEvents events,
                                 Messages messages, TransactionOperations tx, Clock clock) {
        this.loads = loads;
        this.shipments = shipments;
        this.carriers = carriers;
        this.imports = imports;
        this.importRows = importRows;
        this.events = events;
        this.messages = messages;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public byte[] template() {
        return DeliveryOutcomeFile.template(List.of(
                messages.get("fulfillment.delivery-outcome-import.waybillNo", "Waybill number"),
                messages.get("fulfillment.delivery-outcome-import.outcome", "Outcome (DELIVERED / DELIVERY_FAILED)"),
                messages.get("fulfillment.delivery-outcome-import.failureReason", "Failure reason"),
                messages.get("fulfillment.delivery-outcome-import.reportedAt", "Time (2026-11-09 10:15)"),
                messages.get("fulfillment.delivery-outcome-import.recipient", "Received by"),
                messages.get("fulfillment.delivery-outcome-import.note", "Note"),
                messages.get("fulfillment.delivery-outcome-import.shipmentCode",
                        "Shipment code (blank: the whole vehicle)"),
                messages.get("fulfillment.delivery-outcome-import.carrierCode",
                        "Carrier code (when two carriers share the number)")));
    }

    @Override
    @Transactional(readOnly = true)
    public Preview preview(byte[] file) {
        List<DeliveryOutcomeFile.Row> rows = parse(file);
        List<DeliveryOutcomeCheck.Verdict> verdicts = check(rows);
        int accepted = (int) verdicts.stream().filter(DeliveryOutcomeCheck.Verdict::accepted).count();
        return new Preview(rows.size(), accepted, rows.size() - accepted,
                verdicts.stream().map(v -> line(v, null)).toList());
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "delivery-outcome-import", resourceId = "#result?.id()")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ImportView importFile(byte[] file, String fileName, CurrentUser actor) {
        if (actor == null || actor.userId() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        List<DeliveryOutcomeFile.Row> rows = parse(file);
        String name = fileName == null || fileName.isBlank() ? "delivery-outcomes.csv" : fileName.strip();
        List<DeliveryOutcomeCheck.Verdict> verdicts = Objects.requireNonNull(tx.execute(s -> check(rows)));
        int accepted = (int) verdicts.stream().filter(DeliveryOutcomeCheck.Verdict::accepted).count();
        UUID importId = Identifiers.newId();
        Instant now = clock.instant();
        DeliveryOutcomeImportJpaEntity record = Objects.requireNonNull(tx.execute(s -> {
            DeliveryOutcomeImportJpaEntity saved = imports.saveAndFlush(new DeliveryOutcomeImportJpaEntity(importId,
                    name, Checksums.sha256Hex(file), actor.userId(), now, rows.size(), accepted,
                    rows.size() - accepted));
            importRows.saveAll(verdicts.stream().map(v -> new DeliveryOutcomeImportRowJpaEntity(Identifiers.newId(),
                    importId, v.row().line(), v.row().waybillNo(), v.row().outcome(), v.row().failureReason(),
                    v.row().reportedAt(), v.row().recipient(), v.row().note(), v.row().shipmentCode(),
                    v.row().carrierCode(), v.load() == null ? null : v.load().loadId(),
                    v.problem() == null ? null : v.problem().name()))
                    .toList());
            importRows.flush();
            return saved;
        }));
        // Each accepted line is a report of its own, in the file's order; nothing is held between two of them.
        Map<Integer, CarrierEvents.FileOutcome> outcomes = new HashMap<>();
        for (DeliveryOutcomeCheck.Verdict v : verdicts) {
            if (v.accepted()) {
                outcomes.put(v.row().line(), events.recordFromFile(new CarrierEvents.FileLine(importId,
                        v.row().line(), v.load().carrierId(), v.load().loadId(), v.row().waybillNo(),
                        v.row().shipmentCode(), v.kind(), v.reportedAt(), v.reason(), v.wording(),
                        v.row().recipient(), v.row().note())));
            }
        }
        ImportView view = view(record, verdicts.stream().map(v -> line(v, outcomes.get(v.row().line()))).toList());
        log.info("Delivery-outcome import {}: {} of {} line(s) accepted, {} applied", importId, accepted,
                rows.size(), view.rowsApplied());
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ImportRow> list(int page, int size, CurrentUser actor) {
        var found = WarehouseScope.restriction().isEmpty() ? imports.findAll(Pages.of(page, size, NEWEST_FIRST))
                : imports.findByUploadedBy(actor.userId(), Pages.of(page, size, NEWEST_FIRST));
        return Pages.toResponse(found, i -> new ImportRow(i.getId(), i.getFileName(), i.getUploadedBy(),
                i.getUploadedAt(), i.getRowsTotal(), i.getRowsAccepted(), i.getRowsRejected()));
    }

    @Override
    @Transactional(readOnly = true)
    public ImportView get(UUID importId, CurrentUser actor) {
        DeliveryOutcomeImportJpaEntity record = imports.findById(importId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DELIVERY_OUTCOME_IMPORT_NOT_FOUND));
        // A file may hold lines of several warehouses: someone limited to some of them reads only their own imports.
        if (WarehouseScope.restriction().isPresent() && !record.getUploadedBy().equals(actor.userId())) {
            throw new BusinessException(ErrorCode.DELIVERY_OUTCOME_IMPORT_NOT_FOUND);
        }
        List<DeliveryOutcomeImportRowJpaEntity> rows = importRows.findByImportIdOrderByLineNoAsc(importId);
        Set<UUID> loadIds = rows.stream().map(DeliveryOutcomeImportRowJpaEntity::getLoadId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> loadCodes = loadIds.isEmpty() ? Map.of() : loads.findByIdIn(loadIds).stream()
                .collect(Collectors.toMap(DispatchLoadJpaEntity::getId, DispatchLoadJpaEntity::getCode));
        Map<Integer, CarrierEvents.FileOutcome> outcomes = events.outcomesOfImport(importId);
        return view(record, rows.stream().map(r -> {
            CarrierEvents.FileOutcome outcome = outcomes.get(r.getLineNo());
            return new Line(r.getLineNo(), r.getWaybillNo(), r.getOutcome(), r.getFailureReason(), r.getReportedAt(),
                    r.getRecipient(), r.getNote(), r.getShipmentCode(), r.getCarrierCode(), r.getLoadId(),
                    loadCodes.get(r.getLoadId()),
                    r.getVerdict(), null, r.getProblem(), outcome == null ? null : outcome.eventId(),
                    outcome == null ? null : outcome.result(), outcome == null ? null : outcome.note());
        }).toList());
    }

    // ------------------------------------------------------------------ internals

    private static List<DeliveryOutcomeFile.Row> parse(byte[] file) {
        try {
            return DeliveryOutcomeFile.parse(file);
        } catch (ImportCsv.Malformed malformed) {
            throw new BusinessException(ErrorCode.DELIVERY_OUTCOME_IMPORT_INVALID_FILE, malformed.getMessage());
        }
    }

    /**
     * The lines checked against the vehicles holding their waybill numbers. A vehicle of a warehouse the importer is
     * not assigned to is left out, so its number reads as one no vehicle has: the check tells nothing about it.
     */
    private List<DeliveryOutcomeCheck.Verdict> check(List<DeliveryOutcomeFile.Row> rows) {
        Set<String> numbers = rows.stream().map(DeliveryOutcomeFile.Row::waybillNo).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Optional<AssignedWarehouses> restriction = WarehouseScope.restriction();
        List<DispatchLoadJpaRepository.LoadFacts> found = numbers.isEmpty() ? List.of()
                : loads.findFactsByWaybills(numbers).stream()
                        .filter(l -> restriction.isEmpty() || restriction.get().ids().contains(l.getWarehouseId()))
                        .toList();
        Map<UUID, List<DeliveryOutcomeCheck.ShipmentFacts>> onLoad = new HashMap<>();
        if (!found.isEmpty()) {
            shipments.statesOnLoads(found.stream().map(DispatchLoadJpaRepository.LoadFacts::getId).toList())
                    .forEach(s -> onLoad.computeIfAbsent(s.getLoadId(), id -> new ArrayList<>())
                            .add(new DeliveryOutcomeCheck.ShipmentFacts(s.getId(), s.getCode(), s.getStatus())));
        }
        Map<UUID, String> carrierCodes = found.isEmpty() ? Map.of() : carriers.findAllById(found.stream()
                        .map(DispatchLoadJpaRepository.LoadFacts::getCarrierId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(CarrierJpaEntity::getId, CarrierJpaEntity::getCode));
        Map<String, List<DeliveryOutcomeCheck.LoadFacts>> byWaybill = new HashMap<>();
        for (DispatchLoadJpaRepository.LoadFacts load : found) {
            byWaybill.computeIfAbsent(load.getWaybillNo(), number -> new ArrayList<>())
                    .add(new DeliveryOutcomeCheck.LoadFacts(load.getId(), load.getCode(), load.getCarrierId(),
                            carrierCodes.get(load.getCarrierId()), load.getStatus(), load.getDispatchedAt(),
                            onLoad.getOrDefault(load.getId(), List.of())));
        }
        return DeliveryOutcomeCheck.check(rows, byWaybill, clock.instant());
    }

    private static Line line(DeliveryOutcomeCheck.Verdict v, CarrierEvents.FileOutcome outcome) {
        DeliveryOutcomeFile.Row r = v.row();
        return new Line(r.line(), r.waybillNo(), r.outcome(), r.failureReason(), r.reportedAt(), r.recipient(),
                r.note(), r.shipmentCode(), r.carrierCode(), v.load() == null ? null : v.load().loadId(),
                v.load() == null ? null : v.load().code(),
                v.accepted() ? DeliveryOutcomeImportRowJpaEntity.ACCEPTED : DeliveryOutcomeImportRowJpaEntity.ERROR,
                v.accepted() ? v.changes() : null, v.problem() == null ? null : v.problem().name(),
                outcome == null ? null : outcome.eventId(), outcome == null ? null : outcome.result(),
                outcome == null ? null : outcome.note());
    }

    private static ImportView view(DeliveryOutcomeImportJpaEntity i, List<Line> lines) {
        int applied = (int) lines.stream().filter(l -> CarrierEventResult.APPLIED.name().equals(l.result())).count();
        return new ImportView(i.getId(), i.getFileName(), i.getUploadedBy(), i.getUploadedAt(), i.getRowsTotal(),
                i.getRowsAccepted(), i.getRowsRejected(), applied, lines);
    }
}
