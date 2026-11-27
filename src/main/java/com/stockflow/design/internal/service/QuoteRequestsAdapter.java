package com.stockflow.design.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.design.api.QuoteRequests;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** {@link QuoteRequests} over the request services, which read and close requests for the design's own pages too. */
@Component
class QuoteRequestsAdapter implements QuoteRequests {

    private final DesignQuoteRequestService designRequests;
    private final DesignProjectQuoteRequestService projectRequests;

    QuoteRequestsAdapter(DesignQuoteRequestService designRequests, DesignProjectQuoteRequestService projectRequests) {
        this.designRequests = designRequests;
        this.projectRequests = projectRequests;
    }

    @Override
    public Optional<DesignRequest> designRequest(UUID requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        return designRequests.find(requestId).map(v -> new DesignRequest(v.id(), v.designId(), v.designName(),
                v.designStatus().name(), v.customerId(), v.sku(), v.customerSku(), v.returnReason(), v.cartons(),
                v.desiredDeliveryDate(), v.status().name(), v.requestedAt(), v.closedAt()));
    }

    @Override
    public Optional<ProjectRequest> projectRequest(UUID requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        return projectRequests.find(requestId).map(QuoteRequestsAdapter::toProjectRequest);
    }

    @Override
    public Optional<ProjectRequest> lockProjectRequest(UUID requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(toProjectRequest(projectRequests.lockedView(requestId)));
        } catch (BusinessException e) {
            if (e.errorCode() == ErrorCode.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public Optional<UUID> openProjectRequestOf(UUID designId) {
        return designId == null ? Optional.empty() : projectRequests.openRequestOf(designId);
    }

    private static ProjectRequest toProjectRequest(DesignProjectQuoteRequestService.ProjectQuoteRequestView v) {
        return new ProjectRequest(v.id(), v.projectId(), v.projectName(), v.customerId(), v.desiredDeliveryDate(),
                v.requestedAt(), v.status().name(), v.lines().stream()
                .map(l -> new ProjectLine(l.designId(), l.designName(), l.designStatus().name(), l.customerSku(),
                        l.cartons(), l.returnReason())).toList(), v.closeReason());
    }

    @Override
    public void close(UUID requestId, UUID salesUserId) {
        designRequests.close(requestId, salesUserId);
    }

    @Override
    public void closeProject(UUID requestId, UUID salesUserId) {
        projectRequests.close(requestId, salesUserId);
    }
}
