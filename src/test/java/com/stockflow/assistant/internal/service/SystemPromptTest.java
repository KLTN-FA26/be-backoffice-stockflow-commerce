package com.stockflow.assistant.internal.service;

import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.RequiresPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The system prompt closes with the permission each tool needs, so that the model, seeing a tool missing from the
 * user's list in the question context, can name what to ask an administrator for without first spending a request on
 * a call it knows will be refused. That list repeats what the tools' annotations say; this test keeps the two equal,
 * and a new tool fails it until the prompt names its permission.
 */
class SystemPromptTest {

    private static final String PERMISSION_LIST = "The permission each tool needs";

    @Test
    @DisplayName("the prompt names, for every tool, the permission its run method carries")
    void namesEveryToolsPermission() throws ReflectiveOperationException {
        Map<String, String> listed = permissionList(new SystemPrompt().text());
        List<Class<?>> tools = toolClasses();
        assertThat(tools).isNotEmpty();
        for (Class<?> type : tools) {
            AssistantTool tool = instantiate(type);
            String name = tool.spec().name();
            RequiresPermission guard = AnnotatedElementUtils.findMergedAnnotation(
                    type.getMethod("run", ToolArguments.class), RequiresPermission.class);
            assertThat(guard).as("%s.run carries @RequiresPermission", name).isNotNull();
            assertThat(listed).as("the prompt's permission list names %s", name).containsKey(name);
            assertThat(listed.get(name)).as("the prompt names the permission %s needs", name)
                    .contains(PermissionCode.of(guard.resource(), guard.action()).toString());
            for (PermissionCode also : tool.alsoRequires()) {
                assertThat(listed.get(name)).as("the prompt names %s, which %s also needs", also, name)
                        .contains(also.toString());
            }
            assertThat(listed.get(name).contains("company-wide"))
                    .as("the prompt marks %s company-wide exactly when its scopeLimit is COMPANY_WIDE_ONLY", name)
                    .isEqualTo(tool.scopeLimit() == ScopeLimit.COMPANY_WIDE_ONLY);
        }
    }

    /** Each tool the closing list names, with the rest of its line. */
    private static Map<String, String> permissionList(String prompt) {
        int start = prompt.indexOf(PERMISSION_LIST);
        assertThat(start).as("the prompt ends with its permission list").isNotNegative();
        Map<String, String> listed = new HashMap<>();
        for (String line : prompt.substring(start).lines().skip(1).toList()) {
            if (!line.startsWith("- ")) {
                break;
            }
            int colon = line.indexOf(": ");
            assertThat(colon).as("'%s' reads 'tool: permission'", line).isPositive();
            for (String name : line.substring(2, colon).split(",\\s*|\\s+and\\s+")) {
                listed.put(name.strip(), line.substring(colon + 2));
            }
        }
        return listed;
    }

    /** Every tool bean of the assistant module, found as Spring finds them, so a new tool is held to this too. */
    private static List<Class<?>> toolClasses() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(AssistantTool.class));
        List<Class<?>> tools = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.stockflow.assistant")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            if (type.isAnnotationPresent(Component.class)) {
                tools.add(type);
            }
        }
        return tools;
    }

    /** {@code spec()} returns a constant, so the tool's dependencies are never touched and may be null. */
    private static AssistantTool instantiate(Class<?> type) throws ReflectiveOperationException {
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return (AssistantTool) constructor.newInstance(new Object[constructor.getParameterCount()]);
    }
}
