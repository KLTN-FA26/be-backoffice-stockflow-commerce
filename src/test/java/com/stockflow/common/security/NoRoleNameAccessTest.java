package com.stockflow.common.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lead's rule (2026-10-22): every access check calls the permission system; no role name decides who may do what.
 *
 * <p>Fails the build when production code guards with a role: a {@code @PreAuthorize} / {@code @Secured}, a
 * {@code hasRole} / {@code hasAuthority} expression naming a role, a {@code Roles.X} constant, or a {@code Role.X}
 * enum constant or {@code roles()} test in a controller or service. The few places that legitimately name a role are
 * listed in {@link #ROLE_NAMES_ALLOWED} with the reason: none of them decides whether the caller may act.</p>
 *
 * <p>Access goes through {@link RequiresPermission} on the endpoint, or {@link CurrentUser#can} /
 * {@link CurrentUser#require} in a service. Which rows (a portal customer's own customer, the caller's warehouses) is
 * {@link ScopeGuards} and {@link WarehouseScope}.</p>
 */
class NoRoleNameAccessTest {

    private static final Path MAIN = Paths.get("src/main/java/com/stockflow");

    /** Path suffixes (relative to com/stockflow) that may name a role, and why. */
    private static final Map<String, String> ROLE_NAMES_ALLOWED = Map.ofEntries(
            Map.entry("common/security/", "the role catalogue itself, token conversion and the data-scope guards "
                    + "(ScopeGuards, CurrentUser.isPortalCustomer: a portal account sees its own customer's rows)"),
            Map.entry("identity/", "role management: assigning, seeding and protecting roles is about roles"),
            Map.entry("notification/internal/service/", "who is told: the audience of a notification, not access"),
            Map.entry("design/internal/service/DesignWorkflowService.java",
                    "validates the users a design is handed to (owner, designer, reviewer), not the caller"),
            Map.entry("fulfillment/internal/service/FulfillmentServiceImpl.java",
                    "who a pick can be assigned to (the assignee's eligibility), not the caller"));

    /** Never, anywhere: role expressions and role-annotation guards. */
    private static final List<Pattern> NEVER = List.of(
            Pattern.compile("@([\\w.]+\\.)?(PreAuthorize|PostAuthorize|Secured|RolesAllowed)\\b"),
            Pattern.compile("\\bhas(Any)?Role\\s*\\("),
            Pattern.compile("\\bhas(Any)?Authority\\s*\\(\\s*(\"|'|Roles\\.|Role\\.)"),
            Pattern.compile("[\"']ROLE_[A-Z]"));

    /** Outside {@link #ROLE_NAMES_ALLOWED}: any role constant or role-set test. */
    private static final List<Pattern> ROLE_NAMES = List.of(
            Pattern.compile("\\bRoles\\.[A-Z_]+"),
            Pattern.compile("\\bRole\\.[A-Z][A-Z_]+\\b"),
            Pattern.compile("\\.roles\\(\\)\\s*\\.\\s*(contains|stream|containsAll|isEmpty)\\b"),
            Pattern.compile("\\bhas(Any)?Authority\\s*\\("));

    private static final Pattern TRAILING_COMMENT = Pattern.compile("\\s//.*$");

    @Test
    void noAccessCheckNamesARole() throws IOException {
        assertThat(MAIN).as("run from the project root").isDirectory();
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                boolean allowed = ROLE_NAMES_ALLOWED.keySet().stream().anyMatch(relative::startsWith);
                boolean securityConfig = relative.equals("common/security/ResourceServerSecurityConfig.java");
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = TRAILING_COMMENT.matcher(lines.get(i)).replaceFirst("").strip();
                    if (line.startsWith("*") || line.startsWith("/*") || line.startsWith("//")) {
                        continue;
                    }
                    for (Pattern never : NEVER) {
                        if (never.matcher(line).find()) {
                            problems.add(relative + ":" + (i + 1) + " " + line);
                        }
                    }
                    if (allowed && !securityConfig) {
                        continue;
                    }
                    for (Pattern roleName : ROLE_NAMES) {
                        // The URL rule of the actuator is the one hasAuthority left, and it names a permission.
                        if (securityConfig && line.contains("hasAuthority(ActuatorPermission.AUTHORITY)")) {
                            continue;
                        }
                        if (roleName.matcher(line).find()) {
                            problems.add(relative + ":" + (i + 1) + " " + line);
                        }
                    }
                }
            }
        }
        assertThat(problems).as("role names deciding access - use @RequiresPermission or CurrentUser.can/require")
                .isEmpty();
    }
}
