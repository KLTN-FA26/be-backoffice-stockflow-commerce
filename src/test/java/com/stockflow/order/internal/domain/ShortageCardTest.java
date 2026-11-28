package com.stockflow.order.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.ShortageCardEnd;
import com.stockflow.order.api.ShortageCardStatus;
import com.stockflow.order.api.ShortageCards;
import com.stockflow.order.api.ShortageOption;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shortage choice card's lifecycle (kltn-docs 17 §5.6): SENT, ANSWERED once by the customer with an option the card
 * offered, EXECUTED by Sales, or ended - superseded by a newer card, expired when the order moved on - and the rule that
 * a card whose execution started ends only with its order.
 */
class ShortageCardTest {

    private static final Instant NOW = Instant.parse("2026-11-28T03:00:00Z");
    private final UUID customerUser = UUID.randomUUID();
    private final UUID sales = UUID.randomUUID();

    private static List<ShortageCards.Option> allFour() {
        return List.of(ShortageCards.Option.waiting(LocalDate.parse("2026-12-12"), true),
                ShortageCards.Option.reduce(new BigDecimal("2530000")),
                ShortageCards.Option.shipPart(new BigDecimal("12650000"), List.of(2, 3)),
                ShortageCards.Option.cancel(new BigDecimal("30000000"), false));
    }

    private ShortageCard card(List<ShortageCards.Option> options, String note) {
        boolean waits = options.stream().anyMatch(o -> o.option() == ShortageOption.WAIT_FOR_PRODUCTION);
        return ShortageCard.send(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                List.of(new ShortageCard.Line(UUID.randomUUID(), UUID.randomUUID(), "LY-GIAY-12OZ", "Ly giấy 12oz", 10, 8,
                        2, 1000)), options, waits ? LocalDate.parse("2026-12-12") : null,
                waits ? ShortageCard.DateSource.STANDARD_LEAD_TIME : null, note, sales, NOW);
    }

    private ShortageCard card() {
        return card(allFour(), "Kho báo thiếu 2 thùng");
    }

    @Test
    void aSentCardCountsTheCartonsMissingAndKeepsTheNote() {
        ShortageCard card = card();
        assertThat(card.status()).isEqualTo(ShortageCardStatus.SENT);
        assertThat(card.missingCartons()).isEqualTo(2);
        assertThat(card.salesNote()).isEqualTo("Kho báo thiếu 2 thùng");
        assertThat(card(allFour(), "   ").salesNote()).as("a blank note is no note").isNull();
        assertThatThrownBy(() -> card(allFour(), "x".repeat(501))).isInstanceOf(BusinessException.class);
    }

