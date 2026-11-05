package com.stockflow.procurement;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.IncomingFiles;
import com.stockflow.procurement.internal.domain.PurchaseOrderAttachmentCategory;
import com.stockflow.procurement.internal.service.PurchaseOrderAttachmentService;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import com.stockflow.support.TestPdfs;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Documents kept with a purchase order, through the real storage path and schema. */
@IntegrationTest
@Import(PostgresContainer.class)
@TestPropertySource(properties = "stockflow.upload-inspection.enabled=false")
class PurchaseOrderAttachmentIntegrationTest {

    @Autowired PurchaseOrderAttachmentService attachments;
    // The test profile stores files locally, which signs no browser link; the link only has to look like S3's here.
    @MockitoSpyBean FileStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;

    private UUID buyer;
    private UUID order;

    @BeforeEach
    void setUp() {
        doAnswer(call -> "http://objects.test/" + call.getArgument(0)).when(storage)
                .presignedDownloadUrl(anyString(), any(), any());
        buyer = tx.execute(s -> ReferenceRows.user(entityManager));
        order = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO procurement.purchase_orders (id, po_number, status, supplier_id, warehouse_id, currency,
                    order_date, version, created_at)
                SELECT ?, ?, 'DRAFT', s.id, w.id, 'VND', CURRENT_DATE, 0, NOW()
                  FROM procurement.suppliers s, warehouse.warehouse w WHERE s.code = 'GOVIET' AND w.prefix = 'HCM'""",
                order, "PO-ATT-" + order.toString().substring(24)));
    }

    private static FileUpload pdf(String name, byte[] bytes) {
        return new FileUpload(name, "application/pdf", bytes.length, new ByteArrayInputStream(bytes));
    }

    private static int code(Runnable action) {
        Throwable failure = catchThrowable(action::run);
        assertThat(failure).isInstanceOf(BusinessException.class);
        return ((BusinessException) failure).errorCode().ordinal();
    }

    @Test
    @DisplayName("a quotation and a photographed delivery note are kept with the order, listed and linked by name")
    void documentsAreKeptListedAndLinked() throws Exception {
        var quotation = attachments.attach(order, PurchaseOrderAttachmentCategory.QUOTATION,
                IncomingFiles.sent(List.of(pdf("Báo giá Gỗ Việt.pdf", TestPdfs.blank()))), buyer);
        var photo = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "jpeg", photo);
        byte[] jpeg = photo.toByteArray();
        attachments.attach(order, PurchaseOrderAttachmentCategory.DELIVERY_NOTE, IncomingFiles.sent(List.of(
                new FileUpload("phieu-giao.jpg", "image/jpeg", jpeg.length, new ByteArrayInputStream(jpeg)))), buyer);

        assertThat(attachments.list(order)).extracting(PurchaseOrderAttachmentService.AttachmentView::category)
                .containsExactly(PurchaseOrderAttachmentCategory.QUOTATION, PurchaseOrderAttachmentCategory.DELIVERY_NOTE);
        assertThat(quotation.fileName()).isEqualTo("Báo giá Gỗ Việt.pdf");
        assertThat(quotation.fileType()).isEqualTo("pdf");
        assertThat(quotation.uploadedAt()).isNotNull();
        String key = jdbc.queryForObject("SELECT file_url FROM procurement.purchase_order_attachments WHERE id = ?",
                String.class, quotation.id());
        assertThat(key).startsWith("documents/").endsWith(".pdf");
        assertThat(storage.exists(key)).isTrue();
        assertThat(attachments.downloadLink(order, quotation.id()).url()).startsWith("http://objects.test/" + key);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform.audit_log WHERE resource_type = 'purchase-order-attachment'
                   AND action = 'EXPORT' AND resource_id = ?""", Integer.class, quotation.id().toString())).isOne();
    }

    @Test
    @DisplayName("an unknown order or a PDF that runs code keeps nothing")
    void refusalsKeepNothing() {
        clearInvocations(storage);
        assertThat(code(() -> attachments.attach(UUID.randomUUID(), PurchaseOrderAttachmentCategory.CONTRACT,
                IncomingFiles.sent(List.of(pdf("c.pdf", TestPdfs.blank()))), buyer)))
                .isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_FOUND.ordinal());
        verify(storage, never()).store(any(), any(), any(), anyLong(), any(), any());

        byte[] scripted = scriptedPdf();
        assertThat(code(() -> attachments.attach(order, PurchaseOrderAttachmentCategory.OTHER,
                IncomingFiles.sent(List.of(pdf("s.pdf", scripted))), buyer)))
                .isEqualTo(ErrorCode.VALIDATION_FAILED.ordinal());
        verify(storage, never()).store(any(), any(), any(), anyLong(), any(), any());
        assertThat(attachments.list(order)).isEmpty();
        assertThat(code(() -> attachments.downloadLink(order, UUID.randomUUID())))
                .isEqualTo(ErrorCode.NOT_FOUND.ordinal());
    }

    private static byte[] scriptedPdf() {
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument(); var out = new ByteArrayOutputStream()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            document.getDocumentCatalog().setOpenAction(
                    new org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript("app.alert(1)"));
            document.save(out);
            return out.toByteArray();
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }
}
