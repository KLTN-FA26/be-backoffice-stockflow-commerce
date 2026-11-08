package com.stockflow.fulfillment.internal.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A carrier's words for a failed delivery, mapped to the six internal reasons (kltn-docs 09 §4.1 step 2, §4.3 step 3).
 *
 * <p>A carrier reports failures in its own vocabulary - Ahamove sends a free-text {@code fail_comment} - so the warehouse
 * manager keeps a table per carrier. A reason missing from the table is {@link DeliveryFailureReason#OTHER} with the
 * carrier's own words as the note (E22): the delivery is still recorded as failed, and the manager can add the wording
 * to the table for next time. Matching ignores case and runs of spaces, since the same carrier writes the same
 * reason both ways.</p>
 */
public final class FailureReasonMap {

    /** A table bigger than this is somebody pasting a log, not a vocabulary. */
    public static final int MAX_ENTRIES = 100;
    public static final int MAX_WORDING = 200;

    private static final FailureReasonMap EMPTY = new FailureReasonMap(Map.of());

    private final Map<String, DeliveryFailureReason> entries;

    private FailureReasonMap(Map<String, DeliveryFailureReason> entries) {
        this.entries = entries;
    }

    public static FailureReasonMap empty() {
        return EMPTY;
    }

    /**
     * @param entries the carrier's wording to the internal reason, as the warehouse manager entered it
     * @throws IllegalArgumentException with a message naming the rule broken: more than {@value #MAX_ENTRIES} entries,
     *         a blank or over-long wording, a wording listed twice (once the case and spaces are set aside), or no
     *         reason for a wording
     */
    public static FailureReasonMap of(Map<String, DeliveryFailureReason> entries) {
        if (entries == null || entries.isEmpty()) {
            return EMPTY;
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("At most " + MAX_ENTRIES + " carrier reasons");
        }
        Map<String, DeliveryFailureReason> kept = new LinkedHashMap<>();
        Map<String, String> seen = new LinkedHashMap<>();
        entries.forEach((wording, reason) -> {
            String trimmed = wording == null ? "" : wording.strip();
            if (trimmed.isEmpty() || trimmed.length() > MAX_WORDING) {
                throw new IllegalArgumentException("A carrier reason is 1-" + MAX_WORDING + " characters");
            }
            if (reason == null) {
                throw new IllegalArgumentException("Carrier reason '" + trimmed + "' maps to no internal reason");
            }
            String key = normalise(trimmed);
            if (seen.putIfAbsent(key, trimmed) != null) {
                throw new IllegalArgumentException("Carrier reason '" + trimmed + "' is listed twice");
            }
            kept.put(trimmed, reason);
        });
        return new FailureReasonMap(Collections.unmodifiableMap(kept));
    }

    /** The table as entered, wording to reason, in the order entered. */
    public Map<String, DeliveryFailureReason> entries() {
        return entries;
    }

    /**
     * What a failure the carrier reported in {@code carrierWording} is recorded as.
     *
     * @return the mapped reason, or {@link DeliveryFailureReason#OTHER} when the wording is missing from the table or
     *         blank; the note is always the carrier's wording, kept as it came (E22), or null when it sent none
     */
    public Resolution resolve(String carrierWording) {
        String raw = carrierWording == null || carrierWording.isBlank() ? null : carrierWording.strip();
        if (raw == null) {
            return new Resolution(DeliveryFailureReason.OTHER, null);
        }
        String key = normalise(raw);
        for (Map.Entry<String, DeliveryFailureReason> entry : entries.entrySet()) {
            if (normalise(entry.getKey()).equals(key)) {
                return new Resolution(entry.getValue(), raw);
            }
        }
        return new Resolution(DeliveryFailureReason.OTHER, raw);
    }

    /** A failure as recorded: the internal reason, and the carrier's own words (null when it gave none). */
    public record Resolution(DeliveryFailureReason reason, String carrierWording) {
    }

    /**
     * NFC first: Vietnamese typed on one keyboard and sent by another system may compose the same letter differently
     * (precomposed or with combining marks). Then case and runs of spaces, the non-breaking one included.
     */
    private static String normalise(String wording) {
        return java.text.Normalizer.normalize(wording, java.text.Normalizer.Form.NFC).strip()
                .replaceAll("[\\s\\u00A0]+", " ").toLowerCase(Locale.ROOT);
    }
}
