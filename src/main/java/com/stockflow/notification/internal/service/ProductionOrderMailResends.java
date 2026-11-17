package com.stockflow.notification.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.notification.api.ProductionOrderResends;
import com.stockflow.notification.internal.domain.DeliveryStatus;
import com.stockflow.notification.internal.domain.NotificationChannel;
import com.stockflow.notification.internal.domain.ProductionOrderMailResend;
import com.stockflow.notification.internal.entity.DeliveryLogJpaEntity;
import com.stockflow.notification.internal.repository.DeliveryLogJpaRepository;
import com.stockflow.notification.internal.service.ProductionOrderMessages.Kind;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * A staff re-send of a production order's latest message to its producer (4A-3). The automatic retry gives up after
 * five failures over some hours, and a link dies when it expires or the links key is rotated: without this, nothing
 * could reach the producer again.
 *
 * <pre>
 * 1. the production order's message lock   a message is being sent now          -> refused
 * 2. the latest message                     "sample approved" once attempted, else "sent"; neither -> refused
 * 3. its newest generation                  neither sent nor final (retrying)    -> refused: it is on its way
 * 4. the next generation                    one PENDING row RESEND_REQUESTED, the resend event after commit
 * </pre>
 *
 * <p>A stop is never re-sent: only an open production order is (the order module checks), and an open one has none.
 * The PENDING row is what a second click finds in step 3, so two clicks make one message.</p>
 */
@Service
@Transactional
class ProductionOrderMailResends implements ProductionOrderResends {

    private static final Logger log = LoggerFactory.getLogger(ProductionOrderMailResends.class);

    static final String RESEND_REQUESTED = "RESEND_REQUESTED";

    private final DeliveryLogJpaRepository logs;
    private final ApplicationEventPublisher events;

    ProductionOrderMailResends(DeliveryLogJpaRepository logs, ApplicationEventPublisher events) {
        this.logs = logs;
        this.events = events;
    }

    @Override
    public Resend resend(UUID productionOrderId, Instant at) {
        if (!logs.tryLockDelivery(ProductionOrderMailListener.lockName(productionOrderId))) {
            throw refused("a message about it is being sent right now");
        }
        Kind kind = latest(productionOrderId);
        String reference = ProductionOrderMailListener.reference(productionOrderId, kind);
        int current = logs.latestGeneration(reference);
        boolean settled = logs.countByOperationReferenceAndDeliveryGenerationAndStatus(reference, current,
                DeliveryStatus.SENT) > 0
                || logs.existsByOperationReferenceAndDeliveryGenerationAndTerminalTrue(reference, current);
        if (!settled) {
            throw refused("its latest message is still being sent or retried");
        }
        int generation = Math.addExact(current, 1);
        var requested = new DeliveryLogJpaEntity(Identifiers.newId(), kind.template, NotificationChannel.EMAIL, "-",
                DeliveryStatus.PENDING, RESEND_REQUESTED, null);
        requested.correlate(reference);
        requested.recordGeneration(generation);
        requested.recordAttempt(Math.toIntExact(logs.countByOperationReferenceAndDeliveryGeneration(reference,
                generation) + 1));
        logs.saveAndFlush(requested);
        events.publishEvent(new ProductionOrderMailResend(Identifiers.newId(), productionOrderId, kind.name(),
                generation, at));
        log.info("Production order message {} goes again as generation {}", reference, generation);
        return new Resend(kind.name(), generation);
    }

    /** "Sample approved" once it was attempted - it is the newer message - otherwise "sent". */
    private Kind latest(UUID productionOrderId) {
        for (Kind kind : new Kind[] {Kind.SAMPLE_APPROVED, Kind.SENT}) {
            if (logs.latestGeneration(ProductionOrderMailListener.reference(productionOrderId, kind)) != null) {
                return kind;
            }
        }
        throw refused("its producer was not written to yet");
    }

    private static BusinessException refused(String why) {
        return new BusinessException(ErrorCode.PRODUCTION_ORDER_RESEND_NOT_ALLOWED,
                "The production order's message cannot be sent again: " + why);
    }
}
