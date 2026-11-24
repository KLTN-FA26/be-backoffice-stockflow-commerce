package com.stockflow.order.internal.repository;

import com.stockflow.order.api.ShippingFeeWaivers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@link ShippingFeeWaivers} from what payment answered and the order kept: the shipments' waivers
 * ({@code order_shipment.shipping_fee_waived}) and the closures' ({@code order_remainder_closure.shipping_fee_waived},
 * Q-19 edge).
 */
@Repository
class ShippingFeeWaiversAdapter implements ShippingFeeWaivers {

    private final JdbcTemplate jdbc;

    ShippingFeeWaiversAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BigDecimal waivedOf(UUID orderId) {
        if (orderId == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal waived = jdbc.queryForObject("""
                SELECT (SELECT COALESCE(SUM(shipping_fee_waived), 0) FROM ordering.order_shipment WHERE order_id = ?)
                     + (SELECT COALESCE(SUM(shipping_fee_waived), 0) FROM ordering.order_remainder_closure
                         WHERE order_id = ?)""", BigDecimal.class, orderId, orderId);
        // Whole by ck_order_shipment_closed_money.
        return waived == null ? BigDecimal.ZERO : waived.setScale(0, java.math.RoundingMode.UNNECESSARY);
    }
}
