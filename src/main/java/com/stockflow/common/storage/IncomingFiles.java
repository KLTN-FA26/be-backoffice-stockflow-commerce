package com.stockflow.common.storage;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The files of one request, however they arrived: sent through the backend as multipart parts, or uploaded by the
 * browser straight to the object store and named by their upload ids ({@link StagedUploads}). A service takes this
 * instead of either list, so both ways in share one path from here on.
 */
public sealed interface IncomingFiles {

    /** How many files the request carries, for the caller's own limits (at least one photo, at most five). */
    int count();

    /** Checks, scans and stores the files with no transaction held, for the caller's transaction to adopt. */
    PreparedFiles prepare(FileCategory category, FileTransfers files, StagedUploads staged);

    static IncomingFiles sent(List<FileUpload> uploads) {
        return new Sent(uploads == null ? List.of() : uploads);
    }

    static IncomingFiles uploaded(UUID ownerId, List<UUID> uploadIds) {
        return new Uploaded(ownerId, uploadIds == null ? List.of() : uploadIds);
    }

    /** Multipart parts, read once. */
    record Sent(List<FileUpload> uploads) implements IncomingFiles {
        public Sent {
            uploads = List.copyOf(uploads);
        }

        @Override
        public int count() {
            return uploads.size();
        }

        @Override
        public PreparedFiles prepare(FileCategory category, FileTransfers files, StagedUploads staged) {
            return files.prepare(category, uploads);
        }
    }

    /** Files the browser uploaded through slots {@code ownerId} was given. */
    record Uploaded(UUID ownerId, List<UUID> uploadIds) implements IncomingFiles {
        public Uploaded {
            Objects.requireNonNull(ownerId, "ownerId");
            uploadIds = List.copyOf(uploadIds);
        }

        @Override
        public int count() {
            return uploadIds.size();
        }

        @Override
        public PreparedFiles prepare(FileCategory category, FileTransfers files, StagedUploads staged) {
            return staged.accept(category, ownerId, uploadIds);
        }
    }
}
