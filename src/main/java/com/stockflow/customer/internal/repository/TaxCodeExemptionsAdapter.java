package com.stockflow.customer.internal.repository;

import com.stockflow.customer.internal.domain.TaxCodeExemptions;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** {@link TaxCodeExemptions} in SQL: one row per exempted customer. */
@Repository
class TaxCodeExemptionsAdapter implements TaxCodeExemptions {

    private final NamedParameterJdbcTemplate jdbc;

    TaxCodeExemptionsAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void grant(UUID customerId, String reason, UUID grantedBy, Instant at) {
        jdbc.update("""
                INSERT INTO customer.customer_tax_code_exemption (customer_id, reason, granted_by, granted_at, version,
                                                                  created_at, created_by)
                VALUES (:customer, :reason, :by, :at, 0, :at, :creator)""",
                new MapSqlParameterSource("customer", customerId).addValue("reason", reason).addValue("by", grantedBy)
                        .addValue("at", Timestamp.from(at)).addValue("creator", String.valueOf(grantedBy)));
    }
}
