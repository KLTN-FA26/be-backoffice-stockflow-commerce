package com.stockflow.inventory.internal.controller;
import com.stockflow.inventory.internal.domain.StockAlert;
import com.stockflow.inventory.internal.controller.dto.*;
import com.stockflow.notification.api.AlertDeliverySummary;
import org.springframework.stereotype.Component;
@Component
public class StockAlertWebMapper {
    public StockAlertResponse toResponse(StockAlert a){return new StockAlertResponse(a.id(),a.sku(),a.kind(),a.status(),a.quantity(),a.threshold(),a.openedAt(),a.lastObservedAt(),a.resolvedAt(),a.resolutionReason(),a.acknowledgedBy(),a.acknowledgedAt());}
    public AlertDeliveryResponse toResponse(AlertDeliverySummary d){return new AlertDeliveryResponse(d.id(),d.phase(),d.recipient(),d.status(),d.attempts(),d.nextAttemptAt(),d.sentAt(),d.lastError());}
}
