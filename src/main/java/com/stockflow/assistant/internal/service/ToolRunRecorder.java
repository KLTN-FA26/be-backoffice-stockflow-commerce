package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.ToolChannel;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Keeps the tool runs that belong to no conversation (design §5.5): what an MCP client and the "today" card read,
 * and for whom, in the same table as the chat's runs.
 *
 * <p><b>A record that fails does not fail the read.</b> The tools have already run and the caller is waiting for their
 * answer; the write goes in a transaction of its own, and a failure to store it is an ERROR in the log, with the tool
 * names and the user, so a gap in the trail is seen rather than silent.</p>
 */
@Service
public class ToolRunRecorder {

    private static final Logger log = LoggerFactory.getLogger(ToolRunRecorder.class);

    private final ToolRunRepository runs;
    private final TransactionTemplate own;

    ToolRunRecorder(ToolRunRepository runs, PlatformTransactionManager transactions) {
        this.runs = runs;
        this.own = new TransactionTemplate(transactions);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Stores {@code executions}, run for {@code userId} through {@code channel}; logs instead of throwing. */
    public void record(ToolChannel channel, UUID userId, List<ToolExecution> executions) {
        if (executions.isEmpty()) {
            return;
        }
        try {
            own.executeWithoutResult(status -> runs.saveStandalone(channel, userId, executions));
        } catch (RuntimeException failed) {
            log.error("Assistant {} tool runs {} for user {} could not be recorded", channel,
                    executions.stream().map(execution -> execution.call().name()).toList(), userId, failed);
        }
    }

    /**
     * What a watch read keeps in place of its result: the same lists are read every few minutes for every user, and
     * their rows would be most of the table while telling an auditor nothing the arguments do not. What the watch made
     * of them is in {@code assistant.watch_item} and the user's inbox.
     */
    static final String RESULT_NOT_KEPT = "{\"resultNotKept\":\"a watch read; what it found is in assistant.watch_item\"}";

    /** {@link #record}, each result replaced by {@link #RESULT_NOT_KEPT}: who read what, when, and how it ended. */
    public void recordReadsOnly(ToolChannel channel, UUID userId, List<ToolExecution> executions) {
        record(channel, userId, executions.stream().map(execution -> new ToolExecution(execution.call(),
                execution.outcome(), RESULT_NOT_KEPT, execution.durationMs())).toList());
    }

    /** Deletes standalone runs older than {@code cutoff}, at most {@code limit}, in a transaction of its own. */
    public int deleteBefore(Instant cutoff, int limit) {
        Integer deleted = own.execute(status -> runs.deleteStandaloneBefore(cutoff, limit));
        return deleted == null ? 0 : deleted;
    }
}
