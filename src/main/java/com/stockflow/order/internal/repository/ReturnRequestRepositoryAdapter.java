package com.stockflow.order.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Specs;
import com.stockflow.order.internal.domain.ReturnDiscrepancy;
import com.stockflow.order.internal.domain.ReturnEvidence;
import com.stockflow.order.internal.domain.ReturnLine;
import com.stockflow.order.internal.domain.ReturnRequest;
import com.stockflow.order.internal.domain.ReturnRequestRepository;
import com.stockflow.order.internal.domain.ReturnStatus;
import com.stockflow.order.internal.domain.ReturnTransition;
import com.stockflow.order.internal.entity.ReturnRequestDiscrepancyJpaEntity;
import com.stockflow.order.internal.entity.ReturnRequestEvidenceJpaEntity;
import com.stockflow.order.internal.entity.ReturnRequestJpaEntity;
import com.stockflow.order.internal.entity.ReturnRequestLineJpaEntity;
import com.stockflow.order.internal.entity.ReturnRequestStatusHistoryJpaEntity;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The only class that speaks both the return's language and its tables. Look up, apply, save - as
 * {@code OrderRepositoryAdapter} does: the managed row is updated in place (never a blind {@code save} of a new
 * instance, which would merge), and the append-only children the aggregate collected since it was loaded are
 * inserted once.
 */
@Repository
class ReturnRequestRepositoryAdapter implements ReturnRequestRepository, ReturnRequestSearch {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ReturnRequestJpaRepository jpa;
    private final ReturnRequestRecordsJpaRepository records;
    private final ReturnNumberSequence sequence;
    private final EntityManager entityManager;

