package com.stockflow.product.api;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The fonts the design studio offers, in the order it lists them (product owner, 2026-11-20, FE request BE-18). The
 * list is the system's, not the browser's: a print configuration may narrow it ({@code allowedFontCodes}) only to codes
 * named here, so the back office and the storefront read it from one place instead of each copying the codes.
 *
 * <p>It lives with the print configurations rather than in the design module because saving a configuration checks
 * its fonts against it, and the product module cannot depend on the design module, which depends on it. A code is the
 * stable key a layout's text element and a configuration store; the label is what people read.</p>
 */
public enum PrintFont {
    BE_VIETNAM_PRO("be-vietnam-pro", "Be Vietnam Pro"),
    MONTSERRAT("montserrat", "Montserrat"),
    ROBOTO_SLAB("roboto-slab", "Roboto Slab"),
    PLAYFAIR_DISPLAY("playfair-display", "Playfair Display"),
    OSWALD("oswald", "Oswald"),
    DANCING_SCRIPT("dancing-script", "Dancing Script"),
    LOBSTER("lobster", "Lobster"),
    PACIFICO("pacifico", "Pacifico");

    private static final Map<String, PrintFont> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(PrintFont::code, Function.identity()));

    private final String code;
    private final String label;

    PrintFont(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    /** Whether {@code code} names one of these fonts, exactly as written (codes are lower case). */
    public static boolean isKnown(String code) {
        return code != null && BY_CODE.containsKey(code);
    }
}
