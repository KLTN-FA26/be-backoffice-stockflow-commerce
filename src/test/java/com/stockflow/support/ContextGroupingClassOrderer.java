package com.stockflow.support;

import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.annotation.RepeatableContainers;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs the test classes that share a Spring application context one after another, so each context starts once.
 *
 * <p><b>Why.</b> Spring's test context cache is capped at eight (see {@code spring.properties}: uncapped, every
 * context of the build stayed open with its own pool and database until the machine paged), and Surefire hands the
 * classes over in filesystem order. A package's tests ask for four or five different contexts, so by the time a
 * context is asked for again it has usually been evicted, and the application starts once more: about 140 starts
 * for about 75 distinct contexts. Sorted by context, each distinct context is asked for in one run of classes, the
 * cap never evicts a context that will be needed again, and the count of starts falls to the count of contexts.
 * Raising the cap instead would bring the paging back.</p>
 *
 * <p><b>The key is Spring's own cache key.</b> Classes are grouped by their {@link MergedContextConfiguration},
 * which is exactly what the context cache compares: meta-annotations at any depth ({@link IntegrationTest},
 * {@link SecuredIntegrationTest}, {@link ProductionReceiptTest}, ...), {@code @TestPropertySource},
 * {@code @Import}, {@code @ActiveProfiles}, the set of {@code @MockitoBean} / {@code @MockitoSpyBean} overrides,
 * {@code @DataJpaTest}, {@code @ApplicationModuleTest}, {@code @DynamicPropertySource}, nested
 * {@code @TestConfiguration} classes. A key rebuilt from a list of annotations would drift from the cache the
 * first time someone used one it did not list; this one cannot.</p>
 *
 * <p>Classes without the {@link SpringExtension} (the unit tests) go first, by name; then each context's classes,
 * groups in the order of their alphabetically first class, classes by name within a group. Only the order changes:
 * which context a class gets, and what it asserts, do not. A class whose configuration cannot be resolved (it will
 * fail on its own when it runs) sorts as its own group rather than breaking discovery for everyone.</p>
 *
 * <p>It works across classes only because Surefire's JUnit Platform provider sends every class in one launcher
 * request (it does with the default single reused fork). The one line it prints, "N Spring test classes in M
 * context groups", appears once per build when that holds; if it is missing from a full run, the classes are being
 * handed over one at a time and the ordering does nothing.</p>
 *
 * <p>Registered in {@code src/test/resources/junit-platform.properties}.</p>
 */
public class ContextGroupingClassOrderer implements ClassOrderer {

    @Override
    public void orderClasses(ClassOrdererContext context) {
        List<? extends ClassDescriptor> classes = context.getClassDescriptors();
        Map<Class<?>, Object> keys = new HashMap<>();
        for (ClassDescriptor descriptor : classes) {
            Class<?> testClass = descriptor.getTestClass();
            keys.put(testClass, contextKey(testClass).orElse(NoSpring.INSTANCE));
        }

        // Each group is ranked by its alphabetically first class, so the order is stable from build to build.
        Map<Object, String> groupRank = new HashMap<>();
        keys.forEach((testClass, key) -> groupRank.merge(key, testClass.getName(),
                (a, b) -> a.compareTo(b) <= 0 ? a : b));

        Comparator<ClassDescriptor> order = Comparator
                .comparing((ClassDescriptor d) -> keys.get(d.getTestClass()) != NoSpring.INSTANCE)
                .thenComparing(d -> groupRank.get(keys.get(d.getTestClass())))
                .thenComparing(d -> d.getTestClass().getName());
        classes.sort(order);

        // JUnit also calls the orderer for each class's @Nested classes; only the top-level call is worth a line.
        long springGroups = groupRank.keySet().stream().filter(key -> key != NoSpring.INSTANCE).count();
        if (springGroups > 1) {
            long springClasses = keys.values().stream().filter(key -> key != NoSpring.INSTANCE).count();
            System.err.printf("ContextGroupingClassOrderer: %d Spring test classes in %d context groups%n",
                    springClasses, springGroups);
        }
    }

    /** The class's context cache key, or empty for a class that does not use the Spring TestContext framework. */
    private static Optional<Object> contextKey(Class<?> testClass) {
        if (!usesSpring(testClass)) return Optional.empty();
        try {
            MergedContextConfiguration configuration =
                    BootstrapUtils.resolveTestContextBootstrapper(testClass).buildMergedContextConfiguration();
            return Optional.of(configuration);
        } catch (RuntimeException | LinkageError e) {
            return Optional.of(testClass);
        }
    }

    private static boolean usesSpring(Class<?> testClass) {
        return MergedAnnotations.from(testClass, SearchStrategy.TYPE_HIERARCHY,
                        RepeatableContainers.standardRepeatables())
                .stream(ExtendWith.class)
                .anyMatch(extendWith -> Arrays.asList(extendWith.getClassArray("value")).contains(SpringExtension.class));
    }

    /** The one key every non-Spring class shares. */
    private enum NoSpring { INSTANCE }
}
