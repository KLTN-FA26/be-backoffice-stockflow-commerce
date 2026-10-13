package com.stockflow.fulfillment.internal.controller;

import com.stockflow.fulfillment.api.FulfillmentArtifactAccess;
import com.stockflow.fulfillment.api.FulfillmentAssignee;
import com.stockflow.fulfillment.api.FulfillmentTask;
import com.stockflow.fulfillment.internal.controller.dto.AssigneeResponse;
import com.stockflow.fulfillment.internal.controller.dto.FulfillmentArtifactAccessResponse;
import com.stockflow.fulfillment.internal.controller.dto.FulfillmentTaskResponse;
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
                        .toList());
    }

    AssigneeResponse toResponse(FulfillmentAssignee assignee) {
        return new AssigneeResponse(assignee.userId(), assignee.displayName(), assignee.roles());
    }

    FulfillmentArtifactAccessResponse toResponse(FulfillmentArtifactAccess access) {
        return new FulfillmentArtifactAccessResponse(access.artifactId(), access.role(), access.originalName(),
                access.contentType(), access.sizeBytes(), access.checksum(), access.url(), access.expiresAt());
    }
}
