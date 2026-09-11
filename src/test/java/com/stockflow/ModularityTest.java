package com.stockflow;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * <b>The test that makes the modules real.</b>
 *
 * <p>Without it, {@code inventory.internal} is a naming convention and nothing stops a developer
 * in a hurry from importing {@code StockItem} straight into {@code OrderServiceImpl}. The compiler
 * will not object — it is all one jar. This test will, in the pull request, with a message naming
 * both classes.</p>
 *
 * <p>It is the single most valuable file in the project, because it is what distinguishes a
 * modular monolith from a monolith with hopeful package names.</p>
 */
class ModularityTest {

    static final ApplicationModules MODULES = ApplicationModules.of(StockFlowApplication.class);

    /**
     * Verifies the whole structure at once:
     * <ul>
     *   <li>no module reads another module's {@code internal} packages;</li>
     *   <li>no module depends on one absent from its {@code allowedDependencies};</li>
     *   <li>no cycles between modules — the rule that stops the system decaying back into a ball
     *       of mud one convenience import at a time.</li>
     * </ul>
     *
     * <p>When it fails it prints the offending class, the class it reached for, and the module
     * boundary crossed, which is usually enough to see whether the fix is "call the service
     * instead" or "this really should be an allowed dependency".</p>
     */
    @Test
    void modulesRespectTheirBoundaries() {
        MODULES.verify();
    }

    /**
     * Prints the discovered structure. Not an assertion — it runs in CI so that a reviewer reading
     * the build log can see which modules exist and what each one is allowed to reach, without
     * opening fourteen {@code package-info.java} files.
     */
    @Test
    void writeStructureToTheBuildLog() {
        MODULES.forEach(System.out::println);
    }

    /**
     * Generates the architecture documentation from the code.
     *
     * <p>Produces, under {@code target/spring-modulith-docs}: a C4 component diagram per module, a
     * PlantUML file of the whole system, and a "module canvas" table listing each module's
     * published interface, the events it publishes and consumes, and its dependencies.</p>
     *
     * <p>Worth the one test: a hand-drawn architecture diagram is wrong within a month, and a
     * capstone report is graded on whether the diagram matches the code. This one cannot drift,
     * because it is regenerated from the code on every build.</p>
     */
    @Test
    void generateDocumentation() {
        new Documenter(MODULES)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml()
                .writeModuleCanvases();
    }
}
