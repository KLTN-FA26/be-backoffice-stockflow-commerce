package com.stockflow.order;

import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.Checksums;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.contracts.ProductionOrderSent;
import com.stockflow.notification.internal.domain.Email;
import com.stockflow.order.internal.domain.Producer;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import com.stockflow.order.internal.service.ProductionOrderDispatch;
import com.stockflow.common.id.Identifiers;
import com.stockflow.support.Await;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 4A-3 against the real database (kltn-docs 17 §4.3 steps 7, 10, 12-13, BR-23; 00 §6; QD-7): approving a production
 * order sends it - the slip drawn and kept, the producer e-mailed once with time-limited links that check the files on
 * every use, the receiving warehouse's managers told in-app - and a sent production order that is cancelled or closed
 * tells its producer to stop, on the same channel.
 *
 * <p>The fixtures are {@link ProductionOrderIntegrationTest}'s - a confirmed order of a signed contract, its shipping
 * warehouse chosen, a customer SKU on a design the customer really confirmed - with the same property source and mocks,
 * so both share one context. E-mail goes to the test profile's recording transport; each test's producer has an address
 * of its own, so what it was sent is exact whatever the other tests sent.</p>
 */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = {"stockflow.upload-inspection.enabled=false",
        "stockflow.notification.email.customer.enabled=false"})
class ProductionOrderDispatchIntegrationTest extends ProductionOrderDispatchFixtures {

    // ---- scenarios --------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("17 §4.3 step 7: approved, so sent - the slip kept, one e-mail to the producer, the warehouse told")
    void approvalSendsIt() throws Exception {
        ProductionOrder sent = approved(producer);

        assertThat(sent.status()).isEqualTo(ProductionOrderStatus.SENT);
        Map<String, Object> slip = jdbc.queryForMap(
                "SELECT * FROM ordering.production_order_slip WHERE production_order_id = ?", sent.id());
        assertThat((String) slip.get("storage_key")).startsWith("documents/");
        byte[] pdf = bytes((String) slip.get("storage_key"));
        assertThat(slip.get("sha256")).as("the digest is the stored object's").isEqualTo(Checksums.sha256Hex(pdf));
        assertThat(slip.get("size_bytes")).isEqualTo((long) pdf.length);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("PHIẾU ĐƠN SẢN XUẤT", sent.code(), sku.sku(),
                    "Dispatch Test Co", "Xưởng in thử", "Chị Lan - 0909123456 - " + producerEmail,
                    "Warehouse " + a.code(), "7 Kho Street", "12 x 50 cái/thùng = 600 cái", "600 x 400 x 350 mm",
                    sku.snapshotId().toString(), sku.checksum());
        }

