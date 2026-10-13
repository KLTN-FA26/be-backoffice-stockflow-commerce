package com.stockflow.product.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.internal.domain.AttributeDataType;
import com.stockflow.product.internal.domain.AttributeDetails;
import com.stockflow.product.internal.domain.AttributeValueDetails;
import com.stockflow.product.internal.domain.AxisSpec;
import com.stockflow.product.internal.domain.VariantStatus;
import com.stockflow.product.internal.service.AttributeDictionaryService.AttributeView;
import com.stockflow.product.internal.service.ProductVariantService.Generated;
import com.stockflow.product.internal.service.ProductVariantService.NewVariant;
import com.stockflow.product.internal.service.ProductVariantService.VariantOption;
import com.stockflow.product.internal.service.ProductVariantService.VariantView;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static com.stockflow.support.DemoData.demoId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Variants as combinations of a product's options: the derived signature and SKU, the default variant
 * taking the first combination, generation and its limits. On Postgres; every test rolls back.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class ProductVariantOptionsIntegrationTest {

    @Autowired ProductVariantService variants;
    @Autowired ProductAttributeService attributesOfProduct;
    @Autowired AttributeDictionaryService dictionary;
    @Autowired ProductService products;
    @Autowired ProductTaxonomyService taxonomy;
    @Autowired SkuInventoryControlService skus;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @MockitoBean CurrentUserProvider users;

    private UUID user;
    private UUID category;
    private UUID capacity;
    private UUID color;
    private UUID cap12;
    private UUID cap16;
    private UUID clear;
    private UUID red;

    @BeforeEach
    void dictionaryAndActor() {
        user = ReferenceRows.user(entityManager);
        when(users.current()).thenReturn(Optional.of(new CurrentUser(user, "test-" + user, Set.of(), Set.of(),
                DataScope.ALL, Set.of())));
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        category = taxonomy.createCategory("CAT-" + suffix, null,
                new ProductTaxonomyService.CategoryDetails("Ly", 0, null, null, null, true)).id();

        capacity = dictionary.create("CAPACITY", AttributeDataType.SELECT, details("Dung tich")).id();
        cap12 = value(capacity, "12OZ", "12 oz", 1, null);
        cap16 = value(capacity, "16OZ", "16 oz", 2, null);
        // COLOR is seeded with the demo data; a rolled-back test adds its values to it.
        color = demoId("demo:attr:COLOR");
        clear = value(color, "CLEAR", "Clear", 1, "#FFFFFF");
        red = value(color, "RED", "Red", 2, "#FF0000");
    }

    private static AttributeDetails details(String name) {
        return new AttributeDetails(name, null, false, false, 0);
    }

    private UUID value(UUID attribute, String code, String label, int sortOrder, String hex) {
        AttributeView view = dictionary.addValue(attribute, code, new AttributeValueDetails(label, null, hex, sortOrder));
        return view.values().stream().filter(v -> v.code().equals(code)).findFirst().orElseThrow().id();
    }

    private String newCode;

    private UUID product(String code) {
        newCode = code;
        return products.create(new CreateProductCommand(code, "Ly giay", "Paper cup", null, category, null, null,
                null, TaxClass.STANDARD, ProductKind.CUSTOMIZABLE)).productId();
    }

    private UUID productWithAxes() {
        UUID product = product("CUP-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        attributesOfProduct.replaceAxes(product, List.of(new AxisSpec(capacity, 0, List.of(cap12, cap16)),
                new AxisSpec(color, 1, List.of(clear, red))));
        return product;
    }

    private static ErrorCode errorOf(Supplier<?> call) {
        try {
            call.get();
        } catch (BusinessException e) {
            return e.errorCode();
        }
        throw new AssertionError("expected a BusinessException");
    }

    private VariantView defaultOf(UUID product) {
        return variants.list(product, 0, 200).items().stream().filter(VariantView::defaultVariant).findFirst()
                .orElseThrow();
    }

    private long count(UUID product) {
        return jdbc.queryForObject("select count(*) from product.variants where product_id = ?", Long.class, product);
    }

    @Test
    void theDefaultVariantTakesTheFirstCombination() {
        UUID product = productWithAxes();
        String code = newCode;

        var result = variants.generate(product, null);
        var all = variants.list(product, 0, 50).items();

        assertThat(all).hasSize(4);
        var def = all.stream().filter(VariantView::defaultVariant).findFirst().orElseThrow();
        assertThat(def.sku()).isEqualTo(code);
        assertThat(def.attributeSignature()).isEqualTo("CAPACITY=12OZ;COLOR=CLEAR");
        assertThat(def.options()).extracting(VariantOption::valueCode).containsExactly("12OZ", "CLEAR");
        assertThat(result.created()).extracting(VariantView::sku)
                .containsExactly(code + "-12OZ-RED", code + "-16OZ-CLEAR", code + "-16OZ-RED");
        assertThat(jdbc.queryForObject("select count(*) from inventory.inventory_items i join product.variants v "
                + "on v.sku = i.sku where v.product_id = ?", Long.class, product)).isEqualTo(4);
    }

    @Test
    void generationIsIdempotent() {
        UUID product = productWithAxes();
        variants.generate(product, null);

        Generated again = variants.generate(product, null);
        assertThat(again.created()).isEmpty();
        assertThat(again.skipped()).isEqualTo(4);
        assertThat(count(product)).isEqualTo(4);

        UUID other = productWithAxes();
        // 12 oz / Clear goes to the default variant, 16 oz / Red is then added by hand.
        variants.add(other, new NewVariant(null, null, List.of(cap12, clear), null));
        variants.add(other, new NewVariant(null, null, List.of(cap16, red), null));
        Generated rest = variants.generate(other, null);
        assertThat(rest.skipped()).isEqualTo(2);
        assertThat(rest.created()).hasSize(2);
        assertThat(count(other)).isEqualTo(4);
    }

    @Test
    void addingFromOptionsDerivesSignatureSkuAndName() {
        UUID product = productWithAxes();
        String code = newCode;
        // The first combination goes to the default variant (D1); the next one is a row of its own.
        variants.add(product, new NewVariant(null, null, List.of(cap12, clear), null));

        VariantView added = variants.add(product, new NewVariant(null, null, List.of(red, cap16), null));

        assertThat(added.sku()).isEqualTo(code + "-16OZ-RED");
        assertThat(added.attributeSignature()).isEqualTo("CAPACITY=16OZ;COLOR=RED");
        assertThat(added.name()).isEqualTo("Ly giay – 16 oz / Red");
        assertThat(added.defaultVariant()).isFalse();
        assertThat(added.options()).extracting(VariantOption::attributeCode, VariantOption::valueCode,
                VariantOption::label).containsExactly(
                org.assertj.core.groups.Tuple.tuple("CAPACITY", "16OZ", "16 oz"),
                org.assertj.core.groups.Tuple.tuple("COLOR", "RED", "Red"));
        entityManager.flush();
        entityManager.clear();
        assertThat(variants.get(product, added.id()).options()).extracting(VariantOption::valueCode)
                .containsExactly("16OZ", "RED");
        assertThat(defaultOf(product).name()).isEqualTo("Ly giay – 12 oz / Clear");
    }

    @Test
    void aManualSkuOverridesTheRuleAndIsNormalised() {
        UUID product = productWithAxes();
        variants.add(product, new NewVariant(null, null, List.of(cap12, clear), null));

        VariantView manual = variants.add(product, new NewVariant(" cup_x.1 ", null, List.of(cap16, red), null));
        assertThat(manual.sku()).isEqualTo("CUP_X.1");

        assertThat(errorOf(() -> variants.add(product, new NewVariant("CUP_X.1", null, List.of(cap16, clear), null))))
                .isEqualTo(ErrorCode.VARIANT_SKU_ALREADY_EXISTS);
        assertThat(errorOf(() -> variants.add(product, new NewVariant("-bad", null, List.of(cap16, clear), null))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void optionsMustNameOneValuePerAxis() {
        UUID product = productWithAxes();
        UUID outsider = value(capacity, "20OZ", "20 oz", 3, null);

        assertThat(errorOf(() -> variants.add(product, new NewVariant(null, null, List.of(cap12), null))))
                .isEqualTo(ErrorCode.VARIANT_OPTIONS_INVALID);
        assertThat(errorOf(() -> variants.add(product, new NewVariant(null, null, List.of(cap12, cap16, clear), null))))
                .isEqualTo(ErrorCode.VARIANT_OPTIONS_INVALID);
        assertThat(errorOf(() -> variants.add(product, new NewVariant(null, null, List.of(outsider, clear), null))))
                .isEqualTo(ErrorCode.VARIANT_OPTIONS_INVALID);
        assertThat(errorOf(() -> variants.add(product, new NewVariant(null, null, null, null))))
                .isEqualTo(ErrorCode.VARIANT_OPTIONS_INVALID);

        variants.add(product, new NewVariant(null, null, List.of(cap12, clear), null));
        assertThat(errorOf(() -> variants.add(product, new NewVariant(null, null, List.of(cap12, clear), null))))
                .isEqualTo(ErrorCode.VARIANT_SKU_ALREADY_EXISTS);
        assertThat(count(product)).isEqualTo(1);
    }

    @Test
    void aProductWithoutAxesNeedsASkuAndNoOptions() {
        UUID product = product("CUP-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        String sku = "X-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        assertThat(errorOf(() -> variants.add(product, new NewVariant(null, "x", null, null))))
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(errorOf(() -> variants.add(product, new NewVariant(sku, "x", List.of(cap12), null))))
                .isEqualTo(ErrorCode.VARIANT_OPTIONS_INVALID);
        VariantView extra = variants.add(product, new NewVariant(sku, "x", null, null));
        assertThat(extra.attributeSignature()).isEqualTo("SKU=" + sku);
        assertThat(extra.options()).isEmpty();
        assertThat(defaultOf(product).attributeSignature()).isEqualTo("DEFAULT");
        assertThat(errorOf(() -> variants.generate(product, null))).isEqualTo(ErrorCode.VARIANT_OPTIONS_INVALID);
    }

    @Test
    void optionsChangeOnlyWhileDraft() {
        UUID product = productWithAxes();
        String code = newCode;
        Generated generated = variants.generate(product, Map.of(capacity, List.of(cap12, cap16), color, List.of(clear)));
        VariantView sixteenClear = generated.created().getFirst();
        assertThat(sixteenClear.attributeSignature()).isEqualTo("CAPACITY=16OZ;COLOR=CLEAR");

        VariantView moved = variants.update(product, sixteenClear.id(),
                new NewVariant(null, null, List.of(cap16, red), null));
        assertThat(moved.attributeSignature()).isEqualTo("CAPACITY=16OZ;COLOR=RED");
        assertThat(moved.sku()).isEqualTo(code + "-16OZ-CLEAR");
        assertThat(moved.options()).extracting(VariantOption::valueCode).containsExactly("16OZ", "RED");
        assertThat(errorOf(() -> variants.update(product, moved.id(),
                new NewVariant(null, null, List.of(cap12, clear), null)))).isEqualTo(ErrorCode.VARIANT_SKU_ALREADY_EXISTS);

        UUID approver = ReferenceRows.user(entityManager);
        products.submit(product, user);
        products.approve(product, approver);
        VariantView active = variants.get(product, moved.id());
        assertThat(active.status()).isEqualTo(VariantStatus.ACTIVE);
        assertThat(errorOf(() -> variants.update(product, moved.id(),
                new NewVariant(null, null, List.of(cap16, clear), null)))).isEqualTo(ErrorCode.INVALID_VARIANT_TRANSITION);
    }

    @Test
    void addingAnAxisAfterGenerationIsLocked() {
        UUID product = productWithAxes();
        variants.generate(product, null);
        UUID finish = dictionary.create("FINISH", AttributeDataType.SELECT, details("Hoan thien")).id();
        UUID matte = value(finish, "MATTE", "Matte", 1, null);

        assertThat(errorOf(() -> attributesOfProduct.replaceAxes(product, List.of(
                new AxisSpec(capacity, 0, List.of(cap12, cap16)), new AxisSpec(color, 1, List.of(clear, red)),
                new AxisSpec(finish, 2, List.of(matte)))))).isEqualTo(ErrorCode.PRODUCT_AXES_LOCKED);
    }

    @Test
    void tooLargeAMatrixIsRefusedBeforeWriting() {
        UUID wide = dictionary.create("WIDE", AttributeDataType.SELECT, details("Wide")).id();
        UUID tall = dictionary.create("TALL", AttributeDataType.SELECT, details("Tall")).id();
        List<UUID> wideValues = IntStream.range(0, 11).mapToObj(i -> value(wide, "W" + i, "W" + i, i, null)).toList();
        List<UUID> tallValues = IntStream.range(0, 10).mapToObj(i -> value(tall, "T" + i, "T" + i, i, null)).toList();
        UUID product = product("CUP-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        attributesOfProduct.replaceAxes(product, List.of(new AxisSpec(wide, 0, wideValues), new AxisSpec(tall, 1, tallValues)));

        assertThat(errorOf(() -> variants.generate(product, null))).isEqualTo(ErrorCode.VARIANT_MATRIX_TOO_LARGE);
        assertThat(count(product)).isEqualTo(1);
        assertThat(defaultOf(product).attributeSignature()).isEqualTo("DEFAULT");
    }

    @Test
    void aProductCodeWithUnderscoreWorksEndToEnd() {
        String code = "CUP_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        UUID product = product(code);
        attributesOfProduct.replaceAxes(product, List.of(new AxisSpec(capacity, 0, List.of(cap12, cap16))));

        Generated generated = variants.generate(product, null);

        assertThat(defaultOf(product).sku()).isEqualTo(code);
        assertThat(generated.created()).extracting(VariantView::sku).containsExactly(code + "-16OZ");
        UUID defaultId = defaultOf(product).id();
        assertThat(skus.get(product, defaultId).sku()).isEqualTo(code);
        assertThat(skus.get(product, generated.created().getFirst().id()).sku()).isEqualTo(code + "-16OZ");
    }

    @Test
    void listingVariantsIsConstantInQueries() {
        UUID small = productWithAxes();
        variants.generate(small, null);

        UUID cap20 = value(capacity, "20OZ", "20 oz", 3, null);
        UUID blue = value(color, "BLUE", "Blue", 3, "#0000FF");
        UUID green = value(color, "GREEN", "Green", 4, "#00FF00");
        UUID large = product("CUP-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        attributesOfProduct.replaceAxes(large, List.of(new AxisSpec(capacity, 0, List.of(cap12, cap16, cap20)),
                new AxisSpec(color, 1, List.of(clear, red, blue, green))));
        variants.generate(large, null);
        entityManager.flush();
        entityManager.clear();

        long smallStatements = statementsToList(small, 4);
        long largeStatements = statementsToList(large, 12);

        assertThat(largeStatements).isEqualTo(smallStatements);
    }

    private long statementsToList(UUID product, int expected) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            var items = variants.list(product, 0, 50).items();
            assertThat(items).hasSize(expected);
            assertThat(items).allSatisfy(v -> assertThat(v.options()).hasSize(2));
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
            entityManager.clear();
        }
    }
}
