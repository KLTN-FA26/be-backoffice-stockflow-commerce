package com.stockflow.fulfillment.internal.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a bean that exists only while {@code stockflow.fulfillment.ahamove.mode=fake}: the fake Ahamove, the endpoints
 * that drive it. Nothing marked so is on the class path of a live deployment's context (default mode: live).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@ConditionalOnProperty(prefix = "stockflow.fulfillment.ahamove", name = "mode", havingValue = "fake")
public @interface FakeAhamoveOnly {
}
