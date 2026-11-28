package com.stockflow.order.internal.controller.dto;

import com.stockflow.order.internal.domain.ShortageCard;
import com.stockflow.order.internal.service.ShortageCardService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the order page's "Thiếu thùng" section shows (kltn-docs 17 §4.14 step 55): whether a card could be sent now, why
 * not when it could not, what it would offer, and the order's cards with who did what.
 *
 * @param notSendable  when {@code sendable} is false: {@code NOT_ON_HOLD}, {@code NOTHING_SHORT}, {@code LIVE_CARD}
 *                     (a card is waiting: sending replaces it) or {@code NO_BATCH}
 * @param batch        the batch a card would be about; null when none is released
 * @param expectedDateSource where the wait option's date would come from: {@code PRODUCTION_ORDER} or
 *                     {@code STANDARD_LEAD_TIME}; staff only
 */
@Schema(name = "ShortagePreview")
public record ShortagePreviewResponse(UUID orderId, String orderNumber, boolean sendable, String notSendable,
                                      ShortageCardResponse.Batch batch, List<ShortageCardResponse.Line> lines,
                                      List<ShortageCardResponse.Option> options, String expectedDateSource,
                                      StaffCard live, List<StaffCard> history) {

    /** A card and who did what on it: the staff's view only. */
    public record StaffCard(ShortageCardResponse card, UUID sentBy, String sentByName, UUID chosenBy,
                            String chosenByName, UUID executedBy, String executedByName, String expectedDateSource) {
    }

    public static ShortagePreviewResponse of(ShortageCardService.Preview p, Map<UUID, String> people) {
        return new ShortagePreviewResponse(p.orderId(), p.orderNumber(), p.sendable(),
                p.notSendable() == null ? null : p.notSendable().name(),
                p.batch() == null ? null : new ShortageCardResponse.Batch(p.batch().seq(), p.batch().windowFrom(),
                        p.batch().windowTo(), p.batch().moved()),
                p.lines().stream().map(l -> new ShortageCardResponse.Line(l.sku(), l.skuName(), l.batchCartons(),
                        l.availableCartons(), l.missingCartons(), l.unitsPerCarton())).toList(),
                p.options().stream().map(ShortageCardResponse::option).toList(), name(p.expectedDateSource()),
                p.live() == null ? null : staff(p.live(), people), p.history().stream().map(c -> staff(c, people))
                        .toList());
    }

    /** The people a preview names, to look up in one read. */
    public static java.util.Set<UUID> peopleOf(ShortageCardService.Preview p) {
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        java.util.stream.Stream.concat(p.history().stream(), java.util.stream.Stream.ofNullable(p.live()))
                .forEach(c -> {
                    ids.add(c.sentBy());
                    ids.add(c.chosenBy());
                    ids.add(c.executedBy());
                });
        ids.remove(null);
        return ids;
    }

    private static StaffCard staff(ShortageCardService.StaffCard c, Map<UUID, String> people) {
        return new StaffCard(ShortageCardResponse.of(c.card()), c.sentBy(), people.get(c.sentBy()), c.chosenBy(),
                c.chosenBy() == null ? null : people.get(c.chosenBy()), c.executedBy(),
                c.executedBy() == null ? null : people.get(c.executedBy()), name(c.expectedDateSource()));
    }

    private static String name(ShortageCard.DateSource source) {
        return source == null ? null : source.name();
    }
}
