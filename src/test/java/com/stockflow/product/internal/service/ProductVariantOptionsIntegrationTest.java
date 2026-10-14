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
    private UUID brand;
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
        // A product needs a brand before it is submitted (BR-PRD-001).
        brand = taxonomy.createBrand("BR-" + suffix, "Brand " + suffix, null).id();

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
        return products.create(new CreateProductCommand(code, "Ly giay", "Paper cup", brand, category, null, null,
                null, TaxClass.STANDARD, ProductKind.CUSTOMIZABLE, List.of())).productId();
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
        variants.add(other, new NewVariant(null, null, List.of(cap16, red), null));
        Generated rest = variants.generate(other, null);
        assertThat(rest.skipped()).isEqualTo(1);
        assertThat(rest.created()).hasSize(2);
        assertThat(count(other)).isEqualTo(4);
        assertThat(defaultOf(other).attributeSignature()).isEqualTo("CAPACITY=12OZ;COLOR=CLEAR");
    }

    @Test
    void addingFromOptionsDerivesSignatureSkuAndName() {
        UUID product = productWithAxes();
        String code = newCode;
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
        // Not the matrix-first combination: the default variant is left alone.
        assertThat(count(product)).isEqualTo(2);
        assertThat(defaultOf(product).attributeSignature()).isEqualTo("DEFAULT");
        assertThat(defaultOf(product).sku()).isEqualTo(code);
    }

    @Test
    void theMatrixFirstCombinationBecomesTheDefaultVariant() {
        UUID product = productWithAxes();
        String code = newCode;

        VariantView first = variants.add(product, new NewVariant(null, null, List.of(clear, cap12), null));

        assertThat(first.defaultVariant()).isTrue();
        assertThat(first.sku()).isEqualTo(code);
        assertThat(first.attributeSignature()).isEqualTo("CAPACITY=12OZ;COLOR=CLEAR");
        assertThat(first.name()).isEqualTo("Ly giay \u2013 12 oz / Clear");
        assertThat(first.options()).extracting(VariantOption::valueCode).containsExactly("12OZ", "CLEAR");
        assertThat(count(product)).isEqualTo(1);
    }

    @Test
    void aSkuSentWithTheMatrixFirstCombinationRenamesTheDraftDefault() {
        UUID product = productWithAxes();

        VariantView first = variants.add(product, new NewVariant(" cup-first ", null, List.of(cap12, clear), null));

        assertThat(first.defaultVariant()).isTrue();
        assertThat(first.sku()).isEqualTo("CUP-FIRST");
        assertThat(count(product)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from inventory.inventory_items where sku = 'CUP-FIRST'",
                Long.class)).isEqualTo(1);
    }

    @Test
    void aRenameOfAReferencedSkuIsARefusedTransition() {
        UUID product = productWithAxes();
        jdbc.update("insert into catalog.pricing_rule(id, name, sku, price, currency, active, version, created_at) "
                + "values (?, 'rule', ?, 1, 'VND', true, 0, now())", UUID.randomUUID(), newCode);

        assertThat(errorOf(() -> variants.add(product, new NewVariant("CUP-RENAMED", null, List.of(cap12, clear), null))))
                .isEqualTo(ErrorCode.INVALID_VARIANT_TRANSITION);
    }

    @Test
    void aDefaultThatLeftDraftKeepsDefaultAndEveryCombinationIsARow() {
        UUID product = productWithAxes();
        products.submit(product, user);
        products.approve(product, ReferenceRows.user(entityManager));
        assertThat(defaultOf(product).status()).isEqualTo(VariantStatus.ACTIVE);

        Generated generated = variants.generate(product, null);

        assertThat(generated.created()).hasSize(4);
        assertThat(defaultOf(product).attributeSignature()).isEqualTo("DEFAULT");
        assertThat(count(product)).isEqualTo(5);
    }

    @Test
    void aRestrictedMatrixWithoutTheFirstCombinationLeavesTheDefaultAlone() {
        UUID product = productWithAxes();

        Generated generated = variants.generate(product, Map.of(capacity, List.of(cap16)));

        assertThat(generated.created()).hasSize(2);
        assertThat(defaultOf(product).attributeSignature()).isEqualTo("DEFAULT");
        assertThat(count(product)).isEqualTo(3);
    }

    @Test
    void generatedSkusThatCollideAreRefusedBeforeWriting() {
        UUID left = dictionary.create("LEFTX", AttributeDataType.SELECT, details("Left")).id();
        UUID right = dictionary.create("RIGHTX", AttributeDataType.SELECT, details("Right")).id();
        UUID a = value(left, "A", "A", 1, null);
        UUID ab = value(left, "A-B", "AB", 2, null);
        UUID c = value(right, "C", "C", 1, null);
        UUID bc = value(right, "B-C", "BC", 2, null);
        UUID product = product("CUP-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        attributesOfProduct.replaceAxes(product, List.of(new AxisSpec(left, 0, List.of(a, ab)),
                new AxisSpec(right, 1, List.of(c, bc))));

        assertThat(errorOf(() -> variants.generate(product, null))).isEqualTo(ErrorCode.VARIANT_SKU_ALREADY_EXISTS);
        assertThat(count(product)).isEqualTo(1);
    }

    @Test
    void aManualSkuOverridesTheRuleAndIsNormalised() {
        UUID product = productWithAxes();

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

    private UUID createWithAxes(String code, List<CreateProductCommand.Axis> axes) {
        newCode = code;
        return products.create(new CreateProductCommand(code, "Ly giay", "Paper cup", null, category, null, null,
                null, TaxClass.STANDARD, ProductKind.CUSTOMIZABLE, axes)).productId();
    }

    @Test
    void aProductCreatedWithAxesIsBornWithTheWholeMatrixAndNoPlaceholderSku() {
        String code = "CUP-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        // Listed colour first with no sort order: the list position decides, so COLOR is the first axis.
        UUID product = createWithAxes(code, List.of(
                new CreateProductCommand.Axis(color, null, List.of(red, clear)),
                new CreateProductCommand.Axis(capacity, null, List.of(cap16, cap12))));

        var all = variants.list(product, 0, 50).items();

        assertThat(all).extracting(VariantView::sku).containsExactly(
                code + "-CLEAR-12OZ", code + "-CLEAR-16OZ", code + "-RED-12OZ", code + "-RED-16OZ");
        assertThat(all).extracting(VariantView::sku).doesNotContain(code);
        assertThat(all).filteredOn(VariantView::defaultVariant).singleElement().satisfies(def -> {
            assertThat(def.sku()).isEqualTo(code + "-CLEAR-12OZ");
            assertThat(def.position()).isZero();
            assertThat(def.attributeSignature()).isEqualTo("CAPACITY=12OZ;COLOR=CLEAR");
            assertThat(def.name()).isEqualTo("Ly giay – Clear / 12 oz");
        });
        assertThat(all).allSatisfy(v -> assertThat(v.status()).isEqualTo(VariantStatus.DRAFT));
        assertThat(jdbc.queryForObject("select count(*) from inventory.inventory_items i join product.variants v "
                + "on v.sku = i.sku where v.product_id = ?", Long.class, product)).isEqualTo(4);
        assertThat(attributesOfProduct.get(product).axes()).extracting(ProductAttributeService.AxisView::attributeId)
                .containsExactly(color, capacity);
        // Generating again finds nothing missing.
        assertThat(variants.generate(product, null).created()).isEmpty();
    }

    @Test
    void aDeactivatedValueCannotBeAnOptionOfANewProduct() {
        deactivate(color, red);
        String code = "CUP-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        assertThat(errorOf(() -> createWithAxes(code, List.of(
                new CreateProductCommand.Axis(capacity, 0, List.of(cap12, cap16)),
                new CreateProductCommand.Axis(color, 1, List.of(clear, red)))))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void creatingWithInvalidAxesIsRefused() {
        String code = "CUP-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        // A value that belongs to another attribute is not an option of this axis.
        assertThat(errorOf(() -> createWithAxes(code, List.of(new CreateProductCommand.Axis(capacity, 0,
                List.of(clear)))))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void creatingWithTooLargeAMatrixIsRefused() {
        UUID wide = dictionary.create("WIDE", AttributeDataType.SELECT, details("Wide")).id();
        UUID tall = dictionary.create("TALL", AttributeDataType.SELECT, details("Tall")).id();
        List<UUID> wideValues = IntStream.range(0, 11).mapToObj(i -> value(wide, "W" + i, "W" + i, i, null)).toList();
        List<UUID> tallValues = IntStream.range(0, 10).mapToObj(i -> value(tall, "T" + i, "T" + i, i, null)).toList();
        String code = "CUP-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        assertThat(errorOf(() -> createWithAxes(code, List.of(new CreateProductCommand.Axis(wide, 0, wideValues),
                new CreateProductCommand.Axis(tall, 1, tallValues))))).isEqualTo(ErrorCode.VARIANT_MATRIX_TOO_LARGE);
    }

    private void deactivate(UUID attribute, UUID valueId) {
        var view = dictionary.get(attribute);
        var value = view.values().stream().filter(v -> v.id().equals(valueId)).findFirst().orElseThrow();
        dictionary.updateValue(attribute, valueId, value.version(),
                new AttributeValueDetails(value.label(), value.valueNumber(), value.hexColor(), value.sortOrder()),
                false);
    }

    @Test
    void generationSkipsADeactivatedValueAndCountsWhatItMakes() {
        UUID product = productWithAxes();
        deactivate(color, red);

        Generated generated = variants.generate(product, null);

        assertThat(variants.list(product, 0, 50).items()).flatExtracting(VariantView::options)
                .extracting(VariantOption::valueCode).doesNotContain("RED");
        assertThat(generated.created()).hasSize(1);
        assertThat(generated.skipped()).isZero();
        assertThat(count(product)).isEqualTo(2);
        assertThat(errorOf(() -> variants.generate(product, Map.of(color, List.of(red)))))
                .isEqualTo(ErrorCode.VARIANT_OPTIONS_INVALID);
        assertThat(variants.generate(product, null).skipped()).isEqualTo(2);
    }

    @Test
    void addingWithADeactivatedValueIsRefusedButAnUpdateMayKeepIt() {
        UUID product = productWithAxes();
        VariantView kept = variants.add(product, new NewVariant(null, null, List.of(cap16, red), null));
        VariantView other = variants.add(product, new NewVariant(null, null, List.of(cap16, clear), null));
        deactivate(color, red);

        assertThat(errorOf(() -> variants.add(product, new NewVariant(null, null, List.of(cap12, red), null))))
                .isEqualTo(ErrorCode.VARIANT_OPTIONS_INVALID);
        VariantView renamed = variants.update(product, kept.id(),
                new NewVariant(null, "Renamed", List.of(cap16, red), null));
        assertThat(renamed.name()).isEqualTo("Renamed");
        assertThat(errorOf(() -> variants.update(product, other.id(),
                new NewVariant(null, null, List.of(cap16, red), null)))).isEqualTo(ErrorCode.VARIANT_OPTIONS_INVALID);
    }

    @Test
    void theDefaultTakesTheFirstActiveCombination() {
        UUID product = productWithAxes();
        deactivate(capacity, cap12);

        variants.generate(product, null);

        assertThat(defaultOf(product).attributeSignature()).isEqualTo("CAPACITY=16OZ;COLOR=CLEAR");
        assertThat(count(product)).isEqualTo(2);
    }

    @Test
    void anAxisWithNoActiveOptionRefusesGenerationNamingTheAxis() {
        UUID product = productWithAxes();
        deactivate(color, clear);
        deactivate(color, red);

        BusinessException refused = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> variants.generate(product, null));
        assertThat(refused.errorCode()).isEqualTo(ErrorCode.VARIANT_OPTIONS_INVALID);
        assertThat(refused.getMessage()).contains("COLOR");
        assertThat(count(product)).isEqualTo(1);
    }

    @Test
    void theDraftDefaultCanBeGivenACombinationByUpdate() {
        UUID product = productWithAxes();
        VariantView def = defaultOf(product);
        assertThat(def.attributeSignature()).isEqualTo("DEFAULT");

        VariantView assigned = variants.update(product, def.id(), new NewVariant(null, null, List.of(cap16, red), null));

        assertThat(assigned.defaultVariant()).isTrue();
        assertThat(assigned.attributeSignature()).isEqualTo("CAPACITY=16OZ;COLOR=RED");
        assertThat(assigned.options()).extracting(VariantOption::valueCode).containsExactly("16OZ", "RED");
        assertThat(assigned.sku()).isEqualTo(def.sku());
    }

    @Test
    void aVariantWithoutOptionsIsRepairedByAnUpdateThatRederivesTheSignature() {
        UUID product = product("CUP-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        String sku = "X-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        VariantView legacy = variants.add(product, new NewVariant(sku, "x", null, null));
        assertThat(legacy.attributeSignature()).isEqualTo("SKU=" + sku);
        attributesOfProduct.replaceAxes(product, List.of(new AxisSpec(capacity, 0, List.of(cap12, cap16)),
                new AxisSpec(color, 1, List.of(clear, red))));

        VariantView repaired = variants.update(product, legacy.id(), new NewVariant(null, null, List.of(cap16, red), null));

        assertThat(repaired.attributeSignature()).isEqualTo("CAPACITY=16OZ;COLOR=RED");
        assertThat(repaired.sku()).isEqualTo(sku);
        assertThat(repaired.options()).extracting(VariantOption::valueCode).containsExactly("16OZ", "RED");
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

    /** The variant entries of the product's history, oldest first, as {@code TYPE:changed,fields}. */
    private List<String> variantHistory(UUID product) {
        return jdbc.queryForList("""
                SELECT change_type || ':' || array_to_string(changed_fields, ',') FROM product.product_change_log
                WHERE product_id = ? AND subject_type = 'VARIANT' ORDER BY occurred_at, id""", String.class, product);
    }

    @Test
    void generationWritesEveryNewRowAndTheDefaultsCombinationToTheHistory() {
        UUID product = productWithAxes();

        variants.generate(product, null);

        // The default variant was named after the product; taking a combination renames it.
        assertThat(variantHistory(product)).containsExactly("VARIANT_UPDATED:name,attributeSignature",
                "VARIANT_ADDED:", "VARIANT_ADDED:", "VARIANT_ADDED:");
        // Nothing to create, nothing recorded.
        variants.generate(product, null);
        assertThat(variantHistory(product)).hasSize(4);
    }

    @Test
    void aCombinationIsTakenWhateverOrderItsStoredSignatureIsSpelledIn() {
        UUID product = productWithAxes();
        String code = newCode;
        variants.generate(product, null);
        // Rows written before the signature was sorted by attribute code, as the deployed seed has them.
        jdbc.update("""
                UPDATE product.variants SET attribute_signature =
                       split_part(attribute_signature, ';', 2) || ';' || split_part(attribute_signature, ';', 1)
                WHERE product_id = ?""", product);
        entityManager.clear();

        Generated again = variants.generate(product, null);
        assertThat(again.created()).isEmpty();
        assertThat(again.skipped()).isEqualTo(4);
        assertThat(errorOf(() -> variants.add(product, new NewVariant(code + "-BY-HAND", null, List.of(cap16, red), null))))
                .isEqualTo(ErrorCode.VARIANT_SKU_ALREADY_EXISTS);
        assertThat(count(product)).isEqualTo(4);
    }
}
