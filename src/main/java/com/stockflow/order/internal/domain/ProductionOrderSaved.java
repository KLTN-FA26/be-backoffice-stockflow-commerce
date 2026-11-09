package com.stockflow.order.internal.domain;

import java.util.UUID;

/**
 * A production order was written - drawn up, edited, moved, received against (4A-7). An application event inside the
 * order module, published by the repository on every save so that whatever path changes a production order (and so
 * its sales order's delay alerts: kltn-docs 17 §4.12 step 48, "lập / gửi / nhận Đơn sản xuất, ghi ngày hoàn thành dự
 * kiến") is followed without anyone remembering to. Its listener recomputes after the commit.
 */
public record ProductionOrderSaved(UUID productionOrderId, UUID salesOrderId) {
}
