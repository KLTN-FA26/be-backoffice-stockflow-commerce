package com.stockflow.common.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lead's rule (2026-10-22): every access check calls the permission system; no role name decides who may do what.
 *
 * <p>Scans all production Java (comments stripped, string literals kept) and every {@code application*.yml} and fails on
 * anything that names a role: {@code @PreAuthorize} / {@code @Secured} / {@code @RolesAllowed}, {@code hasRole},
 * {@code hasAuthority}, a {@code "ROLE_"} or role-code string literal ({@code "WAREHOUSE_MANAGER".equals(...)}, a
 * {@code new SimpleGrantedAuthority("ROLE_...")}), {@code Role.X} / {@code Roles.X} / {@code Role.valueOf} /
 * {@code Role::}, a static import of either, a {@code case} on a role constant, or a {@code roles()} test.</p>
 *
 * <p>The places that legitimately name a role are allowed <b>line by line</b> in {@link #ALLOWED}, each with its reason;
 * none of them decides whether the caller may act. An entry that no longer matches anything fails the test too, so the
 * list cannot rot. The only whole files exempt are the two that define the role catalogue.</p>
 *
 * <p>Access goes through {@link RequiresPermission} on the endpoint, or {@link CurrentUser#can} /
 * {@link CurrentUser#require} in a service. Which rows (a portal customer's own customer, the caller's warehouses) is
 * {@link ScopeGuards} and {@link WarehouseScope}.</p>
 */
class NoRoleNameAccessTest {

    private static final Path JAVA = Paths.get("src/main/java/com/stockflow");
    private static final Path RESOURCES = Paths.get("src/main/resources");

    /** The role catalogue itself: the enum and the annotation-constant twin it is checked against (RoleConsistencyTest). */
    private static final Set<String> DEFINITIONS = Set.of("common/security/Role.java", "common/security/Roles.java");

    private record Allowed(String file, String fragment, String reason) {
    }

    private static final List<Allowed> ALLOWED = List.of(
            // ---- the security layer: converting a token, and the one data-scope test that has to name CUSTOMER
            new Allowed("common/security/CurrentUserProvider.java", ".map(Role::fromAuthority)",
                    "turns the token's role codes into Role constants for CurrentUser.roles(); decides nothing"),
            new Allowed("common/security/CurrentUser.java", "allMatch(role -> role == Role.CUSTOMER)",
                    "isPortalCustomer: which rows (the caller's own customer), not whether it may act"),
            new Allowed("common/security/ResourceServerSecurityConfig.java",
                    ".hasAuthority(ActuatorPermission.READ_AUTHORITY)",
                    "the actuator URL rule: the authority is the permission platform-actuator:READ"),
            new Allowed("common/security/ResourceServerSecurityConfig.java",
                    ".hasAuthority(ActuatorPermission.UPDATE_AUTHORITY)",
                    "the actuator URL rule: the authority is the permission platform-actuator:UPDATE"),
            // ---- role management: assigning, creating and protecting roles is about roles
            new Allowed("identity/internal/service/IdentityServiceImpl.java", "Roles.CUSTOMER",
                    "role management: CUSTOMER is never combined with, revoked from or edited like a staff role"),
            new Allowed("identity/internal/service/IdentityServiceImpl.java", "Roles.SYSTEM_ADMIN",
                    "role management: the last active SYSTEM_ADMIN is kept, SYSTEM_ADMIN is not a staff template"),
            new Allowed("identity/internal/service/PortalAccountServiceImpl.java", "roles.findByCode(Roles.CUSTOMER)",
                    "a portal account is created with the CUSTOMER role"),
            new Allowed("identity/internal/controller/dto/CreateRoleRequest.java", "example = \"WAREHOUSE_STAFF\"",
                    "OpenAPI example of a role code"),
            new Allowed("identity/internal/controller/dto/RoleResponse.java", "example = \"ACCOUNTANT\"",
                    "OpenAPI example of a role code"),
            // ---- who is told: the audience of a notification, not access
            new Allowed("notification/internal/service/OrderReferralNotificationListener.java",
                    "List.of(Role.SALES_STAFF, Role.ORDER_COORDINATOR)", "who is told of a referred order"),
            new Allowed("notification/internal/service/QuoteNotificationListener.java", "staff.activeStaff(Role.SALES_STAFF)",
                    "who is told of a quote with no handler"),
            new Allowed("notification/internal/service/ProductionNotificationListener.java", "-> Role.",
                    "who is told of a production event, by kind"),
            new Allowed("notification/internal/service/ProductionNotificationListener.java",
                    "audience == Role.WAREHOUSE_MANAGER", "managers are told about their own warehouse only"),
            new Allowed("notification/internal/service/InventoryAlertNotificationListener.java",
                    "inventory-alert.stock-level-roles:", "stock-alert audience, configurable"),
            new Allowed("notification/internal/service/InventoryAlertNotificationListener.java",
                    "inventory-alert.expiry-roles:", "expiry-alert audience, configurable"),
            new Allowed("application.yml", "stock-level-roles:", "stock-alert audience (who is told), not access"),
            new Allowed("application.yml", "expiry-roles:", "expiry-alert audience (who is told), not access"),
            // ---- checks on the user an action is about, not on the caller
            new Allowed("design/internal/service/DesignWorkflowService.java", "Role.CUSTOMER.authority()",
                    "the design's owner must be a customer account"),
            new Allowed("design/internal/service/DesignWorkflowService.java", "Role.SALES_STAFF.authority()",
                    "the designer a design is handed to must be Sales or the E-commerce Admin"),
            new Allowed("design/internal/service/DesignWorkflowService.java", "Role.QC_STAFF.authority()",
                    "the reviewer a design is handed to must be QC"),
            new Allowed("fulfillment/internal/service/FulfillmentServiceImpl.java",
                    "Role.WAREHOUSE_STAFF.authority(), Role.WAREHOUSE_MANAGER.authority()",
                    "who a pick task can be assigned to (the assignee's eligibility), not the caller"),
            new Allowed("identity/internal/controller/MeController.java", "!authority.startsWith(\"ROLE_\")",
                    "GET /me/permissions lists permissions: role-prefixed authorities are left out"),
            new Allowed("identity/internal/controller/dto/CreateUserRequest.java", "example = \"[\\\"WAREHOUSE_STAFF\\\"]\"",
                    "OpenAPI example of the roles a new staff user gets"),
            new Allowed("identity/internal/repository/UserJpaRepository.java", "c.code = 'CUSTOMER'",
                    "role management query: staff accounts are the ones without the CUSTOMER role"),
            new Allowed("identity/internal/service/IdentityServiceImpl.java", "\"Role CUSTOMER is not revoked from account \"",
                    "error message of the CUSTOMER role rule"),
            new Allowed("identity/internal/service/IdentityServiceImpl.java", "\"A staff account cannot hold CUSTOMER\"",
                    "error message of the CUSTOMER role rule"),
            new Allowed("identity/internal/service/PortalAccountServiceImpl.java", "\"The CUSTOMER role is seeded by migration",
                    "error message: a portal account is created with the CUSTOMER role"),
            // ---- not a role at all: the same word used for something else
            new Allowed("payment/internal/controller/dto/ReceivableResponse.java", "allowableValues = {\"CUSTOMER\", \"CARRIER\"}",
                    "the receivable's party type, not a role"),
            new Allowed("payment/internal/repository/ReceivableBookAdapter.java", "collector = 'CUSTOMER'",
                    "who collects a receivable (customer or carrier), not a role"),
            new Allowed("order/", "CUSTOMER, FRAUD", "the hold reason CUSTOMER (HoldReason), not a role"));

    private static final String CODES = Arrays.stream(Role.values()).map(Role::name).collect(Collectors.joining("|"));

    private static final List<Pattern> JAVA_RULES = List.of(
            Pattern.compile("@([\\w.]+\\.)?(PreAuthorize|PostAuthorize|Secured|RolesAllowed)\\b"),
            Pattern.compile("\\bhas(Any)?(Role|Authority)\\s*\\("),
            Pattern.compile("\\bRoles?\\s*\\.\\s*(valueOf\\b|[A-Z][A-Z0-9_]*\\b)"),
            Pattern.compile("\\bRole\\s*::"),
            Pattern.compile("import\\s+static\\s+com\\.stockflow\\.common\\.security\\.Roles?\\b"),
            Pattern.compile("\\bcase\\s+(" + CODES + ")\\b"),
            // A test on a role set (passing roles() along as data is fine): roles().contains(...), .stream(), ...
            Pattern.compile("\\.\\s*roles\\s*\\(\\s*\\)\\s*\\.\\s*\\w+"));

    /** Inside a string literal: a ROLE_ prefix or a role code as a whole word. */
    private static final Pattern ROLE_IN_LITERAL =
            Pattern.compile("ROLE_|(?<![A-Za-z0-9_])(" + CODES + ")(?![A-Za-z0-9_])");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'");

    @Test
    void noAccessCheckNamesARole() throws IOException {
        assertThat(JAVA).as("run from the project root").isDirectory();
        List<String> problems = new ArrayList<>();
        Set<Allowed> used = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(JAVA)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String relative = JAVA.relativize(file).toString().replace('\\', '/');
                if (DEFINITIONS.contains(relative)) {
                    continue;
                }
                List<String> code = withoutComments(Files.readString(file));
                for (int i = 0; i < code.size(); i++) {
                    check(relative, i + 1, code.get(i), true, problems, used);
                }
            }
        }
        try (Stream<Path> files = Files.list(RESOURCES)) {
            for (Path file : files.filter(f -> f.getFileName().toString().matches("application.*\\.ya?ml")).sorted()
                    .toList()) {
                String name = file.getFileName().toString();
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).replaceFirst("(^|\\s)#.*$", "");
                    check(name, i + 1, line, false, problems, used);
                }
            }
        }
        assertThat(problems).as("role names deciding access - use @RequiresPermission or CurrentUser.can/require, or "
                + "allow the line in ALLOWED with its reason").isEmpty();
        assertThat(ALLOWED.stream().filter(a -> !used.contains(a)).toList()).as("stale ALLOWED entries").isEmpty();
    }

    private static void check(String file, int number, String line, boolean java, List<String> problems,
                              Set<Allowed> used) {
        if (line.isBlank()) {
            return;
        }
        boolean hit = false;
        if (java) {
            for (Pattern rule : JAVA_RULES) {
                hit |= rule.matcher(line).find();
            }
            Matcher literal = STRING_LITERAL.matcher(line);
            while (literal.find()) {
                hit |= ROLE_IN_LITERAL.matcher(literal.group()).find();
            }
        } else {
            hit = ROLE_IN_LITERAL.matcher(line).find();
        }
        if (!hit) {
            return;
        }
        String trimmed = line.strip();
        for (Allowed allowed : ALLOWED) {
            if (file.startsWith(allowed.file()) && trimmed.contains(allowed.fragment())) {
                used.add(allowed);
                return;
            }
        }
        problems.add(file + ":" + number + " " + trimmed);
    }

    /**
     * The file's lines with comments blanked out and string literals (text blocks included) kept, so a role name in a
     * javadoc is ignored while one in code or in a string is not. A line starting with {@code *} is only a comment when
     * it is inside one.
     */
    static List<String> withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (source.startsWith("/*", i)) {
                i += 2;
                while (i < n && !source.startsWith("*/", i)) {
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                i = Math.min(n, i + 2);
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                out.append(source, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(n, j + 1);
                out.append(source, i, j);
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return List.of(out.toString().split("\n", -1));
    }

    /** The scanner itself: what it must see and what it must not. */
    @Test
    void theScannerSeesCodeAndStringsButNotComments() {
        List<String> lines = withoutComments("""
                /** Role.CUSTOMER in a javadoc
                 * still a comment: hasRole('X') */
                int a = 1; // Role.SALES_STAFF in a trailing comment
                String s = "WAREHOUSE_MANAGER"; /* Roles.CUSTOMER */ boolean b = u.roles().isEmpty();
                """);
        assertThat(lines.get(0)).doesNotContain("Role.CUSTOMER");
        assertThat(lines.get(1)).doesNotContain("hasRole");
        assertThat(lines.get(2)).doesNotContain("SALES_STAFF");
        assertThat(lines.get(3)).contains("\"WAREHOUSE_MANAGER\"").doesNotContain("Roles.CUSTOMER")
                .contains("u.roles().isEmpty()");

        List<String> problems = new ArrayList<>();
        Set<Allowed> used = new LinkedHashSet<>();
        for (String bad : List.of("if (\"WAREHOUSE_MANAGER\".equals(code)) {", "new SimpleGrantedAuthority(\"ROLE_X\")",
                "import static com.stockflow.common.security.Role.CUSTOMER;", "case SYSTEM_ADMIN -> true;",
                "Role.valueOf(code)", "user.roles().contains(x)", "@org.springframework.security.access.prepost"
                        + ".PreAuthorize(\"x\")", "http.hasAnyAuthority(\"A\")", "roles.stream().map(Role::name)")) {
            check("x/X.java", 1, bad, true, problems, used);
        }
        check("application.yml", 1, "      roles: ECOMMERCE_ADMIN", false, problems, used);
        assertThat(problems).hasSize(10);
    }
}
