package com.stockflow.product.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.product.internal.domain.AttributeDetails;
import com.stockflow.product.internal.domain.AttributeValueDetails;
import com.stockflow.product.internal.service.AttributeDictionaryService.AttributeValueView;
import com.stockflow.product.internal.service.AttributeDictionaryService.AttributeView;
import com.stockflow.support.DemoData;
import com.stockflow.support.IntegrationTest;
import com.stockflow.support.PostgresContainer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static com.stockflow.product.internal.domain.AttributeDataType.SELECT;
import static org.assertj.core.api.Assertions.assertThat;

/** The attribute dictionary through its service, on Postgres with the demo seed. Every test rolls back. */
@IntegrationTest
@Import(PostgresContainer.class)
@Transactional
class AttributeDictionaryIntegrationTest {

    @Autowired AttributeDictionaryService service;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;

    private static AttributeDetails details() {
        return new AttributeDetails("Test attribute", null, true, false, 1);
    }

    private static AttributeValueDetails label(String label) {
        return new AttributeValueDetails(label, null, null, 0);
    }

    private static ErrorCode errorOf(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e.errorCode();
        }
        throw new AssertionError("expected a BusinessException");
    }

    @Test
    void createListAndGet() {
        var created = service.create("capacity", SELECT, new AttributeDetails("Dung tích", "oz", true, false, 1));
        service.addValue(created.id(), "12OZ", new AttributeValueDetails("12 oz", new BigDecimal("12"), null, 0));
        assertThat(service.get(created.id()).values()).extracting(AttributeValueView::code).containsExactly("12OZ");
        assertThat(service.list("capac", null, null, 0, 20, null).items())
                .extracting(AttributeView::code).contains("CAPACITY");
    }

    @Test
    void aDuplicateCodeIsAConflict() {
        service.create("LID", SELECT, details());
        assertThat(errorOf(() -> service.create("lid", SELECT, details())))
                .isEqualTo(ErrorCode.ATTRIBUTE_CODE_ALREADY_EXISTS);
    }

    @Test
    void aStaleVersionIsAConflict() {
        var created = service.create("STALE", SELECT, details());
        assertThat(errorOf(() -> service.update(created.id(), created.version() + 1, details(), true)))
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
    }

    @Test
    void anUnknownAttributeIsNotFound() {
        assertThat(errorOf(() -> service.get(UUID.randomUUID()))).isEqualTo(ErrorCode.ATTRIBUTE_NOT_FOUND);
    }

    @Test
    void deletingAnAttributeInUseIsRefused() {
        var color = DemoData.demoId("demo:attr:COLOR");
        assertThat(errorOf(() -> service.delete(color, service.get(color).version())))
                .isEqualTo(ErrorCode.ATTRIBUTE_IN_USE);
        var grey = DemoData.demoId("demo:val:COLOR:GREY");
        long greyVersion = service.get(color).values().stream().filter(v -> v.id().equals(grey))
                .findFirst().orElseThrow().version();
        assertThat(errorOf(() -> service.deleteValue(color, grey, greyVersion)))
                .isEqualTo(ErrorCode.ATTRIBUTE_IN_USE);
    }

    @Test
    void deletingAnUnusedAttributeDeletesItsValuesToo() {
        var created = service.create("WALL", SELECT, details());
        var a = service.addValue(created.id(), "SINGLE", label("Một lớp"));
        service.delete(a.id(), a.version());
        entityManager.flush();
        entityManager.clear();
        assertThat(jdbc.queryForObject("select count(*) from product.attribute_values where attribute_id = ?",
                Long.class, a.id())).isZero();
        assertThat(errorOf(() -> service.get(a.id()))).isEqualTo(ErrorCode.ATTRIBUTE_NOT_FOUND);
    }

    @Test
    void deletingAnUnusedValueRemovesIt() {
        var created = service.create("PLY", SELECT, details());
        var a = service.addValue(created.id(), "ONE", label("One"));
        var value = a.values().get(0);
        var after = service.deleteValue(a.id(), value.id(), value.version());
        assertThat(after.values()).isEmpty();
    }

    @Test
    void deactivatingKeepsTheRowAndItsValues() {
        var created = service.create("RETIRE", SELECT, details());
        var a = service.addValue(created.id(), "KEEP", label("Keep"));
        service.update(a.id(), a.version(), details(), false);
        entityManager.flush();
        entityManager.clear();
        var reloaded = service.get(a.id());
        assertThat(reloaded.active()).isFalse();
        assertThat(reloaded.values()).extracting(AttributeValueView::code).containsExactly("KEEP");
    }

    @Test
    void updatingAValueChangesItsLabel() {
        var created = service.create("FINISH", SELECT, details());
        var a = service.addValue(created.id(), "MATTE", label("Matte"));
        var value = a.values().get(0);
        var updated = service.updateValue(a.id(), value.id(), value.version(), label("Matt"), true);
        assertThat(updated.values()).extracting(AttributeValueView::label).containsExactly("Matt");
    }

    @Test
    void listingAPageLoadsValuesInOneQuery() {
        for (int i = 0; i < 5; i++) {
            var a = service.create("PAGED" + i, SELECT, details());
            service.addValue(a.id(), "V1", label("One"));
            service.addValue(a.id(), "V2", label("Two"));
        }
        entityManager.flush();
        entityManager.clear();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            var page = service.list("PAGED", null, null, 0, 20, null);
            assertThat(page.items()).hasSize(5).allSatisfy(v -> assertThat(v.values()).hasSize(2));
            // One for the page (Spring Data skips the count when the page is not full), one for the values.
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    /**
     * The audit writer commits on its own, so its rows are visible from this test's transaction.
     * A value is audited as what it is - a child row - so the trail can tell deleting a value from
     * deleting the attribute.
     */
    @Test
    void aStaleVersionIsReportedBeforeAnyOtherReasonToRefuseADelete() {
        var attribute = service.create("STALE", SELECT, details());
        var value = service.addValue(attribute.id(), "V1", label("One")).values().get(0);
        assertThat(errorOf(() -> service.deleteValue(attribute.id(), value.id(), value.version() + 1)))
                .isEqualTo(ErrorCode.OPTIMISTIC_LOCK);
    }

    @Test
    void valueOperationsAreAuditedAsAttributeUpdatesAndValueRows() {
        var attribute = service.create("AUDITED", SELECT, details());
        var withValue = service.addValue(attribute.id(), "V1", label("One"));
        var value = withValue.values().get(0);
        service.updateValue(attribute.id(), value.id(), value.version(), label("Uno"), true);
        service.deleteValue(attribute.id(), value.id(), service.get(attribute.id()).values().get(0).version());

        assertThat(auditOf(attribute.id().toString())).containsExactly(
                "CREATE product-attribute", "UPDATE product-attribute");
        assertThat(auditOf(value.id().toString())).containsExactly(
                "UPDATE product-attribute-value", "DELETE product-attribute-value");
    }

    private java.util.List<String> auditOf(String resourceId) {
        return jdbc.queryForList("select action || ' ' || resource_type from platform.audit_log "
                + "where resource_id = ? order by occurred_at", String.class, resourceId);
    }
}
