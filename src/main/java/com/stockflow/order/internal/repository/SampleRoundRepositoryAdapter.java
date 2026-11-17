package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.ReprintReason;
import com.stockflow.order.internal.domain.SampleRound;
import com.stockflow.order.internal.domain.SampleRoundRepository;
import com.stockflow.order.internal.domain.SampleRounds;
import jakarta.persistence.EntityManager;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link SampleRoundRepository} over {@code ordering.production_order_sample_round} and
 * {@code production_order_sample_media}, by native SQL through the entity manager - so the statements flush and run in
 * the caller's persistence context, in the order the caller makes them: a round is written before the production order
 * it moves, which {@code tg_production_order_sample} reads (round 1 before SAMPLE_PENDING, the approval before
 * IN_PRODUCTION). Plain rows with no life of their own beside {@link SampleRounds}, like a production order's lines.
 */
@Repository
class SampleRoundRepositoryAdapter implements SampleRoundRepository {

    private final EntityManager entityManager;

    SampleRoundRepositoryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public SampleRounds of(UUID productionOrderId) {
        return of(Set.of(productionOrderId)).getOrDefault(productionOrderId,
                new SampleRounds(productionOrderId, List.of()));
    }

    @Override
    public Map<UUID, SampleRounds> of(Collection<UUID> productionOrderIds) {
        if (productionOrderIds.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT id, production_order_id, round_no, reprint_reason, opened_by, opened_at,
                               physical_sample_sent_on, result, customer_note, answered_by, answered_at
                          FROM ordering.production_order_sample_round
                         WHERE production_order_id IN (:ids) ORDER BY production_order_id, round_no""")
                .setParameter("ids", productionOrderIds)
                .getResultList();
        Map<UUID, List<SampleRound.Media>> media = mediaOf(rows.stream().map(row -> (UUID) row[0]).toList());
        Map<UUID, List<SampleRound>> rounds = new LinkedHashMap<>();
        for (Object[] row : rows) {
            UUID id = (UUID) row[0];
            rounds.computeIfAbsent((UUID) row[1], key -> new ArrayList<>()).add(new SampleRound(id, (UUID) row[1],
                    ((Number) row[2]).intValue(), row[3] == null ? null : ReprintReason.valueOf((String) row[3]),
                    (UUID) row[4], instant(row[5]), date(row[6]),
                    row[7] == null ? null : SampleRound.Result.valueOf((String) row[7]), (String) row[8],
                    (UUID) row[9], instant(row[10]), media.getOrDefault(id, List.of())));
        }
        Map<UUID, SampleRounds> result = new LinkedHashMap<>();
        rounds.forEach((order, list) -> result.put(order, new SampleRounds(order, list)));
        return result;
    }

    @Override
    public Optional<UUID> productionOrderOf(UUID roundId) {
        @SuppressWarnings("unchecked")
        List<UUID> found = entityManager.createNativeQuery("""
                        SELECT production_order_id FROM ordering.production_order_sample_round WHERE id = :id""")
                .setParameter("id", roundId)
                .getResultList();
        return found.stream().findFirst();
    }

    @Override
    public List<UUID> awaitingCustomer(UUID customerId) {
        @SuppressWarnings("unchecked")
        List<UUID> found = entityManager.createNativeQuery("""
                        SELECT p.id FROM ordering.production_order p
                          JOIN ordering.production_order_sample_round r ON r.production_order_id = p.id
                                                                       AND r.result IS NULL
                         WHERE p.customer_id = :customer AND p.status = 'SAMPLE_PENDING'
                         ORDER BY r.opened_at, p.id""")
                .setParameter("customer", customerId)
                .getResultList();
        return found;
    }

    @Override
    public void insert(SampleRound round) {
        typed("""
                        INSERT INTO ordering.production_order_sample_round (id, production_order_id, round_no,
                                                                            reprint_reason, opened_by, opened_at,
                                                                            physical_sample_sent_on)
                        VALUES (:id, :order, :no, :reason, :by, :at, :sentOn)""")
                .setParameter("id", round.id())
                .setParameter("order", round.productionOrderId())
                .setParameter("no", round.roundNo())
                .setParameter("reason", round.reprintReason() == null ? null : round.reprintReason().name(),
                        String.class)
                .setParameter("by", round.openedBy())
                .setParameter("at", round.openedAt())
                .setParameter("sentOn", round.physicalSampleSentOn(), LocalDate.class)
                .executeUpdate();
        addMedia(round.id(), round.media());
    }

    @Override
    public void update(SampleRound round) {
        typed("""
                        UPDATE ordering.production_order_sample_round
                           SET physical_sample_sent_on = :sentOn, result = :result, customer_note = :note,
                               answered_by = :by, answered_at = :at
                         WHERE id = :id""")
                .setParameter("sentOn", round.physicalSampleSentOn(), LocalDate.class)
                .setParameter("result", round.result() == null ? null : round.result().name(), String.class)
                .setParameter("note", round.customerNote(), String.class)
                .setParameter("by", round.answeredBy(), UUID.class)
                .setParameter("at", round.answeredAt(), Instant.class)
                .setParameter("id", round.id())
                .executeUpdate();
    }

    @Override
    public void addMedia(UUID roundId, List<SampleRound.Media> media) {
        for (SampleRound.Media file : media) {
            entityManager.createNativeQuery("""
                            INSERT INTO ordering.production_order_sample_media (id, sample_round_id, storage_key,
                                                                                file_name, content_type, size_bytes,
                                                                                uploaded_by, uploaded_at)
                            VALUES (:id, :round, :key, :name, :type, :size, :by, :at)""")
                    .setParameter("id", file.id())
                    .setParameter("round", roundId)
                    .setParameter("key", file.storageKey())
                    .setParameter("name", file.fileName())
                    .setParameter("type", file.contentType())
                    .setParameter("size", file.sizeBytes())
                    .setParameter("by", file.uploadedBy())
                    .setParameter("at", file.uploadedAt())
                    .executeUpdate();
        }
    }

    @Override
    public Map<String, UUID> roundsOfKeys(Collection<String> storageKeys) {
        if (storageKeys.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> found = entityManager.createNativeQuery("""
                        SELECT storage_key, sample_round_id FROM ordering.production_order_sample_media
                         WHERE storage_key IN (:keys)""")
                .setParameter("keys", storageKeys)
                .getResultList();
        Map<String, UUID> rounds = new LinkedHashMap<>();
        found.forEach(row -> rounds.put((String) row[0], (UUID) row[1]));
        return rounds;
    }

    /**
     * A native statement whose parameters are bound with their type: a null bound without one goes to PostgreSQL as
     * bytea, which no date or uuid column takes.
     */
    @SuppressWarnings("unchecked")
    private NativeQuery<Object> typed(String sql) {
        return entityManager.createNativeQuery(sql).unwrap(NativeQuery.class);
    }

    private Map<UUID, List<SampleRound.Media>> mediaOf(List<UUID> roundIds) {
        if (roundIds.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT id, sample_round_id, storage_key, file_name, content_type, size_bytes, uploaded_by,
                               uploaded_at
                          FROM ordering.production_order_sample_media
                         WHERE sample_round_id IN (:ids) ORDER BY uploaded_at, id""")
                .setParameter("ids", roundIds)
                .getResultList();
        return rows.stream().collect(Collectors.groupingBy(row -> (UUID) row[1], LinkedHashMap::new,
                Collectors.mapping(row -> new SampleRound.Media((UUID) row[0], (String) row[2], (String) row[3],
                        (String) row[4], ((Number) row[5]).longValue(), (UUID) row[6], instant(row[7])),
                        Collectors.toList())));
    }

    private static LocalDate date(Object value) {
        return switch (value) {
            case null -> null;
            case LocalDate d -> d;
            case Date d -> d.toLocalDate();
            default -> LocalDate.parse(value.toString());
        };
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant i -> i;
            case OffsetDateTime o -> o.toInstant();
            case java.sql.Timestamp t -> t.toInstant();
            default -> Instant.parse(value.toString());
        };
    }
}
