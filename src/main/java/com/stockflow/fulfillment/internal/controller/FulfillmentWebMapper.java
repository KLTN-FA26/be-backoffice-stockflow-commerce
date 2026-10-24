package com.stockflow.fulfillment.internal.controller;

import com.stockflow.fulfillment.api.FulfillmentArtifactAccess;
import com.stockflow.fulfillment.api.FulfillmentAssignee;
import com.stockflow.fulfillment.api.FulfillmentTask;
import com.stockflow.fulfillment.internal.controller.dto.AssigneeResponse;
import com.stockflow.fulfillment.internal.controller.dto.FulfillmentArtifactAccessResponse;
import com.stockflow.fulfillment.internal.controller.dto.FulfillmentTaskResponse;
import com.stockflow.fulfillment.internal.controller.dto.PickLineResponse;
import com.stockflow.fulfillment.internal.controller.dto.PutBackRowResponse;
import com.stockflow.fulfillment.internal.service.PickLists;
import org.springframework.stereotype.Component;

@Component
class FulfillmentWebMapper {
    FulfillmentTaskResponse toResponse(FulfillmentTask task) {
        return new FulfillmentTaskResponse(task.taskId(), task.orderId(), task.orderNumber(), task.warehouseId(),
                task.assignedUserId(), task.assignedUserName(), task.pickStatus(), task.packStatus(),
                task.assignedAt(), task.startedAt(), task.pickedAt(), task.packedAt(),
                task.integrityEvidence().stream()
                .map(e -> new FulfillmentTaskResponse.IntegrityEvidence(e.orderLineId(), e.snapshotId(),
                        e.expectedChecksum(), e.actualChecksum(), e.matching(), e.verifiedAt()))
                .toList(),
                task.lines() == null ? null : task.lines().stream()
                        .map(l -> new FulfillmentTaskResponse.TaskLine(l.lineId(), l.sku(), l.quantity(),
                                l.designSnapshotId(), l.printLine()))
                        .toList(),
                task.paymentDue() == null ? null : new FulfillmentTaskResponse.PaymentDue(
                        task.paymentDue().amountDueBeforeHandover(), task.paymentDue().currency()),
                task.shipmentId());
    }

    AssigneeResponse toResponse(FulfillmentAssignee assignee) {
        return new AssigneeResponse(assignee.userId(), assignee.displayName(), assignee.roles());
    }

    FulfillmentArtifactAccessResponse toResponse(FulfillmentArtifactAccess access) {
        return new FulfillmentArtifactAccessResponse(access.artifactId(), access.role(), access.originalName(),
                access.contentType(), access.sizeBytes(), access.checksum(), access.url(), access.expiresAt());
    }

    PickLineResponse toResponse(PickLists.PickLineView line) {
        return new PickLineResponse(line.lineId(), line.taskId(), line.sequenceNo(), line.orderLineId(), line.sku(),
                line.barcode(), line.locationCode(), line.lotNumber(), line.allocatedQty(), line.pickedQty(),
                line.status(), line.pickedAt(), line.putBackQty(), line.putBackLocationCode(), line.putBackAt(),
                line.shortQty());
    }

    PutBackRowResponse toResponse(PickLists.PutBackRow row) {
        return new PutBackRowResponse(row.taskId(), row.orderNumber(), row.warehouseId(), row.lineId(), row.sku(),
                row.lotNumber(), row.pickedQty(), row.fromLocationCode(), row.pickedAt());
    }
}
