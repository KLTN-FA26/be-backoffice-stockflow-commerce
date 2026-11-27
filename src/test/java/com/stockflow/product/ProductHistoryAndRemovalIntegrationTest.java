package com.stockflow.product;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StorageException;
import com.stockflow.common.storage.UploadInspection;
import com.stockflow.contracts.CatalogListingChanged;
import com.stockflow.contracts.ProductDiscontinued;
import com.stockflow.inventory.internal.service.InventoryItems;
import com.stockflow.product.api.ListProductsQuery;
import com.stockflow.product.api.ProductChange;
import com.stockflow.product.api.ProductDiscontinuation;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.ProductSummary;
import com.stockflow.product.api.Purchasability;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.api.UpdateProductCommand;
import com.stockflow.product.internal.domain.VariantStatus;
import com.stockflow.product.internal.service.ProductMediaService;
import com.stockflow.product.internal.service.ProductTaxonomyService;
import com.stockflow.product.internal.service.ProductVariantService;
import com.stockflow.product.internal.service.ProductVariantService.NewVariant;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ProductFixtures;
import com.stockflow.support.RecordedEvents;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * SCRUM-461 at the service level, against Postgres: the product's history ({@code product_change_log}) written in
 * the transaction of each change — the status moves with from, to and reason, and what happens to its variants,
 * images, logistics and stock policy — discontinuing making every variant OBSOLETE, what a suspended product still
 * allows, which SKUs a purchase order line may take, the events other modules listen to, and deleting a draft with
 * its variants, its inventory items and its stored image files.
 */
@IntegrationTest
@Import({PostgresContainer.class, RecordedEvents.class})
@TestPropertySource(properties = "stockflow.product-media.cloud-front-base-url=https://cdn.example.test")
class ProductHistoryAndRemovalIntegrationTest {

    @Autowired ProductService products;
    @Autowired ProductVariantService variants;
    @Autowired ProductMediaService media;
    @Autowired ProductTaxonomyService taxonomy;
    @Autowired InventoryItems items;
    @Autowired com.stockflow.product.internal.service.CustomerSkuLifecycleService customerSkuLifecycle;
    @Autowired FileStorage storage;
    @Autowired RecordedEvents.Recorder recorded;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired TransactionTemplate tx;
    @MockitoBean UploadInspection inspection;
    @MockitoBean CurrentUserProvider users;

    private UUID editor;
    private UUID approver;
    private String suffix;
    private ProductFixtures.Taxonomy tax;

    @BeforeEach
    void setUp() {
        editor = tx.execute(s -> ReferenceRows.user(entityManager));
        approver = tx.execute(s -> ReferenceRows.user(entityManager));
        suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        tax = ProductFixtures.taxonomy(taxonomy, suffix);
        recorded.clear();
        actAs(editor);
    }

    private void actAs(UUID user) {
        when(users.current()).thenReturn(Optional.of(new CurrentUser(user, "test-" + user, Set.of(), Set.of(),
                DataScope.ALL, Set.of())));
    }

    private ProductSummary draft() {
        return ProductFixtures.draft(products, tax, suffix);
    }

    private ProductSummary approved() {
        ProductSummary created = draft();
        products.submit(created.productId(), editor);
        actAs(approver);
        ProductSummary approved = products.approve(created.productId(), approver);
        actAs(editor);
        return approved;
    }

    private UUID defaultVariant(UUID productId) {
        return variants.list(productId, 0, 50).items().stream().filter(ProductVariantService.VariantView::defaultVariant)
                .findFirst().orElseThrow().id();
    }

    /** A customer's SKU drawn on one of the product's variants. */
    private record Drawn(UUID id, String sku, UUID itemId) {
    }

