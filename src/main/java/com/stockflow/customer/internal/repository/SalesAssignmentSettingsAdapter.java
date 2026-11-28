package com.stockflow.customer.internal.repository;

import com.stockflow.customer.internal.domain.SalesAssignmentSettings;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** {@link SalesAssignmentSettings} in SQL: one row per salesperson who has ever had a setting. */
@Repository
class SalesAssignmentSettingsAdapter implements SalesAssignmentSettings {

    private static final RowMapper<Setting> SETTING = (rs, row) -> new Setting(rs.getObject("user_id", UUID.class),
            rs.getBoolean("paused"), (Integer) rs.getObject("capacity_per_month"), rs.getLong("version"));

    private final NamedParameterJdbcTemplate jdbc;

    SalesAssignmentSettingsAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, Setting> of(Collection<UUID> userIds) {
        Set<UUID> ids = userIds == null ? Set.of() : userIds.stream().filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Setting> settings = new HashMap<>();
        ids.forEach(id -> settings.put(id, Setting.defaults(id)));
        jdbc.query("SELECT user_id, paused, capacity_per_month, version FROM customer.sales_assignment_setting"
                        + " WHERE user_id IN (:ids)", new MapSqlParameterSource("ids", ids), SETTING)
                .forEach(setting -> settings.put(setting.userId(), setting));
        return settings;
    }

    @Override
    public Setting forUpdate(UUID userId) {
        List<Setting> found = jdbc.query("SELECT user_id, paused, capacity_per_month, version"
                        + " FROM customer.sales_assignment_setting WHERE user_id = :id FOR UPDATE",
                new MapSqlParameterSource("id", userId), SETTING);
        return found.isEmpty() ? Setting.defaults(userId) : found.get(0);
    }

    @Override
    public Setting save(UUID userId, boolean paused, Integer capacityPerMonth, UUID by) {
        return jdbc.queryForObject("""
                INSERT INTO customer.sales_assignment_setting (user_id, paused, capacity_per_month, version, created_at,
                                                               created_by)
                VALUES (:id, :paused, :capacity, 0, NOW(), :by)
                ON CONFLICT (user_id) DO UPDATE
                   SET paused = EXCLUDED.paused, capacity_per_month = EXCLUDED.capacity_per_month,
                       version = customer.sales_assignment_setting.version + 1, last_modified_at = NOW(),
                       last_modified_by = EXCLUDED.created_by
                RETURNING user_id, paused, capacity_per_month, version""",
                new MapSqlParameterSource("id", userId).addValue("paused", paused).addValue("capacity", capacityPerMonth)
                        .addValue("by", String.valueOf(by)), SETTING);
    }
}
