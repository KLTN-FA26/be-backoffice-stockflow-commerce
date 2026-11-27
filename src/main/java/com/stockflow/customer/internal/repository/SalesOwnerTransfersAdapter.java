package com.stockflow.customer.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.SalesOwnerChangeReason;
import com.stockflow.customer.internal.domain.SalesOwnerTransfers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link SalesOwnerTransfers} in SQL. A batch's items are written in one batched statement; the managers' list reads
 * batches, their targets, what each still holds and its return in three statements whatever the number of batches.
 */
@Repository
class SalesOwnerTransfersAdapter implements SalesOwnerTransfers {

    private static final String COLUMNS = """
            id, request_id, from_user_id, reason, note, temporary, returns_batch_id, notify_customer, moved, skipped,
            requested_by, requested_at, not_found_customer_ids""";

    private static final RowMapper<Batch> BATCH = SalesOwnerTransfersAdapter::batch;

    private final NamedParameterJdbcTemplate jdbc;

    SalesOwnerTransfersAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean claim(Batch batch) {
        return jdbc.update("""
                INSERT INTO customer.sales_owner_transfer_batch (id, request_id, from_user_id, reason, note, temporary,
                        returns_batch_id, notify_customer, requested_by, requested_at, version, created_at, created_by)
                VALUES (:id, :request, :from, :reason, :note, :temporary, :returns, :notify, :by, :at, 0, :at, :creator)
                ON CONFLICT (request_id) DO NOTHING""",
                new MapSqlParameterSource("id", batch.id()).addValue("request", batch.requestId())
                        .addValue("from", batch.fromUserId()).addValue("reason", batch.reason().name())
                        .addValue("note", batch.note()).addValue("temporary", batch.temporary())
                        .addValue("returns", batch.returnsBatchId()).addValue("notify", batch.notifyCustomer())
                        .addValue("by", batch.requestedBy()).addValue("at", Timestamp.from(batch.requestedAt()))
                        .addValue("creator", String.valueOf(batch.requestedBy()))) == 1;
    }

    @Override
    public Optional<Batch> findByRequestId(UUID requestId) {
        return one("request_id = :key", requestId, "");
    }

    @Override
    public Optional<Batch> findById(UUID batchId) {
        return one("id = :key", batchId, "");
    }

    @Override
    public Optional<Batch> findForUpdate(UUID batchId) {
        return one("id = :key", batchId, " FOR UPDATE");
    }

    @Override
    public Optional<Batch> returnOf(UUID batchId) {
        return one("returns_batch_id = :key", batchId, "");
    }

    private Optional<Batch> one(String where, UUID key, String lock) {
        return jdbc.query("SELECT " + COLUMNS + " FROM customer.sales_owner_transfer_batch WHERE " + where + lock,
                new MapSqlParameterSource("key", key), BATCH).stream().findFirst();
    }

