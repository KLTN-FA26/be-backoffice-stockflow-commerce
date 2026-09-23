package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Request-scoped bounded spool for replay fingerprints; not a second storage backend. */
public final class BufferedUpload implements AutoCloseable {
    private final Path path;
    private final String checksum;
    private BufferedUpload(Path path, String checksum) { this.path = path; this.checksum = checksum; }
    public String checksum() { return checksum; }
    public InputStream open() throws IOException { return Files.newInputStream(path); }

    public static BufferedUpload read(FileCategory category, FileUpload upload) {
        Path path = null;
        try {
            var input = new BufferedInputStream(upload.content());
            input.mark(16);
            byte[] head = input.readNBytes(16);
            input.reset();
            ContentTypePolicy.check(category, upload.contentType(), upload.sizeBytes(), head);
            var digest = MessageDigest.getInstance("SHA-256");
            path = Files.createTempFile("stockflow-upload-", ".tmp");
            long size = 0;
            try (var output = Files.newOutputStream(path)) {
                byte[] bytes = new byte[8192];
                for (int count; (count = input.read(bytes)) != -1;) {
                    size += count;
                    if (size > category.maxBytes()) { throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE); }
                    digest.update(bytes, 0, count);
                    output.write(bytes, 0, count);
                }
            }
            if (size != upload.sizeBytes()) { throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Upload size mismatch"); }
            return new BufferedUpload(path, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException ex) {
            cleanup(path); throw new StorageException("Could not buffer uploaded file", ex);
        } catch (NoSuchAlgorithmException impossible) {
            cleanup(path); throw new IllegalStateException(impossible);
        } catch (RuntimeException ex) { cleanup(path); throw ex; }
    }
    @Override public void close() { cleanup(path); }
    private static void cleanup(Path path) {
        if (path != null) {
            try { Files.deleteIfExists(path); }
            catch (IOException ex) { org.slf4j.LoggerFactory.getLogger(BufferedUpload.class).warn("Temporary upload cleanup failed", ex); }
        }
    }
}
