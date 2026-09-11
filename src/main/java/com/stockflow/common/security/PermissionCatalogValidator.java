package com.stockflow.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Spring wiring around {@link PermissionCatalogScanner}: finds the classes that declare or require
 * permissions and lets the scanner decide whether they agree.
 *
 * <p>Startup fails when the catalog and the code disagree, which turns a support ticket from the
 * warehouse three weeks later into a stack trace on somebody's laptop.</p>
 *
 * <p><b>It scans the classpath rather than walking the bean registry</b>, for two reasons. Asking
 * the context for the type of every bean definition forces {@code FactoryBean}s — Spring Data
 * repositories, the entity manager factory — to resolve earlier than they otherwise would, which
 * produces "not eligible for getting processed by all BeanPostProcessors" warnings and invites
 * ordering surprises. And a permission resource is not necessarily a bean: a resource whose
 * endpoints live on a controller that already declares a different resource has nowhere to hang
 * its own annotation except a plain declaration class, which component scanning would never see.
 * The catalog should describe the code, not the subset of it that happens to be instantiated.</p>
 */
@Configuration(proxyBeanMethods = false)
public class PermissionCatalogValidator {

    private static final Logger log = LoggerFactory.getLogger(PermissionCatalogValidator.class);

    /** Everything below this package is ours; nothing above it is worth scanning. */
    static final String BASE_PACKAGE = "com.stockflow";

    @Bean
    public PermissionCatalog permissionCatalog() {
        List<Class<?>> candidates = findCandidates();
        PermissionCatalog catalog = PermissionCatalogScanner.scan(candidates);
        log.info("Permission catalog: {} resources, {} grantable permissions, from {} classes",
                catalog.entries().size(), catalog.allPermissions().size(), candidates.size());
        return catalog;
    }

    /**
     * Classes that either declare a resource or guard an endpoint.
     *
     * <p>{@code @Controller} is included as well as {@code @PermissionResource} so that an endpoint
     * carrying {@code @RequiresPermission} on a controller that forgot its {@code @PermissionResource}
     * is still found — that omission is precisely the mistake this validator exists to catch, and
     * scanning only for the annotation would make it invisible.</p>
     */
    private List<Class<?>> findCandidates() {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(PermissionResource.class));
        // Meta-annotated: @RestController is itself annotated @Controller, so one filter covers both.
        provider.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        Set<String> classNames = new LinkedHashSet<>();
        for (BeanDefinition definition : provider.findCandidateComponents(BASE_PACKAGE)) {
            if (definition.getBeanClassName() != null) {
                classNames.add(definition.getBeanClassName());
            }
        }

        List<Class<?>> candidates = new ArrayList<>();
        for (String className : classNames) {
            try {
                candidates.add(ClassUtils.forName(className, getClass().getClassLoader()));
            } catch (ClassNotFoundException | LinkageError ex) {
                // A class the scanner can see but cannot load is not one that serves requests.
                log.debug("Skipping unloadable permission candidate {}", className, ex);
            }
        }
        return candidates;
    }
}
