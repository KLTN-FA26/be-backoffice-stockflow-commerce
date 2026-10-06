package com.stockflow.fulfillment.internal.controller;

import com.stockflow.fulfillment.api.FulfillmentArtifactAccess;
import com.stockflow.fulfillment.api.FulfillmentTask;
import com.stockflow.fulfillment.internal.controller.dto.FulfillmentArtifactAccessResponse;
import com.stockflow.fulfillment.internal.controller.dto.FulfillmentTaskResponse;
import org.springframework.stereotype.Component;

@Component
class FulfillmentWebMapper {
    FulfillmentTaskResponse toResponse(FulfillmentTask task) {
        return new FulfillmentTaskResponse(task.taskId(), task.orderId(), task.assignedUserId(),
                task.pickStatus(), task.packStatus(), task.assignedAt(), task.startedAt(), task.pickedAt(),
                task.packedAt(), task.integrityEvidence().stream()
                .map(e -> new FulfillmentTaskResponse.IntegrityEvidence(e.orderLineId(), e.snapshotId(),
                        e.expectedChecksum(), e.actualChecksum(), e.matching(), e.verifiedAt()))
                .toList());
    }

    FulfillmentArtifactAccessResponse toResponse(FulfillmentArtifactAccess access) {
        return new FulfillmentArtifactAccessResponse(access.artifactId(), access.role(), access.originalName(),
                access.contentType(), access.sizeBytes(), access.checksum(), access.url(), access.expiresAt());
    }
}
