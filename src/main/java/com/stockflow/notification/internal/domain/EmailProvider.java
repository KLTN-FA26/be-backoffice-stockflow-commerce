package com.stockflow.notification.internal.domain;

/**
 * Which transport delivers e-mail ({@code stockflow.notification.email.provider}). Mapped {@code EnumType.STRING} onto
 * {@code notification.delivery_log.provider}, whose CHECK lists the same values.
 *
 * <ul>
 *   <li>{@link #RESEND} - Resend's HTTP API; the default of the server profiles (dev, prod).</li>
 *   <li>{@link #SMTP} - Spring's {@code JavaMailSender}; the local profile, into Mailpit.</li>
 *   <li>{@link #RECORDING} - keeps the messages in memory and sends nothing; the test profile, so no test can reach a
 *       real inbox or a real provider.</li>
 * </ul>
 */
public enum EmailProvider { RESEND, SMTP, RECORDING }