    /**
     * A customer's own SKU drawn on {@code variantId}, with the inventory item the order naming it would make: a variant
     * has no item of its own since V20270105000100, so an item's settings are a customer's SKU's.
     */
    private Drawn customerSkuOn(UUID productId, UUID variantId) {
        UUID customer = tx.execute(s -> ReferenceRows.customer(entityManager));
        String variantSku = jdbc.queryForObject("select sku from product.variants where id = ?", String.class, variantId);
        UUID id = Identifiers.newId();
        String sku = variantSku + "-KH-" + id.toString().substring(24, 28).toUpperCase(java.util.Locale.ROOT);
        UUID item = tx.execute(s -> {
            UUID draft = Identifiers.newId();
            UUID snapshot = Identifiers.newId();
            jdbc.update("""
                    INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
                    VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", draft, customer, productId);
            jdbc.update("""
                    INSERT INTO design.design_snapshot (id, draft_id, checksum, artifact_url, confirmed_at, created_at,
                        artifact_manifest)
                    VALUES (?, ?, ?, 'https://example.com/print.pdf', NOW(), NOW(), '[]'::jsonb)""",
                    snapshot, draft, "c".repeat(64));
            jdbc.update("""
                    INSERT INTO product.customer_skus (id, product_id, variant_id, customer_id, design_snapshot_id, sku,
                        name)
                    VALUES (?, ?, ?, ?, ?, ?, 'Ly in logo khach')""", id, productId, variantId, customer,
                    snapshot, sku);
            return com.stockflow.support.CartonRows.item(jdbc, sku);
        });
        return new Drawn(id, sku, item);
    }

    private List<ProductChange> history(UUID productId) {
        return products.history(productId, 0, 100).items();
    }

    private static List<String> types(List<ProductChange> history) {
        return history.stream().map(ProductChange::changeType).toList();
    }

    private static FileUpload png() throws Exception {
        var image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        image.setRGB(10, 10, 0xff0000);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        byte[] bytes = out.toByteArray();
        return new FileUpload("cup.png", "image/png", bytes.length, new ByteArrayInputStream(bytes));
    }

    /** Whether the object store still holds {@code key}. */
    private boolean stored(String key) {
        try (var content = storage.download(key)) {
            return content != null;
        } catch (StorageException | java.io.IOException gone) {
            return false;
        }
    }

    private static BusinessException refusal(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return (BusinessException) thrown;
    }

    // ------------------------------------------------------------------ history

    @Test
    @DisplayName("every change is in the history in the order it happened: status moves, variants, images")
    void historyRecordsEveryKindOfChange() throws Exception {
        ProductSummary created = draft();
        UUID id = created.productId();
        UUID defaultId = defaultVariant(id);

        ProductSummary renamed = products.update(new UpdateProductCommand(id, created.version(), "Ly giấy mới", "Fixture " + suffix,
                tax.brandId(), tax.categoryId(), "Mô tả ngắn", null, null, TaxClass.STANDARD));
        // Nothing changed, nothing recorded.
        products.update(new UpdateProductCommand(id, renamed.version(), "Ly giấy mới", "Fixture " + suffix, tax.brandId(),
                tax.categoryId(), "Mô tả ngắn", null, null, TaxClass.STANDARD));

        var extra = variants.add(id, new NewVariant("FX-" + suffix + "-16", "16oz", null, null));
        variants.update(id, extra.id(), new NewVariant(extra.sku(), "16oz renamed", null, null));

        var image = media.upload(id, defaultId, png(), editor, null);
        media.describe(id, defaultId, image.id(), "Mặt trước", 3, false);
        // While the image exists its entries name the file and the SKU of the variant it belongs to (the default
        // variant's SKU is the product code).
        assertThat(history(id).get(0)).satisfies(c -> {
            assertThat(c.subjectSku()).isEqualTo(created.code());
            assertThat(c.subjectLabel()).isEqualTo("cup.png");
        });

        // Logistics and stock policy are edited on the inventory item and leave no trace in the product's history.

        products.submit(id, editor);
        actAs(approver);
        products.approve(id, approver);
        media.publish(id, defaultId, List.of(image.id()), approver);
        actAs(editor);
        media.withdraw(id, defaultId, image.id());
        variants.block(id, extra.id());
        variants.activate(id, extra.id());
        media.delete(id, defaultId, image.id());
        ProductSummary approved = products.findById(id).orElseThrow();
        ProductSummary suspended = products.suspend(id, "Nhà cung cấp thu hồi", approved.version());
        ProductSummary resumed = products.resume(id, suspended.version());
        products.discontinue(id, "Hết dòng sản phẩm", resumed.version());

        List<ProductChange> log = history(id);
        // Newest first; the product's own moves and what hangs off it, nothing from a failed or empty call.
        assertThat(types(log)).containsExactly(
                "DISCONTINUED", "VARIANT_STATUS", "VARIANT_STATUS", "RESUMED", "SUSPENDED",
                "MEDIA", "VARIANT_STATUS", "VARIANT_STATUS", "MEDIA", "MEDIA",
                "APPROVED", "VARIANT_STATUS", "VARIANT_STATUS", "SUBMITTED",
                "MEDIA", "MEDIA", "VARIANT_UPDATED", "VARIANT_ADDED", "UPDATED", "CREATED");
        assertThat(log).extracting(ProductChange::subjectType).containsExactly(
                "PRODUCT", "VARIANT", "VARIANT", "PRODUCT", "PRODUCT",
                "MEDIA", "VARIANT", "VARIANT", "MEDIA", "MEDIA",
                "PRODUCT", "VARIANT", "VARIANT", "PRODUCT",
                "MEDIA", "MEDIA", "VARIANT", "VARIANT", "PRODUCT", "PRODUCT");

        ProductChange discontinued = log.get(0);
        assertThat(discontinued.fromStatus()).isEqualTo("APPROVED");
        assertThat(discontinued.toStatus()).isEqualTo("DISCONTINUED");
        assertThat(discontinued.reason()).isEqualTo("Hết dòng sản phẩm");
        assertThat(discontinued.actorId()).isEqualTo(editor);
        assertThat(discontinued.actorName()).isEqualTo("test-" + editor);
        // Every variant, the default one included, went to OBSOLETE with the product's reason; the history says which.
        assertThat(log.subList(1, 3)).allSatisfy(change -> {
            assertThat(change.fromStatus()).isEqualTo("ACTIVE");
            assertThat(change.toStatus()).isEqualTo("OBSOLETE");
            assertThat(change.reason()).isEqualTo("Hết dòng sản phẩm");
        });
        assertThat(log.subList(1, 3)).extracting(ProductChange::subjectId).containsExactlyInAnyOrder(defaultId, extra.id());

        assertThat(log.get(3).fromStatus()).isEqualTo("INACTIVE");
        assertThat(log.get(3).toStatus()).isEqualTo("APPROVED");
        assertThat(log.get(4).fromStatus()).isEqualTo("APPROVED");
        assertThat(log.get(4).toStatus()).isEqualTo("INACTIVE");
        assertThat(log.get(4).reason()).isEqualTo("Nhà cung cấp thu hồi");

        // Images: deleted, then (older) the withdrawal after publication, the publication, the description, the upload.
        assertThat(log.get(5).changedFields()).containsExactly("deleted");
        assertThat(log.get(5).subjectId()).isEqualTo(image.id());
        assertThat(log.get(6)).satisfies(c -> {
            assertThat(c.subjectId()).isEqualTo(extra.id());
            assertThat(c.fromStatus()).isEqualTo("BLOCKED");
            assertThat(c.toStatus()).isEqualTo("ACTIVE");
        });
        assertThat(log.get(7)).satisfies(c -> {
            assertThat(c.fromStatus()).isEqualTo("ACTIVE");
            assertThat(c.toStatus()).isEqualTo("BLOCKED");
        });
        assertThat(log.get(8).changedFields()).containsExactly("withdrawn");
        assertThat(log.get(9).changedFields()).containsExactly("published");
        assertThat(log.get(9).actorId()).isEqualTo(approver);

        ProductChange approvedEntry = log.get(10);
        assertThat(approvedEntry.fromStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(approvedEntry.toStatus()).isEqualTo("APPROVED");
        assertThat(approvedEntry.actorId()).isEqualTo(approver);
        // Approval put the drafted variants on sale: each move is there, DRAFT to ACTIVE.
        assertThat(log.subList(11, 13)).allSatisfy(c -> {
            assertThat(c.fromStatus()).isEqualTo("DRAFT");
            assertThat(c.toStatus()).isEqualTo("ACTIVE");
        });
        assertThat(log.get(13).fromStatus()).isEqualTo("DRAFT");
        assertThat(log.get(13).toStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(log.get(13).actorId()).isEqualTo(editor);

        // D1: logistics and stock policy live on the inventory item, so no LOGISTICS / INVENTORY_CONTROL rows appear here.
        assertThat(log.get(14).changedFields()).containsExactlyInAnyOrder("altText", "sortOrder");
        assertThat(log.get(14).subjectId()).isEqualTo(image.id());
        assertThat(log.get(15).changedFields()).containsExactly("uploaded");
        assertThat(log.get(16).subjectId()).isEqualTo(extra.id());
        assertThat(log.get(16).changedFields()).containsExactly("name");
        assertThat(log.get(17).subjectId()).isEqualTo(extra.id());
        assertThat(log.get(17).toStatus()).isEqualTo("DRAFT");
        assertThat(log.get(18).changedFields()).containsExactlyInAnyOrder("name", "shortDescription");
        assertThat(log.get(19).toStatus()).isEqualTo("DRAFT");
        assertThat(log.get(19).fromStatus()).isNull();
        assertThat(log).extracting(ProductChange::occurredAt).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        // The subject's SKU and name are read with the page: today's names, none for the product itself or for an
        // image deleted since.
        assertThat(log.get(6).subjectSku()).isEqualTo(extra.sku());
        assertThat(log.get(16).subjectSku()).isEqualTo(extra.sku());
        assertThat(log.get(17).subjectLabel()).isEqualTo("16oz renamed");
        assertThat(discontinued.subjectSku()).isNull();
        assertThat(discontinued.subjectLabel()).isNull();
        assertThat(log.get(15).subjectSku()).isNull();
        assertThat(log.get(15).subjectLabel()).isNull();
    }

    @Test
    @DisplayName("the history is paged, newest first, and a rejection keeps its reason")
    void historyPagesAndRejections() {
        ProductSummary created = draft();
        UUID id = created.productId();
        products.submit(id, editor);
        actAs(approver);
        products.reject(id, approver, "Missing dimensions");
        actAs(editor);
        products.submit(id, editor);

        var first = products.history(id, 0, 2);
        assertThat(first.totalElements()).isEqualTo(4);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(types(first.items())).containsExactly("SUBMITTED", "REJECTED");
        assertThat(first.items().get(1).reason()).isEqualTo("Missing dimensions");
        assertThat(first.items().get(1).fromStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(first.items().get(1).toStatus()).isEqualTo("DRAFT");
        assertThat(first.items().get(1).actorId()).isEqualTo(approver);
        assertThat(types(products.history(id, 1, 2).items())).containsExactly("SUBMITTED", "CREATED");
        assertThat(products.history(id, 5, 2).items()).isEmpty();
        assertThat(refusal(org.assertj.core.api.Assertions.catchThrowable(() -> products.history(UUID.randomUUID(), 0, 10))).errorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("a change that fails leaves no history: the entry is written in the transaction of the change")
    void aRefusedChangeLeavesNoEntry() {
        ProductSummary approved = approved();
        UUID id = approved.productId();
        int before = history(id).size();

        assertThatThrownBy(() -> products.discontinue(id, "  ", approved.version())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> products.suspend(id, "recall", approved.version() + 9)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> variants.obsolete(id, defaultVariant(id))).isInstanceOf(BusinessException.class);

        assertThat(history(id)).hasSize(before);
        assertThat(products.findById(id).orElseThrow().status()).isEqualTo(ProductStatus.APPROVED);
        assertThat(variants.list(id, 0, 10).items()).extracting(ProductVariantService.VariantView::status)
                .containsOnly(VariantStatus.ACTIVE);
    }

    @Test
    @DisplayName("the history table is append-only for the application too: UPDATE is refused, DELETE only with the product")
    void historyIsAppendOnly() {
        ProductSummary created = draft();
        UUID id = created.productId();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE product.product_change_log SET reason = 'edited' WHERE product_id = ?", id)))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "DELETE FROM product.product_change_log WHERE product_id = ?", id)))
                .hasMessageContaining("append-only");
        assertThat(history(id)).hasSize(1);
    }

    // ------------------------------------------------------------------ discontinue

    @Test
    @DisplayName("discontinuing makes every variant OBSOLETE, the default one too, whatever it was; the reason is on the product")
    void discontinueMakesEveryVariantObsolete() {
        ProductSummary created = draft();
        UUID id = created.productId();
        variants.add(id, new NewVariant("FX-" + suffix + "-A", "A", null, null));
        var blocked = variants.add(id, new NewVariant("FX-" + suffix + "-B", "B", null, null));
        variants.add(id, new NewVariant("FX-" + suffix + "-C", "C", null, null));
        products.submit(id, editor);
        actAs(approver);
        products.approve(id, approver);
        actAs(editor);
        variants.block(id, blocked.id());
        // A variant added after approval stays DRAFT until someone puts it on sale.
        var late = variants.add(id, new NewVariant("FX-" + suffix + "-D", "D", null, null));
        assertThat(late.status()).isEqualTo(VariantStatus.DRAFT);

        ProductSummary current = products.findById(id).orElseThrow();
        ProductDiscontinuation result = products.discontinue(id, "  Ngừng sản xuất  ", current.version());

        assertThat(result.product().status()).isEqualTo(ProductStatus.DISCONTINUED);
        assertThat(result.product().discontinueReason()).isEqualTo("Ngừng sản xuất");
        assertThat(result.product().discontinuedAt()).isNotNull();
        assertThat(result.warnings()).isEmpty();
        assertThat(variants.list(id, 0, 50).items()).hasSize(5)
                .allSatisfy(v -> {
                    assertThat(v.status()).isEqualTo(VariantStatus.OBSOLETE);
                    assertThat(v.obsoletedAt()).isNotNull();
                });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.variants WHERE product_id = ? AND is_default AND status = 'OBSOLETE'",
                Long.class, id)).isOne();
        // The old rule is unchanged for one SKU of a product that goes on: the default variant cannot be made obsolete alone.
        ProductSummary other = ProductFixtures.approved(products, tax, suffix + "X", editor, approver);
        assertThat(refusal(org.assertj.core.api.Assertions.catchThrowable(() -> variants.obsolete(other.productId(),
                defaultVariant(other.productId())))).errorCode()).isEqualTo(ErrorCode.INVALID_VARIANT_TRANSITION);
    }

    @Test
    @DisplayName("events: discontinuing publishes ProductDiscontinued (no free text); suspending and resuming wake the catalog")
    void eventsOtherModulesListenTo() {
        ProductSummary approved = approved();
        UUID id = approved.productId();
        recorded.clear();

        ProductSummary suspended = products.suspend(id, "recall", approved.version());
        ProductSummary resumed = products.resume(id, suspended.version());
        products.discontinue(id, "end of line", resumed.version());

        assertThat(recorded.ofType(CatalogListingChanged.class)).extracting(CatalogListingChanged::productId)
                .containsExactly(id, id);
        assertThat(recorded.ofType(ProductDiscontinued.class)).singleElement().satisfies(e -> {
            assertThat(e.productId()).isEqualTo(id);
            assertThat(e.discontinuedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("a logistics row written before the split is still readable in the history")
    void aLogisticsRowWrittenBeforeTheSplitIsStillReadable() {
        UUID id = draft().productId();
        UUID variant = defaultVariant(id);
        tx.executeWithoutResult(s -> jdbc.update("insert into product.product_change_log"
                + "(id, product_id, change_type, subject_type, subject_id, changed_fields)"
                + " values (?, ?, 'LOGISTICS', 'LOGISTICS', ?, '{weightKg}')", Identifiers.newId(), id, variant));

        assertThat(history(id)).anySatisfy(change -> {
            assertThat(change.changeType()).isEqualTo("LOGISTICS");
            assertThat(change.subjectType()).isEqualTo("LOGISTICS");
            assertThat(change.changedFields()).containsExactly("weightKg");
        });
    }

    // ------------------------------------------------------------------ suspended products

    @Test
    @DisplayName("a suspended product is still maintained (variants, images, logistics), listed as INACTIVE, never edited or put on sale")
    void suspendedProductIsStillMaintained() throws Exception {
        ProductSummary approved = approved();
        UUID id = approved.productId();
        UUID defaultId = defaultVariant(id);
        ProductSummary suspended = products.suspend(id, "recall", approved.version());
        assertThat(suspended.status()).isEqualTo(ProductStatus.INACTIVE);
        assertThat(suspended.suspendedAt()).isNotNull();
        assertThat(suspended.suspendReason()).isEqualTo("recall");

        // Maintenance goes on.
        var added = variants.add(id, new NewVariant("FX-" + suffix + "-S", "S", null, null));
        assertThat(added.status()).isEqualTo(VariantStatus.DRAFT);
        var image = media.upload(id, defaultId, png(), editor, null);
        assertThat(media.describe(id, defaultId, image.id(), "Alt", 1, false).altText()).isEqualTo("Alt");
        var item = items.get(customerSkuOn(id, defaultId).itemId());
        assertThat(items.describe(item.id(), item.version(), item.logistics()).version()).isEqualTo(item.version() + 1);
        assertThat(variants.block(id, defaultId).status()).isEqualTo(VariantStatus.BLOCKED);
    }

    @Test
    @DisplayName("a suspended product cannot be edited, have a variant activated or an image published")
    void suspendedProductRefusals() throws Exception {
        ProductSummary approved = approved();
        UUID id = approved.productId();
        UUID defaultId = defaultVariant(id);
        var image = media.upload(id, defaultId, png(), editor, null);
        ProductSummary suspended = products.suspend(id, "recall", approved.version());

        assertThat(refusal(org.assertj.core.api.Assertions.catchThrowable(() -> products.update(new UpdateProductCommand(id,
                suspended.version(), "x", "x", tax.brandId(), tax.categoryId(), null, null, null, TaxClass.STANDARD))))
                .errorCode()).isEqualTo(ErrorCode.INVALID_PRODUCT_STATUS_TRANSITION);
        var draftVariant = variants.add(id, new NewVariant("FX-" + suffix + "-S", "S", null, null));
        assertThat(refusal(org.assertj.core.api.Assertions.catchThrowable(() -> variants.activate(id, draftVariant.id()))).errorCode())
                .isEqualTo(ErrorCode.INVALID_VARIANT_TRANSITION);
        actAs(approver);
        assertThat(refusal(org.assertj.core.api.Assertions.catchThrowable(() -> media.publish(id, defaultId, List.of(image.id()),
                approver))).errorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_APPROVED);
    }

    @Test
    @DisplayName("the product list filters by INACTIVE like any other status")
    void listFiltersByInactive() {
        ProductSummary approved = approved();
        ProductSummary other = ProductFixtures.approved(products, tax, suffix + "Y", editor, approver);
        products.suspend(approved.productId(), "recall", approved.version());

        var inactive = products.list(new ListProductsQuery(0, 50, null, List.of(ProductStatus.INACTIVE), tax.categoryId(), tax.brandId(), null));
        var sellable = products.list(new ListProductsQuery(0, 50, null, List.of(ProductStatus.APPROVED), tax.categoryId(), tax.brandId(), null));

        assertThat(inactive.items()).extracting(ProductSummary::productId).containsExactly(approved.productId());
        assertThat(sellable.items()).extracting(ProductSummary::productId).containsExactly(other.productId());
    }

    // ------------------------------------------------------------------ purchasability

    @Test
    @DisplayName("purchasability: only APPROVED or PUBLISHED with an ACTIVE variant; every other state names why")
    void purchasabilityOfEverySku() {
        ProductSummary drafted = ProductFixtures.draft(products, tax, suffix + "D");
        ProductSummary pending = ProductFixtures.draft(products, tax, suffix + "P");
        products.submit(pending.productId(), editor);
        ProductSummary active = ProductFixtures.approved(products, tax, suffix + "A", editor, approver);
        ProductSummary blockedVariant = ProductFixtures.approved(products, tax, suffix + "B", editor, approver);
        variants.block(blockedVariant.productId(), defaultVariant(blockedVariant.productId()));
        ProductSummary suspended = ProductFixtures.approved(products, tax, suffix + "S", editor, approver);
        products.suspend(suspended.productId(), "recall", suspended.version());
        ProductSummary discontinued = ProductFixtures.approved(products, tax, suffix + "X", editor, approver);
        products.discontinue(discontinued.productId(), "end", discontinued.version());

        Map<String, Purchasability> verdicts = products.purchasability(List.of(drafted.code(), pending.code(), active.code(),
                blockedVariant.code(), suspended.code(), discontinued.code(), DemoData.BLANK_CUP,
                " " + active.code().toLowerCase(Locale.ROOT), "NO-SUCH-SKU"));

        assertThat(verdicts).containsOnly(
                Map.entry(drafted.code(), Purchasability.PRODUCT_NOT_APPROVED),
                Map.entry(pending.code(), Purchasability.PRODUCT_NOT_APPROVED),
                Map.entry(active.code(), Purchasability.PURCHASABLE),
                Map.entry(blockedVariant.code(), Purchasability.VARIANT_NOT_ACTIVE),
                Map.entry(suspended.code(), Purchasability.PRODUCT_INACTIVE),
                Map.entry(discontinued.code(), Purchasability.PRODUCT_DISCONTINUED),
                Map.entry(DemoData.BLANK_CUP, Purchasability.PURCHASABLE),   // PUBLISHED demo product
                Map.entry("NO-SUCH-SKU", Purchasability.VARIANT_NOT_ACTIVE));
        assertThat(products.purchasability(List.of())).isEmpty();
        // Resuming makes it purchasable again.
        ProductSummary current = products.findById(suspended.productId()).orElseThrow();
        products.resume(suspended.productId(), current.version());
        assertThat(products.purchasability(List.of(suspended.code()))).containsEntry(suspended.code(), Purchasability.PURCHASABLE);
    }

    @Test
    @DisplayName("purchasability of a customer's own SKU: its product decides first, then its own status (V20270105000100)")
    void purchasabilityOfACustomersSku() {
        ProductSummary active = ProductFixtures.approved(products, tax, suffix + "C", editor, approver);
        Drawn onSale = customerSkuOn(active.productId(), defaultVariant(active.productId()));
        Drawn blocked = customerSkuOn(active.productId(), defaultVariant(active.productId()));
        customerSkuLifecycle.block(blocked.id());
        assertThat(products.purchasability(List.of(onSale.sku(), blocked.sku()))).containsOnly(
                Map.entry(onSale.sku(), Purchasability.PURCHASABLE),
                Map.entry(blocked.sku(), Purchasability.VARIANT_NOT_ACTIVE));
        // The locked read takes the product FOR SHARE through the union of both tables.
        Map<String, Purchasability> locked = tx.execute(s -> products.lockedPurchasability(List.of(onSale.sku())));
        assertThat(locked).containsEntry(onSale.sku(), Purchasability.PURCHASABLE);
        products.suspend(active.productId(), "recall", products.findById(active.productId()).orElseThrow().version());
        assertThat(products.purchasability(List.of(onSale.sku())))
                .containsEntry(onSale.sku(), Purchasability.PRODUCT_INACTIVE);
    }

    // ------------------------------------------------------------------ delete

    @Test
    @DisplayName("deleting a draft removes the product, its variants and images, and the stored files after the commit")
    void deleteDraftRemovesEverythingItOwns() throws Exception {
        ProductSummary created = draft();
        UUID id = created.productId();
        UUID defaultId = defaultVariant(id);
        var extra = variants.add(id, new NewVariant("FX-" + suffix + "-E", "E", null, null));
        var first = media.upload(id, defaultId, png(), editor, null);
        var second = media.upload(id, extra.id(), png(), editor, null);
        List<String> keys = new ArrayList<>();
        for (var image : List.of(first, second)) {
            keys.add(image.original().key());
            image.renditions().forEach(r -> keys.add(r.file().key()));
        }
        assertThat(keys).hasSizeGreaterThanOrEqualTo(4);
        keys.forEach(key -> assertThat(stored(key)).isTrue());
        // A catalogue variant has no inventory item (V20270105000100): nothing in inventory to delete with it.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory.inventory_items WHERE sku IN (?, ?)", Long.class,
                created.code(), extra.sku())).isZero();

        products.deleteDraft(id, created.version());

        assertThat(products.findById(id)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.variants WHERE product_id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.media WHERE id IN (?, ?)", Long.class, first.id(), second.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.product_change_log WHERE product_id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.product_categories WHERE product_id = ?", Long.class, id)).isZero();
        keys.forEach(key -> assertThat(stored(key)).as(key).isFalse());
        // The product's code and SKU can be used again.
        ProductSummary again = products.create(new com.stockflow.product.api.CreateProductCommand(created.code(), "Again", "Again",
                tax.brandId(), tax.categoryId(), null, null, null, TaxClass.STANDARD, null));
        assertThat(again.status()).isEqualTo(ProductStatus.DRAFT);
    }

    @Test
    @DisplayName("a deletion that is refused keeps the stored files: they go only after a commit")
    void refusedDeletionKeepsTheFiles() throws Exception {
        ProductSummary created = draft();
        UUID id = created.productId();
        UUID defaultId = defaultVariant(id);
        var image = media.upload(id, defaultId, png(), editor, null);
        UUID design = Identifiers.newId();
        tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO design.design_draft (id, customer_id, product_id, status, created_at, current_artifacts)
                VALUES (?, ?, ?, 'DRAFT', NOW(), '{}'::jsonb)""", design, DemoData.CUSTOMER_ID, id));

        assertThat(refusal(org.assertj.core.api.Assertions.catchThrowable(() -> products.deleteDraft(id, created.version()))).errorCode())
                .isEqualTo(ErrorCode.PRODUCT_IN_USE);

        assertThat(stored(image.original().key())).isTrue();
        assertThat(products.findById(id)).isPresent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.variants WHERE sku = ?", Long.class, created.code())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product.media WHERE id = ?", Long.class, image.id())).isOne();
        assertThat(types(history(id))).containsExactly("MEDIA", "CREATED");
        // Once the design is gone the same call succeeds.
        tx.executeWithoutResult(s -> jdbc.update("DELETE FROM design.design_draft WHERE id = ?", design));
        products.deleteDraft(id, created.version());
        assertThat(products.findById(id)).isEmpty();
    }

    @Test
    @DisplayName("an order line names a customer's own SKU, never a variant: the database refuses one on a draft's SKU")
    void anOrderLineNeverNamesAVariant() {
        ProductSummary created = draft();
        // Quotes and orders name product.customer_skus since V20270105000100, and a draft product has none: nothing on
        // an order can hold a draft back from being deleted.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> ReferenceRows.orderWithLine(entityManager, created.code(),
                "SHIPPED", DemoData.CUSTOMER_ID, editor, DemoData.WAREHOUSE_HCM)))
                .hasStackTraceContaining("fk_order_line_customer_sku");
        products.deleteDraft(created.productId(), created.version());
        assertThat(products.findById(created.productId())).isEmpty();
    }

    // ------------------------------------------------------------------ SKU-keyed references

    @Test
    @DisplayName("a DRAFT variant is renamed freely: no stock, adjustment or customer price can name it")
    void aDraftVariantIsRenamedFreely() {
        ProductSummary created = draft();
        UUID id = created.productId();
        UUID defaultId = defaultVariant(id);
        // Stock, adjustments and customer prices name a customer's own SKU since V20270105000100 (its inventory item,
        // its foreign keys), never a catalogue variant: nothing can hold a DRAFT variant's code.
        assertThat(variants.update(id, defaultId, new NewVariant(created.code() + "-NEW", null, null, null)).sku())
                .isEqualTo(created.code() + "-NEW");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                INSERT INTO catalog.customer_price (id, customer_id, sku, created_at)
                VALUES (?, 'c0000000-0000-4000-8000-000000000001', ?, NOW())""", Identifiers.newId(),
                created.code() + "-NEW")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an OBSOLETE customer SKU's item stays readable (its stock is still cleared); editing its logistics is VARIANT_OBSOLETE")
    void obsoleteSkuItemIsReadButNotEdited() {
        ProductSummary created = draft();
        UUID id = created.productId();
        var extra = variants.add(id, new NewVariant("FX-" + suffix + "-OB", "Obsolete", null, null));
        products.submit(id, editor);
        actAs(approver);
        products.approve(id, approver);
        actAs(editor);
        // Only a customer's SKU has an item (V20270105000100): one retired on its own, one retired with the product.
        Drawn retired = customerSkuOn(id, extra.id());
        Drawn kept = customerSkuOn(id, defaultVariant(id));
        customerSkuLifecycle.obsolete(retired.id());

        var obsolete = items.get(retired.itemId());
        assertThat(obsolete.sku()).isEqualTo(retired.sku());
        assertThat(refusal(org.assertj.core.api.Assertions.catchThrowable(() -> items.describe(obsolete.id(),
                obsolete.version(), obsolete.logistics()))).errorCode()).isEqualTo(ErrorCode.VARIANT_OBSOLETE);
        assertThat(items.get(obsolete.id()).version()).as("nothing was written").isEqualTo(obsolete.version());
        var stillOn = items.get(kept.itemId());
        assertThat(items.describe(stillOn.id(), stillOn.version(), stillOn.logistics()).version())
                .isEqualTo(stillOn.version() + 1);

        // Discontinuing retires every customer's SKU of the product with its variants; the reads still answer, the
        // edits are refused.
        products.discontinue(id, "Ngừng", products.findById(id).orElseThrow().version());
        var defaultItem = items.get(kept.itemId());
        assertThat(defaultItem.sku()).isEqualTo(kept.sku());
        assertThat(refusal(org.assertj.core.api.Assertions.catchThrowable(() -> items.describe(defaultItem.id(),
                defaultItem.version(), defaultItem.logistics()))).errorCode()).isEqualTo(ErrorCode.VARIANT_OBSOLETE);
        // An item nobody has is still INVENTORY_ITEM_NOT_FOUND, not VARIANT_OBSOLETE.
        assertThat(refusal(org.assertj.core.api.Assertions.catchThrowable(() -> items.get(UUID.randomUUID())))
                .errorCode()).isEqualTo(ErrorCode.INVENTORY_ITEM_NOT_FOUND);
    }
}
