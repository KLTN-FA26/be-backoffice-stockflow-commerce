package com.stockflow.procurement.internal.repository;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.procurement.api.PurchaseOrderDeliveryDecision;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/** Append-only business evidence in the same transaction as queueing the delivery. */
@Repository
public class PoDeliveryDecisionRepository {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final CurrentUserProvider users;

    public PoDeliveryDecisionRepository(JdbcTemplate jdbc, Clock clock, CurrentUserProvider users) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.users = users;
    }

    public void record(UUID id, int generation, LocalDate previousDate, LocalDate date, String reason,
            boolean reconciled, boolean acknowledgePastDue, String channel, String recipient) {
        jdbc.update("""
                insert into procurement.po_delivery_decision(id,purchase_order_id,generation,previous_expected_at,
                    expected_at,reason,reconciled,acknowledge_past_due,channel,recipient,actor,requested_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                """, Identifiers.newId(), id, generation, previousDate, date, reason, reconciled, acknowledgePastDue,
                channel, recipient, users.current().map(u -> u.userId().toString()).orElse("system"), Timestamp.from(clock.instant()));
    }

    public PageResponse<PurchaseOrderDeliveryDecision> list(UUID id, int page, int size) {
        var pageable = Pages.of(page, size);
        long total = jdbc.queryForObject("select count(*) from procurement.po_delivery_decision where purchase_order_id=?", Long.class, id);
        var rows = jdbc.query("""
                select * from procurement.po_delivery_decision where purchase_order_id=?
                order by requested_at desc,id desc limit ? offset ?
                """, (r, index) -> new PurchaseOrderDeliveryDecision(r.getObject("id", UUID.class), r.getInt("generation"),
                r.getObject("previous_expected_at", LocalDate.class), r.getObject("expected_at", LocalDate.class), r.getString("reason"),
                r.getBoolean("reconciled"), r.getBoolean("acknowledge_past_due"), r.getString("channel"),
                r.getString("recipient"), r.getString("actor"), r.getTimestamp("requested_at").toInstant()), id, size, pageable.getOffset());
        return PageResponse.of(rows, page, size, total);
    }
}
