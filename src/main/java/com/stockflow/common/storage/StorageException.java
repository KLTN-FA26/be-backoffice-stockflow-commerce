package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

/**
 * The file store could not do what was asked.
 *
 * <p>Maps to 503, not 500, and the distinction is worth keeping: the application is fine, a
 * dependency is not, and the client should retry rather than report a bug.
 * {@link ErrorCode#STORAGE_ERROR} is marked retryable for that reason.</p>
 */
public class StorageException extends BusinessException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public StorageException(String message, Throwable cause) {
        super(ErrorCode.STORAGE_ERROR, message, cause);
    }

    public StorageException(String message) {
        this(message, null);
    }
}
