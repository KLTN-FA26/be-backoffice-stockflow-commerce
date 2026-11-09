package com.stockflow.support;

import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.SampleRound;
import com.stockflow.order.internal.service.ProductionOrderService;
import com.stockflow.order.internal.service.ProductionSampleService;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Print samples for integration tests (4A-4): a photo the storage layer accepts, a customer's portal account (who
 * answers a sample, 13 BR-13), and a SENT production order taken into mass production the way production takes it -
 * the producer's acceptance, then the coordinator's start when no sample is to be approved, or round 1 and the
 * customer's approval when one is. Nothing is written by SQL around the services: the database's rules on the rounds
 * ({@code tg_production_order_sample}) hold for the tests as they do for production.
 */
public final class PrintSamples {

    /** A JPEG's first bytes: what {@code ContentTypePolicy} checks. Upload inspection is off in these tests. */
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I',
            'F', 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, (byte) 0xFF, (byte) 0xD9};

    private PrintSamples() {
    }

    /** {@code count} photos of a print sample, sent as multipart parts. */
    public static IncomingFiles photos(int count) {
        List<FileUpload> uploads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            uploads.add(new FileUpload("sample-" + i + ".jpg", "image/jpeg", JPEG.length,
                    new ByteArrayInputStream(JPEG)));
        }
        return IncomingFiles.sent(uploads);
    }

    /** A portal account for {@code customerId} - the user who answers its print samples. */
    public static UUID portalUser(JdbcTemplate jdbc, TransactionTemplate tx, EntityManager entityManager,
                                  UUID customerId) {
        return tx.execute(s -> {
            UUID user = ReferenceRows.user(entityManager);
            jdbc.update("UPDATE customer.customer SET user_id = ? WHERE id = ?", user, customerId);
            return user;
        });
    }

    /**
     * {@code sent} accepted by its producer and in mass production: started by the coordinator when it has no print
     * sample to approve, or through round 1 approved by {@code customerUser} when it has one.
     *
     * @return the production order as stored after it
     */
    public static ProductionOrder massPrinting(ProductionOrderService productionOrders,
                                               ProductionSampleService samples, ProductionOrder sent,
                                               UUID coordinator, UUID customerUser) {
        ProductionOrder accepted = productionOrders.accept(sent.id(), sent.version(),
                LocalDate.now().plusDays(10), coordinator);
        return started(productionOrders, samples, accepted, coordinator, customerUser);
    }

    /**
     * {@code accepted} (ACCEPTED by its producer) in mass production, as {@link #massPrinting} takes it from there: the
     * coordinator's start, or round 1 approved by {@code customerUser}.
     *
     * @return the production order as stored after it
     */
    public static ProductionOrder started(ProductionOrderService productionOrders, ProductionSampleService samples,
                                          ProductionOrder accepted, UUID coordinator, UUID customerUser) {
        if (!accepted.sampleApprovalRequired()) {
            return productionOrders.startProduction(accepted.id(), accepted.version(), null, coordinator);
        }
        var opened = samples.open(accepted.id(), null, null, photos(1), coordinator);
        UUID round = opened.rounds().waiting().map(SampleRound::id).orElseThrow();
        return samples.answer(accepted.customerId(), customerUser, round, SampleRound.Result.APPROVED, null).order();
    }
}
