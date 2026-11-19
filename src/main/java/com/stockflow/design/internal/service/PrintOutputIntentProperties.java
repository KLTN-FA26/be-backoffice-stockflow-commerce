package com.stockflow.design.internal.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The PDF/X output intent stamped into an approved print file ({@code stockflow.design.print.output-intent};
 * {@link PrintReadyStamp}): the ICC profile of the printing condition the file is meant for, and the names a press
 * reads to recognise that condition.
 *
 * <p>By default (application.yml) the profile is {@value #BUNDLED}, ECI's ISO Coated v2 under its registered condition
 * {@value #FOGRA39} - the very file the storefront embeds when it exports the print file (FE request 2026-11-18 BE-3;
 * {@code print/icc/README.md} has its source and terms). It is bundled rather than configured so that a laptop and
 * the VPS stamp the same intent with nothing to install; it names its own condition, so it needs no identifier. Any
 * other profile is the printer's own, and only whoever configures it knows which condition it describes: there the
 * identifier is required, and a missing one stops startup rather than labelling the file with a wrong condition. The
 * content itself is never converted to the profile.</p>
 *
 * <p>A blank {@code profile} still gives the earlier interim intent, the JDK's built-in sRGB profile under
 * {@value #SRGB} - a display profile a strict PDF/X-4 checker can reject. The production instruction says which intent
 * is embedded and whether it is that one.</p>
 *
 * @param profile                   where the ICC profile is: a Spring resource location ({@code classpath:},
 *                                  {@code file:}) or a plain file path; blank for the interim sRGB profile
 * @param outputConditionIdentifier the condition's name in the registry, e.g. {@code FOGRA39}; required with a
 *                                  profile other than {@value #BUNDLED}, always {@value #SRGB} without one
 * @param info                      a human-readable description of the condition; the identifier when blank
 * @param registryName              the registry the identifier belongs to; the ICC's when blank
 */
@ConfigurationProperties(prefix = "stockflow.design.print.output-intent")
public record PrintOutputIntentProperties(String profile, String outputConditionIdentifier, String info,
                                          String registryName) {

    static final String SRGB = "sRGB IEC61966-2.1";
    /** The profile bundled with the application, and the default: ECI's ISO Coated v2. */
    static final String BUNDLED = "classpath:print/icc/ISOcoated_v2_eci.icc";
    static final String FOGRA39 = "FOGRA39";
    private static final String FOGRA39_INFO = "ISO Coated v2 (ECI)";
    private static final String ICC_REGISTRY = "http://www.color.org";

    public PrintOutputIntentProperties {
        if (isBlank(profile)) {
            // An identifier or description naming a press condition over the sRGB profile would tell the press a lie.
            profile = null;
            outputConditionIdentifier = SRGB;
            info = SRGB;
        } else if (BUNDLED.equals(profile.strip()) && isBlank(outputConditionIdentifier)) {
            outputConditionIdentifier = FOGRA39;
            info = isBlank(info) ? FOGRA39_INFO : info;
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
