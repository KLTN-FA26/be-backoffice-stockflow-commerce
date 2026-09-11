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
    StoredFile store(FileCategory category, String originalName, String contentType,
                     long sizeBytes, InputStream content);

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
    String presignedDownloadUrl(String key, Duration validFor);

    /**
     * Delete a stored file.
     *
     * <p>Idempotent: deleting something that is not there succeeds. A delete that failed because the
     * object was already gone would make every retry and every cleanup job fragile.</p>
     */
    void delete(String key);

    boolean exists(String key);
}
