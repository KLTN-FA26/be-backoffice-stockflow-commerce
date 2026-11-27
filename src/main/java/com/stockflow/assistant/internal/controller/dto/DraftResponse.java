package com.stockflow.assistant.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stockflow.assistant.internal.service.Draft;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * A text the assistant drafted for the user to edit and send through the screen's own action; it sends nothing itself
 * (ADR-0015). The contract's {@code Draft}: the one shape of all four model-backed drafts - the reply and the summary
 * in the staff chat, the payment reminder, the message to a producer.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DraftResponse(
        @Schema(description = "The draft, plain text in Vietnamese, to be edited before it is sent") String text,
        @Schema(description = "The records it was written from, each with a link to its screen")
        List<SourceResponse> sources,
        @Schema(description = "Codes and figures the text states that no source carries: show them flagged; they "
                + "are never removed") List<String> unverifiedCodes,
        @Schema(description = "The model that wrote it", example = "gemini-2.5-flash") String model,
        @Schema(description = "When it was written") Instant generatedAt) {

    /** One record a draft or an answer rests on. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SourceResponse(
            @Schema(allowableValues = {"ORDER", "CONTRACT", "INSTALLMENT", "BANK_TRANSACTION", "QUOTE", "CARTON",
                    "PRODUCTION_ORDER", "CHAT", "RULE"}) String kind,
            @Schema(description = "What to show", example = "HD-20261117-000123-1") String label,
            @Schema(description = "A back-office path to the record's screen; null when the record has none",
                    example = "/admin/orders/018f...", nullable = true) String href) {
    }

    public static DraftResponse of(Draft draft) {
        return new DraftResponse(draft.text(), draft.sources().stream()
                .map(source -> new SourceResponse(source.kind().name(), source.label(), source.href())).toList(),
                draft.unverifiedCodes(), draft.model(), draft.generatedAt());
    }
}
