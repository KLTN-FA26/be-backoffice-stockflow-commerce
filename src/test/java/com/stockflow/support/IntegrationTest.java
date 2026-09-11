package com.stockflow.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation for tests that need the whole application and a real Postgres.
 *
 * <p>One annotation instead of four on every test class, and — more importantly — one place to
 * change when the setup does. The context is cached across every test class that carries it, so
 * the application starts once per build rather than once per class.</p>
 *
 * <p>Use it sparingly. A rule of thumb that holds up: if the behaviour can be tested on the
 * aggregate, test it there; reach for this only when the point of the test is the wiring —
 * transactions, event delivery, SQL.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@ActiveProfiles("test")
public @interface IntegrationTest {
}
