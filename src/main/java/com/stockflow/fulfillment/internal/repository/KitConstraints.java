package com.stockflow.fulfillment.internal.repository;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.hibernate.exception.ConstraintViolationException;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The database's kit rules (V20261022001000) as the answers the packing station gives for them (review of #155): a
 * refusal by one of them is a 409 with the kit's own code, not a 500. Each trigger names itself (its message starts
 * with its name and {@code USING CONSTRAINT} carries it), so the name decides, as {@code CustomerConstraints} does for
 * the customer's; the database's message is never passed on.
 */
public final class KitConstraints {

    private static final Map<String, ErrorCode> CODES = Map.of(
            "tg_package_kit_ships_whole", ErrorCode.KIT_INCOMPLETE,
            "tg_package_line_kit_part", ErrorCode.KIT_CONTENTS_INVALID,
            "tg_package_kit_check", ErrorCode.KIT_CONTENTS_INVALID,
            "tg_package_kit_fixed", ErrorCode.KIT_CONTENTS_INVALID,
            "ck_package_kit", ErrorCode.KIT_CONTENTS_INVALID,
            "ck_package_kit_override", ErrorCode.KIT_OVERRIDE_REASON_REQUIRED,
            "uk_package_kit_part", ErrorCode.KIT_CONTENTS_INVALID);

    private KitConstraints() {
    }

    /** The kit refusal in {@code error}'s cause chain, as the station answers it; empty when it is none of them. */
    public static Optional<BusinessException> translate(Throwable error) {
        for (Throwable cause = error; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                ErrorCode code = CODES.get(violation.getConstraintName().toLowerCase(Locale.ROOT));
                if (code != null) {
                    return Optional.of(refusal(code));
                }
            }
            String message = cause.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                // Longest name first: ck_package_kit_override is not ck_package_kit.
                for (var entry : CODES.entrySet().stream().sorted((a, b) -> b.getKey().length() - a.getKey().length())
                        .toList()) {
                    if (lower.contains(entry.getKey())) {
                        return Optional.of(refusal(entry.getValue()));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static BusinessException refusal(ErrorCode code) {
        return new BusinessException(code, switch (code) {
            case KIT_INCOMPLETE -> "A kit ships whole: every part sealed, in one shipment, part 1 with its unit";
            case KIT_OVERRIDE_REASON_REQUIRED -> "A kit part has no override reason, and a reason is never blank";
            default -> "Only part 1 of a kit holds goods, one unit of its own line; a kit's parts do not change";
        });
    }
}