    @Test
    void theWaitOptionCarriesItsDateAndOnlyItDoes() {
        assertThatThrownBy(() -> ShortageCard.send(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), card().lines(), allFour(), null, null, null, sales, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(card(List.of(ShortageCards.Option.reduce(BigDecimal.TEN)), null).expectedDeliveryDate()).isNull();
    }

    @Test
    void theCustomerChoosesOnceAnOptionTheCardOffered() {
        ShortageCard card = card(List.of(ShortageCards.Option.reduce(BigDecimal.TEN)), null);
        assertThatThrownBy(() -> card.choose(ShortageOption.CANCEL_ORDER, customerUser, null, NOW))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SHORTAGE_OPTION_NOT_AVAILABLE));
        card.choose(ShortageOption.REDUCE_CARTONS, customerUser, "  Giảm 2 thùng nhé  ", NOW);
        assertThat(card.status()).isEqualTo(ShortageCardStatus.ANSWERED);
        assertThat(card.chosenOption()).isEqualTo(ShortageOption.REDUCE_CARTONS);
        assertThat(card.chosenBy()).isEqualTo(customerUser);
        assertThat(card.customerNote()).isEqualTo("Giảm 2 thùng nhé");
        assertThatThrownBy(() -> card.choose(ShortageOption.REDUCE_CARTONS, customerUser, null, NOW))
                .as("one choice per card (17 §4.14 step 57)").isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SHORTAGE_CARD_STATE_CONFLICT));
    }

    @Test
    void aNoteOfMoreThanFiveHundredCharactersIsRefused() {
        assertThatThrownBy(() -> card().choose(ShortageOption.WAIT_FOR_PRODUCTION, customerUser, "x".repeat(501), NOW))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void salesExecuteOnlyWhatTheCustomerChose() {
        ShortageCard card = card();
        assertThatThrownBy(() -> card.executed(sales, NOW)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SHORTAGE_CARD_STATE_CONFLICT));
        assertThatThrownBy(() -> card.startExecution(sales, NOW)).isInstanceOf(BusinessException.class);
        card.choose(ShortageOption.SHIP_PART_CLOSE_REST, customerUser, null, NOW);
        card.startExecution(sales, NOW);
        assertThat(card.executionStartedAt()).isEqualTo(NOW);
        card.executed(sales, NOW.plusSeconds(60));
        assertThat(card.status()).isEqualTo(ShortageCardStatus.EXECUTED);
        assertThat(card.executedBy()).isEqualTo(sales);
        assertThat(card.expire(ShortageCardEnd.REALLOCATED)).as("an executed card does not end").isFalse();
    }

    @Test
    void aNewerCardSupersedesALiveOneAndNothingElse() {
        ShortageCard sent = card();
        UUID newer = UUID.randomUUID();
        sent.supersede(newer);
        assertThat(sent.status()).isEqualTo(ShortageCardStatus.SUPERSEDED);
        assertThat(sent.supersededBy()).isEqualTo(newer);
        assertThat(sent.endedReason()).isEqualTo(ShortageCardEnd.REPLACED);
        assertThatThrownBy(() -> sent.supersede(UUID.randomUUID())).isInstanceOf(BusinessException.class);
        ShortageCard answered = card();
        answered.choose(ShortageOption.WAIT_FOR_PRODUCTION, customerUser, null, NOW);
        answered.supersede(newer);
        assertThat(answered.chosenOption()).as("what the customer chose stays on record").isNotNull();
    }

    @Test
    void aCardEndsWhenTheOrderMovesOnWithoutIt() {
        ShortageCard card = card();
        assertThat(card.expire(ShortageCardEnd.REALLOCATED)).isTrue();
        assertThat(card.status()).isEqualTo(ShortageCardStatus.EXPIRED);
        assertThat(card.endedReason()).isEqualTo(ShortageCardEnd.REALLOCATED);
        assertThat(card.expire(ShortageCardEnd.ORDER_CANCELLED)).as("already ended").isFalse();
        assertThatThrownBy(() -> card().expire(ShortageCardEnd.REPLACED)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCardBeingCarriedOutEndsOnlyWithItsOrder() {
        ShortageCard card = card();
        card.choose(ShortageOption.SHIP_PART_CLOSE_REST, customerUser, null, NOW);
        card.startExecution(sales, NOW);
        assertThat(card.expire(ShortageCardEnd.REMAINDER_CLOSED)).isFalse();
        assertThat(card.expire(ShortageCardEnd.DECIDED_WITHOUT_CARD)).isFalse();
        assertThat(card.status()).isEqualTo(ShortageCardStatus.ANSWERED);
        assertThat(card.expire(ShortageCardEnd.ORDER_CANCELLED)).isTrue();
        assertThat(card.chosenOption()).isEqualTo(ShortageOption.SHIP_PART_CLOSE_REST);
    }

    @Test
    void theLineCountsAddUp() {
        assertThatThrownBy(() -> new ShortageCard.Line(UUID.randomUUID(), UUID.randomUUID(), "X", null, 10, 7, 2, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShortageCard.Line(UUID.randomUUID(), UUID.randomUUID(), "X", null, 10, 10, 0, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
