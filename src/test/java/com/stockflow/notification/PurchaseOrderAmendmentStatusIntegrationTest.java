package com.stockflow.notification;

import com.stockflow.common.id.Identifiers;
import com.stockflow.notification.api.AmendmentDeliveryStatus;
import com.stockflow.notification.api.NotificationService;
import com.stockflow.notification.internal.domain.DeliveryStatus;
import com.stockflow.notification.internal.domain.NotificationChannel;
import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-117: the amendment delivery state of an order is that of the latest attempt of its highest revision, and names
 * that revision.
 */
@IntegrationTest
@Import(PostgresContainer.class)
class PurchaseOrderAmendmentStatusIntegrationTest {

    @Autowired NotificationService notifications;
    @Autowired DeliveryLogJpaRepository logs;
    @Autowired TransactionTemplate tx;

    private void attempt(UUID po, int revision, DeliveryStatus status, boolean terminal) {
        String reference = "purchase-order:" + po + ":revision:" + revision;
        var row = new DeliveryLogJpaEntity(Identifiers.newId(), "purchase-order.amended", NotificationChannel.EMAIL,
                "supplier@example.com", status, status == DeliveryStatus.FAILED ? "MAIL_SEND_FAILED" : null,
                status == DeliveryStatus.SENT ? Instant.now() : null);
        row.correlate(reference);
        row.recordGeneration(0);
        row.recordAttempt(1);
        if (terminal) row.stopRetrying();
        tx.executeWithoutResult(s -> logs.saveAndFlush(row));
    }

    @Test
    void highestRevisionDecidesNotTheLatestRow() {
        UUID po = UUID.randomUUID();
        attempt(po, 2, DeliveryStatus.FAILED, true);
        attempt(po, 1, DeliveryStatus.SENT, false);   // written later, but revision 1 is not the newest notice

        assertThat(notifications.purchaseOrderAmendmentStatuses(List.of(po)))
                .containsEntry(po, new AmendmentDeliveryStatus(2, "FAILED"));
    }

    /**
     * What the listener asks before sending a notice: a later revision's notice, of this order only, compared as a
     * number (10 is later than 9), with the order's own and cancellation rows of the same order kept away from the cast.
     */
    @Test
    void laterAmendmentNoticeIsTheOrdersAndComparedAsANumber() {
        UUID po = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        for (String reference : List.of("purchase-order:" + po, "purchase-order:" + po + ":cancellation")) {
            var row = new DeliveryLogJpaEntity(Identifiers.newId(), "purchase-order.sent", NotificationChannel.EMAIL,
                    "supplier@example.com", DeliveryStatus.FAILED, "MAIL_SEND_FAILED", null);
            row.correlate(reference);
            row.recordGeneration(0);
            row.recordAttempt(1);
            tx.executeWithoutResult(s -> logs.saveAndFlush(row));
        }
        attempt(po, 10, DeliveryStatus.FAILED, false);
        attempt(other, 20, DeliveryStatus.SENT, false);

        assertThat(logs.existsLaterAmendmentNotice(po.toString(), 9)).isTrue();
        assertThat(logs.existsLaterAmendmentNotice(po.toString(), 10)).isFalse();
        assertThat(logs.existsLaterAmendmentNotice(other.toString(), 19)).isTrue();
        assertThat(logs.existsLaterAmendmentNotice(UUID.randomUUID().toString(), 0)).isFalse();
    }

    @Test
    void onlyRevisionOneReportsItsOwnStatusAndAnUnattemptedOrderIsAbsent() {
        UUID sent = UUID.randomUUID();
        UUID none = UUID.randomUUID();
        attempt(sent, 1, DeliveryStatus.SENT, false);

        var statuses = notifications.purchaseOrderAmendmentStatuses(List.of(sent, none));

        assertThat(statuses).containsEntry(sent, new AmendmentDeliveryStatus(1, "DELIVERED")).doesNotContainKey(none);
    }
}
