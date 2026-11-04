package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.fulfillment.api.ShipmentQueries;
import com.stockflow.fulfillment.api.ShipmentSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ShipmentQueries} over {@link Shipments#list}, the screen's own list: the same filters, order and warehouse
 * limit. Each row is copied field by field into the api record, so a field the screen's row gains later does not
 * reach another module unasked.
 */
@Service
@Transactional(readOnly = true)
class ShipmentQueriesImpl implements ShipmentQueries {

    private final Shipments shipments;

    ShipmentQueriesImpl(Shipments shipments) {
        this.shipments = shipments;
    }

    @Override
    public PageResponse<ShipmentSummary> shipments(String status, String search, boolean oldestFirst, int page,
                                                   int size) {
        return shipments.list(null, status, null, null, search, page, size, oldestFirst).map(row -> new ShipmentSummary(
                row.shipmentId(), row.code(), row.orderId(), row.orderNumber(), row.warehouseId(), row.status(),
                row.deliveryMode(), row.packageCount(), row.codAmount(), row.currency(), row.carrierName(),
                row.loadCode(), row.partial(), row.readyAt(), row.handedOverAt(), row.outcomeAt(),
                row.failureReason()));
    }
}
