package com.stockflow.order.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stockflow.order.api.ShortageCardEnd;
import com.stockflow.order.api.ShortageCardStatus;
import com.stockflow.order.api.ShortageCards;
import com.stockflow.order.api.ShortageOption;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A shortage choice card as the portal and the back office read it (kltn-docs 17 §4.14). Nothing internal: no LPN, bin,
 * hold reason or internal note. The same shape as the card's chat payload, so the three screens draw one thing.
 *
 * @param code    the card's short code "TT-yyyyMMdd-NNNN" (UNH-CODE-1128), to show instead of its id
 * @param version the version a choice must name; a stale one is refused 409
 */
@Schema(name = "ShortageCard")
public record ShortageCardResponse(UUID cardId, String code, UUID orderId, String orderNumber,
                                   ShortageCardStatus status, long version, Batch batch, int missingCartons,
                                   List<Line> lines, List<Option> options, String salesNote, Instant sentAt, Chosen chosen,
                                   Instant executedAt, boolean executionStarted, ShortageCardEnd endedReason) {

    /** The batch the card is about: its window in force; {@code moved} when Sales moved it since signing. */
    public record Batch(int seq, LocalDate windowFrom, LocalDate windowTo, boolean moved) {
    }

    public record Line(String sku, String skuName, int batchCartons, int availableCartons, int missingCartons,
                       int unitsPerCarton) {
    }

    /** One way on; only the figures of its own option are present. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Option(ShortageOption option, LocalDate expectedDeliveryDate, Boolean afterWindow,
                         BigDecimal amountReduced, List<Integer> laterBatchesClosed, BigDecimal refundUpTo,
                         Boolean feePossible) {
    }

    public record Chosen(ShortageOption option, Instant at, String note) {
    }

    public static ShortageCardResponse of(ShortageCards.Card c) {
        return new ShortageCardResponse(c.cardId(), c.code(), c.orderId(), c.orderNumber(), c.status(), c.version(),
                new Batch(c.batch().seq(), c.batch().windowFrom(), c.batch().windowTo(), c.batch().moved()),
                c.missingCartons(), c.lines().stream().map(l -> new Line(l.sku(), l.skuName(), l.batchCartons(),
                        l.availableCartons(), l.missingCartons(), l.unitsPerCarton())).toList(),
                c.options().stream().map(ShortageCardResponse::option).toList(), c.salesNote(), c.sentAt(),
                c.chosen() == null ? null : new Chosen(c.chosen().option(), c.chosen().at(), c.chosen().note()),
                c.executedAt(), c.executionStarted(), c.endedReason());
    }

    public static Option option(ShortageCards.Option o) {
        return new Option(o.option(), o.expectedDeliveryDate(), o.afterWindow(), o.amountReduced(),
                o.laterBatchesClosed(), o.refundUpTo(), o.feePossible());
    }
}
