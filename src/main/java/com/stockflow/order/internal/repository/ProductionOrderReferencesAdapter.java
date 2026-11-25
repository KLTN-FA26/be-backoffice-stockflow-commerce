package com.stockflow.order.internal.repository;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Plain SQL: two columns by id, no entity worth loading for them. */
@Repository
class ProductionOrderReferencesAdapter implements ProductionOrderReferences {

    private final NamedParameterJdbcTemplate jdbc;

    ProductionOrderReferencesAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, String> orderNumbers(Collection<UUID> orderIds) {
        return labels("SELECT id, order_number AS label FROM ordering.customer_order WHERE id IN (:ids)", orderIds);
    }

    @Override
    public Map<UUID, String> producerNames(Collection<UUID> producerIds) {
        return labels("SELECT id, name AS label FROM ordering.producer WHERE id IN (:ids)", producerIds);
    }

    private Map<UUID, String> labels(String sql, Collection<UUID> ids) {
        List<UUID> wanted = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> labels = new HashMap<>();
        jdbc.query(sql, Map.of("ids", wanted),
                rs -> {
                    labels.put(rs.getObject("id", UUID.class), rs.getString("label"));
                });
        return labels;
    }
}
