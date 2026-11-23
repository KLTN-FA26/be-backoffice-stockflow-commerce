package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A photo kept with a return ({@code ordering.return_request_evidence}, append-only): the customer's picture of the
 * problem, or the custody note for goods that came back but are not the return's to take (WF-05 Q-11). The warehouse's
 * and QC's photos of what arrived (stages RECEIPT and INSPECTION) are historical since team plan 4D-3: the goods are
 * photographed on their return receipt now. The file lives in object storage under {@code fileKey}; it is served only
 * through a short presigned link, never from the application's origin.
 *
 * @param custody what a CUSTODY photo shows; null for every other stage ({@code ck_return_request_evidence_custody})
 */
public record ReturnEvidence(UUID id, Stage stage, String fileKey, String originalName, String contentType,
                             long sizeBytes, UUID uploadedBy, Instant uploadedAt, Custody custody) {

    /**
     * When it was taken: with the claim; at receipt or at inspection before team plan 4D-3 (kept for those rows); or
     * as the custody note of goods kept for the customer (WF-05 Q-11).
     */
    public enum Stage { CLAIM, RECEIPT, INSPECTION, CUSTODY }

    /**
     * Goods that came back and that the return does not take - another SKU, more than was approved, a parcel after the
     * claim was settled or refused (WF-05 Q-11; Luật Thương mại 2005 Điều 62; TT 91/2026/TT-BTC Điều 10 khoản 5
     * điểm c): kept for the customer, not put into stock, not refunded, no invoice adjusted - until they are sent back,
     * a supplementary claim takes them, or the customer agrees in writing that they are destroyed. Who took them in is
     * the photo's {@code uploadedBy}, when is its {@code uploadedAt}.
     *
     * @param sku      the SKU, when known; at least one of it and {@code lpn}
     * @param lpn      the carton's LPN, when the goods came in one
     * @param quantity how many units, at least one
     * @param note     what else was noted (the paper record's number, the condition); optional
     */
    public record Custody(String sku, String lpn, int quantity, String note) {

        public static final int NOTE_LIMIT = 1000;

        public Custody {
            sku = blankToNull(sku);
            lpn = blankToNull(lpn);
            note = blankToNull(note);
            if (sku == null && lpn == null) {
                throw new IllegalArgumentException("Goods kept in custody are named by their SKU or their LPN");
            }
            if (quantity < 1) {
                throw new IllegalArgumentException("Goods kept in custody are at least one unit");
            }
            if (sku != null && sku.length() > 64 || lpn != null && lpn.length() > 40
                    || note != null && note.length() > NOTE_LIMIT) {
                throw new IllegalArgumentException("A custody note's SKU, LPN or note is too long");
            }
        }

        private static String blankToNull(String text) {
            return text == null || text.isBlank() ? null : text.strip();
        }
    }

    public ReturnEvidence {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(fileKey, "fileKey");
        Objects.requireNonNull(uploadedBy, "uploadedBy");
        Objects.requireNonNull(uploadedAt, "uploadedAt");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("An evidence file is not empty");
        }
        if ((stage == Stage.CUSTODY) != (custody != null)) {
            throw new IllegalArgumentException("A custody note says what is kept, and only a custody note does");
        }
    }

    /** A photo of any stage but CUSTODY. */
    public ReturnEvidence(UUID id, Stage stage, String fileKey, String originalName, String contentType,
                          long sizeBytes, UUID uploadedBy, Instant uploadedAt) {
        this(id, stage, fileKey, originalName, contentType, sizeBytes, uploadedBy, uploadedAt, null);
    }
}
