package com.stockflow.product.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.product.api.CreateProductCommand;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.TaxClass;
import com.stockflow.product.internal.domain.Attribute;
import com.stockflow.product.internal.domain.AttributeDataType;
import com.stockflow.product.internal.domain.AttributeDetails;
import com.stockflow.product.internal.domain.AttributeRepository;
import com.stockflow.product.internal.domain.AttributeValueDetails;
import com.stockflow.product.internal.domain.AxisOption;
import com.stockflow.product.internal.domain.AxisSpec;
import com.stockflow.product.internal.domain.DescriptiveAttribute;
import com.stockflow.product.internal.domain.DescriptiveSpec;
import com.stockflow.product.internal.domain.DescriptiveValue;
import com.stockflow.product.internal.domain.ProductAttributeSet;
import com.stockflow.product.internal.domain.ProductAttributeSetRepository;
import com.stockflow.product.internal.service.AttributeDictionaryService;
import com.stockflow.product.internal.service.AttributeDictionaryService.AttributeView;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.stockflow.product.internal.domain.AttributeDataType.BOOLEAN;
import static com.stockflow.product.internal.domain.AttributeDataType.MULTISELECT;
import static com.stockflow.product.internal.domain.AttributeDataType.NUMBER;
import static com.stockflow.product.internal.domain.AttributeDataType.SELECT;
import static com.stockflow.product.internal.domain.AttributeDataType.TEXT;
import static com.stockflow.support.DemoData.PRODUCT_SOFA;
import static com.stockflow.support.DemoData.demoId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * A product's attribute set against Postgres: load, round trip, rows left alone when nothing
 * changed, in-place change of a single descriptive value, removals before inserts, and the load
 * statement count.
 */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class ProductAttributeSetRepositoryAdapterIntegrationTest {

    @Autowired ProductAttributeSetRepository repository;
    @Autowired ProductService products;
    @Autowired AttributeDictionaryService dictionary;
    @Autowired AttributeRepository attributes;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CurrentUserProvider users;

    private String suffix;
    private UUID productId;

    @BeforeEach
    void draftProduct() {
        UUID user = ReferenceRows.user(entityManager);
        when(users.current()).thenReturn(Optional.of(new CurrentUser(user, "test-" + user, Set.of(), Set.of(),
                DataScope.ALL, Set.of())));
        suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        productId = products.create(new CreateProductCommand("SET-" + suffix, "Ten " + suffix, "Name " + suffix, null,
                null, null, null, null, TaxClass.STANDARD, ProductKind.CUSTOMIZABLE, List.of())).productId();
    }

    private AttributeView attribute(String code, AttributeDataType type, String... valueCodes) {
        AttributeView view = dictionary.create(code + "_" + suffix, type, new AttributeDetails(code, null, true, false, 0));
        for (String valueCode : valueCodes) {
            view = dictionary.addValue(view.id(), valueCode, new AttributeValueDetails(valueCode, null, null, 0));
        }
        return view;
    }

    private static UUID value(AttributeView view, String code) {
        return view.values().stream().filter(v -> v.code().equals(code)).findFirst().orElseThrow().id();
    }

    private Map<UUID, Attribute> dictionaryOf(AttributeView... views) {
        return attributes.findAllById(List.of(views).stream().map(AttributeView::id).toList()).stream()
                .collect(Collectors.toMap(Attribute::id, Function.identity()));
    }

    private ProductAttributeSet draftSet() {
        return repository.load(productId, ProductStatus.DRAFT);
    }

    private void persisted(ProductAttributeSet set) {
        repository.save(set);
        entityManager.flush();
        entityManager.clear();
    }

    /** Only this product's rows: the demo seed has attribute rows of its own. */
    private static String ownRows(String table) {
        return table.equals("product_attributes")
                ? "select t.* from product.product_attributes t where t.product_id = ?"
                : "select t.* from product." + table + " t join product.product_attributes pa"
                        + " on pa.id = t.product_attribute_id where pa.product_id = ?";
    }

    private long rows(String table) {
        return jdbc.queryForObject("select count(*) from (" + ownRows(table) + ") r", Long.class, productId);
    }

    private Map<UUID, Long> versions(String table) {
        return jdbc.query("select r.id, r.version from (" + ownRows(table) + ") r", rs -> {
            Map<UUID, Long> result = new java.util.HashMap<>();
            while (rs.next()) {
                result.put(rs.getObject(1, UUID.class), rs.getLong(2));
            }
            return result;
        }, productId);
    }

    @Test
    void theDemoSofaLoadsWithItsAxisAndDescriptive() {
        var set = repository.load(PRODUCT_SOFA, ProductStatus.PUBLISHED);

        assertThat(set.axes()).singleElement()
                .satisfies(a -> assertThat(a.attributeId()).isEqualTo(demoId("demo:attr:COLOR")));
        assertThat(set.descriptives()).singleElement()
                .satisfies(d -> assertThat(d.values()).extracting(DescriptiveValue::valueId)
                        .containsExactly(demoId("demo:val:MATERIAL:FABRIC")));
        assertThat(repository.valueIdsUsedByVariants(PRODUCT_SOFA)).containsExactly(demoId("demo:val:COLOR:GREY"));
    }

    @Test
    void aSetRoundTrips() {
        var size = attribute("SIZE", SELECT, "S", "M");
        var material = attribute("MATERIAL", SELECT, "FABRIC", "OAK");
        var tags = attribute("TAGS", MULTISELECT, "A", "B");
        var note = attribute("NOTE", TEXT);
        var weight = attribute("WEIGHT", NUMBER);
        var flag = attribute("FLAG", BOOLEAN);
        var dict = dictionaryOf(size, material, tags, note, weight, flag);

        var set = draftSet();
        set.replaceAxes(List.of(new AxisSpec(size.id(), 0, List.of(value(size, "S"), value(size, "M")))), dict,
                Set.of(), Identifiers::newId);
        set.replaceDescriptives(List.of(
                new DescriptiveSpec(material.id(), 0, List.of(value(material, "FABRIC")), null, null, null),
                new DescriptiveSpec(tags.id(), 1, List.of(value(tags, "A"), value(tags, "B")), null, null, null),
                new DescriptiveSpec(note.id(), 2, List.of(), "hand wash", null, null),
                new DescriptiveSpec(weight.id(), 3, List.of(), null, new BigDecimal("12.5"), null),
                new DescriptiveSpec(flag.id(), 4, List.of(), null, null, true)), dict, Identifiers::newId);
        persisted(set);

        var loaded = draftSet();

        assertThat(loaded.axes()).singleElement().satisfies(a -> {
            assertThat(a.attributeId()).isEqualTo(size.id());
            assertThat(a.options()).extracting(AxisOption::valueId)
                    .containsExactlyInAnyOrder(value(size, "S"), value(size, "M"));
        });
        assertThat(loaded.descriptives()).extracting(DescriptiveAttribute::attributeId)
                .containsExactly(material.id(), tags.id(), note.id(), weight.id(), flag.id());
        assertThat(loaded.descriptives().get(0).values()).extracting(DescriptiveValue::valueId)
                .containsExactly(value(material, "FABRIC"));
        assertThat(loaded.descriptives().get(1).values()).extracting(DescriptiveValue::valueId)
                .containsExactlyInAnyOrder(value(tags, "A"), value(tags, "B"));
        assertThat(loaded.descriptives().get(2).values()).extracting(DescriptiveValue::text).containsExactly("hand wash");
        assertThat(loaded.descriptives().get(3).values().getFirst().number()).isEqualByComparingTo("12.5");
        assertThat(loaded.descriptives().get(4).values()).extracting(DescriptiveValue::bool).containsExactly(true);
    }

    @Test
    void savingAnUnchangedSetTouchesNoRow() {
        var size = attribute("SIZE", SELECT, "S", "M");
        var material = attribute("MATERIAL", SELECT, "FABRIC", "OAK");
        var tags = attribute("TAGS", MULTISELECT, "A", "B");
        var weight = attribute("WEIGHT", NUMBER);
        var dict = dictionaryOf(size, material, tags, weight);
        List<AxisSpec> axes = List.of(new AxisSpec(size.id(), 0, List.of(value(size, "S"), value(size, "M"))));
        List<DescriptiveSpec> descriptives = List.of(
                new DescriptiveSpec(material.id(), 0, List.of(value(material, "FABRIC")), null, null, null),
                new DescriptiveSpec(tags.id(), 1, List.of(value(tags, "A"), value(tags, "B")), null, null, null),
                new DescriptiveSpec(weight.id(), 2, List.of(), null, new BigDecimal("12.5"), null));
        var first = draftSet();
        first.replaceAxes(axes, dict, Set.of(), Identifiers::newId);
        first.replaceDescriptives(descriptives, dict, Identifiers::newId);
        persisted(first);
        var before = List.of(versions("product_attributes"), versions("product_attribute_options"),
                versions("product_descriptive_values"));

        var again = draftSet();
        again.replaceAxes(axes, dict, Set.of(), Identifiers::newId);
        again.replaceDescriptives(descriptives, dict, Identifiers::newId);
        repository.save(again);
        entityManager.flush();
        entityManager.clear();

        assertThat(List.of(versions("product_attributes"), versions("product_attribute_options"),
                versions("product_descriptive_values"))).isEqualTo(before);
        assertThat(before.get(0)).hasSize(4);
        assertThat(before.get(1)).hasSize(2);
        assertThat(before.get(2)).hasSize(4);
    }

    @Test
    void aSingleDescriptiveValueIsChangedInPlace() {
        var material = attribute("MATERIAL", SELECT, "FABRIC", "OAK");
        var dict = dictionaryOf(material);
        var first = draftSet();
        first.replaceDescriptives(List.of(
                new DescriptiveSpec(material.id(), 0, List.of(value(material, "FABRIC")), null, null, null)),
                dict, Identifiers::newId);
        persisted(first);
        UUID rowId = jdbc.queryForObject("select r.id from (" + ownRows("product_descriptive_values") + ") r",
                UUID.class, productId);

        var second = draftSet();
        second.replaceDescriptives(List.of(
                new DescriptiveSpec(material.id(), 0, List.of(value(material, "OAK")), null, null, null)),
                dict, Identifiers::newId);
        persisted(second);

        assertThat(rows("product_descriptive_values")).isEqualTo(1);
        assertThat(jdbc.queryForList("select r.id from (" + ownRows("product_descriptive_values") + ") r",
                UUID.class, productId)).containsExactly(rowId);
        assertThat(jdbc.queryForList("select r.attribute_value_id from (" + ownRows("product_descriptive_values")
                + ") r", UUID.class, productId)).containsExactly(value(material, "OAK"));
    }

    @Test
    void anOptionAVariantStartedUsingIsRefusedAsInUse() {
        var size = attribute("SIZE", SELECT, "S", "M");
        var dict = dictionaryOf(size);
        var first = draftSet();
        first.replaceAxes(List.of(new AxisSpec(size.id(), 0, List.of(value(size, "S"), value(size, "M")))), dict,
                Set.of(), Identifiers::newId);
        persisted(first);
        UUID optionS = jdbc.queryForObject("select o.id from product.product_attribute_options o where o.attribute_value_id = ?"
                + " and o.product_attribute_id in (select id from product.product_attributes where product_id = ?)",
                UUID.class, value(size, "S"), productId);
        UUID variant = Identifiers.newId();
        jdbc.update("insert into product.variants (id, product_id, sku, name, attribute_signature) values (?, ?, ?, ?, ?)",
                variant, productId, "V-" + suffix, "v", "SIG-" + suffix);
        jdbc.update("insert into product.variant_attribute_values (id, variant_id, product_attribute_option_id)"
                + " values (?, ?, ?)", Identifiers.newId(), variant, optionS);

        // The service's used-values check ran before the variant existed: this is the race the FK nets.
        var second = draftSet();
        second.replaceAxes(List.of(new AxisSpec(size.id(), 0, List.of(value(size, "M")))), dict, Set.of(),
                Identifiers::newId);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.save(second))
                .isInstanceOfSatisfying(com.stockflow.common.error.ConflictException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(com.stockflow.common.error.ErrorCode.ATTRIBUTE_IN_USE));
    }

    /** Regression guard: Hibernate flushes inserts before deletes, so a save must flush removals first. */
    @Test
    void removalsAreFlushedBeforeInserts() {
        var capacity = attribute("CAPACITY", SELECT, "12OZ", "16OZ", "22OZ");
        var tags = attribute("TAGS", MULTISELECT, "A", "B", "C");
        var dict = dictionaryOf(capacity, tags);
        var first = draftSet();
        first.replaceAxes(List.of(new AxisSpec(capacity.id(), 0, List.of(value(capacity, "12OZ"), value(capacity, "16OZ")))),
                dict, Set.of(), Identifiers::newId);
        first.replaceDescriptives(List.of(
                new DescriptiveSpec(tags.id(), 0, List.of(value(tags, "A"), value(tags, "B")), null, null, null)),
                dict, Identifiers::newId);
        persisted(first);

        var second = draftSet();
        second.replaceAxes(List.of(new AxisSpec(capacity.id(), 0, List.of(value(capacity, "16OZ"), value(capacity, "22OZ")))),
                dict, Set.of(), Identifiers::newId);
        second.replaceDescriptives(List.of(
                new DescriptiveSpec(tags.id(), 0, List.of(value(tags, "B"), value(tags, "C")), null, null, null)),
                dict, Identifiers::newId);
        persisted(second);

        assertThat(jdbc.queryForList("select r.attribute_value_id from (" + ownRows("product_attribute_options") + ") r",
                UUID.class, productId))
                .containsExactlyInAnyOrder(value(capacity, "16OZ"), value(capacity, "22OZ"));
        assertThat(jdbc.queryForList("select r.attribute_value_id from (" + ownRows("product_descriptive_values") + ") r",
                UUID.class, productId))
                .containsExactlyInAnyOrder(value(tags, "B"), value(tags, "C"));
    }

    @Test
    void aDroppedAttributeTakesItsRowsWithIt() {
        var size = attribute("SIZE", SELECT, "S");
        var dict = dictionaryOf(size);
        var first = draftSet();
        first.replaceAxes(List.of(new AxisSpec(size.id(), 0, List.of(value(size, "S")))), dict, Set.of(),
                Identifiers::newId);
        persisted(first);

        var second = draftSet();
        second.replaceAxes(List.of(), dict, Set.of(), Identifiers::newId);
        persisted(second);

        assertThat(draftSet().axes()).isEmpty();
        assertThat(rows("product_attribute_options")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from product.product_attributes where product_id = ?",
                Long.class, productId)).isZero();
    }

    @Test
    void loadingIsTwoQueries() {
        var size = attribute("SIZE", SELECT, "S", "M");
        var material = attribute("MATERIAL", SELECT, "FABRIC");
        var dict = dictionaryOf(size, material);
        var set = draftSet();
        set.replaceAxes(List.of(new AxisSpec(size.id(), 0, List.of(value(size, "S"), value(size, "M")))), dict,
                Set.of(), Identifiers::newId);
        set.replaceDescriptives(List.of(
                new DescriptiveSpec(material.id(), 0, List.of(value(material, "FABRIC")), null, null, null)),
                dict, Identifiers::newId);
        persisted(set);

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            var loaded = draftSet();
            assertThat(loaded.axes().getFirst().options()).hasSize(2);
            assertThat(loaded.descriptives().getFirst().values()).hasSize(1);
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }
}