    @Override
    public void complete(UUID batchId, List<Item> items, List<UUID> notFound) {
        if (!items.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO customer.sales_owner_transfer_item (id, batch_id, customer_id, outcome, to_user_id,
                                                                     skip_reason, version, created_at)
                    VALUES (:id, :batch, :customer, :outcome, :to, :skip, 0, NOW())""",
                    items.stream().map(item -> new MapSqlParameterSource("id", Identifiers.newId())
                            .addValue("batch", batchId).addValue("customer", item.customerId())
                            .addValue("outcome", item.moved() ? "MOVED" : "SKIPPED").addValue("to", item.toUserId())
                            .addValue("skip", item.skip() == null ? null : item.skip().name()))
                            .toArray(MapSqlParameterSource[]::new));
        }
        long moved = items.stream().filter(Item::moved).count();
        jdbc.update("""
                UPDATE customer.sales_owner_transfer_batch
                   SET moved = :moved, skipped = :skipped, not_found_customer_ids = CAST(:notFound AS uuid[]),
                       version = version + 1, last_modified_at = NOW()
                 WHERE id = :id""",
                new MapSqlParameterSource("id", batchId).addValue("moved", moved)
                        .addValue("skipped", items.size() - moved + notFound.size())
                        .addValue("notFound", notFound.stream().map(UUID::toString)
                                .collect(Collectors.joining(",", "{", "}"))));
    }

    @Override
    public List<Item> items(UUID batchId) {
        return jdbc.query("""
                SELECT customer_id, to_user_id, skip_reason FROM customer.sales_owner_transfer_item
                 WHERE batch_id = :batch ORDER BY customer_id""", new MapSqlParameterSource("batch", batchId),
                (rs, row) -> new Item(rs.getObject("customer_id", UUID.class),
                        rs.getString("skip_reason") == null ? rs.getObject("to_user_id", UUID.class) : null,
                        rs.getString("skip_reason") == null ? null : Skip.valueOf(rs.getString("skip_reason"))));
    }

    @Override
    public List<Summary> list(boolean temporaryOnly, int limit) {
        List<Batch> batches = jdbc.query("SELECT " + COLUMNS + " FROM customer.sales_owner_transfer_batch"
                        + " WHERE returns_batch_id IS NULL AND (NOT :temporaryOnly OR temporary)"
                        + " ORDER BY requested_at DESC, id DESC LIMIT :limit",
                new MapSqlParameterSource("temporaryOnly", temporaryOnly).addValue("limit", limit), BATCH);
        if (batches.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = batches.stream().map(Batch::id).toList();
        record Held(List<UUID> targets, int stillHeld) {
        }
        Map<UUID, Held> held = new LinkedHashMap<>();
        // A customer is still held by the batch when its owner (or the one who left it) is the item's target.
        jdbc.query("""
                SELECT i.batch_id,
                       array_agg(DISTINCT i.to_user_id) FILTER (WHERE i.outcome = 'MOVED') AS targets,
                       count(*) FILTER (WHERE i.outcome = 'MOVED' AND c.sales_owner_id = i.to_user_id
                                          AND c.sales_owner_state <> 'UNASSIGNED') AS still_held
                  FROM customer.sales_owner_transfer_item i
                  JOIN customer.customer c ON c.id = i.customer_id
                 WHERE i.batch_id IN (:ids)
                 GROUP BY i.batch_id""", new MapSqlParameterSource("ids", ids), rs -> {
            held.put(rs.getObject("batch_id", UUID.class),
                    new Held(uuids(rs.getArray("targets")), rs.getInt("still_held")));
        });
        Map<UUID, java.time.Instant> returned = new LinkedHashMap<>();
        jdbc.query("""
                SELECT returns_batch_id, requested_at FROM customer.sales_owner_transfer_batch
                 WHERE returns_batch_id IN (:ids)""", new MapSqlParameterSource("ids", ids),
                rs -> {
                    returned.put(rs.getObject("returns_batch_id", UUID.class),
                            rs.getTimestamp("requested_at").toInstant());
                });
        List<Summary> summaries = new ArrayList<>();
        for (Batch batch : batches) {
            Held of = held.getOrDefault(batch.id(), new Held(List.of(), 0));
            summaries.add(new Summary(batch, of.targets(), of.stillHeld(), returned.get(batch.id())));
        }
        return summaries;
    }

    private static Batch batch(ResultSet rs, int row) throws SQLException {
        return new Batch(rs.getObject("id", UUID.class), rs.getObject("request_id", UUID.class),
                rs.getObject("from_user_id", UUID.class), SalesOwnerChangeReason.valueOf(rs.getString("reason")),
                rs.getString("note"), rs.getBoolean("temporary"), rs.getObject("returns_batch_id", UUID.class),
                rs.getBoolean("notify_customer"), rs.getInt("moved"), rs.getInt("skipped"),
                rs.getObject("requested_by", UUID.class), rs.getTimestamp("requested_at").toInstant(),
                uuids(rs.getArray("not_found_customer_ids")));
    }

    private static List<UUID> uuids(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object[] values = (Object[]) array.getArray();
        return Arrays.stream(values).filter(java.util.Objects::nonNull)
                .map(value -> value instanceof UUID uuid ? uuid : UUID.fromString(value.toString())).toList();
    }
}
