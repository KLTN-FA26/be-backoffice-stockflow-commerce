package com.stockflow.common.storage;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.Locale;

/**
 * Decides whether a file may be stored at all.
 *
 * <h2>Why the declared content type is not trusted</h2>
 *
 * <p>{@code Content-Type} on an upload is chosen by the client. A file can claim to be
 * {@code image/png} and contain anything at all. So the check has two parts, and both are needed:
 * the declared type must be in the category's allow-list, <b>and</b> the first bytes of the file
 * must actually look like that type. Checking only the header lets an executable through under a
 * PNG label; checking only the bytes lets a valid PNG into a category that does not accept
 * images.</p>
 *
 * <p>Magic-number checking here is deliberately shallow — it is a sanity check, not virus scanning,
 * and it is not represented as one. A file that reaches storage is still untrusted content; it must
 * never be served from the application's own origin, which is why uploads live in object storage
 * behind a separate host.</p>
 */
public final class ContentTypePolicy {

    private ContentTypePolicy() {
    }

    /**
     * @param declaredType the client-supplied content type
     * @param head         the first bytes of the file; at least 12 are needed for a useful check
     * @throws BusinessException 415 if the type is not accepted, 413 if the file is too large
     */
    public static void check(FileCategory category, String declaredType, long sizeBytes, byte[] head) {
        if (sizeBytes > category.maxBytes()) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "%s files may be at most %d MB, got %d MB".formatted(
                            category, category.maxBytes() / (1024 * 1024),
                            sizeBytes / (1024 * 1024)));
        }
        if (sizeBytes == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "The uploaded file is empty");
        }

        String normalised = normalise(declaredType);
        if (!category.allowedContentTypes().contains(normalised)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "%s does not accept %s. Accepted: %s".formatted(
                            category, normalised, String.join(", ", category.allowedContentTypes())));
        }
        if (!looksLike(normalised, head)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "The file contents do not match the declared type " + normalised);
        }
    }

    /** Strips any {@code ; charset=...} and lowercases, so {@code IMAGE/PNG} matches. */
    static String normalise(String declaredType) {
        if (declaredType == null || declaredType.isBlank()) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "The upload declared no content type");
        }
        int semicolon = declaredType.indexOf(';');
        String base = semicolon < 0 ? declaredType : declaredType.substring(0, semicolon);
        return base.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Shallow magic-number check.
     *
     * <p>Returns true for types with no distinctive signature (CSV, plain text) rather than
     * rejecting them — those carry no executable payload, and demanding a signature they do not
     * have would reject every valid file.</p>
     */
    static boolean looksLike(String contentType, byte[] head) {
        if (head == null || head.length < 4) {
            // Too small to identify. Only reachable for a file of a few bytes, which the size
            // check above has already accepted; let it through rather than reject on no evidence.
            return true;
        }
        return switch (contentType) {
            case "image/jpeg" -> startsWith(head, 0xFF, 0xD8, 0xFF);
            case "image/png" -> startsWith(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/webp" -> startsWith(head, 0x52, 0x49, 0x46, 0x46)      // "RIFF"
                    && head.length >= 12
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            case "application/pdf" -> startsWith(head, 0x25, 0x50, 0x44, 0x46); // "%PDF"
            // Both modern Office formats are ZIP containers.
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                 "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ->
                    startsWith(head, 0x50, 0x4B, 0x03, 0x04)
                            || startsWith(head, 0x50, 0x4B, 0x05, 0x06);
            // No signature exists for these, and that is not a reason to reject them.
            case "text/csv", "text/plain" -> true;
            default -> true;
        };
    }

    private static boolean startsWith(byte[] head, int... signature) {
        if (head.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((head[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
