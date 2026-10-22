package com.stockflow.order.internal.service;

import com.stockflow.common.id.Identifiers;
import com.stockflow.order.api.DeliveryMode;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.entity.OrderDeliveryModeChangeJpaEntity;
import com.stockflow.order.internal.repository.OrderDeliveryModeChangeJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * LP-1: changes the delivery mode of an order the caller loaded under its row lock, and writes the history row of the
 * change - for {@link DeliveryModeService} and for a release that names a mode. The caller saves the order, in its own
 * transaction. Deliberately unadvised (no proxy), so the package-private call runs on the real bean.
 */
@Component
class DeliveryModeHistory {

    private static final Logger log = LoggerFactory.getLogger(DeliveryModeHistory.class);

    private final OrderDeliveryModeChangeJpaRepository changes;
    private final Clock clock;

    DeliveryModeHistory(OrderDeliveryModeChangeJpaRepository changes, Clock clock) {
        this.changes = changes;
        this.clock = clock;
    }

    /**
     * @return whether the mode changed (the same mode again records nothing)
     * @throws com.stockflow.common.error.BusinessException {@code DELIVERY_MODE_LOCKED}, {@code DELIVERY_MODE_FTL_NO_COD}
     */
    boolean apply(Order order, DeliveryMode mode, String note, UUID by) {
        DeliveryMode before = order.deliveryMode();
        if (!order.changeDeliveryMode(mode)) {
            return false;
        }
        changes.save(new OrderDeliveryModeChangeJpaEntity(Identifiers.newId(), order.id().value(), mode.name(),
                before.name(), order.status().name(), note, by, clock.instant()));
        log.info("Order {}: delivery mode {} → {} by {}", order.orderNumber(), before, mode, by);
        return true;
    }
}
