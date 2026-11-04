package com.stockflow.assistant.internal.service;

import com.stockflow.assistant.internal.domain.BusinessDate;
import com.stockflow.assistant.internal.domain.ToolSpec;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.RequiresPermission;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Which tools a user may use, and what stops each of the others: for the panel ({@code GET
 * /api/v1/assistant/capabilities}); for the question context, so the model knows a refusal before it calls the tool,
 * and a refusal the system can predict does not cost a model request to learn; and for the model request itself,
 * which offers only those tools ({@link #specsFor}).
 *
 * <p><b>It decides what the model is offered, never what runs.</b> Every tool still runs behind its proxy, its own
 * checks in {@code run()} and the rules of the module it calls, and nothing here lets a call through or stops one: a
 * call to a tool that was not offered (a provider without strict tool use, a scripted model in a test) still reaches
 * that tool, which refuses it, and the refusal is recorded like any other call. The report is read from the
 * declarations that enforcement reads: each {@code run()}'s {@code @RequiresPermission}, read once at startup as the
 * permission aspect and {@code AssistantIntegrationTest.everyToolMirrorsItsScreen} read it, plus
 * {@link AssistantTool#alsoRequires}, {@link AssistantTool#alsoRequiresAnyOf} and {@link AssistantTool#scopeLimit}.
 * There is no second list to keep in step: a grant changed in the role matrix, or a new tool, shows here at once.</p>
 *
 * <p><b>A tool without a guard stops the application from starting.</b> Its {@code run()} would execute for anyone,
 * with no data scope; reporting it as open to everyone would be true and would hide the defect.</p>
 *
 * <p><b>Warehouse-limited</b> is decided as {@code WarehouseScope.restriction()} decides it once a tool's guard has put
 * its scope in force: the caller's own scope is WAREHOUSE and the guard's scope is not narrower. It is read from the
 * {@link CurrentUser}, not from {@code WarehouseScope}, because the panel's endpoint runs under scope OWN, where no
 * warehouse restriction is in force.</p>
 */
@Component
public class ToolAvailability {

    private final List<Declaration> tools;
    private final ConversationService conversations;
    private final AssistantProperties properties;
    private final Clock clock;

    ToolAvailability(AssistantTools tools, ConversationService conversations, AssistantProperties properties,
                     Clock clock) {
        this.tools = tools.all().stream().map(Declaration::of).sorted(Comparator.comparing(Declaration::name))
                .toList();
        this.conversations = conversations;
        this.properties = properties;
        this.clock = clock;
    }

    /** Every tool as this user stands with it, by name. */
    public List<ToolAccess> toolsFor(CurrentUser user) {
        return tools.stream().map(tool -> tool.accessOf(user)).toList();
    }

    /** The names of the tools this user may use, sorted: what the question context lists. */
    public List<String> grantedTo(CurrentUser user) {
        return toolsFor(user).stream().filter(ToolAccess::granted).map(ToolAccess::name).toList();
    }

    /**
     * The definitions of the tools this user may use, sorted by name: what the model is offered, the tools of
     * {@link #grantedTo} (ADR-0010, amended 29/10/2026). Not every tool, as before. The definitions are the largest
     * part of a request before the history, sent again on every call of an answer, and a small model offered a tool
     * its user may not use calls it all the same and spends a round learning the refusal.
     *
     * <p>The same grants and the same warehouse limit give the same definitions in the same order, so the list is
     * byte-identical on every call and for every user who holds them: the provider caches it once per set of grants
     * instead of once for everyone.</p>
     */
    public List<ToolSpec> specsFor(CurrentUser user) {
        return tools.stream().filter(tool -> tool.accessOf(user).granted()).map(Declaration::spec).toList();
    }

    /**
     * What the panel shows before a question: the user's scope and warehouses, today's questions against the daily
     * cap, counted as {@link AssistantService} counts them when it enforces the cap, and every tool.
     */
    public Capabilities capabilitiesOf(CurrentUser user) {
        long askedToday = conversations.questionsSince(user.userId(), BusinessDate.startOfDay(clock.instant()));
        return new Capabilities(user.scope(), user.warehouseCodes().stream().sorted().toList(),
                properties.dailyMessageLimit(), askedToday, toolsFor(user));
    }

    /** Whether the user sees only the warehouses they are assigned to. */
    public static boolean limitedToWarehouses(CurrentUser user) {
        return user.scope() == DataScope.WAREHOUSE;
    }

    /**
     * One tool as a user stands with it.
     *
     * @param granted    the user holds every requirement, and the tool does not give company-wide figures to a user
     *                   limited to warehouses
     * @param requires   everything the tool needs, its annotation's permission first
     * @param missing    the requirements the user does not meet; empty and still not granted when the tool gives
     *                   company-wide figures and the user is limited to warehouses
     * @param scopeLimit what any warehouse-limited user gets from the tool
     */
    public record ToolAccess(String name, boolean granted, List<Requirement> requires, List<Requirement> missing,
                             ScopeLimit scopeLimit) {
    }

    /** One condition of a tool: hold any one of {@code anyOf}; a single permission when it holds one. */
    public record Requirement(List<PermissionCode> anyOf) {

        public Requirement {
            anyOf = List.copyOf(anyOf);
            if (anyOf.isEmpty()) {
                throw new IllegalArgumentException("a requirement names at least one permission");
            }
        }

        /** {@code resource:ACTION}, alternatives joined by {@code |}: one entry of the panel's lists. */
        public String code() {
            return anyOf.stream().map(PermissionCode::toString).collect(Collectors.joining("|"));
        }

        boolean metBy(CurrentUser user) {
            return anyOf.stream().anyMatch(permission -> user.can(permission.resource(), permission.action()));
        }
    }

    /**
     * @param warehouses the codes of the warehouses the user is assigned to, sorted
     * @param dailyLimit questions one user may ask per business day
     * @param askedToday questions the user has asked since the start of today, Vietnam time
     */
    public record Capabilities(DataScope scope, List<String> warehouses, int dailyLimit, long askedToday,
                               List<ToolAccess> tools) {
    }

    /** A tool's declarations, read once, with the definition the model is offered. */
    private record Declaration(ToolSpec spec, DataScope guardScope, List<Requirement> requires,
                               ScopeLimit scopeLimit) {

        static Declaration of(AssistantTool tool) {
            RequiresPermission guard = guardOf(tool);
            List<Requirement> requires = new ArrayList<>();
            requires.add(new Requirement(List.of(PermissionCode.of(guard.resource(), guard.action()))));
            tool.alsoRequires().forEach(permission -> requires.add(new Requirement(List.of(permission))));
            if (!tool.alsoRequiresAnyOf().isEmpty()) {
                requires.add(new Requirement(tool.alsoRequiresAnyOf()));
            }
            return new Declaration(tool.spec(), guard.scope(), List.copyOf(requires),
                    Objects.requireNonNull(tool.scopeLimit(), "scopeLimit"));
        }

        String name() {
            return spec.name();
        }

        ToolAccess accessOf(CurrentUser user) {
            List<Requirement> missing = requires.stream().filter(requirement -> !requirement.metBy(user)).toList();
            boolean limited = limitedToWarehouses(user) && !DataScope.WAREHOUSE.isBroaderThan(guardScope);
            boolean refusedForScope = limited && scopeLimit == ScopeLimit.COMPANY_WIDE_ONLY;
            return new ToolAccess(name(), missing.isEmpty() && !refusedForScope, requires, missing, scopeLimit);
        }
    }

    private static RequiresPermission guardOf(AssistantTool tool) {
        Method run;
        try {
            run = AopUtils.getTargetClass(tool).getMethod("run", ToolArguments.class);
        } catch (NoSuchMethodException impossible) {
            throw new IllegalStateException("an AssistantTool always has run(ToolArguments)", impossible);
        }
        RequiresPermission guard = AnnotatedElementUtils.findMergedAnnotation(run, RequiresPermission.class);
        if (guard == null) {
            throw new IllegalStateException("Assistant tool " + tool.spec().name() + " carries no @RequiresPermission "
                    + "on run(): it would run for anyone, with no data scope. Give it the guard of the screen it "
                    + "mirrors (design §5.1).");
        }
        return guard;
    }
}
