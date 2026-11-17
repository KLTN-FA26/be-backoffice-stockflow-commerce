package com.stockflow.order.internal.domain;

import java.util.Optional;
import java.util.UUID;

/** Persistence port of the producer catalogue (4A-1). */
public interface ProducerRepository {

    Optional<Producer> findById(UUID id);

    boolean codeTaken(String code);

    /**
     * Writes a new producer, or the changes of one read before: the version it was read at must still be the stored one.
     *
     * @throws com.stockflow.common.error.BusinessException {@code OPTIMISTIC_LOCK} when it changed meanwhile
     */
    Producer save(Producer producer);
}
