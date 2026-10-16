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
import com.stockflow.product.internal.domain.AttributeValueDetails;
import com.stockflow.product.internal.domain.AxisSpec;
import com.stockflow.product.internal.domain.DescriptiveSpec;
import com.stockflow.product.internal.service.ProductAttributeService.AxisView;
import com.stockflow.product.internal.service.ProductAttributeService.DescriptiveView;
import com.stockflow.product.internal.service.ProductAttributeService.OptionView;
import com.stockflow.product.internal.service.ProductAttributeService.ProductAttributesView;
import com.stockflow.product.internal.service.ProductAttributeService.ValueRef;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import com.stockflow.support.ReferenceRows;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.stockflow.support.DemoData.PRODUCT_SOFA;
import static com.stockflow.support.DemoData.demoId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

/** A product's axes and descriptive attributes through the service, on Postgres with the demo seed. Every test rolls back. */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class ProductAttributeIntegrationTest {

    private static final UUID COLOR = demoId("demo:attr:COLOR");
    private static final UUID GREY = demoId("demo:val:COLOR:GREY");
    private static final UUID LENGTH = demoId("demo:attr:LENGTH");
    private static final UUID LENGTH_160 = demoId("demo:val:LENGTH:160");
    private static final UUID MATERIAL = demoId("demo:attr:MATERIAL");
    private static final UUID FABRIC = demoId("demo:val:MATERIAL:FABRIC");
    private static final UUID OAK = demoId("demo:val:MATERIAL:OAK");

    @Autowired ProductAttributeService service;
    @Autowired ProductService products;
    @Autowired AttributeDictionaryService dictionary;
    @Autowired ProductTaxonomyService taxonomy;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CurrentUserProvider users;

    private UUID user;

    @BeforeEach
    void actor() {
        user = ReferenceRows.user(entityManager);
        when(users.current()).thenReturn(Optional.of(new CurrentUser(user, "test-" + user, Set.of(), Set.of(),
                DataScope.ALL, Set.of())));
    }

    private UUID draftProduct() {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        // A product cannot be submitted without a category (BR-PRD-001).
        UUID category = taxonomy.createCategory("CAT-" + suffix, null,
                new ProductTaxonomyService.CategoryDetails("Ly", 0, null, null, null, true)).id();
        return products.create(new CreateProductCommand("ATTR-" + suffix, "Ten " + suffix, "Name " + suffix, null,
                category, null, null, null, TaxClass.STANDARD, ProductKind.CUSTOMIZABLE)).productId();
    }

    private static ErrorCode errorOf(Supplier<?> call) {
        try {
            call.get();
        } catch (BusinessException e) {
            return e.errorCode();
        }
        throw new AssertionError("expected a BusinessException");
    }

    private static AxisSpec axis(UUID attribute, int order, UUID... values) {
        return new AxisSpec(attribute, order, List.of(values));
    }

    private static DescriptiveSpec select(UUID attribute, int order, UUID value) {
        return new DescriptiveSpec(attribute, order, List.of(value), null, null, null);
    }

    private UUID addBlue() {
        return dictionary.addValue(COLOR, "BLUE", new AttributeValueDetails("Xanh", null, "#0000FF", 1)).values()
                .stream().filter(v -> v.code().equals("BLUE")).findFirst().orElseThrow().id();
    }

    private UUID addColor(String code, int sortOrder) {
        return dictionary.addValue(COLOR, code, new AttributeValueDetails(code, null, "#0000FF", sortOrder)).values()
                .stream().filter(v -> v.code().equals(code)).findFirst().orElseThrow().id();
    }

    @Test
    void optionsComeBackInDictionaryOrderWhateverTheRequestOrder() {
        UUID product = draftProduct();
        UUID late = addColor("ZZ_LATE", 90);
        UUID early = addColor("ZZ_EARLY", 80);
        UUID tieB = addColor("ZZ_TIE_B", 85);
        UUID tieA = addColor("ZZ_TIE_A", 85);

        ProductAttributesView asked = service.replaceAxes(product, List.of(axis(COLOR, 0, late, tieB, early, tieA)));
        assertThat(asked.axes().getFirst().options()).extracting(OptionView::code)
                .containsExactly("ZZ_EARLY", "ZZ_TIE_A", "ZZ_TIE_B", "ZZ_LATE");

        service.replaceAxes(product, List.of(axis(COLOR, 0, early, tieA, tieB, late)));
        entityManager.flush();
        entityManager.clear();
        assertThat(service.get(product).axes().getFirst().options()).extracting(OptionView::code)
                .containsExactly("ZZ_EARLY", "ZZ_TIE_A", "ZZ_TIE_B", "ZZ_LATE");
    }

    @Test
    void getShowsTheDemoSofa() {
        ProductAttributesView view = service.get(PRODUCT_SOFA);

        assertThat(view.productId()).isEqualTo(PRODUCT_SOFA);
        assertThat(view.axes()).singleElement().satisfies(a -> {
            assertThat(a.code()).isEqualTo("COLOR");
            assertThat(a.options()).extracting(OptionView::code, OptionView::usedByVariant)
                    .containsExactly(tuple("GREY", true));
            assertThat(a.options().getFirst().hexColor()).isEqualTo("#8A8A8A");
        });
        assertThat(view.descriptives()).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo("MATERIAL");
            assertThat(d.values()).extracting(ValueRef::code).containsExactly("FABRIC");
            assertThat(d.text()).isNull();
        });
    }

    @Test
    void aDraftProductTakesAxesAndDescriptives() {
        UUID product = draftProduct();
        assertThat(service.get(product).axes()).isEmpty();

        service.replaceAxes(product, List.of(axis(COLOR, 0, GREY), axis(LENGTH, 1, LENGTH_160)));
        service.replaceDescriptives(product, List.of(select(MATERIAL, 0, OAK)));
        entityManager.flush();
        entityManager.clear();

        ProductAttributesView view = service.get(product);
        assertThat(view.axes()).extracting(AxisView::code).containsExactly("COLOR", "LENGTH");
        assertThat(view.axes().get(1).options()).singleElement().satisfies(o ->
                assertThat(o.valueNumber()).isEqualByComparingTo("160"));
        assertThat(view.axes()).flatExtracting(AxisView::options).extracting(OptionView::usedByVariant)
                .containsOnly(false);
        assertThat(view.descriptives()).extracting(DescriptiveView::code).containsExactly("MATERIAL");
        assertThat(view.descriptives().getFirst().values()).extracting(ValueRef::code).containsExactly("OAK");
    }

    @Test
    void theSofaCanGainAnOptionButNotAnAxis() {
        UUID blue = addBlue();

        ProductAttributesView grown = service.replaceAxes(PRODUCT_SOFA, List.of(axis(COLOR, 0, GREY, blue)));
        assertThat(grown.axes().getFirst().options()).extracting(OptionView::code).containsExactlyInAnyOrder("GREY", "BLUE");

        assertThat(errorOf(() -> service.replaceAxes(PRODUCT_SOFA,
                List.of(axis(COLOR, 0, GREY), axis(LENGTH, 1, LENGTH_160))))).isEqualTo(ErrorCode.PRODUCT_AXES_LOCKED);
    }

    @Test
    void removingTheSofasUsedOptionIsRefused() {
        UUID blue = addBlue();

        assertThat(errorOf(() -> service.replaceAxes(PRODUCT_SOFA, List.of(axis(COLOR, 0, blue)))))
                .isEqualTo(ErrorCode.ATTRIBUTE_IN_USE);
    }

    @Test
    void unknownProductOrAttributeIsNotFound() {
        assertThat(errorOf(() -> service.get(UUID.randomUUID()))).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
        assertThat(errorOf(() -> service.replaceAxes(UUID.randomUUID(), List.of()))).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);
        assertThat(errorOf(() -> service.replaceDescriptives(UUID.randomUUID(), List.of())))
                .isEqualTo(ErrorCode.PRODUCT_NOT_FOUND);

        UUID product = draftProduct();
        assertThat(errorOf(() -> service.replaceAxes(product, List.of(axis(UUID.randomUUID(), 0, GREY)))))
                .isEqualTo(ErrorCode.ATTRIBUTE_NOT_FOUND);
        assertThat(errorOf(() -> service.replaceDescriptives(product, List.of(select(UUID.randomUUID(), 0, FABRIC)))))
                .isEqualTo(ErrorCode.ATTRIBUTE_NOT_FOUND);
    }

    @Test
    void aPendingProductAcceptsNoChange() {
        UUID product = draftProduct();
        products.submit(product, user);

        assertThat(errorOf(() -> service.replaceDescriptives(product, List.of(select(MATERIAL, 0, FABRIC)))))
                .isEqualTo(ErrorCode.INVALID_PRODUCT_STATUS_TRANSITION);
        assertThat(errorOf(() -> service.replaceAxes(product, List.of(axis(COLOR, 0, GREY)))))
                .isEqualTo(ErrorCode.INVALID_PRODUCT_STATUS_TRANSITION);
    }

    /** product UPDATED rows in the history, oldest first, each as its changed fields joined. */
    private List<String> updates(UUID product) {
        return jdbc.queryForList("""
                SELECT array_to_string(changed_fields, ',') FROM product.product_change_log
                WHERE product_id = ? AND change_type = 'UPDATED' ORDER BY occurred_at, id""", String.class, product);
    }

    @Test
    void aChangedSetIsWrittenToTheHistoryAndAnUnchangedOneIsNot() {
        UUID product = draftProduct();
        UUID blue = addBlue();

        service.replaceAxes(product, List.of(axis(COLOR, 0, GREY)));
        service.replaceAxes(product, List.of(axis(COLOR, 0, GREY)));
        service.replaceAxes(product, List.of(axis(COLOR, 0, blue, GREY)));
        // The same axes again, the options in another order: nothing changed.
        service.replaceAxes(product, List.of(axis(COLOR, 0, GREY, blue)));
        service.replaceDescriptives(product, List.of(select(MATERIAL, 0, OAK)));
        service.replaceDescriptives(product, List.of(select(MATERIAL, 0, OAK)));
        service.replaceDescriptives(product, List.of(select(MATERIAL, 0, FABRIC)));

        assertThat(updates(product)).containsExactly("axes", "axes", "descriptiveAttributes", "descriptiveAttributes");
    }
}
