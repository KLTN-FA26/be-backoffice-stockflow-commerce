package com.stockflow.notification.internal.service;

import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Lays an {@link EmailContent} out as the HTML and the plain-text part of one message.
 *
 * <p>The HTML is {@code templates/email/customer.html}, rendered by an engine of its own rather than Boot's
 * auto-configured one: that engine serves MVC views, and a resolver added to it for mail would be a view resolver
 * too. Thymeleaf's HTML mode escapes every {@code th:text}, which is the protection against a company name carrying
 * markup into a customer's inbox.</p>
 *
 * <p>The plain text is laid out here, in Java. It has no markup to escape, and Thymeleaf's text mode keeps every line
 * break of its own directives, which makes a readable text template harder to keep right than these lines.</p>
 */
@Component
class EmailTemplates {

    static final String CUSTOMER = "customer.html";

    private final SpringTemplateEngine engine;

    EmailTemplates() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/email/");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(true);
        resolver.setCheckExistence(true);
        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
    }

    /** One message: its subject, HTML and plain text. */
    record Rendered(String subject, String html, String text) {
    }

    Rendered render(EmailContent content, Locale locale) {
        var context = new Context(locale);
        context.setVariable("mail", content);
        context.setVariable("lang", locale.getLanguage());
        return new Rendered(content.subject(), engine.process(CUSTOMER, context), text(content));
    }

    static String text(EmailContent content) {
        var text = new StringBuilder();
        paragraph(text, content.heading());
        paragraph(text, content.greeting());
        content.intro().forEach(line -> paragraph(text, line));
        if (!content.facts().isEmpty()) {
            content.facts().forEach(fact -> text.append(fact.label()).append(": ").append(fact.value()).append('\n'));
            text.append('\n');
        }
        if (content.table() != null) {
            text.append(String.join(" | ", content.table().headers())).append('\n');
            for (List<String> row : content.table().rows()) {
                text.append(String.join(" | ", row)).append('\n');
            }
            if (content.table().note() != null) {
                text.append(content.table().note()).append('\n');
            }
            text.append('\n');
        }
        content.outro().forEach(line -> paragraph(text, line));
        text.append(content.closing()).append('\n').append(content.signature()).append("\n\n");
        text.append("--\n").append(content.footer()).append('\n');
        return text.toString();
    }

    private static void paragraph(StringBuilder text, String line) {
        if (line != null && !line.isBlank()) {
            text.append(line).append("\n\n");
        }
    }
}
