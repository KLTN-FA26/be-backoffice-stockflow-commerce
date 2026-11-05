package com.stockflow.common.storage;

import java.io.InputStream;
import java.time.Duration;

/**
 * Where uploaded and generated files live.
 *
 * <p>A port with two implementations: {@code S3FileStorage} against MinIO or S3, and
 * {@code LocalFileStorage} for a developer with no object store running. Application code names
 * only this interface, so which one is active is a profile decision rather than a code change.</p>
 *
 * <h2>Files are not served through the application</h2>
 *
 * <p>{@link #presignedDownloadUrl} exists so downloads go straight from the object store to the
 * browser. Streaming them through the application instead would tie up a request thread for the
 * length of a 50 MB transfer over a phone connection, and — worse — serve untrusted user content
 * from the application's own origin, where a crafted SVG or HTML file becomes stored cross-site
 * scripting against every logged-in user. {@link #download} exists for server-side processing, not
 * for handing bytes to a browser.</p>
 */
public interface FileStorage {

    /**
     * Store a file and return where it went.
     *
     * <p>The implementation generates the key. Callers pass a category and the original name;
     * anything else — a caller-chosen path, a filename used verbatim — would let one upload
     * overwrite another, or escape its prefix.</p>
     *
     * @throws StorageException if the store is unreachable or refuses the write
     */
    default StoredFile store(FileCategory category, String originalName, String contentType,
                             long sizeBytes, InputStream content) {
        return store(category, originalName, contentType, sizeBytes, content, null);
    }

    /**
     * {@link #store(FileCategory, String, String, long, InputStream)}, with the SHA-256 the caller
     * already computed over the same bytes.
     *
     * <p>Every object is written with a store-computed SHA-256, so {@link #stat} can answer for it
     * later without a read. Passing {@code sha256} as well makes the store <i>check</i> the bytes it
     * received against it and refuse the write on a difference: the digest the database records is
     * then provably the digest of the object, end to end.</p>
     *
     * @param sha256 lower-case hex, or null when the caller has none
     * @throws StorageException if the store is unreachable, refuses the write, or the bytes it
     *         received do not hash to {@code sha256}
     */
    StoredFile store(FileCategory category, String originalName, String contentType,
                     long sizeBytes, InputStream content, String sha256);

    /**
     * What the store knows about an object, from its metadata alone (one HEAD request; no bytes).
     *
     * <p>This is how integrity is checked where a file is used: the store recorded the object's
     * SHA-256 when it was written and recomputes it on any later write, so comparing
     * {@link ObjectStat#sha256()} with the digest recorded at upload proves the object unchanged
     * without downloading it. A null digest proves nothing; the caller hashes the bytes.</p>
     *
     * @throws StorageException if the key does not exist or the store cannot be reached
     */
    ObjectStat stat(String key);

    /**
     * Locks the current version of an object until {@code until} (S3 Object Lock, in the mode
     * {@link ObjectLockProperties} configures): it cannot be deleted or overwritten in place until
     * then. Only for business records ({@link RecordRetention}); call it through
     * {@link FileTransfers#retainAfterCommit}, never on an object a transaction may still roll back.
     *
     * @throws StorageException if the bucket has no Object Lock, or the store refuses (a lock can be
     *         extended but never shortened)
     */
    void retain(String key, java.time.Instant until);

    /**
     * A signed PUT a browser uses to write exactly the declared file to {@code incomingKey}: its length, type and
     * SHA-256 are part of the signature, so the store refuses anything else. Only for keys under
     * {@link StorageKeys#incoming}; {@link StagedUploads} is the one caller.
     *
     * @throws StorageException when the provider cannot sign browser uploads (the local filesystem)
     */
    PresignedUpload presignedUploadUrl(String incomingKey, String contentType, long sizeBytes, String sha256,
                                       Duration validFor);

    /**
     * Open a stored file for server-side processing.
     *
     * <p>The caller must close the stream. Not for serving to a browser — see the class javadoc.</p>
     *
     * @throws StorageException if the key does not exist or cannot be read
     */
    InputStream download(String key);

    /**
     * A time-limited URL the browser can fetch directly.
     *
     * <p>Keep {@code validFor} short. The URL carries its own authorisation, so anyone who obtains
     * it — from a chat message, a proxy log, a browser history — can fetch the file until it
     * expires. Minutes, not days.</p>
     */
    default String presignedDownloadUrl(String key, Duration validFor) {
        return presignedDownloadUrl(key, validFor, null);
    }

    /**
     * {@link #presignedDownloadUrl(String, Duration)}, telling the browser how to treat the response: save it under
     * its original name, or show it in the page and cache it while the link lives ({@link DownloadDisposition}).
     * The overrides are part of the signature, so a holder of the link cannot change them.
     *
     * @param disposition null to serve the object's own headers
     */
    String presignedDownloadUrl(String key, Duration validFor, DownloadDisposition disposition);

    /**
     * Delete a stored file.
     *
     * <p>Idempotent: deleting something that is not there succeeds. A delete that failed because the
     * object was already gone would make every retry and every cleanup job fragile.</p>
     */
    void delete(String key);

    boolean exists(String key);

    /**
     * Copy an object to another key in the same store, overwriting the target.
     *
     * <p>Server-side where the store supports it, so publishing an image does not pull its bytes
     * through the application. Idempotent: copying twice leaves the same object.</p>
     *
     * @throws StorageException if the source does not exist or the store refuses the copy
     */
    void copy(String sourceKey, String targetKey);
}
