package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * ClamD INSTREAM integration. Unavailable or inconclusive inspection never means clean.
 *
 * <p>{@code stockflow.upload-inspection.enabled} defaults to {@code true} and exists so a developer
 * without the ClamAV container can still upload; only the {@code local} profile turns it off. When
 * it is off nothing is scanned and a warning is logged at startup.</p>
 */
@Component
public class UploadInspection {
    private static final Logger log = LoggerFactory.getLogger(UploadInspection.class);
    private final String host;
    private final int port;
    private final boolean enabled;
    private final Semaphore permits = new Semaphore(2);
    public UploadInspection(@Value("${stockflow.upload-inspection.host:localhost}") String host,
                            @Value("${stockflow.upload-inspection.port:3310}") int port,
                            @Value("${stockflow.upload-inspection.enabled:true}") boolean enabled) {
        this.host = host; this.port = port; this.enabled = enabled;
        if (!enabled) {
            log.warn("Upload virus scanning is DISABLED (stockflow.upload-inspection.enabled=false). "
                    + "Never run this way outside a developer machine.");
        }
    }
    public void requireClean(InputStream content) {
        if (!enabled) { return; }
        if (!permits.tryAcquire()) { throw new BusinessException(ErrorCode.RATE_LIMITED, "File inspection is busy"); }
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
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
            var response = new java.io.ByteArrayOutputStream();
            for (int value; (value = socket.getInputStream().read()) != -1 && value != 0;) {
                if (response.size() >= 4096) { throw new StorageException("Invalid file inspection response"); }
                response.write(value);
            }
            String result = response.toString(StandardCharsets.UTF_8).trim();
            if (result.endsWith(" FOUND")) { throw new BusinessException(ErrorCode.VALIDATION_FAILED, "File failed security inspection"); }
            if (!result.equals("stream: OK")) { throw new StorageException("File inspection did not return a clean result"); }
        } catch (IOException ex) { throw new StorageException("File inspection service is unavailable", ex); }
        finally { permits.release(); }
    }
}
