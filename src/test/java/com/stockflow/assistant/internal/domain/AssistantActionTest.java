package com.stockflow.assistant.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantActionTest {

    private static final Instant NOW = Instant.parse("2026-11-04T08:00:00Z");
    private static final UUID OWNER = UUID.randomUUID();

    private static AssistantAction suggestion() {
        return AssistantAction.suggest(UUID.randomUUID(), OWNER, UUID.randomUUID(), UUID.randomUUID(),
                AssistantAction.Kind.PURCHASE_ORDER_DRAFT, "PURCHASE_ORDER_DRAFT:GOVIET", "{}", NOW);
    }

    @Test
    @DisplayName("open for a day, then EXPIRED with nothing stored; only its owner decides it")
    void opensForADay() {
        AssistantAction action = suggestion();

        assertThat(action.statusAt(NOW.plus(Duration.ofHours(23)))).isEqualTo(AssistantAction.Status.SUGGESTED);
        assertThat(action.statusAt(NOW.plus(AssistantAction.OPEN_FOR))).isEqualTo(AssistantAction.Status.EXPIRED);
        assertThat(action.storedStatus()).isEqualTo(AssistantAction.Status.SUGGESTED);
        assertThat(action.ownedBy(OWNER)).isTrue();
        assertThat(action.ownedBy(UUID.randomUUID())).isFalse();
        assertThatThrownBy(() -> action.requireOpen(NOW.plus(Duration.ofDays(2))))
                .isInstanceOfSatisfying(BusinessException.class,
                        refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.ASSISTANT_ACTION_EXPIRED));
    }

    @Test
    @DisplayName("confirmed, it names its document and can no longer be dismissed; dismissed, it is closed for good")
    void decisions() {
        AssistantAction confirmed = suggestion();
        UUID order = UUID.randomUUID();
        confirmed.confirm("{\"lines\":[]}", order, "PO-1", NOW.plusSeconds(60));

        assertThat(confirmed.statusAt(NOW.plus(Duration.ofDays(3)))).as("a decision never expires")
                .isEqualTo(AssistantAction.Status.CONFIRMED);
        assertThat(confirmed.resultId()).isEqualTo(order);
        assertThatThrownBy(() -> confirmed.dismiss(NOW)).isInstanceOfSatisfying(BusinessException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.ASSISTANT_ACTION_CLOSED));

        AssistantAction dismissed = suggestion();
        dismissed.dismiss(NOW.plus(Duration.ofDays(2)));
        dismissed.dismiss(NOW.plus(Duration.ofDays(3)));
        assertThat(dismissed.storedStatus()).isEqualTo(AssistantAction.Status.DISMISSED);
        assertThat(dismissed.decidedAt()).as("the first dismissal").isEqualTo(NOW.plus(Duration.ofDays(2)));
        assertThatThrownBy(() -> dismissed.requireOpen(NOW)).isInstanceOfSatisfying(BusinessException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.ASSISTANT_ACTION_CLOSED));
        assertThatThrownBy(() -> dismissed.confirm("{}", order, "PO-2", NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an answer's suggestion names its conversation and answer, a photo's neither; both name what they "
            + "propose")
    void itsSource() {
        assertThatThrownBy(() -> AssistantAction.suggest(UUID.randomUUID(), OWNER, UUID.randomUUID(), null,
                AssistantAction.Kind.PURCHASE_ORDER_DRAFT, "key", "{}", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AssistantAction.suggest(UUID.randomUUID(), OWNER, null, null,
                AssistantAction.Kind.GOODS_RECEIPT_DRAFT, null, "{}", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AssistantAction.suggest(UUID.randomUUID(), OWNER, null, null,
                AssistantAction.Kind.GOODS_RECEIPT_DRAFT, "GOODS_RECEIPT_DRAFT:PO-1", "{}", NOW).conversationId())
                .isNull();
    }

    @Test
    @DisplayName("superseded by a newer one, it is closed: not confirmed, and a dismissal changes nothing")
    void superseded() {
        AssistantAction replaced = new AssistantAction(UUID.randomUUID(), OWNER, UUID.randomUUID(), UUID.randomUUID(),
                AssistantAction.Kind.PURCHASE_ORDER_DRAFT, "PURCHASE_ORDER_DRAFT:GOVIET", "{}",
                AssistantAction.Status.SUPERSEDED, null, NOW, NOW.plus(AssistantAction.OPEN_FOR),
                NOW.plusSeconds(30), null, null);

        assertThat(replaced.statusAt(NOW.plus(Duration.ofDays(3)))).isEqualTo(AssistantAction.Status.SUPERSEDED);
        assertThatThrownBy(() -> replaced.requireOpen(NOW)).isInstanceOfSatisfying(BusinessException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.ASSISTANT_ACTION_CLOSED));
        replaced.dismiss(NOW.plusSeconds(60));
        assertThat(replaced.storedStatus()).isEqualTo(AssistantAction.Status.SUPERSEDED);
        assertThat(replaced.decidedAt()).isEqualTo(NOW.plusSeconds(30));
    }
}
