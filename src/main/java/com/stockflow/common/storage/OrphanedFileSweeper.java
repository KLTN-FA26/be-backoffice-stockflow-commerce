package com.stockflow.common.storage;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds stored files no record names any more - an upload whose transaction died between storing and committing, a
 * cleanup that failed and was only logged, a product image deleted from its gallery - and, when told to, deletes them.
 *
 * <h2>What it never touches</h2>
 *
 * <ul>
 *   <li>A category no module claims ({@link StoredFileReferences}): supplier invoice scans (a replaced scan is kept as
 *       a financial record), published product images (a CDN may still serve them), generated exports and the
 *       quarantine prefix (their lifecycle rules expire them), derived copies (they go with their original).</li>
 *   <li>Anything younger than {@link OrphanSweepProperties#grace()}: a file whose transaction is still running.</li>
 * </ul>
 *
 * <p>Report-only by default ({@code delete=false}): the log says what would go. With versioning on, a delete is a
 * delete marker, recoverable; an object under Object Lock is refused by the store and the refusal is logged.</p>
 */
@Component
public class OrphanedFileSweeper {

    private static final Logger log = LoggerFactory.getLogger(OrphanedFileSweeper.class);
    private static final int LOGGED_KEYS = 20;

    private final FileStorage storage;
    private final List<StoredFileReferences> owners;
    private final OrphanSweepProperties properties;
    private final Clock clock;

    public OrphanedFileSweeper(FileStorage storage, List<StoredFileReferences> owners, OrphanSweepProperties properties,
                               Clock clock) {
        this.storage = storage;
        this.owners = List.copyOf(owners);
        this.properties = properties;
        this.clock = clock;
    }

    /** What a sweep found, and what it deleted. */
    public record Report(int checked, List<String> orphans, int deleted) {
        public Report {
            orphans = List.copyOf(orphans);
        }
    }

    @Scheduled(cron = "${stockflow.storage.orphan-sweep.cron:0 30 3 * * *}")
    @SchedulerLock(name = "storage.sweepOrphanedFiles", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void sweepNightly() {
        Report report = sweep();
        if (!report.orphans().isEmpty()) {
            log.warn("Orphaned files: {} of {} checked, {} deleted (delete={}); first: {}", report.orphans().size(),
                    report.checked(), report.deleted(), properties.delete(),
                    report.orphans().subList(0, Math.min(LOGGED_KEYS, report.orphans().size())));
        } else {
            log.info("Orphaned files: none among {} checked", report.checked());
        }
    }

    /** One pass over every claimed category. */
    public Report sweep() {
        Instant cutoff = clock.instant().minus(properties.grace());
        Map<FileCategory, List<StoredFileReferences>> byCategory = new EnumMap<>(FileCategory.class);
        for (StoredFileReferences owner : owners) {
            for (FileCategory category : owner.categories()) {
                byCategory.computeIfAbsent(category, any -> new ArrayList<>()).add(owner);
            }
        }
        int checked = 0;
        int deleted = 0;
        List<String> orphans = new ArrayList<>();
        for (var entry : byCategory.entrySet()) {
            String after = null;
            while (true) {
                List<StoredObject> page = storage.list(entry.getKey().prefix() + "/", after, properties.batch());
                if (page.isEmpty()) {
                    break;
                }
                after = page.stream().map(StoredObject::key).max(Comparator.naturalOrder()).orElseThrow();
                List<String> old = page.stream().filter(object -> object.lastModified().isBefore(cutoff))
                        .map(StoredObject::key).toList();
                checked += old.size();
                if (!old.isEmpty()) {
                    Set<String> referenced = new HashSet<>();
                    for (StoredFileReferences owner : entry.getValue()) {
                        referenced.addAll(owner.referenced(entry.getKey(), old));
                    }
                    for (String key : old) {
                        if (!referenced.contains(key)) {
                            orphans.add(key);
                            if (properties.delete() && delete(entry.getKey(), key)) {
                                deleted++;
                            }
                        }
                    }
                }
                if (page.size() < properties.batch()) {
                    break;
                }
            }
        }
        return new Report(checked, orphans, deleted);
    }

    private boolean delete(FileCategory category, String key) {
        try {
            if (StorageKeys.hasDisplayCopies(category)) {
                storage.delete(StorageKeys.evidenceCopyOf(key, EvidenceCopies.THUMBNAIL));
                storage.delete(StorageKeys.evidenceCopyOf(key, EvidenceCopies.DISPLAY));
            }
            storage.delete(key);
            return true;
        } catch (RuntimeException refused) {
            log.warn("Could not delete orphaned file {}", key, refused);
            return false;
        }
    }
}
