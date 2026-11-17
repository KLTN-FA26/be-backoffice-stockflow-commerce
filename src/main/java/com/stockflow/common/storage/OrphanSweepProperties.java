package com.stockflow.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The orphaned-file sweep ({@link OrphanedFileSweeper}), bound from {@code stockflow.storage.orphan-sweep.*}.
 *
 * @param delete off by default: the sweep only reports what it would delete, so the first runs on real data are read
 *               before anything goes. With versioning on (infra/aws) a delete is a delete marker, recoverable for the
 *               noncurrent-version period.
 * @param grace  how old an object must be before it may be called an orphan - far longer than any upload takes to be
 *               attached, so a file whose transaction is still running is never touched
 * @param batch  keys listed and checked per round
 */
@ConfigurationProperties(prefix = "stockflow.storage.orphan-sweep")
public record OrphanSweepProperties(
        @DefaultValue("false") boolean delete,
        @DefaultValue("P7D") Duration grace,
        @DefaultValue("500") int batch
) {

    public OrphanSweepProperties {
        if (grace == null || grace.compareTo(Duration.ofDays(1)) < 0) {
            throw new IllegalArgumentException("stockflow.storage.orphan-sweep.grace must be at least a day");
        }
        if (batch < 1 || batch > 1000) {
            throw new IllegalArgumentException("stockflow.storage.orphan-sweep.batch must be 1 to 1000");
        }
    }
}
