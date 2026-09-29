package com.stockflow;

import com.stockflow.support.*;
import com.stockflow.notification.api.InventoryAlertMessage;
import com.stockflow.notification.internal.repository.InventoryAlertDeliveryRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real queue SQL and lease semantics; SMTP transport is tested separately. */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class InventoryAlertDeliveryIntegrationTest {
    @Autowired InventoryAlertDeliveryRepository queue;
    @Autowired JdbcTemplate jdbc;
    final Instant now=Instant.parse("2100-01-01T00:00:00Z");
    final List<String> recipients=List.of("planner@example.test");
    @BeforeEach void isolateQueue(){jdbc.update("delete from notification.inventory_alert_delivery");}
    UUID enqueue(){
        UUID id=UUID.randomUUID();queue.enqueue(new InventoryAlertMessage(id,"SKU","REORDER","OPEN",2,5,now));return id;
    }
    @Test void missingRecipientsStayVisibleAndCanBeRetriedAfterConfiguration(){
        UUID id=enqueue();queue.claim(now,List.of());
        assertThat(queue.list(id)).singleElement().satisfies(d->{assertThat(d.status()).isEqualTo("BLOCKED_CONFIG");assertThat(d.lastError()).isEqualTo("NO_RECIPIENTS");});
        assertThat(queue.claim(now,List.of())).isNull();
        queue.retry(id,now);queue.claim(now,recipients);
        var pending=queue.claim(now,recipients);assertThat(pending.token()).isNotNull();
        assertThat(queue.complete(pending,now,null)).isTrue();
        assertThat(queue.complete(pending,now,null)).isFalse();
        assertThat(queue.list(id).stream().filter(d->d.status().equals("SENT"))).hasSize(1);
    }
    @Test void failureBackoffDoesNotBlockLaterMessagesAndLeaseRejectsStaleWorker(){
        UUID first=enqueue();queue.claim(now,recipients);var attempt=queue.claim(now,recipients);
        queue.complete(attempt,now,"MailSendException");
        assertThat(queue.list(first).stream().filter(d->!d.recipient().isEmpty()).findFirst().orElseThrow().nextAttemptAt()).isAfter(now);
        UUID second=enqueue();queue.claim(now,recipients);var later=queue.claim(now,recipients);
        assertThat(later.alertId()).isEqualTo(second);queue.complete(later,now,null);
        var expired=queue.claim(now.plusSeconds(61),recipients);
        var replacement=queue.claim(now.plusSeconds(362),recipients);
        assertThat(replacement.id()).isEqualTo(expired.id());assertThat(replacement.token()).isNotEqualTo(expired.token());
        assertThat(queue.complete(expired,now.plusSeconds(363),null)).isFalse();
        assertThat(queue.complete(replacement,now.plusSeconds(363),null)).isTrue();
    }
    @Test void recoveryDoesNotResurrectFailedOpenNotification(){
        UUID id=enqueue();queue.claim(now,recipients);var attempt=queue.claim(now,recipients);
        queue.enqueue(new InventoryAlertMessage(id,"SKU","REORDER","RESOLVED",10,5,now));
        queue.complete(attempt,now,"MailSendException");queue.retry(id,now);
        assertThat(queue.list(id).stream().filter(d->d.phase().equals("OPEN"))).allSatisfy(d->assertThat(d.status()).isEqualTo("SUPERSEDED"));
        queue.claim(now,recipients);var recovery=queue.claim(now,recipients);assertThat(recovery.phase()).isEqualTo("RESOLVED");
    }
    @Test void tenthFailureRequiresExplicitRetry(){
        UUID id=enqueue();queue.claim(now,recipients);
        Instant current=now;
        for(int i=0;i<10;i++) {var p=queue.claim(current,recipients);queue.complete(p,current,"MailSendException");current=current.plusSeconds(4000);}
        assertThat(queue.list(id).stream().filter(d->!d.recipient().isEmpty())).singleElement().satisfies(d->assertThat(d.status()).isEqualTo("FAILED"));
        assertThat(queue.claim(current,recipients)).isNull();queue.retry(id,current);
        assertThat(queue.claim(current,recipients).attempts()).isEqualTo(1);
    }
    @Test void crashedOpenAttemptIsNotSentAgainAfterRecovery(){
        UUID id=enqueue();queue.claim(now,recipients);var crashed=queue.claim(now,recipients);
        queue.enqueue(new InventoryAlertMessage(id,"SKU","REORDER","RESOLVED",10,5,now.plusSeconds(1)));
        // The SENDING attempt has no completion: simulate process death and lease expiry.
        var discarded=queue.claim(now.plusSeconds(301),recipients);
        assertThat(discarded.id()).isEqualTo(crashed.id());assertThat(discarded.token()).isNull();
        assertThat(queue.list(id).stream().filter(d->d.id().equals(crashed.id()))).singleElement()
                .satisfies(d->assertThat(d.status()).isEqualTo("SUPERSEDED"));
    }
}