        Email email = awaitMail(sent.code());
        assertThat(email.to()).containsExactly(producerEmail);
        assertThat(email.idempotencyKey()).isEqualTo("production-order:" + sent.id() + ":sent");
        assertThat(email.text()).contains("/api/v1/production-order-files/" + sent.id() + "/slip?expires=",
                "/print-files/" + sku.snapshotId() + "?expires=");
        Await.until("the delivery is recorded", ASYNC, () -> !deliveries(sent.id(), "sent").isEmpty());
        assertThat(deliveries(sent.id(), "sent")).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("status", "SENT").containsEntry("recipient", producerEmail)
                    .containsEntry("template_code", "production_order_sent")
                    .containsEntry("external_reference", "production-order:" + sent.id() + ":sent");
        });

        Await.until("the warehouse manager is told", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM notification.user_notification WHERE recipient_id = ? AND dedupe_key = ?""",
                Long.class, warehouseManager, "production-order-sent:" + sent.id()) == 1);
        Map<String, Object> notice = jdbc.queryForMap("""
                SELECT severity, title, body, link FROM notification.user_notification
                 WHERE recipient_id = ? AND dedupe_key = ?""", warehouseManager, "production-order-sent:" + sent.id());
        assertThat(notice).containsEntry("severity", "INFO").containsEntry("link", "/production/orders/" + sent.id());
        assertThat((String) notice.get("title")).contains(sent.code(), "Warehouse " + a.code());
        assertThat((String) notice.get("body")).as("cartons, volume (12 x 0.6 x 0.4 x 0.35 m), need-by date")
                .contains("12 thùng", "1.01 m³", sent.needByDate().format(java.time.format.DateTimeFormatter
                        .ofPattern("dd/MM/yyyy")));

        // The event delivered again (a redelivery, a restart's republication): nothing is sent twice.
        Await.until("the first deliveries complete", ASYNC, () -> pendingPublications(sent.id()) == 0);
        tx.executeWithoutResult(s -> events.publishEvent(new ProductionOrderSent(UUID.randomUUID(), sent.id(),
                sent.code(), a.id(), sent.needByDate(), 12, BigDecimal.ONE, sent.sentAt())));
        Await.until("the redelivery completes", ASYNC, () -> pendingPublications(sent.id()) == 0);
        assertThat(mailTo(producerEmail)).as("each approval sends one e-mail").hasSize(1);
        assertThat(deliveries(sent.id(), "sent")).hasSize(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notification.user_notification WHERE recipient_id = ? AND dedupe_key = ?""",
                Long.class, warehouseManager, "production-order-sent:" + sent.id())).isEqualTo(1);

        ProductionOrderDispatch.DispatchView view = dispatch.dispatch(sent.id(), 0, 20);
        assertThat(view.channel()).isEqualTo("EMAIL");
        assertThat(view.slip().sha256()).isEqualTo(slip.get("sha256"));
        assertThat(view.messages().items()).singleElement().satisfies(attempt -> {
            assertThat(attempt.templateCode()).isEqualTo("production_order_sent");
            assertThat(attempt.status()).isEqualTo("SENT");
        });
        handedOut(() -> dispatch.slipLink(sent.id()));
    }

    @Test
    @DisplayName("17 §4.3 step 7: the e-mailed links work until their time, and check the file on every use")
    void linksCheckEverything() {
        ProductionOrder sent = approved(producer);
        Email email = awaitMail(sent.code());
        Link slip = link(email.text(), "/slip");
        Link print = link(email.text(), "/print-files/" + sku.snapshotId());
        assertThat(Instant.ofEpochSecond(slip.expires())).as("valid for the configured 30 days")
                .isAfter(Instant.now().plus(Duration.ofDays(29)))
                .isBefore(Instant.now().plus(Duration.ofDays(31)));

        handedOut(() -> dispatch.openSlip(sent.id(), slip.expires(), slip.signature()));
        handedOut(() -> dispatch.openPrintFile(sent.id(), sku.snapshotId(), print.expires(), print.signature()));

        assertThatThrownBy(() -> dispatch.openSlip(sent.id(), slip.expires(), slip.signature() + "x"))
                .as("an altered signature").extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_LINK_INVALID);
        assertThatThrownBy(() -> dispatch.openSlip(sent.id(), slip.expires() + 86_400, slip.signature()))
                .as("a later expiry written in").extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_LINK_INVALID);
        assertThatThrownBy(() -> dispatch.openPrintFile(sent.id(), sku.snapshotId(), slip.expires(),
                slip.signature())).as("the slip's link used for a print file")
                .extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_LINK_INVALID);
        UUID otherDesign = UUID.randomUUID();
        String foreign = links.url(ProductionOrderDispatch.printFilePath(sent.id(), otherDesign),
                Instant.now().plusSeconds(600));
        Link foreignLink = link(foreign, "/print-files/" + otherDesign);
        assertThatThrownBy(() -> dispatch.openPrintFile(sent.id(), otherDesign, foreignLink.expires(),
                foreignLink.signature())).as("a signed link to a design that is not the production order's")
                .extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_LINK_INVALID);
        Link expired = link(links.url(ProductionOrderDispatch.slipPath(sent.id()), Instant.now().minusSeconds(1)),
                "/slip");
        assertThatThrownBy(() -> dispatch.openSlip(sent.id(), expired.expires(), expired.signature()))
                .extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_LINK_EXPIRED);
        assertThat(ErrorCode.PRODUCTION_ORDER_LINK_EXPIRED.httpStatus()).isEqualTo(410);
        assertThat(ErrorCode.PRODUCTION_ORDER_LINK_INVALID.httpStatus()).isEqualTo(403);

        // The files checked on every use: a print file or a slip that changed in the store is not handed out.
        tamper(sku.printFileKey(), FileCategory.DESIGN_RENDER);
        assertThatThrownBy(() -> dispatch.openPrintFile(sent.id(), sku.snapshotId(), print.expires(),
                print.signature())).extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRINT_FILE_MISMATCH);
        tamper(slipKey(sent.id()), FileCategory.DOCUMENT);
        assertThatThrownBy(() -> dispatch.openSlip(sent.id(), slip.expires(), slip.signature()))
                .extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_SLIP_MISMATCH);
        assertThatThrownBy(() -> dispatch.slipLink(sent.id())).as("staff are refused the changed slip too")
                .extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_SLIP_MISMATCH);
    }

    /** An ACTIVE user assigned to warehouse A whose only role holds just the given permissions (none: a bare role). */
    private UUID holderOf(String... permissions) {
        UUID user = tx.execute(s -> ReferenceRows.user(entityManager));
        UUID role = Identifiers.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO identity.app_role (id, code, name, version, created_at) VALUES (?, ?, 'Arrival test', 0, NOW())",
                    role, "ARR_" + role.toString().replace("-", "").substring(12).toUpperCase());
            for (String code : permissions) {
                jdbc.update("""
                        INSERT INTO identity.role_permission (id, role_id, permission_id, version, created_at)
                        SELECT ?, ?, p.id, 0, NOW() FROM identity.permission p WHERE p.code = ?""",
                        Identifiers.newId(), role, code);
            }
            jdbc.update("INSERT INTO identity.user_role (id, user_id, role_id, version, created_at) VALUES (?, ?, ?, 0, NOW())",
                    Identifiers.newId(), user, role);
            jdbc.update("INSERT INTO identity.user_warehouse (id, user_id, warehouse_id, version, created_at) VALUES (?, ?, ?, 0, NOW())",
                    Identifiers.newId(), user, a.id());
        });
        return user;
    }

    @Test
    @DisplayName("F1: the arrival notice goes to whoever holds procurement-goods-receipts:CREATE at the warehouse, "
            + "not to a user of the same warehouse without it")
    void arrivalNoticeFollowsThePermission() {
        UUID holder = holderOf("procurement-goods-receipts:CREATE");
        UUID bystander = holderOf("procurement-goods-receipts:READ");

        ProductionOrder sent = approved(producer);

        String key = "production-order-sent:" + sent.id();
        Await.until("the permission holder is told", ASYNC, () -> jdbc.queryForObject(
                "SELECT count(*) FROM notification.user_notification WHERE recipient_id = ? AND dedupe_key = ?",
                Long.class, holder, key) == 1);
        Await.until("the deliveries complete", ASYNC, () -> pendingPublications(sent.id()) == 0);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification.user_notification WHERE recipient_id = ? AND dedupe_key = ?",
                Long.class, bystander, key)).as("a warehouse user without the permission").isZero();
    }

    @Test
    @DisplayName("17 §4.3 step 13: cancelled after it was sent - the producer is told to stop, and its links die")
    void cancelledAfterSent() {
        ProductionOrder sent = approved(producer);
        Email first = awaitMail(sent.code());
        Link slip = link(first.text(), "/slip");
        awaitRecorded(sent.id());

        ProductionOrder cancelled = productionOrders.cancel(sent.id(), reread(sent.id()).version(),
                "Khách sửa file thiết kế", coordinator);

        Email stop = awaitMail("ĐÃ HUỶ");
        assertThat(stop.subject()).contains(cancelled.code());
        assertThat(stop.text()).contains("Lý do: Khách sửa file thiết kế").doesNotContain("signature=");
        assertThat(stop.idempotencyKey()).isEqualTo("production-order:" + sent.id() + ":stopped");
        assertThat(mailTo(producerEmail)).hasSize(2);
        assertThatThrownBy(() -> dispatch.openSlip(sent.id(), slip.expires(), slip.signature()))
                .as("a stopped production order's files are no longer handed out")
                .extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_LINK_EXPIRED);
        Await.until("the deliveries complete", ASYNC, () -> pendingPublications(sent.id()) == 0);
        assertThat(dispatch.dispatch(sent.id(), 0, 20).messages().items())
                .extracting(attempt -> attempt.templateCode() + ":" + attempt.status())
                .containsExactlyInAnyOrder("production_order_sent:SENT", "production_order_stopped:SENT");
    }

    @Test
    @DisplayName("17 §4.3 step 13: a production order cancelled before it was sent tells no producer anything")
    void cancelledBeforeSent() {
        ProductionOrder pending = pending(producer);

        productionOrders.cancel(pending.id(), pending.version(), "Hàng đã có", coordinator);

        assertThat(publications(com.stockflow.contracts.ProductionOrderStopped.class, pending.id())).isZero();
        assertThat(publications(ProductionOrderSent.class, pending.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.production_order_slip WHERE production_order_id = ?",
                Long.class, pending.id())).isZero();
    }

    @Test
    @DisplayName("17 §4.3 step 12: closed after mass printing started - the producer is told to deliver nothing more")
    void closedAfterMassPrinting() {
        ProductionOrder sent = approved(producer);
        awaitMail(sent.code());
        awaitRecorded(sent.id());
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE ordering.production_order SET status = 'ACCEPTED', version = version + 1 WHERE id = ?",
                    sent.id());
            jdbc.update("""
                    UPDATE ordering.production_order SET status = 'IN_PRODUCTION', production_started_at = NOW(),
                           version = version + 1 WHERE id = ?""", sent.id());
        });

        productionOrders.close(sent.id(), reread(sent.id()).version(), "Xưởng dừng hẳn", coordinator);

        Email stop = awaitMail("ĐÃ ĐÓNG");
        assertThat(stop.text()).contains("đã được đóng", "Lý do: Xưởng dừng hẳn");
    }

    @Test
    @DisplayName("B31: the customer approved the sample - the producer is told to mass print, with fresh links")
    void sampleApproved() {
        ProductionOrder sent = approved(producer);
        awaitMail(sent.code());
        assertThat(sent.sampleApprovalRequired()).as("BR-24: the SKU was never produced").isTrue();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> dispatch.sampleApproved(reread(sent.id()),
                Instant.now()))).as("not in production yet")
                .extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_STATUS_INVALID);
        tx.executeWithoutResult(s -> {
            jdbc.update("UPDATE ordering.production_order SET status = 'ACCEPTED', version = version + 1 WHERE id = ?",
                    sent.id());
            jdbc.update("UPDATE ordering.production_order SET status = 'SAMPLE_PENDING', version = version + 1 "
                    + "WHERE id = ?", sent.id());
            jdbc.update("""
                    UPDATE ordering.production_order SET status = 'IN_PRODUCTION', production_started_at = NOW(),
                           version = version + 1 WHERE id = ?""", sent.id());
        });

        tx.executeWithoutResult(s -> dispatch.sampleApproved(reread(sent.id()), Instant.now()));

        Email go = awaitMail("mẫu in đã duyệt");
        assertThat(go.text()).contains("bắt đầu in hàng loạt", "/print-files/" + sku.snapshotId() + "?expires=");
        Link print = link(go.text(), "/print-files/" + sku.snapshotId());
        handedOut(() -> dispatch.openPrintFile(sent.id(), sku.snapshotId(), print.expires(), print.signature()));
    }

    @Test
    @DisplayName("staff re-send the order: a new generation, a key of its own, fresh links")
    void resend() {
        ProductionOrder sent = approved(producer);
        Email first = awaitMail(sent.code());
        awaitRecorded(sent.id());
        Await.until("the first deliveries complete", ASYNC, () -> pendingPublications(sent.id()) == 0);

        var resend = dispatch.resend(sent.id());

        assertThat(resend.message()).isEqualTo("SENT");
        assertThat(resend.generation()).isEqualTo(1);
        Await.until("the re-sent e-mail", ASYNC, () -> mailTo(producerEmail).size() == 2);
        Email again = mailTo(producerEmail).get(1);
        assertThat(again.idempotencyKey()).isEqualTo("production-order:" + sent.id() + ":sent:1");
        // Signed afresh from the request: they expire no earlier (the same second when both fall in it).
        assertThat(link(again.text(), "/slip").expires()).isGreaterThanOrEqualTo(link(first.text(), "/slip").expires());
        Await.until("the re-send is recorded", ASYNC, () -> deliveries(sent.id(), "sent").stream()
                .filter(row -> "SENT".equals(row.get("status"))).count() == 2);
        assertThat(deliveries(sent.id(), "sent")).extracting(row -> row.get("external_reference"))
                .contains("production-order:" + sent.id() + ":sent", "production-order:" + sent.id() + ":sent:1");
        assertThat(dispatch.dispatch(sent.id(), 0, 20).messages().items())
                .anySatisfy(attempt -> assertThat(attempt.generation()).isEqualTo(1));

        Producer handed = producers.register(producerCode(), new Producer.Details("Xưởng nhận phiếu tay", null, null,
                null, Producer.Channel.PDF, 14, null));
        ProductionOrder byHand = approved(handed);
        assertThatThrownBy(() -> dispatch.resend(byHand.id())).as("nothing is e-mailed to a producer handed the slip")
                .extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_RESEND_NOT_ALLOWED);
        ProductionOrder pending = pending(producer);
        assertThatThrownBy(() -> dispatch.resend(pending.id())).as("not sent yet")
                .extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_RESEND_NOT_ALLOWED);
    }

    @Test
    @DisplayName("a producer the coordinator hands the slip: no e-mail, the message recorded as skipped")
    void handedTheSlip() {
        Producer handed = producers.register(producerCode(), new Producer.Details("Xưởng nhận phiếu tay", null, null,
                null, Producer.Channel.PDF, 14, null));
        ProductionOrder sent = approved(handed);

        Await.until("the message is settled", ASYNC, () -> !deliveries(sent.id(), "sent").isEmpty());
        assertThat(deliveries(sent.id(), "sent")).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("status", "SKIPPED").containsEntry("error", "PRODUCER_CHANNEL_PDF")
                .containsEntry("terminal", true));
        assertThat(slipKey(sent.id())).as("the slip is kept for the coordinator to hand over").isNotNull();
        assertThat(dispatch.dispatch(sent.id(), 0, 20).channel()).isEqualTo("PDF");
        Await.until("the warehouse manager is told all the same", ASYNC, () -> jdbc.queryForObject("""
                SELECT count(*) FROM notification.user_notification WHERE recipient_id = ? AND dedupe_key = ?""",
                Long.class, warehouseManager, "production-order-sent:" + sent.id()) == 1);
    }

    @Test
    @DisplayName("a slip drawn for an approval that does not commit is deleted from the store")
    void unadoptedSlipIsDeleted() throws Exception {
        ProductionOrder pending = pending(producer);
        String key;
        try (ProductionOrderDispatch.PreparedSlip slip = dispatch.prepareSlip(reread(pending.id()), approver,
                Instant.now())) {
            key = slip.storageKey();
            assertThat(storage.exists(key)).isTrue();
            try (PDDocument doc = Loader.loadPDF(bytes(key))) {
                assertThat(new PDFTextStripper().getText(doc)).contains(pending.code());
            }
        }
        assertThat(storage.exists(key)).as("never adopted by a committed approval").isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.production_order_slip WHERE production_order_id = ?",
                Long.class, pending.id())).isZero();
    }

    @Test
    @DisplayName("a stale approval is refused before any slip is drawn, and sends nothing")
    void failedApprovalSendsNothing() {
        ProductionOrder pending = pending(producer);

        assertThatThrownBy(() -> productionOrders.approve(pending.id(), pending.version() - 1, approver))
                .extracting(ProductionOrderDispatchIntegrationTest::code).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);

        assertThat(reread(pending.id()).status()).isEqualTo(ProductionOrderStatus.PENDING_APPROVAL);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordering.production_order_slip WHERE production_order_id = ?",
                Long.class, pending.id())).isZero();
        assertThat(publications(ProductionOrderSent.class, pending.id())).isZero();
        assertThatThrownBy(() -> dispatch.slipLink(pending.id()))
                .extracting(ProductionOrderDispatchIntegrationTest::code)
                .isEqualTo(ErrorCode.PRODUCTION_ORDER_SLIP_NOT_FOUND);
    }
}
