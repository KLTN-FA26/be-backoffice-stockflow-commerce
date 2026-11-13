package com.stockflow.design.internal.service;

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
        return projectRequests.find(requestId).map(v -> new ProjectRequest(v.id(), v.projectId(), v.projectName(),
                v.customerId(), v.desiredDeliveryDate(), v.requestedAt(), v.status().name(), v.lines().stream()
                .map(l -> new ProjectLine(l.designId(), l.designName(), l.designStatus().name(), l.customerSku(),
                        l.cartons())).toList()));
    }

    @Override
    public void close(UUID requestId, UUID salesUserId) {
        designRequests.close(requestId, salesUserId);
    }
}
