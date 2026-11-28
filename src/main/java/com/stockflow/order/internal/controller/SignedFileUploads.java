package com.stockflow.order.internal.controller;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.StorageException;
import com.stockflow.order.internal.service.signing.SignedPdfInspector;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Reads the signed file of a signing request into memory - once, and never more than the inspector looks at: a file
 * larger than {@link SignedPdfInspector#MAX_BYTES} is refused before it is read, with the reason the inspector would
 * give, so an upload cannot be used to fill the heap.
 */
final class SignedFileUploads {

    private SignedFileUploads() {
    }

    static byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw rejected(SignedPdfInspector.Reasons.EMPTY);
        }
        if (file.getSize() > SignedPdfInspector.MAX_BYTES) {
            throw rejected(SignedPdfInspector.Reasons.TOO_LARGE);
        }
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new StorageException("Could not read the multipart upload", ex);
        }
    }

    static String name(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null || name.isBlank() ? "signed-contract.pdf" : name;
    }

    private static BusinessException rejected(String reason) {
        return new BusinessException(ErrorCode.CONTRACT_SIGNED_FILE_REJECTED,
                ErrorCode.CONTRACT_SIGNED_FILE_REJECTED.defaultMessage(), List.of(), Map.of("reason", reason));
    }
}