    ReturnRequestRepositoryAdapter(ReturnRequestJpaRepository jpa, ReturnRequestRecordsJpaRepository records,
                                   ReturnNumberSequence sequence, EntityManager entityManager) {
        this.jpa = jpa;
        this.records = records;
        this.sequence = sequence;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<ReturnRequest> findById(UUID id) {
        return jpa.findByIdWithLines(id).map(this::toDomain);
    }

    @Override
    public Optional<UUID> orderOf(UUID returnId) {
        return jpa.findOrderIdOf(returnId);
    }

    /**
     * The row lock, then the row as it is now: a copy already in the persistence context is not re-read by the locking
     * query, so the caller would decide on the state from before the wait (the reason {@code OrderRepositoryAdapter}
     * refreshes too).
     */
    @Override
    public Optional<ReturnRequest> findByIdForUpdate(UUID id) {
        return jpa.findByIdForUpdate(id)
                .map(locked -> {
                    entityManager.refresh(locked);
                    return locked;
                })
                .flatMap(locked -> jpa.findByIdWithLines(locked.getId()))
                .map(this::toDomain);
    }

    @Override
    public Optional<ReturnRequest> findByRequestId(UUID requestId) {
        return jpa.findByRequestIdWithLines(requestId).map(this::toDomain);
    }

    @Override
    public List<ReturnRequest> findByOrder(UUID orderId) {
        return jpa.findByOrderIdWithLines(orderId).stream().map(this::toDomain).toList();
    }

    @Override
    public List<UUID> awaitingReturnPast(Instant now, int limit) {
        return jpa.awaitingReturnPast(now, PageRequest.of(0, limit));
    }

    @Override
    public String nextRmaNumber(LocalDate day) {
        return "RMA-%s-%04d".formatted(DAY.format(day), sequence.nextFor(day));
    }

    @Override
    public ReturnRequest save(ReturnRequest request) {
        ReturnRequestJpaEntity entity = jpa.findByIdWithLines(request.id()).orElseGet(() -> newEntity(request));
        apply(request, entity);
        jpa.saveAndFlush(entity);
        for (ReturnEvidence photo : request.pullNewEvidence()) {
            records.insert(new ReturnRequestEvidenceJpaEntity(photo.id(), request.id(), photo.stage().name(),
                    photo.fileKey(), photo.originalName(), photo.contentType(), photo.sizeBytes(), photo.uploadedBy(),
                    photo.uploadedAt()));
        }
        for (ReturnDiscrepancy found : request.pullNewDiscrepancies()) {
            records.insert(new ReturnRequestDiscrepancyJpaEntity(found.id(), request.id(), found.lineId(),
                    found.kind().name(), found.sku(), found.quantity(), found.note(), found.recordedBy(),
                    found.recordedAt()));
        }
        // History is written here, where every save passes, so no transition can forget it (as for orders).
        for (ReturnTransition step : request.pullTransitions()) {
            records.insert(new ReturnRequestStatusHistoryJpaEntity(Identifiers.newId(), request.id(),
                    step.from() == null ? null : step.from().name(), step.to().name(), step.reason(), step.actor(),
                    step.at()));
        }
        entityManager.flush();
        return findById(request.id()).orElseThrow();
    }

    @Override
    public List<ReturnTransition> history(UUID returnRequestId) {
        return records.historyOf(returnRequestId).stream()
                .map(row -> new ReturnTransition(statusOrNull(row.getFromStatus()), statusOrNull(row.getToStatus()),
                        row.getActorId(), row.getReason(), row.getOccurredAt()))
                .toList();
    }

    @Override
    public Page<ReturnRequestRow> search(Criteria criteria, Pageable pageable) {
        Specification<ReturnRequestJpaEntity> spec = Specification.<ReturnRequestJpaEntity>where(
                        Specs.eq("customerId", criteria.customerId()))
                .and(Specs.eq("orderId", criteria.orderId()))
                .and(criteria.statuses() == null || criteria.statuses().isEmpty() ? null
                        : Specs.in("status", criteria.statuses()))
                .and(Specs.eq("vatAdjustmentRequired", criteria.vatAdjustment()))
                .and(criteria.text() == null || criteria.text().isBlank() ? null
                        : Specification.<ReturnRequestJpaEntity>where(Specs.contains("rmaNumber", criteria.text()))
                        .or(Specs.contains("orderNumber", criteria.text())));
        // The lines are LAZY and not touched: a page is one query (plus its count).
        return jpa.findAll(spec, pageable).map(ReturnRequestRepositoryAdapter::toRow);
    }

    // ---- mapping ---------------------------------------------------------------------------------------------------

    private static ReturnRequestJpaEntity newEntity(ReturnRequest r) {
        ReturnRequestJpaEntity entity = new ReturnRequestJpaEntity(r.id(), r.rmaNumber(), r.orderId(), r.orderNumber(),
                r.customerId(), r.requestId(), r.channel(), r.reason(), r.description(), r.requestedResolution(),
                r.requestedBy(), r.requestedAt(), r.currency());
        for (ReturnLine line : r.lines()) {
            entity.addLine(new ReturnRequestLineJpaEntity(line.id(), line.orderLineId(), line.sku(),
                    line.customPrinted(), line.unitPrice(), line.requestedQuantity()));
        }
        return entity;
    }

    private static void apply(ReturnRequest r, ReturnRequestJpaEntity entity) {
        entity.applyStatus(r.status());
        entity.applyReview(r.decidedBy(), r.decidedAt(), r.decisionNote(), r.rejectionReason(), r.returnRequired(),
                r.returnDeadline());
        entity.applyReceipt(r.receivedBy(), r.receivedAt(), r.receivingLocation(), r.receiptNote());
        entity.applyInspection(r.inspectedBy(), r.inspectedAt(), r.inspectionNote());
        entity.applyResolution(r.moneyMode(), r.settlementAmount(), r.proposedBy(), r.proposedAt(), r.resolutionNote(),
                r.settledBy(), r.settledAt(), r.money().refunded(), r.money().credited(), r.money().offset(),
                r.money().creditTransferId(), r.replacementOrderId(), r.vatAdjustmentRequired());
        entity.applyEnd(r.cancelledBy(), r.cancellationReason(), r.closedAt());
        Map<UUID, ReturnRequestLineJpaEntity> rows = entity.getLines().stream()
                .collect(Collectors.toMap(ReturnRequestLineJpaEntity::getId, Function.identity()));
        for (ReturnLine line : r.lines()) {
            ReturnRequestLineJpaEntity row = rows.get(line.id());
            row.apply(line.approvedQuantity(), line.receivedSku(), line.receivedQuantity(), line.lotNumber(),
                    line.acceptedQuantity(), line.rejectedQuantity(), line.condition(), line.inspectionNote(),
                    line.restockQuantity(), line.scrapQuantity(), line.customerReturnQuantity(),
                    line.restockLocation(), line.scrapAdjustmentId(), line.customerReturnAdjustmentId(),
                    line.replacementQuantity(), line.creditQuantity(), line.creditAmount(),
                    line.replacementOrderLineId());
        }
    }

    private ReturnRequest toDomain(ReturnRequestJpaEntity e) {
        List<ReturnLine> lines = e.getLines().stream()
                .map(row -> new ReturnLine(row.getId(), row.getOrderLineId(), row.getSku(), row.isCustomPrinted(),
                        row.getUnitPrice(), row.getQuantity()).restore(row.getApprovedQuantity(),
                        row.getReceivedSku(), row.getReceivedQuantity(), row.getLotNumber(), row.getAcceptedQuantity(),
                        row.getRejectedQuantity(), row.getItemCondition(), row.getInspectionNote(),
                        row.getRestockQuantity(), row.getScrapQuantity(), row.getCustomerReturnQuantity(),
                        row.getRestockLocationCode(), row.getScrapAdjustmentId(), row.getCustomerReturnAdjustmentId(),
                        row.getReplacementQuantity(), row.getCreditQuantity(), row.getReplacementOrderLineId()))
                .toList();
        ReturnRequest request = new ReturnRequest(e.getId(), e.getRmaNumber(), e.getOrderId(), e.getOrderNumber(),
                e.getCustomerId(), e.getRequestId(), e.getChannel(), e.getReasonCode(), e.getNote(),
                e.getRequestedResolution(), e.getRequestedBy(), e.getRequestedAt(), e.getCurrency(), lines,
                e.getStatus(), e.getVersion());
        request.restoreProgress(e.getDecidedBy(), e.getDecidedAt(), e.getDecisionNote(), e.getRejectionReason(),
                e.getReturnRequired(), e.getReturnDeadline(), e.getReceivedBy(), e.getReceivedAt(),
                e.getReceivingLocationCode(), e.getReceiptNote(), e.getInspectedBy(), e.getInspectedAt(),
                e.getInspectionNote());
        request.restoreResolution(e.getMoneyMode(), e.getSettlementAmount(), e.getProposedBy(), e.getProposedAt(),
                e.getResolutionNote(), e.getSettledBy(), e.getSettledAt(),
                new ReturnRequest.MoneyOutcome(e.getOffsetAmount(), e.getRefundedAmount(), e.getCreditedAmount(),
                        e.getCreditTransferId()),
                e.getReplacementOrderId(), e.isVatAdjustmentRequired(), e.getCancelledBy(), e.getCancellationReason(),
                e.getClosedAt());
        request.restoreRecords(
                records.evidenceOf(e.getId()).stream().map(row -> new ReturnEvidence(row.getId(),
                        ReturnEvidence.Stage.valueOf(row.getStage()), row.getFileKey(), row.getOriginalName(),
                        row.getContentType(), row.getSizeBytes(), row.getUploadedBy(), row.getUploadedAt())).toList(),
                records.discrepanciesOf(e.getId()).stream().map(row -> new ReturnDiscrepancy(row.getId(),
                        row.getReturnRequestLineId(), ReturnDiscrepancy.Kind.valueOf(row.getKind()), row.getSku(),
                        row.getQuantity(), row.getNote(), row.getRecordedBy(), row.getRecordedAt())).toList());
        return request;
    }

    private static ReturnRequestRow toRow(ReturnRequestJpaEntity e) {
        return new ReturnRequestRow(e.getId(), e.getRmaNumber(), e.getOrderId(), e.getOrderNumber(), e.getCustomerId(),
                e.getStatus(), e.getReasonCode(), e.getRequestedResolution(), e.getChannel().name(), e.getRequestedAt(),
                e.getReturnDeadline(), e.getSettlementAmount(), e.getRefundedAmount(), e.getCreditedAmount(),
                e.getOffsetAmount(), e.getCurrency(), e.isVatAdjustmentRequired(), e.getReplacementOrderId(),
                e.getClosedAt(), e.getVersion());
    }

    private static ReturnStatus statusOrNull(String name) {
        if (name == null) {
            return null;
        }
        try {
            return ReturnStatus.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
