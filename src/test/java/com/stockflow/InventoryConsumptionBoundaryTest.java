package com.stockflow;

import com.stockflow.inventory.consumption.PickConsumption;
import com.stockflow.inventory.consumption.StageCommand;
import com.stockflow.inventory.consumption.StagedPick;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.core.NamedInterface;

import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SCRUM-275: stock is deducted in-process through {@code inventory :: consumption}, a named interface of its
 * own so that only the module entitled to say "these units left the shelf" can reach it.
 * {@link ModularityTest} proves fulfillment's use of it is legal; this pins that the interface exists,
 * carries the port, and that no other module is allowed to depend on it.
 */
class InventoryConsumptionBoundaryTest {

    @Test
    @DisplayName("inventory publishes a 'consumption' named interface holding PickConsumption, next to 'api'")
    void theNamedInterfaceExists() {
        var inventory = ModularityTest.MODULES.getModuleByName("inventory").orElseThrow();

        NamedInterface consumption = inventory.getNamedInterfaces().getByName("consumption").orElseThrow();

        assertThat(consumption.contains(PickConsumption.class)).isTrue();
        assertThat(inventory.getNamedInterfaces().getByName("api").orElseThrow().contains(PickConsumption.class))
                .as("deducting stock is not on the general inventory API").isFalse();
    }

    @Test
    @DisplayName("fulfillment is the only module allowed to depend on inventory :: consumption")
    void onlyFulfillmentMayConsume() throws Exception {
        var allowed = new TreeSet<String>();
        for (var module : ModularityTest.MODULES) {
            Class<?> info = Class.forName(module.getBasePackage().getName() + ".package-info");
            ApplicationModule declaration = info.getAnnotation(ApplicationModule.class);
            if (declaration != null && Arrays.stream(declaration.allowedDependencies())
                    .map(dependency -> dependency.replace(" ", ""))
                    .anyMatch("inventory::consumption"::equals)) {
                allowed.add(module.getName());
            }
        }

        assertThat(allowed).containsExactlyElementsOf(List.of("fulfillment"));
    }

    /**
     * Team plan 4C-5: moving a carton to a staging area, deducting it from there and putting it back are as much the
     * pick's business as the deduction itself, so they ride the same interface - and nothing about them is on the
     * general inventory API, which would let any module empty a staging area.
     */
    @Test
    @DisplayName("stage, consumeStaged and unstage, with their records, are on 'consumption' and not on 'api'")
    void stagingRidesTheConsumptionInterface() {
        var inventory = ModularityTest.MODULES.getModuleByName("inventory").orElseThrow();
        NamedInterface consumption = inventory.getNamedInterfaces().getByName("consumption").orElseThrow();
        NamedInterface api = inventory.getNamedInterfaces().getByName("api").orElseThrow();

        assertThat(Arrays.stream(PickConsumption.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName))
                .contains("stage", "consumeStaged", "unstage");
        assertThat(consumption.contains(StageCommand.class)).isTrue();
        assertThat(consumption.contains(StagedPick.class)).isTrue();
        assertThat(api.contains(StageCommand.class)).isFalse();
        assertThat(api.contains(StagedPick.class)).isFalse();
    }
}
