package com.stockflow.common.audit;

import com.stockflow.common.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code #result} in {@code resourceId}: a method that creates its subject has no argument naming
 * it, so without the return value its CREATE entry carried no id and never showed in the record's
 * history.
 */
class AuditAspectResultTest {

    record Created(UUID id) {}

    /** Stands in for a service; public, because the aspect only advises what a proxy can reach. */
    public static class Catalogue {
        static final UUID NEW_ID = UUID.fromString("01a10000-0000-7000-8000-0000000000aa");

        @Auditable(action = AuditAction.CREATE, resourceType = "thing", resourceId = "#result?.id()")
        public Created create(String name) {
            if (name.isBlank()) {
                throw new IllegalArgumentException("name is required");
            }
            return new Created(NEW_ID);
        }

        @Auditable(action = AuditAction.UPDATE, resourceType = "thing", resourceId = "#id")
        public Created update(UUID id) {
            return new Created(id);
        }

        @Auditable(action = AuditAction.TRANSITION, resourceType = "thing", resourceId = "#id",
                details = "{change: #change, reason: #reason, count: #count}")
        public Created transition(UUID id, String change, String reason, int count) {
            if (count < 0) {
                throw new IllegalStateException("refused");
            }
            return new Created(id);
        }

        @Auditable(action = AuditAction.UPDATE, resourceType = "thing", details = "#nope.broken()")
        public Created brokenDetails() {
            return new Created(NEW_ID);
        }
    }

    private final List<AuditEntry> entries = new ArrayList<>();

    private Catalogue proxied() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new Catalogue());
        factory.setProxyTargetClass(true);
        factory.addAspect(new AuditAspect(entries::add, new CurrentUserProvider(),
                Clock.fixed(Instant.parse("2026-10-04T10:00:00Z"), ZoneOffset.UTC)));
        return factory.getProxy();
    }

    @Test
    void aCreationIsRecordedUnderTheIdItReturned() {
        proxied().create("sofa");

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo(AuditAction.CREATE);
            assertThat(entry.resourceId()).isEqualTo(Catalogue.NEW_ID.toString());
            assertThat(entry.outcome()).isEqualTo(AuditEntry.Outcome.SUCCESS);
        });
    }

    @Test
    void aFailedCreationIsStillRecordedButNamesNoRecord() {
        assertThatThrownBy(() -> proxied().create(" ")).isInstanceOf(IllegalArgumentException.class);

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.outcome()).isEqualTo(AuditEntry.Outcome.FAILURE);
            assertThat(entry.resourceId()).isNull();
        });
    }

    @Test
    void argumentExpressionsAreUnchanged() {
        UUID id = UUID.fromString("01a10000-0000-7000-8000-0000000000bb");

        proxied().update(id);

        assertThat(entries).singleElement()
                .satisfies(entry -> assertThat(entry.resourceId()).isEqualTo(id.toString()));
    }

    @Test
    void detailsAreTheEvaluatedMapAsOneJsonObjectWithValuesEscaped() {
        UUID id = UUID.fromString("01a10000-0000-7000-8000-0000000000cc");

        proxied().transition(id, "LOCK", "he said \"stop\"\nnow", 3);

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.outcome()).isEqualTo(AuditEntry.Outcome.SUCCESS);
            assertThat(entry.details())
                    .isEqualTo("{\"change\":\"LOCK\",\"reason\":\"he said \\\"stop\\\"\\nnow\",\"count\":3}");
        });
    }

    @Test
    void afterAFailureTheDetailsSitNextToTheErrorUnderContext() {
        UUID id = UUID.fromString("01a10000-0000-7000-8000-0000000000dd");

        assertThatThrownBy(() -> proxied().transition(id, "DISABLE", "because", -1))
                .isInstanceOf(IllegalStateException.class);

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.outcome()).isEqualTo(AuditEntry.Outcome.FAILURE);
            assertThat(entry.details()).startsWith("{\"error\":\"IllegalStateException\"")
                    .contains("\"context\":{\"change\":\"DISABLE\",\"reason\":\"because\",\"count\":-1}");
        });
    }

    @Test
    void anEntryWithoutDetailsKeepsNullAndABadExpressionNeverFailsTheOperation() {
        proxied().update(UUID.randomUUID());
        proxied().brokenDetails();

        assertThat(entries).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.outcome()).isEqualTo(AuditEntry.Outcome.SUCCESS);
            assertThat(entry.details()).isNull();
        });
    }
}
