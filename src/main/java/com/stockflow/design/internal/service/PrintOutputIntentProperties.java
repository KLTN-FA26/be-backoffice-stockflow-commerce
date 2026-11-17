package com.stockflow.design.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The PDF/X output intent stamped into an approved print file ({@code stockflow.design.print.output-intent};
 * {@link PrintReadyStamp}): the ICC profile of the printing condition the file is meant for, and the names a press
 * reads to recognise that condition.
 *
 * <p>The printer's profile is not known yet (open question B35, FE design request Q-1). Until it is configured,
 * {@code profile} is left blank and the JDK's built-in sRGB profile is embedded under {@value #SRGB}: an interim
 * intent that says what the content is - the design studio produces RGB - and nothing about the press. sRGB is a
 * display profile, not an output device one, so a strict PDF/X-4 checker can still reject the file; the production
 * instruction says which intent is embedded and whether it is the interim one. Configuring the printer's profile
 * replaces it with no code change: a CMYK profile such as ISO Coated v2 (FOGRA39) under its registered condition
 * identifier. The content itself is never converted to that profile.</p>
 *
 * @param profile                   where the ICC profile is, as a Spring resource location ({@code classpath:},
 *                                  {@code file:}); blank for the interim sRGB profile
 * @param outputConditionIdentifier the condition's name in the registry, e.g. {@code FOGRA39}; required with a
 *                                  {@code profile}, always {@value #SRGB} without one
 * @param info                      a human-readable description of the condition; the identifier when blank
 * @param registryName              the registry the identifier belongs to; the ICC's when blank
 */
@ConfigurationProperties(prefix = "stockflow.design.print.output-intent")
public record PrintOutputIntentProperties(String profile, String outputConditionIdentifier, String info,
                                          String registryName) {

    static final String SRGB = "sRGB IEC61966-2.1";
    private static final String ICC_REGISTRY = "http://www.color.org";

    public PrintOutputIntentProperties {
        if (isBlank(profile)) {
            // An identifier naming a press condition over the sRGB profile would tell the press a lie.
            profile = null;
            outputConditionIdentifier = SRGB;
        } else if (isBlank(outputConditionIdentifier)) {
            throw new IllegalArgumentException("stockflow.design.print.output-intent.output-condition-identifier is"
                    + " required when a profile is configured: a press recognises the condition by it");
        }
        info = isBlank(info) ? outputConditionIdentifier : info;
        registryName = isBlank(registryName) ? ICC_REGISTRY : registryName;
    }

    /** Whether this is the interim sRGB intent rather than a configured printer's profile. */
    boolean interim() {
        return profile == null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
