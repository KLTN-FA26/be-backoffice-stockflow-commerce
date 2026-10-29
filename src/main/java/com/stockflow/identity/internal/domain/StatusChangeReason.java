package com.stockflow.identity.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.regex.Pattern;

/**
 * The reason an administrator gives for locking, unlocking, disabling or enabling an account
 * (SCRUM-474 LOCK-REASON, owner decision 29/10/2026): free text of 5 to 500 characters, counted after
 * trimming Unicode spaces too and in code points, as {@code ck_user_status_change_reason} counts them. Stated here once; the
 * request DTO and the CHECK constraint repeat it.
 */
public final class StatusChangeReason {

    public static final int MIN_LENGTH = 5;
    public static final int MAX_LENGTH = 500;

    /**
     * What is trimmed off both ends: ASCII whitespace plus every Unicode space separator (NBSP, narrow NBSP,
     * ideographic space...) and the zero-width space. {@code String.trim()} leaves all of the non-ASCII ones and
     * {@code strip()} still leaves the no-break spaces, so a reason of five NBSPs would pass as "5 characters".
     * {@code ck_user_status_change_reason} trims the same set with {@code btrim}.
     */
    private static final Pattern EDGES = Pattern.compile("^[\\s\\p{Z}\\u200B]+|[\\s\\p{Z}\\u200B]+$");

    private StatusChangeReason() {
    }

    /**
     * @return the trimmed reason
     * @throws BusinessException {@code VALIDATION_FAILED} when it is missing, blank or outside 5 to 500 characters
     */
    public static String require(String raw) {
        String reason = raw == null ? "" : EDGES.matcher(raw).replaceAll("");
        int length = reason.codePointCount(0, reason.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "reason is required and must be between %d and %d characters".formatted(MIN_LENGTH, MAX_LENGTH));
        }
        return reason;
    }
}
