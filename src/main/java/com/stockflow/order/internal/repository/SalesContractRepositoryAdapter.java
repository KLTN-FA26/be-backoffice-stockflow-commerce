package com.stockflow.order.internal.repository;

import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.internal.domain.ContractParty;
import com.stockflow.order.internal.domain.DeliveryBatch;
import com.stockflow.order.internal.domain.PaymentMilestone;
import com.stockflow.order.internal.domain.PlannedInstallment;
import com.stockflow.order.internal.domain.SalesContract;
import com.stockflow.order.internal.domain.SalesContractRepository;
import com.stockflow.order.internal.domain.SalesContractStatus;
import com.stockflow.order.internal.entity.SalesContractJpaEntity;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link SalesContractRepository}: the contract row through JPA, its delivery schedule and payment plan in SQL. A
 * draft's schedule and plan are written whole on every save - deleted and inserted again, after the contract row is
 * flushed so their foreign keys find it. Once the contract is sent they are never written again; the database refuses it
 * too ({@code tg_delivery_batch_draft} and its siblings).
 */
@Repository
class SalesContractRepositoryAdapter implements SalesContractRepository {

    private final SalesContractJpaRepository jpa;
    private final NamedParameterJdbcTemplate named;
    private final EntityManager entityManager;

    SalesContractRepositoryAdapter(SalesContractJpaRepository jpa, JdbcTemplate jdbc, EntityManager entityManager) {
        this.jpa = jpa;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.entityManager = entityManager;
    }

    @Override
    public Optional<SalesContract> findLiveByOrderId(UUID orderId) {
        return jpa.findLiveByOrderId(orderId).map(entity -> restore(List.of(entity)).getFirst());
    }

    @Override
    public List<SalesContract> findByOrderId(UUID orderId) {
        return restore(jpa.findByOrderIdOrderByCreatedAtDesc(orderId));
    }

    @Override
    public int countByOrderId(UUID orderId) {
        return jpa.countByOrderId(orderId);
    }

