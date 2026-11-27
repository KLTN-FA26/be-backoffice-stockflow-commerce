package com.stockflow.assistant.internal.repository;

import com.stockflow.assistant.internal.domain.FeatureUseRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * {@link FeatureUseRepository} in SQL. A count is added to its day's row by one upsert per row, all in one batch: two
 * instances flushing the same feature, role and day at once both add, neither overwrites, and a flush of a few dozen
 * rows is one round trip.
 */
@Repository
class FeatureUseRepositoryAdapter implements FeatureUseRepository {

    private final NamedParameterJdbcTemplate jdbc;

    FeatureUseRepositoryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(List<FeatureCount> counts) {
        if (counts.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] rows = counts.stream().map(count -> new MapSqlParameterSource()
                        .addValue("featureKey", count.featureKey())
                        .addValue("role", count.role())
                        .addValue("day", Date.valueOf(count.day()))
                        .addValue("uses", count.uses()))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO assistant.feature_use (feature_key, role, day, uses)
                VALUES (:featureKey, :role, :day, :uses)
                ON CONFLICT (day, feature_key, role) DO UPDATE SET uses = assistant.feature_use.uses + EXCLUDED.uses""",
                rows);
    }

    @Override
    public List<FeatureCount> between(LocalDate from, LocalDate to) {
        return jdbc.query("""
                        SELECT feature_key, role, day, uses FROM assistant.feature_use
                        WHERE day BETWEEN :from AND :to
                        ORDER BY day, feature_key, role""",
                new MapSqlParameterSource("from", Date.valueOf(from)).addValue("to", Date.valueOf(to)),
                (rs, row) -> new FeatureCount(rs.getString("feature_key"), rs.getString("role"),
                        rs.getDate("day").toLocalDate(), rs.getLong("uses")));
    }

    @Override
    public int deleteBefore(LocalDate day, int limit) {
        return jdbc.update("""
                        DELETE FROM assistant.feature_use WHERE (day, feature_key, role) IN (
                            SELECT day, feature_key, role FROM assistant.feature_use
                            WHERE day < :day ORDER BY day LIMIT :limit)""",
                new MapSqlParameterSource("day", Date.valueOf(day)).addValue("limit", limit));
    }
}
