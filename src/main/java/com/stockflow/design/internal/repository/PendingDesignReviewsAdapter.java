package com.stockflow.design.internal.repository;

import com.stockflow.product.spi.PendingDesignReviews;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** The design module's answer to {@link PendingDesignReviews}: one grouped count over the designs. */
@Component
class PendingDesignReviewsAdapter implements PendingDesignReviews {

    private final DesignDraftJpaRepository drafts;

    PendingDesignReviewsAdapter(DesignDraftJpaRepository drafts) {
        this.drafts = drafts;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> countByTemplate(Collection<UUID> templateIds) {
        if (templateIds == null || templateIds.isEmpty()) {
            return Map.of();
        }
        return drafts.pendingReviewsOn(templateIds).stream().collect(Collectors.toMap(
                DesignDraftJpaRepository.PendingOn::getTemplateId, DesignDraftJpaRepository.PendingOn::getPending));
    }
}