    @Override
    public SalesContract save(SalesContract contract) {
        SalesContractJpaEntity entity = jpa.findById(contract.id()).orElse(null);
        boolean created = entity == null;
        if (created) {
            ContractParty seller = contract.seller();
            ContractParty buyer = contract.buyer();
            entity = new SalesContractJpaEntity(contract.id(), contract.contractNumber(), contract.orderId(),
                    seller.name(), seller.taxCode(), seller.address(), seller.representative(),
                    seller.representativeTitle(), buyer.name(), buyer.taxCode(), buyer.address(),
                    buyer.representative(), buyer.representativeTitle());
        } else if (entity.getVersion() != contract.version()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK, "Contract %s changed since it was read"
                    .formatted(contract.contractNumber()));
        }
        entity.apply(contract.status(), contract.freeSampleRounds(), contract.sentAt(), contract.sentBy(),
                contract.sentDocumentKey(), contract.signedOn(), contract.signedFileKey(), contract.signedFileName(),
                contract.signedAt(), contract.signedBy(), contract.cancelledAt(), contract.cancelledBy(),
                contract.cancellationReason(), contract.completedAt());
        SalesContractJpaEntity saved = jpa.saveAndFlush(entity);
        if (contract.status() == SalesContractStatus.DRAFT) {
            writeTerms(contract);
            if (!created) {
                // A draft whose schedule or plan alone changed leaves its row as it was, and Hibernate would write no
                // new version: the next editor holding the old one would then overwrite this one unseen.
                named.update("UPDATE ordering.sales_contract SET version = version + 1 WHERE id = :id",
                        new MapSqlParameterSource("id", contract.id()));
                entityManager.refresh(saved);
            }
        }
        return restore(saved, contract.schedule(), contract.plan());
    }

    @Override
    public Set<String> referencedKeys(Collection<String> keys) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        Set<String> kept = new HashSet<>(jpa.sentDocumentKeysIn(keys));
        kept.addAll(jpa.signedFileKeysIn(keys));
        return kept;
    }

    private void writeTerms(SalesContract contract) {
        MapSqlParameterSource of = new MapSqlParameterSource("contract", contract.id());
        named.update("DELETE FROM ordering.contract_payment_installment WHERE contract_id = :contract", of);
        named.update("DELETE FROM ordering.delivery_batch WHERE contract_id = :contract", of);
        Map<Integer, UUID> batchIds = new HashMap<>();
        List<MapSqlParameterSource> batches = new ArrayList<>();
        List<MapSqlParameterSource> lines = new ArrayList<>();
        for (DeliveryBatch batch : contract.schedule()) {
            batchIds.put(batch.seq(), batch.id());
            batches.add(new MapSqlParameterSource()
                    .addValue("id", batch.id())
                    .addValue("contract", contract.id())
                    .addValue("seq", batch.seq())
                    .addValue("start", batch.windowStart() == null ? null : Date.valueOf(batch.windowStart()))
                    .addValue("end", batch.windowEnd() == null ? null : Date.valueOf(batch.windowEnd())));
            for (DeliveryBatch.Line line : batch.lines()) {
                lines.add(new MapSqlParameterSource()
                        .addValue("id", line.id())
                        .addValue("batch", batch.id())
                        .addValue("line", line.orderLineId())
                        .addValue("cartons", line.cartons()));
            }
        }
        named.batchUpdate("""
                INSERT INTO ordering.delivery_batch (id, contract_id, seq, window_start, window_end)
                VALUES (:id, :contract, :seq, :start, :end)""", batches.toArray(MapSqlParameterSource[]::new));
        named.batchUpdate("""
                INSERT INTO ordering.delivery_batch_line (id, delivery_batch_id, order_line_id, cartons)
                VALUES (:id, :batch, :line, :cartons)""", lines.toArray(MapSqlParameterSource[]::new));
        List<MapSqlParameterSource> installments = new ArrayList<>();
        for (PlannedInstallment installment : contract.plan()) {
            installments.add(new MapSqlParameterSource()
                    .addValue("id", installment.id())
                    .addValue("contract", contract.id())
                    .addValue("seq", installment.seq())
                    .addValue("name", installment.name())
                    .addValue("milestone", installment.milestone().name())
                    .addValue("percent", installment.percent())
                    .addValue("amount", installment.amount().amount())
                    .addValue("due", installment.dueDays())
                    .addValue("batch", installment.deliveryBatchSeq() == null ? null
                            : batchIds.get(installment.deliveryBatchSeq())));
        }
        named.batchUpdate("""
                INSERT INTO ordering.contract_payment_installment (id, contract_id, seq, name, milestone, percent,
                    amount, due_days, delivery_batch_id)
                VALUES (:id, :contract, :seq, :name, :milestone, :percent, :amount, :due, :batch)""",
                installments.toArray(MapSqlParameterSource[]::new));
    }

    /** The contracts with their schedules and plans: three queries, whatever the number of contracts. */
    private List<SalesContract> restore(List<SalesContractJpaEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = entities.stream().map(SalesContractJpaEntity::getId).toList();
        MapSqlParameterSource of = new MapSqlParameterSource("ids", ids);
        Map<UUID, List<DeliveryBatch.Line>> linesByBatch = new HashMap<>();
        named.query("""
                SELECT l.id, l.delivery_batch_id, l.order_line_id, l.cartons
                  FROM ordering.delivery_batch_line l
                  JOIN ordering.delivery_batch b ON b.id = l.delivery_batch_id
                 WHERE b.contract_id IN (:ids)
                 ORDER BY l.id""", of, row -> {
            linesByBatch.computeIfAbsent(row.getObject("delivery_batch_id", UUID.class), k -> new ArrayList<>())
                    .add(new DeliveryBatch.Line(row.getObject("id", UUID.class),
                            row.getObject("order_line_id", UUID.class), row.getInt("cartons")));
        });
        Map<UUID, List<DeliveryBatch>> batchesByContract = new LinkedHashMap<>();
        named.query("""
                SELECT id, contract_id, seq, window_start, window_end FROM ordering.delivery_batch
                 WHERE contract_id IN (:ids) ORDER BY contract_id, seq""", of, row -> {
            UUID batchId = row.getObject("id", UUID.class);
            Date start = row.getDate("window_start");
            Date end = row.getDate("window_end");
            batchesByContract.computeIfAbsent(row.getObject("contract_id", UUID.class), k -> new ArrayList<>())
                    .add(new DeliveryBatch(batchId, row.getInt("seq"), start == null ? null : start.toLocalDate(),
                            end == null ? null : end.toLocalDate(), linesByBatch.getOrDefault(batchId, List.of())));
        });
        Map<UUID, List<PlannedInstallment>> planByContract = new HashMap<>();
        named.query("""
                SELECT i.id, i.contract_id, i.seq, i.name, i.milestone, i.percent, i.amount, i.due_days,
                       b.seq AS batch_seq
                  FROM ordering.contract_payment_installment i
                  LEFT JOIN ordering.delivery_batch b ON b.id = i.delivery_batch_id
                 WHERE i.contract_id IN (:ids) ORDER BY i.contract_id, i.seq""", of, row -> {
            int dueDays = row.getInt("due_days");
            Integer due = row.wasNull() ? null : dueDays;
            int batchSeq = row.getInt("batch_seq");
            Integer batch = row.wasNull() ? null : batchSeq;
            planByContract.computeIfAbsent(row.getObject("contract_id", UUID.class), k -> new ArrayList<>())
                    .add(new PlannedInstallment(row.getObject("id", UUID.class), row.getInt("seq"),
                            row.getString("name"), PaymentMilestone.valueOf(row.getString("milestone")),
                            row.getBigDecimal("percent"), new Money(row.getBigDecimal("amount"), Money.VND), due,
                            batch));
        });
        return entities.stream().map(entity -> restore(entity, batchesByContract.getOrDefault(entity.getId(),
                List.of()), planByContract.getOrDefault(entity.getId(), List.of()))).toList();
    }

    private static SalesContract restore(SalesContractJpaEntity e, List<DeliveryBatch> schedule,
                                         List<PlannedInstallment> plan) {
        return SalesContract.restore(e.getId(), e.getContractNumber(), e.getOrderId(), e.getStatus(),
                new ContractParty(e.getSellerName(), e.getSellerTaxCode(), e.getSellerAddress(),
                        e.getSellerRepresentative(), e.getSellerRepresentativeTitle()),
                new ContractParty(e.getBuyerName(), e.getBuyerTaxCode(), e.getBuyerAddress(),
                        e.getBuyerRepresentative(), e.getBuyerRepresentativeTitle()),
                e.getFreeSampleRounds(), schedule, plan, e.getSentAt(), e.getSentBy(), e.getSentDocumentKey(),
                e.getSignedOn(), e.getSignedFileKey(), e.getSignedFileName(), e.getSignedAt(), e.getSignedBy(),
                e.getCancelledAt(), e.getCancelledBy(), e.getCancellationReason(), e.getCompletedAt(),
                e.getVersion());
    }
}
