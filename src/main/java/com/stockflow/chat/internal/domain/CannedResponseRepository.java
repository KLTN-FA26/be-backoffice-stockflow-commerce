package com.stockflow.chat.internal.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for {@link CannedResponse}. */
public interface CannedResponseRepository {

    Optional<CannedResponse> findById(UUID id);

    /**
     * The replies {@code agentId} sees - their own and the shared ones, archived ones never - shared first, then by
     * title; only those whose title, body or shortcut holds {@code search}, and of {@code category}, when given.
     */
    List<CannedResponse> visibleTo(UUID agentId, String search, String category);

    /** Inserts or stores; {@code VALIDATION_FAILED} when its shortcut is taken among the owner's live replies. */
    CannedResponse save(CannedResponse response);
}
