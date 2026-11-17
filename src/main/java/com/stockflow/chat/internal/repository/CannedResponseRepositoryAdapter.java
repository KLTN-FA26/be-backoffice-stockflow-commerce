package com.stockflow.chat.internal.repository;

import com.stockflow.chat.internal.domain.CannedResponse;
import com.stockflow.chat.internal.domain.CannedResponseRepository;
import com.stockflow.chat.internal.entity.CannedResponseJpaEntity;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CannedResponseRepository} over JPA. An agent sees a few dozen replies at most, so the search narrows the list
 * in memory rather than in SQL. A shortcut taken is checked before {@code uk_canned_response_shortcut} would refuse the
 * insert, so the agent is told which field to change rather than shown a constraint name.
 */
@Repository
class CannedResponseRepositoryAdapter implements CannedResponseRepository {

    private final CannedResponseJpaRepository responses;

    CannedResponseRepositoryAdapter(CannedResponseJpaRepository responses) {
        this.responses = responses;
    }

    @Override
    public Optional<CannedResponse> findById(UUID id) {
        return responses.findById(id).map(CannedResponseRepositoryAdapter::toDomain);
    }

    @Override
    public List<CannedResponse> visibleTo(UUID agentId, String search, String category) {
        String needle = search == null || search.isBlank() ? null : search.strip().toLowerCase(Locale.ROOT);
        String kind = category == null || category.isBlank() ? null : category.strip();
        return responses.visibleTo(agentId).stream().map(CannedResponseRepositoryAdapter::toDomain)
                .filter(r -> kind == null || kind.equalsIgnoreCase(r.category()))
                .filter(r -> needle == null || r.title().toLowerCase(Locale.ROOT).contains(needle)
                        || r.body().toLowerCase(Locale.ROOT).contains(needle)
                        || (r.shortcut() != null && r.shortcut().contains(needle)))
                .toList();
    }

    @Override
    public CannedResponse save(CannedResponse response) {
        if (response.shortcut() != null && response.archivedAt() == null
                && responses.shortcutTaken(response.ownerId(), response.shortcut(), response.id())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The shortcut /" + response.shortcut() + " is already used by another saved reply");
        }
        CannedResponseJpaEntity entity = responses.findById(response.id())
                .orElseGet(() -> new CannedResponseJpaEntity(response.id(), response.ownerId()));
        entity.apply(response.title(), response.body(), response.shortcut(), response.category(),
                response.archivedAt());
        return toDomain(responses.saveAndFlush(entity));
    }

    private static CannedResponse toDomain(CannedResponseJpaEntity e) {
        return new CannedResponse(e.getId(), e.getOwnerId(), e.getTitle(), e.getBody(), e.getShortcut(),
                e.getCategory(), e.getArchivedAt(), e.getVersion());
    }
}
