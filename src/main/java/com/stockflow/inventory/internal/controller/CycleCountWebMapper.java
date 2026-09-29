package com.stockflow.inventory.internal.controller;
import com.stockflow.inventory.internal.controller.dto.CycleCountResponse;
import com.stockflow.inventory.internal.service.CycleCountService;
import org.springframework.stereotype.Component;
@Component
public class CycleCountWebMapper {
    public CycleCountResponse toResponse(CycleCountService.Summary s) {
        return new CycleCountResponse(s.id(),s.warehouse(),s.assignedTo(),s.status(),s.version(),s.approvedBy(),s.note(),
                s.createdAt(),s.approvedAt(),s.postedAt(),
                s.lines().stream().map(l -> new CycleCountResponse.Line(l.stockId(),l.sku(),l.location(),l.lot(),l.serial(),
                        l.receivedAt(),l.expiry(),l.baseline(),l.counted(),l.reason(),l.onHand(),l.reserved(),
                        !"POSTED".equals(s.status()) && !"CANCELLED".equals(s.status()) && l.version()!=l.currentVersion())).toList());
    }
}
