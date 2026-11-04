package com.stockflow.assistant.internal.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Where tool runs that belong to no conversation are kept (design §5.5): an MCP client's calls, the "today" card's
 * reads, background checks. A chat run is stored with its turn by {@link ConversationRepository}.
 */
public interface ToolRunRepository {

    /** Stores {@code executions}, run for {@code userId} through {@code channel}, in the order given. */
    void saveStandalone(ToolChannel channel, UUID userId, List<ToolExecution> executions);

    /** Deletes standalone runs older than {@code cutoff}, oldest first, at most {@code limit}; how many went. */
    int deleteStandaloneBefore(Instant cutoff, int limit);
}
