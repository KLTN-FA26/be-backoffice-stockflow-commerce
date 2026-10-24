package com.stockflow.fulfillment.internal.controller;

import com.stockflow.fulfillment.internal.controller.dto.TaskCandidateResponse;
import com.stockflow.order.api.FulfillmentCandidate;
import org.springframework.stereotype.Component;

@Component
class TaskCandidateWebMapper {

    TaskCandidateResponse toResponse(FulfillmentCandidate candidate) {
        var lines = candidate.lines().stream()
                .map(line -> new TaskCandidateResponse.Line(line.lineId(), line.sku(), line.quantity(), line.printed()))
                .toList();
        return new TaskCandidateResponse(candidate.orderId(), candidate.orderNumber(), candidate.customerId(),
                candidate.customerName(), candidate.warehouseId(), candidate.placedAt(), candidate.releasedAt(),
                lines.size(), lines.stream().mapToInt(TaskCandidateResponse.Line::quantity).sum(), lines);
    }
}
