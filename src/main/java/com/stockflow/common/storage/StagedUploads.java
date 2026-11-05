package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.lock.DistributedLock;
import com.stockflow.common.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Uploads that go from the browser straight to the object store, never through the backend.
 *
 * <h2>Why</h2>
 *
 * <p>A file sent through the backend ties up a request and the backend's bandwidth for the whole of the
 * slowest leg - a 50 MB design over a customer's connection, five phone photos over warehouse Wi-Fi -
 * and is written to disk twice on the way. Here the browser writes into a quarantine prefix
 * ({@code incoming/}) with a URL signed for exactly the declared file; the backend reads it once, over
 * the store's fast side, to check and scan it, and copies it into its category server-side.</p>
 *
 * <h2>The two steps</h2>
 *
 * <ol>
 *   <li>{@link #begin}: the owning module checks the caller may upload here, then this checks the
 *       declaration against the category, takes one from the caller's hourly budget, records a session
 *       in Redis and signs a PUT that carries the declared length, type and SHA-256 - the store refuses
 *       any other bytes.</li>
 *   <li>{@link #accept}: in the request that attaches the file to a record, before its transaction.
 *       The session must be the caller's and of this category; the stored object must have the declared
 *       size and digest; its bytes must look like the declared type, pass the scanner and hash to the
 *       declared digest. Then it is copied to {@code <category>/<date>/<uploadId>.<ext>} and handed back as
 *       {@link PreparedFiles}, which the record's transaction adopts.</li>
 * </ol>
 *
 * <p>An upload is attached once. While one request is attaching it, another gets 409; after the
 * transaction commits, the session remembers the key ({@link #attachedKey}), so a retry is answered
 * from the record instead of attaching twice. Nothing in {@code incoming/} is ever served: the bucket
 * policy refuses signed reads of it, and its lifecycle rule deletes whatever was never attached.</p>
 */
@Component
public class StagedUploads {

    private static final Logger log = LoggerFactory.getLogger(StagedUploads.class);
    private static final String SESSION = "upload:session:";
    /** Long enough for a scan of the largest file; a dead holder releases it by expiry. */
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(5);

    private final FileStorage storage;
    private final StagedUploadProperties properties;
    private final UploadInspection inspection;
    private final StringRedisTemplate redis;
    private final DistributedLock locks;
    private final RateLimiter limiter;
    private final Clock clock;
    private final ImageRenditions images;

    /** With its own image renderer: the shape the tests construct. */
    public StagedUploads(FileStorage storage, StagedUploadProperties properties, UploadInspection inspection,
                         StringRedisTemplate redis, DistributedLock locks, RateLimiter limiter, Clock clock) {
        this(storage, properties, inspection, redis, locks, limiter, clock, new ImageRenditions());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public StagedUploads(FileStorage storage, StagedUploadProperties properties, UploadInspection inspection,
                         StringRedisTemplate redis, DistributedLock locks, RateLimiter limiter, Clock clock,
                         ImageRenditions images) {
        this.images = images;
        this.storage = storage;
        this.properties = properties;
        this.inspection = inspection;
        this.redis = redis;
        this.locks = locks;
        this.limiter = limiter;
        this.clock = clock;
    }

    /**
     * A slot for one file of {@code category}, owned by {@code ownerId}. The caller has already checked that the
     * owner may upload to the record it is for.
     *
     * @throws BusinessException {@code UNSUPPORTED_MEDIA_TYPE}, {@code PAYLOAD_TOO_LARGE}, {@code VALIDATION_FAILED}
     *         for a declaration the category refuses; {@code RATE_LIMITED} past the hourly budget
     */
    public UploadSlot begin(FileCategory category, UUID ownerId, UploadSlotRequest request) {
        if (ownerId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        String type = ContentTypePolicy.normalise(request.contentType());
        // No bytes yet: the type and size are checked now, the signature of the bytes when the file is attached.
        ContentTypePolicy.check(category, type, request.sizeBytes(), new byte[0]);
        if (request.fileName() == null || request.fileName().isBlank() || request.fileName().length() > 255) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A file name of at most 255 characters is required");
        }
        if (!Checksums.isSha256Hex(request.sha256())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "sha256 must be a lower-case hex SHA-256");
        }
        if (!limiter.tryAcquire("staged-upload:" + ownerId, properties.perUserPerHour(), Duration.ofHours(1)).allowed()) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "Too many uploads; try again later");
        }
        UUID uploadId = Identifiers.newId();
        Instant now = clock.instant();
        String incoming = StorageKeys.incoming(category, uploadId, now);
        Map<String, String> session = new HashMap<>();
        session.put("owner", ownerId.toString());
        session.put("category", category.name());
        session.put("name", request.fileName());
        session.put("type", type);
        session.put("size", Long.toString(request.sizeBytes()));
        session.put("sha256", request.sha256());
        session.put("created", now.toString());
        session.put("incoming", incoming);
        redis.opsForHash().putAll(SESSION + uploadId, session);
        redis.expire(SESSION + uploadId, properties.sessionTtl());
        PresignedUpload put = storage.presignedUploadUrl(incoming, type, request.sizeBytes(), request.sha256(),
                properties.urlTtl());
        return new UploadSlot(uploadId, "PUT", put.url(), put.headers(), now.plus(properties.urlTtl()));
    }

    /**
     * The key {@code uploadId} was attached under, when a committed transaction attached it - so a retry of the
     * attaching request finds its record instead of attaching the file again. Empty while it is not attached, and
     * for an upload that is not the caller's.
     */
    public Optional<String> attachedKey(FileCategory category, UUID ownerId, UUID uploadId) {
        Map<String, String> session = read(uploadId);
        if (session.isEmpty() || !owns(session, category, ownerId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(session.get("attached"));
    }

    /**
     * Checks, scans and moves the named uploads into {@code category}, for a transaction to adopt. All or nothing;
     * several are checked side by side. Call it outside any transaction: it reads every byte of every file.
     *
     * @throws BusinessException {@code NOT_FOUND} for an upload that is not the caller's or has expired;
     *         {@code CONFLICT} for one already attached or being attached; {@code VALIDATION_FAILED} for one not
     *         received, or whose bytes differ from the declaration, or that fails the scan;
     *         {@code UNSUPPORTED_MEDIA_TYPE} for bytes of another type
     */
    public PreparedFiles accept(FileCategory category, UUID ownerId, List<UUID> uploadIds) {
        if (uploadIds.isEmpty()) {
            return new PreparedFiles(storage, List.of());
        }
        if (new HashSet<>(uploadIds).size() != uploadIds.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "An upload is named twice");
        }
        List<DistributedLock.Handle> claims = new ArrayList<>();
        try {
            for (UUID uploadId : uploadIds) {
                claims.add(locks.tryAcquire("staged-upload:" + uploadId, CLAIM_LEASE).orElseThrow(() ->
                        new BusinessException(ErrorCode.CONFLICT, "Upload " + uploadId + " is being attached")));
            }
            List<Accepted> accepted = acceptAll(category, ownerId, uploadIds);
            List<PreparedFiles.PreparedFile> files = accepted.stream().map(Accepted::file).toList();
            Runnable afterCommit = () -> accepted.forEach(this::attached);
            Runnable afterwards = () -> claims.forEach(DistributedLock.Handle::close);
            return new PreparedFiles(storage, files, afterCommit, afterwards);
        } catch (RuntimeException failure) {
            claims.forEach(DistributedLock.Handle::close);
            throw failure;
        }
    }

    private record Accepted(UUID uploadId, String incoming, PreparedFiles.PreparedFile file) {
    }

    private List<Accepted> acceptAll(FileCategory category, UUID ownerId, List<UUID> uploadIds) {
        if (uploadIds.size() == 1) {
            return List.of(acceptOne(category, ownerId, uploadIds.get(0)));
        }
        List<Future<Accepted>> pending = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (UUID uploadId : uploadIds) {
                pending.add(executor.submit(() -> acceptOne(category, ownerId, uploadId)));
            }
        }
        List<Accepted> accepted = new ArrayList<>();
        RuntimeException failure = null;
        for (Future<Accepted> result : pending) {
            try {
                accepted.add(result.get());
            } catch (ExecutionException ex) {
                if (failure == null) {
                    failure = ex.getCause() instanceof RuntimeException runtime ? runtime
                            : new StorageException("Could not accept an upload", ex.getCause());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (failure == null) {
                    failure = new StorageException("Interrupted while accepting uploads", interrupted);
                }
            }
        }
        if (failure != null) {
            new PreparedFiles(storage, accepted.stream().map(Accepted::file).toList()).close();
            throw failure;
        }
        return accepted;
    }

    private Accepted acceptOne(FileCategory category, UUID ownerId, UUID uploadId) {
        Map<String, String> session = read(uploadId);
        if (session.isEmpty() || !owns(session, category, ownerId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "No upload " + uploadId);
        }
        if (session.containsKey("attached")) {
            throw new BusinessException(ErrorCode.CONFLICT, "Upload " + uploadId + " is already attached");
        }
        String incoming = session.get("incoming");
        String type = session.get("type");
        long size = Long.parseLong(session.get("size"));
        String sha256 = session.get("sha256");
        if (!storage.exists(incoming)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Upload " + uploadId + " has not been received");
        }
        ObjectStat stat = storage.stat(incoming);
        if (stat.sizeBytes() != size || !sha256.equals(stat.sha256())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Upload " + uploadId + " differs from the file that was declared");
        }
        // Spooled once to a temporary file, as a multipart upload is: its first bytes, size and digest checked on the
        // way, then scanned, then - a PDF - inspected for active content, an evidence photo rendered.
        String key = StorageKeys.forUpload(category, type, uploadId, Instant.parse(session.get("created")));
        List<String> copies;
        try (InputStream raw = storage.download(incoming);
             BufferedUpload buffered = BufferedUpload.read(category, new FileUpload(session.get("name"), type, size, raw))) {
            if (!sha256.equals(buffered.checksum())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Upload " + uploadId + " differs from the file that was declared");
            }
            try (InputStream content = buffered.open()) {
                inspection.requireClean(content);
            }
            PdfContentPolicy.check(category, type, buffered.path());
            storage.copy(incoming, key);
            try (InputStream content = buffered.open()) {
                copies = StorageKeys.hasDisplayCopies(category)
                        ? EvidenceCopies.store(storage, images, category, key, content.readAllBytes()) : List.of();
            } catch (IOException | RuntimeException failure) {
                storage.delete(key);
                throw failure;
            }
        } catch (IOException unreadable) {
            throw new StorageException("Could not read upload " + uploadId, unreadable);
        }
        var file = new StoredFile(key, session.get("name"), type, size, clock.instant());
        return new Accepted(uploadId, incoming, new PreparedFiles.PreparedFile(file, sha256, copies));
    }

    /** After the attaching transaction committed: the session remembers the key; the quarantined copy goes. */
    private void attached(Accepted accepted) {
        try {
            redis.opsForHash().put(SESSION + accepted.uploadId(), "attached", accepted.file().file().key());
        } catch (RuntimeException failure) {
            log.warn("Could not mark upload {} attached; a retry will be refused as a conflict", accepted.uploadId(),
                    failure);
        }
        try {
            storage.delete(accepted.incoming());
        } catch (RuntimeException failure) {
            // The lifecycle rule on incoming/ removes it within a day.
            log.warn("Could not delete quarantined upload {}", accepted.incoming(), failure);
        }
    }

    private Map<String, String> read(UUID uploadId) {
        Map<Object, Object> raw = redis.opsForHash().entries(SESSION + uploadId);
        Map<String, String> session = new HashMap<>();
        raw.forEach((field, value) -> session.put(String.valueOf(field), String.valueOf(value)));
        return session;
    }

    private static boolean owns(Map<String, String> session, FileCategory category, UUID ownerId) {
        return ownerId != null && ownerId.toString().equals(session.get("owner"))
                && category.name().equals(session.get("category"));
    }
}
