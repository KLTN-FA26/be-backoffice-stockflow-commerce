package com.stockflow.fulfillment.internal.service;

import com.stockflow.fulfillment.api.LoadingLoadLookup;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A narrow fulfillment reading for payment's receipt-void warning. */
@Service
class LoadingLoadLookupAdapter implements LoadingLoadLookup {
    private final NamedParameterJdbcTemplate jdbc;

    LoadingLoadLookupAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> loadingLoadCodes(Collection<UUID> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) return List.of();
        return jdbc.queryForList("""
                SELECT DISTINCT l.code
                  FROM fulfillment.dispatch_load l
                  JOIN fulfillment.dispatch_load_shipment ls ON ls.load_id = l.id AND ls.active
                  JOIN fulfillment.shipment s ON s.id = ls.shipment_id
                 WHERE l.status IN ('PLANNED', 'VEHICLE_BOOKED', 'LOADING')
                   AND s.order_id IN (:orderIds)
                 ORDER BY l.code
                """, Map.of("orderIds", orderIds), String.class);
    }
}
