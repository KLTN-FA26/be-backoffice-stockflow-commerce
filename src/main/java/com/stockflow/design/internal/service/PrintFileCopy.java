package com.stockflow.design.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.Checksums;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.StorageException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A design's print file on local disk for as long as it is checked or stamped: copied from storage and checked against
 * the checksum it was stored with on the way (plan D2), or an empty file to write a stamped one into.
 *
 * <p>A print file runs to 50 MB. It is never held in the heap: it is streamed to a temporary file, and opened with
 * PDFBox's stream cache on disk too, as {@code PdfContentPolicy} opens every uploaded PDF. The file is deleted when
 * this is closed.</p>
 */
final class PrintFileCopy implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PrintFileCopy.class);

    private final Path path;

    private PrintFileCopy(Path path) {
        this.path = path;
    }

    /**
     * The file stored under {@code key}, which must still be the bytes whose SHA-256 is {@code checksum}.
     *
     * @throws BusinessException {@code CONFLICT} when the stored bytes are not those any more
     */
    static PrintFileCopy download(FileStorage storage, String key, String checksum) {
        Path path = temporary();
        try {
            MessageDigest digest = Checksums.sha256();
            try (InputStream in = new DigestInputStream(storage.download(key), digest)) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(checksum)) {
                throw new BusinessException(ErrorCode.CONFLICT, "The stored print file differs from the one uploaded");
            }
            return new PrintFileCopy(path);
        } catch (IOException ex) {
            delete(path);
            throw new StorageException("Could not read the print file", ex);
        } catch (RuntimeException ex) {
            delete(path);
            throw ex;
        }
    }

    /** An empty temporary file, for a print file written from another. */
    static PrintFileCopy empty() {
        return new PrintFileCopy(temporary());
    }

    Path path() {
        return path;
    }

    long size() {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            throw new StorageException("Could not read the print file", ex);
        }
    }

    /** The file as a PDF, its streams cached on disk rather than in the heap. */
    PDDocument open() throws IOException {
        return Loader.loadPDF(path.toFile(), "", null, null, IOUtils.createTempFileOnlyStreamCache());
    }

    @Override
    public void close() {
        delete(path);
    }

    private static Path temporary() {
        try {
            return Files.createTempFile("stockflow-print-file-", ".pdf");
        } catch (IOException ex) {
            throw new StorageException("Could not create a temporary file for the print file", ex);
        }
    }

    private static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("Could not delete the temporary print file {}", path, ex);
        }
    }
}
