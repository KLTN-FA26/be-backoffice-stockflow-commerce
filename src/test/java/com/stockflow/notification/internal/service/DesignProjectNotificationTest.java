package com.stockflow.notification.internal.service;

import com.stockflow.contracts.DesignBatchSubmittedForReview;
import com.stockflow.contracts.DesignProjectQuoteRequested;
import com.stockflow.notification.api.InboxMessage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A project's batch tells the reviewers once for the round (kltn-docs 12 §4.10), and a project quote request tells the
 * sales staff once with every line (§4.11) - by SKU and numbers, never a name the customer typed.
 */
class DesignProjectNotificationTest {

    @Test
    void aBatchIsOneNoticeForItsRound() {
        var event = new DesignBatchSubmittedForReview(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), Instant.parse("2026-11-08T03:00:00Z"));
        InboxMessage message = DesignReviewNotificationListener.message(event);

        assertThat(message.title()).contains("3 bản");
        assertThat(message.link()).isEqualTo("/admin/design-review-rounds/" + event.roundId());
        assertThat(message.dedupeKey()).isEqualTo("design-review-round:" + event.roundId());
    }

    @Test
    void aProjectQuoteRequestListsEveryLineAndTheDate() {
        var event = new DesignProjectQuoteRequested(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                List.of(new DesignProjectQuoteRequested.Line(UUID.randomUUID(), "CUP12-0312345678-01", 10),
                        new DesignProjectQuoteRequested.Line(UUID.randomUUID(), "BAG-0312345678-01", 4)),
                LocalDate.of(2026, 12, 1), Instant.parse("2026-11-08T03:00:00Z"));
        InboxMessage message = DesignQuoteRequestNotificationListener.message(event);

        assertThat(message.title()).contains("2 bản");
        assertThat(message.body()).contains("CUP12-0312345678-01 x10", "BAG-0312345678-01 x4", "01/12/2026");
        assertThat(message.dedupeKey()).isEqualTo("design-project-quote:" + event.requestId() + ":"
                + event.requestedAt().toEpochMilli());
    }
}
