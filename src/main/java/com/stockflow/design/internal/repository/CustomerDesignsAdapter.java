package com.stockflow.design.internal.repository;

import com.stockflow.design.api.CustomerDesign;
import com.stockflow.design.api.CustomerDesigns;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The design module's answer to {@link CustomerDesigns}: one statement per question, so asking whose a design is does
 * not load its document and artifacts.
 */
@Component
class CustomerDesignsAdapter implements CustomerDesigns {

    private static final int MAX_LIMIT = 50;

    private static final String DESIGNS = """
            SELECT d.id, d.customer_id, d.name, d.status, coalesce(d.last_modified_at, d.created_at) AS changed_at
              FROM design.design_draft d""";
    private static final String PROJECTS = """
            SELECT p.id, p.customer_id, p.name, p.status, coalesce(p.last_modified_at, p.created_at) AS changed_at
              FROM design.design_project p""";

    private static final RowMapper<CustomerDesign> ROW = (rs, n) -> {
        Timestamp changed = rs.getTimestamp("changed_at");
        return new CustomerDesign(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getString("name"), rs.getString("status"), changed == null ? null : changed.toInstant());
    };

    private final NamedParameterJdbcTemplate jdbc;

    CustomerDesignsAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerDesign> design(UUID designId) {
        return designId == null ? Optional.empty()
                : jdbc.query(DESIGNS + " WHERE d.id = :id", Map.of("id", designId), ROW).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerDesign> project(UUID projectId) {
        return projectId == null ? Optional.empty()
                : jdbc.query(PROJECTS + " WHERE p.id = :id", Map.of("id", projectId), ROW).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerDesign> recentDesigns(UUID customerId, int limit) {
        if (customerId == null) {
            return List.of();
        }
        return jdbc.query(DESIGNS + " WHERE d.customer_id = :customer"
                        + " ORDER BY coalesce(d.last_modified_at, d.created_at) DESC, d.id DESC LIMIT :limit",
                Map.of("customer", customerId, "limit", Math.max(1, Math.min(limit, MAX_LIMIT))), ROW);
    }
}
