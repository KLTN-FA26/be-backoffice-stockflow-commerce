package com.stockflow.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;

/**
 * {@link FileStorage} on the local filesystem, for development.
 *
 * <p>It exists so that a developer can run and test every upload path without MinIO — one fewer
 * container, and no "works on the server, fails locally". It is selected by
 * {@code stockflow.storage.provider=local} and is never the default, so it cannot be reached in an
 * environment that meant to use S3.</p>
 *
 * <h2>What it does not do</h2>
 *
 * <p>{@link #presignedDownloadUrl} returns a {@code file:} URI, which no browser will fetch from a
 * web page. That is honest rather than convenient: pretending to support signed URLs would let a
 * download flow appear to work locally and fail only in staging. Local development that needs real
 * download URLs runs MinIO.</p>
 *
 * <p>Also single-node by definition, so it must never be selected for a deployment with more than
 * one instance — each would hold different files.</p>
 */
@Component
@ConditionalOnProperty(prefix = "stockflow.storage", name = "provider", havingValue = "local")
class LocalFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);

    private final Path root;
    private final Clock clock;

    LocalFileStorage(StorageProperties properties, Clock clock) {
        this.root = Path.of(properties.localDirectory()).toAbsolutePath().normalize();
        this.clock = clock;
        try {
            Files.createDirectories(root);
        } catch (IOException ex) {
            throw new StorageException("Cannot create local storage directory " + root, ex);
        }
        log.warn("Using LOCAL file storage at {} - not suitable for anything but development", root);
    }

    @Override
    public StoredFile store(FileCategory category, String originalName, String contentType,
                            long sizeBytes, InputStream content) {
        byte[] head = new byte[12];
        InputStream sniffable = content.markSupported()
                ? content
                : new java.io.BufferedInputStream(content, 8192);
        try {
            sniffable.mark(head.length);
            int read = sniffable.readNBytes(head, 0, head.length);
            sniffable.reset();
            ContentTypePolicy.check(category, contentType, sizeBytes,
                    read == head.length ? head : java.util.Arrays.copyOf(head, Math.max(read, 0)));

            String key = StorageKeys.generate(category, contentType, clock.instant());
            Path target = resolve(key);
            Files.createDirectories(target.getParent());
            Files.copy(sniffable, target, StandardCopyOption.REPLACE_EXISTING);

            return new StoredFile(key, originalName, ContentTypePolicy.normalise(contentType),
                    Files.size(target), clock.instant());
        } catch (IOException ex) {
            throw new StorageException("Failed to write file to local storage", ex);
        }
    }

    @Override
    public InputStream download(String key) {
        Path source = resolve(key);
        try {
            return Files.newInputStream(source);
        } catch (IOException ex) {
            throw new StorageException("Cannot read " + key, ex);
        }
    }

    @Override
    public String presignedDownloadUrl(String key, Duration validFor) {
        // Deliberately not a usable HTTP URL - see the class javadoc.
        return resolve(key).toUri().toString();
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ex) {
            throw new StorageException("Cannot delete " + key, ex);
        }
    }

    @Override
    public void copy(String sourceKey, String targetKey) {
        Path source = resolve(sourceKey);
        Path target = resolve(targetKey);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.NoSuchFileException absent) {
            throw new StorageException("No stored file with key " + sourceKey, absent);
        } catch (IOException ex) {
            throw new StorageException("Could not copy " + sourceKey, ex);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    /**
     * Resolves a key under the root, refusing anything that escapes it.
     *
     * <p>{@code StorageKeys.requireValid} already rejects {@code ..}; this is the second, structural
     * check — after normalising, the result must still start with the root. Two independent guards,
     * because a path traversal here reads arbitrary files off the developer's machine.</p>
     */
    private Path resolve(String key) {
        Path resolved = root.resolve(StorageKeys.requireValid(key)).normalize();
        if (!resolved.startsWith(root)) {
            throw new StorageException("Storage key escapes the storage root: " + key);
        }
        return resolved;
    }
}
