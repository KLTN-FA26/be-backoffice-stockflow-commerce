package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * ClamD INSTREAM integration. Unavailable or inconclusive inspection never means clean.
 *
 * <p>{@code stockflow.upload-inspection.enabled} defaults to {@code true} and exists so a developer
 * without the ClamAV container can still upload; only the {@code local} profile turns it off. When
 * it is off nothing is scanned and a warning is logged at startup.</p>
 *
 * <h2>Concurrency</h2>
 *
 * <p>clamd scans a bounded number of streams at once, and every scan here holds a socket and a
 * request open, so the number in flight per instance is capped
 * ({@code stockflow.upload-inspection.max-concurrent}). A scan over the cap <i>waits</i> for a slot up
 * to {@code acquire-timeout} before answering 429: refusing immediately turned three people uploading
 * at the same moment into a failed upload for the third, although the first two finish in seconds.</p>
 *
 * <p>Scanning is an upload-time concern only. A stored object reached its category through this
 * check, so reading it back later (integrity verification, downloads) never scans it again.</p>
 */
@Component
public class UploadInspection {
    private static final Logger log = LoggerFactory.getLogger(UploadInspection.class);
    private static final int CONNECT_TIMEOUT_MILLIS = 3000;
    private final String host;
    private final int port;
    private final boolean enabled;
    private final Semaphore permits;
    private final Duration acquireTimeout;

    /** Two scans at a time and no waiting: the shape the unit tests and the real-engine test use. */
    public UploadInspection(String host, int port, boolean enabled) {
        this(host, port, enabled, 2, Duration.ZERO);
    }

    @Autowired
    public UploadInspection(@Value("${stockflow.upload-inspection.host:localhost}") String host,
                            @Value("${stockflow.upload-inspection.port:3310}") int port,
                            @Value("${stockflow.upload-inspection.enabled:true}") boolean enabled,
                            @Value("${stockflow.upload-inspection.max-concurrent:4}") int maxConcurrent,
                            @Value("${stockflow.upload-inspection.acquire-timeout:PT15S}") Duration acquireTimeout) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("stockflow.upload-inspection.max-concurrent must be at least 1");
        }
        if (acquireTimeout == null || acquireTimeout.isNegative()) {
            throw new IllegalArgumentException("stockflow.upload-inspection.acquire-timeout must not be negative");
        }
        this.host = host;
        this.port = port;
        this.enabled = enabled;
        this.permits = new Semaphore(maxConcurrent);
        this.acquireTimeout = acquireTimeout;
        if (!enabled) {
            log.warn("Upload virus scanning is DISABLED (stockflow.upload-inspection.enabled=false). "
                    + "Never run this way outside a developer machine.");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Streams {@code content} to clamd and returns only on a clean verdict.
     *
     * <p>The stream is read to its end when scanning is on, and not at all when it is off: a caller
     * that needs every byte read (a digest wrapped around the stream) drains what is left itself.</p>
     *
     * @throws BusinessException {@code VALIDATION_FAILED} for a detection, {@code RATE_LIMITED} when
     *         no slot frees up within the acquire timeout, {@code PAYLOAD_TOO_LARGE} past the scan limit
     * @throws StorageException when clamd is unreachable or answers anything but a clean verdict
     */
    public void requireClean(InputStream content) {
        if (!enabled) { return; }
        acquire();
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(30000);
            // Also bound blocked writes: SO_TIMEOUT alone only bounds reads.
            CompletableFuture.delayedExecutor(35, TimeUnit.SECONDS).execute(() -> {
                try { socket.close(); } catch (IOException ignored) { }
            });
            var output = new DataOutputStream(socket.getOutputStream());
            output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buffer = new byte[8192];
            long total = 0;
            for (int count; (count = content.read(buffer)) != -1;) {
                total += count;
                if (total > FileCategory.DESIGN_RENDER.maxBytes()) { throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE); }
                output.writeInt(count);
                output.write(buffer, 0, count);
            }
            output.writeInt(0);
            output.flush();
            String result = readReply(socket.getInputStream());
            if (result.endsWith(" FOUND")) { throw new BusinessException(ErrorCode.VALIDATION_FAILED, "File failed security inspection"); }
            if (!result.equals("stream: OK")) { throw new StorageException("File inspection did not return a clean result"); }
        } catch (IOException ex) { throw new StorageException("File inspection service is unavailable", ex); }
        finally { permits.release(); }
    }

    /**
     * clamd's {@code PING}, for the health indicator: true on {@code PONG}. Takes no scan slot, so a
     * busy scanner still reports itself alive.
     *
     * @throws StorageException when clamd cannot be reached or answers something else
     */
    public void ping() {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(CONNECT_TIMEOUT_MILLIS);
            socket.getOutputStream().write("zPING\0".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String reply = readReply(socket.getInputStream());
            if (!"PONG".equals(reply)) {
                throw new StorageException("File inspection service answered PING unexpectedly");
            }
        } catch (IOException ex) {
            throw new StorageException("File inspection service is unavailable", ex);
        }
    }

    private void acquire() {
        boolean acquired;
        try {
            acquired = acquireTimeout.isZero()
                    ? permits.tryAcquire()
                    : permits.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.RATE_LIMITED, "File inspection is busy");
        }
        if (!acquired) { throw new BusinessException(ErrorCode.RATE_LIMITED, "File inspection is busy"); }
    }

    /** A zero-terminated clamd reply, bounded so a misbehaving peer cannot grow it without limit. */
    private static String readReply(InputStream input) throws IOException {
        var response = new ByteArrayOutputStream();
        for (int value; (value = input.read()) != -1 && value != 0;) {
            if (response.size() >= 4096) { throw new StorageException("Invalid file inspection response"); }
            response.write(value);
        }
        return response.toString(StandardCharsets.UTF_8).trim();
    }
}
