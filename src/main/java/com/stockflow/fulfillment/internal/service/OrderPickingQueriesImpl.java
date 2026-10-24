package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.persistence.Pages;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.fulfillment.api.OrderPicking;
import com.stockflow.fulfillment.api.OrderPickingQueries;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * {@link OrderPickingQueries} over {@link PickLists}, which applies the task-visibility rule ({@code TaskAccess}) the
 * pick-lines screen applies: one rule, one place.
 */
@Service
@Transactional(readOnly = true)
class OrderPickingQueriesImpl implements OrderPickingQueries {

    private final PickJpaRepository picks;
    private final PickLists pickLists;

    OrderPickingQueriesImpl(PickJpaRepository picks, PickLists pickLists) {
        this.picks = picks;
        this.pickLists = pickLists;
    }

    @Override
    public Optional<OrderPicking> pickingOf(UUID orderId, CurrentUser actor) {
        return picks.findByOrderId(orderId).map(pick -> new OrderPicking(pick.getId(), pick.getStatus().name(),
                pick.getAssignedAt(), pick.getPickedAt(),
                pickLists.lines(pick.getId(), 0, Pages.MAX_PAGE_SIZE, actor).items().stream()
                        .map(line -> new OrderPicking.Line(line.sku(), line.locationCode(), line.lotNumber(),
                                line.allocatedQty(), line.pickedQty(), line.shortQty(), line.putBackQty(),
                                line.status()))
                        .toList()));
    }
}
