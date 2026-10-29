package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.RequiresPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the panel and the question context are told about each tool, read from the tool's own declarations: the guard
 * on {@code run}, the permissions {@code run} checks besides it, an either-or rule, and what a user limited to
 * warehouses gets. The real tools against the seeded roles are {@code AssistantCapabilitiesIntegrationTest}.
 */
class ToolAvailabilityTest {

    /** 09:00 on 28/10/2026 in Vietnam; that business day began at 17:00 UTC the day before. */
    private static final Instant NOW = Instant.parse("2026-10-28T02:00:00Z");

    private final ConversationService conversations = mock(ConversationService.class);
    private final AssistantProperties properties = mock(AssistantProperties.class);

    @Test
    @DisplayName("a tool is granted to whoever holds its guard; anyone else is told the permission they lack")
    void guard() {
        ToolAvailability availability = availability(new StockTool());

        ToolAvailability.ToolAccess holder = only(availability, user(DataScope.ALL, "inventory-stock-items:READ"));
        assertThat(holder.granted()).isTrue();
        assertThat(codes(holder.requires())).containsExactly("inventory-stock-items:READ");
        assertThat(holder.missing()).isEmpty();
        assertThat(holder.scopeLimit()).isEqualTo(ScopeLimit.NONE);

        ToolAvailability.ToolAccess other = only(availability, user(DataScope.ALL, "sales-orders:READ"));
        assertThat(other.granted()).isFalse();
        assertThat(codes(other.missing())).containsExactly("inventory-stock-items:READ");
    }

    @Test
    @DisplayName("what run() checks besides the guard is listed after it, and each one lacking is named")
    void alsoRequires() {
        ToolAvailability.ToolAccess reorder = only(availability(new ReorderTool()),
                user(DataScope.ALL, "procurement-purchase-orders:CREATE", "procurement-supplier-items:READ"));

        assertThat(codes(reorder.requires())).containsExactly("procurement-purchase-orders:CREATE",
                "reporting-reports:READ", "inventory-stock-items:READ", "procurement-supplier-items:READ");
        assertThat(codes(reorder.missing())).containsExactly("reporting-reports:READ", "inventory-stock-items:READ");
        assertThat(reorder.granted()).isFalse();
    }

    @Test
    @DisplayName("an either-or rule is one requirement, met by any one of its permissions")
    void eitherOr() {
        ToolAvailability availability = availability(new PickingTool());

        assertThat(only(availability, user(DataScope.ALL, "fulfillment-pick-lists:READ",
                "fulfillment-design-reverification:READ")).granted()).isTrue();
        ToolAvailability.ToolAccess neither = only(availability, user(DataScope.ALL, "fulfillment-pick-lists:READ"));
        assertThat(neither.granted()).isFalse();
        assertThat(codes(neither.missing()))
                .containsExactly("fulfillment-task-oversight:READ|fulfillment-design-reverification:READ");
    }

    @Test
    @DisplayName("company-wide figures are refused to a warehouse-limited user holding every permission, and only "
            + "to them; a partial tool stays open to them")
    void companyWideOnly() {
        ToolAvailability availability = availability(new LowStockTool(), new ExpiringTool());

        List<ToolAvailability.ToolAccess> limited = availability.toolsFor(user(DataScope.WAREHOUSE,
                "inventory-alerts:READ"));
        assertThat(limited).extracting(ToolAvailability.ToolAccess::name, ToolAvailability.ToolAccess::granted,
                        ToolAvailability.ToolAccess::scopeLimit)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("findExpiringLots", true, ScopeLimit.PARTIAL),
                        org.assertj.core.groups.Tuple.tuple("findLowStock", false, ScopeLimit.COMPANY_WIDE_ONLY));
        assertThat(limited.get(1).missing()).as("nothing to grant: the refusal is the scope").isEmpty();

