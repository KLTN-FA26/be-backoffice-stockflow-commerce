package com.stockflow.common.i18n;

import com.stockflow.common.error.ErrorCode;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Translates an {@link ErrorCode} into the caller's language.
 *
 * <h2>Why the translation happens here and not in the exception</h2>
 *
 * <p>An exception is thrown deep in the domain, where there is no request and therefore no locale.
 * If it carried a translated message, the domain would have to know about {@code MessageSource},
 * and a message thrown from a scheduled job would be translated into whatever locale happened to be
 * on that thread. So exceptions carry a <b>code</b> and an English developer message; the
 * translation happens once, at the edge, in {@code GlobalExceptionHandler}.</p>
 *
 * <h2>What is translated, and what is not</h2>
 *
 * <p><b>Translated:</b> the generic message for an error code, and Bean Validation messages — the
 * things a user reads on a form.</p>
 *
 * <p><b>Not translated:</b> the specific detail a domain exception builds, such as
 * "Insufficient stock for SKU SOFA-3S-GREY: requested 6, available 5". Translating those would mean
 * a properties key and a parameter list for every message in the system, and most of them are read
 * by a developer in a log rather than by a customer. Where a customer-facing message needs
 * translating, add a key and call {@link #forCode}.</p>
 *
 * <p>The locale comes from {@code Accept-Language} via {@link LocaleContextHolder}. Outside a
 * request — a scheduled job, an event listener — that yields the platform default, which is
 * configured to Vietnamese in {@code application.yml}.</p>
 */
@Component
public class Messages {

    private final MessageSource messageSource;

    public Messages(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * The translated message for an error code, or its English default when no translation exists.
     *
     * <p>Falling back rather than throwing is deliberate: a missing key is a documentation gap, and
     * turning it into a 500 inside the exception handler — the one place that must not fail — would
     * replace a readable error with an unreadable one.</p>
     */
    public String forCode(ErrorCode code) {
        return forCode(code, LocaleContextHolder.getLocale());
    }

    public String forCode(ErrorCode code, Locale locale) {
        try {
            return messageSource.getMessage("error." + code.name(), null, locale);
        } catch (NoSuchMessageException missing) {
            return code.defaultMessage();
        }
    }

    /**
     * An arbitrary key, with the English text to fall back on.
     *
     * <p>Requiring the fallback at the call site means a missing translation degrades to readable
     * English rather than to {@code ???error.foo???}, which is what a message source normally
     * renders and which no user can act on.</p>
     */
    public String get(String key, String fallback, Object... arguments) {
        try {
            return messageSource.getMessage(key, arguments, LocaleContextHolder.getLocale());
        } catch (NoSuchMessageException missing) {
            return fallback;
        }
    }
}
