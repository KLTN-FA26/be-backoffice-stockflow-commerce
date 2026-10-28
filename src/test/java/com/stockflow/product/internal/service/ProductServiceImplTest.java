package com.stockflow.product.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.OpenReferences;
import com.stockflow.product.api.ProductChange;
import com.stockflow.product.api.ProductDiscontinuation;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.ProductSummary;
import com.stockflow.product.api.Purchasability;
import com.stockflow.product.api.SkuReference;
import com.stockflow.product.api.SkuReferenceProbe;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.api.UpdateProductCommand;
import com.stockflow.product.internal.domain.Product;
import com.stockflow.product.internal.domain.ProductChangeType;
import com.stockflow.product.internal.domain.ProductId;
import com.stockflow.product.internal.domain.ProductRepository;
import com.stockflow.product.internal.domain.ReferenceStatus;
import com.stockflow.product.internal.repository.ProductChangeLogRepository;
import com.stockflow.product.internal.repository.ProductCommerceRepository;
import com.stockflow.product.internal.repository.ProductSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The application service of SCRUM-461 with its collaborators mocked: what it asks of whom, in which order, and what
 * it refuses before it changes anything. The aggregate's own rules are in {@code ProductTest}; the database and the
 * HTTP surface are in the integration tests.
 */
class ProductServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID CATEGORY = UUID.randomUUID();
    private static final UUID PEOPLE = UUID.randomUUID();

    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductSearchRepository search = mock(ProductSearchRepository.class);
    private final ProductVariantService variants = mock(ProductVariantService.class);
    private final ProductMediaService media = mock(ProductMediaService.class);
    private final ProductAttributeService attributes = mock(ProductAttributeService.class);
    private final ProductEventPublisher events = mock(ProductEventPublisher.class);
    private final ProductChangeLogRepository history = mock(ProductChangeLogRepository.class);
    private final ProductCommerceRepository commerce = mock(ProductCommerceRepository.class);
    private final InventoryService inventory = mock(InventoryService.class);
    private final IdentityService identity = mock(IdentityService.class);
    private final SkuReferenceProbe purchasing = mock(SkuReferenceProbe.class);
    private final SkuReferenceProbe selling = mock(SkuReferenceProbe.class);
    private ProductServiceImpl service;

    @BeforeEach
    void setUp() {
        when(products.save(any())).thenAnswer(call -> call.getArgument(0));
        when(purchasing.openReferences(anyCollection(), anyInt())).thenReturn(List.of());
        when(selling.openReferences(anyCollection(), anyInt())).thenReturn(List.of());
        service = new ProductServiceImpl(products, search, variants, media, attributes, events, history, commerce,
                inventory, identity, List.of(purchasing, selling), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Product productIn(ProductStatus status, long version) {
        boolean approvedOnce = status != ProductStatus.DRAFT && status != ProductStatus.PENDING_APPROVAL;
        return new Product(ProductId.newId(), "CUP-12OZ", "Ly giấy", "Paper cup", BRAND, CATEGORY, null, null, null,
                TaxClass.STANDARD, ProductKind.STANDARD, status, version,
                status == ProductStatus.DRAFT ? null : PEOPLE, status == ProductStatus.DRAFT ? null : NOW,
                approvedOnce ? UUID.randomUUID() : null, approvedOnce ? NOW : null, null,
                status == ProductStatus.DISCONTINUED ? NOW : null, status == ProductStatus.DISCONTINUED ? "old" : null,
                status == ProductStatus.INACTIVE ? NOW : null, status == ProductStatus.INACTIVE ? "recall" : null);
    }

    /** The product is found, locked, with two SKUs; the summary read after the write shows {@code after}. */
    private Product locked(ProductStatus status, long version, ProductStatus after) {
        Product product = productIn(status, version);
        when(products.findForUpdate(product.id())).thenReturn(Optional.of(product));
        when(products.findById(product.id())).thenReturn(Optional.of(product));
        when(products.skusOf(product.id())).thenReturn(List.of("CUP-12OZ", "CUP-12OZ-L"));
        when(search.summary(product.id().value())).thenReturn(Optional.of(summary(product, after)));
        return product;
    }

    private static ProductSummary summary(Product product, ProductStatus status) {
        return new ProductSummary(product.id().value(), product.code(), product.name(), null, "cup-12oz", BRAND, "Brand",
                CATEGORY, null, null, null, TaxClass.STANDARD, ProductKind.STANDARD, status, NOW, "u", NOW, "u", null, null,
                null, null, null, null, null, product.version() + 1, null, null, null);
    }

    private static SkuReference po(int n) {
        return new SkuReference(SkuReference.Kind.PURCHASE_ORDER_LINE, "PO-" + n, UUID.randomUUID(), "CUP-12OZ");
    }

    private static SkuReference order(int n) {
        return new SkuReference(SkuReference.Kind.SALES_ORDER_LINE, "SO-" + n, UUID.randomUUID(), "CUP-12OZ-L");
    }

    private static BusinessException refusal(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return (BusinessException) thrown;
    }

    // ------------------------------------------------------------------ discontinue

    @Test
    @DisplayName("discontinue: lock, version, the move, what is open, the variants, the history last")
    void discontinueOrder() {
        Product product = locked(ProductStatus.PUBLISHED, 4, ProductStatus.DISCONTINUED);
        when(purchasing.openReferences(anyCollection(), anyInt())).thenReturn(List.of(po(1)));

        ProductDiscontinuation result = service.discontinue(product.id().value(), "  Hết dòng  ", 4);

        InOrder order = inOrder(products, purchasing, selling, variants, events, history);
        order.verify(products).findForUpdate(product.id());
        order.verify(purchasing).openReferences(List.of("CUP-12OZ", "CUP-12OZ-L"), OpenReferences.LIMIT + 1);
        order.verify(selling).openReferences(List.of("CUP-12OZ", "CUP-12OZ-L"), OpenReferences.LIMIT + 1);
        order.verify(variants).obsoleteAll(product.id().value(), "Hết dòng");
        order.verify(products).save(product);
        order.verify(events).publishEventsOf(product);
        order.verify(history).record(product.id().value(), ProductChangeType.DISCONTINUED, null, "PUBLISHED", "DISCONTINUED",
                "Hết dòng", List.of());
        assertThat(result.product().status()).isEqualTo(ProductStatus.DISCONTINUED);
        assertThat(result.warnings()).containsExactly(ProductDiscontinuation.OPEN_PURCHASE_ORDER_LINES);
        assertThat(result.references().references()).extracting(SkuReference::documentNumber).containsExactly("PO-1");
    }

    @Test
    @DisplayName("discontinue: both kinds of open document give both warnings, and the discontinuation still happens")
    void discontinueWarnsAndGoesAhead() {
        Product product = locked(ProductStatus.APPROVED, 1, ProductStatus.DISCONTINUED);
        when(purchasing.openReferences(anyCollection(), anyInt())).thenReturn(List.of(po(1), po(2)));
        when(selling.openReferences(anyCollection(), anyInt())).thenReturn(List.of(order(1)));

        ProductDiscontinuation result = service.discontinue(product.id().value(), "end", 1);

        assertThat(result.warnings()).containsExactly("OPEN_PURCHASE_ORDER_LINES", "UNSHIPPED_ORDER_LINES");
        assertThat(result.references().references()).hasSize(3);
        assertThat(result.references().truncated()).isFalse();
        assertThat(product.status()).isEqualTo(ProductStatus.DISCONTINUED);
        verify(variants).obsoleteAll(product.id().value(), "end");
    }

    @Test
    @DisplayName("discontinue: nothing open means no warning")
    void discontinueWithNothingOpen() {
        Product product = locked(ProductStatus.INACTIVE, 1, ProductStatus.DISCONTINUED);

        ProductDiscontinuation result = service.discontinue(product.id().value(), "end", 1);

        assertThat(result.warnings()).isEmpty();
        assertThat(result.references().references()).isEmpty();
    }

    @Test
    @DisplayName("discontinue: a stale version is OPTIMISTIC_LOCK and nothing is asked or changed")
    void discontinueStaleVersion() {
        Product product = locked(ProductStatus.APPROVED, 4, ProductStatus.DISCONTINUED);

        BusinessException refused = refusal(catchThrowable(() -> service.discontinue(product.id().value(), "end", 3)));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        assertThat(product.status()).isEqualTo(ProductStatus.APPROVED);
        verifyNoInteractions(purchasing, selling, variants, events, history);
        verify(products, never()).save(any());
    }

    @Test
    @DisplayName("discontinue: a draft, a blank reason or an unknown product is refused before any variant moves")
    void discontinueRefusals() {
        Product draft = locked(ProductStatus.DRAFT, 0, ProductStatus.DRAFT);
        assertThat(refusal(catchThrowable(() -> service.discontinue(draft.id().value(), "end", 0))).errorCode())
                .isEqualTo(ErrorCode.INVALID_PRODUCT_STATUS_TRANSITION);
        Product approved = locked(ProductStatus.APPROVED, 0, ProductStatus.APPROVED);
        assertThat(refusal(catchThrowable(() -> service.discontinue(approved.id().value(), " ", 0))).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        UUID unknown = UUID.randomUUID();
        when(products.findForUpdate(new ProductId(unknown))).thenReturn(Optional.empty());
        assertThat(refusal(catchThrowable(() -> service.discontinue(unknown, "end", 0))).errorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);

        verifyNoInteractions(variants, events, history);
        verify(products, never()).save(any());
    }

    @Test
    @DisplayName("open documents: at most 20, taken from each kind in turn, truncated when there are more")
    void referencesAreCappedAndShared() {
        Product product = locked(ProductStatus.APPROVED, 1, ProductStatus.DISCONTINUED);
        // Each probe is asked for LIMIT + 1 and answers with that many at most: 21 purchase order lines, 2 order lines.
        when(purchasing.openReferences(anyCollection(), anyInt()))
                .thenReturn(IntStream.rangeClosed(1, 21).mapToObj(ProductServiceImplTest::po).toList());
        when(selling.openReferences(anyCollection(), anyInt())).thenReturn(List.of(order(1), order(2)));

        ProductDiscontinuation result = service.discontinue(product.id().value(), "end", 1);

        List<SkuReference> shown = result.references().references();
        assertThat(shown).hasSize(OpenReferences.LIMIT);
        assertThat(result.references().truncated()).isTrue();
        // The order lines are not crowded out by the purchase order lines: both kinds are listed and warned about.
        assertThat(shown).filteredOn(r -> r.kind() == SkuReference.Kind.SALES_ORDER_LINE).hasSize(2);
        assertThat(shown).filteredOn(r -> r.kind() == SkuReference.Kind.PURCHASE_ORDER_LINE).hasSize(18);
        assertThat(shown.get(0).documentNumber()).isEqualTo("PO-1");
        assertThat(shown.get(1).documentNumber()).isEqualTo("SO-1");
        assertThat(result.warnings()).containsExactly("OPEN_PURCHASE_ORDER_LINES", "UNSHIPPED_ORDER_LINES");
    }

    @Test
    @DisplayName("open documents: exactly 20 is not truncated")
    void exactlyTheLimitIsNotTruncated() {
        Product product = locked(ProductStatus.APPROVED, 1, ProductStatus.APPROVED);
        when(purchasing.openReferences(anyCollection(), anyInt()))
                .thenReturn(IntStream.rangeClosed(1, 20).mapToObj(ProductServiceImplTest::po).toList());

        OpenReferences open = service.openReferences(product.id().value());

        assertThat(open.references()).hasSize(20);
        assertThat(open.truncated()).isFalse();
    }

    @Test
    @DisplayName("open documents: a product with no SKU asks nobody; an unknown product is PRODUCT_NOT_FOUND")
    void openReferencesEdges() {
        Product product = locked(ProductStatus.APPROVED, 1, ProductStatus.APPROVED);
        when(products.skusOf(product.id())).thenReturn(List.of());
        assertThat(service.openReferences(product.id().value()).references()).isEmpty();
        verifyNoInteractions(purchasing, selling);

        UUID unknown = UUID.randomUUID();
        when(products.findById(new ProductId(unknown))).thenReturn(Optional.empty());
        assertThat(refusal(catchThrowable(() -> service.openReferences(unknown))).errorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }

    // ------------------------------------------------------------------ suspend / resume

    @Test
    @DisplayName("suspend: the move, the event for the catalog and the history with from, to and reason")
    void suspend() {
        Product product = locked(ProductStatus.PUBLISHED, 2, ProductStatus.INACTIVE);

        ProductSummary result = service.suspend(product.id().value(), " recall ", 2);

        assertThat(result.status()).isEqualTo(ProductStatus.INACTIVE);
        assertThat(product.status()).isEqualTo(ProductStatus.INACTIVE);
        assertThat(product.suspendedAt()).isEqualTo(NOW);
        InOrder order = inOrder(products, events, history);
        order.verify(products).save(product);
        order.verify(events).publishEventsOf(product);
        order.verify(history).record(product.id().value(), ProductChangeType.SUSPENDED, null, "PUBLISHED", "INACTIVE", "recall",
                List.of());
        verifyNoInteractions(variants);
    }

    @Test
    @DisplayName("resume: back to APPROVED with its history entry; stale version and wrong status are refused")
    void resume() {
        Product product = locked(ProductStatus.INACTIVE, 2, ProductStatus.APPROVED);

        assertThat(refusal(catchThrowable(() -> service.resume(product.id().value(), 1))).errorCode())
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        verify(products, never()).save(any());

        service.resume(product.id().value(), 2);

        assertThat(product.status()).isEqualTo(ProductStatus.APPROVED);
        verify(history).record(product.id().value(), ProductChangeType.RESUMED, null, "INACTIVE", "APPROVED", null, List.of());

        Product approved = locked(ProductStatus.APPROVED, 2, ProductStatus.APPROVED);
        assertThat(refusal(catchThrowable(() -> service.resume(approved.id().value(), 2))).errorCode())
                .isEqualTo(ErrorCode.INVALID_PRODUCT_STATUS_TRANSITION);
    }

    // ------------------------------------------------------------------ delete

    @Test
    @DisplayName("delete: lock, version, draft, nothing open, the files noted, the inventory items, the rows, the files after commit")
    void deleteDraftOrder() {
        Product product = locked(ProductStatus.DRAFT, 3, ProductStatus.DRAFT);
        when(media.storedKeysOf(product.id().value())).thenReturn(List.of("k1", "k2"));

        service.deleteDraft(product.id().value(), 3);

        InOrder order = inOrder(products, purchasing, selling, media, inventory);
        order.verify(products).findForUpdate(product.id());
        order.verify(purchasing).openReferences(anyCollection(), anyInt());
        order.verify(selling).openReferences(anyCollection(), anyInt());
        order.verify(inventory).skusInUse(List.of("CUP-12OZ", "CUP-12OZ-L"));
        order.verify(media).storedKeysOf(product.id().value());
        order.verify(inventory).deleteUnusedItems(List.of("CUP-12OZ", "CUP-12OZ-L"));
        order.verify(products).delete(product.id());
        order.verify(media).deleteStoredAfterCommit(List.of("k1", "k2"));
    }

    @Test
    @DisplayName("delete: not a draft is PRODUCT_NOT_DELETABLE, a stale version OPTIMISTIC_LOCK, an unknown one PRODUCT_NOT_FOUND")
    void deleteRefusals() {
        for (ProductStatus status : List.of(ProductStatus.PENDING_APPROVAL, ProductStatus.APPROVED, ProductStatus.PUBLISHED,
                ProductStatus.INACTIVE, ProductStatus.DISCONTINUED)) {
            Product product = locked(status, 3, status);
            assertThat(refusal(catchThrowable(() -> service.deleteDraft(product.id().value(), 3))).errorCode())
                    .as(status.name()).isEqualTo(ErrorCode.PRODUCT_NOT_DELETABLE);
        }
        Product draft = locked(ProductStatus.DRAFT, 3, ProductStatus.DRAFT);
        assertThat(refusal(catchThrowable(() -> service.deleteDraft(draft.id().value(), 2))).errorCode())
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        UUID unknown = UUID.randomUUID();
        when(products.findForUpdate(new ProductId(unknown))).thenReturn(Optional.empty());
        assertThat(refusal(catchThrowable(() -> service.deleteDraft(unknown, 0))).errorCode())
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);

        verify(products, never()).delete(any());
        verifyNoInteractions(inventory, media);
    }

    @Test
    @DisplayName("delete: an open purchase order or order line is PRODUCT_IN_USE, before anything is deleted")
    void deleteRefusedByOpenDocuments() {
        Product product = locked(ProductStatus.DRAFT, 3, ProductStatus.DRAFT);
        when(selling.openReferences(anyCollection(), anyInt())).thenReturn(List.of(order(7)));

        BusinessException refused = refusal(catchThrowable(() -> service.deleteDraft(product.id().value(), 3)));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.PRODUCT_IN_USE);
        assertThat(refused.getMessage()).contains("SO-7");
        verify(products, never()).delete(any());
        verifyNoInteractions(inventory);
    }

    @Test
    @DisplayName("delete: an inventory item some document uses (stock, ledger, warehouse task...) is PRODUCT_IN_USE naming the SKU, before anything is deleted")
    void deleteRefusedByAnItemInUse() {
        Product product = locked(ProductStatus.DRAFT, 3, ProductStatus.DRAFT);
        when(inventory.skusInUse(anyCollection())).thenReturn(List.of("CUP-12OZ-L"));

        BusinessException refused = refusal(catchThrowable(() -> service.deleteDraft(product.id().value(), 3)));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.PRODUCT_IN_USE);
        assertThat(refused.getMessage()).contains("CUP-12OZ-L");
        verify(inventory, never()).deleteUnusedItems(anyCollection());
        verify(products, never()).delete(any());
        verifyNoInteractions(media);
    }

    @Test
    @DisplayName("delete: a foreign key still holding an inventory item is PRODUCT_IN_USE, and the rows stay")
    void deleteRefusedByInventoryForeignKey() {
        Product product = locked(ProductStatus.DRAFT, 3, ProductStatus.DRAFT);
        var held = new DataIntegrityViolationException("fk_stock_item_inventory_item");
        org.mockito.Mockito.doThrow(held).when(inventory).deleteUnusedItems(anyCollection());

        BusinessException refused = refusal(catchThrowable(() -> service.deleteDraft(product.id().value(), 3)));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.PRODUCT_IN_USE);
        assertThat(refused.getCause()).isSameAs(held);
        verify(products, never()).delete(any());
        verify(media, never()).deleteStoredAfterCommit(anyList());
    }

    // ------------------------------------------------------------------ edit and the approval moves

    @Test
    @DisplayName("update: a stale version is OPTIMISTIC_LOCK before anything is read or written")
    void updateStaleVersion() {
        Product product = locked(ProductStatus.DRAFT, 5, ProductStatus.DRAFT);

        BusinessException refused = refusal(catchThrowable(() -> service.update(new UpdateProductCommand(product.id().value(), 4,
                "Ly mới", "New", BRAND, CATEGORY, null, null, null, TaxClass.STANDARD, ProductKind.STANDARD))));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
        assertThat(product.name()).isEqualTo("Ly giấy");
        verify(products, never()).save(any());
        verifyNoInteractions(history);
    }

    @Test
    @DisplayName("update: the history names the fields that changed, and nothing is written when none did")
    void updateRecordsChangedFields() {
        Product product = locked(ProductStatus.DRAFT, 5, ProductStatus.DRAFT);
        when(products.brandStatus(BRAND)).thenReturn(ReferenceStatus.ACTIVE);
        when(products.categoryStatus(CATEGORY)).thenReturn(ReferenceStatus.ACTIVE);

        service.update(new UpdateProductCommand(product.id().value(), 5, "Ly mới", "Paper cup", BRAND, CATEGORY, null,
                null, null, TaxClass.STANDARD, ProductKind.STANDARD));

        verify(history).record(product.id().value(), ProductChangeType.UPDATED, null, null, null, null, List.of("name"));

        service.update(new UpdateProductCommand(product.id().value(), 5, "Ly mới", "Paper cup", BRAND, CATEGORY, null,
                null, null, TaxClass.STANDARD, ProductKind.STANDARD));

        verify(history, org.mockito.Mockito.times(1)).record(any(), eq(ProductChangeType.UPDATED), any(), any(), any(), any(), anyList());
    }

    // ------------------------------------------------------------------ the brand and category chosen (SCRUM-87)

    private static CreateProductCommand create(UUID brand, UUID category) {
        return new CreateProductCommand("CUP-NEW", "Ly mới", "New cup", brand, category, null, null, null,
                TaxClass.STANDARD, ProductKind.STANDARD, List.of());
    }

    @Test
    @DisplayName("create: an inactive brand is BRAND_INACTIVE on brandId, an inactive category CATEGORY_INACTIVE on categoryId")
    void createRefusesInactiveReferences() {
        UUID otherCategory = UUID.randomUUID();
        when(products.brandStatus(BRAND)).thenReturn(ReferenceStatus.INACTIVE);
        when(products.categoryStatus(CATEGORY)).thenReturn(ReferenceStatus.ACTIVE);
        when(products.brandStatus(PEOPLE)).thenReturn(ReferenceStatus.ACTIVE);
        when(products.categoryStatus(otherCategory)).thenReturn(ReferenceStatus.INACTIVE);

        BusinessException brand = refusal(catchThrowable(() -> service.create(create(BRAND, CATEGORY))));
        BusinessException category = refusal(catchThrowable(() -> service.create(create(PEOPLE, otherCategory))));

        assertThat(brand.errorCode()).isEqualTo(ErrorCode.BRAND_INACTIVE);
        assertThat(brand.fieldErrors()).extracting(f -> f.field()).containsExactly("brandId");
        assertThat(category.errorCode()).isEqualTo(ErrorCode.CATEGORY_INACTIVE);
        assertThat(category.fieldErrors()).extracting(f -> f.field()).containsExactly("categoryId");
        verify(products, never()).save(any());
        verifyNoInteractions(history);
    }

    @Test
    @DisplayName("create: an unknown brand or category is still 404 BRAND_NOT_FOUND / CATEGORY_NOT_FOUND")
    void createStillAnswersNotFound() {
        when(products.brandStatus(BRAND)).thenReturn(ReferenceStatus.MISSING);
        when(products.brandStatus(PEOPLE)).thenReturn(ReferenceStatus.ACTIVE);
        when(products.categoryStatus(CATEGORY)).thenReturn(ReferenceStatus.MISSING);

        assertThat(refusal(catchThrowable(() -> service.create(create(BRAND, null)))).errorCode())
                .isEqualTo(ErrorCode.BRAND_NOT_FOUND);
        assertThat(refusal(catchThrowable(() -> service.create(create(PEOPLE, CATEGORY)))).errorCode())
                .isEqualTo(ErrorCode.CATEGORY_NOT_FOUND);
        verify(products, never()).save(any());
    }

    @Test
    @DisplayName("update: a brand deactivated after the product chose it is kept; the edit goes through")
    void keptInactiveBrandIsAccepted() {
        Product product = locked(ProductStatus.DRAFT, 5, ProductStatus.DRAFT);
        when(products.brandStatus(BRAND)).thenReturn(ReferenceStatus.INACTIVE);
        when(products.categoryStatus(CATEGORY)).thenReturn(ReferenceStatus.INACTIVE);

        service.update(new UpdateProductCommand(product.id().value(), 5, "Ly mới", "Paper cup", BRAND, CATEGORY, null,
                null, null, TaxClass.STANDARD, ProductKind.STANDARD));

        assertThat(product.name()).isEqualTo("Ly mới");
        verify(products).save(product);
    }

    @Test
    @DisplayName("update: moving to an inactive category is CATEGORY_INACTIVE, and nothing changes")
    void changingToInactiveCategoryIsRefused() {
        Product product = locked(ProductStatus.DRAFT, 5, ProductStatus.DRAFT);
        UUID retired = UUID.randomUUID();
        when(products.brandStatus(BRAND)).thenReturn(ReferenceStatus.ACTIVE);
        when(products.categoryStatus(retired)).thenReturn(ReferenceStatus.INACTIVE);

        BusinessException refused = refusal(catchThrowable(() -> service.update(new UpdateProductCommand(
                product.id().value(), 5, "Ly mới", "Paper cup", BRAND, retired, null, null, null, TaxClass.STANDARD,
                ProductKind.STANDARD))));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.CATEGORY_INACTIVE);
        assertThat(product.categoryId()).isEqualTo(CATEGORY);
        verify(products, never()).save(any());
    }

    @Test
    @DisplayName("submit, approve and reject each write their own history entry with from, to and the reason")
    void approvalMovesAreRecorded() {
        Product draft = locked(ProductStatus.DRAFT, 0, ProductStatus.PENDING_APPROVAL);
        service.submit(draft.id().value(), PEOPLE);
        verify(history).record(draft.id().value(), ProductChangeType.SUBMITTED, null, "DRAFT", "PENDING_APPROVAL", null, List.of());

        Product pending = locked(ProductStatus.PENDING_APPROVAL, 1, ProductStatus.APPROVED);
        service.approve(pending.id().value(), UUID.randomUUID());
        verify(variants).activateDrafts(pending.id().value());
        verify(history).record(pending.id().value(), ProductChangeType.APPROVED, null, "PENDING_APPROVAL", "APPROVED", null, List.of());

        Product other = locked(ProductStatus.PENDING_APPROVAL, 1, ProductStatus.DRAFT);
        service.reject(other.id().value(), UUID.randomUUID(), "Missing dimensions");
        verify(history).record(other.id().value(), ProductChangeType.REJECTED, null, "PENDING_APPROVAL", "DRAFT",
                "Missing dimensions", List.of());
    }

    // ------------------------------------------------------------------ purchasability and history

    @Test
    @DisplayName("purchasability: SKUs are upper-cased and stripped, and the answer is the commerce repository's")
    void purchasabilityNormalisesTheKeys() {
        when(commerce.purchasability(anyCollection(), org.mockito.ArgumentMatchers.eq(false))).thenReturn(Map.of("CUP-12OZ", Purchasability.PRODUCT_INACTIVE));

        Map<String, Purchasability> answer = service.purchasability(List.of(" cup-12oz ", "CUP-12OZ"));

        assertThat(answer).containsExactly(Map.entry("CUP-12OZ", Purchasability.PRODUCT_INACTIVE));
        @SuppressWarnings("unchecked")
        var asked = org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(commerce).purchasability(asked.capture(), org.mockito.ArgumentMatchers.eq(false));
        assertThat(asked.getValue()).containsExactly("CUP-12OZ");
    }

    @Test
    @DisplayName("history: names the actors, shows the system for a job and unknown for a name that is gone")
    void historyNamesTheActors() {
        Product product = locked(ProductStatus.APPROVED, 1, ProductStatus.APPROVED);
        UUID known = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        var entries = List.of(
                new ProductChangeLogRepository.Entry(ProductChangeType.SUSPENDED, null, "APPROVED", "INACTIVE", "recall", List.of(), known, NOW),
                new ProductChangeLogRepository.Entry(ProductChangeType.LOGISTICS, UUID.randomUUID(), null, null, null, List.of("weightKg"), null, NOW),
                new ProductChangeLogRepository.Entry(ProductChangeType.UPDATED, null, null, null, null, List.of("name"), gone, NOW));
        when(history.page(eq(product.id().value()), any())).thenReturn(new PageImpl<>(entries, PageRequest.of(0, 20), 3));
        when(identity.displayNames(anyCollection())).thenReturn(Map.of(known, "Nguyễn Văn A"));

        PageResponse<ProductChange> page = service.history(product.id().value(), 0, 20);

        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.items()).extracting(ProductChange::changeType, ProductChange::subjectType, ProductChange::actorName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("SUSPENDED", "PRODUCT", "Nguyễn Văn A"),
                        org.assertj.core.groups.Tuple.tuple("LOGISTICS", "LOGISTICS", "system"),
                        org.assertj.core.groups.Tuple.tuple("UPDATED", "PRODUCT", "unknown"));
        assertThat(page.items().get(0).fromStatus()).isEqualTo("APPROVED");
        assertThat(page.items().get(0).reason()).isEqualTo("recall");
        assertThat(page.items().get(1).changedFields()).containsExactly("weightKg");
        UUID unknown = UUID.randomUUID();
        when(products.findById(new ProductId(unknown))).thenReturn(Optional.empty());
        assertThat(refusal(catchThrowable(() -> service.history(unknown, 0, 20))).errorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
    }
}