        assertThat(availability.grantedTo(user(DataScope.ALL, "inventory-alerts:READ")))
                .containsExactly("findExpiringLots", "findLowStock");
    }

    @Test
    @DisplayName("a guard narrowed to OWN puts no warehouse limit in force, as WarehouseScope decides it")
    void ownScopedGuard() {
        assertThat(only(availability(new OwnFiguresTool()), user(DataScope.WAREHOUSE, "assistant-conversations:READ"))
                .granted()).isTrue();
    }

    @Test
    @DisplayName("the granted names come sorted, every tool counted: what the question context lists")
    void grantedNamesAreSorted() {
        ToolAvailability availability = availability(new StockTool(), new LowStockTool(), new ExpiringTool(),
                new ReorderTool());

        assertThat(availability.grantedTo(user(DataScope.ALL, "inventory-alerts:READ", "inventory-stock-items:READ")))
                .containsExactly("findExpiringLots", "findLowStock", "getStockLevels");
        assertThat(availability.toolsFor(user(DataScope.ALL)))
                .extracting(ToolAvailability.ToolAccess::name)
                .containsExactly("findExpiringLots", "findLowStock", "getStockLevels", "proposeReorder");
    }

    @Test
    @DisplayName("a tool without a guard stops the application from starting instead of being reported as open")
    void unguardedToolIsRefused() {
        assertThatThrownBy(() -> availability(new UnguardedTool()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unguarded")
                .hasMessageContaining("@RequiresPermission");
    }

    @Test
    @DisplayName("the panel gets the scope, the sorted warehouses, the daily cap and today's questions counted from "
            + "the start of the Vietnam day")
    void capabilities() {
        when(properties.dailyMessageLimit()).thenReturn(50);
        CurrentUser manager = new CurrentUser(UUID.randomUUID(), "manager", Set.of(),
                permissions("inventory-stock-items:READ"), DataScope.WAREHOUSE, Set.of("HN", "HCM"));
        when(conversations.questionsSince(manager.userId(), Instant.parse("2026-10-27T17:00:00Z"))).thenReturn(3L);

        ToolAvailability.Capabilities capabilities = availability(new StockTool()).capabilitiesOf(manager);

        assertThat(capabilities.scope()).isEqualTo(DataScope.WAREHOUSE);
        assertThat(capabilities.warehouses()).containsExactly("HCM", "HN");
        assertThat(capabilities.dailyLimit()).isEqualTo(50);
        assertThat(capabilities.askedToday()).isEqualTo(3);
        assertThat(capabilities.tools()).extracting(ToolAvailability.ToolAccess::name)
                .containsExactly("getStockLevels");
    }

    // ------------------------------------------------------------------------------------------------------------

    private ToolAvailability availability(AssistantTool... tools) {
        return new ToolAvailability(new AssistantTools(List.of(tools), new ObjectMapper()), conversations, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ToolAvailability.ToolAccess only(ToolAvailability availability, CurrentUser user) {
        List<ToolAvailability.ToolAccess> tools = availability.toolsFor(user);
        assertThat(tools).hasSize(1);
        return tools.getFirst();
    }

    private static CurrentUser user(DataScope scope, String... permissions) {
        return new CurrentUser(UUID.randomUUID(), "tester", Set.of(), permissions(permissions), scope,
                scope == DataScope.WAREHOUSE ? Set.of("HCM") : Set.of());
    }

    private static Set<PermissionCode> permissions(String... codes) {
        return Arrays.stream(codes).map(PermissionCode::parse).collect(Collectors.toSet());
    }

    private static List<String> codes(List<ToolAvailability.Requirement> requirements) {
        return requirements.stream().map(ToolAvailability.Requirement::code).toList();
    }

    /** Only what availability reads: a name, a guard on run(), and the declarations a real tool makes. */
    private abstract static class Probe implements AssistantTool {

        private final String name;

        Probe(String name) {
            this.name = name;
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec(name, name, ToolSchema.object().build());
        }

        @Override
        public String mirrors() {
            return null;
        }
    }

    static final class StockTool extends Probe {

        StockTool() {
            super("getStockLevels");
        }

        @Override
        @RequiresPermission(resource = "inventory-stock-items", action = Action.READ)
        public Object run(ToolArguments arguments) {
            return null;
        }
    }

    static final class ReorderTool extends Probe {

        ReorderTool() {
            super("proposeReorder");
        }

        @Override
        public List<PermissionCode> alsoRequires() {
            return List.of(PermissionCode.of("reporting-reports", Action.READ),
                    PermissionCode.of("inventory-stock-items", Action.READ),
                    PermissionCode.of("procurement-supplier-items", Action.READ));
        }

        @Override
        public ScopeLimit scopeLimit() {
            return ScopeLimit.COMPANY_WIDE_ONLY;
        }

        @Override
        @RequiresPermission(resource = "procurement-purchase-orders", action = Action.CREATE)
        public Object run(ToolArguments arguments) {
            return null;
        }
    }

    static final class PickingTool extends Probe {

        PickingTool() {
            super("getOrderPicking");
        }

        @Override
        public List<PermissionCode> alsoRequiresAnyOf() {
            return List.of(PermissionCode.of("fulfillment-task-oversight", Action.READ),
                    PermissionCode.of("fulfillment-design-reverification", Action.READ));
        }

        @Override
        @RequiresPermission(resource = "fulfillment-pick-lists", action = Action.READ)
        public Object run(ToolArguments arguments) {
            return null;
        }
    }

    static final class LowStockTool extends Probe {

        LowStockTool() {
            super("findLowStock");
        }

        @Override
        public ScopeLimit scopeLimit() {
            return ScopeLimit.COMPANY_WIDE_ONLY;
        }

        @Override
        @RequiresPermission(resource = "inventory-alerts", action = Action.READ)
        public Object run(ToolArguments arguments) {
            return null;
        }
    }

    static final class ExpiringTool extends Probe {

        ExpiringTool() {
            super("findExpiringLots");
        }

        @Override
        public ScopeLimit scopeLimit() {
            return ScopeLimit.PARTIAL;
        }

        @Override
        @RequiresPermission(resource = "inventory-alerts", action = Action.READ)
        public Object run(ToolArguments arguments) {
            return null;
        }
    }

    /** Company-wide figures behind a guard narrowed to the caller's own records: no warehouse limit applies. */
    static final class OwnFiguresTool extends Probe {

        OwnFiguresTool() {
            super("ownFigures");
        }

        @Override
        public ScopeLimit scopeLimit() {
            return ScopeLimit.COMPANY_WIDE_ONLY;
        }

        @Override
        @RequiresPermission(resource = "assistant-conversations", action = Action.READ, scope = DataScope.OWN)
        public Object run(ToolArguments arguments) {
            return null;
        }
    }

    static final class UnguardedTool extends Probe {

        UnguardedTool() {
            super("unguarded");
        }

        @Override
        public Object run(ToolArguments arguments) {
            return null;
        }
    }
}
