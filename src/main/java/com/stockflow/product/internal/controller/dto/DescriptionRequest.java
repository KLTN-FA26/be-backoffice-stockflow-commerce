package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A description as the back office editor (Tiptap) writes it: {@code editor.getHTML()}. The server cleans it to its
 * allow-list before storing it (ADR-0013); null or an empty editor removes it.
 */
public record DescriptionRequest(
        @Schema(example = "<p>Ly giấy <strong>12oz</strong>, in logo theo thiết kế.</p>",
                description = "HTML from the editor; at most 100 000 characters. Tags outside the allow-list, and "
                        + "images that are not on the system's CDN, are removed.")
        String html
) {
}
