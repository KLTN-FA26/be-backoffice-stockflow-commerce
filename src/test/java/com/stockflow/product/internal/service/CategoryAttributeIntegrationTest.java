package com.stockflow.product.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.product.internal.domain.AttributeDetails;
import com.stockflow.product.internal.service.CategoryAttributeService.CategoryAttributeEntry;
import com.stockflow.product.internal.service.CategoryAttributeService.CategoryAttributeView;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.stockflow.product.internal.domain.AttributeDataType.TEXT;
import static com.stockflow.product.internal.domain.AttributeRole.DESCRIPTIVE;
import static com.stockflow.product.internal.domain.AttributeRole.VARIANT_AXIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** A category's suggested attribute set through its service, on Postgres with the demo seed. Every test rolls back. */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class CategoryAttributeIntegrationTest {

    private static final UUID SOFA = DemoData.demoId("demo:cat:SOFA");
    private static final UUID COLOR = DemoData.demoId("demo:attr:COLOR");
    private static final UUID MATERIAL = DemoData.demoId("demo:attr:MATERIAL");
    private static final UUID LENGTH = DemoData.demoId("demo:attr:LENGTH");

    @Autowired CategoryAttributeService service;
    @Autowired AttributeDictionaryService dictionary;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    private static ErrorCode errorOf(Supplier<?> call) {
        try {
            call.get();
        } catch (BusinessException e) {
            return e.errorCode();
        }
        throw new AssertionError("expected a BusinessException");
    }

    private UUID rowId(UUID category, UUID attribute) {
        return jdbc.queryForObject("select id from product.category_attributes where category_id = ? and attribute_id = ?",
                UUID.class, category, attribute);
    }

    private void deactivate(UUID attributeId) {
        long version = jdbc.queryForObject("select version from product.attributes where id = ?", Long.class, attributeId);
        var a = dictionary.get(attributeId);
        dictionary.update(attributeId, version,
                new AttributeDetails(a.name(), a.unit(), a.filterable(), a.searchable(), a.sortOrder()), false);
    }

    @Test
    void listsTheDemoSet() {
        assertThat(service.list(SOFA)).extracting(CategoryAttributeView::attributeCode, CategoryAttributeView::defaultRole)
                .containsExactlyInAnyOrder(tuple("COLOR", VARIANT_AXIS), tuple("MATERIAL", DESCRIPTIVE));
    }

    @Test
    void replacingKeepsAnExistingPairAndChangesItInPlace() {
        UUID rowId = rowId(SOFA, MATERIAL);
        service.replace(SOFA, List.of(new CategoryAttributeEntry(COLOR, DESCRIPTIVE, false, 0),
                new CategoryAttributeEntry(MATERIAL, DESCRIPTIVE, true, 1)));
        entityManager.flush();
        assertThat(rowId(SOFA, MATERIAL)).isEqualTo(rowId);
        assertThat(service.list(SOFA)).extracting(CategoryAttributeView::attributeCode, CategoryAttributeView::defaultRole)
                .containsExactly(tuple("COLOR", DESCRIPTIVE), tuple("MATERIAL", DESCRIPTIVE));
        assertThat(service.list(SOFA)).extracting(CategoryAttributeView::required).containsExactly(false, true);
    }

    @Test
    void anAttributeLeftOutIsRemovedAndANewOneAdded() {
        service.replace(SOFA, List.of(new CategoryAttributeEntry(LENGTH, VARIANT_AXIS, false, 0)));
        entityManager.flush();
        assertThat(service.list(SOFA)).extracting(CategoryAttributeView::attributeCode).containsExactly("LENGTH");
    }

    @Test
    void theSameAttributeTwiceInOneRequestIsRefused() {
        assertThat(errorOf(() -> service.replace(SOFA, List.of(
                new CategoryAttributeEntry(COLOR, VARIANT_AXIS, false, 0),
                new CategoryAttributeEntry(COLOR, DESCRIPTIVE, false, 1))))).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void aTextAttributeCannotBeAVariantAxis() {
        var text = dictionary.create("NOTE", TEXT, new AttributeDetails("Note", null, false, false, 0));
        assertThat(errorOf(() -> service.replace(SOFA, List.of(
                new CategoryAttributeEntry(text.id(), VARIANT_AXIS, false, 0))))).isEqualTo(ErrorCode.VALIDATION_FAILED);
        service.replace(SOFA, List.of(new CategoryAttributeEntry(text.id(), DESCRIPTIVE, false, 0)));
        assertThat(service.list(SOFA)).extracting(CategoryAttributeView::attributeCode).containsExactly("NOTE");
    }

    @Test
    void anInactiveAttributeCannotBeNewlyAdded() {
        deactivate(LENGTH);
        assertThat(errorOf(() -> service.replace(SOFA, List.of(
                new CategoryAttributeEntry(LENGTH, VARIANT_AXIS, false, 0))))).isEqualTo(ErrorCode.VALIDATION_FAILED);

        deactivate(COLOR);
        service.replace(SOFA, List.of(new CategoryAttributeEntry(COLOR, VARIANT_AXIS, true, 0)));
        assertThat(service.list(SOFA)).extracting(CategoryAttributeView::attributeCode).containsExactly("COLOR");
    }

    @Test
    void unknownCategoryOrAttributeIsNotFound() {
        assertThat(errorOf(() -> service.replace(UUID.randomUUID(), List.of()))).isEqualTo(ErrorCode.CATEGORY_NOT_FOUND);
        assertThat(errorOf(() -> service.list(UUID.randomUUID()))).isEqualTo(ErrorCode.CATEGORY_NOT_FOUND);
        assertThat(errorOf(() -> service.replace(SOFA, List.of(
                new CategoryAttributeEntry(UUID.randomUUID(), DESCRIPTIVE, false, 0))))).isEqualTo(ErrorCode.ATTRIBUTE_NOT_FOUND);
    }

    @Test
    void anEmptyListClearsTheSet() {
        service.replace(SOFA, List.of());
        entityManager.flush();
        assertThat(service.list(SOFA)).isEmpty();
    }
}
