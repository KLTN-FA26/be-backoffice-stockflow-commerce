package com.stockflow.common.label;

import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Printed captions, read from the message bundles so no Vietnamese lives in the label classes. Bilingual, Vietnamese
 * first (owner decision RTM-LABELLANG, 2026-11-19): the warehouse and the carrier's crew read Vietnamese, the English
 * half keeps the usual wording. One place, so the shipping and the carton label join their captions the same way.
 */
final class LabelCaptions {

    private static final ResourceBundle VI = ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));
    private static final ResourceBundle EN = ResourceBundle.getBundle("i18n.messages", Locale.ROOT);

    private LabelCaptions() {
    }

    /** {@code "<vi> / <en>"} of {@code key}, or the one word when both halves are the same (SKU). */
    static String bilingual(String key) {
        String vi = VI.getString(key);
        String en = EN.getString(key);
        return vi.equals(en) ? vi : vi + " / " + en;
    }

    /** The Vietnamese half alone, for a caption that has no room for both. */
    static String vietnamese(String key) {
        return VI.getString(key);
    }
}
