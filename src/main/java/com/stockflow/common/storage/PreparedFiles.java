package com.stockflow.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Uploads already checked, scanned and stored - before any transaction began - waiting for the
 * short transaction that attaches them to a record.
 *
 * <p>The point is what is <i>not</i> held while they are prepared: no row lock, no pooled connection.
 * Scanning 50 MB and writing it to the object store takes seconds; under a {@code FOR UPDATE} it made
 * every other writer of the record wait that long, and with the scanner slow it drained the pool.</p>
 *
 * <p>Use in a try-with-resources around the transaction:</p>
 * <pre>{@code
 * try (PreparedFiles photos = files.prepare(FileCategory.QC_EVIDENCE, uploads)) {
 *     return tx.execute(status -> {
 *         ... lock, check again, attach photos.files() ...
 *         photos.adopt();
 *     });
 * }
 * }</pre>
 * <p>{@link #adopt} hands the objects to the current transaction: kept when it commits (or its outcome
 * is unknown - a row may reference them), deleted when it rolls back. {@link #close} deletes whatever
 * no transaction adopted: a replayed request answered with its earlier result, a check that failed
 * after the files were stored.</p>
 */
public final class PreparedFiles implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PreparedFiles.class);

    /**
     * One stored upload, the SHA-256 of its bytes (lower-case hex), and the keys of the copies derived from it (an
     * evidence photo's display copies), which go wherever the file goes.
     */
    public record PreparedFile(StoredFile file, String sha256, List<String> derivedKeys) {
        public PreparedFile {
            derivedKeys = derivedKeys == null ? List.of() : List.copyOf(derivedKeys);
        }

        public PreparedFile(StoredFile file, String sha256) {
            this(file, sha256, List.of());
        }
    }

    private final FileStorage storage;
    private final List<PreparedFile> files;
    private final Runnable afterCommit;
    private final Runnable afterwards;
    private final AtomicBoolean adopted = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();

    PreparedFiles(FileStorage storage, List<PreparedFile> files) {
        this(storage, files, () -> { }, () -> { });
    }

    /**
     * @param afterCommit run once the adopting transaction commits (best effort: logged, never thrown)
     * @param afterwards  run exactly once when the files are settled: after the adopting transaction completes,
     *                    or on {@link #close} when none adopted them
     */
    PreparedFiles(FileStorage storage, List<PreparedFile> files, Runnable afterCommit, Runnable afterwards) {
        this.storage = storage;
        this.files = List.copyOf(files);
        this.afterCommit = afterCommit;
        this.afterwards = afterwards;
    }

    public List<PreparedFile> files() {
        return files;
    }

    /** The only file, for an endpoint that takes exactly one. */
    public PreparedFile single() {
        if (files.size() != 1) {
            throw new IllegalStateException("Expected exactly one prepared file, have " + files.size());
        }
        return files.get(0);
    }

    /**
     * Hands every file to the current transaction. Idempotent: adopting twice (a retried callback)
     * registers the cleanup once.
     *
     * @throws IllegalStateException outside a transaction
     */
    public void adopt() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Prepared files are adopted by an active database transaction");
        }
        if (!adopted.compareAndSet(false, true)) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    afterCommit.run();
                } catch (RuntimeException failure) {
                    log.warn("Follow-up of committed uploads failed", failure);
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    discard("rolled back");
                }
                finish();
            }
        });
    }

    /** Deletes the files unless a transaction adopted them. */
    @Override
    public void close() {
        if (!adopted.get()) {
            discard("never attached");
            finish();
        }
    }

    private void finish() {
        if (finished.compareAndSet(false, true)) {
            try {
                afterwards.run();
            } catch (RuntimeException failure) {
                log.warn("Releasing prepared uploads failed", failure);
            }
        }
    }

    private void discard(String why) {
        for (PreparedFile prepared : files) {
            List<String> keys = new java.util.ArrayList<>(prepared.derivedKeys());
            keys.add(prepared.file().key());
            for (String key : keys) {
                try {
                    storage.delete(key);
                } catch (RuntimeException cleanupFailure) {
                    log.error("Orphaned upload requires cleanup ({}): {}", why, key, cleanupFailure);
                }
            }
        }
    }
}
